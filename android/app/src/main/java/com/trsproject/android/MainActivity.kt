package com.trsproject.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.trsproject.android.data.model.ActiveCall
import com.trsproject.android.data.model.CallDirection
import com.trsproject.android.data.remote.SupabaseService
import io.github.jan.supabase.auth.auth
import com.trsproject.android.network.CallEvent
import com.trsproject.android.network.CallService
import com.trsproject.android.network.phoneNumbersMatch
import com.trsproject.android.ui.navigation.Screen
import com.trsproject.android.ui.screens.AccountScreen
import com.trsproject.android.ui.screens.AuthScreen
import com.trsproject.android.ui.screens.CaptionSettingsScreen
import com.trsproject.android.ui.screens.ErrorScreen
import com.trsproject.android.ui.screens.HomeScreen
import com.trsproject.android.ui.screens.IncomingCallScreen
import com.trsproject.android.ui.screens.NotificationsScreen
import com.trsproject.android.ui.screens.OutgoingCallScreen
import com.trsproject.android.ui.screens.PersonalInfoScreen
import com.trsproject.android.ui.screens.SecuritySettingsScreen
import com.trsproject.android.ui.screens.TranscriptionScreen
import com.trsproject.android.ui.theme.TRSProjectTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TRSProjectTheme {
                MainApp()
            }
        }
    }
}

/** Name of the signed-in user's saved contact with [number], or null if none / lookup fails. */
private suspend fun lookupContactName(number: String): String? {
    return try {
        val userId = SupabaseService.client.auth.currentUserOrNull()?.id ?: return null
        SupabaseService.fetchContacts(userId)
            // fetchContacts returns a placeholder "Contact" when the list is empty or fails; skip it
            .filterNot { it.id == "1" && it.name == "Contact" }
            .firstOrNull { phoneNumbersMatch(it.phoneNumber, number) }
            ?.name
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        android.util.Log.w("MainApp", "Contact lookup failed: ${e.message}")
        null
    }
}

private val SIGNED_OUT_SCREENS = setOf(Screen.AUTH, Screen.ERROR)
private val IN_CALL_SCREENS = setOf(Screen.OUTGOING_CALL, Screen.INCOMING_CALL, Screen.TRANSCRIPTION)

