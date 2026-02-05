package com.nostr.unfiltered.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.BlossomClient
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
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
import rust.nostr.protocol.Kind
import rust.nostr.protocol.Tag
import javax.inject.Inject

@HiltViewModel
class CreatePostViewModel @Inject constructor(
    private val blossomClient: BlossomClient,
    private val nostrClient: NostrClient,
    private val keyManager: KeyManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreatePostUiState())
    val uiState: StateFlow<CreatePostUiState> = _uiState.asStateFlow()

    // Amber signing flow state
    private var pendingPreparedUpload: BlossomClient.PreparedUpload? = null
    private var pendingUploadResult: BlossomClient.UploadResult? = null
    private var pendingDimensions: Pair<Int, Int>? = null
    private var pendingContext: Context? = null
    private var pendingPostUnsignedEvent: String? = null
    private var pendingKind1UnsignedEvent: String? = null

    fun setSelectedImage(uri: Uri) {
        _uiState.update { it.copy(selectedImageUri = uri, error = null) }
    }

    fun setCaption(caption: String) {
        _uiState.update { it.copy(caption = caption) }
    }

    fun setAltText(altText: String) {
        _uiState.update { it.copy(altText = altText) }
    }

    fun createPost(context: Context) {
        val imageUri = _uiState.value.selectedImageUri ?: return

        _uiState.update { it.copy(isUploading = true, error = null) }

        viewModelScope.launch {
            try {
                // Get image dimensions
                val dimensions = getImageDimensions(context, imageUri)
                pendingDimensions = dimensions
                pendingContext = context

                if (keyManager.isAmberConnected()) {
                    // Amber flow: prepare upload and request auth signing
                    val prepareResult = blossomClient.prepareUpload(context, imageUri)
                    prepareResult.fold(
                        onSuccess = { prepared ->
                            pendingPreparedUpload = prepared
                            val intent = keyManager.createAmberSignEventIntent(
                                eventJson = prepared.unsignedAuthEvent,
                                eventId = "blossom_auth"
                            )
                            _uiState.update {
                                it.copy(
                                    isUploading = false,
                                    pendingAmberIntent = intent,
                                    amberSigningStep = AmberSigningStep.AUTH
                                )
                            }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(
                                    isUploading = false,
                                    error = error.message ?: "Failed to prepare upload"
                                )
                            }
                        }
                    )
                } else {
                    // Local keys flow: existing behavior
                    val uploadResult = blossomClient.uploadImage(context, imageUri)

                    uploadResult.fold(
                        onSuccess = { result ->
                            publishPhotoPost(
                                imageUrl = result.url,
                                sha256 = result.sha256,
                                mimeType = result.mimeType,
                                dimensions = dimensions,
                                caption = _uiState.value.caption,
                                altText = _uiState.value.altText
                            )

                            _uiState.update {
                                it.copy(
                                    isUploading = false,
                                    isSuccess = true
                                )
                            }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(
                                    isUploading = false,
                                    error = error.message ?: "Upload failed"
                                )
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isUploading = false,
                        error = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    /**
     * Handle signed authorization event from Amber - continue with upload
     */
    fun handleAmberSignedAuthEvent(signedEventJson: String) {
        val prepared = pendingPreparedUpload ?: return

        _uiState.update { it.copy(isUploading = true, pendingAmberIntent = null, amberSigningStep = null) }

        viewModelScope.launch {
            try {
                val uploadResult = blossomClient.uploadWithSignedAuth(prepared, signedEventJson)

                uploadResult.fold(
                    onSuccess = { result ->
                        pendingUploadResult = result
                        // Now create unsigned post event for Amber signing
                        val unsignedPostEvent = createUnsignedPhotoPostEvent(
                            imageUrl = result.url,
                            sha256 = result.sha256,
                            mimeType = result.mimeType,
                            dimensions = pendingDimensions,
                            caption = _uiState.value.caption,
                            altText = _uiState.value.altText
                        )
                        pendingPostUnsignedEvent = unsignedPostEvent
                        val intent = keyManager.createAmberSignEventIntent(
                            eventJson = unsignedPostEvent,
                            eventId = "photo_post"
                        )
                        _uiState.update {
                            it.copy(
                                isUploading = false,
                                pendingAmberIntent = intent,
                                amberSigningStep = AmberSigningStep.POST
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isUploading = false,
                                error = error.message ?: "Upload failed"
                            )
                        }
                        clearPendingState()
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isUploading = false,
                        error = e.message ?: "Upload failed"
                    )
                }
                clearPendingState()
            }
        }
    }

    /**
     * Handle signed post event from Amber - publish to relays.
     * Amber may return either:
     * - Full signed event JSON (with "id" and "sig" fields)
     * - Just the signature (hex string)
     */
    fun handleAmberSignedPostEvent(signedEventJson: String) {
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
                    nostrClient.publish(event)

                    // Now create Kind 1 event for cross-client compatibility
                    val uploadResult = pendingUploadResult
                    if (uploadResult != null) {
                        val unsignedKind1Event = createUnsignedKind1PostEvent(
                            imageUrl = uploadResult.url,
                            caption = _uiState.value.caption
                        )
                        pendingKind1UnsignedEvent = unsignedKind1Event
                        val intent = keyManager.createAmberSignEventIntent(
                            eventJson = unsignedKind1Event,
                            eventId = "kind1_post"
                        )
                        _uiState.update {
                            it.copy(
                                pendingAmberIntent = intent,
                                amberSigningStep = AmberSigningStep.POST_KIND1
                            )
                        }
                    } else {
                        // No upload result, just finish
                        _uiState.update {
                            it.copy(
                                pendingAmberIntent = null,
                                amberSigningStep = null,
                                isSuccess = true
                            )
                        }
                        clearPendingState()
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            pendingAmberIntent = null,
                            amberSigningStep = null,
                            error = "Failed to reconstruct signed event"
                        )
                    }
                    clearPendingState()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        pendingAmberIntent = null,
                        amberSigningStep = null,
                        error = e.message ?: "Failed to publish post"
                    )
                }
                clearPendingState()
            }
        }
    }

    /**
     * Handle signed Kind 1 post event from Amber - publish for cross-client compatibility
     */
    fun handleAmberSignedKind1Event(signedEventJson: String) {
        viewModelScope.launch {
            try {
                val event = try {
                    Event.fromJson(signedEventJson)
                } catch (e: Exception) {
                    reconstructSignedKind1Event(signedEventJson)
                }

                if (event != null) {
                    nostrClient.publish(event)
                }

                // Regardless of Kind 1 success, the post was created (Kind 20 already published)
                _uiState.update {
                    it.copy(
                        pendingAmberIntent = null,
                        amberSigningStep = null,
                        isSuccess = true
                    )
                }
                clearPendingState()
            } catch (e: Exception) {
                // Kind 1 failed but Kind 20 was published, still consider it a success
                _uiState.update {
                    it.copy(
                        pendingAmberIntent = null,
                        amberSigningStep = null,
                        isSuccess = true
                    )
                }
                clearPendingState()
            }
        }
    }

    /**
     * Reconstruct a signed event from a signature and the stored unsigned event.
     */
    private fun reconstructSignedEvent(signature: String): Event? {
        val unsignedJson = pendingPostUnsignedEvent ?: return null

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

    /**
     * Reconstruct a signed Kind 1 event from a signature.
     */
    private fun reconstructSignedKind1Event(signature: String): Event? {
        val unsignedJson = pendingKind1UnsignedEvent ?: return null

        return try {
            val eventObj = JSONObject(unsignedJson)
            val serialized = computeEventSerialization(eventObj)
            val eventId = computeSha256Hex(serialized)
            eventObj.put("id", eventId)
            eventObj.put("sig", signature)
            Event.fromJson(eventObj.toString())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create unsigned Kind 1 post event for cross-client compatibility
     */
    private fun createUnsignedKind1PostEvent(imageUrl: String, caption: String): String {
        val pubkey = keyManager.getPublicKeyHex() ?: ""
        val createdAt = System.currentTimeMillis() / 1000

        // Content is caption followed by image URL
        val content = if (caption.isNotBlank()) {
            "$caption\n\n$imageUrl"
        } else {
            imageUrl
        }

        return JSONObject().apply {
            put("kind", 1)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("tags", JSONArray())
            put("content", content)
        }.toString()
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

    /**
     * Clear pending Amber intent (user cancelled)
     */
    fun clearPendingAmberIntent() {
        _uiState.update {
            it.copy(
                pendingAmberIntent = null,
                amberSigningStep = null,
                isUploading = false
            )
        }
        clearPendingState()
    }

    private fun clearPendingState() {
        pendingPreparedUpload = null
        pendingUploadResult = null
        pendingDimensions = null
        pendingContext = null
        pendingPostUnsignedEvent = null
        pendingKind1UnsignedEvent = null
    }

    /**
     * Create unsigned kind 20 photo post event for Amber signing
     */
    private fun createUnsignedPhotoPostEvent(
        imageUrl: String,
        sha256: String,
        mimeType: String,
        dimensions: Pair<Int, Int>?,
        caption: String,
        altText: String
    ): String {
        val pubkey = keyManager.getPublicKeyHex() ?: ""
        val createdAt = System.currentTimeMillis() / 1000

        // Build imeta tag
        val imetaTag = JSONArray().apply {
            put("imeta")
            put("url $imageUrl")
            if (sha256.isNotEmpty()) put("x $sha256")
            if (mimeType.isNotEmpty()) put("m $mimeType")
            dimensions?.let { (w, h) ->
                if (w > 0 && h > 0) put("dim ${w}x${h}")
            }
            if (altText.isNotBlank()) put("alt $altText")
        }

        val tags = JSONArray().apply {
            put(imetaTag)
            if (caption.isNotBlank() && caption.length <= 100) {
                put(JSONArray().apply {
                    put("title")
                    put(caption.take(100))
                })
            }
        }

        return JSONObject().apply {
            put("kind", 20)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("tags", tags)
            put("content", caption)
        }.toString()
    }

    private fun publishPhotoPost(
        imageUrl: String,
        sha256: String,
        mimeType: String,
        dimensions: Pair<Int, Int>?,
        caption: String,
        altText: String
    ) {
        val keys = keyManager.getKeys() ?: return

        // Build imeta tag according to NIP-68
        val imetaParts = mutableListOf("imeta", "url $imageUrl")

        if (sha256.isNotEmpty()) {
            imetaParts.add("x $sha256")
        }

        if (mimeType.isNotEmpty()) {
            imetaParts.add("m $mimeType")
        }

        dimensions?.let { (width, height) ->
            if (width > 0 && height > 0) {
                imetaParts.add("dim ${width}x${height}")
            }
        }

        if (altText.isNotBlank()) {
            imetaParts.add("alt $altText")
        }

        val tags = mutableListOf(
            Tag.parse(imetaParts)
        )

        // Add title tag if caption is short enough
        if (caption.isNotBlank() && caption.length <= 100) {
            tags.add(Tag.parse(listOf("title", caption.take(100))))
        }

        // Kind 20 = picture post (NIP-68)
        val kind20Event = EventBuilder(Kind(20u), caption, tags)
            .toEvent(keys)

        nostrClient.publish(kind20Event)

        // Also publish Kind 1 for cross-client compatibility (Primal, etc.)
        // Content is the caption followed by the image URL
        val kind1Content = if (caption.isNotBlank()) {
            "$caption\n\n$imageUrl"
        } else {
            imageUrl
        }

        val kind1Event = EventBuilder(Kind(1u), kind1Content, emptyList())
            .toEvent(keys)

        nostrClient.publish(kind1Event)
    }

    private fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                Pair(options.outWidth, options.outHeight)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun resetState() {
        _uiState.value = CreatePostUiState()
        clearPendingState()
    }
}

enum class AmberSigningStep {
    AUTH,       // Signing Blossom authorization event
    POST,       // Signing photo post event (Kind 20)
    POST_KIND1  // Signing compatibility post event (Kind 1)
}

data class CreatePostUiState(
    val selectedImageUri: Uri? = null,
    val caption: String = "",
    val altText: String = "",
    val isUploading: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null,
    val pendingAmberIntent: Intent? = null,
    val amberSigningStep: AmberSigningStep? = null
)
