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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.trsproject.android.data.model.ActiveCall
import com.trsproject.android.data.model.CallDirection
import com.trsproject.android.data.model.CallLog
import com.trsproject.android.data.model.UserProfile
import com.trsproject.android.data.remote.SupabaseService
import io.github.jan.supabase.auth.auth
import com.trsproject.android.network.CallEvent
import com.trsproject.android.network.CallService
import com.trsproject.android.network.phoneNumbersMatch
import com.trsproject.android.ui.navigation.Screen
import com.trsproject.android.ui.screens.AccountScreen
import com.trsproject.android.ui.screens.AuthScreen
import com.trsproject.android.ui.screens.CallHistoryScreen
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

/**
 * The signed-in user's TRS relay number (their own Twilio number), or null if they haven't set one
 * or it can't be loaded. Null makes the app use the server's shared default number.
 */
private suspend fun loadLineNumber(): String? {
    return try {
        val userId = SupabaseService.client.auth.currentUserOrNull()?.id ?: return null
        SupabaseService.fetchUserProfile(userId).trsRelayNumber?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        android.util.Log.w("MainApp", "Couldn't load TRS relay number: ${e.message}")
        null
    }
}

private val SIGNED_OUT_SCREENS = setOf(Screen.AUTH, Screen.ERROR)
private val IN_CALL_SCREENS = setOf(Screen.OUTGOING_CALL, Screen.INCOMING_CALL, Screen.TRANSCRIPTION)

