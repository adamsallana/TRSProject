package com.trsproject.android

import com.trsproject.android.ui.screens.DemoErrorEvaluator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthErrorUnitTest {

    @Test
    fun testValidCredentialsSucceed() {
        assertTrue(DemoErrorEvaluator.isValidCredentials("correct@gmail.com", "correct"))
        assertTrue(DemoErrorEvaluator.isValidCredentials("CORRECT@GMAIL.COM", "correct"))
        assertFalse(DemoErrorEvaluator.isWrongCredentials("correct@gmail.com", "correct"))
    }

    @Test
    fun testAllOtherCredentialsFailAndTriggerError() {
        assertTrue(DemoErrorEvaluator.isWrongCredentials("wrongauth@gmail.com", "wrongauth"))
        assertTrue(DemoErrorEvaluator.isWrongCredentials("correct@gmail.com", "wrongpassword"))
        assertTrue(DemoErrorEvaluator.isWrongCredentials("wrong@gmail.com", "correct"))
        assertTrue(DemoErrorEvaluator.isWrongCredentials("user@example.com", "123456"))
    }
}
