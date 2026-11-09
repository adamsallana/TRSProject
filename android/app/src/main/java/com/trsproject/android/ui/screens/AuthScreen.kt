package com.trsproject.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.trsproject.android.ui.theme.TRSProjectTheme
import androidx.compose.runtime.rememberCoroutineScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import com.trsproject.android.data.remote.SupabaseService
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun AuthScreen(
    modifier: Modifier = Modifier,
    onSignInSuccess: () -> Unit = {},
    onSignInError: () -> Unit = {}
) {
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }


    fun processAuthentication() {
        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(
                context,
                "Please enter your email and password",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (isSignUp && password != confirmPassword) {
            Toast.makeText(
                context,
                "Passwords do not match",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (isLoading) return

        isLoading = true

        coroutineScope.launch {
            try {
                if (isSignUp) {
                    SupabaseService.client.auth.signUpWith(Email) {
                        this.email = email.trim()
                        this.password = password
                        this.data = buildJsonObject {
                            put("full_name", name.trim())
                        }
                    }

                    Toast.makeText(
                        context,
                        "Account created! Check your email for verification if required.",
                        Toast.LENGTH_LONG
                    ).show()

                    // A signup may require email verification before login.
                    if (SupabaseService.client.auth.currentUserOrNull() != null) {
                        onSignInSuccess()
                    } else {
                        isSignUp = false
                    }
                } else {
                    SupabaseService.client.auth.signInWith(Email) {
                        this.email = email.trim()
                        this.password = password
                    }

                    onSignInSuccess()
                }
            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("timed out", ignoreCase = true) == true ->
                        "Network request timed out. Please check your internet connection or try again."
                    else -> e.message ?: "Authentication failed. Please try again."
                }
                Toast.makeText(
                    context,
                    errorMessage,
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isLoading = false
            }
        }
    }


    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isSignUp) "Create Account" else "Welcome Back",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isSignUp) "Sign up to get started" else "Sign in to continue",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (isSignUp) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Full Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email Address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        if (isSignUp) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Confirm Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))


        Button(
            onClick = { processAuthentication() },
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(
                text = if (isLoading) {
                    "Please wait..."
                } else if (isSignUp) {
                    "Sign Up"
                } else {
                    "Sign In"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }


        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = {
                isSignUp = !isSignUp
            }
        ) {
            Text(
                text = if (isSignUp) {
                    "Already have an account? Sign In"
                } else {
                    "Don't have an account? Sign Up"
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AuthScreenPreview() {
    TRSProjectTheme {
        AuthScreen()
    }
}
