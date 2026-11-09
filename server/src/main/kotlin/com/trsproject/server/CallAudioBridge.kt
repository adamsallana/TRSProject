package com.trsproject.server

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/*
 * Relays the app user's microphone into the phone call.
 *
 *   app mic --(PCM16 LE, /call-audio)--> server --(8 kHz mu-law, /twilio-media-stream)--> Twilio --> phone party
 *
 * The call's TwiML opens a bidirectional Media Stream (<Connect><Stream>) to /twilio-media-stream.
 * The app opens /call-audio?callSid=...&sampleRate=... and sends binary PCM frames, which are
 * converted and written into that call's stream. Audio that arrives before Twilio's stream is up
 * (or for TEST- calls, which have no stream) is dropped.
 */

/** Twilio Media Streams only accept 8 kHz mu-law. */
private const val TWILIO_SAMPLE_RATE = 8000

private class TwilioMediaStream(val session: DefaultWebSocketServerSession, val streamSid: String)

/** Active Twilio media streams, by CallSid. */
private val mediaStreams = ConcurrentHashMap<String, TwilioMediaStream>()

private val bridgeJson = Json { ignoreUnknownKeys = true }

/** G.711 mu-law encoding of one 16-bit linear sample. */
internal fun linearToMulaw(sample: Int): Byte {
    val bias = 0x84
    val clip = 32635
    var magnitude = sample
    val sign = if (magnitude < 0) {
        magnitude = -magnitude
        0x80
    } else {
        0
    }
    if (magnitude > clip) magnitude = clip
    magnitude += bias
    var exponent = 7
    var mask = 0x4000
    while (exponent > 0 && (magnitude and mask) == 0) {
        exponent--
        mask = mask shr 1
    }
    val mantissa = (magnitude shr (exponent + 3)) and 0x0F
    return (sign or (exponent shl 4) or mantissa).inv().toByte()
}

/**
 * Converts little-endian PCM16 mono at [inputRate] (a multiple of 8000) to 8 kHz mu-law.
 * Downsamples by averaging each group of samples; partial groups carry over to the next frame.
 */
internal class PcmToMulawEncoder(inputRate: Int) {
    private val factor = inputRate / TWILIO_SAMPLE_RATE
    private var sum = 0
    private var count = 0
    private var pendingLowByte: Int? = null

    init {
        require(inputRate > 0 && inputRate % TWILIO_SAMPLE_RATE == 0) {
            "Sample rate must be a multiple of $TWILIO_SAMPLE_RATE, was $inputRate"
        }
    }

    fun encode(pcm: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(pcm.size / (2 * factor) + 1)
        for (b in pcm) {
            val low = pendingLowByte
            if (low == null) {
                pendingLowByte = b.toInt() and 0xFF
                continue
            }
            pendingLowByte = null
            val sample = (b.toInt() shl 8) or low // sign comes from the high byte
            sum += sample
            count++
            if (count == factor) {
                out.write(linearToMulaw(sum / factor).toInt())
                sum = 0
                count = 0
            }
        }
        return out.toByteArray()
    }
}

/** Bidirectional Media Stream TwiML. Put after <Start><Transcription>; it blocks until the stream ends. */
internal fun connectStreamTwiml(publicBaseUrl: String): String {
    val wsBase = publicBaseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
    return """<Connect><Stream url="$wsBase/twilio-media-stream" /></Connect>"""
}

/** Twilio connects here when a call's TwiML runs <Connect><Stream>. */
fun Route.twilioMediaStreamRoute() {
    webSocket("/twilio-media-stream") {
        var registered: Pair<String, TwilioMediaStream>? = null
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                // "media" frames (the phone party's audio, ~50/s) aren't used; skip parsing them
                if (text.contains("\"event\":\"media\"")) continue

                val msg = try {
                    bridgeJson.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    continue
                }
                when (msg["event"]?.jsonPrimitive?.contentOrNull) {
                    "start" -> {
                        val start = msg["start"]?.jsonObject
                        val callSid = start?.get("callSid")?.jsonPrimitive?.contentOrNull
                        val streamSid = msg["streamSid"]?.jsonPrimitive?.contentOrNull
                            ?: start?.get("streamSid")?.jsonPrimitive?.contentOrNull
                        if (callSid != null && streamSid != null) {
                            val stream = TwilioMediaStream(this, streamSid)
                            mediaStreams[callSid] = stream
                            registered = callSid to stream
                            println(">>> [SERVER] TWILIO MEDIA STREAM STARTED: callSid=$callSid streamSid=$streamSid")
                        }
                    }
                    "stop" -> break
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            println(">>> [SERVER] TWILIO MEDIA STREAM ERROR: ${e.message}")
        } finally {
            registered?.let { (callSid, stream) ->
                mediaStreams.remove(callSid, stream)
                println(">>> [SERVER] TWILIO MEDIA STREAM ENDED: callSid=$callSid")
            }
        }
    }
}

/** The app streams its microphone here as binary PCM16 LE frames. */
fun Route.callAudioRoute() {
    webSocket("/call-audio") {
        val callSid = call.request.queryParameters["callSid"]?.takeIf { it.isNotBlank() }
        val sampleRate = call.request.queryParameters["sampleRate"]?.toIntOrNull() ?: TWILIO_SAMPLE_RATE
        if (callSid == null || sampleRate <= 0 || sampleRate % TWILIO_SAMPLE_RATE != 0) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "callSid and a sampleRate that is a multiple of 8000 are required"))
            return@webSocket
        }
        println(">>> [SERVER] APP MIC AUDIO CONNECTED: callSid=$callSid sampleRate=$sampleRate")
        val encoder = PcmToMulawEncoder(sampleRate)
        val base64 = Base64.getEncoder()
        try {
            for (frame in incoming) {
                if (frame !is Frame.Binary) continue
                val mulaw = encoder.encode(frame.readBytes())
                val stream = mediaStreams[callSid] ?: continue
                if (mulaw.isEmpty()) continue
                val message = """{"event":"media","streamSid":"${stream.streamSid}","media":{"payload":"${base64.encodeToString(mulaw)}"}}"""
                try {
                    stream.session.send(Frame.Text(message))
                } catch (e: Exception) {
                    // Twilio's side closed; its route will unregister the stream
                }
            }
        } catch (e: Exception) {
            println(">>> [SERVER] APP MIC AUDIO ERROR: ${e.message}")
        } finally {
            println(">>> [SERVER] APP MIC AUDIO DISCONNECTED: callSid=$callSid")
        }
    }
}
