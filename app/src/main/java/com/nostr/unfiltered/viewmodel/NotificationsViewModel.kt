package com.nostr.unfiltered.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.MetadataCache
import com.nostr.unfiltered.nostr.NotificationService
import com.nostr.unfiltered.nostr.SearchService
import com.nostr.unfiltered.nostr.models.Notification
import com.nostr.unfiltered.repository.FeedRepository
import com.nostr.unfiltered.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val notificationService: NotificationService,
    private val notificationRepository: NotificationRepository,
    private val feedRepository: FeedRepository,
    private val metadataCache: MetadataCache,
    private val searchService: SearchService
) : ViewModel() {

    val hasUnread: StateFlow<Boolean> = notificationService.hasUnread

    // Enrich notifications with latest metadata and split by follow status
    private val enrichedNotifications = combine(
        notificationService.notifications,
        feedRepository.followList
    ) { notifications, followSet ->
        // Fetch metadata for any pubkeys not in cache
        val missingPubkeys = notifications
            .map { it.actorPubkey }
            .distinct()
            .filter { !metadataCache.contains(it) }
        if (missingPubkeys.isNotEmpty()) {
            try {
                searchService.fetchMetadataForPubkeys(missingPubkeys)
            } catch (_: Exception) { }
        }

        // Enrich each notification with latest metadata
        val enriched = notifications.map { notification ->
            val metadata = metadataCache.get(notification.actorPubkey)
            if (metadata != null) {
                notification.copy(
                    actorName = metadata.bestName,
                    actorAvatar = metadata.picture ?: notification.actorAvatar
                )
            } else {
                // Fall back to npub format instead of raw hex
                if (notification.actorName == null) {
                    notification.copy(actorName = hexToNpub(notification.actorPubkey))
                } else {
                    notification
                }
            }
        }

        val following = enriched.filter { followSet.contains(it.actorPubkey) }
        val others = enriched.filter { !followSet.contains(it.actorPubkey) }

        NotificationsUiState(
            followingNotifications = following,
            otherNotifications = others
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        NotificationsUiState()
    )

    val uiState: StateFlow<NotificationsUiState> = enrichedNotifications

    init {
        try {
            notificationRepository.initialize()
        } catch (e: Exception) {
            android.util.Log.e("NotificationsVM", "Failed to initialize notifications", e)
        }
    }

    fun markAsRead() {
        viewModelScope.launch {
            notificationService.markAllAsRead()
        }
    }

    fun getPostImageUrl(postId: String): String? {
        return feedRepository.getPostImageUrl(postId)
    }

    private fun hexToNpub(hex: String): String {
        return try {
            rust.nostr.protocol.PublicKey.fromHex(hex).toBech32().take(16) + "..."
        } catch (_: Exception) {
            hex.take(12) + "..."
        }
    }
}

data class NotificationsUiState(
    val followingNotifications: List<Notification> = emptyList(),
    val otherNotifications: List<Notification> = emptyList()
)
