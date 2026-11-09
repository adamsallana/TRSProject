package com.trsproject.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.trsproject.android.data.model.UserProfile
import com.trsproject.android.data.remote.SupabaseService
import com.trsproject.android.ui.theme.TRSProjectTheme
import kotlinx.coroutines.launch
import io.github.jan.supabase.auth.auth
import androidx.compose.runtime.LaunchedEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalInfoScreen(
    modifier: Modifier = Modifier,
    initialProfile: UserProfile = UserProfile(),
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var fullName by remember { mutableStateOf(initialProfile.fullName.orEmpty()) }
    var email by remember { mutableStateOf(initialProfile.email.orEmpty()) }
    var phoneNumber by remember { mutableStateOf(initialProfile.phoneNumber.orEmpty()) }
    var trsRelayNumber by remember { mutableStateOf(initialProfile.trsRelayNumber.orEmpty()) }
    var phoneNumberError by remember { mutableStateOf<String?>(null) }
    var trsRelayNumberError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val hasCache = remember { SupabaseService.hasCachedUserProfile() }
    var isLoading by remember { mutableStateOf(!hasCache) }


    LaunchedEffect(Unit) {
        try {
            val user = SupabaseService.client.auth.currentUserOrNull()

            if (user == null) {
                Toast.makeText(
                    context,
                    "Please sign in to view your profile.",
                    Toast.LENGTH_LONG
                ).show()
                return@LaunchedEffect
            }

            val profile = SupabaseService.fetchUserProfile(user.id)

            fullName = profile.fullName.orEmpty()
            email = profile.email.orEmpty().ifBlank { user.email ?: "" }
            phoneNumber = profile.phoneNumber.orEmpty()
            trsRelayNumber = profile.trsRelayNumber.orEmpty()

        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Unable to load profile. Please try again.",
                Toast.LENGTH_LONG
            ).show()
        } finally {
            isLoading = false
        }
    }

    fun saveInfo() {
        val trimmedPhone = phoneNumber.trim()
        val trimmedRelay = trsRelayNumber.trim()

        var hasError = false
        if (trimmedPhone.isBlank()) {
            phoneNumberError = "Personal phone number is required"
            hasError = true
        } else {
            phoneNumberError = null
        }

        if (trimmedRelay.isBlank()) {
            trsRelayNumberError = "TRS Relay (Twilio) phone number is required"
            hasError = true
        } else {
            trsRelayNumberError = null
        }

        if (hasError) {
            Toast.makeText(
                context,
                "Please enter both your personal phone number and TRS Relay number.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        isSaving = true
        coroutineScope.launch {

            val userId = SupabaseService.client.auth.currentUserOrNull()?.id

            if (userId == null) {
                isSaving = false
                Toast.makeText(
                    context,
                    "Please sign in before saving your profile.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val formattedPhone = com.trsproject.android.network.formatToE164(trimmedPhone)
            val formattedRelay = com.trsproject.android.network.formatToE164(trimmedRelay)

            val updated = UserProfile(
                id = userId,
                displayName = fullName,
                fullName = fullName,
                email = email,
                phoneNumber = formattedPhone,
                trsRelayNumber = formattedRelay
            )

            val success = SupabaseService.updateUserProfile(updated)
            isSaving = false

            if (success) {
                Toast.makeText(
                    context,
                    "Personal information saved successfully!",
                    Toast.LENGTH_SHORT
                ).show()
                onBack()
            } else {
                Toast.makeText(
                    context,
                    "Unable to save personal information. Please try again.",
                    Toast.LENGTH_LONG
                ).show()
            }

        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personal Information") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        if (isLoading) {
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
                    .verticalScroll(scrollState)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Update your account details and assigned TRS relay contact information.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("Full Name") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Address") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = {
                        phoneNumber = it
                        if (it.isNotBlank()) phoneNumberError = null
                    },
                    label = { Text("Personal Phone Number") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    singleLine = true,
                    isError = phoneNumberError != null,
                    supportingText = phoneNumberError?.let { err -> { Text(err, color = MaterialTheme.colorScheme.error) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = trsRelayNumber,
                    onValueChange = {
                        trsRelayNumber = it
                        if (it.isNotBlank()) trsRelayNumberError = null
                    },
                    label = { Text("TRS Relay (Twilio) Phone Number") },
                    leadingIcon = { Icon(Icons.Default.PhoneInTalk, contentDescription = null) },
                    singleLine = true,
                    isError = trsRelayNumberError != null,
                    supportingText = trsRelayNumberError?.let { err -> { Text(err, color = MaterialTheme.colorScheme.error) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { saveInfo() },
                    enabled = !isSaving && !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = if (isSaving) "Saving..." else "Save Changes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PersonalInfoScreenPreview() {
    TRSProjectTheme {
        PersonalInfoScreen()
    }
}
