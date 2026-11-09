package com.trsproject.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.trsproject.android.network.CallEvent
import com.trsproject.android.network.CallService
import com.trsproject.android.ui.theme.TRSProjectTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

private const val END_MESSAGE_DELAY_MS = 2500L

/**
 * Shown while an outgoing call is being placed and ringing. Starts the Twilio call on entry and
 * hands off to the transcription screen once the other party answers.
 */
@Composable
fun OutgoingCallScreen(
    contactName: String,
    phoneNumber: String,
    callEvents: Flow<CallEvent>,
    modifier: Modifier = Modifier,
    callService: CallService = remember { CallService() },
    onConnected: (callSid: String) -> Unit = {},
    onFinished: () -> Unit = {}
) {
    var callSid by remember { mutableStateOf<String?>(null) }
    var startError by remember { mutableStateOf<String?>(null) }
    var cancelRequested by remember { mutableStateOf(false) }
    // Latest status per sid. Events can arrive before makeCall returns the sid, so keep them all.
    val statusBySid = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(Unit) {
        launch {
            callEvents.collect { event ->
                if (event.direction != "outgoing") return@collect
                val previous = statusBySid[event.callSid]
                if (previous == null || previous !in CallEvent.TERMINAL_STATUSES) {
                    statusBySid[event.callSid] = event.status
                }
            }
        }
        val result = callService.makeCall(toPhoneNumber = phoneNumber)
        if (result.success && result.callSid != null) {
            callSid = result.callSid
        } else {
            startError = result.errorMessage ?: "Could not place call"
        }
    }

    val status = callSid?.let { statusBySid[it] }

    // React to call progress
    LaunchedEffect(status, callSid) {
        val sid = callSid ?: return@LaunchedEffect
        when {
            cancelRequested -> Unit
            status == "in-progress" -> onConnected(sid)
            status in CallEvent.TERMINAL_STATUSES -> {
                delay(END_MESSAGE_DELAY_MS)
                onFinished()
            }
        }
    }

    // Couldn't start the call at all
    LaunchedEffect(startError) {
        if (startError != null) {
            delay(END_MESSAGE_DELAY_MS + 1000)
            onFinished()
        }
    }

    // Cancel: wait for the sid if makeCall is still in flight, hang up, then leave
    LaunchedEffect(cancelRequested, callSid, startError) {
        if (!cancelRequested) return@LaunchedEffect
        val sid = callSid
        when {
            sid != null -> {
                callService.endCall(sid)
                onFinished()
            }
            startError != null -> onFinished()
        }
    }

    val statusText = when {
        cancelRequested -> "Cancelling…"
        startError != null -> "Call failed: $startError"
        callSid == null -> "Calling…"
        else -> outgoingStatusText(status)
    }
    val stillRinging = !cancelRequested && startError == null && status !in CallEvent.TERMINAL_STATUSES

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "Outgoing call", textAlign = TextAlign.Center)

        CallerHeader(
            name = contactName,
            phoneNumber = phoneNumber,
            statusText = statusText,
            pulsing = stillRinging
        )

        Spacer(Modifier.padding(0.dp))

        RoundCallButton(
            icon = Icons.Default.CallEnd,
            label = if (stillRinging) "Cancel" else "Close",
            color = CallRed,
            enabled = !cancelRequested,
            onClick = {
                if (stillRinging) cancelRequested = true else onFinished()
            }
        )
    }
}

private fun outgoingStatusText(status: String?): String = when (status) {
    null, "queued", "initiated" -> "Calling…"
    "ringing" -> "Ringing…"
    "in-progress" -> "Connected"
    "busy" -> "Line busy"
    "no-answer" -> "No answer"
    "canceled" -> "Call canceled"
    "failed" -> "Call failed"
    "completed" -> "Call ended"
    else -> status
}

@Preview(showBackground = true)
@Composable
fun OutgoingCallScreenPreview() {
    TRSProjectTheme {
        OutgoingCallScreen(
            contactName = "Contact",
            phoneNumber = "+18282751713",
            callEvents = emptyFlow()
        )
    }
}
