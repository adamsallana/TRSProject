package com.trsproject.server

import com.twilio.Twilio
import com.twilio.http.HttpMethod
import com.twilio.rest.api.v2010.account.Call
import com.twilio.type.PhoneNumber
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class MakeCallRequest(
    val toPhoneNumber: String,
    val fromPhoneNumber: String? = null,
    val webhookUrl: String? = null
)

@Serializable
data class MakeCallResponse(
    val status: String,
    val callSid: String? = null,
    val error: String? = null
)

@Serializable
data class CallSidRequest(
    val callSid: String? = null,
    val sid: String? = null,
    val call_sid: String? = null
) {
    fun resolvedSid(): String? = (callSid ?: sid ?: call_sid)?.takeIf { it.isNotBlank() }
}

@Serializable
data class SimpleResponse(
    val status: String,
    val error: String? = null
)

/** A line of live captions. `type` lets clients tell captions apart from call events. */
@Serializable
data class TranscriptMessage(
    val id: Int,
    val senderName: String,
    val text: String,
    val isMe: Boolean,
    /** False for interim text that a later message with the same id will replace. */
    val isFinal: Boolean = true,
    val type: String = "transcript"
)

/**
 * Call lifecycle event pushed to apps over the /transcribe WebSocket.
 * direction: "outgoing" | "incoming"
 * status: Twilio CallStatus values (queued, ringing, in-progress, completed, busy,
 *         no-answer, canceled, failed).
 */
@Serializable
data class CallEvent(
    val callSid: String,
    val direction: String,
    val status: String,
    val phoneNumber: String? = null,
    val name: String? = null,
    val type: String = "call_event"
)

private data class TrackedCall(val direction: String, val phoneNumber: String?)

// encodeDefaults so the `type` discriminator is always sent
private val wireJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val activeSessions = Collections.synchronizedList(mutableListOf<DefaultWebSocketServerSession>())
private val messageIdCounter = AtomicInteger(1)
private val trackedCalls = ConcurrentHashMap<String, TrackedCall>()

private val publicBaseUrl: String =
    (System.getenv("PUBLIC_BASE_URL") ?: "https://trsproject.onrender.com").removeSuffix("/")

private val TERMINAL_STATUSES = setOf("completed", "busy", "no-answer", "canceled", "failed")

private const val TEST_SID_PREFIX = "TEST-"

private fun toE164(number: String?): String {
    if (number.isNullOrBlank()) return ""
    val trimmed = number.trim()
    val digits = trimmed.filter { it.isDigit() }
    return when {
        trimmed.startsWith("+") -> "+$digits"
        digits.length == 10 -> "+1$digits"
        digits.length == 11 && digits.startsWith("1") -> "+$digits"
        else -> if (digits.isNotBlank()) "+$digits" else trimmed
    }
}

/**
 * How the remote party's speech is captioned:
 * - REALTIME (default): Twilio Real-Time Transcription streams interim + final text for the
 *   whole call with no gaps between turns.
 * - GATHER: the older <Gather> loop (one request per utterance). Set CAPTION_MODE=gather to use it.
 *   Also used automatically if a real-time transcription session reports an error.
 */
private enum class CaptionMode { REALTIME, GATHER }

private val captionMode: CaptionMode =
    if (System.getenv("CAPTION_MODE").equals("gather", ignoreCase = true)) CaptionMode.GATHER else CaptionMode.REALTIME

// Which audio track is the remote party. For calls on a Twilio number (REST-placed or inbound),
// the phone party's audio arrives at Twilio on the inbound track.
private val transcriptionTrack: String = System.getenv("TRANSCRIPTION_TRACK")?.takeIf { it.isNotBlank() } ?: "inbound_track"

// speechTimeout must be an integer (not "auto") when speechModel is set
private val GATHER_TWIML = """
    <?xml version="1.0" encoding="UTF-8"?>
    <Response>
        <Gather input="speech" action="/api/twilio-speech-callback" method="POST"
                speechTimeout="1" timeout="5" speechModel="phone_call" enhanced="true"
                actionOnEmptyResult="false"
                partialResultCallback="$publicBaseUrl/api/twilio-speech-partial"
                partialResultCallbackMethod="POST" />
        <Redirect method="POST">/api/twiml/gather</Redirect>
    </Response>
""".trimIndent()

