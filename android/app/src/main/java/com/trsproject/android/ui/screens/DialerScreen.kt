package com.trsproject.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trsproject.android.data.model.Contact
import com.trsproject.android.data.remote.SupabaseService
import com.trsproject.android.network.phoneNumbersMatch
import io.github.jan.supabase.auth.auth

/** Formats raw phone number input into standard display format (e.g. "(828) 275-1713"). */
fun formatDisplayPhoneNumber(input: String): String {
    if (input.isBlank()) return ""

    if (input.contains("*") || input.contains("#")) {
        return input
    }

    val startsWithPlus = input.startsWith("+")
    val cleanDigits = input.filter { it.isDigit() }

    if (cleanDigits.isEmpty()) return input

    return when {
        startsWithPlus || (cleanDigits.length == 11 && cleanDigits.startsWith("1")) -> {
            val digits = if (cleanDigits.startsWith("1")) cleanDigits.substring(1) else cleanDigits
            when {
                digits.length <= 3 -> "+1 ($digits"
                digits.length <= 6 -> "+1 (${digits.substring(0, 3)}) ${digits.substring(3)}"
                digits.length <= 10 -> "+1 (${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"
                else -> "+1 (${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6, 10)} ${digits.substring(10)}"
            }
        }
        cleanDigits.length <= 3 -> {
            if (cleanDigits.length == 3) "($cleanDigits)" else cleanDigits
        }
        cleanDigits.length <= 6 -> {
            "(${cleanDigits.substring(0, 3)}) ${cleanDigits.substring(3)}"
        }
        cleanDigits.length <= 10 -> {
            "(${cleanDigits.substring(0, 3)}) ${cleanDigits.substring(3, 6)}-${cleanDigits.substring(6)}"
        }
        else -> input
    }
}

private data class NumpadKey(
    val digit: String,
    val letters: String
)

private val NUMPAD_ROWS = listOf(
    listOf(NumpadKey("1", ""), NumpadKey("2", "ABC"), NumpadKey("3", "DEF")),
    listOf(NumpadKey("4", "GHI"), NumpadKey("5", "JKL"), NumpadKey("6", "MNO")),
    listOf(NumpadKey("7", "PQRS"), NumpadKey("8", "TUV"), NumpadKey("9", "WXYZ")),
    listOf(NumpadKey("*", ""), NumpadKey("0", "+"), NumpadKey("#", ""))
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(
    modifier: Modifier = Modifier,
    contacts: List<Contact>? = null,
    onCallClick: (formattedNumber: String, matchedContactName: String?) -> Unit = { _, _ -> }
) {
    var rawInput by remember { mutableStateOf("") }
    val fetchedContacts = remember { mutableStateListOf<Contact>() }

    LaunchedEffect(Unit) {
        if (contacts == null) {
            try {
                val user = SupabaseService.client.auth.currentUserOrNull()
                val userId = user?.id ?: "user_default"
                val list = SupabaseService.fetchContacts(userId)
                fetchedContacts.clear()
                fetchedContacts.addAll(list.filterNot { it.id == "1" && it.name == "Contact" })
            } catch (e: Exception) {
                // Ignore failure in preview or unauthenticated state
            }
        }
    }

    val activeContacts = contacts ?: fetchedContacts
    val matchedContact = remember(rawInput, activeContacts) {
        if (rawInput.isBlank()) null
        else activeContacts.firstOrNull { phoneNumbersMatch(it.phoneNumber, rawInput) }
    }
    val matchedContactName = matchedContact?.name?.takeIf { it.isNotBlank() }
    val formattedNumber = remember(rawInput) { formatDisplayPhoneNumber(rawInput) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Keypad", fontWeight = FontWeight.Bold) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header / Number Display Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                // Matched Contact Name Preview
                if (matchedContactName != null) {
                    Text(
                        text = matchedContactName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Spacer(modifier = Modifier.height(24.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Formatted Number & Backspace Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(48.dp))

                    Text(
                        text = formattedNumber,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        fontSize = 32.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )

                    if (rawInput.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = {
                                        if (rawInput.isNotEmpty()) {
                                            rawInput = rawInput.dropLast(1)
                                        }
                                    },
                                    onLongClick = {
                                        rawInput = ""
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Backspace",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(48.dp))
                    }
                }
            }

            // Numpad Grid (3x4)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                for (row in NUMPAD_ROWS) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (key in row) {
                            KeypadButton(
                                key = key,
                                onClick = { rawInput += key.digit },
                                onLongClick = if (key.digit == "0") {
                                    { rawInput += "+" }
                                } else null
                            )
                        }
                    }
                }
            }

            // Call Button Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                FloatingActionButton(
                    onClick = {
                        onCallClick(formattedNumber, matchedContactName)
                    },
                    shape = CircleShape,
                    containerColor = Color(0xFF4CAF50),
                    contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Call",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeypadButton(
    key: NumpadKey,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = key.digit,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (key.letters.isNotEmpty()) {
                Text(
                    text = key.letters,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}