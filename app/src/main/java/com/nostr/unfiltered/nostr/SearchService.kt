package com.nostr.unfiltered.nostr

import com.nostr.unfiltered.nostr.models.ImageDimensions
import com.nostr.unfiltered.nostr.models.PhotoPost
import com.nostr.unfiltered.nostr.models.UserMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import rust.nostr.protocol.Event
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search service using NIP-50 WebSocket search to relay.nostr.band
 * for reliable user search.
 */
@Singleton
class SearchService @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val searchRelays = listOf(
        "wss://relay.nostr.band",
        "wss://search.nos.today"
    )

    /**
     * Search for users by name using NIP-50 search filter via WebSocket
     */
    suspend fun searchUsers(query: String): List<UserMetadata> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, UserMetadata>()

        // Try each search relay
        for (relayUrl in searchRelays) {
            try {
                val relayResults = searchViaRelay(relayUrl, query)
                relayResults.forEach { metadata ->
                    // Keep the first result for each pubkey (or update if better)
                    if (!results.containsKey(metadata.pubkey)) {
                        results[metadata.pubkey] = metadata
                    }
                }

                // If we got results, no need to try more relays
                if (results.isNotEmpty()) break
            } catch (e: Exception) {
                // Try next relay
                continue
            }
        }

        results.values.toList().sortedBy { it.bestName?.lowercase() ?: "zzz" }
    }

    private suspend fun searchViaRelay(relayUrl: String, query: String): List<UserMetadata> {
        val results = mutableListOf<UserMetadata>()
        val completed = CompletableDeferred<Unit>()
        val isConnected = AtomicBoolean(false)

        val subscriptionId = "search_${System.currentTimeMillis()}"

        // Build NIP-50 search filter
        val filterJson = JSONObject().apply {
            put("kinds", JSONArray().put(0))
            put("search", query)
            put("limit", 30)
        }

        val reqMessage = JSONArray().apply {
            put("REQ")
            put(subscriptionId)
            put(filterJson)
        }.toString()

        val request = Request.Builder()
            .url(relayUrl)
            .build()

        var webSocket: WebSocket? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected.set(true)
                ws.send(reqMessage)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONArray(text)
                    when (json.getString(0)) {
                        "EVENT" -> {
                            val eventJson = json.getJSONObject(2).toString()
                            val event = Event.fromJson(eventJson)
                            val pubkey = event.author().toHex()
                            val metadata = UserMetadata.fromJson(
                                pubkey = pubkey,
                                json = event.content(),
                                createdAt = event.createdAt().asSecs().toLong()
                            )
                            synchronized(results) {
                                results.add(metadata)
                            }
                        }
                        "EOSE" -> {
                            // End of stored events - search complete
                            completed.complete(Unit)
                        }
                        "CLOSED" -> {
                            // Subscription closed
                            completed.complete(Unit)
                        }
                    }
                } catch (e: Exception) {
                    // Parse error, ignore
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                completed.complete(Unit)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                completed.complete(Unit)
            }
        }

        webSocket = client.newWebSocket(request, listener)

        // Wait for results with timeout
        withTimeoutOrNull(5000) {
            completed.await()
        }

        // Close subscription and connection
        try {
            val closeMessage = JSONArray().apply {
                put("CLOSE")
                put(subscriptionId)
            }.toString()
            webSocket.send(closeMessage)
            webSocket.close(1000, "Search complete")
        } catch (e: Exception) {
            // Ignore close errors
        }

        return results
    }

    /**
     * Fetch posts by a specific user (kind 1 and kind 20)
     */
    suspend fun fetchUserPosts(pubkey: String): List<PhotoPost> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, PhotoPost>()

        // Try multiple relays for better coverage
        val relays = listOf(
            "wss://relay.nostr.band",
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://nos.lol"
        )

        for (relayUrl in relays) {
            try {
                val relayResults = fetchPostsFromRelay(relayUrl, pubkey)
                relayResults.forEach { post ->
                    if (!results.containsKey(post.id)) {
                        results[post.id] = post
                    }
                }

                // If we got enough results, stop
                if (results.size >= 20) break
            } catch (e: Exception) {
                continue
            }
        }

        results.values.toList().sortedByDescending { it.createdAt }
    }

    private suspend fun fetchPostsFromRelay(relayUrl: String, pubkey: String): List<PhotoPost> {
        val results = mutableListOf<PhotoPost>()
        val completed = CompletableDeferred<Unit>()

        val subscriptionId = "posts_${System.currentTimeMillis()}"

        // Request both kind 1 (notes) and kind 20 (pictures)
        val filterJson = JSONObject().apply {
            put("kinds", JSONArray().put(1).put(20))
            put("authors", JSONArray().put(pubkey))
            put("limit", 50)
        }

        val reqMessage = JSONArray().apply {
            put("REQ")
            put(subscriptionId)
            put(filterJson)
        }.toString()

        val request = Request.Builder()
            .url(relayUrl)
            .build()

        var webSocket: WebSocket? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(reqMessage)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONArray(text)
                    when (json.getString(0)) {
                        "EVENT" -> {
                            val eventJson = json.getJSONObject(2).toString()
                            val event = Event.fromJson(eventJson)
                            val post = parseEventToPost(event)
                            if (post != null) {
                                synchronized(results) {
                                    results.add(post)
                                }
                            }
                        }
                        "EOSE" -> {
                            completed.complete(Unit)
                        }
                        "CLOSED" -> {
                            completed.complete(Unit)
                        }
                    }
                } catch (e: Exception) {
                    // Parse error, ignore
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                completed.complete(Unit)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                completed.complete(Unit)
            }
        }

        webSocket = client.newWebSocket(request, listener)

        withTimeoutOrNull(5000) {
            completed.await()
        }

        try {
            val closeMessage = JSONArray().apply {
                put("CLOSE")
                put(subscriptionId)
            }.toString()
            webSocket.send(closeMessage)
            webSocket.close(1000, "Fetch complete")
        } catch (e: Exception) {
            // Ignore
        }

        return results
    }

    private fun parseEventToPost(event: Event): PhotoPost? {
        val content = event.content()
        val eventJson = JSONObject(event.asJson())
        val tagsArray = eventJson.optJSONArray("tags") ?: JSONArray()

        // Parse tags
        val tags = mutableListOf<List<String>>()
        for (i in 0 until tagsArray.length()) {
            val tagArray = tagsArray.optJSONArray(i) ?: continue
            val tag = mutableListOf<String>()
            for (j in 0 until tagArray.length()) {
                tag.add(tagArray.optString(j, ""))
            }
            tags.add(tag)
        }

        // Find image URL
        var imageUrl: String? = null
        var blurhash: String? = null
        var dimensions: ImageDimensions? = null
        var altText: String? = null

        // Check imeta tag
        val imetaTag = tags.find { it.size >= 2 && it[0] == "imeta" }
        if (imetaTag != null) {
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
                }
            }
        }

        // Check other image tags
        if (imageUrl == null) {
            imageUrl = tags.find { it.size >= 2 && it[0] == "url" }?.get(1)
        }
        if (imageUrl == null) {
            imageUrl = tags.find { it.size >= 2 && it[0] == "image" }?.get(1)
        }

        // Check content for image URL
        if (imageUrl == null) {
            val urlRegex = Regex("https?://[^\\s]+\\.(jpg|jpeg|png|gif|webp)(\\?[^\\s]*)?", RegexOption.IGNORE_CASE)
            imageUrl = urlRegex.find(content)?.value
        }

        // Must have an image URL
        if (imageUrl == null) return null

        val authorPubkey = event.author().toHex()
        val createdAt = event.createdAt().asSecs().toLong()

        return PhotoPost(
            id = event.id().toHex(),
            authorPubkey = authorPubkey,
            createdAt = createdAt,
            imageUrl = imageUrl,
            caption = content,
            title = tags.find { it.size >= 2 && it[0] == "title" }?.get(1),
            blurhash = blurhash,
            dimensions = dimensions,
            altText = altText,
            fallbackUrls = emptyList(),
            authorName = null,
            authorAvatar = null,
            authorNip05 = null,
            relativeTime = formatRelativeTime(createdAt)
        )
    }

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
