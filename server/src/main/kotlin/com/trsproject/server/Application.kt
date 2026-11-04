package com.trsproject.server

import com.twilio.Twilio
import com.twilio.rest.api.v2010.account.Call
import com.twilio.type.PhoneNumber
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.serialization.Serializable
import java.net.URI

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

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(WebSockets)
    install(ContentNegotiation) {
        json()
    }

    val accountSid = System.getenv("TWILIO_ACCOUNT_SID")
    val authToken = System.getenv("TWILIO_AUTH_TOKEN")
    val defaultFromNumber = System.getenv("TWILIO_PHONE_NUMBER") ?: "+17372583478"

    if (!accountSid.isNullOrBlank() && !authToken.isNullOrBlank()) {
        Twilio.init(accountSid, authToken)
    }

    routing {
        // Story 1: Android streams raw mic audio frames here; this will forward
        // each chunk to Google Speech-to-Text's streaming recognizer and send
        // transcript messages back over the same socket. Stubbed for now so the
        // client <-> server plumbing can be smoke-tested before STT is wired in.
        webSocket("/transcribe") {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> {
                        val audioChunk = frame.readBytes()
                        send(Frame.Text("stub transcript: received ${audioChunk.size} bytes"))
                    }
                    else -> Unit
                }
            }
        }

        // Endpoint to initiate a call via Twilio REST API
        post("/api/make-call") {
            val request = try {
                call.receive<MakeCallRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    MakeCallResponse(status = "error", error = "Invalid request format: ${e.message}")
                )
                return@post
            }

            val currentAccountSid = System.getenv("TWILIO_ACCOUNT_SID")
            val currentAuthToken = System.getenv("TWILIO_AUTH_TOKEN")

            if (currentAccountSid.isNullOrBlank() || currentAuthToken.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    MakeCallResponse(
                        status = "error",
                        error = "Twilio credentials (TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN) are not configured on the server."
                    )
                )
                return@post
            }

            try {
                Twilio.init(currentAccountSid, currentAuthToken)

                val fromNumber = request.fromPhoneNumber?.takeIf { it.isNotBlank() } ?: defaultFromNumber
                val webhook = request.webhookUrl?.takeIf { it.isNotBlank() }
                    ?: "https://webhooks.twilio.com/v1/Voice/Template/voice_speech_recognition"

                val twilioCall = Call.creator(
                    PhoneNumber(request.toPhoneNumber),
                    PhoneNumber(fromNumber),
                    URI.create(webhook)
                ).create()

                call.respond(
                    HttpStatusCode.OK,
                    MakeCallResponse(
                        status = "success",
                        callSid = twilioCall.sid
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    MakeCallResponse(
                        status = "error",
                        error = "Failed to initiate call via Twilio: ${e.message}"
                    )
                )
            }
        }
    }
}
