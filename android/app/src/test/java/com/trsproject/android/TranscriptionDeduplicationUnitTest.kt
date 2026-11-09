package com.trsproject.android

import com.trsproject.android.ui.screens.CaptionMessage
import com.trsproject.android.ui.screens.mergeCaption
import org.junit.Assert.assertEquals
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
    fun mergeCaption_dropsEchoOfOwnMessage() {
        val mine = CaptionMessage(id = 100, senderName = "Me", text = "Hello", isMe = true)
        val merged = mergeCaption(listOf(mine), mine.copy())
        assertEquals(1, merged.size)
    }

    @Test
    fun mergeCaption_interimUpdatesReplaceSameBubble() {
        var captions = listOf<CaptionMessage>()
        captions = mergeCaption(captions, CaptionMessage(5, "Bob", "hel", isMe = false, isFinal = false))
        captions = mergeCaption(captions, CaptionMessage(5, "Bob", "hello the", isMe = false, isFinal = false))
        captions = mergeCaption(captions, CaptionMessage(5, "Bob", "Hello there.", isMe = false, isFinal = true))
        assertEquals(1, captions.size)
        assertEquals("Hello there.", captions[0].text)
        assertTrue(captions[0].isFinal)
    }

    @Test
    fun mergeCaption_lateInterimDoesNotOverwriteFinal() {
        val final = CaptionMessage(5, "Bob", "Hello there.", isMe = false, isFinal = true)
        val merged = mergeCaption(listOf(final), CaptionMessage(5, "Bob", "hello", isMe = false, isFinal = false))
        assertEquals("Hello there.", merged.single().text)
    }

    @Test
    fun mergeCaption_newUtteranceAppends() {
        val first = CaptionMessage(5, "Bob", "Hi.", isMe = false)
        val merged = mergeCaption(listOf(first), CaptionMessage(6, "Bob", "How are", isMe = false, isFinal = false))
        assertEquals(2, merged.size)
    }

    @Test
    fun testIsMeResolutionFromName() {
        val senderName = "Me"
        val isMe = senderName.equals("Me", ignoreCase = true)
        assertTrue(isMe)
    }

    @Test
    fun testCaptionPreferencesDefaults() {
        val prefs = com.trsproject.android.data.model.CaptionPreferences()
        assertEquals("Medium", prefs.fontSize)
        assertEquals("Default", prefs.textColor)
        assertEquals("English (US)", prefs.captionLanguage)
        assertTrue(prefs.autoCaptionEnabled)
        assertFalse(prefs.highContrastEnabled)
    }

    @Test
    fun testCaptionPreferencesCustomValues() {
        val prefs = com.trsproject.android.data.model.CaptionPreferences(
            fontSize = "Large",
            textColor = "Yellow",
            captionLanguage = "Spanish",
            autoCaptionEnabled = false,
            highContrastEnabled = true
        )
        assertEquals("Large", prefs.fontSize)
        assertEquals("Yellow", prefs.textColor)
        assertEquals("Spanish", prefs.captionLanguage)
        assertFalse(prefs.autoCaptionEnabled)
        assertTrue(prefs.highContrastEnabled)
    }
}
