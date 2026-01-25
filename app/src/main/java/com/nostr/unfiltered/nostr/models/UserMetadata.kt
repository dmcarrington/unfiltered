package com.nostr.unfiltered.nostr.models

import org.json.JSONObject

/**
 * Represents user profile metadata (kind 0 event)
 */
data class UserMetadata(
    val pubkey: String,
    val name: String? = null,
    val displayName: String? = null,
    val about: String? = null,
    val picture: String? = null,
    val banner: String? = null,
    val nip05: String? = null,
    val lud16: String? = null,  // Lightning address
    val lud06: String? = null,  // LNURL
    val website: String? = null,
    val createdAt: Long = 0
) {
    val bestName: String
        get() = displayName ?: name ?: npub.take(16) + "..."

    val npub: String
        get() = try {
            rust.nostr.protocol.PublicKey.fromHex(pubkey).toBech32()
        } catch (e: Exception) {
            pubkey
        }

    /**
     * Convert metadata to JSON string for publishing as kind 0 event content
     */
    fun toJson(): String {
        val obj = JSONObject()
        name?.let { obj.put("name", it) }
        displayName?.let { obj.put("display_name", it) }
        about?.let { obj.put("about", it) }
        picture?.let { obj.put("picture", it) }
        banner?.let { obj.put("banner", it) }
        nip05?.let { obj.put("nip05", it) }
        lud16?.let { obj.put("lud16", it) }
        lud06?.let { obj.put("lud06", it) }
        website?.let { obj.put("website", it) }
        return obj.toString()
    }

    companion object {
        fun fromJson(pubkey: String, json: String, createdAt: Long): UserMetadata {
            return try {
                val obj = JSONObject(json)
                UserMetadata(
                    pubkey = pubkey,
                    name = obj.optString("name").takeIf { it.isNotEmpty() },
                    displayName = obj.optString("display_name").takeIf { it.isNotEmpty() }
                        ?: obj.optString("displayName").takeIf { it.isNotEmpty() },
                    about = obj.optString("about").takeIf { it.isNotEmpty() },
                    picture = obj.optString("picture").takeIf { it.isNotEmpty() },
                    banner = obj.optString("banner").takeIf { it.isNotEmpty() },
                    nip05 = obj.optString("nip05").takeIf { it.isNotEmpty() },
                    lud16 = obj.optString("lud16").takeIf { it.isNotEmpty() },
                    lud06 = obj.optString("lud06").takeIf { it.isNotEmpty() },
                    website = obj.optString("website").takeIf { it.isNotEmpty() },
                    createdAt = createdAt
                )
            } catch (e: Exception) {
                UserMetadata(pubkey = pubkey, createdAt = createdAt)
            }
        }
    }
}
