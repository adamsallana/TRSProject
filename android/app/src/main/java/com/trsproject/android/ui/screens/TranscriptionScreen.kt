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
import com.trsproject.android.network.CallService
import com.trsproject.android.ui.theme.TRSProjectTheme
import kotlinx.coroutines.launch

// Data model for a caption line
data class CaptionMessage(
    val id: Int,
    val senderName: String,
    val text: String,
    val isMe: Boolean
)

@Composable
fun TranscriptionScreen(
    modifier: Modifier = Modifier,
    contactName: String? = null,
    contactPhoneNumber: String? = null,
    onBack: () -> Unit = {}
) {
    var onCall by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }
    var isEditingSpeech by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }

    var twilioStatus by remember { mutableStateOf<String?>(null) }
    var callSid by remember { mutableStateOf<String?>(null) }

    val displayName = contactName ?: "Contact"
    val phoneNumber = contactPhoneNumber ?: "+17818690609"
    val callService = remember { CallService() }
    val coroutineScope = rememberCoroutineScope()

    var visibleCaptions by remember { mutableStateOf(listOf<CaptionMessage>()) }
    val listState = rememberLazyListState()
    var webSocket by remember { mutableStateOf<okhttp3.WebSocket?>(null) }

    fun handleEndCall() {
        val activeCallSid = callSid
        val activePhoneNumber = phoneNumber

        android.util.Log.d("TranscriptionScreen", "handleEndCall triggered. callSid=$activeCallSid, phone=$activePhoneNumber")

        try {
            val hangupMessage = org.json.JSONObject().apply {
                put("event", "end_call")
                put("action", "hangup")
                put("type", "hangup")
                put("text", "END_CALL")
                if (!activeCallSid.isNullOrBlank()) {
                    put("callSid", activeCallSid)
                    put("sid", activeCallSid)
                }
                if (activePhoneNumber.isNotBlank()) {
                    put("toPhoneNumber", activePhoneNumber)
                }
            }.toString()
            webSocket?.send(hangupMessage)
        } catch (e: Exception) {
            // Ignore if websocket already closed
        }

        webSocket?.close(1000, "User ended call")
        webSocket = null

        coroutineScope.launch {
            callService.endCall(callSid = activeCallSid, toPhoneNumber = activePhoneNumber)
        }

        twilioStatus = "Call Ended"
        onCall = false
        onBack()
    }

    // Initiate Twilio call via backend when screen is loaded
    LaunchedEffect(phoneNumber) {
        twilioStatus = "Initiating Twilio call to $phoneNumber..."
        val result = callService.makeCall(toPhoneNumber = phoneNumber)
        if (result.success) {
            callSid = result.callSid
            android.util.Log.d("TranscriptionScreen", "makeCall success! callSid=${result.callSid}")
            twilioStatus = "Twilio Call Active (SID: ${result.callSid})"
        } else {
            android.util.Log.e("TranscriptionScreen", "makeCall failed: ${result.errorMessage}")
            twilioStatus = "Twilio Status: ${result.errorMessage ?: "Disconnected"}"
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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    var micStatusText by remember { mutableStateOf("Mic: Active (Listening...)") }

    // Continuous SpeechRecognizer for caller speech transcription
    DisposableEffect(onCall, isMuted, hasMicPermission) {
        if (!onCall || isMuted || !hasMicPermission || !SpeechRecognizer.isRecognitionAvailable(context)) {
            micStatusText = if (isMuted) "Mic: Muted" else "Mic: Disabled"
            return@DisposableEffect onDispose {}
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

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
                micStatusText = "Mic: Active (Listening...)"
                keepListening(150L)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull()
                android.util.Log.d("SpeechRecognizer", "Recognized text: $spokenText")

                if (!spokenText.isNullOrBlank()) {
                    val msgId = System.currentTimeMillis().toInt()
                    val myMessage = CaptionMessage(
                        id = msgId,
                        senderName = "Me",
                        text = spokenText,
                        isMe = true
                    )
                    visibleCaptions = visibleCaptions + myMessage

                    val jsonPayload = org.json.JSONObject().apply {
                        put("id", msgId)
                        put("senderName", "Me")
                        put("text", spokenText)
                        put("isMe", true)
                    }.toString()
                    webSocket?.send(jsonPayload)
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
            micStatusText = "Mic Error: Failed to start"
        }

        onDispose {
            handler.removeCallbacksAndMessages(null)
            try {
                speechRecognizer.stopListening()
                speechRecognizer.cancel()
                speechRecognizer.destroy()
            } catch (e: Exception) {}
        }
    }

    // Connect WebSocket for live dual-sided transcription when call is active
    LaunchedEffect(onCall, displayName) {
        if (onCall) {
            val socket = callService.connectTranscriptionWebSocket(displayName) { msg ->
                val isDuplicate = visibleCaptions.any { existing ->
                    existing.id == msg.id || (msg.isMe && existing.isMe && existing.text == msg.text)
                }
                if (!isDuplicate) {
                    visibleCaptions = visibleCaptions + msg
                }
            }
            webSocket = socket
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                socket.close(1000, "Call Ended")
                webSocket = null
            }
        }
    }

    // Auto-scroll to the newest message when it appears
    LaunchedEffect(visibleCaptions.size) {
        if (visibleCaptions.isNotEmpty()) {
            listState.animateScrollToItem(visibleCaptions.size - 1)
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
                    text = "Call Ended",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBack) {
                    Text("Back to Home")
                }
            }
        } else {
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
                        CaptionBubble(caption = caption)
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
                                val textToSend = inputText.trim()
                                val msgId = System.currentTimeMillis().toInt()
                                val myCaption = CaptionMessage(
                                    id = msgId,
                                    senderName = "Me",
                                    text = textToSend,
                                    isMe = true
                                )
                                visibleCaptions = visibleCaptions + myCaption
                                val jsonPayload = org.json.JSONObject().apply {
                                    put("id", msgId)
                                    put("senderName", "Me")
                                    put("text", textToSend)
                                    put("isMe", true)
                                }.toString()
                                webSocket?.send(jsonPayload)
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
fun CaptionBubble(caption: CaptionMessage) {
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
                color = textColor
            )
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