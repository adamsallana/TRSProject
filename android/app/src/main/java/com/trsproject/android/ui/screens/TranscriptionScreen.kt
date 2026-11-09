package com.trsproject.android.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.trsproject.android.network.CallEvent
import com.trsproject.android.network.CallService
import com.trsproject.android.ui.theme.TRSProjectTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

// Data model for a caption line
data class CaptionMessage(
    val id: Int,
    val senderName: String,
    val text: String,
    val isMe: Boolean,
    /** True if this was our caption and it couldn't be sent (captions socket was down). */
    val failed: Boolean = false,
    /** False while the text is still interim; later messages with the same id replace it. */
    val isFinal: Boolean = true
)

/**
 * Adds [msg] to [captions]. Messages from the other party with an id already shown replace that
 * bubble (interim text being refined); our own echoed messages are dropped as duplicates.
 */
internal fun mergeCaption(captions: List<CaptionMessage>, msg: CaptionMessage): List<CaptionMessage> {
    if (msg.isMe) {
        val isDuplicate = captions.any { it.id == msg.id || (it.isMe && it.text == msg.text) }
        return if (isDuplicate) captions else captions + msg
    }
    val index = captions.indexOfFirst { !it.isMe && it.id == msg.id }
    return when {
        index < 0 -> captions + msg
        // A late interim update must not overwrite the finished text
        captions[index].isFinal && !msg.isFinal -> captions
        else -> captions.toMutableList().also { it[index] = msg }
    }
}

// SpeechRecognizer error codes added in API 31; literals so they compile against minSdk 26
private const val ERROR_TOO_MANY_REQUESTS = 10
private const val ERROR_SERVER_DISCONNECTED = 11
private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
private const val ERROR_LANGUAGE_UNAVAILABLE = 13

/** Give up restarting the recognizer after this many failures in a row (not counting silence). */
private const val MAX_CONSECUTIVE_SPEECH_FAILURES = 5

/** Streams the recognizer's start/stop "beep" can play on. Muted while listening during a call. */
private val BEEP_STREAMS = intArrayOf(
    android.media.AudioManager.STREAM_MUSIC,
    android.media.AudioManager.STREAM_NOTIFICATION,
    android.media.AudioManager.STREAM_SYSTEM
)

