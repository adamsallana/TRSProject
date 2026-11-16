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
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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

// ---- Local (filesDir) transcripts screen ----

data class LocalTranscript(
    val file: File,
    val name: String,
    val date: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val transcripts = remember { mutableStateListOf<LocalTranscript>() }
    var fileToDelete by remember { mutableStateOf<LocalTranscript?>(null) }

    fun loadTranscripts() {
        transcripts.clear()
        val filesDir = context.filesDir
        val files = filesDir.listFiles { _, name -> name.startsWith("transcript_") && name.endsWith(".txt") }
        if (files != null) {
            val sorted = files.sortedByDescending { it.lastModified() }
            for (file in sorted) {
                val dateStr = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(file.lastModified()))
                transcripts.add(LocalTranscript(file = file, name = file.name, date = dateStr))
            }
        }
    }

    LaunchedEffect(Unit) {
        loadTranscripts()
    }

    if (fileToDelete != null) {
        val target = fileToDelete!!
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete Transcript") },
            text = { Text("Are you sure you want to delete this saved transcript from your phone?") },
            confirmButton = {
                Button(
                    onClick = {
                        target.file.delete()
                        transcripts.remove(target)
                        fileToDelete = null
                        Toast.makeText(context, "Transcript deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
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
                .padding(16.dp)
        ) {
            if (transcripts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No saved call transcripts found locally.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(transcripts, key = { it.file.absolutePath }) { item ->
                        TranscriptCard(
                            transcript = item,
                            onDownload = {
                                try {
                                    val text = item.file.readText()
                                    val externalDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                                    externalDir.mkdirs()
                                    val dest = File(externalDir, item.name)
                                    dest.writeText(text)
                                    Toast.makeText(context, "Downloaded to:\n${dest.absolutePath}", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to download: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDelete = {
                                fileToDelete = item
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TranscriptCard(
    transcript: LocalTranscript,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = "Transcript",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = transcript.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = transcript.date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDownload) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download Transcript",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Transcript",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}
