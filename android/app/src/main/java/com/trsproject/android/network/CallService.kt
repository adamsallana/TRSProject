package com.trsproject.android.network

import com.trsproject.android.BuildConfig
import com.trsproject.android.ui.screens.CaptionMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class TwilioCallResult(
    val success: Boolean,
    val callSid: String? = null,
    val errorMessage: String? = null,
    /**
     * The request timed out after being sent, so the server may still have acted on it
     * (e.g. placed the call). Callers should watch call events rather than assume failure.
     */
    val mayHaveSucceeded: Boolean = false
)

/** Last 10 digits, so "+1 (781) 869-0609" and "7818690609" compare equal. */
internal fun normalizePhoneNumber(number: String?): String =
    number.orEmpty().filter { it.isDigit() }.takeLast(10)

internal fun phoneNumbersMatch(a: String?, b: String?): Boolean {
    val na = normalizePhoneNumber(a)
    return na.isNotEmpty() && na == normalizePhoneNumber(b)
}

/**
 * Like [JSONObject.optString], but returns null for missing, JSON-null, or blank values.
 * (org.json's optString turns a JSON null into the literal string "null".)
 */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

/**
 * Call lifecycle event pushed by the server over the /transcribe WebSocket.
 * [direction] is "outgoing" or "incoming"; [status] is a Twilio CallStatus value
 * (queued, ringing, in-progress, completed, busy, no-answer, canceled, failed).
 */
data class CallEvent(
    val callSid: String,
    val direction: String,
    val status: String,
    val phoneNumber: String? = null,
    val name: String? = null
) {
    val isTerminal: Boolean get() = status in TERMINAL_STATUSES

    companion object {
        val TERMINAL_STATUSES = setOf("completed", "busy", "no-answer", "canceled", "failed")
    }
}