@Composable
fun TranscriptionScreen(
    modifier: Modifier = Modifier,
    contactName: String? = null,
    contactPhoneNumber: String? = null,
    /** Twilio CallSid of the already-connected call (placed by OutgoingCallScreen or accepted on IncomingCallScreen). */
    callSid: String? = null,
    callEvents: Flow<CallEvent> = emptyFlow(),
    onBack: () -> Unit = {}
) {
    var onCall by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }
    var isEditingSpeech by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }

    var twilioStatus by remember { mutableStateOf<String?>("Connected") }
    var isEndingCall by remember { mutableStateOf(false) }
    var endCallError by remember { mutableStateOf<String?>(null) }

    // Prefer the contact name, then the phone number; never show a placeholder like "null"
    val displayName = contactName?.takeIf { it.isNotBlank() && it != "null" }
        ?: contactPhoneNumber?.takeIf { it.isNotBlank() }
        ?: "Caller"
    val callService = remember { CallService() }
    val coroutineScope = rememberCoroutineScope()

    var visibleCaptions by remember { mutableStateOf(listOf<CaptionMessage>()) }
    val listState = rememberLazyListState()
    var webSocket by remember { mutableStateOf<okhttp3.WebSocket?>(null) }
    var captionsConnected by remember { mutableStateOf(true) }

    fun captionPayload(caption: CaptionMessage): String = org.json.JSONObject().apply {
        put("id", caption.id)
        put("senderName", caption.senderName)
        put("text", caption.text)
        put("isMe", true)
    }.toString()

    /** Shows the user's own caption and sends it to the other party; marks it failed if the socket is down. */
    fun sendMyCaption(text: String) {
        val caption = CaptionMessage(
            id = System.currentTimeMillis().toInt(),
            senderName = "Me",
            text = text,
            isMe = true
        )
        val sent = webSocket?.send(captionPayload(caption)) == true
        visibleCaptions = visibleCaptions + caption.copy(failed = !sent)
    }

    fun retryCaption(caption: CaptionMessage) {
        val sent = webSocket?.send(captionPayload(caption)) == true
        if (sent) {
            visibleCaptions = visibleCaptions.map { if (it.id == caption.id) it.copy(failed = false) else it }
        }
    }

    fun hangUp(sid: String) {
        isEndingCall = true
        endCallError = null
        coroutineScope.launch {
            val result = callService.endCall(sid)
            if (!result.success) {
                android.util.Log.e("TranscriptionScreen", "endCall failed: ${result.errorMessage}")
                endCallError = result.errorMessage ?: "Unknown error"
            }
            isEndingCall = false
        }
    }

    fun handleEndCall() {
        android.util.Log.d("TranscriptionScreen", "handleEndCall triggered. callSid=$callSid")
        onCall = false
        twilioStatus = "Call Ended"
        val sid = callSid ?: return
        hangUp(sid)
    }

    // The other party hung up
    LaunchedEffect(callSid) {
        if (callSid == null) return@LaunchedEffect
        callEvents.collect { event ->
            if (event.callSid == callSid && event.isTerminal && onCall) {
                onCall = false
                twilioStatus = when (event.status) {
                    "failed" -> "The call dropped due to a connection problem"
                    "busy", "no-answer", "canceled" -> "The call was disconnected"
                    else -> "$displayName hung up"
                }
            }
        }
    }

    val context = LocalContext.current
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // After a second denial Android stops showing the prompt, so send the user to app settings
    var micPermissionDenials by remember { mutableStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (!isGranted) micPermissionDenials++
    }

    fun requestMicPermission() {
        val grantedNow = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        when {
            grantedNow -> hasMicPermission = true
            micPermissionDenials >= 2 -> context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val speechAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    // Set when the recognizer hits an error it can't recover from; "Retry" clears it, restarting the mic
    var speechError by remember { mutableStateOf<String?>(null) }
    val micUnusable = !hasMicPermission || !speechAvailable || speechError != null

    // If the mic can't caption the user's speech, open the typing box so they can still talk
    LaunchedEffect(micUnusable, onCall) {
        if (micUnusable && onCall) isEditingSpeech = true
    }

    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    var micStatusText by remember { mutableStateOf("Mic: Active (Listening...)") }

    // Continuous SpeechRecognizer for caller speech transcription
    DisposableEffect(onCall, isMuted, hasMicPermission, speechError) {
        if (!onCall || isMuted || micUnusable) {
            micStatusText = when {
                isMuted -> "Mic: Muted"
                !hasMicPermission -> "Mic: No permission"
                !speechAvailable -> "Mic: Speech recognition unavailable"
                speechError != null -> "Mic: Stopped"
                else -> "Mic: Disabled"
            }
            return@DisposableEffect onDispose {}
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        var consecutiveFailures = 0

        // The recognizer plays a start/stop beep on every restart, which happens every few
        // seconds of silence. Silence those streams while listening and restore them after.
        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val streamsWeMuted = BEEP_STREAMS.filter { stream ->
            try {
                if (audioManager.isStreamMute(stream)) {
                    false // user already had it muted; leave it alone
                } else {
                    audioManager.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_MUTE, 0)
                    true
                }
            } catch (e: SecurityException) {
                // Muting notification/system streams can be blocked by Do Not Disturb policy
                android.util.Log.w("SpeechRecognizer", "Couldn't mute stream $stream: ${e.message}")
                false
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        fun keepListening(delayMs: Long = 100L) {
            if (!onCall || isMuted) return
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                if (onCall && !isMuted) {
                    try {
                        speechRecognizer.cancel()
                        speechRecognizer.startListening(intent)
                    } catch (e: Exception) {
                        android.util.Log.e("SpeechRecognizer", "Error restarting: ${e.message}")
                        speechError = "Captioning of your speech stopped unexpectedly."
                    }
                }
            }, delayMs)
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                android.util.Log.d("SpeechRecognizer", "Ready for speech")
                micStatusText = "Mic: Active (Listening...)"
            }

            override fun onBeginningOfSpeech() {
                android.util.Log.d("SpeechRecognizer", "Beginning of speech")
                consecutiveFailures = 0
                micStatusText = "Mic: Hearing speech..."
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                android.util.Log.d("SpeechRecognizer", "End of speech")
                micStatusText = "Mic: Processing..."
            }

            override fun onError(error: Int) {
                android.util.Log.e("SpeechRecognizer", "Speech error code: $error")
                when (error) {
                    // Silence: normal, just listen again
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        micStatusText = "Mic: Active (Listening...)"
                        keepListening(100L)
                    }
                    // Can't recover by retrying
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> hasMicPermission = false
                    ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
                        speechError = "Speech recognition doesn't support your device's language."
                    else -> {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_CONSECUTIVE_SPEECH_FAILURES) {
                            speechError = when (error) {
                                SpeechRecognizer.ERROR_NETWORK,
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                                SpeechRecognizer.ERROR_SERVER,
                                ERROR_SERVER_DISCONNECTED ->
                                    "Speech captioning lost its network connection."
                                SpeechRecognizer.ERROR_AUDIO ->
                                    "Couldn't record audio. Another app may be using the microphone."
                                else -> "Captioning of your speech stopped unexpectedly."
                            }
                            return
                        }
                        micStatusText = when (error) {
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Mic: Network problem, retrying…"
                            else -> "Mic: Reconnecting…"
                        }
                        // Back off: 0.5s, 1s, 2s, 4s… so we don't spin on a broken recognizer
                        val backoff = when (error) {
                            ERROR_TOO_MANY_REQUESTS -> 5000L
                            else -> 500L shl (consecutiveFailures - 1)
                        }
                        keepListening(backoff)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull()
                android.util.Log.d("SpeechRecognizer", "Recognized text: $spokenText")
                consecutiveFailures = 0

                if (!spokenText.isNullOrBlank()) {
                    sendMyCaption(spokenText)
                }

                micStatusText = "Mic: Active (Listening...)"
                keepListening(100L)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = matches?.firstOrNull()
                if (!partialText.isNullOrBlank()) {
                    android.util.Log.d("SpeechRecognizer", "Partial speech: $partialText")
                    micStatusText = "Mic: \"$partialText\""
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        speechRecognizer.setRecognitionListener(listener)
        try {
            speechRecognizer.startListening(intent)
            micStatusText = "Mic: Active (Listening...)"
        } catch (e: Exception) {
            android.util.Log.e("SpeechRecognizer", "Initial startListening error: ${e.message}")
            speechError = "Couldn't start the microphone."
        }

        onDispose {
            handler.removeCallbacksAndMessages(null)
            try {
                speechRecognizer.stopListening()
                speechRecognizer.cancel()
                speechRecognizer.destroy()
            } catch (e: Exception) {}
            // Restore synchronously: a restarted effect checks isStreamMute right after this
            streamsWeMuted.forEach { stream ->
                try {
                    audioManager.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_UNMUTE, 0)
                } catch (e: SecurityException) {}
            }
        }
    }

    // WebSocket for live dual-sided transcription while the call is active. Reconnects with
    // backoff if it drops, and shows a banner while captions are disconnected.
    LaunchedEffect(onCall, displayName) {
        if (!onCall) return@LaunchedEffect
        var attempt = 0
        while (true) {
            val dropped = kotlinx.coroutines.CompletableDeferred<Throwable?>()
            val socket = callService.connectTranscriptionWebSocket(
                contactName = displayName,
                onConnected = {
                    coroutineScope.launch {
                        attempt = 0
                        captionsConnected = true
                    }
                },
                onDisconnected = { dropped.complete(it) }
            ) { msg ->
                coroutineScope.launch {
                    // The server labels the other party generically; use the name we know
                    val labeled = if (msg.isMe) msg else msg.copy(senderName = displayName)
                    visibleCaptions = mergeCaption(visibleCaptions, labeled)
                }
            }
            webSocket = socket
            try {
                val cause = dropped.await()
                android.util.Log.w("TranscriptionScreen", "Captions socket dropped: ${cause?.message}")
            } finally {
                socket.close(1000, "Call Ended")
                webSocket = null
            }
            captionsConnected = false
            attempt++
            kotlinx.coroutines.delay(minOf(1000L shl minOf(attempt - 1, 4), 10_000L))
        }
    }

    // Auto-scroll to the newest message when it appears (animated), and keep the bottom in view
    // as interim text grows the last bubble (instant, so rapid updates don't queue animations)
    var lastScrolledSize by remember { mutableStateOf(0) }
    LaunchedEffect(visibleCaptions.size, visibleCaptions.lastOrNull()?.text) {
        if (visibleCaptions.isEmpty()) return@LaunchedEffect
        val lastIndex = visibleCaptions.size - 1
        if (visibleCaptions.size != lastScrolledSize) {
            lastScrolledSize = visibleCaptions.size
            listState.animateScrollToItem(lastIndex)
        } else {
            // Large offset scrolls to the bottom of the last item rather than its top
            listState.scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE / 2)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header
        Text(
            text = if (onCall) displayName else "No Active Call",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = if (onCall) "Active Call - Live Captioning" else "Call Ended",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (onCall) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (isMuted) "Mic: Muted" else micStatusText,
                style = MaterialTheme.typography.labelSmall,
                color = if (isMuted) Color.Red else MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Medium
            )
        }

        if (onCall && twilioStatus != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = twilioStatus!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!onCall) {
            // Screen shown when call ends
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = twilioStatus ?: "Call Ended",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                endCallError?.let { error ->
                    Spacer(modifier = Modifier.height(16.dp))
                    StatusBanner(
                        message = "Couldn't hang up the call, so the other person may still be connected. ($error)",
                        actionLabel = "Try again",
                        onAction = { callSid?.let { hangUp(it) } }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Wait for the hang-up request to finish so leaving the screen doesn't cancel it
                Button(onClick = onBack, enabled = !isEndingCall) {
                    Text(if (isEndingCall) "Ending call…" else "Back to Home")
                }
            }
        } else {
            // Problems that affect captioning, most important first
            when {
                !hasMicPermission -> StatusBanner(
                    message = "Microphone access is off, so your speech can't be captioned. You can type messages below instead.",
                    actionLabel = if (micPermissionDenials >= 2) "Open settings" else "Allow mic",
                    onAction = { requestMicPermission() }
                )
                !speechAvailable -> StatusBanner(
                    message = "Speech recognition isn't available on this device. Type your messages below instead."
                )
                speechError != null -> StatusBanner(
                    message = "$speechError You can type messages below instead.",
                    actionLabel = "Retry mic",
                    onAction = { speechError = null }
                )
            }
            if (!captionsConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                StatusBanner(message = "Captions disconnected. Reconnecting…")
            }
            // Active Chat Transcriptions
            if (visibleCaptions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Call connected. Waiting for speech...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleCaptions) { caption ->
                        CaptionBubble(caption = caption, onRetry = { retryCaption(caption) })
                    }
                }
            }

            if (isEditingSpeech) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type speech to send...") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                sendMyCaption(inputText.trim())
                                inputText = ""
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Send Speech",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // In-Call Action Control Bar (Mute, Speaker, End Call, Edit, Dial)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Mute / Unmute Button
                CallActionButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = if (isMuted) "Unmute" else "Mute",
                    isActive = isMuted,
                    onClick = { isMuted = !isMuted }
                )

                // 2. Speaker Button
                CallActionButton(
                    icon = Icons.Default.VolumeUp,
                    label = "Speaker",
                    isActive = isSpeakerOn,
                    onClick = { isSpeakerOn = !isSpeakerOn }
                )

                // 3. End Call Button (Red Circle)
                IconButton(
                    onClick = { handleEndCall() },
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.Red, shape = CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        tint = Color.White
                    )
                }

                // 4. Edit Speech Button
                CallActionButton(
                    icon = Icons.Default.Edit,
                    label = "Edit Speech",
                    isActive = isEditingSpeech,
                    onClick = { isEditingSpeech = !isEditingSpeech }
                )

                // 5. Keypad / Dial Button
                CallActionButton(
                    icon = Icons.Default.Dialpad,
                    label = "Keypad",
                    onClick = { /* Add dialpad action here */ }
                )
            }
        }
    }
}

@Composable
fun StatusBanner(
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        if (actionLabel != null) {
            androidx.compose.material3.TextButton(onClick = onAction) {
                Text(actionLabel, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CaptionBubble(caption: CaptionMessage, onRetry: () -> Unit = {}) {
    val isMe = caption.isMe
    val bubbleColor = if (isMe) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isMe) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Text(
            text = caption.senderName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isMe) 16.dp else 4.dp,
                        bottomEnd = if (isMe) 4.dp else 16.dp
                    )
                )
                .padding(12.dp)
        ) {
            Text(
                text = caption.text,
                style = MaterialTheme.typography.bodyMedium,
                // Interim text is dimmed until the final version arrives
                color = if (caption.isFinal) textColor else textColor.copy(alpha = 0.6f)
            )
        }
        if (caption.failed) {
            androidx.compose.material3.TextButton(onClick = onRetry) {
                Text(
                    text = "Not sent · Tap to retry",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// Custom reusable button component for bottom action bar
@Composable
fun CallActionButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(imageVector = icon, contentDescription = label)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Preview(showBackground = true)
@Composable
fun TranscriptionScreenPreview() {
    TRSProjectTheme {
        TranscriptionScreen()
    }
}