@Composable
fun MainApp() {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf(Screen.AUTH) }
    var activeCall by remember { mutableStateOf<ActiveCall?>(null) }
    // True once the call on the transcription screen has ended (its "Call Ended" view is showing)
    var activeCallEnded by remember { mutableStateOf(false) }

    var callStartTimeMs by remember { mutableStateOf<Long?>(null) }
    var currentCallLogged by remember { mutableStateOf(false) }

    val callService = remember { CallService() }
    val appScope = rememberCoroutineScope()
    // Call events from the server, re-emitted to whichever call screen is showing
    val callEvents = remember { MutableSharedFlow<CallEvent>(extraBufferCapacity = 32) }
    val isSignedIn = currentScreen !in SIGNED_OUT_SCREENS

    fun saveCallLog(
        direction: String,
        phoneNumber: String,
        name: String?,
        sid: String?,
        durationSec: Int,
        status: String
    ) {
        if (currentCallLogged) return
        currentCallLogged = true
        appScope.launch {
            try {
                val userId = SupabaseService.client.auth.currentUserOrNull()?.id ?: "user_default"
                val nowStr = java.time.Instant.now().toString()
                val log = CallLog(
                    id = java.util.UUID.randomUUID().toString(),
                    userId = userId,
                    callSid = sid,
                    contactName = name,
                    phoneNumber = phoneNumber,
                    direction = direction,
                    status = status,
                    durationSeconds = durationSec,
                    startedAt = nowStr
                )
                SupabaseService.addCallLog(log)
            } catch (e: Exception) {
                android.util.Log.e("MainApp", "Failed to save call log: ${e.message}")
            }
        }
    }

    fun endCallAndGoHome(statusIfUnsaved: String = "COMPLETED") {
        val call = activeCall
        if (call != null && !currentCallLogged) {
            val durationSec = callStartTimeMs?.let { ((System.currentTimeMillis() - it) / 1000).toInt() } ?: 0
            val dir = if (call.direction == CallDirection.INCOMING) "INCOMING" else "OUTGOING"
            saveCallLog(
                direction = dir,
                phoneNumber = call.phoneNumber,
                name = call.name,
                sid = call.callSid,
                durationSec = durationSec,
                status = statusIfUnsaved
            )
        }
        activeCall = null
        currentScreen = Screen.HOME
    }

    // The user's own Twilio number decides which calls the server sends this app. Reloaded on
    // sign-in and after the user leaves Personal Info (where they can change it).
    var lineNumber by remember { mutableStateOf<String?>(null) }
    var lineLoaded by remember { mutableStateOf(false) }
    var profileVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(isSignedIn, profileVersion) {
        if (!isSignedIn) {
            CallService.lineNumber = null
            lineNumber = null
            lineLoaded = false
            return@LaunchedEffect
        }
        val number = loadLineNumber()
        CallService.lineNumber = number
        lineNumber = number
        lineLoaded = true
    }

    // One app-wide socket for call events while signed in, so incoming calls ring on any screen.
    // Reconnects after a short delay if the connection drops, or when the user's number changes.
    LaunchedEffect(isSignedIn, lineLoaded, lineNumber) {
        if (!isSignedIn || !lineLoaded) return@LaunchedEffect
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
                        // sends calls to every app signed in on this number, so declining here would
                        // hang up on a call another device may want to answer. Unanswered, it times out.
                        android.util.Log.d("MainApp", "Busy; ignoring incoming call ${event.callSid}")
                    } else {
                        activeCallEnded = false
                        currentCallLogged = false
                        callStartTimeMs = System.currentTimeMillis()
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
                    appScope.launch {
                        val user = try { SupabaseService.client.auth.currentUserOrNull() } catch (e: Exception) { null }
                        val profile = user?.let { try { SupabaseService.fetchUserProfile(it.id) } catch (e: Exception) { null } }

                        val userRelayNumber: String? = profile?.trsRelayNumber?.takeIf { it.isNotBlank() }
                            ?: profile?.phoneNumber?.takeIf { it.isNotBlank() }

                        if (userRelayNumber.isNullOrBlank()) {
                            android.widget.Toast.makeText(
                                context,
                                "Error: No phone number configured for your account. Please set up your phone numbers in Account Settings.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            currentScreen = Screen.PERSONAL_INFO
                            return@launch
                        }

                        val number = contact.phoneNumber.orEmpty()
                        if (number.isBlank()) {
                            android.widget.Toast.makeText(
                                context,
                                "Error: Contact has no valid phone number.",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }

                        activeCallEnded = false
                        currentCallLogged = false
                        callStartTimeMs = System.currentTimeMillis()
                        activeCall = ActiveCall(
                            direction = CallDirection.OUTGOING,
                            name = contact.name?.ifBlank { null } ?: number,
                            phoneNumber = number,
                            userRelayNumber = userRelayNumber
                        )
                        currentScreen = Screen.OUTGOING_CALL
                    }
                },
                onHistoryClick = {
                    currentScreen = Screen.CALL_HISTORY
                },
                onAccountClick = {
                    currentScreen = Screen.ACCOUNT
                },
                onTestIncomingCall = {
                    appScope.launch { callService.triggerTestIncomingCall() }
                },
                onSignOut = {
                    SupabaseService.clearCache()
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
                        userRelayNumber = call.userRelayNumber,
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
                            onAccepted = {
                                callStartTimeMs = System.currentTimeMillis()
                                currentScreen = Screen.TRANSCRIPTION
                            },
                            onFinished = { endCallAndGoHome(statusIfUnsaved = "DECLINED") }
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

            Screen.CALL_HISTORY -> CallHistoryScreen(
                modifier = Modifier.padding(innerPadding),
                callService = callService,
                onBack = { currentScreen = Screen.HOME },
                onCallClick = { phone, name ->
                    appScope.launch {
                        val user = try { SupabaseService.client.auth.currentUserOrNull() } catch (e: Exception) { null }
                        val profile = user?.let { try { SupabaseService.fetchUserProfile(it.id) } catch (e: Exception) { null } }

                        val userRelayNumber: String? = profile?.trsRelayNumber?.takeIf { it.isNotBlank() }
                            ?: profile?.phoneNumber?.takeIf { it.isNotBlank() }

                        if (userRelayNumber.isNullOrBlank()) {
                            android.widget.Toast.makeText(
                                context,
                                "Error: No phone number configured for your account. Please set up your phone numbers in Account Settings.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            currentScreen = Screen.PERSONAL_INFO
                            return@launch
                        }

                        if (phone.isBlank()) {
                            android.widget.Toast.makeText(
                                context,
                                "Error: Contact has no valid phone number.",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }

                        activeCallEnded = false
                        currentCallLogged = false
                        callStartTimeMs = System.currentTimeMillis()
                        activeCall = ActiveCall(
                            direction = CallDirection.OUTGOING,
                            name = name?.ifBlank { null } ?: phone,
                            phoneNumber = phone,
                            userRelayNumber = userRelayNumber
                        )
                        currentScreen = Screen.OUTGOING_CALL
                    }
                }
            )

            Screen.ACCOUNT -> AccountScreen(
                modifier = Modifier.padding(innerPadding),
                onBack = { currentScreen = Screen.HOME },
                onSignOut = {
                    SupabaseService.clearCache()
                    currentScreen = Screen.AUTH
                },
                onNavigateToPersonalInfo = { currentScreen = Screen.PERSONAL_INFO },
                onNavigateToCaptionSettings = { currentScreen = Screen.CAPTION_SETTINGS },
                onNavigateToNotifications = { currentScreen = Screen.NOTIFICATIONS },
                onNavigateToSecurity = { currentScreen = Screen.SECURITY_SETTINGS },
                onNavigateToCallHistory = { currentScreen = Screen.CALL_HISTORY }
            )
            Screen.PERSONAL_INFO -> PersonalInfoScreen(
                modifier = Modifier.padding(innerPadding),
                onBack = {
                    profileVersion++ // pick up a changed TRS relay number
                    currentScreen = Screen.ACCOUNT
                }
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
