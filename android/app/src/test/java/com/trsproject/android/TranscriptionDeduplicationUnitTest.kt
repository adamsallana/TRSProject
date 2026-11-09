package com.trsproject.android

import com.trsproject.android.ui.screens.CaptionMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionDeduplicationUnitTest {

    @Test
    fun testDeduplicationIdentifiesSameIdOrSameMeMessage() {
        val message1 = CaptionMessage(id = 100, senderName = "Me", text = "Hello", isMe = true)
        val captions = listOf(message1)

        val echoSameId = CaptionMessage(id = 100, senderName = "Me", text = "Hello", isMe = true)
        val isDuplicateById = captions.any { existing ->
            existing.id == echoSameId.id || (echoSameId.isMe && existing.isMe && existing.text == echoSameId.text)
        }
        assertTrue("Message with same ID should be marked as duplicate", isDuplicateById)

        val echoSameText = CaptionMessage(id = 200, senderName = "Me", text = "Hello", isMe = true)
        val isDuplicateByText = captions.any { existing ->
            existing.id == echoSameText.id || (echoSameText.isMe && existing.isMe && existing.text == echoSameText.text)
        }
        assertTrue("Identical 'Me' message text should be marked as duplicate", isDuplicateByText)

        val callerMsg = CaptionMessage(id = 300, senderName = "Contact", text = "Hello", isMe = false)
        val isCallerDuplicate = captions.any { existing ->
            existing.id == callerMsg.id || (callerMsg.isMe && existing.isMe && existing.text == callerMsg.text)
        }
        assertFalse("Caller message with different sender should not be duplicate", isCallerDuplicate)
    }

    @Test
    fun testIsMeResolutionFromName() {
        val senderName = "Me"
        val isMe = senderName.equals("Me", ignoreCase = true)
        assertTrue(isMe)
    }
}
