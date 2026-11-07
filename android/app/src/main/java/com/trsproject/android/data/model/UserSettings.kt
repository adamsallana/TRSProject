package com.trsproject.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    @SerialName("id") val id: String = "",
    @SerialName("full_name") val fullName: String = "Alice Smith",
    @SerialName("email") val email: String = "alice.smith@example.com",
    @SerialName("phone_number") val phoneNumber: String = "+1 (555) 123-4567",
    @SerialName("trs_relay_number") val trsRelayNumber: String = "+1 (800) 555-8771"
)

@Serializable
data class CaptionPreferences(
    @SerialName("font_size") val fontSize: String = "Medium",
    @SerialName("text_color") val textColor: String = "Default",
    @SerialName("caption_language") val captionLanguage: String = "English (US)",
    @SerialName("auto_caption") val autoCaptionEnabled: Boolean = true,
    @SerialName("high_contrast") val highContrastEnabled: Boolean = false
)

@Serializable
data class NotificationPreferences(
    @SerialName("call_alerts") val callAlertsEnabled: Boolean = true,
    @SerialName("transcript_summaries") val transcriptSummariesEnabled: Boolean = true,
    @SerialName("sound_vibration") val soundVibrationEnabled: Boolean = true,
    @SerialName("email_notifications") val emailNotificationsEnabled: Boolean = false
)

@Serializable
data class SecurityPreferences(
    @SerialName("two_factor_enabled") val twoFactorEnabled: Boolean = false,
    @SerialName("pin_lock_enabled") val pinLockEnabled: Boolean = false,
    @SerialName("optional_pin") val optionalPin: String = ""
)
