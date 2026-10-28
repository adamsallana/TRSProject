package com.trsproject.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.trsproject.android.SpeechTranscriber
import com.trsproject.android.ui.theme.TRSProjectTheme

@Composable
fun TranscriptionScreen(
    modifier: Modifier = Modifier,
    contactName: String? = null,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val available = remember { SpeechTranscriber.isAvailable(context) }

    var onCall by remember { mutableStateOf(true) }
    var isSpeakerOn by remember { mutableStateOf(false) }

    var status by remember {
        mutableStateOf(if (available) "Tap the mic to start captioning" else "Speech recognition not available on this device")
    }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    // Only testing one mic for now, so every utterance goes into a single bubble
    var transcript by remember { mutableStateOf("") }

    val displayName = contactName ?: "Alice Smith"

    val transcriber = remember {
        SpeechTranscriber(
            context = context,
            onPartial = { partial = it },
            onFinal = { text ->
                partial = ""
                transcript = if (transcript.isEmpty()) text else "$transcript $text"
            },
            onStatus = { status = it },
            onStopped = { listening = false },
        )
    }
    DisposableEffect(transcriber) {
        onDispose { transcriber.destroy() }
    }

    fun startListening() {
        listening = true
        partial = ""
        transcriber.start()
    }

    // asking for mic permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else status = "Microphone permission denied"
    }

    val bubbleText = listOf(transcript, partial).filter { it.isNotEmpty() }.joinToString(" ")
    val scrollState = rememberScrollState()

    // Keep the newest words in view as the bubble grows
    LaunchedEffect(bubbleText) {
        scrollState.animateScrollTo(scrollState.maxValue)
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
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
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
            // Live caption bubble
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                if (bubbleText.isNotEmpty()) {
                    // Mic audio is the local user, so it's labeled "Me" and sits on the right
                    CaptionBubble(senderName = "Me", text = bubbleText, isMe = true)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // In-Call Action Control Bar (Mic, Speaker, End Call, Edit, Dial)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Start / Stop captioning
                CallActionButton(
                    icon = if (listening) Icons.Default.Mic else Icons.Default.MicOff,
                    label = if (listening) "Stop" else "Listen",
                    isActive = listening,
                    enabled = available,
                    onClick = {
                        when {
                            listening -> transcriber.stop()
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED -> startListening()
                            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
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
                    onClick = {
                        if (listening) transcriber.stop()
                        onCall = false
                    },
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
                    onClick = { /* Add edit action here */ }
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
fun CaptionBubble(senderName: String, text: String, isMe: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Text(
            text = senderName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        // Small "tail" corner points toward the speaker's side
                        bottomStart = if (isMe) 16.dp else 4.dp,
                        bottomEnd = if (isMe) 4.dp else 16.dp
                    )
                )
                .padding(12.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
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
        TranscriptionScreen(contactName = "Alice Smith")
    }
}
