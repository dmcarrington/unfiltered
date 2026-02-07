package com.nostr.unfiltered.repository

import android.content.Context
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
import javax.inject.Inject
import javax.inject.Singleton

private val Context.muteDataStore by preferencesDataStore(name = "mute_list")

@Singleton
class MuteListRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val muteListKey = stringPreferencesKey("muted_pubkeys_json")

    private val _muteList = MutableStateFlow<Set<String>>(emptySet())
    val muteList: StateFlow<Set<String>> = _muteList.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun initialize() {
        val prefs = context.muteDataStore.data.first()
        val jsonStr = prefs[muteListKey]
        if (jsonStr != null) {
            try {
                _muteList.value = json.decodeFromString<Set<String>>(jsonStr)
            } catch (e: Exception) {
                // Invalid JSON, start fresh
            }
        }
    }

    suspend fun muteUser(pubkey: String) {
        _muteList.value = _muteList.value + pubkey
        persist()
    }

    suspend fun unmuteUser(pubkey: String) {
        _muteList.value = _muteList.value - pubkey
        persist()
    }

    fun isMuted(pubkey: String): Boolean = _muteList.value.contains(pubkey)

    private suspend fun persist() {
        context.muteDataStore.edit { prefs ->
            prefs[muteListKey] = json.encodeToString(_muteList.value)
        }
    }
}
