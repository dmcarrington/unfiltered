package com.nostr.unfiltered.nostr

import com.nostr.unfiltered.nostr.models.UserMetadata
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared cache for user metadata across different services.
 */
@Singleton
class MetadataCache @Inject constructor() {

    private val cache = ConcurrentHashMap<String, UserMetadata>()

    /**
     * Store metadata in cache, only if newer than existing
     */
    fun put(metadata: UserMetadata) {
        val existing = cache[metadata.pubkey]
        if (existing == null || metadata.createdAt > existing.createdAt) {
            cache[metadata.pubkey] = metadata
        }
    }

    /**
     * Get metadata from cache
     */
    fun get(pubkey: String): UserMetadata? {
        return cache[pubkey]
    }

    /**
     * Check if metadata exists in cache
     */
    fun contains(pubkey: String): Boolean {
        return cache.containsKey(pubkey)
    }

    /**
     * Clear all cached metadata
     */
    fun clear() {
        cache.clear()
    }
}
