package com.trsproject.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper around Android's built-in SpeechRecognizer. One call to
 * startListening() captures a single utterance: the recognizer stops on its
 * own after a pause in speech and delivers a final result.
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
                onStatus("Done")
                onStopped()
            }

            override fun onError(error: Int) {
                onStatus("Error: ${describeError(error)}")
                onStopped()
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
    fun start() = recognizer.startListening(intent)

    fun stop() = recognizer.stopListening()

    fun destroy() = recognizer.destroy()

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
        fun isAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)
    }
}
