package com.trsproject.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.trsproject.android.data.model.NotificationPreferences
import com.trsproject.android.data.remote.SupabaseService
import com.trsproject.android.ui.theme.TRSProjectTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import io.github.jan.supabase.auth.auth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    modifier: Modifier = Modifier,
    initialPreferences: NotificationPreferences = NotificationPreferences(),
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var callAlerts by remember { mutableStateOf(initialPreferences.callAlertsEnabled) }
    var soundVibration by remember { mutableStateOf(initialPreferences.soundVibrationEnabled) }
    var isSaving by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val user = SupabaseService.client.auth.currentUserOrNull()

            if (user == null) {
                loadFailed = true
                Toast.makeText(
                    context,
                    "Please sign in to view notification settings.",
                    Toast.LENGTH_LONG
                ).show()
                return@LaunchedEffect
            }

            val prefs = SupabaseService.fetchNotificationPreferences(user.id)

            callAlerts = prefs.callAlertsEnabled
            soundVibration = prefs.soundVibrationEnabled

        } catch (e: Exception) {
            loadFailed = true
            Toast.makeText(
                context,
                "Unable to load notification settings.",
                Toast.LENGTH_LONG
            ).show()
        } finally {
            isLoading = false
        }
    }

    fun savePreferences() {
        if (isSaving || isLoading || loadFailed) return

        isSaving = true

        coroutineScope.launch {
            try {
                val userId = SupabaseService.client.auth.currentUserOrNull()?.id

                if (userId == null) {
                    Toast.makeText(
                        context,
                        "Please sign in before saving notification settings.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val updated = NotificationPreferences(
                    userId = userId,
                    callAlertsEnabled = callAlerts,
                    transcriptSummariesEnabled = initialPreferences.transcriptSummariesEnabled,
                    soundVibrationEnabled = soundVibration,
                    emailNotificationsEnabled = initialPreferences.emailNotificationsEnabled
                )

                val success = SupabaseService.updateNotificationPreferences(updated)

                if (success) {
                    Toast.makeText(
                        context,
                        "Notification settings saved successfully!",
                        Toast.LENGTH_SHORT
                    ).show()
                    onBack()
                } else {
                    Toast.makeText(
                        context,
                        "Unable to save notification settings. Please try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Unable to save notification settings. Please try again.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isSaving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Call Alert Preferences",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Incoming Call Alerts", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(text = "Receive banner popups when an incoming TRS call arrives", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = callAlerts, onCheckedChange = { callAlerts = it })
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Sound & Vibration", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(text = "Play sound and haptic vibration on alerts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = soundVibration, onCheckedChange = { soundVibration = it })
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { savePreferences() },
                enabled = !isSaving && !isLoading && !loadFailed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = if (isSaving) "Saving..." else "Save Notification Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun NotificationsScreenPreview() {
    TRSProjectTheme {
        NotificationsScreen()
    }
}
