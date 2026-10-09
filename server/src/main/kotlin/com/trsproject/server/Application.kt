package com.trsproject.server

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
    }
}
