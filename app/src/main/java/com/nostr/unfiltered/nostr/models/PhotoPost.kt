package com.nostr.unfiltered.nostr.models

/**
 * Represents a single media item (image or video) within a post
 */
data class MediaItem(
    val url: String,
    val mimeType: String? = null,
    val blurhash: String? = null,
    val dimensions: ImageDimensions? = null,
    val altText: String? = null,
    val fallbackUrls: List<String> = emptyList(),
    val isVideo: Boolean = false
)

/**
 * Represents a photo post (NIP-68 kind 20 event)
 */
data class PhotoPost(
    val id: String,
    val authorPubkey: String,
    val createdAt: Long,
    val imageUrl: String,
    val caption: String,
    val title: String? = null,
    val blurhash: String? = null,
    val dimensions: ImageDimensions? = null,
    val altText: String? = null,
    val fallbackUrls: List<String> = emptyList(),
    val isVideo: Boolean = false,

    // Multiple media items (for posts with multiple images/videos)
    val mediaItems: List<MediaItem> = emptyList(),

    // Populated from author's metadata
    val authorName: String? = null,
    val authorAvatar: String? = null,
    val authorNip05: String? = null,
    val authorLud16: String? = null,

    // Interaction state
    val isLiked: Boolean = false,
    val likeCount: Int = 0,
    val zapAmount: Long = 0,
    val isZapped: Boolean = false,

    // For display
    val relativeTime: String = ""
) {
    val authorNpub: String
        get() = try {
            rust.nostr.protocol.PublicKey.fromHex(authorPubkey).toBech32()
        } catch (e: Exception) {
            authorPubkey.take(16) + "..."
        }

    /** Returns true if this post has multiple media items */
    val hasMultipleMedia: Boolean
        get() = mediaItems.size > 1
}

data class ImageDimensions(
    val width: Int,
    val height: Int
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 1f
}
