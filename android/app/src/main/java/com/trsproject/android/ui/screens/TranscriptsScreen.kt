package com.trsproject.android.ui.screens

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TranscriptFile(
    val file: File,
    val contactName: String,
    val timestampMs: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptsListScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onTranscriptClick: (String) -> Unit
) {
    val context = LocalContext.current
    var transcripts by remember { mutableStateOf<List<TranscriptFile>>(emptyList()) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }

    fun loadTranscripts() {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val files = directory?.listFiles { _, name -> name.startsWith("Transcription_") && name.endsWith(".txt") }
            ?.map { file ->
                // filename format: Transcription_{safeName}_{yyyyMMdd_HHmmss}.txt
                val nameParts = file.name.substringAfter("Transcription_").substringBefore(".txt").split("_")
                val safeName = if (nameParts.size >= 3) nameParts.dropLast(2).joinToString("_") else nameParts.firstOrNull() ?: "Unknown"
                TranscriptFile(
                    file = file,
                    contactName = safeName.replace("_", " "),
                    timestampMs = file.lastModified()
                )
            }
            ?.sortedByDescending { it.timestampMs } ?: emptyList()
        transcripts = files
    }

    LaunchedEffect(Unit) {
        loadTranscripts()
    }

    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete Transcript") },
            text = { Text("Are you sure you want to delete this transcript? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        fileToDelete?.delete()
                        loadTranscripts()
                        fileToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Transcripts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        if (transcripts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No saved transcripts found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(transcripts, key = { it.file.absolutePath }) { transcript ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTranscriptClick(transcript.file.absolutePath) },
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.padding(end = 16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = "Transcript",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = transcript.contactName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(transcript.timestampMs)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = { fileToDelete = transcript.file }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptDetailScreen(
    filePath: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val file = File(filePath)
    var parsedCaptions by remember { mutableStateOf<List<CaptionMessage>>(emptyList()) }
    var title by remember { mutableStateOf("Transcript") }

    LaunchedEffect(filePath) {
        if (file.exists()) {
            val lines = file.readLines()
            if (lines.size > 0) {
                title = lines[0]
            }
            // Skip first 3 lines (Header, Date, Divider)
            val captionLines = if (lines.size > 3) lines.drop(3) else emptyList()
            
            val captions = captionLines.mapIndexedNotNull { index, line ->
                val separatorIndex = line.indexOf(": ")
                if (separatorIndex != -1) {
                    val senderName = line.substring(0, separatorIndex)
                    val text = line.substring(separatorIndex + 2)
                    CaptionMessage(
                        id = index,
                        senderName = senderName,
                        text = text,
                        isMe = senderName == "Me",
                        failed = false,
                        isFinal = true
                    )
                } else {
                    null
                }
            }
            parsedCaptions = captions
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(parsedCaptions) { caption ->
                CaptionBubble(caption = caption)
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
