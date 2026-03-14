package com.nostr.unfiltered.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.BlossomClient
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.ui.screens.createpost.ImageFilter
import com.nostr.unfiltered.ui.screens.createpost.applyFilter
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
    private var pendingUploadResults: List<UploadedMedia> = emptyList()
    private var pendingDimensions: Pair<Int, Int>? = null
    private var pendingContext: Context? = null
    private var pendingPostUnsignedEvent: String? = null
    private var pendingKind1UnsignedEvent: String? = null

    data class UploadedMedia(
        val url: String,
        val sha256: String,
        val mimeType: String,
        val dimensions: Pair<Int, Int>?,
        val isVideo: Boolean
    )

    fun addMedia(context: Context, uri: Uri) {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val isVideo = mimeType.startsWith("video/")
        _uiState.update {
            it.copy(
                selectedMedia = it.selectedMedia + SelectedMediaItem(uri, isVideo),
                error = null
            )
        }
    }

    fun addMultipleMedia(context: Context, uris: List<Uri>) {
        val items = uris.map { uri ->
            val mimeType = context.contentResolver.getType(uri) ?: ""
            SelectedMediaItem(uri, mimeType.startsWith("video/"))
        }
        _uiState.update {
            it.copy(
                selectedMedia = it.selectedMedia + items,
                error = null
            )
        }
    }

    fun removeMedia(index: Int) {
        _uiState.update {
            it.copy(selectedMedia = it.selectedMedia.filterIndexed { i, _ -> i != index })
        }
    }

    fun setCaption(caption: String) {
        _uiState.update { it.copy(caption = caption) }
    }

    fun setAltText(altText: String) {
        _uiState.update { it.copy(altText = altText) }
    }

    fun setFilter(filter: ImageFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun createPost(context: Context) {
        val media = _uiState.value.selectedMedia
        if (media.isEmpty()) return
        val selectedFilter = _uiState.value.selectedFilter

        _uiState.update { it.copy(isUploading = true, error = null) }

        viewModelScope.launch {
            try {
                if (keyManager.isAmberConnected()) {
                    // Amber flow: upload all images first, then sign event
                    val uploadResults = mutableListOf<UploadedMedia>()
                    for ((index, item) in media.withIndex()) {
                        _uiState.update {
                            it.copy(uploadProgress = "Uploading ${index + 1} of ${media.size}...")
                        }
                        val uploadUri = if (!item.isVideo) {
                            prepareImageForUpload(context, item.uri, selectedFilter)
                        } else {
                            item.uri
                        }
                        val dimensions = if (item.isVideo) {
                            getVideoDimensions(context, uploadUri)
                        } else {
                            getImageDimensions(context, uploadUri)
                        }

                        val prepareResult = blossomClient.prepareUpload(context, uploadUri)
                        val prepared = prepareResult.getOrThrow()

                        // For Amber, we need to sign each auth event - use first one to kick off flow
                        // For simplicity with multiple images, we'll store all and sign sequentially
                        pendingPreparedUpload = prepared
                        pendingDimensions = dimensions
                        pendingContext = context

                        val intent = keyManager.createAmberSignEventIntent(
                            eventJson = prepared.unsignedAuthEvent,
                            eventId = "blossom_auth_$index"
                        )
                        pendingUploadResults = uploadResults.toList()
                        _uiState.update {
                            it.copy(
                                isUploading = false,
                                pendingAmberIntent = intent,
                                amberSigningStep = AmberSigningStep.AUTH
                            )
                        }
                        // Amber flow continues in handleAmberSignedAuthEvent
                        return@launch
                    }
                } else {
                    // Local keys flow: upload all images, then publish
                    val uploadResults = mutableListOf<UploadedMedia>()
                    for ((index, item) in media.withIndex()) {
                        _uiState.update {
                            it.copy(uploadProgress = "Uploading ${index + 1} of ${media.size}...")
                        }
                        val uploadUri = if (!item.isVideo) {
                            prepareImageForUpload(context, item.uri, selectedFilter)
                        } else {
                            item.uri
                        }
                        val dimensions = if (item.isVideo) {
                            getVideoDimensions(context, uploadUri)
                        } else {
                            getImageDimensions(context, uploadUri)
                        }

                        val result = blossomClient.uploadImage(context, uploadUri).getOrThrow()
                        uploadResults.add(
                            UploadedMedia(
                                url = result.url,
                                sha256 = result.sha256,
                                mimeType = result.mimeType,
                                dimensions = dimensions,
                                isVideo = item.isVideo
                            )
                        )
                    }

                    publishMultiPhotoPost(
                        uploads = uploadResults,
                        caption = _uiState.value.caption,
                        altText = _uiState.value.altText
                    )

                    _uiState.update {
                        it.copy(isUploading = false, uploadProgress = "", isSuccess = true)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isUploading = false,
                        uploadProgress = "",
                        error = e.message ?: "Upload failed"
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
        val context = pendingContext ?: return
        val media = _uiState.value.selectedMedia
        val selectedFilter = _uiState.value.selectedFilter

        _uiState.update { it.copy(isUploading = true, pendingAmberIntent = null, amberSigningStep = null) }

        viewModelScope.launch {
            try {
                val uploadResult = blossomClient.uploadWithSignedAuth(prepared, signedEventJson)
                val result = uploadResult.getOrThrow()

                val currentResults = pendingUploadResults.toMutableList()
                currentResults.add(
                    UploadedMedia(
                        url = result.url,
                        sha256 = result.sha256,
                        mimeType = result.mimeType,
                        dimensions = pendingDimensions,
                        isVideo = media.getOrNull(currentResults.size)?.isVideo ?: false
                    )
                )

                // Check if there are more images to upload
                val nextIndex = currentResults.size
                if (nextIndex < media.size) {
                    _uiState.update {
                        it.copy(uploadProgress = "Uploading ${nextIndex + 1} of ${media.size}...")
                    }
                    val nextItem = media[nextIndex]
                    val uploadUri = if (!nextItem.isVideo) {
                        prepareImageForUpload(context, nextItem.uri, selectedFilter)
                    } else {
                        nextItem.uri
                    }
                    val dimensions = if (nextItem.isVideo) {
                        getVideoDimensions(context, uploadUri)
                    } else {
                        getImageDimensions(context, uploadUri)
                    }

                    val prepareResult = blossomClient.prepareUpload(context, uploadUri)
                    val nextPrepared = prepareResult.getOrThrow()

                    pendingPreparedUpload = nextPrepared
                    pendingDimensions = dimensions
                    pendingUploadResults = currentResults

                    val intent = keyManager.createAmberSignEventIntent(
                        eventJson = nextPrepared.unsignedAuthEvent,
                        eventId = "blossom_auth_$nextIndex"
                    )
                    _uiState.update {
                        it.copy(
                            isUploading = false,
                            pendingAmberIntent = intent,
                            amberSigningStep = AmberSigningStep.AUTH
                        )
                    }
                } else {
                    // All uploads done - create the post event for signing
                    pendingUploadResults = currentResults
                    pendingUploadResult = result // keep for Kind 1 compat

                    val unsignedPostEvent = createUnsignedMultiPhotoPostEvent(
                        uploads = currentResults,
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
                            uploadProgress = "",
                            pendingAmberIntent = intent,
                            amberSigningStep = AmberSigningStep.POST
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isUploading = false,
                        uploadProgress = "",
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
                    val uploads = pendingUploadResults
                    if (uploads.isNotEmpty()) {
                        val unsignedKind1Event = createUnsignedKind1PostEvent(
                            imageUrls = uploads.map { it.url },
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
    private fun createUnsignedKind1PostEvent(imageUrls: List<String>, caption: String): String {
        val pubkey = keyManager.getPublicKeyHex() ?: ""
        val createdAt = System.currentTimeMillis() / 1000

        val urlBlock = imageUrls.joinToString("\n")
        val content = if (caption.isNotBlank()) {
            "$caption\n\n$urlBlock"
        } else {
            urlBlock
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
        pendingUploadResults = emptyList()
        pendingDimensions = null
        pendingContext = null
        pendingPostUnsignedEvent = null
        pendingKind1UnsignedEvent = null
    }

    /**
     * Create unsigned kind 20 photo post event for Amber signing (multi-image)
     */
    private fun createUnsignedMultiPhotoPostEvent(
        uploads: List<UploadedMedia>,
        caption: String,
        altText: String
    ): String {
        val pubkey = keyManager.getPublicKeyHex() ?: ""
        val createdAt = System.currentTimeMillis() / 1000

        val tags = JSONArray()

        // Build imeta tag for each upload
        for (upload in uploads) {
            val imetaTag = JSONArray().apply {
                put("imeta")
                put("url ${upload.url}")
                if (upload.sha256.isNotEmpty()) put("x ${upload.sha256}")
                if (upload.mimeType.isNotEmpty()) put("m ${upload.mimeType}")
                upload.dimensions?.let { (w, h) ->
                    if (w > 0 && h > 0) put("dim ${w}x${h}")
                }
                if (altText.isNotBlank()) put("alt $altText")
            }
            tags.put(imetaTag)
        }

        if (caption.isNotBlank() && caption.length <= 100) {
            tags.put(JSONArray().apply {
                put("title")
                put(caption.take(100))
            })
        }

        return JSONObject().apply {
            put("kind", 20)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("tags", tags)
            put("content", caption)
        }.toString()
    }

    private fun publishMultiPhotoPost(
        uploads: List<UploadedMedia>,
        caption: String,
        altText: String
    ) {
        val keys = keyManager.getKeys() ?: return

        val tags = mutableListOf<Tag>()

        // Build imeta tag for each upload according to NIP-68
        for (upload in uploads) {
            val imetaParts = mutableListOf("imeta", "url ${upload.url}")
            if (upload.sha256.isNotEmpty()) imetaParts.add("x ${upload.sha256}")
            if (upload.mimeType.isNotEmpty()) imetaParts.add("m ${upload.mimeType}")
            upload.dimensions?.let { (w, h) ->
                if (w > 0 && h > 0) imetaParts.add("dim ${w}x${h}")
            }
            if (altText.isNotBlank()) imetaParts.add("alt $altText")
            tags.add(Tag.parse(imetaParts))
        }

        if (caption.isNotBlank() && caption.length <= 100) {
            tags.add(Tag.parse(listOf("title", caption.take(100))))
        }

        // Kind 20 = picture post (NIP-68)
        val kind20Event = EventBuilder(Kind(20u), caption, tags)
            .toEvent(keys)

        nostrClient.publish(kind20Event)

        // Also publish Kind 1 for cross-client compatibility
        val urlBlock = uploads.joinToString("\n") { it.url }
        val kind1Content = if (caption.isNotBlank()) {
            "$caption\n\n$urlBlock"
        } else {
            urlBlock
        }

        val kind1Event = EventBuilder(Kind(1u), kind1Content, emptyList())
            .toEvent(keys)

        nostrClient.publish(kind1Event)
    }

    companion object {
        private const val MAX_IMAGE_DIMENSION = 2048
        private const val JPEG_QUALITY = 85
    }

    /**
     * Prepare an image for upload: apply EXIF rotation, resize to max 2048px
     * on the long side, apply filter if needed, and compress as JPEG.
     */
    private fun prepareImageForUpload(context: Context, uri: Uri, filter: ImageFilter): Uri {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open image")
        val originalBitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        // Apply EXIF orientation
        val orientedBitmap = applyExifOrientation(context, uri, originalBitmap)

        // Resize if larger than max dimension
        val resizedBitmap = resizeBitmap(orientedBitmap, MAX_IMAGE_DIMENSION)

        // Apply filter if needed
        val finalBitmap = if (filter != ImageFilter.NONE) {
            val filtered = applyFilter(resizedBitmap, filter)
            if (resizedBitmap !== orientedBitmap && resizedBitmap !== filtered) {
                resizedBitmap.recycle()
            }
            filtered
        } else {
            resizedBitmap
        }

        val tempFile = java.io.File(context.cacheDir, "prepared_${System.currentTimeMillis()}.jpg")
        tempFile.outputStream().use { out ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }

        if (finalBitmap !== orientedBitmap && finalBitmap !== originalBitmap) {
            finalBitmap.recycle()
        }
        if (resizedBitmap !== orientedBitmap && resizedBitmap !== finalBitmap) {
            resizedBitmap.recycle()
        }
        if (orientedBitmap !== originalBitmap) {
            orientedBitmap.recycle()
        }
        originalBitmap.recycle()

        return tempFile.toUri()
    }

    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longSide = maxOf(width, height)

        if (longSide <= maxDimension) return bitmap

        val scale = maxDimension.toFloat() / longSide.toFloat()
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
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

    private fun getVideoDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            retriever.release()
            if (width != null && height != null && width > 0 && height > 0) {
                Pair(width, height)
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

data class SelectedMediaItem(
    val uri: Uri,
    val isVideo: Boolean = false
)

data class CreatePostUiState(
    val selectedMedia: List<SelectedMediaItem> = emptyList(),
    val caption: String = "",
    val altText: String = "",
    val selectedFilter: ImageFilter = ImageFilter.NONE,
    val isUploading: Boolean = false,
    val uploadProgress: String = "",
    val isSuccess: Boolean = false,
    val error: String? = null,
    val pendingAmberIntent: Intent? = null,
    val amberSigningStep: AmberSigningStep? = null
) {
    // Convenience accessors for backward compatibility
    val selectedImageUri: Uri? get() = selectedMedia.firstOrNull()?.uri
    val isVideo: Boolean get() = selectedMedia.size == 1 && selectedMedia.first().isVideo
    val hasMedia: Boolean get() = selectedMedia.isNotEmpty()
}
