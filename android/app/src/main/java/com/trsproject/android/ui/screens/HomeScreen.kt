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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.trsproject.android.data.model.Contact
import com.trsproject.android.data.model.UserProfile
import com.trsproject.android.data.remote.SupabaseService
import com.trsproject.android.network.CallService
import com.trsproject.android.ui.theme.TRSProjectTheme
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import java.util.UUID

val sampleFavorites = listOf(
    Contact("1", "", "Contact", "+18282751713")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    contacts: List<Contact> = sampleFavorites,
    onCallClick: (Contact) -> Unit = {},
    onAccountClick: () -> Unit = {},
    onSignOut: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var incomingCallContact by remember { mutableStateOf<Contact?>(null) }
    val callService = remember { CallService() }

    val contactList = remember { mutableStateListOf<Contact>() }
    var isAddContactDialogOpen by remember { mutableStateOf(false) }
    var newContactName by remember { mutableStateOf("") }
    var newContactPhone by remember { mutableStateOf("") }
    var isSavingContact by remember { mutableStateOf(false) }
    var userInitial by remember { mutableStateOf("U") }

    // Fetch user profile and contacts from Supabase backend on launch
    LaunchedEffect(Unit) {
        val user = try {
            SupabaseService.client.auth.currentUserOrNull()
        } catch (e: Exception) {
            null
        }

        val userId = user?.id ?: "user_default"
        val fetched = SupabaseService.fetchContacts(userId)
        contactList.clear()
        contactList.addAll(fetched)

        if (user != null) {
            try {
                val profile = SupabaseService.fetchUserProfile(user.id)
                val rawName: String? = profile.fullName?.takeIf { it.isNotBlank() }
                    ?: profile.displayName?.takeIf { it.isNotBlank() }
                    ?: user.email?.takeIf { it.isNotBlank() }

                if (!rawName.isNullOrBlank()) {
                    userInitial = rawName.trim().first().uppercase().toString()
                }
            } catch (e: Exception) {
                // Keep default initial if fetch fails
            }
        }
    }

    // Listen for real incoming call events from server over WebSocket
    LaunchedEffect(Unit) {
        try {
            val socket = callService.connectTranscriptionWebSocket("Client") { msg ->
                if (msg.text.startsWith("INCOMING_CALL:")) {
                    val parts = msg.text.split(":")
                    val callerNum = parts.getOrNull(1) ?: "+17818690609"
                    val callerName = parts.getOrNull(2) ?: "Contact"
                    incomingCallContact = Contact("inc", "", callerName, callerNum)
                }
            }
        } catch (e: Exception) {
            // Ignore connection errors on home screen
        }
    }

    // Add New Contact Dialog Overlay
    if (isAddContactDialogOpen) {
        AlertDialog(
            onDismissRequest = {
                if (!isSavingContact) {
                    isAddContactDialogOpen = false
                    newContactName = ""
                    newContactPhone = ""
                }
            },
            title = {
                Text(
                    text = "Add New Contact",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newContactName,
                        onValueChange = { newContactName = it },
                        label = { Text("Contact Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newContactPhone,
                        onValueChange = { newContactPhone = it },
                        label = { Text("Phone Number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newContactName.isBlank() || newContactPhone.isBlank()) {
                            Toast.makeText(context, "Please enter both name and phone number", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        isSavingContact = true
                        coroutineScope.launch {
                            val currentUserId = try {
                                SupabaseService.client.auth.currentUserOrNull()?.id ?: "user_default"
                            } catch (e: Exception) {
                                "user_default"
                            }

                            val newContact = Contact(
                                id = UUID.randomUUID().toString(),
                                userId = currentUserId,
                                name = newContactName.trim(),
                                phoneNumber = newContactPhone.trim()
                            )

                            val success = SupabaseService.createContact(newContact)
                            if (success) {
                                contactList.add(0, newContact)
                                Toast.makeText(context, "Contact created successfully!", Toast.LENGTH_SHORT).show()
                            } else {
                                // Add locally if backend fails
                                contactList.add(0, newContact)
                                Toast.makeText(context, "Contact saved locally", Toast.LENGTH_SHORT).show()
                            }

                            isSavingContact = false
                            isAddContactDialogOpen = false
                            newContactName = ""
                            newContactPhone = ""
                        }
                    },
                    enabled = !isSavingContact
                ) {
                    Text(if (isSavingContact) "Saving..." else "Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        isAddContactDialogOpen = false
                        newContactName = ""
                        newContactPhone = ""
                    },
                    enabled = !isSavingContact
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Incoming Call Dialog Overlay
    incomingCallContact?.let { contact ->
        AlertDialog(
            onDismissRequest = { incomingCallContact = null },
            title = {
                Text(
                    text = "Incoming Call",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = contact.name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = contact.phoneNumber.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val currentContact = contact
                        incomingCallContact = null
                        onCallClick(currentContact)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("Answer", color = Color.White)
                }
            },
            dismissButton = {
                Button(
                    onClick = { incomingCallContact = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Decline", color = Color.White)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home") },
                actions = {
                    Surface(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(42.dp)
                            .clickable { onAccountClick() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text(
                                text = userInitial,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { isAddContactDialogOpen = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create Contact"
                    )
                },
                text = {
                    Text("New Contact")
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
            Spacer(modifier = Modifier.height(8.dp))

            // Test button to trigger simulated incoming call
            OutlinedButton(
                onClick = {
                    incomingCallContact = Contact("inc", "", "Contact", "+17818690609")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Text("Test Incoming Call Dialog")
            }

            Text(
                text = "Contacts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val displayList = if (contactList.isNotEmpty()) contactList else contacts

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(displayList, key = { it.id }) { contact ->
                    ContactRow(
                        contact = contact,
                        onCallClick = { onCallClick(contact) }
                    )
                }
            }
        }
    }
}

@Composable
fun ContactRow(
    contact: Contact,
    onCallClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCallClick() },
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
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Text(
                    text = contact.name.orEmpty().take(1).ifBlank { "C" }.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = contact.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = contact.phoneNumber.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onCallClick) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Call ${contact.name}",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    TRSProjectTheme {
        HomeScreen()
    }
}
