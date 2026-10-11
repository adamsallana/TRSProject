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

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        when (currentScreen) {
            Screen.AUTH -> AuthScreen(
                modifier = Modifier.padding(innerPadding),
                onSignInSuccess = { currentScreen = Screen.TRANSCRIPTION }
            )
            Screen.TRANSCRIPTION -> TranscriptionScreen(
                modifier = Modifier.padding(innerPadding),
                onSignOut = { currentScreen = Screen.AUTH }
            )
        }
    }
}
