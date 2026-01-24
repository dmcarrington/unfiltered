package com.nostr.unfiltered.repository

import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.NostrEvent
import com.nostr.unfiltered.nostr.models.ContactList
import com.nostr.unfiltered.nostr.models.ImageDimensions
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
    private val keyManager: KeyManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Caches
    private val userMetadataCache = ConcurrentHashMap<String, UserMetadata>()
    private val postsCache = ConcurrentHashMap<String, PhotoPost>()

    private val _posts = MutableStateFlow<List<PhotoPost>>(emptyList())
    val posts: StateFlow<List<PhotoPost>> = _posts.asStateFlow()

    private val _followList = MutableStateFlow<Set<String>>(emptySet())
    val followList: StateFlow<Set<String>> = _followList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
            3 -> handleContactListEvent(event)
            7 -> handleReactionEvent(event)
            20 -> handlePhotoPostEvent(event)
        }
    }

    private fun handleMetadataEvent(event: Event) {
        val pubkey = event.author().toHex()
        val existing = userMetadataCache[pubkey]
        val eventCreatedAt = event.createdAt().asSecs().toLong()

        // Only update if newer
        if (existing == null || eventCreatedAt > existing.createdAt) {
            val metadata = UserMetadata.fromJson(
                pubkey = pubkey,
                json = event.content(),
                createdAt = eventCreatedAt
            )
            userMetadataCache[pubkey] = metadata

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
        val tags = parseTagsFromEvent(event)
        val targetEventId = tags.find { it.size >= 2 && it[0] == "e" }?.get(1) ?: return

        // Update like status on the target post
        postsCache[targetEventId]?.let { post ->
            val updatedPost = post.copy(likeCount = post.likeCount + 1)
            postsCache[targetEventId] = updatedPost
            refreshPostsList()
        }
    }

    private fun handlePhotoPostEvent(event: Event) {
        val post = parsePhotoPost(event) ?: return
        postsCache[post.id] = post
        refreshPostsList()
    }

    fun parsePhotoPost(event: Event): PhotoPost? {
        val tags = parseTagsFromEvent(event)

        // Find imeta tag with image URL
        val imetaTag = tags.find { it.size >= 2 && it[0] == "imeta" }
        var imageUrl: String? = null
        var blurhash: String? = null
        var dimensions: ImageDimensions? = null
        var altText: String? = null
        val fallbackUrls = mutableListOf<String>()

        if (imetaTag != null) {
            // Parse imeta tag values
            for (i in 1 until imetaTag.size) {
                val part = imetaTag[i]
                when {
                    part.startsWith("url ") -> imageUrl = part.removePrefix("url ")
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
        }

        // Also check for standalone url tag or image tag
        if (imageUrl == null) {
            imageUrl = tags.find { it.size >= 2 && it[0] == "url" }?.get(1)
        }
        if (imageUrl == null) {
            imageUrl = tags.find { it.size >= 2 && it[0] == "image" }?.get(1)
        }

        // Check content for image URL if still not found
        if (imageUrl == null) {
            val content = event.content()
            val urlRegex = Regex("https?://[^\\s]+\\.(jpg|jpeg|png|gif|webp)", RegexOption.IGNORE_CASE)
            imageUrl = urlRegex.find(content)?.value
        }

        // Must have an image URL
        if (imageUrl == null) return null

        val title = tags.find { it.size >= 2 && it[0] == "title" }?.get(1)
        val authorPubkey = event.author().toHex()
        val metadata = userMetadataCache[authorPubkey]

        return PhotoPost(
            id = event.id().toHex(),
            authorPubkey = authorPubkey,
            createdAt = event.createdAt().asSecs().toLong(),
            imageUrl = imageUrl,
            caption = event.content(),
            title = title,
            blurhash = blurhash,
            dimensions = dimensions,
            altText = altText,
            fallbackUrls = fallbackUrls,
            authorName = metadata?.bestName,
            authorAvatar = metadata?.picture,
            authorNip05 = metadata?.nip05,
            relativeTime = formatRelativeTime(event.createdAt().asSecs().toLong())
        )
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
                authorNip05 = metadata.nip05
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

        // First, load our follow list
        val myPubkey = keyManager.getPublicKeyHex()
        if (myPubkey != null) {
            val followListFilter = Filter()
                .kind(Kind(3u))
                .author(PublicKey.fromHex(myPubkey))
                .limit(1u)

            nostrClient.subscribe("my_follows", listOf(followListFilter))
        }

        // Subscribe to global picture posts (kind 20) for now
        // Once we have follows, we can filter by author
        val feedFilter = Filter()
            .kind(Kind(20u))
            .limit(50u)

        nostrClient.subscribe("feed", listOf(feedFilter))

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

        // Subscribe to posts from followed users
        val authors = follows.mapNotNull { pubkey ->
            try {
                PublicKey.fromHex(pubkey)
            } catch (e: Exception) {
                null
            }
        }

        if (authors.isNotEmpty()) {
            val feedFilter = Filter()
                .kind(Kind(20u))
                .authors(authors)
                .limit(100u)

            nostrClient.subscribe("feed_follows", listOf(feedFilter))

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
        return userMetadataCache[pubkey]
    }

    fun getPostsByAuthor(pubkey: String): List<PhotoPost> {
        return postsCache.values
            .filter { it.authorPubkey == pubkey }
            .sortedByDescending { it.createdAt }
    }

    // ==================== Actions ====================

    fun likePost(post: PhotoPost) {
        val keys = keyManager.getKeys() ?: return

        scope.launch {
            try {
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
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun clearCache() {
        postsCache.clear()
        _posts.value = emptyList()
    }

    // ==================== Follow/Unfollow ====================

    private var lastContactListCreatedAt: Long = 0

    fun followUser(pubkey: String) {
        val keys = keyManager.getKeys() ?: return

        scope.launch {
            try {
                val currentFollows = _followList.value.toMutableSet()
                currentFollows.add(pubkey)

                publishContactList(keys, currentFollows)

                // Optimistically update
                _followList.value = currentFollows
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun unfollowUser(pubkey: String) {
        val keys = keyManager.getKeys() ?: return

        scope.launch {
            try {
                val currentFollows = _followList.value.toMutableSet()
                currentFollows.remove(pubkey)

                publishContactList(keys, currentFollows)

                // Optimistically update
                _followList.value = currentFollows
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
