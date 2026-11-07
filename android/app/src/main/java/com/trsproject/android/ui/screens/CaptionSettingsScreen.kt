package com.trsproject.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.sp
import com.trsproject.android.data.model.CaptionPreferences
import com.trsproject.android.data.remote.SupabaseService
import com.trsproject.android.ui.theme.TRSProjectTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionSettingsScreen(
    modifier: Modifier = Modifier,
    initialPreferences: CaptionPreferences = CaptionPreferences(),
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var selectedSize by remember { mutableStateOf(initialPreferences.fontSize) }
    var autoCaption by remember { mutableStateOf(initialPreferences.autoCaptionEnabled) }
    var highContrast by remember { mutableStateOf(initialPreferences.highContrastEnabled) }
    var isSaving by remember { mutableStateOf(false) }

    val fontSizes = listOf("Small", "Medium", "Large", "Extra Large")

    val textFontSize = when (selectedSize) {
        "Small" -> 14.sp
        "Large" -> 20.sp
        "Extra Large" -> 24.sp
        else -> 16.sp
    }

    fun savePreferences() {
        isSaving = true
        coroutineScope.launch {
            val updated = CaptionPreferences(
                fontSize = selectedSize,
                autoCaptionEnabled = autoCaption,
                highContrastEnabled = highContrast
            )
            val success = SupabaseService.updateCaptionPreferences(updated)
            isSaving = false
            if (success) {
                Toast.makeText(context, "Caption settings synced to Supabase!", Toast.LENGTH_SHORT).show()
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
                title = { Text("Captioning & Speech") },
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
                text = "Live Caption Preview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // Live Caption Preview Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (highContrast) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp)
            ) {
                Text(
                    text = "Hello! This is how your captions will appear during active TRS phone calls.",
                    fontSize = textFontSize,
                    color = if (highContrast) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = textFontSize * 1.3
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Caption Font Size",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                fontSizes.forEach { size ->
                    FilterChip(
                        selected = selectedSize == size,
                        onClick = { selectedSize = size },
                        label = { Text(size, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                            Text(text = "Auto-Start Captioning", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(text = "Automatically listen and display live captions on call connect", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = autoCaption, onCheckedChange = { autoCaption = it })
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "High Contrast Captions", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(text = "Use bold dark background for maximum readability", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = highContrast, onCheckedChange = { highContrast = it })
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
                    text = if (isSaving) "Saving..." else "Save Caption Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CaptionSettingsScreenPreview() {
    TRSProjectTheme {
        CaptionSettingsScreen()
    }
}
