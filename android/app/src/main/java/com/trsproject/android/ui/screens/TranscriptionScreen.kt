package com.trsproject.android.ui.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.trsproject.android.ui.theme.TRSProjectTheme
import kotlinx.coroutines.delay

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
    onSignOut: () -> Unit = {}
) {
    var onCall by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }

    // Master sequence of call events & captions
    val callSequence = remember {
        listOf(
            CaptionMessage(1, "Me", "Connecting to ASR...", isMe = true),
            CaptionMessage(2, "Me", "Dialing Alice Smith...", isMe = true),
            CaptionMessage(3, "Alice Smith", "Hello! Can you hear me clearly?", isMe = false),
            CaptionMessage(4, "Me", "Yes, I can hear you loud and clear. How's it going?", isMe = true),
            CaptionMessage(5, "Alice Smith", "Everything is great! Just testing out this live transcription feature.", isMe = false),
            CaptionMessage(6, "Me", "It looks amazing and updates in real-time like text messages.", isMe = true)
        )
    }

    // State holding only the messages that have appeared so far
    var visibleCaptions by remember { mutableStateOf(listOf<CaptionMessage>()) }
    val listState = rememberLazyListState()

    // Simulates incoming speech line-by-line over time
    LaunchedEffect(onCall) {
        if (onCall) {
            visibleCaptions = emptyList()
            callSequence.forEachIndexed { index, caption ->
                // Quick delays for connection/dialing status, then 5s between speech
                val delayTime = when (index) {
                    0 -> 1000L // "Connecting to ASR..."
                    1 -> 2000L // "Dialing Alice Smith..."
                    2 -> 3000L // First response from Alice
                    else -> 5000L // Conversation flow
                }
                delay(delayTime)
                visibleCaptions = visibleCaptions + caption
            }
        } else {
            visibleCaptions = emptyList()
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
            text = if (onCall) "Alice Smith" else "No Active Call",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = if (onCall) "Active Call - Live Captioning" else "Call Ended",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
                    text = "Transcription Page Placeholder",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { onCall = true }) {
                    Text("Start Call with Alice Smith")
                }
            }
        } else {
            // Active Chat Transcriptions
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

            Spacer(modifier = Modifier.height(16.dp))

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
                    onClick = { onCall = false },
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