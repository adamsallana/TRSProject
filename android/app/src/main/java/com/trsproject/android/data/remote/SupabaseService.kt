package com.trsproject.android.data.remote

import com.trsproject.android.BuildConfig
import com.trsproject.android.data.model.CaptionPreferences
import com.trsproject.android.data.model.NotificationPreferences
import com.trsproject.android.data.model.SecurityPreferences
import com.trsproject.android.data.model.UserProfile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

object SupabaseService {

    // Initialized Supabase Client using BuildConfig parameters
    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Postgrest)
            install(Auth)
        }
    }

    // --- User Profile Operations ---


    suspend fun fetchUserProfile(userId: String): UserProfile =
        withContext(Dispatchers.IO) {
            try {
                val profile = client.postgrest["profiles"]
                    .select {
                        filter {
                            eq("id", userId)
                        }
                    }
                    .decodeList<UserProfile>()
                    .firstOrNull()

                profile ?: UserProfile(id = userId)

            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to fetch user profile", e)
                throw e
            }
        }




    suspend fun updateUserProfile(profile: UserProfile): Boolean =
        withContext(Dispatchers.IO) {
            try {
                require(profile.id.isNotBlank()) {
                    "Cannot save a profile without a user ID"
                }

                client.postgrest["profiles"].upsert(profile)
                true
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to save user profile", e)
                false
            }
        }


    // --- Caption Preferences Operations ---

    suspend fun fetchCaptionPreferences(userId: String): CaptionPreferences =
        withContext(Dispatchers.IO) {
            try {
                val prefs = client.postgrest["caption_settings"]
                    .select {
                        filter {
                            eq("user_id", userId)
                        }
                    }
                    .decodeList<CaptionPreferences>()
                    .firstOrNull()

                prefs ?: CaptionPreferences(userId = userId)

            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to fetch caption preferences", e)
                throw e
            }
        }



    suspend fun updateCaptionPreferences(prefs: CaptionPreferences): Boolean =
        withContext(Dispatchers.IO) {
            try {
                require(prefs.userId.isNotBlank()) {
                    "Cannot save caption settings without a user ID"
                }

                client.postgrest["caption_settings"].upsert(prefs)
                true
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to save caption preferences", e)
                false
            }
        }


    // --- Notification Preferences Operations ---

    suspend fun fetchNotificationPreferences(userId: String): NotificationPreferences =
        withContext(Dispatchers.IO) {
            try {
                val prefs = client.postgrest["notification_settings"]
                    .select {
                        filter {
                            eq("user_id", userId)
                        }
                    }
                    .decodeList<NotificationPreferences>()
                    .firstOrNull()

                prefs ?: NotificationPreferences(userId = userId)

            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to fetch notification preferences", e)
                throw e
            }
        }



    suspend fun updateNotificationPreferences(prefs: NotificationPreferences): Boolean =
        withContext(Dispatchers.IO) {
            try {
                require(prefs.userId.isNotBlank()) {
                    "Cannot save notification settings without a user ID"
                }

                client.postgrest["notification_settings"].upsert(prefs)
                true
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to save notification preferences", e)
                false
            }
        }


    // --- Security Preferences Operations ---
    suspend fun fetchSecurityPreferences(userId: String): SecurityPreferences = withContext(Dispatchers.IO) {
        try {
            client.postgrest["security_settings"]
                .select { filter { eq("user_id", userId) } }
                .decodeSingle<SecurityPreferences>()
        } catch (e: Exception) {
            SecurityPreferences()
        }
    }


    suspend fun updateSecurityPreferences(prefs: SecurityPreferences): Boolean =
        withContext(Dispatchers.IO) {
            try {
                require(prefs.userId.isNotBlank()) {
                    "Cannot save security settings without a user ID"
                }

                client.postgrest["security_settings"].upsert(prefs)
                true
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to save security preferences", e)
                false
            }
        }


    // --- Contact Operations ---

    suspend fun fetchContacts(userId: String): List<com.trsproject.android.data.model.Contact> =
        withContext(Dispatchers.IO) {
            try {
                if (BuildConfig.SUPABASE_URL.isBlank()) {
                    return@withContext listOf(
                        com.trsproject.android.data.model.Contact("1", userId, "Contact", "+18282751713")
                    )
                }
                val list = client.postgrest["contacts"]
                    .select {
                        filter {
                            eq("user_id", userId)
                        }
                    }
                    .decodeList<com.trsproject.android.data.model.Contact>()

                if (list.isEmpty()) {
                    listOf(
                        com.trsproject.android.data.model.Contact("1", userId, "Contact", "+18282751713")
                    )
                } else {
                    list
                }
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to fetch contacts", e)
                listOf(
                    com.trsproject.android.data.model.Contact("1", userId, "Contact", "+18282751713")
                )
            }
        }

    suspend fun createContact(contact: com.trsproject.android.data.model.Contact): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (BuildConfig.SUPABASE_URL.isBlank()) {
                    return@withContext true
                }
                client.postgrest["contacts"].insert(contact)
                true
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to create contact", e)
                false
            }
        }

}
