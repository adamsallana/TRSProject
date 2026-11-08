package com.trsproject.server

import com.twilio.Twilio
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Collections
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
data class TranscriptMessage(
    val id: Int,
    val senderName: String,
    val text: String,
    val isMe: Boolean
)

private val activeSessions = Collections.synchronizedList(mutableListOf<DefaultWebSocketServerSession>())
private val messageIdCounter = AtomicInteger(1)

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, host = "0.0.0.0", port = port, module = Application::module).start(wait = true)
}

suspend fun broadcastTranscript(message: TranscriptMessage) {
    val jsonStr = Json.encodeToString(message)
    val sessions = synchronized(activeSessions) { activeSessions.toList() }
    println(">>> [SERVER] BROADCASTING TRANSCRIPT TO ${sessions.size} CLIENTS: $jsonStr")
    for (session in sessions) {
        try {
            session.send(Frame.Text(jsonStr))
        } catch (e: Exception) {
            println(">>> [SERVER] ERROR SENDING TO WEBSOCKET: ${e.message}")
        }
    }
}

fun Application.module() {
    install(WebSockets)
    install(ContentNegotiation) {
        json()
    }

    val accountSid = System.getenv("TWILIO_ACCOUNT_SID")
    val authToken = System.getenv("TWILIO_AUTH_TOKEN")
    val defaultFromNumber = "+12182745801"

    if (!accountSid.isNullOrBlank() && !authToken.isNullOrBlank()) {
        Twilio.init(accountSid, authToken)
    }

    routing {
        // WebSocket endpoint for live dual-sided call transcriptions
        webSocket("/transcribe") {
            val contactName = call.request.queryParameters["contact"] ?: "Contact"
            activeSessions.add(this)
            println(">>> [SERVER] ANDROID APP CONNECTED TO WEBSOCKET for contact: '$contactName' (Active Sessions: ${activeSessions.size})")
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Binary -> {
                            val audioChunk = frame.readBytes()
                            // Binary mic audio frames received from Android client
                        }
                        is Frame.Text -> {
                            val text = frame.readText()
                            if (text.isNotBlank()) {
                                try {
                                    val customMsg = Json.decodeFromString<TranscriptMessage>(text)
                                    broadcastTranscript(customMsg)
                                } catch (e: Exception) {
                                    val simpleMsg = TranscriptMessage(
                                        id = messageIdCounter.getAndIncrement(),
                                        senderName = "Me",
                                        text = text,
                                        isMe = true
                                    )
                                    broadcastTranscript(simpleMsg)
                                }
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

        // TwiML endpoint for initiating active calls without sample test prompts
        get("/api/twiml") {
            val twiml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Response>
                    <Gather input="speech" action="/api/twilio-speech-callback" method="POST" speechTimeout="auto" timeout="5" />
                    <Redirect>/api/twiml</Redirect>
                </Response>
            """.trimIndent()
            call.respondText(twiml, ContentType.Application.Xml)
        }
        post("/api/twiml") {
            val twiml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Response>
                    <Gather input="speech" action="/api/twilio-speech-callback" method="POST" speechTimeout="auto" timeout="5" />
                    <Redirect>/api/twiml</Redirect>
                </Response>
            """.trimIndent()
            call.respondText(twiml, ContentType.Application.Xml)
        }

        // Endpoint to receive Twilio speech recognition webhook results for the called phone
        post("/api/twilio-speech-callback") {
            val params = call.receiveParameters()
            val speechResult = params["SpeechResult"] ?: params["UnstableSpeechResult"]
            val contactName = params["ContactName"] ?: "Contact"
            println(">>> [SERVER] RECEIVED TWILIO SPEECH CALLBACK: speechResult='$speechResult', contactName='$contactName'")

            if (!speechResult.isNullOrBlank()) {
                val calleeMessage = TranscriptMessage(
                    id = messageIdCounter.getAndIncrement(),
                    senderName = contactName,
                    text = speechResult,
                    isMe = false
                )
                broadcastTranscript(calleeMessage)
            }

            // Return TwiML to continue gathering speech without hanging up
            val twiml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Response>
                    <Gather input="speech" action="/api/twilio-speech-callback" method="POST" speechTimeout="auto" timeout="5" />
                    <Redirect>/api/twiml</Redirect>
                </Response>
            """.trimIndent()
            call.respondText(twiml, ContentType.Application.Xml)
        }

        // Endpoint to receive Twilio Webhook when someone calls our Twilio number +12182745801
        post("/api/incoming-call") {
            val params = call.receiveParameters()
            val callerNumber = params["From"] ?: "+17818690609"
            println(">>> [SERVER] INCOMING CALL RECEIVED FROM: $callerNumber")

            val incomingMessage = TranscriptMessage(
                id = 0,
                senderName = "System",
                text = "INCOMING_CALL:$callerNumber:Contact",
                isMe = false
            )
            broadcastTranscript(incomingMessage)

            val twiml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Response>
                    <Gather input="speech" action="/api/twilio-speech-callback" method="POST" speechTimeout="auto" timeout="5" />
                    <Redirect>/api/twiml</Redirect>
                </Response>
            """.trimIndent()
            call.respondText(twiml, ContentType.Application.Xml)
        }

        // Endpoint to simulate an incoming call for testing
        get("/api/trigger-incoming-call") {
            val incomingMessage = TranscriptMessage(
                id = 0,
                senderName = "System",
                text = "INCOMING_CALL:+17818690609:Contact",
                isMe = false
            )
            broadcastTranscript(incomingMessage)
            call.respondText("Incoming call triggered!", ContentType.Text.Plain)
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

            val currentAccountSid = System.getenv("TWILIO_ACCOUNT_SID")
            val currentAuthToken = System.getenv("TWILIO_AUTH_TOKEN")

            if (currentAccountSid.isNullOrBlank() || currentAuthToken.isNullOrBlank()) {
                call.respond(
                    MakeCallResponse(
                        status = "error",
                        error = "Twilio credentials (TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN) are not configured on the server."
                    )
                )
                return@post
            }

            try {
                Twilio.init(currentAccountSid, currentAuthToken)

                val fromNumber = request.fromPhoneNumber?.takeIf { it.isNotBlank() } ?: "+12182745801"
                val webhook = request.webhookUrl?.takeIf { it.isNotBlank() }
                    ?: "https://trs-speech-2205.twil.io/speech"

                val twilioCall = Call.creator(
                    PhoneNumber(request.toPhoneNumber),
                    PhoneNumber(fromNumber),
                    URI.create(webhook)
                ).create()

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
    }
}
