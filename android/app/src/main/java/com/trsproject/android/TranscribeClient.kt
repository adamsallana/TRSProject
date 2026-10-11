package com.trsproject.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * Streams raw mic audio (16 kHz mono PCM16, ~100 ms chunks) to the server's
 * /transcribe WebSocket and reports text frames coming back. Callbacks are
 * delivered on the main thread.
 */
class TranscribeClient(
    private val url: String,
    private val onTranscript: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onStopped: () -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var socket: WebSocket? = null
    @Volatile private var recordJob: Job? = null

    /** Caller must have RECORD_AUDIO granted before calling this. */
    fun start(scope: CoroutineScope) {
        if (socket != null) return
        postStatus("Connecting to $url")

        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                postStatus("Connected, streaming audio")
                startRecording(scope, webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                mainHandler.post { onTranscript(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            // Ignore late callbacks from a socket that stop() already discarded.
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === socket) cleanup("Disconnected ($code)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === socket) cleanup("Connection failed: ${t.message}")
            }
        })
    }

    fun stop() {
        socket?.close(NORMAL_CLOSURE, "client stopped")
        cleanup("Stopped")
    }

    // Permission is checked by the caller before start(); see MainActivity.
    @SuppressLint("MissingPermission")
    private fun startRecording(scope: CoroutineScope, webSocket: WebSocket) {
        val chunkBytes = SAMPLE_RATE * BYTES_PER_SAMPLE / 10
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            maxOf(minBuffer, chunkBytes * 2),
        )

        recordJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(chunkBytes)
            try {
                recorder.startRecording()
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) webSocket.send(buffer.toByteString(0, read))
                }
            } finally {
                recorder.stop()
                recorder.release()
            }
        }
    }

    private fun cleanup(status: String) {
        recordJob?.cancel()
        recordJob = null
        socket = null
        postStatus(status)
        mainHandler.post { onStopped() }
    }

    private fun postStatus(status: String) {
        mainHandler.post { onStatus(status) }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SAMPLE = 2
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val NORMAL_CLOSURE = 1000
    }
}
