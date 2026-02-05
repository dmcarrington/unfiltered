package com.nostr.unfiltered.nostr.models

import kotlinx.serialization.Serializable

@Serializable
data class Notification(
    val id: String,                    // Event ID of the notification event
    val type: NotificationType,
    val timestamp: Long,
    val actorPubkey: String,           // Who performed the action
    val actorName: String? = null,
    val actorAvatar: String? = null,
    val targetPostId: String,          // The post being reacted to/zapped/mentioned
    val targetPostImageUrl: String? = null,
    val targetPostContent: String? = null,  // For mentions - the post text
    val zapAmount: Long? = null,       // For zaps only
    val isRead: Boolean = false
)

@Serializable
enum class NotificationType {
    REACTION,
    ZAP,
    MENTION
}
