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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    // Master list of all dummy transcription lines
    val allCaptions = remember {
        listOf(
            CaptionMessage(1, "Other Person", "Hello! Can you hear me clearly?", isMe = false),
            CaptionMessage(2, "Me", "Yes, I can hear you loud and clear. How's it going?", isMe = true),
            CaptionMessage(3, "Other Person", "Everything is great. Just testing out this live transcription feature.", isMe = false),
            CaptionMessage(4, "Me", "It looks amazing and updates in real-time like text messages.", isMe = true)
        )
    }

    // State holding only the messages that have appeared so far
    var visibleCaptions by remember { mutableStateOf(listOf<CaptionMessage>()) }

    // Simulates incoming speech line-by-line over time
    LaunchedEffect(onCall) {
        if (onCall) {
            visibleCaptions = emptyList() // Reset when call starts
            allCaptions.forEachIndexed { index, caption ->
                // Wait 5 seconds between messages (shorter 1-second delay for the very first message)
                val delayTime = if (index == 0) 1000L else 5000L
                delay(delayTime)
                visibleCaptions = visibleCaptions + caption
            }
        } else {
            visibleCaptions = emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header & Call Status
        Text(
            text = if (onCall) "Active Call Transcription" else "No Active Call",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (!onCall) {
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
            }
        } else {
            // Chat Bubble List showing only visible messages
            LazyColumn(
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

        Spacer(modifier = Modifier.height(16.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            Button(onClick = { onCall = !onCall }) {
                Text(if (onCall) "End Call" else "Start Dummy Call")
            }

            OutlinedButton(onClick = onSignOut) {
                Text("Sign Out")
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

@Preview(showBackground = true)
@Composable
fun TranscriptionScreenPreview() {
    TRSProjectTheme {
        TranscriptionScreen()
    }
}