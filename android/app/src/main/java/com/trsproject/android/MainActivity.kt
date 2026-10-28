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
import com.trsproject.android.ui.screens.AuthScreen
import com.trsproject.android.ui.screens.ErrorScreen
import com.trsproject.android.ui.screens.HomeScreen
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
                    currentScreen = Screen.TRANSCRIPTION
                },
                onSignOut = {
                    currentScreen = Screen.AUTH
                }
            )
            Screen.TRANSCRIPTION -> TranscriptionScreen(
                contactName = selectedContactName,
                modifier = Modifier.padding(innerPadding),
                onBack = { currentScreen = Screen.HOME }
            )
            Screen.ERROR -> ErrorScreen(
                modifier = Modifier.padding(innerPadding),
                onBackToLogin = { currentScreen = Screen.AUTH },
                onSignUpClick = { currentScreen = Screen.AUTH }
            )
        }
    }
}