// Keeps the call open while the real-time transcription runs. Long pauses rather than frequent
// redirects; the session runs until the call ends.
private val HOLD_TWIML = """
    <?xml version="1.0" encoding="UTF-8"?>
    <Response>
        <Pause length="3600"/>
        <Redirect method="POST">/api/twiml/hold</Redirect>
    </Response>
""".trimIndent()

private val REALTIME_TWIML = """
    <?xml version="1.0" encoding="UTF-8"?>
    <Response>
        <Start>
            <Transcription name="captions"
                           statusCallbackUrl="$publicBaseUrl/api/transcription-callback"
                           track="$transcriptionTrack"
                           partialResults="true"
                           languageCode="en-US"
                           enableAutomaticPunctuation="true" />
        </Start>
        <Pause length="3600"/>
        <Redirect method="POST">/api/twiml/hold</Redirect>
    </Response>
""".trimIndent()

private fun callTwiml(): String = when (captionMode) {
    CaptionMode.REALTIME -> REALTIME_TWIML
    CaptionMode.GATHER -> GATHER_TWIML
}

/**
 * Groups interim and final transcription segments into utterances so each utterance keeps one
 * message id. The app replaces the bubble with that id as partials refine, instead of appending.
 */
private class UtteranceTracker {
    private var currentId: Int? = null
    private var lastSeq = Long.MIN_VALUE

    /**
     * Returns the message id for this segment, or null if it's stale (arrived out of order).
     * [resetSequenceOnFinal]: Gather numbers partials per <Gather>, so the count restarts each utterance;
     * real-time transcription numbers events across the whole session, so it doesn't.
     */
    @Synchronized
    fun idFor(seq: Long?, isFinal: Boolean, resetSequenceOnFinal: Boolean = false): Int? {
        if (seq != null) {
            if (seq <= lastSeq) return null
            lastSeq = seq
        }
        val id = currentId ?: messageIdCounter.getAndIncrement().also { currentId = it }
        if (isFinal) {
            currentId = null
            if (resetSequenceOnFinal) lastSeq = Long.MIN_VALUE
        }
        return id
    }
}

private val utterances = ConcurrentHashMap<String, UtteranceTracker>()

private fun utteranceTracker(callSid: String) = utterances.getOrPut(callSid) { UtteranceTracker() }

/** Broadcasts one segment of the remote party's speech. Blank finals just close the utterance. */
private suspend fun broadcastRemoteSpeech(
    callSid: String,
    text: String?,
    isFinal: Boolean,
    seq: Long?,
    resetSequenceOnFinal: Boolean = false
) {
    val id = utteranceTracker(callSid).idFor(seq, isFinal, resetSequenceOnFinal) ?: return
    if (text.isNullOrBlank()) return
    broadcastTranscript(
        TranscriptMessage(
            id = id,
            senderName = "Contact",
            text = text.trim(),
            isMe = false,
            isFinal = isFinal
        )
    )
}

// Keeps an incoming caller waiting while the app rings. After ~30s with no answer
// Twilio follows the redirect and we report a missed call.
private val INCOMING_HOLD_TWIML = """
    <?xml version="1.0" encoding="UTF-8"?>
    <Response>
        <Say>Please hold while we connect your call.</Say>
        <Pause length="30"/>
        <Redirect method="POST">/api/incoming-call/timeout</Redirect>
    </Response>
""".trimIndent()

private val INCOMING_TIMEOUT_TWIML = """
    <?xml version="1.0" encoding="UTF-8"?>
    <Response>
        <Say>Sorry, no one is available to take your call.</Say>
        <Hangup/>
    </Response>
""".trimIndent()

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, host = "0.0.0.0", port = port, module = Application::module).start(wait = true)
}

