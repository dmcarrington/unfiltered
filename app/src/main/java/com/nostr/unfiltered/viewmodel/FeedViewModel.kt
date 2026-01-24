package com.nostr.unfiltered.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.models.PhotoPost
import com.nostr.unfiltered.repository.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val keyManager: KeyManager,
    private val feedRepository: FeedRepository
) : ViewModel() {

    val connectionState: StateFlow<NostrClient.ConnectionState> = nostrClient.connectionState

    val posts: StateFlow<List<PhotoPost>> = feedRepository.posts

    val isLoading: StateFlow<Boolean> = feedRepository.isLoading

    private val _connectedRelays = MutableStateFlow<List<String>>(emptyList())
    val connectedRelays: StateFlow<List<String>> = _connectedRelays.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val uiState: StateFlow<FeedUiState> = combine(
        posts,
        connectionState,
        isLoading,
        _isRefreshing
    ) { posts, connectionState, isLoading, isRefreshing ->
        FeedUiState(
            posts = posts,
            isLoading = isLoading,
            isRefreshing = isRefreshing,
            isConnected = connectionState == NostrClient.ConnectionState.Connected,
            isEmpty = posts.isEmpty() && !isLoading
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        FeedUiState()
    )

    init {
        observeRelayStatus()
        ensureConnectedAndSubscribe()
    }

    private fun observeRelayStatus() {
        viewModelScope.launch {
            nostrClient.relayStatus.collect { statusMap ->
                _connectedRelays.value = statusMap
                    .filter { it.value == NostrClient.RelayStatus.Connected }
                    .keys
                    .toList()
            }
        }
    }

    private fun ensureConnectedAndSubscribe() {
        viewModelScope.launch {
            // Wait for connection
            nostrClient.connectionState.collect { state ->
                if (state == NostrClient.ConnectionState.Connected) {
                    feedRepository.subscribeToFeed()
                    return@collect
                }
            }
        }

        // Also try to connect if disconnected
        viewModelScope.launch {
            if (nostrClient.connectionState.value == NostrClient.ConnectionState.Disconnected) {
                nostrClient.connect()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            feedRepository.clearCache()
            feedRepository.subscribeToFeed()
            _isRefreshing.value = false
        }
    }

    fun likePost(post: PhotoPost) {
        feedRepository.likePost(post)
    }

    fun loadMorePosts() {
        // Could implement pagination with 'until' filter
        // For now, initial load is sufficient
    }
}

data class FeedUiState(
    val posts: List<PhotoPost> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isConnected: Boolean = false,
    val isEmpty: Boolean = true,
    val error: String? = null
)
