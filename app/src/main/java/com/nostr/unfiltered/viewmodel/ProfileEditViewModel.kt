package com.nostr.unfiltered.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.models.UserMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import rust.nostr.protocol.Event
import rust.nostr.protocol.EventBuilder
import rust.nostr.protocol.Filter
import rust.nostr.protocol.Kind
import rust.nostr.protocol.PublicKey
import javax.inject.Inject

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val nostrClient: NostrClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileEditUiState())
    val uiState: StateFlow<ProfileEditUiState> = _uiState.asStateFlow()

    init {
        loadCurrentProfile()
    }

    private fun loadCurrentProfile() {
        val pubkeyHex = keyManager.getPublicKeyHex() ?: return

        _uiState.update { it.copy(isLoading = true, pubkey = pubkeyHex) }

        val subscriptionId = "profile_edit_${System.currentTimeMillis()}"

        viewModelScope.launch {
            try {
                // Subscribe to get current metadata
                val filter = Filter()
                    .kind(Kind(0u))
                    .author(PublicKey.fromHex(pubkeyHex))
                    .limit(1u)

                nostrClient.subscribe(subscriptionId, listOf(filter))

                // Collect events from the stream
                nostrClient.events.collect { nostrEvent ->
                    if (nostrEvent.subscriptionId == subscriptionId) {
                        val event = nostrEvent.event
                        if (event.kind().asU16().toInt() == 0) {
                            val metadata = UserMetadata.fromJson(
                                pubkey = event.author().toHex(),
                                json = event.content(),
                                createdAt = event.createdAt().asSecs().toLong()
                            )
                            _uiState.update { state ->
                                state.copy(
                                    isLoading = false,
                                    name = metadata.name ?: "",
                                    displayName = metadata.displayName ?: "",
                                    about = metadata.about ?: "",
                                    picture = metadata.picture ?: "",
                                    banner = metadata.banner ?: "",
                                    nip05 = metadata.nip05 ?: "",
                                    lud16 = metadata.lud16 ?: "",
                                    website = metadata.website ?: "",
                                    originalMetadata = metadata
                                )
                            }
                            // Unsubscribe after getting the metadata
                            nostrClient.unsubscribe(subscriptionId)
                            return@collect
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }

        // Set a timeout to stop loading if no metadata found
        viewModelScope.launch {
            kotlinx.coroutines.delay(5000)
            if (_uiState.value.isLoading) {
                _uiState.update { it.copy(isLoading = false) }
                nostrClient.unsubscribe(subscriptionId)
            }
        }
    }

    fun updateName(value: String) {
        _uiState.update { it.copy(name = value) }
    }

    fun updateDisplayName(value: String) {
        _uiState.update { it.copy(displayName = value) }
    }

    fun updateAbout(value: String) {
        _uiState.update { it.copy(about = value) }
    }

    fun updatePicture(value: String) {
        _uiState.update { it.copy(picture = value) }
    }

    fun updateBanner(value: String) {
        _uiState.update { it.copy(banner = value) }
    }

    fun updateNip05(value: String) {
        _uiState.update { it.copy(nip05 = value) }
    }

    fun updateLud16(value: String) {
        _uiState.update { it.copy(lud16 = value) }
    }

    fun updateWebsite(value: String) {
        _uiState.update { it.copy(website = value) }
    }

    fun saveProfile() {
        val state = _uiState.value
        val pubkey = state.pubkey ?: return

        _uiState.update { it.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            try {
                // Create the metadata JSON
                val metadata = UserMetadata(
                    pubkey = pubkey,
                    name = state.name.takeIf { it.isNotBlank() },
                    displayName = state.displayName.takeIf { it.isNotBlank() },
                    about = state.about.takeIf { it.isNotBlank() },
                    picture = state.picture.takeIf { it.isNotBlank() },
                    banner = state.banner.takeIf { it.isNotBlank() },
                    nip05 = state.nip05.takeIf { it.isNotBlank() },
                    lud16 = state.lud16.takeIf { it.isNotBlank() },
                    website = state.website.takeIf { it.isNotBlank() }
                )

                val metadataJson = metadata.toJson()

                if (keyManager.isAmberConnected()) {
                    // Need to use Amber for signing
                    val unsignedEvent = createUnsignedMetadataEvent(metadataJson)
                    val intent = keyManager.createAmberSignEventIntent(
                        eventJson = unsignedEvent,
                        eventId = "profile_update"
                    )
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            needsAmberSigning = intent,
                            pendingMetadataJson = metadataJson
                        )
                    }
                } else {
                    // Sign locally with stored keys
                    val keys = keyManager.getKeys()
                    if (keys == null) {
                        _uiState.update {
                            it.copy(isSaving = false, error = "No keys available for signing")
                        }
                        return@launch
                    }

                    val event = EventBuilder(Kind(0u), metadataJson, emptyList())
                        .toEvent(keys)

                    val success = nostrClient.publish(event)
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            saveSuccess = success,
                            error = if (!success) "Failed to publish to relays" else null
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, error = e.message ?: "Failed to save profile")
                }
            }
        }
    }

    /**
     * Handle the signed event returned from Amber
     */
    fun handleAmberSignedEvent(signedEventJson: String) {
        viewModelScope.launch {
            try {
                val event = Event.fromJson(signedEventJson)
                val success = nostrClient.publish(event)
                _uiState.update {
                    it.copy(
                        needsAmberSigning = null,
                        pendingMetadataJson = null,
                        saveSuccess = success,
                        error = if (!success) "Failed to publish to relays" else null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        needsAmberSigning = null,
                        pendingMetadataJson = null,
                        error = e.message ?: "Failed to process signed event"
                    )
                }
            }
        }
    }

    fun clearAmberSigningRequest() {
        _uiState.update {
            it.copy(needsAmberSigning = null, pendingMetadataJson = null)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }

    private fun createUnsignedMetadataEvent(content: String): String {
        val pubkey = keyManager.getPublicKeyHex() ?: ""
        val createdAt = System.currentTimeMillis() / 1000

        return JSONObject().apply {
            put("kind", 0)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("tags", JSONArray())
            put("content", content)
        }.toString()
    }
}

data class ProfileEditUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val error: String? = null,
    val pubkey: String? = null,

    // Editable fields
    val name: String = "",
    val displayName: String = "",
    val about: String = "",
    val picture: String = "",
    val banner: String = "",
    val nip05: String = "",
    val lud16: String = "",
    val website: String = "",

    // For Amber signing flow
    val needsAmberSigning: Intent? = null,
    val pendingMetadataJson: String? = null,

    // Original metadata for comparison
    val originalMetadata: UserMetadata? = null
) {
    val hasChanges: Boolean
        get() {
            val orig = originalMetadata ?: return name.isNotBlank() || displayName.isNotBlank() ||
                    about.isNotBlank() || picture.isNotBlank() || lud16.isNotBlank()
            return name != (orig.name ?: "") ||
                    displayName != (orig.displayName ?: "") ||
                    about != (orig.about ?: "") ||
                    picture != (orig.picture ?: "") ||
                    banner != (orig.banner ?: "") ||
                    nip05 != (orig.nip05 ?: "") ||
                    lud16 != (orig.lud16 ?: "") ||
                    website != (orig.website ?: "")
        }
}