private suspend fun broadcastRaw(jsonStr: String) {
    val sessions = synchronized(activeSessions) { activeSessions.toList() }
    println(">>> [SERVER] BROADCASTING TO ${sessions.size} CLIENTS: $jsonStr")
    for (session in sessions) {
        try {
            session.send(Frame.Text(jsonStr))
        } catch (e: Exception) {
            println(">>> [SERVER] ERROR SENDING TO WEBSOCKET: ${e.message}")
        }
    }
}

suspend fun broadcastTranscript(message: TranscriptMessage) =
    broadcastRaw(wireJson.encodeToString(message))

suspend fun broadcastCallEvent(event: CallEvent) =
    broadcastRaw(wireJson.encodeToString(event))

/** Initializes Twilio from env vars. Returns false if credentials are missing. */
private fun initTwilio(): Boolean {
    val sid = System.getenv("TWILIO_ACCOUNT_SID")
    val token = System.getenv("TWILIO_AUTH_TOKEN")
    if (sid.isNullOrBlank() || token.isNullOrBlank()) return false
    Twilio.init(sid, token)
    return true
}

private const val MISSING_CREDENTIALS =
    "Twilio credentials (TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN) are not configured on the server."

/** Reads a callSid from a JSON body, falling back to the `callSid`/`sid` query parameters. */
private suspend fun RoutingContext.receiveCallSid(): String? {
    val fromBody = try {
        call.receive<CallSidRequest>().resolvedSid()
    } catch (e: Exception) {
        null
    }
    return fromBody
        ?: call.request.queryParameters["callSid"]?.takeIf { it.isNotBlank() }
        ?: call.request.queryParameters["sid"]?.takeIf { it.isNotBlank() }
}

