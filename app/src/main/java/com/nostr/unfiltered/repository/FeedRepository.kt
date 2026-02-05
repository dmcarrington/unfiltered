package com.nostr.unfiltered.repository

import android.content.Intent
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.MetadataCache
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.NostrEvent
import com.nostr.unfiltered.nostr.SearchService
import com.nostr.unfiltered.nostr.models.ContactList
import com.nostr.unfiltered.nostr.models.ImageDimensions
import com.nostr.unfiltered.nostr.models.MediaItem
import com.nostr.unfiltered.nostr.models.PhotoPost
import com.nostr.unfiltered.nostr.models.UserMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import rust.nostr.protocol.Event
import rust.nostr.protocol.EventBuilder
import rust.nostr.protocol.EventId
import rust.nostr.protocol.Filter
import rust.nostr.protocol.Kind
import rust.nostr.protocol.PublicKey
import rust.nostr.protocol.Tag
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val keyManager: KeyManager,
    private val metadataCache: MetadataCache,
    private val searchService: SearchService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Caches
    private val postsCache = ConcurrentHashMap<String, PhotoPost>()

    // Track which posts the current user has liked (event IDs)
    private val myLikedPosts = mutableSetOf<String>()

    // Track seen reaction event IDs to prevent double-counting
    private val seenReactionIds = mutableSetOf<String>()

    private val _posts = MutableStateFlow<List<PhotoPost>>(emptyList())
    val posts: StateFlow<List<PhotoPost>> = _posts.asStateFlow()

    private val _followList = MutableStateFlow<Set<String>>(emptySet())
    val followList: StateFlow<Set<String>> = _followList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Callback for new post detection (used by FeedViewModel for new posts indicator)
    private var newestPostCallback: ((Long) -> Unit)? = null

    fun setNewestPostCallback(callback: (Long) -> Unit) {
        newestPostCallback = callback
    }

    // Amber signing flow for follow/unfollow
    private val _pendingFollowIntent = MutableStateFlow<Intent?>(null)
    val pendingFollowIntent: StateFlow<Intent?> = _pendingFollowIntent.asStateFlow()

    private var pendingFollowPubkey: String? = null
    private var pendingFollowAction: FollowAction? = null
    private var pendingFollowUnsignedEvent: String? = null

    enum class FollowAction { FOLLOW, UNFOLLOW }

    // Amber signing flow for likes
    private val _pendingLikeIntent = MutableStateFlow<Intent?>(null)
    val pendingLikeIntent: StateFlow<Intent?> = _pendingLikeIntent.asStateFlow()

    private var pendingLikePostId: String? = null
    private var pendingLikeUnsignedEvent: String? = null

    init {
        observeEvents()
    }

    private fun observeEvents() {
        scope.launch {
            nostrClient.events.collect { nostrEvent ->
                handleEvent(nostrEvent)
            }
        }
    }

    private fun handleEvent(nostrEvent: NostrEvent) {
        val event = nostrEvent.event
        val kind = event.kind().asU16().toInt()

        when (kind) {
            0 -> handleMetadataEvent(event)
            1 -> handlePhotoPostEvent(event) // Kind 1 notes may contain images
            3 -> handleContactListEvent(event)
            7 -> handleReactionEvent(event)
            20 -> handlePhotoPostEvent(event)
        }
    }

    private fun handleMetadataEvent(event: Event) {
        val pubkey = event.author().toHex()
        val existing = metadataCache.get(pubkey)
        val eventCreatedAt = event.createdAt().asSecs().toLong()

        // Only update if newer
        if (existing == null || eventCreatedAt > existing.createdAt) {
            val metadata = UserMetadata.fromJson(
                pubkey = pubkey,
                json = event.content(),
                createdAt = eventCreatedAt
            )
            metadataCache.put(metadata)

            // Update any cached posts with new author info
            updatePostsWithMetadata(pubkey, metadata)
        }
    }

    private fun handleContactListEvent(event: Event) {
        val pubkey = event.author().toHex()
        val myPubkey = keyManager.getPublicKeyHex()

        if (pubkey == myPubkey) {
            val tags = parseTagsFromEvent(event)
            val contactList = ContactList.fromTags(pubkey, tags, event.createdAt().asSecs().toLong())
            _followList.value = contactList.follows
        }
    }

    private fun handleReactionEvent(event: Event) {
        val reactionId = event.id().toHex()

        // Skip if we've already processed this reaction
        if (!seenReactionIds.add(reactionId)) {
            return
        }

        val tags = parseTagsFromEvent(event)
        val targetEventId = tags.find { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
        val reactorPubkey = event.author().toHex()
        val myPubkey = keyManager.getPublicKeyHex()

        // Check if this is the current user's reaction
        val isMyReaction = reactorPubkey == myPubkey
        if (isMyReaction) {
            myLikedPosts.add(targetEventId)
        }

        // Update like status on the target post
        postsCache[targetEventId]?.let { post ->
            val updatedPost = post.copy(
                likeCount = post.likeCount + 1,
                isLiked = post.isLiked || isMyReaction
            )
            postsCache[targetEventId] = updatedPost
            refreshPostsList()
        }
    }

    // Pending pubkeys that need metadata fetching
    private val pendingMetadataFetches = mutableSetOf<String>()
    private var metadataFetchJob: kotlinx.coroutines.Job? = null

    // Pending post IDs that need reaction fetching
    private val pendingReactionFetches = mutableSetOf<String>()
    private var reactionFetchJob: kotlinx.coroutines.Job? = null

    private fun handlePhotoPostEvent(event: Event) {
        val post = parsePhotoPost(event) ?: return
        postsCache[post.id] = post
        refreshPostsList()

        // Notify about new post timestamp for new posts indicator
        newestPostCallback?.invoke(post.createdAt)

        // Queue metadata fetch if not cached
        if (!metadataCache.contains(post.authorPubkey)) {
            synchronized(pendingMetadataFetches) {
                pendingMetadataFetches.add(post.authorPubkey)
            }
            scheduleBatchMetadataFetch()
        }

        // Queue reaction fetch for this post
        synchronized(pendingReactionFetches) {
            pendingReactionFetches.add(post.id)
        }
        scheduleBatchReactionFetch()
    }

    private fun scheduleBatchMetadataFetch() {
        // Cancel existing job if still pending
        if (metadataFetchJob?.isActive == true) return

        metadataFetchJob = scope.launch {
            // Small delay to batch multiple requests
            kotlinx.coroutines.delay(500)

            val pubkeysToFetch: List<String>
            synchronized(pendingMetadataFetches) {
                pubkeysToFetch = pendingMetadataFetches.toList()
                pendingMetadataFetches.clear()
            }

            if (pubkeysToFetch.isNotEmpty()) {
                try {
                    // Use SearchService's reliable direct fetch
                    val metadataList = searchService.fetchMetadataForPubkeys(pubkeysToFetch)

                    // Update posts with fetched metadata
                    metadataList.forEach { metadata ->
                        updatePostsWithMetadata(metadata.pubkey, metadata)
                    }
                } catch (e: Exception) {
                    // Fallback to subscription-based fetch
                    pubkeysToFetch.forEach { fetchUserMetadata(it) }
                }
            }
        }
    }

    private fun scheduleBatchReactionFetch() {
        // Cancel existing job if still pending
        if (reactionFetchJob?.isActive == true) return

        reactionFetchJob = scope.launch {
            // Small delay to batch multiple requests
            kotlinx.coroutines.delay(500)

            val postIdsToFetch: List<String>
            synchronized(pendingReactionFetches) {
                postIdsToFetch = pendingReactionFetches.toList()
                pendingReactionFetches.clear()
            }

            if (postIdsToFetch.isNotEmpty()) {
                try {
                    // Subscribe to reactions (Kind 7) for these posts
                    val eventIds = postIdsToFetch.mapNotNull { postId ->
                        try {
                            EventId.fromHex(postId)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (eventIds.isNotEmpty()) {
                        val reactionsFilter = Filter()
                            .kind(Kind(7u))
                            .events(eventIds)
                            .limit(500u)

                        nostrClient.subscribe("post_reactions", listOf(reactionsFilter))
                    }
                } catch (e: Exception) {
                    // Handle error silently
                }
            }
        }
    }

    fun parsePhotoPost(event: Event): PhotoPost? {
        val tags = parseTagsFromEvent(event)

        // Find ALL imeta tags (posts can have multiple images)
        val imetaTags = tags.filter { it.size >= 2 && it[0] == "imeta" }
        val mediaItems = mutableListOf<MediaItem>()

        for (imetaTag in imetaTags) {
            val item = parseImetaTag(imetaTag)
            if (item != null) {
                mediaItems.add(item)
            }
        }

        // Primary image info (from first imeta or fallback sources)
        var imageUrl: String? = mediaItems.firstOrNull()?.url
        var blurhash: String? = mediaItems.firstOrNull()?.blurhash
        var dimensions: ImageDimensions? = mediaItems.firstOrNull()?.dimensions
        var altText: String? = mediaItems.firstOrNull()?.altText
        var fallbackUrls: List<String> = mediaItems.firstOrNull()?.fallbackUrls ?: emptyList()

        // Also check for standalone url tag or image tag
        if (imageUrl == null) {
            imageUrl = tags.find { it.size >= 2 && it[0] == "url" }?.get(1)
        }
        if (imageUrl == null) {
            imageUrl = tags.find { it.size >= 2 && it[0] == "image" }?.get(1)
        }

        // Check content for image or video URLs if still not found
        if (imageUrl == null) {
            val content = event.content()
            // Try image first
            val imageRegex = Regex("https?://[^\\s]+\\.(jpg|jpeg|png|gif|webp)", RegexOption.IGNORE_CASE)
            imageUrl = imageRegex.find(content)?.value

            // Try video if no image found
            if (imageUrl == null) {
                val videoRegex = Regex("https?://[^\\s]+\\.(mp4|webm|mov|m4v)", RegexOption.IGNORE_CASE)
                imageUrl = videoRegex.find(content)?.value
            }

            // If found in content and no imeta tags, try to find all media URLs
            if (imageUrl != null && mediaItems.isEmpty()) {
                val allMediaRegex = Regex("https?://[^\\s]+\\.(jpg|jpeg|png|gif|webp|mp4|webm|mov|m4v)", RegexOption.IGNORE_CASE)
                allMediaRegex.findAll(content).forEach { match ->
                    val url = match.value
                    val isVideo = isVideoUrl(url)
                    mediaItems.add(MediaItem(url = url, isVideo = isVideo))
                }
            }
        }

        // Must have a media URL
        if (imageUrl == null) return null

        // Determine if primary is a video based on file extension
        val isVideo = isVideoUrl(imageUrl)

        val title = tags.find { it.size >= 2 && it[0] == "title" }?.get(1)
        val authorPubkey = event.author().toHex()
        val metadata = metadataCache.get(authorPubkey)
        val eventId = event.id().toHex()

        return PhotoPost(
            id = eventId,
            authorPubkey = authorPubkey,
            createdAt = event.createdAt().asSecs().toLong(),
            imageUrl = imageUrl,
            caption = event.content(),
            title = title,
            blurhash = blurhash,
            dimensions = dimensions,
            altText = altText,
            fallbackUrls = fallbackUrls,
            isVideo = isVideo,
            mediaItems = mediaItems,
            authorName = metadata?.bestName,
            authorAvatar = metadata?.picture,
            authorNip05 = metadata?.nip05,
            authorLud16 = metadata?.lud16,
            relativeTime = formatRelativeTime(event.createdAt().asSecs().toLong()),
            isLiked = myLikedPosts.contains(eventId)
        )
    }

    /**
     * Parse a single imeta tag into a MediaItem
     */
    private fun parseImetaTag(imetaTag: List<String>): MediaItem? {
        var url: String? = null
        var mimeType: String? = null
        var blurhash: String? = null
        var dimensions: ImageDimensions? = null
        var altText: String? = null
        val fallbackUrls = mutableListOf<String>()

        for (i in 1 until imetaTag.size) {
            val part = imetaTag[i]
            when {
                part.startsWith("url ") -> url = part.removePrefix("url ")
                part.startsWith("m ") -> mimeType = part.removePrefix("m ")
                part.startsWith("blurhash ") -> blurhash = part.removePrefix("blurhash ")
                part.startsWith("dim ") -> {
                    val dimStr = part.removePrefix("dim ")
                    val parts = dimStr.split("x")
                    if (parts.size == 2) {
                        dimensions = ImageDimensions(
                            parts[0].toIntOrNull() ?: 0,
                            parts[1].toIntOrNull() ?: 0
                        )
                    }
                }
                part.startsWith("alt ") -> altText = part.removePrefix("alt ")
                part.startsWith("fallback ") -> fallbackUrls.add(part.removePrefix("fallback "))
            }
        }

        if (url == null) return null

        val isVideo = mimeType?.startsWith("video/") == true || isVideoUrl(url)

        return MediaItem(
            url = url,
            mimeType = mimeType,
            blurhash = blurhash,
            dimensions = dimensions,
            altText = altText,
            fallbackUrls = fallbackUrls,
            isVideo = isVideo
        )
    }

    /**
     * Check if a URL points to a video based on file extension
     */
    private fun isVideoUrl(url: String): Boolean {
        return url.lowercase().let {
            it.endsWith(".mp4") || it.endsWith(".webm") || it.endsWith(".mov") || it.endsWith(".m4v")
        }
    }

    private fun parseTagsFromEvent(event: Event): List<List<String>> {
        return try {
            // Parse tags from the event's JSON representation
            val eventJson = JSONObject(event.asJson())
            val tagsArray = eventJson.optJSONArray("tags") ?: return emptyList()

            val result = mutableListOf<List<String>>()
            for (i in 0 until tagsArray.length()) {
                val tagArray = tagsArray.optJSONArray(i) ?: continue
                val tag = mutableListOf<String>()
                for (j in 0 until tagArray.length()) {
                    tag.add(tagArray.optString(j, ""))
                }
                result.add(tag)
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun updatePostsWithMetadata(pubkey: String, metadata: UserMetadata) {
        postsCache.entries.filter { it.value.authorPubkey == pubkey }.forEach { entry ->
            val updated = entry.value.copy(
                authorName = metadata.bestName,
                authorAvatar = metadata.picture,
                authorNip05 = metadata.nip05,
                authorLud16 = metadata.lud16
            )
            postsCache[entry.key] = updated
        }
        refreshPostsList()
    }

    private fun refreshPostsList() {
        _posts.value = postsCache.values
            .sortedByDescending { it.createdAt }
            .toList()
    }

    // ==================== Subscription Management ====================

    fun subscribeToFeed() {
        _isLoading.value = true

        // Unsubscribe from followed feed if switching modes
        nostrClient.unsubscribe("feed_follows")

        // First, load our follow list and our own reactions
        val myPubkey = keyManager.getPublicKeyHex()
        if (myPubkey != null) {
            val followListFilter = Filter()
                .kind(Kind(3u))
                .author(PublicKey.fromHex(myPubkey))
                .limit(1u)

            nostrClient.subscribe("my_follows", listOf(followListFilter))

            // Subscribe to our own reactions to track which posts we've liked
            val myReactionsFilter = Filter()
                .kind(Kind(7u))
                .author(PublicKey.fromHex(myPubkey))
                .limit(500u)

            nostrClient.subscribe("my_reactions", listOf(myReactionsFilter))
        }

        // Subscribe to global picture posts (kind 20) and text notes (kind 1) that may contain images
        val feedFilterKind20 = Filter()
            .kind(Kind(20u))
            .limit(50u)

        val feedFilterKind1 = Filter()
            .kind(Kind(1u))
            .limit(50u)

        nostrClient.subscribe("feed", listOf(feedFilterKind20, feedFilterKind1))

        _isLoading.value = false
    }

    fun subscribeToFollowedFeed() {
        val follows = _followList.value
        if (follows.isEmpty()) {
            // No follows yet, subscribe to global feed
            subscribeToFeed()
            return
        }

        _isLoading.value = true

        // Unsubscribe from global feed
        nostrClient.unsubscribe("feed")

        // Subscribe to our own reactions to track which posts we've liked
        val myPubkey = keyManager.getPublicKeyHex()
        if (myPubkey != null) {
            val myReactionsFilter = Filter()
                .kind(Kind(7u))
                .author(PublicKey.fromHex(myPubkey))
                .limit(500u)

            nostrClient.subscribe("my_reactions", listOf(myReactionsFilter))
        }

        // Subscribe to posts from followed users
        val authors = follows.mapNotNull { pubkey ->
            try {
                PublicKey.fromHex(pubkey)
            } catch (e: Exception) {
                null
            }
        }

        if (authors.isNotEmpty()) {
            // Subscribe to Kind 20 (picture posts) and Kind 1 (text notes that may contain images)
            val feedFilterKind20 = Filter()
                .kind(Kind(20u))
                .authors(authors)
                .limit(100u)

            val feedFilterKind1 = Filter()
                .kind(Kind(1u))
                .authors(authors)
                .limit(100u)

            nostrClient.subscribe("feed_follows", listOf(feedFilterKind20, feedFilterKind1))

            // Also fetch metadata for followed users
            val metadataFilter = Filter()
                .kind(Kind(0u))
                .authors(authors)

            nostrClient.subscribe("metadata", listOf(metadataFilter))
        }

        _isLoading.value = false
    }

    fun fetchUserMetadata(pubkey: String) {
        try {
            val pk = PublicKey.fromHex(pubkey)
            val filter = Filter()
                .kind(Kind(0u))
                .author(pk)
                .limit(1u)

            nostrClient.subscribe("metadata_$pubkey", listOf(filter))
        } catch (e: Exception) {
            // Invalid pubkey
        }
    }

    fun getUserMetadata(pubkey: String): UserMetadata? {
        return metadataCache.get(pubkey)
    }

    fun getPostsByAuthor(pubkey: String): List<PhotoPost> {
        return postsCache.values
            .filter { it.authorPubkey == pubkey }
            .sortedByDescending { it.createdAt }
    }

    // ==================== Actions ====================

    fun likePost(post: PhotoPost) {
        scope.launch {
            try {
                if (keyManager.isAmberConnected()) {
                    // Amber flow: create unsigned event and request signing
                    val unsignedEvent = createUnsignedLikeEvent(post)
                    val intent = keyManager.createAmberSignEventIntent(
                        eventJson = unsignedEvent,
                        eventId = "like_${post.id}"
                    )
                    pendingLikePostId = post.id
                    pendingLikeUnsignedEvent = unsignedEvent
                    _pendingLikeIntent.value = intent
                } else {
                    // Local keys flow
                    val keys = keyManager.getKeys() ?: return@launch

                    val tags = listOf(
                        Tag.parse(listOf("e", post.id)),
                        Tag.parse(listOf("p", post.authorPubkey)),
                        Tag.parse(listOf("k", "20"))
                    )

                    val event = EventBuilder(Kind(7u), "+", tags)
                        .toEvent(keys)

                    nostrClient.publish(event)

                    // Optimistically update UI
                    postsCache[post.id]?.let { cached ->
                        postsCache[post.id] = cached.copy(isLiked = true, likeCount = cached.likeCount + 1)
                        refreshPostsList()
                    }
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    /**
     * Create unsigned kind 7 (reaction) event for Amber signing
     */
    private fun createUnsignedLikeEvent(post: PhotoPost): String {
        val pubkey = keyManager.getPublicKeyHex() ?: ""
        val createdAt = System.currentTimeMillis() / 1000

        val tags = JSONArray().apply {
            put(JSONArray().apply { put("e"); put(post.id) })
            put(JSONArray().apply { put("p"); put(post.authorPubkey) })
            put(JSONArray().apply { put("k"); put("20") })
        }

        return JSONObject().apply {
            put("kind", 7)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("tags", tags)
            put("content", "+")
        }.toString()
    }

    /**
     * Handle signed like event from Amber.
     * Amber may return either:
     * - Full signed event JSON (with "id" and "sig" fields)
     * - Just the signature (hex string)
     */
    fun handleAmberSignedLike(signedEventJson: String) {
        scope.launch {
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

                    // Optimistically update UI
                    pendingLikePostId?.let { postId ->
                        postsCache[postId]?.let { cached ->
                            postsCache[postId] = cached.copy(isLiked = true, likeCount = cached.likeCount + 1)
                            refreshPostsList()
                        }
                    }
                }
            } catch (e: Exception) {
                // Handle error
            } finally {
                clearPendingLikeIntent()
            }
        }
    }

    /**
     * Reconstruct a signed event from a signature and the stored unsigned event.
     * Amber sometimes returns just the signature instead of the full signed event.
     */
    private fun reconstructSignedEvent(signature: String): Event? {
        val unsignedJson = pendingLikeUnsignedEvent ?: return null

        return try {
            // Parse the unsigned event
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
     * Compute the serialization of an event for ID calculation (NIP-01)
     */
    private fun computeEventSerialization(event: JSONObject): String {
        val kind = event.getInt("kind")
        val pubkey = event.getString("pubkey")
        val createdAt = event.getLong("created_at")
        val tags = event.getJSONArray("tags")
        val content = event.getString("content")

        // NIP-01 serialization: [0,<pubkey>,<created_at>,<kind>,<tags>,<content>]
        return "[0,\"$pubkey\",$createdAt,$kind,$tags,\"${escapeJsonString(content)}\"]"
    }

    /**
     * Escape a string for JSON serialization
     */
    private fun escapeJsonString(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Compute SHA256 hash and return as hex string
     */
    private fun computeSha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Clear pending like intent
     */
    fun clearPendingLikeIntent() {
        _pendingLikeIntent.value = null
        pendingLikePostId = null
        pendingLikeUnsignedEvent = null
    }

    fun clearCache() {
        postsCache.clear()
        seenReactionIds.clear()
        myLikedPosts.clear()
        _posts.value = emptyList()
    }

    // ==================== Follow/Unfollow ====================

    private var lastContactListCreatedAt: Long = 0

    fun followUser(pubkey: String) {
        scope.launch {
            try {
                val currentFollows = _followList.value.toMutableSet()
                currentFollows.add(pubkey)

                if (keyManager.isAmberConnected()) {
                    // Need Amber to sign the contact list event
                    val unsignedEvent = createUnsignedContactListEvent(currentFollows)
                    val intent = keyManager.createAmberSignEventIntent(
                        eventJson = unsignedEvent,
                        eventId = "follow_$pubkey"
                    )
                    pendingFollowPubkey = pubkey
                    pendingFollowAction = FollowAction.FOLLOW
                    pendingFollowUnsignedEvent = unsignedEvent
                    _pendingFollowIntent.value = intent
                } else {
                    // Sign locally with stored keys
                    val keys = keyManager.getKeys() ?: return@launch
                    publishContactList(keys, currentFollows)
                    _followList.value = currentFollows
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun unfollowUser(pubkey: String) {
        scope.launch {
            try {
                val currentFollows = _followList.value.toMutableSet()
                currentFollows.remove(pubkey)

                if (keyManager.isAmberConnected()) {
                    // Need Amber to sign the contact list event
                    val unsignedEvent = createUnsignedContactListEvent(currentFollows)
                    val intent = keyManager.createAmberSignEventIntent(
                        eventJson = unsignedEvent,
                        eventId = "unfollow_$pubkey"
                    )
                    pendingFollowPubkey = pubkey
                    pendingFollowAction = FollowAction.UNFOLLOW
                    pendingFollowUnsignedEvent = unsignedEvent
                    _pendingFollowIntent.value = intent
                } else {
                    // Sign locally with stored keys
                    val keys = keyManager.getKeys() ?: return@launch
                    publishContactList(keys, currentFollows)
                    _followList.value = currentFollows
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    private fun publishContactList(keys: rust.nostr.protocol.Keys, follows: Set<String>) {
        val tags = follows.mapNotNull { pubkey ->
            try {
                Tag.parse(listOf("p", pubkey))
            } catch (e: Exception) {
                null
            }
        }

        val event = EventBuilder(Kind(3u), "", tags)
            .toEvent(keys)

        nostrClient.publish(event)
        lastContactListCreatedAt = System.currentTimeMillis() / 1000
    }

    fun isFollowing(pubkey: String): Boolean {
        return _followList.value.contains(pubkey)
    }

    /**
     * Create an unsigned kind 3 (contact list) event for Amber signing
     */
    private fun createUnsignedContactListEvent(follows: Set<String>): String {
        val pubkey = keyManager.getPublicKeyHex() ?: ""
        val createdAt = System.currentTimeMillis() / 1000

        val tags = JSONArray()
        follows.forEach { followPubkey ->
            tags.put(JSONArray().apply {
                put("p")
                put(followPubkey)
            })
        }

        return JSONObject().apply {
            put("kind", 3)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("tags", tags)
            put("content", "")
        }.toString()
    }

    /**
     * Handle the signed contact list event returned from Amber.
     * Amber may return either:
     * - Full signed event JSON (with "id" and "sig" fields)
     * - Just the signature (hex string)
     */
    fun handleAmberSignedContactList(signedEventJson: String) {
        scope.launch {
            try {
                val event = try {
                    // First, try to parse as a full signed event
                    Event.fromJson(signedEventJson)
                } catch (e: Exception) {
                    // If that fails, it might be just a signature - reconstruct the event
                    reconstructSignedFollowEvent(signedEventJson)
                }

                if (event != null) {
                    nostrClient.publish(event)

                    // Update follow list based on pending action
                    pendingFollowPubkey?.let { pubkey ->
                        val currentFollows = _followList.value.toMutableSet()
                        when (pendingFollowAction) {
                            FollowAction.FOLLOW -> currentFollows.add(pubkey)
                            FollowAction.UNFOLLOW -> currentFollows.remove(pubkey)
                            null -> {}
                        }
                        _followList.value = currentFollows
                    }
                }
            } catch (e: Exception) {
                // Handle error - event parsing or publishing failed
            } finally {
                clearPendingFollowIntent()
            }
        }
    }

    /**
     * Reconstruct a signed follow event from a signature and the stored unsigned event.
     */
    private fun reconstructSignedFollowEvent(signature: String): Event? {
        val unsignedJson = pendingFollowUnsignedEvent ?: return null

        return try {
            // Parse the unsigned event
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
     * Clear pending follow intent after it's been handled or cancelled
     */
    fun clearPendingFollowIntent() {
        _pendingFollowIntent.value = null
        pendingFollowPubkey = null
        pendingFollowAction = null
        pendingFollowUnsignedEvent = null
    }

    // ==================== Helpers ====================

    private fun formatRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis() / 1000
        val diff = now - timestamp

        return when {
            diff < 60 -> "just now"
            diff < 3600 -> "${diff / 60}m"
            diff < 86400 -> "${diff / 3600}h"
            diff < 604800 -> "${diff / 86400}d"
            else -> "${diff / 604800}w"
        }
    }
}
