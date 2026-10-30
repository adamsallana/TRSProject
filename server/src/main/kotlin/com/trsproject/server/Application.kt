package com.trsproject.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(WebSockets)

    val supabaseUrl = System.getenv("SUPABASE_URL")
    val supabaseKey = System.getenv("SUPABASE_SECRET_KEY")
    val httpClient = HttpClient(ClientCIO)

    routing {
	        // Story 5: Tests the connection between the Ktor backend and Supabase
        get("/db-test") {
            if (supabaseUrl.isNullOrBlank() || supabaseKey.isNullOrBlank()) {
                call.respondText("Supabase environment variables are missing.")
                return@get
            }

            try {
                val response = httpClient.get(
                    "$supabaseUrl/rest/v1/profiles?select=id&limit=1"
                ) {
                    header("apikey", supabaseKey)
                    header(HttpHeaders.Authorization, "Bearer $supabaseKey")
                }

                call.respondText(
                    "Supabase responded with ${response.status.value}: ${response.bodyAsText()}"
                )
            } catch (e: Exception) {
                call.respondText("Supabase connection failed: ${e.message}")
            }
        }

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
    }
}
