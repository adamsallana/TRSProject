package com.trsproject.android.data.remote

import com.trsproject.android.BuildConfig
import com.trsproject.android.data.model.CaptionPreferences
import com.trsproject.android.data.model.Contact
import com.trsproject.android.data.model.NotificationPreferences
import com.trsproject.android.data.model.SecurityPreferences
import com.trsproject.android.data.model.UserProfile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

import kotlin.time.Duration.Companion.seconds

object SupabaseService {

    // Initialized Supabase Client using BuildConfig parameters
    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            httpEngine = OkHttp.create()
            requestTimeout = 30.seconds
            install(Postgrest)
            install(Auth)
        }
    }

    // --- In-Memory Caching ---
    @Volatile
    private var cachedUserProfile: UserProfile? = null

    @Volatile
    private var cachedCaptionPreferences: CaptionPreferences? = null

    @Volatile
    private var cachedNotificationPreferences: NotificationPreferences? = null

    @Volatile
    private var cachedSecurityPreferences: SecurityPreferences? = null

    @Volatile
    private var cachedContacts: List<Contact>? = null

    /** Returns true if contacts are currently stored in memory cache. */
    fun hasCachedContacts(): Boolean = cachedContacts != null

    /** Returns true if user profile is currently stored in memory cache. */
    fun hasCachedUserProfile(): Boolean = cachedUserProfile != null

    /** Clears all in-memory cached data (e.g. on user sign out). */
    fun clearCache() {
        cachedUserProfile = null
        cachedCaptionPreferences = null
        cachedNotificationPreferences = null
        cachedSecurityPreferences = null
        cachedContacts = null
    }

    // --- User Profile Operations ---

    suspend fun fetchUserProfile(userId: String, forceRefresh: Boolean = false): UserProfile =
        withContext(Dispatchers.IO) {
            val local = cachedUserProfile
            if (!forceRefresh && local != null && local.id == userId) {
                return@withContext local
            }
            try {
                val profile = client.postgrest["profiles"]
                    .select {
                        filter {
                            eq("id", userId)
                        }
                    }
                    .decodeList<UserProfile>()
                    .firstOrNull() ?: UserProfile(id = userId)

                cachedUserProfile = profile
                profile
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to fetch user profile", e)
                cachedUserProfile ?: UserProfile(id = userId)
            }
        }

    suspend fun updateUserProfile(profile: UserProfile): Boolean =
        withContext(Dispatchers.IO) {
            try {
                require(profile.id.isNotBlank()) {
                    "Cannot save a profile without a user ID"
                }

                client.postgrest["profiles"].upsert(profile)
                cachedUserProfile = profile
                true
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to save user profile", e)
                false
            }
        }

    // --- Caption Preferences Operations ---

    suspend fun fetchCaptionPreferences(userId: String, forceRefresh: Boolean = false): CaptionPreferences =
        withContext(Dispatchers.IO) {
            val local = cachedCaptionPreferences
            if (!forceRefresh && local != null && local.userId == userId) {
                return@withContext local
            }
            try {
                val prefs = client.postgrest["caption_settings"]
                    .select {
                        filter {
                            eq("user_id", userId)
                        }
                    }
                    .decodeList<CaptionPreferences>()
                    .firstOrNull() ?: CaptionPreferences(userId = userId)

                cachedCaptionPreferences = prefs
                prefs
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to fetch caption preferences", e)
                cachedCaptionPreferences ?: CaptionPreferences(userId = userId)
            }
        }

    suspend fun updateCaptionPreferences(prefs: CaptionPreferences): Boolean =
        withContext(Dispatchers.IO) {
            try {
                require(prefs.userId.isNotBlank()) {
                    "Cannot save caption settings without a user ID"
                }

                client.postgrest["caption_settings"].upsert(prefs)
                cachedCaptionPreferences = prefs
                true
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to save caption preferences", e)
                false
            }
        }

    // --- Notification Preferences Operations ---

    suspend fun fetchNotificationPreferences(userId: String, forceRefresh: Boolean = false): NotificationPreferences =
        withContext(Dispatchers.IO) {
            val local = cachedNotificationPreferences
            if (!forceRefresh && local != null && local.userId == userId) {
                return@withContext local
            }
            try {
                val prefs = client.postgrest["notification_settings"]
                    .select {
                        filter {
                            eq("user_id", userId)
                        }
                    }
                    .decodeList<NotificationPreferences>()
                    .firstOrNull() ?: NotificationPreferences(userId = userId)

                cachedNotificationPreferences = prefs
                prefs
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to fetch notification preferences", e)
                cachedNotificationPreferences ?: NotificationPreferences(userId = userId)
            }
        }

    suspend fun updateNotificationPreferences(prefs: NotificationPreferences): Boolean =
        withContext(Dispatchers.IO) {
            try {
                require(prefs.userId.isNotBlank()) {
                    "Cannot save notification settings without a user ID"
                }

                client.postgrest["notification_settings"].upsert(prefs)
                cachedNotificationPreferences = prefs
                true
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to save notification preferences", e)
                false
            }
        }

    // --- Security Preferences Operations ---

    suspend fun fetchSecurityPreferences(userId: String, forceRefresh: Boolean = false): SecurityPreferences =
        withContext(Dispatchers.IO) {
            val local = cachedSecurityPreferences
            if (!forceRefresh && local != null && local.userId == userId) {
                return@withContext local
            }
            try {
                val prefs = client.postgrest["security_settings"]
                    .select { filter { eq("user_id", userId) } }
                    .decodeSingle<SecurityPreferences>()
                cachedSecurityPreferences = prefs
                prefs
            } catch (e: Exception) {
                cachedSecurityPreferences ?: SecurityPreferences(userId = userId)
            }
        }

    suspend fun updateSecurityPreferences(prefs: SecurityPreferences): Boolean =
        withContext(Dispatchers.IO) {
            try {
                require(prefs.userId.isNotBlank()) {
                    "Cannot save security settings without a user ID"
                }

                client.postgrest["security_settings"].upsert(prefs)
                cachedSecurityPreferences = prefs
                true
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to save security preferences", e)
                false
            }
        }

    // --- Contact Operations ---

    suspend fun fetchContacts(userId: String, forceRefresh: Boolean = false): List<Contact> =
        withContext(Dispatchers.IO) {
            val local = cachedContacts
            if (!forceRefresh && local != null) {
                return@withContext local
            }
            try {
                if (BuildConfig.SUPABASE_URL.isBlank()) {
                    val fallback = listOf(
                        Contact("1", userId, "Contact", "+18282751713")
                    )
                    cachedContacts = fallback
                    return@withContext fallback
                }
                val list = client.postgrest["contacts"]
                    .select {
                        filter {
                            eq("user_id", userId)
                        }
                    }
                    .decodeList<Contact>()

                val result = if (list.isEmpty()) {
                    listOf(
                        Contact("1", userId, "Contact", "+18282751713")
                    )
                } else {
                    list
                }
                cachedContacts = result
                result
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to fetch contacts", e)
                cachedContacts ?: listOf(
                    Contact("1", userId, "Contact", "+18282751713")
                )
            }
        }

    suspend fun createContact(contact: Contact): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!BuildConfig.SUPABASE_URL.isBlank()) {
                    client.postgrest["contacts"].insert(contact)
                }
                val currentList = cachedContacts.orEmpty().toMutableList()
                currentList.add(0, contact)
                cachedContacts = currentList
                true
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to create contact", e)
                false
            }
        }

    suspend fun updateContact(contact: Contact): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!BuildConfig.SUPABASE_URL.isBlank()) {
                    client.postgrest["contacts"].upsert(contact)
                }
                val currentList = cachedContacts.orEmpty().toMutableList()
                val index = currentList.indexOfFirst { it.id == contact.id }
                if (index >= 0) {
                    currentList[index] = contact
                    cachedContacts = currentList
                }
                true
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to update contact", e)
                false
            }
        }

    suspend fun deleteContact(contactId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!BuildConfig.SUPABASE_URL.isBlank()) {
                    client.postgrest["contacts"].delete {
                        filter {
                            eq("id", contactId)
                        }
                    }
                }
                val currentList = cachedContacts.orEmpty().toMutableList()
                currentList.removeAll { it.id == contactId }
                cachedContacts = currentList
                true
            } catch (e: Exception) {
                Log.e("SupabaseService", "Failed to delete contact", e)
                false
            }
        }
}