class CallService(private val baseUrl: String = getHttpBaseUrl()) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        // Generous: the hosted server can take a while to wake up, and Twilio API calls are slow
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // WebSockets can sit idle for long stretches (no one speaking), so no read timeout;
    // pings keep the connection alive and detect dead sockets instead.
    private val wsClient = client.newBuilder()
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** The configured base URL, plus the emulator loopback alternative when running locally. */
    private fun candidateBaseUrls(): List<String> = buildList {
        add(baseUrl)
        if (baseUrl.contains("127.0.0.1")) {
            add(baseUrl.replace("127.0.0.1", "10.0.2.2"))
        } else if (baseUrl.contains("10.0.2.2")) {
            add(baseUrl.replace("10.0.2.2", "127.0.0.1"))
        }
    }

    /**
     * POSTs [payload] to [path] and returns the parsed JSON body. Tries each candidate base URL
     * until one responds. Throws if none can be reached or the HTTP status is not 2xx.
     */
    private suspend fun postJson(path: String, payload: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (targetUrl in candidateBaseUrls()) {
            try {
                val request = Request.Builder()
                    .url("$targetUrl$path")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    val json = body?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (!response.isSuccessful) {
                        val error = json?.optString("error")?.ifBlank { null }
                            ?: "HTTP ${response.code}: ${response.message}"
                        throw IllegalStateException(error)
                    }
                    return@withContext json
                }
            } catch (e: IllegalStateException) {
                throw e // Server answered with an error; don't retry on another host
            } catch (e: java.net.SocketTimeoutException) {
                // The request may have reached the server; retrying could e.g. place a second call
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Network error occurred")
    }

    /** Calls an endpoint that replies `{status: "success" | "error", error?}`. */
    private suspend fun postCallAction(path: String, callSid: String): TwilioCallResult = try {
        val json = postJson(path, JSONObject().put("callSid", callSid))
        val status = json?.optString("status")?.ifBlank { null }
        if (status == "success") {
            TwilioCallResult(success = true, callSid = callSid)
        } else {
            TwilioCallResult(
                success = false,
                callSid = callSid,
                errorMessage = json?.optString("error")?.ifBlank { null } ?: "Unexpected server response"
            )
        }
    } catch (e: Exception) {
        android.util.Log.e("CallService", "$path failed: ${e.message}")
        TwilioCallResult(success = false, callSid = callSid, errorMessage = e.localizedMessage ?: "Network error occurred")
    }

    /** "&[param]=<this user's line number>", or "" if they don't have one. */
    private fun lineQuery(param: String): String =
        lineNumber?.takeIf { it.isNotBlank() }
            ?.let { "&$param=" + java.net.URLEncoder.encode(it, "UTF-8") }
            .orEmpty()

    suspend fun makeCall(
        toPhoneNumber: String,
        fromPhoneNumber: String? = lineNumber,
        webhookUrl: String? = null
    ): TwilioCallResult {
        val payload = JSONObject().apply {
            put("toPhoneNumber", toPhoneNumber)
            if (!fromPhoneNumber.isNullOrBlank()) put("fromPhoneNumber", fromPhoneNumber)
            if (!webhookUrl.isNullOrBlank()) put("webhookUrl", webhookUrl)
        }
        return try {
            val json = postJson("/api/make-call", payload)
            val status = json?.optString("status")?.ifBlank { null } ?: "success"
            val extractedSid = json?.let { j ->
                sequenceOf("callSid", "sid", "CallSid", "call_sid")
                    .map { j.optString(it, "") }
                    .firstOrNull { it.isNotBlank() }
                    ?: j.optJSONObject("call")?.optString("sid", "")?.takeIf { it.isNotBlank() }
            }
            val error = json?.optString("error")?.ifBlank { null }

            if (status == "success" && !extractedSid.isNullOrBlank()) {
                android.util.Log.d("CallService", "makeCall successful! callSid=$extractedSid")
                TwilioCallResult(success = true, callSid = extractedSid)
            } else {
                android.util.Log.e("CallService", "makeCall response error: $error, body=$json")
                TwilioCallResult(success = false, errorMessage = error ?: "Server returned success but missing CallSid")
            }
        } catch (e: java.net.SocketTimeoutException) {
            android.util.Log.w("CallService", "makeCall timed out; the call may still have been placed")
            TwilioCallResult(success = false, errorMessage = "The server took too long to respond", mayHaveSucceeded = true)
        } catch (e: Exception) {
            TwilioCallResult(success = false, errorMessage = e.localizedMessage ?: "Network error occurred")
        }
    }

    /** Hangs up [callSid]. The server cancels it if still ringing, or completes it if connected. */
    suspend fun endCall(callSid: String): TwilioCallResult = postCallAction("/api/end-call", callSid)

    /** Connects a ringing incoming call to the transcription loop. */
    suspend fun acceptIncomingCall(callSid: String): TwilioCallResult =
        postCallAction("/api/incoming-call/accept", callSid)

    /** Rejects a ringing incoming call. */
    suspend fun declineIncomingCall(callSid: String): TwilioCallResult =
        postCallAction("/api/incoming-call/decline", callSid)

    /** Asks the server to broadcast a fake incoming call (sid prefixed with TEST-). */
    suspend fun triggerTestIncomingCall(): Boolean = withContext(Dispatchers.IO) {
        for (targetUrl in candidateBaseUrls()) {
            try {
                val url = "$targetUrl/api/trigger-incoming-call" + lineQuery("to").replaceFirst('&', '?')
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { if (it.isSuccessful) return@withContext true }
            } catch (e: Exception) {
                android.util.Log.e("CallService", "triggerTestIncomingCall failed on $targetUrl: ${e.message}")
            }
        }
        false
    }

    private val wsBaseUrl: String
        get() = if (baseUrl.startsWith("https://")) {
            baseUrl.replace("https://", "wss://")
        } else {
            baseUrl.replace("http://", "ws://")
        }

    /**
     * Opens the socket that carries the user's mic audio into call [callSid]. Send binary frames
     * of 16-bit little-endian mono PCM at [sampleRate] (a multiple of 8000).
     */
    fun connectCallAudioWebSocket(
        callSid: String,
        sampleRate: Int,
        onOpened: () -> Unit,
        onDropped: (Throwable?) -> Unit
    ): okhttp3.WebSocket {
        val encodedSid = java.net.URLEncoder.encode(callSid, "UTF-8")
        val request = Request.Builder()
            .url("$wsBaseUrl/call-audio?callSid=$encodedSid&sampleRate=$sampleRate")
            .build()
        return wsClient.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) = onOpened()

            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) = onDropped(null)

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                android.util.Log.e("CallService", "Call audio socket failure: ${t.message}")
                onDropped(t)
            }
        })
    }

    /**
     * Opens the server WebSocket. Caption messages go to [onTranscriptReceived], call lifecycle
     * events go to [onCallEvent].
     */
    fun connectTranscriptionWebSocket(
        contactName: String,
        onCallEvent: (CallEvent) -> Unit = {},
        onConnected: () -> Unit = {},
        onDisconnected: (Throwable?) -> Unit = {},
        onTranscriptReceived: (CaptionMessage) -> Unit = {}
    ): okhttp3.WebSocket {
        val encodedContact = java.net.URLEncoder.encode(contactName, "UTF-8")
        val wsUrl = "$wsBaseUrl/transcribe?contact=$encodedContact" + lineQuery("number")

        val request = Request.Builder().url(wsUrl).build()
        return wsClient.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                onConnected()
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    // Plain-text frame: show it as a caption from the other party
                    onTranscriptReceived(
                        CaptionMessage(
                            id = System.currentTimeMillis().toInt(),
                            senderName = contactName,
                            text = text,
                            isMe = false
                        )
                    )
                    return
                }

                when (json.optString("type", "transcript")) {
                    "call_event" -> {
                        val callSid = json.optString("callSid")
                        val status = json.optString("status")
                        if (callSid.isNotBlank() && status.isNotBlank()) {
                            onCallEvent(
                                CallEvent(
                                    callSid = callSid,
                                    direction = json.optString("direction", "outgoing"),
                                    status = status,
                                    phoneNumber = json.optStringOrNull("phoneNumber"),
                                    name = json.optStringOrNull("name")
                                )
                            )
                        }
                    }

                    "transcript" -> {
                        val id = json.optInt("id", System.currentTimeMillis().toInt())
                        val senderName = json.optStringOrNull("senderName") ?: contactName
                        val transcriptText = json.optString("text", "")
                        val isMe = json.optBoolean("isMe", false) || senderName.equals("Me", ignoreCase = true)
                        if (transcriptText.isNotBlank()) {
                            onTranscriptReceived(
                                CaptionMessage(
                                    id = id,
                                    senderName = senderName,
                                    text = transcriptText,
                                    isMe = isMe,
                                    isFinal = json.optBoolean("isFinal", true)
                                )
                            )
                        }
                    }

                    else -> Unit
                }
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                android.util.Log.e("CallService", "WebSocket failure: ${t.message}")
                onDisconnected(t)
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                onDisconnected(null)
            }
        })
    }

    /**
     * Stream of call events for as long as it's collected. Closes the socket on cancellation.
     * Completes with an error if the socket drops, so callers can `retryWhen` to reconnect.
     */
    fun callEvents(): Flow<CallEvent> = callbackFlow {
        val socket = connectTranscriptionWebSocket(
            contactName = "Client",
            onCallEvent = { trySend(it) },
            onDisconnected = { cause -> close(cause ?: IllegalStateException("Call event socket closed")) }
        )
        awaitClose { socket.close(1000, "Stopped listening for call events") }
    }

    companion object {
        /**
         * The signed-in user's own Twilio number (their profile's TRS relay number). The server
         * only sends this app the calls and captions for this number, and outgoing calls use it as
         * caller ID. Null means the server's shared default number. Set at sign-in.
         */
        @Volatile
        var lineNumber: String? = null

        private fun getHttpBaseUrl(): String {
            val wsUrl = try {
                BuildConfig.SERVER_WS_URL
            } catch (e: Exception) {
                "ws://10.0.2.2:8080/transcribe"
            }
            return wsUrl
                .replace("wss://", "https://")
                .replace("ws://", "http://")
                .removeSuffix("/transcribe")
                .removeSuffix("/")
        }
    }
}
