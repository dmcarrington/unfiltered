package com.nostr.unfiltered.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.BlossomClient
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.MetadataCache
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.models.UserMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
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
    private val nostrClient: NostrClient,
    private val blossomClient: BlossomClient,
    private val metadataCache: MetadataCache
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileEditUiState())
    private var pendingPreparedUpload: BlossomClient.PreparedUpload? = null
    val uiState: StateFlow<ProfileEditUiState> = _uiState.asStateFlow()

    init {
        loadCurrentProfile()
    }

    private fun loadCurrentProfile() {
        val pubkeyHex = keyManager.getPublicKeyHex() ?: return

        _uiState.update { it.copy(isLoading = true, pubkey = pubkeyHex) }

        // Use cached metadata for immediate display if available
        val cached = metadataCache.get(pubkeyHex)
        if (cached != null) {
            applyMetadata(cached)
        }

        // Fetch freshest metadata from relays
        val subscriptionId = "profile_edit_${System.currentTimeMillis()}"

        viewModelScope.launch {
            try {
                val filter = Filter()
                    .kind(Kind(0u))
                    .author(PublicKey.fromHex(pubkeyHex))
                    .limit(1u)

                val result = withTimeoutOrNull(5000L) {
                    // Start collector before subscribing to avoid any race
                    val deferred = async {
                        nostrClient.events.first { nostrEvent ->
                            nostrEvent.subscriptionId == subscriptionId &&
                                nostrEvent.event.kind().asU16().toInt() == 0
                        }
                    }
                    yield()
                    nostrClient.subscribe(subscriptionId, listOf(filter))
                    deferred.await()
                }

                nostrClient.unsubscribe(subscriptionId)

                if (result != null) {
                    val metadata = UserMetadata.fromJson(
                        pubkey = result.event.author().toHex(),
                        json = result.event.content(),
                        createdAt = result.event.createdAt().asSecs().toLong()
                    )
                    applyMetadata(metadata)
                } else {
                    // Timed out — if cache already populated the fields, just clear loading
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun applyMetadata(metadata: UserMetadata) {
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
     * Handle the signed event returned from Amber.
     * Amber may return either:
     * - Full signed event JSON (with "id" and "sig" fields)
     * - Just the signature (hex string)
     */
    fun handleAmberSignedEvent(signedEventJson: String) {
        viewModelScope.launch {
            try {
                val event = try {
                    // First, try to parse as a full signed event
                    Event.fromJson(signedEventJson)
                } catch (e: Exception) {
                    // If that fails, it might be just a signature - reconstruct the event
                    reconstructSignedEvent(signedEventJson)
                }

                if (event != null) {
                    val success = nostrClient.publish(event)
                    _uiState.update {
                        it.copy(
                            needsAmberSigning = null,
                            pendingMetadataJson = null,
                            saveSuccess = success,
                            error = if (!success) "Failed to publish to relays" else null
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            needsAmberSigning = null,
                            pendingMetadataJson = null,
                            error = "Failed to reconstruct signed event"
                        )
                    }
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

    /**
     * Reconstruct a signed event from a signature and the stored unsigned event.
     */
    private fun reconstructSignedEvent(signature: String): Event? {
        val unsignedJson = _uiState.value.pendingMetadataJson ?: return null

        return try {
            val eventObj = JSONObject(unsignedJson)

            // Compute the event ID (sha256 of serialized event)
            val serialized = computeEventSerialization(eventObj)
            val eventId = computeSha256Hex(serialized)

            // Add id and sig to create signed event
            eventObj.put("id", eventId)
            eventObj.put("sig", signature)

            Event.fromJson(eventObj.toString())
        } catch (e: Exception) {
            null
        }
    }

    private fun computeEventSerialization(event: JSONObject): String {
        val kind = event.getInt("kind")
        val pubkey = event.getString("pubkey")
        val createdAt = event.getLong("created_at")
        val tags = event.getJSONArray("tags")
        val content = event.getString("content")

        // NIP-01 serialization: [0,<pubkey>,<created_at>,<kind>,<tags>,<content>]
        return "[0,\"$pubkey\",$createdAt,$kind,$tags,\"${escapeJsonString(content)}\"]"
    }

    private fun escapeJsonString(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun computeSha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun uploadProfilePicture(context: Context, imageUri: Uri) {
        _uiState.update { it.copy(isUploadingPicture = true, error = null) }

        viewModelScope.launch {
            try {
                if (keyManager.isAmberConnected()) {
                    val prepareResult = blossomClient.prepareUpload(context, imageUri)
                    prepareResult.fold(
                        onSuccess = { prepared ->
                            pendingPreparedUpload = prepared
                            val intent = keyManager.createAmberSignEventIntent(
                                eventJson = prepared.unsignedAuthEvent,
                                eventId = "blossom_profile_pic"
                            )
                            _uiState.update {
                                it.copy(
                                    isUploadingPicture = false,
                                    pendingBlossomAmberIntent = intent
                                )
                            }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(
                                    isUploadingPicture = false,
                                    error = error.message ?: "Failed to prepare upload"
                                )
                            }
                        }
                    )
                } else {
                    val uploadResult = blossomClient.uploadImage(context, imageUri)
                    uploadResult.fold(
                        onSuccess = { result ->
                            _uiState.update {
                                it.copy(isUploadingPicture = false, picture = result.url)
                            }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(
                                    isUploadingPicture = false,
                                    error = error.message ?: "Upload failed"
                                )
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isUploadingPicture = false, error = e.message ?: "Upload failed")
                }
            }
        }
    }

    fun handleBlossomAmberSignedEvent(signedEventJson: String) {
        val prepared = pendingPreparedUpload ?: return
        pendingPreparedUpload = null

        _uiState.update { it.copy(isUploadingPicture = true, pendingBlossomAmberIntent = null) }

        viewModelScope.launch {
            try {
                val uploadResult = blossomClient.uploadWithSignedAuth(prepared, signedEventJson)
                uploadResult.fold(
                    onSuccess = { result ->
                        _uiState.update {
                            it.copy(isUploadingPicture = false, picture = result.url)
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isUploadingPicture = false,
                                error = error.message ?: "Upload failed"
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isUploadingPicture = false, error = e.message ?: "Upload failed")
                }
            }
        }
    }

    fun clearBlossomAmberIntent() {
        _uiState.update { it.copy(pendingBlossomAmberIntent = null) }
        pendingPreparedUpload = null
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

    // For Blossom image upload
    val isUploadingPicture: Boolean = false,
    val pendingBlossomAmberIntent: Intent? = null,

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
