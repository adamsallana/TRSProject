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
    var transcriptSummaries by remember { mutableStateOf(initialPreferences.transcriptSummariesEnabled) }
    var soundVibration by remember { mutableStateOf(initialPreferences.soundVibrationEnabled) }
    var emailNotifications by remember { mutableStateOf(initialPreferences.emailNotificationsEnabled) }
    var isSaving by remember { mutableStateOf(false) }

    fun savePreferences() {
        isSaving = true
        coroutineScope.launch {
            val updated = NotificationPreferences(
                callAlertsEnabled = callAlerts,
                transcriptSummariesEnabled = transcriptSummaries,
                soundVibrationEnabled = soundVibration,
                emailNotificationsEnabled = emailNotifications
            )
            val success = SupabaseService.updateNotificationPreferences(updated)
            isSaving = false
            if (success) {
                Toast.makeText(context, "Notification settings saved to Supabase!", Toast.LENGTH_SHORT).show()
                onBack()
            } else {
                Toast.makeText(context, "Saved locally (Supabase offline)", Toast.LENGTH_SHORT).show()
                onBack()
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
                text = "Call & Summary Preferences",
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
                            Text(text = "Transcript Summaries", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(text = "Receive notifications when call transcripts are generated", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = transcriptSummaries, onCheckedChange = { transcriptSummaries = it })
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

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Email Notifications", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(text = "Send call summaries and system updates via email", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = emailNotifications, onCheckedChange = { emailNotifications = it })
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { savePreferences() },
                enabled = !isSaving,
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
