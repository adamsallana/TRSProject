package com.trsproject.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class UserProfile(
    @SerialName("id") val id: String = "",
    @SerialName("full_name") val fullName: String = "",
    @SerialName("email") val email: String = "",
    @SerialName("phone_number") val phoneNumber: String = "",
    @SerialName("trs_relay_number") val trsRelayNumber: String = ""
)



@Serializable
data class CaptionPreferences(
    @SerialName("user_id") val userId: String = "",
    @SerialName("font_size") val fontSize: String = "Medium",
    @SerialName("text_color") val textColor: String = "Default",
    @SerialName("caption_language") val captionLanguage: String = "English (US)",
    @SerialName("auto_caption") val autoCaptionEnabled: Boolean = true,
    @SerialName("high_contrast") val highContrastEnabled: Boolean = false
)

@Serializable
data class NotificationPreferences(
    @SerialName("user_id") val userId: String = "",
    @SerialName("call_alerts") val callAlertsEnabled: Boolean = true,
    @SerialName("transcript_summaries") val transcriptSummariesEnabled: Boolean = true,
    @SerialName("sound_vibration") val soundVibrationEnabled: Boolean = true,
    @SerialName("email_notifications") val emailNotificationsEnabled: Boolean = false
)

@Serializable
data class SecurityPreferences(
    @SerialName("user_id") val userId: String = "",
    @SerialName("two_factor_enabled") val twoFactorEnabled: Boolean = false,
    @SerialName("pin_lock_enabled") val pinLockEnabled: Boolean = false,
    @SerialName("optional_pin") val optionalPin: String = ""
)
