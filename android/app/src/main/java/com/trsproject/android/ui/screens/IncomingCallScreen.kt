package com.trsproject.android.ui.screens

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
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
import androidx.compose.ui.platform.LocalContext
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

/**
 * Full-screen "ringing" page for an incoming call. Rings and vibrates until the user accepts
 * or declines, or the caller hangs up / the call times out.
 */
@Composable
fun IncomingCallScreen(
    callSid: String,
    callerName: String,
    phoneNumber: String,
    callEvents: Flow<CallEvent>,
    modifier: Modifier = Modifier,
    callService: CallService = remember { CallService() },
    onAccepted: () -> Unit = {},
    onFinished: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var endedStatus by remember { mutableStateOf<String?>(null) }

    // Caller hung up, timed out, or the call was handled elsewhere
    LaunchedEffect(callSid) {
        callEvents.collect { event ->
            if (event.callSid == callSid && event.isTerminal && endedStatus == null) {
                endedStatus = event.status
            }
        }
    }
    LaunchedEffect(endedStatus) {
        if (endedStatus != null) {
            delay(2000)
            onFinished()
        }
    }

    val isRinging = endedStatus == null && !isBusy
    RingAndVibrate(active = isRinging)

    val statusText = when {
        endedStatus == "no-answer" -> "Missed call"
        endedStatus != null -> "Caller hung up"
        isBusy -> "Connecting…"
        else -> "Incoming call…"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "Incoming call", textAlign = TextAlign.Center)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CallerHeader(
                name = callerName,
                phoneNumber = phoneNumber,
                statusText = statusText,
                pulsing = isRinging
            )
            errorText?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RoundCallButton(
                icon = Icons.Default.CallEnd,
                label = "Decline",
                color = CallRed,
                enabled = endedStatus == null && !isBusy,
                onClick = {
                    isBusy = true
                    scope.launch {
                        val result = callService.declineIncomingCall(callSid)
                        if (!result.success) {
                            android.util.Log.e("IncomingCallScreen", "Decline failed: ${result.errorMessage}")
                        }
                        onFinished()
                    }
                }
            )
            RoundCallButton(
                icon = Icons.Default.Call,
                label = "Accept",
                color = CallGreen,
                enabled = endedStatus == null && !isBusy,
                onClick = {
                    isBusy = true
                    errorText = null
                    scope.launch {
                        val result = callService.acceptIncomingCall(callSid)
                        if (result.success) {
                            onAccepted()
                        } else {
                            errorText = "Couldn't connect: ${result.errorMessage ?: "unknown error"}"
                            isBusy = false
                        }
                    }
                }
            )
        }
    }
}

/** Plays the default ringtone and vibrates while [active] is true. */
@Composable
private fun RingAndVibrate(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose {}

        val ringtone: Ringtone? = try {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                ?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
        } catch (e: Exception) {
            android.util.Log.e("IncomingCallScreen", "Ringtone failed: ${e.message}")
            null
        }

        val vibrator = context.defaultVibrator()
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        } catch (e: Exception) {
            android.util.Log.e("IncomingCallScreen", "Vibration failed: ${e.message}")
        }

        onDispose {
            ringtone?.stop()
            vibrator?.cancel()
        }
    }
}

private fun Context.defaultVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

@Preview(showBackground = true)
@Composable
fun IncomingCallScreenPreview() {
    TRSProjectTheme {
        IncomingCallScreen(
            callSid = "TEST-preview",
            callerName = "Contact",
            phoneNumber = "+17818690609",
            callEvents = emptyFlow()
        )
    }
}
