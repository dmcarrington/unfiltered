package com.nostr.unfiltered.nostr.models

data class Comment(
    val id: String,
    val postId: String,
    val authorPubkey: String,
    val content: String,
    val createdAt: Long,
    val authorName: String? = null,
    val authorAvatar: String? = null,
    val authorNip05: String? = null,
    val relativeTime: String = ""
)
