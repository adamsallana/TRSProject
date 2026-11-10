package com.trsproject.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CallLog(
    @SerialName("id") val id: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("call_sid") val callSid: String? = null,
    @SerialName("contact_name") val contactName: String? = null,
    @SerialName("phone_number") val phoneNumber: String = "",
    @SerialName("direction") val direction: String = "OUTGOING",
    @SerialName("status") val status: String = "COMPLETED",
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    @SerialName("started_at") val startedAt: String? = null
)
