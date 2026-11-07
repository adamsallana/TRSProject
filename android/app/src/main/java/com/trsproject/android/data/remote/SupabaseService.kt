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
    suspend fun fetchUserProfile(userId: String): UserProfile = withContext(Dispatchers.IO) {
        try {
            client.postgrest["profiles"]
                .select { filter { eq("id", userId) } }
                .decodeSingle<UserProfile>()
        } catch (e: Exception) {
            // Fallback default profile if network/tables not initialized yet
            UserProfile(id = userId)
        }
    }

    suspend fun updateUserProfile(profile: UserProfile): Boolean = withContext(Dispatchers.IO) {
        try {
            client.postgrest["profiles"]
                .upsert(profile)
            true
        } catch (e: Exception) {
            false
        }
    }

    // --- Caption Preferences Operations ---
    suspend fun fetchCaptionPreferences(userId: String): CaptionPreferences = withContext(Dispatchers.IO) {
        try {
            client.postgrest["caption_settings"]
                .select { filter { eq("user_id", userId) } }
                .decodeSingle<CaptionPreferences>()
        } catch (e: Exception) {
            CaptionPreferences()
        }
    }

    suspend fun updateCaptionPreferences(prefs: CaptionPreferences): Boolean = withContext(Dispatchers.IO) {
        try {
            client.postgrest["caption_settings"]
                .upsert(prefs)
            true
        } catch (e: Exception) {
            false
        }
    }

    // --- Notification Preferences Operations ---
    suspend fun fetchNotificationPreferences(userId: String): NotificationPreferences = withContext(Dispatchers.IO) {
        try {
            client.postgrest["notification_settings"]
                .select { filter { eq("user_id", userId) } }
                .decodeSingle<NotificationPreferences>()
        } catch (e: Exception) {
            NotificationPreferences()
        }
    }

    suspend fun updateNotificationPreferences(prefs: NotificationPreferences): Boolean = withContext(Dispatchers.IO) {
        try {
            client.postgrest["notification_settings"]
                .upsert(prefs)
            true
        } catch (e: Exception) {
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

    suspend fun updateSecurityPreferences(prefs: SecurityPreferences): Boolean = withContext(Dispatchers.IO) {
        try {
            client.postgrest["security_settings"]
                .upsert(prefs)
            true
        } catch (e: Exception) {
            false
        }
    }
}
