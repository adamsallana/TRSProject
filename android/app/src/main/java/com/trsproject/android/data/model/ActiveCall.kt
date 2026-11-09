package com.trsproject.android.data.model

enum class CallDirection { OUTGOING, INCOMING }

/** The call the user is currently placing, receiving, or on. */
data class ActiveCall(
    val direction: CallDirection,
    val name: String,
    val phoneNumber: String,
    /** Twilio CallSid. Null for an outgoing call until the server has created it. */
    val callSid: String? = null,
    /** Optional from/relay phone number associated with the user's account. */
    val userRelayNumber: String? = null
)
