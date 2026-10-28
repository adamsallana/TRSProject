package com.trsproject.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper around Android's built-in SpeechRecognizer. The platform
 * recognizer ends each session after a pause in speech, so we restart it
 * after every result until stop() is called, giving continuous captioning.
 *
 * SpeechRecognizer must be created and used on the main thread.
 */
class SpeechTranscriber(
    context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onStopped: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    // True from start() until the user calls stop(); drives the restart loop.
    private var active = false

    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
        setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onStatus("Listening…")
            override fun onBeginningOfSpeech() = onStatus("Hearing speech…")
            override fun onEndOfSpeech() = onStatus("Processing…")

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstResult()?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                results.firstResult()?.let(onFinal)
                if (active) restart() else finish("Stopped")
            }

            override fun onError(error: Int) {
                when {
                    !active -> finish("Stopped")
                    // Silence or unintelligible audio just ends a session; keep going.
                    error in RECOVERABLE_ERRORS -> restart()
                    else -> {
                        active = false
                        finish("Error: ${describeError(error)}")
                    }
                }
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    /** Caller must have RECORD_AUDIO granted before calling this. */
    fun start() {
        if (active) return
        active = true
        recognizer.startListening(intent)
    }

    /** Ends the session; the last words spoken are still delivered via onFinal. */
    fun stop() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        recognizer.stopListening()
    }

    fun destroy() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        recognizer.destroy()
    }

    // Restarting from inside a listener callback can hit ERROR_RECOGNIZER_BUSY,
    // so give the recognizer a moment to tear down the previous session.
    private fun restart() {
        mainHandler.postDelayed({
            if (active) recognizer.startListening(intent)
        }, RESTART_DELAY_MS)
    }

    private fun finish(status: String) {
        onStatus(status)
        onStopped()
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "didn't catch that"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
        SpeechRecognizer.ERROR_AUDIO -> "audio recording problem"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network problem"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        else -> "code $error"
    }

    companion object {
        private const val RESTART_DELAY_MS = 100L
        private val RECOVERABLE_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        )

        fun isAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)
    }
}
