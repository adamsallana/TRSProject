package com.trsproject.android.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.twilio.voice.Call
import com.twilio.voice.CallException
import com.twilio.voice.CallInvite
import com.twilio.voice.CancelledCallInvite
import com.twilio.voice.MessageListener
import com.twilio.voice.RegistrationException
import com.twilio.voice.RegistrationListener
import com.twilio.voice.Voice

class VoiceFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Registration Token: $token")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "FCM Message Received from: ${remoteMessage.from}")

        if (remoteMessage.data.isNotEmpty()) {
            val valid = Voice.handleMessage(this, remoteMessage.data, object : MessageListener {
                override fun onCallInvite(callInvite: CallInvite) {
                    Log.d(TAG, "Twilio CallInvite Received from: ${callInvite.from}")

                    ActiveCallManager.activeCallInvite = callInvite

                    val intent = Intent(ACTION_INCOMING_CALL_INVITE).apply {
                        putExtra(EXTRA_CALL_INVITE, callInvite)
                    }
                    sendBroadcast(intent)
                }

                override fun onCancelledCallInvite(cancelledCallInvite: CancelledCallInvite, callException: CallException?) {
                    Log.d(TAG, "Twilio CallInvite Cancelled")
                    ActiveCallManager.activeCallInvite = null

                    val intent = Intent(ACTION_CANCELLED_CALL_INVITE)
                    sendBroadcast(intent)
                }
            })

            if (!valid) {
                Log.d(TAG, "Message was not a valid Twilio Voice payload")
            }
        }
    }

    companion object {
        private const val TAG = "VoiceFCMService"
        const val ACTION_INCOMING_CALL_INVITE = "com.trsproject.android.INCOMING_CALL_INVITE"
        const val ACTION_CANCELLED_CALL_INVITE = "com.trsproject.android.CANCELLED_CALL_INVITE"
        const val EXTRA_CALL_INVITE = "extra_call_invite"
    }
}

object ActiveCallManager {
    var activeCallInvite: CallInvite? = null

    fun registerTwilioVoiceToken(
        accessToken: String,
        fcmToken: String,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        Voice.register(accessToken, Voice.RegistrationChannel.FCM, fcmToken, object : RegistrationListener {
            override fun onRegistered(accessToken: String, fcmToken: String) {
                Log.d("TwilioVoice", "Successfully registered Twilio Voice for incoming calls")
                onResult(true, null)
            }

            override fun onError(registrationException: RegistrationException, accessToken: String, fcmToken: String) {
                Log.e("TwilioVoice", "Twilio Voice Registration Error: ${registrationException.message}")
                onResult(false, registrationException.message)
            }
        })
    }

    fun acceptCall(context: Context, listener: Call.Listener): Call? {
        val invite = activeCallInvite ?: return null
        val call = invite.accept(context, listener)
        activeCallInvite = null
        return call
    }

    fun rejectCall(context: Context) {
        activeCallInvite?.reject(context)
        activeCallInvite = null
    }
}
