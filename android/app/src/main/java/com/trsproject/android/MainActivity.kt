package com.trsproject.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.trsproject.android.ui.navigation.Screen
import com.trsproject.android.ui.screens.AccountScreen
import com.trsproject.android.ui.screens.AuthScreen
import com.trsproject.android.ui.screens.CaptionSettingsScreen
import com.trsproject.android.ui.screens.Contact
import com.trsproject.android.ui.screens.ErrorScreen
import com.trsproject.android.ui.screens.HomeScreen
import com.trsproject.android.ui.screens.NotificationsScreen
import com.trsproject.android.ui.screens.PersonalInfoScreen
import com.trsproject.android.ui.screens.SecuritySettingsScreen
import com.trsproject.android.ui.screens.TranscriptionScreen
import com.trsproject.android.ui.theme.TRSProjectTheme

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

@Composable
fun MainApp() {
    var currentScreen by remember { mutableStateOf(Screen.AUTH) }
    var selectedContactName by remember { mutableStateOf<String?>(null) }
    var selectedContactPhoneNumber by remember { mutableStateOf<String?>(null) }

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
                    selectedContactName = contact.name
                    selectedContactPhoneNumber = contact.phoneNumber
                    currentScreen = Screen.TRANSCRIPTION
                },
                onAccountClick = {
                    currentScreen = Screen.ACCOUNT
                },
                onSignOut = {
                    currentScreen = Screen.AUTH
                }
            )

            Screen.TRANSCRIPTION -> TranscriptionScreen(
                contactName = selectedContactName,
                contactPhoneNumber = selectedContactPhoneNumber,
                modifier = Modifier.padding(innerPadding),
                onBack = { currentScreen = Screen.HOME }
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
