package com.trsproject.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Contact(
    @SerialName("id") val id: String = "",
    @SerialName("user_id") val userId: String? = "",
    @SerialName("name") val name: String? = "",
    @SerialName("phone_number") val phoneNumber: String? = ""
)
