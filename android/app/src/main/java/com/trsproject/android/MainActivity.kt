package com.trsproject.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.trsproject.android.ui.navigation.Screen
import com.trsproject.android.ui.screens.AuthScreen
import com.trsproject.android.ui.screens.HomeScreen
import com.trsproject.android.ui.screens.TranscriptionScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.trsproject.android.ui.theme.TRSProjectTheme


//For now, restricting # of transcriptions
private const val MAX_TRANSCRIPT_LINES = 50

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TRSProjectTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TranscribeScreen(modifier = Modifier.padding(innerPadding))
                }
                MainApp()
            }
        }
    }
}

@Composable
fun TranscribeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val available = remember { SpeechTranscriber.isAvailable(context) }

    var status by remember {
        mutableStateOf(if (available) "Idle" else "Speech recognition not available on this device")
    }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    val transcripts = remember { mutableStateListOf<String>() }

    val transcriber = remember {
        SpeechTranscriber(
            context = context,
            onPartial = { partial = it },
            onFinal = { text ->
                partial = ""
                transcripts.add(0, text)
                if (transcripts.size > MAX_TRANSCRIPT_LINES) transcripts.removeAt(transcripts.lastIndex)
            },
            onStatus = { status = it },
            onStopped = { listening = false },
        )
    }
    DisposableEffect(transcriber) {
        onDispose { transcriber.destroy() }
    }

    fun startListening() {
        listening = true
        partial = ""
        transcriber.start()
    }

//    asking for mic permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else status = "Microphone permission denied"
    }

    //UI Layout
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = status, style = MaterialTheme.typography.bodyMedium)

        Button(enabled = available, onClick = {
            when {
                listening -> transcriber.stop()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED -> startListening()
                else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }) {
            Text(if (listening) "Stop" else "Start listening")
        }

        if (partial.isNotEmpty()) {
            Text(text = partial, style = MaterialTheme.typography.bodyLarge)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(transcripts) { line ->
                Text(text = line, style = MaterialTheme.typography.bodySmall)
            }
        }
fun MainApp() {
    var currentScreen by remember { mutableStateOf(Screen.AUTH) }
    var selectedContactName by remember { mutableStateOf<String?>(null) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        when (currentScreen) {
            Screen.AUTH -> AuthScreen(
                modifier = Modifier.padding(innerPadding),
                onSignInSuccess = { currentScreen = Screen.HOME }
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
        }
    }
}