@Composable
fun MainApp() {
    var currentScreen by remember { mutableStateOf(Screen.AUTH) }
    var activeCall by remember { mutableStateOf<ActiveCall?>(null) }
    // True once the call on the transcription screen has ended (its "Call Ended" view is showing)
    var activeCallEnded by remember { mutableStateOf(false) }

    val callService = remember { CallService() }
    val appScope = rememberCoroutineScope()
    // Call events from the server, re-emitted to whichever call screen is showing
    val callEvents = remember { MutableSharedFlow<CallEvent>(extraBufferCapacity = 32) }
    val isSignedIn = currentScreen !in SIGNED_OUT_SCREENS

    fun endCallAndGoHome() {
        activeCall = null
        currentScreen = Screen.HOME
    }

    // One app-wide socket for call events while signed in, so incoming calls ring on any screen.
    // Reconnects after a short delay if the connection drops.
    LaunchedEffect(isSignedIn) {
        if (!isSignedIn) return@LaunchedEffect
        callService.callEvents()
            .retryWhen { cause, _ ->
                android.util.Log.w("MainApp", "Call event socket dropped, reconnecting: ${cause.message}")
                delay(3000)
                true
            }
            .collect { event ->
                val isNewIncoming = event.direction == "incoming" && event.status == "ringing"
                if (isNewIncoming) {
                    val busy = currentScreen in IN_CALL_SCREENS && !activeCallEnded
                    if (busy) {
                        // Already on a live call: ignore the new one. Don't decline it: the server
                        // broadcasts calls to every connected app, so declining here would hang up
                        // on a call another device may want to answer. Unanswered, it times out.
                        android.util.Log.d("MainApp", "Busy; ignoring incoming call ${event.callSid}")
                    } else {
                        activeCallEnded = false
                        val number = event.phoneNumber.orEmpty()
                        activeCall = ActiveCall(
                            direction = CallDirection.INCOMING,
                            name = event.name ?: number.ifBlank { "Unknown caller" },
                            phoneNumber = number,
                            callSid = event.callSid
                        )
                        currentScreen = Screen.INCOMING_CALL
                        // Show the saved contact's name if we have this number
                        if (event.name == null && number.isNotBlank()) {
                            launch {
                                val savedName = lookupContactName(number) ?: return@launch
                                val current = activeCall
                                if (current?.callSid == event.callSid) {
                                    activeCall = current.copy(name = savedName)
                                }
                            }
                        }
                    }
                }
                callEvents.emit(event)
            }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        when (currentScreen) {
            Screen.AUTH -> AuthScreen(
                modifier = Modifier.padding(innerPadding),
                onSignInSuccess = { currentScreen = Screen.HOME },
                onSignInError = { currentScreen = Screen.ERROR }
            )

            Screen.HOME -> HomeScreen(
                modifier = Modifier.padding(innerPadding),
                onCallClick = { contact ->
                    activeCallEnded = false
                    val number = contact.phoneNumber.orEmpty()
                    activeCall = ActiveCall(
                        direction = CallDirection.OUTGOING,
                        name = contact.name?.ifBlank { null } ?: number,
                        phoneNumber = number
                    )
                    currentScreen = Screen.OUTGOING_CALL
                },
                onAccountClick = {
                    currentScreen = Screen.ACCOUNT
                },
                onTestIncomingCall = {
                    appScope.launch { callService.triggerTestIncomingCall() }
                },
                onSignOut = {
                    currentScreen = Screen.AUTH
                }
            )

            Screen.OUTGOING_CALL -> {
                val call = activeCall
                if (call == null) {
                    LaunchedEffect(Unit) { endCallAndGoHome() }
                } else {
                    OutgoingCallScreen(
                        contactName = call.name,
                        phoneNumber = call.phoneNumber,
                        callEvents = callEvents,
                        callService = callService,
                        modifier = Modifier.padding(innerPadding),
                        onConnected = { sid ->
                            activeCall = call.copy(callSid = sid)
                            currentScreen = Screen.TRANSCRIPTION
                        },
                        onFinished = { endCallAndGoHome() }
                    )
                }
            }

            Screen.INCOMING_CALL -> {
                val call = activeCall
                val sid = call?.callSid
                if (call == null || sid == null) {
                    LaunchedEffect(Unit) { endCallAndGoHome() }
                } else {
                    // key on sid so a new incoming call gets fresh screen state
                    key(sid) {
                        IncomingCallScreen(
                            callSid = sid,
                            callerName = call.name,
                            phoneNumber = call.phoneNumber,
                            callEvents = callEvents,
                            callService = callService,
                            modifier = Modifier.padding(innerPadding),
                            onAccepted = { currentScreen = Screen.TRANSCRIPTION },
                            onFinished = { endCallAndGoHome() }
                        )
                    }
                }
            }

            Screen.TRANSCRIPTION -> TranscriptionScreen(
                contactName = activeCall?.name,
                contactPhoneNumber = activeCall?.phoneNumber,
                callSid = activeCall?.callSid,
                callEvents = callEvents,
                modifier = Modifier.padding(innerPadding),
                onCallEnded = { activeCallEnded = true },
                onBack = { endCallAndGoHome() }
            )

            Screen.ACCOUNT -> AccountScreen(
                modifier = Modifier.padding(innerPadding),
                onBack = { currentScreen = Screen.HOME },
                onSignOut = { currentScreen = Screen.AUTH },
                onNavigateToPersonalInfo = { currentScreen = Screen.PERSONAL_INFO },
                onNavigateToCaptionSettings = { currentScreen = Screen.CAPTION_SETTINGS },
                onNavigateToNotifications = { currentScreen = Screen.NOTIFICATIONS },
                onNavigateToSecurity = { currentScreen = Screen.SECURITY_SETTINGS }
            )
            Screen.PERSONAL_INFO -> PersonalInfoScreen(
                modifier = Modifier.padding(innerPadding),
                onBack = { currentScreen = Screen.ACCOUNT }
            )
            Screen.CAPTION_SETTINGS -> CaptionSettingsScreen(
                modifier = Modifier.padding(innerPadding),
                onBack = { currentScreen = Screen.ACCOUNT }
            )
            Screen.NOTIFICATIONS -> NotificationsScreen(
                modifier = Modifier.padding(innerPadding),
                onBack = { currentScreen = Screen.ACCOUNT }
            )
            Screen.SECURITY_SETTINGS -> SecuritySettingsScreen(
                modifier = Modifier.padding(innerPadding),
                onBack = { currentScreen = Screen.ACCOUNT }
            )

            Screen.ERROR -> ErrorScreen(
                modifier = Modifier.padding(innerPadding),
                onBackToLogin = { currentScreen = Screen.AUTH },
                onSignUpClick = {
                    currentScreen = Screen.AUTH
                }
            )
        }
    }
}
