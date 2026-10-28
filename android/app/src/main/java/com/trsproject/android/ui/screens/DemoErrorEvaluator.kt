package com.trsproject.android.ui.screens

object DemoErrorEvaluator {
    private const val DEMO_CORRECT_EMAIL = "correct@gmail.com"
    private const val DEMO_CORRECT_PASSWORD = "correct"

    /**
     * Checks if the provided credentials match the mock valid credentials.
     * Only [DEMO_CORRECT_EMAIL] and [DEMO_CORRECT_PASSWORD] are allowed to sign in.
     * All other credentials will fail authentication.
     */
    fun isValidCredentials(email: String, password: String): Boolean {
        return email.trim().equals(DEMO_CORRECT_EMAIL, ignoreCase = true) &&
                password.trim() == DEMO_CORRECT_PASSWORD
    }

    fun isWrongCredentials(email: String, password: String): Boolean {
        return !isValidCredentials(email, password)
    }
}