fun Application.module() {
    install(WebSockets)
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }

    initTwilio()

    routing {
        // WebSocket endpoint for live captions and call events
        webSocket("/transcribe") {
            val contactName = call.request.queryParameters["contact"] ?: "Contact"
            activeSessions.add(this)
            println(">>> [SERVER] ANDROID APP CONNECTED TO WEBSOCKET for contact: '$contactName' (Active Sessions: ${activeSessions.size})")
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Binary -> {
                            frame.readBytes()
                            // Binary mic audio frames received from Android client (unused for now)
                        }
                        is Frame.Text -> {
                            val text = frame.readText()
                            if (text.isBlank()) continue
                            val parsed = try {
                                wireJson.decodeFromString<TranscriptMessage>(text)
                            } catch (e: Exception) {
                                null
                            }
                            when {
                                parsed != null -> broadcastTranscript(parsed)
                                // Don't echo unrecognised JSON (e.g. control messages) as captions
                                text.trimStart().startsWith("{") ->
                                    println(">>> [SERVER] IGNORING NON-TRANSCRIPT JSON FRAME: $text")
                                else -> broadcastTranscript(
                                    TranscriptMessage(
                                        id = messageIdCounter.getAndIncrement(),
                                        senderName = "Me",
                                        text = text,
                                        isMe = true
                                    )
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                println(">>> [SERVER] WEBSOCKET SESSION DISCONNECTED: ${e.message}")
            } finally {
                activeSessions.remove(this)
                println(">>> [SERVER] WEBSOCKET CLIENT REMOVED (Remaining Sessions: ${activeSessions.size})")
            }
        }

        // TwiML for a connected call (outgoing calls and accepted incoming calls): starts captioning
        get("/api/twiml") {
            call.respondText(callTwiml(), ContentType.Application.Xml)
        }
        post("/api/twiml") {
            call.respondText(callTwiml(), ContentType.Application.Xml)
        }
        // Gather loop, used by CAPTION_MODE=gather and as the fallback if real-time transcription fails
        post("/api/twiml/gather") {
            call.respondText(GATHER_TWIML, ContentType.Application.Xml)
        }
        // Keeps a real-time-transcribed call open without restarting the transcription
        post("/api/twiml/hold") {
            call.respondText(HOLD_TWIML, ContentType.Application.Xml)
        }

        // Real-Time Transcription events: interim and final text for the remote party
        post("/api/transcription-callback") {
            val params = call.receiveParameters()
            val callSid = params["CallSid"]
            val event = params["TranscriptionEvent"]

            when (event) {
                "transcription-content" -> if (callSid != null) {
                    val text = params["TranscriptionData"]?.let { data ->
                        try {
                            wireJson.parseToJsonElement(data).jsonObject["transcript"]?.jsonPrimitive?.contentOrNull
                        } catch (e: Exception) {
                            println(">>> [SERVER] BAD TranscriptionData: $data")
                            null
                        }
                    }
                    val isFinal = params["Final"]?.equals("true", ignoreCase = true) ?: true
                    broadcastRemoteSpeech(callSid, text, isFinal, params["SequenceId"]?.toLongOrNull())
                    if (isFinal) {
                        println(">>> [SERVER] TRANSCRIPT (track=${params["Track"]}): '$text'")
                    }
                }

                "transcription-started" ->
                    println(">>> [SERVER] REAL-TIME TRANSCRIPTION STARTED: sid=$callSid track=${params["Track"]} engine=${params["TranscriptionEngine"]}")

                "transcription-stopped" -> {
                    println(">>> [SERVER] REAL-TIME TRANSCRIPTION STOPPED: sid=$callSid")
                    callSid?.let { utterances.remove(it) }
                }

                "transcription-error" -> {
                    println(">>> [SERVER] REAL-TIME TRANSCRIPTION ERROR: sid=$callSid code=${params["TranscriptionErrorCode"]} error=${params["TranscriptionError"]}")
                    // Keep captions working: switch this call to the Gather loop
                    if (callSid != null && !callSid.startsWith(TEST_SID_PREFIX) && initTwilio()) {
                        try {
                            Call.updater(callSid)
                                .setUrl(URI.create("$publicBaseUrl/api/twiml/gather"))
                                .setMethod(HttpMethod.POST)
                                .update()
                            println(">>> [SERVER] FELL BACK TO GATHER CAPTIONING: sid=$callSid")
                        } catch (e: Exception) {
                            println(">>> [SERVER] GATHER FALLBACK FAILED: ${e.message}")
                        }
                    }
                }

                else -> println(">>> [SERVER] UNKNOWN TRANSCRIPTION EVENT: $event")
            }
            call.respond(HttpStatusCode.NoContent)
        }

        // Gather mode: interim text while the remote party is still speaking (can't return TwiML)
        post("/api/twilio-speech-partial") {
            val params = call.receiveParameters()
            val callSid = params["CallSid"]
            if (callSid != null) {
                broadcastRemoteSpeech(
                    callSid,
                    params["UnstableSpeechResult"],
                    isFinal = false,
                    seq = params["SequenceNumber"]?.toLongOrNull()
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }

        // Gather mode: final text for one utterance
        post("/api/twilio-speech-callback") {
            val params = call.receiveParameters()
            val callSid = params["CallSid"]
            val speechResult = params["SpeechResult"]
            println(">>> [SERVER] RECEIVED TWILIO SPEECH CALLBACK: speechResult='$speechResult'")

            if (callSid != null) {
                // No sequence number on the final callback; it always closes the current utterance
                broadcastRemoteSpeech(callSid, speechResult, isFinal = true, seq = null, resetSequenceOnFinal = true)
            }

            // Continue gathering speech without hanging up
            call.respondText(GATHER_TWIML, ContentType.Application.Xml)
        }

        // Twilio status callback for call progress (outgoing calls are registered in /api/make-call;
        // for incoming calls, set this URL as the number's "Call status changes" webhook in the
        // Twilio console so the app learns when a caller hangs up while ringing).
        post("/api/call-status") {
            val params = call.receiveParameters()
            val callSid = params["CallSid"]
            val status = params["CallStatus"]
            println(">>> [SERVER] CALL STATUS CALLBACK: sid=$callSid status=$status")

            if (!callSid.isNullOrBlank() && !status.isNullOrBlank()) {
                val tracked = trackedCalls[callSid]
                val direction = tracked?.direction
                    ?: if (params["Direction"] == "inbound") "incoming" else "outgoing"
                val phoneNumber = tracked?.phoneNumber
                    ?: if (direction == "incoming") params["From"] else params["To"]

                broadcastCallEvent(CallEvent(callSid, direction, status, phoneNumber))
                if (status in TERMINAL_STATUSES) {
                    trackedCalls.remove(callSid)
                    utterances.remove(callSid)
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }

        // Twilio webhook when someone calls our Twilio number. The caller is held
        // (not connected to transcription) until the app user accepts.
        post("/api/incoming-call") {
            val params = call.receiveParameters()
            val callSid = params["CallSid"]
            val callerNumber = params["From"] ?: "Unknown"
            println(">>> [SERVER] INCOMING CALL RECEIVED FROM: $callerNumber (sid=$callSid)")

            if (!callSid.isNullOrBlank()) {
                trackedCalls[callSid] = TrackedCall("incoming", callerNumber)
                broadcastCallEvent(CallEvent(callSid, "incoming", "ringing", callerNumber))
            }
            call.respondText(INCOMING_HOLD_TWIML, ContentType.Application.Xml)
        }

        // Reached when the hold pause runs out without the app user answering
        post("/api/incoming-call/timeout") {
            val params = call.receiveParameters()
            val callSid = params["CallSid"]
            println(">>> [SERVER] INCOMING CALL TIMED OUT: sid=$callSid")
            if (!callSid.isNullOrBlank()) {
                val tracked = trackedCalls.remove(callSid)
                broadcastCallEvent(CallEvent(callSid, "incoming", "no-answer", tracked?.phoneNumber ?: params["From"]))
            }
            call.respondText(INCOMING_TIMEOUT_TWIML, ContentType.Application.Xml)
        }

        // App user accepted the incoming call: move the caller into the transcription loop
        post("/api/incoming-call/accept") {
            val callSid = receiveCallSid()
            if (callSid == null) {
                call.respond(SimpleResponse(status = "error", error = "No callSid provided."))
                return@post
            }
            val phoneNumber = trackedCalls[callSid]?.phoneNumber

            if (!callSid.startsWith(TEST_SID_PREFIX)) {
                if (!initTwilio()) {
                    call.respond(SimpleResponse(status = "error", error = MISSING_CREDENTIALS))
                    return@post
                }
                try {
                    Call.updater(callSid)
                        .setUrl(URI.create("$publicBaseUrl/api/twiml"))
                        .setMethod(HttpMethod.POST)
                        .update()
                } catch (e: Exception) {
                    println(">>> [SERVER] FAILED TO ACCEPT INCOMING CALL: ${e.message}")
                    call.respond(SimpleResponse(status = "error", error = "Failed to accept call: ${e.message}"))
                    return@post
                }
            }

            println(">>> [SERVER] INCOMING CALL ACCEPTED: sid=$callSid")
            broadcastCallEvent(CallEvent(callSid, "incoming", "in-progress", phoneNumber))
            call.respond(SimpleResponse(status = "success"))
        }

        // App user declined the incoming call: hang up on the caller
        post("/api/incoming-call/decline") {
            val callSid = receiveCallSid()
            if (callSid == null) {
                call.respond(SimpleResponse(status = "error", error = "No callSid provided."))
                return@post
            }
            val tracked = trackedCalls.remove(callSid)

            if (!callSid.startsWith(TEST_SID_PREFIX)) {
                if (!initTwilio()) {
                    call.respond(SimpleResponse(status = "error", error = MISSING_CREDENTIALS))
                    return@post
                }
                try {
                    Call.updater(callSid).setStatus(Call.UpdateStatus.COMPLETED).update()
                } catch (e: Exception) {
                    println(">>> [SERVER] FAILED TO DECLINE INCOMING CALL: ${e.message}")
                    call.respond(SimpleResponse(status = "error", error = "Failed to decline call: ${e.message}"))
                    return@post
                }
            }

            println(">>> [SERVER] INCOMING CALL DECLINED: sid=$callSid")
            broadcastCallEvent(CallEvent(callSid, "incoming", "canceled", tracked?.phoneNumber))
            call.respond(SimpleResponse(status = "success"))
        }

        // Simulates an incoming call for testing (no real Twilio call; accept/decline are no-ops)
        get("/api/trigger-incoming-call") {
            val callSid = TEST_SID_PREFIX + UUID.randomUUID()
            val callerNumber = call.request.queryParameters["from"] ?: "+17818690609"
            trackedCalls[callSid] = TrackedCall("incoming", callerNumber)
            broadcastCallEvent(CallEvent(callSid, "incoming", "ringing", callerNumber))
            call.respondText("Incoming call triggered! sid=$callSid", ContentType.Text.Plain)
        }

        // Endpoint to initiate a call via Twilio REST API
        post("/api/make-call") {
            val request = try {
                call.receive<MakeCallRequest>()
            } catch (e: Exception) {
                call.respond(
                    MakeCallResponse(status = "error", error = "Invalid request format: ${e.message}")
                )
                return@post
            }

            if (!initTwilio()) {
                call.respond(MakeCallResponse(status = "error", error = MISSING_CREDENTIALS))
                return@post
            }

            try {
                val envFromNumber = System.getenv("TWILIO_PHONE_NUMBER")?.takeIf { it.isNotBlank() }
                val rawFromNumber = request.fromPhoneNumber?.takeIf { it.isNotBlank() } ?: envFromNumber

                if (rawFromNumber.isNullOrBlank()) {
                    call.respond(
                        MakeCallResponse(
                            status = "error",
                            error = "No phone number specified for call initiation. Please configure your phone number in Account Settings."
                        )
                    )
                    return@post
                }

                val formattedTo = toE164(request.toPhoneNumber)
                val formattedFrom = toE164(rawFromNumber)

                val webhook = request.webhookUrl?.takeIf { it.isNotBlank() }
                    ?: "$publicBaseUrl/api/twiml"

                val twilioCall = Call.creator(
                    PhoneNumber(formattedTo),
                    PhoneNumber(formattedFrom),
                    URI.create(webhook)
                )
                    .setStatusCallback(URI.create("$publicBaseUrl/api/call-status"))
                    .setStatusCallbackEvent(listOf("initiated", "ringing", "answered", "completed"))
                    .setStatusCallbackMethod(HttpMethod.POST)
                    .create()

                trackedCalls[twilioCall.sid] = TrackedCall("outgoing", formattedTo)

                call.respond(
                    MakeCallResponse(
                        status = "success",
                        callSid = twilioCall.sid
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    MakeCallResponse(
                        status = "error",
                        error = "Failed to initiate call via Twilio: ${e.message}"
                    )
                )
            }
        }

        // Ends a call. Ringing/queued calls are canceled; connected calls are completed.
        post("/api/end-call") {
            val callSid = receiveCallSid()
            if (callSid == null) {
                println(">>> [SERVER] END CALL WARNING: No callSid provided in request")
                call.respond(SimpleResponse(status = "error", error = "No callSid provided to end call."))
                return@post
            }

            if (callSid.startsWith(TEST_SID_PREFIX)) {
                trackedCalls.remove(callSid)
                call.respond(SimpleResponse(status = "success"))
                return@post
            }

            if (!initTwilio()) {
                println(">>> [SERVER] END CALL FAILED: Twilio credentials not configured")
                call.respond(SimpleResponse(status = "error", error = MISSING_CREDENTIALS))
                return@post
            }

            try {
                val currentStatus = Call.fetcher(callSid).fetch().status
                val newStatus = when (currentStatus) {
                    Call.Status.QUEUED, Call.Status.RINGING -> Call.UpdateStatus.CANCELED
                    else -> Call.UpdateStatus.COMPLETED
                }
                val updatedCall = Call.updater(callSid).setStatus(newStatus).update()
                println(">>> [SERVER] CALL ENDED via Twilio REST API: sid=${updatedCall.sid}, was=$currentStatus, now=${updatedCall.status}")

                call.respond(SimpleResponse(status = "success"))
            } catch (e: Exception) {
                println(">>> [SERVER] FAILED TO END CALL VIA TWILIO: ${e.message}")
                call.respond(SimpleResponse(status = "error", error = "Failed to end call via Twilio: ${e.message}"))
            }
        }
    }
}
