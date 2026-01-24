package com.nostr.unfiltered.nostr.models

/**
 * Represents a user's contact/follow list (kind 3 event)
 */
data class ContactList(
    val pubkey: String,
    val follows: Set<String>,  // Set of pubkeys being followed
    val createdAt: Long
) {
    companion object {
        fun fromTags(pubkey: String, tags: List<List<String>>, createdAt: Long): ContactList {
            val follows = tags
                .filter { it.size >= 2 && it[0] == "p" }
                .map { it[1] }
                .toSet()

            return ContactList(
                pubkey = pubkey,
                follows = follows,
                createdAt = createdAt
            )
        }
    }
}
