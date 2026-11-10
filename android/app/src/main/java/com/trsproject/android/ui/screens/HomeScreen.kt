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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.trsproject.android.data.model.Contact
import com.trsproject.android.data.remote.SupabaseService
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
    onHistoryClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onTestIncomingCall: () -> Unit = {},
    onSignOut: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val contactList = remember { mutableStateListOf<Contact>() }
    var isEditMode by remember { mutableStateOf(false) }

    // Dialog states
    var isAddContactDialogOpen by remember { mutableStateOf(false) }
    var newContactName by remember { mutableStateOf("") }
    var newContactPhone by remember { mutableStateOf("") }
    var isSavingContact by remember { mutableStateOf(false) }

    var contactToEdit by remember { mutableStateOf<Contact?>(null) }
    var editName by remember { mutableStateOf("") }
    var editPhone by remember { mutableStateOf("") }
    var isSavingEdit by remember { mutableStateOf(false) }

    var contactToDelete by remember { mutableStateOf<Contact?>(null) }
    var isDeletingContact by remember { mutableStateOf(false) }

    var userInitial by remember { mutableStateOf("U") }
    val hasCachedData = remember { SupabaseService.hasCachedContacts() }
    var isLoadingHome by remember { mutableStateOf(!hasCachedData) }

    // Fetch user profile and contacts from Supabase backend on launch
    LaunchedEffect(Unit) {
        try {
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
        } catch (e: Exception) {
            // Keep empty contact list or fallback
        } finally {
            isLoadingHome = false
        }
    }

    // 1. Add New Contact Dialog
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

                            val formattedPhone = com.trsproject.android.network.formatToE164(newContactPhone.trim())

                            val newContact = Contact(
                                id = UUID.randomUUID().toString(),
                                userId = currentUserId,
                                name = newContactName.trim(),
                                phoneNumber = formattedPhone
                            )

                            val success = SupabaseService.createContact(newContact)
                            if (success) {
                                contactList.add(0, newContact)
                                Toast.makeText(context, "Contact created successfully!", Toast.LENGTH_SHORT).show()
                            } else {
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

    // 2. Edit Contact Dialog
    if (contactToEdit != null) {
        val target = contactToEdit!!
        AlertDialog(
            onDismissRequest = {
                if (!isSavingEdit) contactToEdit = null
            },
            title = {
                Text(
                    text = "Edit Contact",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Contact Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editPhone,
                        onValueChange = { editPhone = it },
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
                        if (editName.isBlank() || editPhone.isBlank()) {
                            Toast.makeText(context, "Please enter both name and phone number", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        isSavingEdit = true
                        coroutineScope.launch {
                            val formattedPhone = com.trsproject.android.network.formatToE164(editPhone.trim())
                            val updated = target.copy(
                                name = editName.trim(),
                                phoneNumber = formattedPhone
                            )

                            val success = SupabaseService.updateContact(updated)
                            val index = contactList.indexOfFirst { it.id == target.id }
                            if (index >= 0) {
                                contactList[index] = updated
                            }
                            isSavingEdit = false
                            contactToEdit = null
                            Toast.makeText(context, "Contact updated successfully", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isSavingEdit
                ) {
                    Text(if (isSavingEdit) "Saving..." else "Save Changes")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { contactToEdit = null },
                    enabled = !isSavingEdit
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // 3. Delete Contact Dialog
    if (contactToDelete != null) {
        val target = contactToDelete!!
        AlertDialog(
            onDismissRequest = {
                if (!isDeletingContact) contactToDelete = null
            },
            title = {
                Text(
                    text = "Delete Contact",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Are you sure you want to delete ${target.name}? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDeletingContact = true
                        coroutineScope.launch {
                            val success = SupabaseService.deleteContact(target.id)
                            contactList.removeAll { it.id == target.id }
                            isDeletingContact = false
                            contactToDelete = null
                            Toast.makeText(context, "Contact deleted", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !isDeletingContact
                ) {
                    Text(if (isDeletingContact) "Deleting..." else "Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { contactToDelete = null },
                    enabled = !isDeletingContact
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home") },
                actions = {
                    IconButton(onClick = onHistoryClick) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Call History"
                        )
                    }
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
        if (isLoadingHome) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {

                // Header with Edit Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isEditMode) "Editing Contacts" else "Contacts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )

                    TextButton(onClick = { isEditMode = !isEditMode }) {
                        Text(
                            text = if (isEditMode) "Done" else "Edit",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                val displayList = if (contactList.isNotEmpty()) contactList else contacts

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(displayList, key = { _, contact -> contact.id }) { index, contact ->
                        ContactRow(
                            contact = contact,
                            isEditMode = isEditMode,
                            canMoveUp = index > 0,
                            canMoveDown = index < displayList.size - 1,
                            onCallClick = { onCallClick(contact) },
                            onEditClick = {
                                contactToEdit = contact
                                editName = contact.name.orEmpty()
                                editPhone = contact.phoneNumber.orEmpty()
                            },
                            onDeleteClick = {
                                contactToDelete = contact
                            },
                            onMoveUpClick = {
                                if (index > 0 && index < contactList.size) {
                                    val item = contactList.removeAt(index)
                                    contactList.add(index - 1, item)
                                }
                            },
                            onMoveDownClick = {
                                if (index >= 0 && index < contactList.size - 1) {
                                    val item = contactList.removeAt(index)
                                    contactList.add(index + 1, item)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContactRow(
    contact: Contact,
    isEditMode: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onCallClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onMoveUpClick: () -> Unit = {},
    onMoveDownClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                if (isEditMode) onEditClick() else onCallClick()
            },
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

            if (isEditMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onMoveUpClick,
                        enabled = canMoveUp
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Move Up",
                            tint = if (canMoveUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }

                    IconButton(
                        onClick = onMoveDownClick,
                        enabled = canMoveDown
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Move Down",
                            tint = if (canMoveDown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }

                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Contact",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(onClick = onDeleteClick) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Contact",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
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
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    TRSProjectTheme {
        HomeScreen()
    }
}
