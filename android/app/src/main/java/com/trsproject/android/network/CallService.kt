package com.trsproject.android.network

import com.trsproject.android.BuildConfig
import com.trsproject.android.ui.screens.CaptionMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class TwilioCallResult(
    val success: Boolean,
    val callSid: String? = null,
    val errorMessage: String? = null
)

class CallService(private val baseUrl: String = getHttpBaseUrl()) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun makeCall(
        toPhoneNumber: String,
        fromPhoneNumber: String? = null,
        webhookUrl: String? = null
    ): TwilioCallResult = withContext(Dispatchers.IO) {
        val urlsToTry = mutableListOf(baseUrl)
        if (baseUrl.contains("127.0.0.1")) {
            urlsToTry.add(baseUrl.replace("127.0.0.1", "10.0.2.2"))
        } else if (baseUrl.contains("10.0.2.2")) {
            urlsToTry.add(baseUrl.replace("10.0.2.2", "127.0.0.1"))
        }

        var lastError: String? = null
        for (targetUrl in urlsToTry) {
            try {
                val jsonPayload = JSONObject().apply {
                    put("toPhoneNumber", toPhoneNumber)
                    if (!fromPhoneNumber.isNullOrBlank()) {
                        put("fromPhoneNumber", fromPhoneNumber)
                    }
                    if (!webhookUrl.isNullOrBlank()) {
                        put("webhookUrl", webhookUrl)
                    }
                }

                val requestBody = jsonPayload.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url("$targetUrl/api/make-call")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBodyStr = response.body?.string()
                    val jsonResponse = if (!responseBodyStr.isNullOrBlank()) {
                        JSONObject(responseBodyStr)
                    } else null

                    if (response.isSuccessful) {
                        val status = jsonResponse?.optString("status")?.ifBlank { null } ?: "success"
                        val extractedSid = jsonResponse?.let { json ->
                            sequenceOf("callSid", "sid", "CallSid", "call_sid")
                                .map { json.optString(it, "") }
                                .firstOrNull { it.isNotBlank() }
                                ?: json.optJSONObject("call")?.optString("sid", "")?.takeIf { it.isNotBlank() }
                        }
                        val error = jsonResponse?.optString("error")?.ifBlank { null }

                        if (status == "success" && !extractedSid.isNullOrBlank()) {
                            android.util.Log.d("CallService", "makeCall successful! extractedSid=$extractedSid")
                            return@withContext TwilioCallResult(success = true, callSid = extractedSid)
                        } else {
                            android.util.Log.e("CallService", "makeCall response error: $error, rawBody=$responseBodyStr")
                            return@withContext TwilioCallResult(
                                success = false,
                                errorMessage = error ?: "Server returned success but missing CallSid"
                            )
                        }
                    } else {
                        val errorMsg = jsonResponse?.optString("error")?.ifBlank { null }
                            ?: "HTTP ${response.code}: ${response.message}"
                        return@withContext TwilioCallResult(success = false, errorMessage = errorMsg)
                    }
                }
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: "Network error occurred"
            }
        }
        TwilioCallResult(
            success = false,
            errorMessage = lastError
        )
    }

    suspend fun endCall(
        callSid: String? = null,
        toPhoneNumber: String? = null
    ): TwilioCallResult = withContext(Dispatchers.IO) {
        val urlsToTry = mutableListOf(baseUrl)
        if (baseUrl.contains("127.0.0.1")) {
            urlsToTry.add(baseUrl.replace("127.0.0.1", "10.0.2.2"))
        } else if (baseUrl.contains("10.0.2.2")) {
            urlsToTry.add(baseUrl.replace("10.0.2.2", "127.0.0.1"))
        }

        val endpointsToTry = listOf("/api/end-call", "/api/hangup", "/api/call/end", "/api/calls/end")
        var lastError: String? = null

        for (targetUrl in urlsToTry) {
            for (endpoint in endpointsToTry) {
                try {
                    val jsonPayload = JSONObject().apply {
                        if (!callSid.isNullOrBlank()) {
                            put("callSid", callSid)
                            put("sid", callSid)
                            put("CallSid", callSid)
                            put("call_sid", callSid)
                        }
                        if (!toPhoneNumber.isNullOrBlank()) {
                            put("toPhoneNumber", toPhoneNumber)
                            put("phoneNumber", toPhoneNumber)
                        }
                        put("status", "completed")
                        put("action", "hangup")
                    }

                    val requestBody = jsonPayload.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())

                    val request = Request.Builder()
                        .url("$targetUrl$endpoint")
                        .post(requestBody)
                        .build()

                    client.newCall(request).execute().use { response ->
                        val responseBodyStr = response.body?.string()
                        android.util.Log.d("CallService", "endCall endpoint $endpoint response code=${response.code}, body=$responseBodyStr")
                        if (response.isSuccessful) {
                            return@withContext TwilioCallResult(success = true, callSid = callSid)
                        } else {
                            lastError = "HTTP ${response.code}: ${response.message}"
                        }
                    }
                } catch (e: Exception) {
                    lastError = e.localizedMessage ?: "Network error occurred"
                    android.util.Log.e("CallService", "endCall exception on $endpoint: ${e.message}")
                }
            }
        }
        TwilioCallResult(
            success = false,
            errorMessage = lastError
        )
    }

    fun connectTranscriptionWebSocket(
        contactName: String,
        onTranscriptReceived: (com.trsproject.android.ui.screens.CaptionMessage) -> Unit
    ): okhttp3.WebSocket {
        val wsBaseUrl = if (baseUrl.startsWith("https://")) {
            baseUrl.replace("https://", "wss://")
        } else {
            baseUrl.replace("http://", "ws://")
        }
        val encodedContact = java.net.URLEncoder.encode(contactName, "UTF-8")
        val wsUrl = "$wsBaseUrl/transcribe?contact=$encodedContact"

        val request = Request.Builder().url(wsUrl).build()
        return client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val id = json.optInt("id", System.currentTimeMillis().toInt())
                    val senderName = json.optString("senderName", contactName)
                    val transcriptText = json.optString("text", "")
                    val isMe = json.optBoolean("isMe", false) || senderName.equals("Me", ignoreCase = true)

                    if (transcriptText.isNotBlank()) {
                        onTranscriptReceived(
                            com.trsproject.android.ui.screens.CaptionMessage(
                                id = id,
                                senderName = senderName,
                                text = transcriptText,
                                isMe = isMe
                            )
                        )
                    }
                } catch (e: Exception) {
                    onTranscriptReceived(
                        com.trsproject.android.ui.screens.CaptionMessage(
                            id = System.currentTimeMillis().toInt(),
                            senderName = contactName,
                            text = text,
                            isMe = false
                        )
                    )
                }
            }
        })
    }

    companion object {
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
