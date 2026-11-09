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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow
import com.trsproject.android.network.phoneNumbersMatch

private const val END_MESSAGE_DELAY_MS = 2500L

/** How long to wait for status events after make-call times out before reporting failure. */
private const val LOST_CALL_RECOVERY_MS = 30_000L

/**
 * Shown while an outgoing call is being placed and ringing. Starts the Twilio call on entry and
 * hands off to the transcription screen once the other party answers.
 */
@Composable
fun OutgoingCallScreen(
    contactName: String,
    phoneNumber: String,
    userRelayNumber: String? = null,
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

    // True while the make-call request timed out and we're watching events to see if the call started
    var recoveringLostCall by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch {
            callEvents.collect { event ->
                if (event.direction != "outgoing") return@collect
                val previous = statusBySid[event.callSid]
                if (previous == null || previous !in CallEvent.TERMINAL_STATUSES) {
                    statusBySid[event.callSid] = event.status
                }
                // If makeCall hasn't given us a sid (slow or timed out), adopt a live call to this
                // number. Skip terminal events so a leftover event from an older call isn't picked.
                if (callSid == null && startError == null &&
                    event.status !in CallEvent.TERMINAL_STATUSES &&
                    phoneNumbersMatch(event.phoneNumber, phoneNumber)
                ) {
                    android.util.Log.d("OutgoingCallScreen", "Adopted call ${event.callSid} from status event")
                    callSid = event.callSid
                }
            }
        }
        val result = callService.makeCall(toPhoneNumber = phoneNumber, fromPhoneNumber = userRelayNumber)
        when {
            result.success && result.callSid != null -> callSid = result.callSid
            callSid != null -> Unit // already learned the sid from a status event
            result.mayHaveSucceeded -> {
                // The server may have placed the call anyway; give its status events time to arrive
                recoveringLostCall = true
                val found = withTimeoutOrNull(LOST_CALL_RECOVERY_MS) {
                    snapshotFlow { callSid }.filterNotNull().first()
                }
                recoveringLostCall = false
                if (found == null) {
                    startError = "Couldn't reach the server. Check your connection and try again."
                }
            }
            else -> startError = result.errorMessage ?: "Could not place call"
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
        recoveringLostCall -> "Still connecting…"
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
