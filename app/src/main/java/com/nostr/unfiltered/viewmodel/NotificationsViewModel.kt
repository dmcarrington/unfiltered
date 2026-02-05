package com.nostr.unfiltered.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.NotificationService
import com.nostr.unfiltered.nostr.models.Notification
import com.nostr.unfiltered.repository.FeedRepository
import com.nostr.unfiltered.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val notificationService: NotificationService,
    private val notificationRepository: NotificationRepository,
    private val feedRepository: FeedRepository
) : ViewModel() {

    val notifications: StateFlow<List<Notification>> = notificationService.notifications
    val hasUnread: StateFlow<Boolean> = notificationService.hasUnread

    init {
        // Ensure notification repository is initialized
        try {
            notificationRepository.initialize()
        } catch (e: Exception) {
            // Log but don't crash - notifications are not critical
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
}
