package com.nostr.unfiltered.viewmodel

import android.content.Context
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

                // Upload to Blossom
                val uploadResult = blossomClient.uploadImage(context, imageUri)

                uploadResult.fold(
                    onSuccess = { result ->
                        // Create and publish kind 20 event
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
        val event = EventBuilder(Kind(20u), caption, tags)
            .toEvent(keys)

        nostrClient.publish(event)
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
    }
}

data class CreatePostUiState(
    val selectedImageUri: Uri? = null,
    val caption: String = "",
    val altText: String = "",
    val isUploading: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)
