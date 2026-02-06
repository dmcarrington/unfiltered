package com.nostr.unfiltered.nostr

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.nostr.unfiltered.nostr.models.Notification
import javax.inject.Inject
import javax.inject.Singleton

private val Context.notificationDataStore by preferencesDataStore(name = "notifications")

@Singleton
class NotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager
) {
    private val hasUnreadKey = booleanPreferencesKey("has_unread")
    private val notificationsKey = stringPreferencesKey("notifications_json")

    private val _notifications = MutableStateFlow<List<Notification>>(emptyList())
    val notifications: StateFlow<List<Notification>> = _notifications.asStateFlow()

    private val _hasUnread = MutableStateFlow(false)
    val hasUnread: StateFlow<Boolean> = _hasUnread.asStateFlow()

    private val seenNotificationIds = mutableSetOf<String>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun initialize() {
        // Load persisted state
        val prefs = context.notificationDataStore.data.first()
        _hasUnread.value = prefs[hasUnreadKey] ?: false

        val jsonStr = prefs[notificationsKey]
        if (jsonStr != null) {
            try {
                val loaded = json.decodeFromString<List<Notification>>(jsonStr)
                _notifications.value = loaded.sortedByDescending { it.timestamp }
                seenNotificationIds.addAll(loaded.map { it.id })
            } catch (e: Exception) {
                // Invalid JSON, start fresh
            }
        }
    }

    suspend fun addNotification(notification: Notification) {
        if (seenNotificationIds.contains(notification.id)) return
        seenNotificationIds.add(notification.id)

        val updated = (_notifications.value + notification)
            .sortedByDescending { it.timestamp }
            .take(100) // Keep most recent 100
        _notifications.value = updated

        _hasUnread.value = true
        persistState()
    }

    suspend fun markAllAsRead() {
        _hasUnread.value = false
        context.notificationDataStore.edit { prefs ->
            prefs[hasUnreadKey] = false
        }
    }

    private suspend fun persistState() {
        context.notificationDataStore.edit { prefs ->
            prefs[hasUnreadKey] = _hasUnread.value
            prefs[notificationsKey] = json.encodeToString(_notifications.value)
        }
    }

    fun getMyPubkey(): String? = keyManager.getPublicKeyHex()
}
