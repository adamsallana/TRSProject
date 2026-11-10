package com.trsproject.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.trsproject.android.data.model.CallLog
import com.trsproject.android.data.model.Contact
import com.trsproject.android.data.remote.SupabaseService
import com.trsproject.android.ui.theme.TRSProjectTheme
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import com.trsproject.android.network.CallService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(
    modifier: Modifier = Modifier,
    callService: CallService = remember { CallService() },
    onBack: () -> Unit = {},
    onCallClick: (phoneNumber: String, contactName: String?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val callLogList = remember { mutableStateListOf<CallLog>() }
    var selectedFilter by remember { mutableStateOf("All") }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Dialog states
    var isClearAllDialogOpen by remember { mutableStateOf(false) }
    var isClearingAll by remember { mutableStateOf(false) }

    var logToDelete by remember { mutableStateOf<CallLog?>(null) }
    var isDeletingLog by remember { mutableStateOf(false) }

    fun loadCallLogs(forceRefresh: Boolean = false) {
        coroutineScope.launch {
            if (forceRefresh) isRefreshing = true else isLoading = true
            try {
                val userId = SupabaseService.client.auth.currentUserOrNull()?.id ?: "user_default"
                val contacts = try {
                    SupabaseService.fetchContacts(userId, forceRefresh = forceRefresh)
                } catch (e: Exception) {
                    emptyList<com.trsproject.android.data.model.Contact>()
                }
                val supabaseLogs = SupabaseService.fetchCallLogs(userId, forceRefresh = forceRefresh)
                val serverLogs = try {
                    callService.fetchCallLogsFromServer()
                } catch (e: Exception) {
                    emptyList()
                }

                val merged = (supabaseLogs + serverLogs)
                    .distinctBy { log -> log.callSid?.takeIf { it.isNotBlank() } ?: log.id }
                    .map { log ->
                        val matchedContact = contacts.firstOrNull { contact ->
                            com.trsproject.android.network.phoneNumbersMatch(contact.phoneNumber, log.phoneNumber)
                        }
                        val resolvedName = matchedContact?.name?.takeIf { it.isNotBlank() } ?: log.contactName
                        log.copy(contactName = resolvedName)
                    }
                    .sortedByDescending { it.startedAt ?: "" }

                callLogList.clear()
                callLogList.addAll(merged)
            } catch (e: Exception) {
                // Keep current call logs or handle error
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadCallLogs(forceRefresh = false)
    }

    // Confirmation Dialog for Clear All
    if (isClearAllDialogOpen) {
        AlertDialog(
            onDismissRequest = {
                if (!isClearingAll) isClearAllDialogOpen = false
            },
            title = {
                Text(
                    text = "Clear Call History",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Are you sure you want to delete all call history logs? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        isClearingAll = true
                        coroutineScope.launch {
                            val userId = SupabaseService.client.auth.currentUserOrNull()?.id ?: "user_default"
                            SupabaseService.clearCallLogs(userId)
                            callLogList.clear()
                            isClearingAll = false
                            isClearAllDialogOpen = false
                            Toast.makeText(context, "Call history cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !isClearingAll
                ) {
                    Text(if (isClearingAll) "Clearing..." else "Clear All")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { isClearAllDialogOpen = false },
                    enabled = !isClearingAll
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirmation Dialog for Single Delete
    if (logToDelete != null) {
        val log = logToDelete!!
        AlertDialog(
            onDismissRequest = {
                if (!isDeletingLog) logToDelete = null
            },
            title = {
                Text(
                    text = "Delete Call Log",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Delete this call log entry?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDeletingLog = true
                        coroutineScope.launch {
                            SupabaseService.deleteCallLog(log.id)
                            callLogList.removeAll { it.id == log.id }
                            isDeletingLog = false
                            logToDelete = null
                            Toast.makeText(context, "Call log deleted", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !isDeletingLog
                ) {
                    Text(if (isDeletingLog) "Deleting..." else "Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { logToDelete = null },
                    enabled = !isDeletingLog
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call History") },
                actions = {
                    IconButton(onClick = { loadCallLogs(forceRefresh = true) }, enabled = !isRefreshing) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                    if (callLogList.isNotEmpty()) {
                        IconButton(onClick = { isClearAllDialogOpen = true }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear All Call Logs",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
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
                .padding(horizontal = 16.dp)
        ) {
            // Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filters = listOf("All", "Missed", "Outgoing", "Incoming")
                filters.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter) }
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val filteredLogs = callLogList.filter { log ->
                    val isMissed = log.status.equals("MISSED", ignoreCase = true) || log.status.equals("DECLINED", ignoreCase = true)
                    when (selectedFilter) {
                        "Missed" -> isMissed
                        "Outgoing" -> log.direction.equals("OUTGOING", ignoreCase = true) && !isMissed
                        "Incoming" -> log.direction.equals("INCOMING", ignoreCase = true) && !isMissed
                        else -> true
                    }
                }

                if (filteredLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            text = if (callLogList.isEmpty()) "No call history available" else "No $selectedFilter call logs",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    ) {
                        items(filteredLogs, key = { it.id }) { log ->
                            CallLogItemRow(
                                log = log,
                                onCallClick = { onCallClick(log.phoneNumber, log.contactName) },
                                onDeleteClick = { logToDelete = log }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CallLogItemRow(
    log: CallLog,
    onCallClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMissed = log.status.equals("MISSED", ignoreCase = true) || log.status.equals("DECLINED", ignoreCase = true)
    val isIncoming = log.direction.equals("INCOMING", ignoreCase = true)

    val icon = when {
        isMissed -> Icons.Default.CallMissed
        isIncoming -> Icons.Default.CallReceived
        else -> Icons.Default.CallMade
    }

    val iconContainerColor = when {
        isMissed -> MaterialTheme.colorScheme.errorContainer
        isIncoming -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    val iconTintColor = when {
        isMissed -> MaterialTheme.colorScheme.onErrorContainer
        isIncoming -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    val titleText = log.contactName?.takeIf { it.isNotBlank() } ?: log.phoneNumber
    val statusText = when {
        log.status.equals("DECLINED", ignoreCase = true) -> "Declined"
        log.status.equals("MISSED", ignoreCase = true) -> "Missed"
        isIncoming -> "Incoming"
        else -> "Outgoing"
    }

    val formattedDuration = formatCallDuration(log.durationSeconds)
    val formattedTime = formatCallTimestamp(log.startedAt)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCallClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                shape = CircleShape,
                color = iconContainerColor,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(10.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = statusText,
                        tint = iconTintColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isMissed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (log.contactName?.isNotBlank() == true) {
                        Text(
                            text = log.phoneNumber,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = " • ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isMissed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (formattedDuration.isNotBlank()) {
                        Text(
                            text = " • $formattedDuration",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (formattedTime.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCallClick) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Call $titleText",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Call Log",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

private fun formatCallDuration(seconds: Int): String {
    if (seconds <= 0) return "0s"
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
}

private fun formatCallTimestamp(startedAt: String?): String {
    if (startedAt.isNullOrBlank()) return ""
    return try {
        val instant = Instant.parse(startedAt)
        val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (e: Exception) {
        startedAt.take(19).replace("T", " ")
    }
}

@Preview(showBackground = true)
@Composable
fun CallHistoryScreenPreview() {
    TRSProjectTheme {
        CallHistoryScreen()
    }
}
