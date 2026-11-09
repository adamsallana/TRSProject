package com.trsproject.android.network

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicReference

/** The microphone couldn't be opened or stopped working. [message] is user-facing. */
class MicUnavailableException(message: String) : Exception(message)

enum class MicStreamStatus { CONNECTING, STREAMING, RECONNECTING }

// Twilio needs 8 kHz; the server downsamples, so any multiple of 8000 works. Prefer 8 kHz to save
// bandwidth, falling back for devices that don't support it.
private val SAMPLE_RATES = intArrayOf(8000, 16000, 48000)

/** Frames of 20 ms, the usual size for real-time voice. */
private const val FRAMES_PER_SECOND = 50

@SuppressLint("MissingPermission") // Callers check RECORD_AUDIO first; failures surface as exceptions
private fun openMic(): AudioRecord? {
    for (rate in SAMPLE_RATES) {
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) continue
        val record = try {
            // VOICE_COMMUNICATION applies the device's echo cancellation and noise suppression
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, rate / 5 * 2) // at least 200 ms
            )
        } catch (e: Exception) {
            null
        } ?: continue
        if (record.state == AudioRecord.STATE_INITIALIZED) return record
        record.release()
    }
    return null
}

/**
 * Streams the microphone into Twilio call [callSid] so the other party hears the user, until the
 * calling coroutine is cancelled. The server transcribes this audio for the user's own captions.
 *
 * Reconnects with backoff if the socket drops (audio recorded while disconnected is discarded so
 * the call stays live rather than lagging). Throws [MicUnavailableException] if the mic fails.
 */
suspend fun streamMicrophoneToCall(
    callService: CallService,
    callSid: String,
    onStatus: (MicStreamStatus) -> Unit
) = coroutineScope {
    val mic = openMic() ?: throw MicUnavailableException("Couldn't open the microphone.")
    try {
        mic.startRecording()
    } catch (e: Exception) {
        mic.release()
        throw MicUnavailableException("Couldn't start the microphone.")
    }
    if (mic.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        mic.release()
        throw MicUnavailableException("Couldn't record audio. Another app may be using the microphone.")
    }

    val sampleRate = mic.sampleRate
    // If the network stalls, drop audio past ~1 s of backlog instead of queueing ever more delay
    val maxQueuedBytes = sampleRate * 2L
    val liveSocket = AtomicReference<okhttp3.WebSocket?>(null)

    // Reader: owns the AudioRecord and releases it when done
    launch(Dispatchers.IO) {
        val samples = ShortArray(sampleRate / FRAMES_PER_SECOND)
        val bytes = ByteArray(samples.size * 2)
        try {
            while (isActive) {
                val read = mic.read(samples, 0, samples.size)
                if (read < 0) throw MicUnavailableException("Couldn't record audio. Another app may be using the microphone.")
                val socket = liveSocket.get() ?: continue
                if (read == 0 || socket.queueSize() > maxQueuedBytes) continue
                for (i in 0 until read) {
                    val s = samples[i].toInt()
                    bytes[2 * i] = s.toByte()
                    bytes[2 * i + 1] = (s shr 8).toByte()
                }
                socket.send(bytes.toByteString(0, read * 2))
            }
        } finally {
            try {
                mic.stop()
            } catch (e: Exception) {}
            mic.release()
        }
    }

    // Connection loop
    var attempt = 0
    onStatus(MicStreamStatus.CONNECTING)
    while (isActive) {
        val opened = CompletableDeferred<Unit>()
        val dropped = CompletableDeferred<Throwable?>()
        val socket = callService.connectCallAudioWebSocket(
            callSid = callSid,
            sampleRate = sampleRate,
            onOpened = { opened.complete(Unit) },
            onDropped = { dropped.complete(it) }
        )
        try {
            val didOpen = select {
                opened.onAwait { true }
                dropped.onAwait { false }
            }
            if (didOpen) {
                attempt = 0
                liveSocket.set(socket)
                onStatus(MicStreamStatus.STREAMING)
                val cause = dropped.await()
                android.util.Log.w("CallAudioStreamer", "Mic audio socket dropped: ${cause?.message}")
            }
        } finally {
            liveSocket.set(null)
            socket.close(1000, null)
        }
        onStatus(MicStreamStatus.RECONNECTING)
        attempt++
        delay(minOf(500L shl minOf(attempt - 1, 4), 8_000L))
    }
}
