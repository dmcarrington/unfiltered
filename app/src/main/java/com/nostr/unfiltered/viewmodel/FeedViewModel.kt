package com.nostr.unfiltered.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.ZapManager
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

enum class FeedMode {
    FOLLOWING,
    TRENDING
}

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val keyManager: KeyManager,
    private val feedRepository: FeedRepository,
    private val zapManager: ZapManager
) : ViewModel() {

    val connectionState: StateFlow<NostrClient.ConnectionState> = nostrClient.connectionState

    val posts: StateFlow<List<PhotoPost>> = feedRepository.posts

    val isLoading: StateFlow<Boolean> = feedRepository.isLoading

    private val _connectedRelays = MutableStateFlow<List<String>>(emptyList())
    val connectedRelays: StateFlow<List<String>> = _connectedRelays.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _canZap = MutableStateFlow(false)
    val canZap: StateFlow<Boolean> = _canZap.asStateFlow()

    private val _zapState = MutableStateFlow<ZapState>(ZapState.Idle)
    val zapState: StateFlow<ZapState> = _zapState.asStateFlow()

    // Amber like signing flow
    val pendingLikeIntent: StateFlow<Intent?> = feedRepository.pendingLikeIntent

    private val _feedMode = MutableStateFlow(FeedMode.TRENDING)
    val feedMode: StateFlow<FeedMode> = _feedMode.asStateFlow()

    /**
     * Calculate trending score with time decay.
     * Recent posts are boosted; older posts need more likes to rank high.
     * Formula: score = (likeCount + 1) / (1 + hoursOld / 6)
     */
    private fun calculateTrendingScore(post: PhotoPost): Double {
        val now = System.currentTimeMillis() / 1000
        val ageSeconds = (now - post.createdAt).coerceAtLeast(0)
        val ageHours = ageSeconds / 3600.0
        return (post.likeCount + 1).toDouble() / (1.0 + ageHours / 6.0)
    }

    val uiState: StateFlow<FeedUiState> = combine(
        posts,
        connectionState,
        isLoading,
        _isRefreshing,
        _canZap,
        _feedMode,
        feedRepository.followList
    ) { values ->
        val posts = values[0] as List<PhotoPost>
        val connectionState = values[1] as NostrClient.ConnectionState
        val isLoading = values[2] as Boolean
        val isRefreshing = values[3] as Boolean
        val canZap = values[4] as Boolean
        val feedMode = values[5] as FeedMode
        val follows = values[6] as Set<String>

        val sortedPosts = when (feedMode) {
            FeedMode.TRENDING -> posts.sortedByDescending { calculateTrendingScore(it) }
            FeedMode.FOLLOWING -> posts.sortedByDescending { it.createdAt }
        }

        FeedUiState(
            posts = sortedPosts,
            isLoading = isLoading,
            isRefreshing = isRefreshing,
            isConnected = connectionState == NostrClient.ConnectionState.Connected,
            isEmpty = sortedPosts.isEmpty() && !isLoading,
            canZap = canZap,
            feedMode = feedMode,
            hasFollows = follows.isNotEmpty()
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        FeedUiState()
    )

    init {
        observeRelayStatus()
        ensureConnectedAndSubscribe()
        checkZapAvailability()
        setDefaultFeedMode()
    }

    private fun setDefaultFeedMode() {
        viewModelScope.launch {
            // Wait briefly for follow list to load
            kotlinx.coroutines.delay(1000)
            val hasFollows = feedRepository.followList.value.isNotEmpty()
            if (hasFollows && _feedMode.value == FeedMode.TRENDING) {
                setFeedMode(FeedMode.FOLLOWING)
            }
        }
    }

    fun setFeedMode(mode: FeedMode) {
        if (_feedMode.value == mode) return
        _feedMode.value = mode
        viewModelScope.launch {
            feedRepository.clearCache()
            when (mode) {
                FeedMode.FOLLOWING -> feedRepository.subscribeToFollowedFeed()
                FeedMode.TRENDING -> feedRepository.subscribeToFeed()
            }
        }
    }

    private fun checkZapAvailability() {
        viewModelScope.launch {
            // Zaps are available if we have any payment method
            val method = zapManager.getAvailableZapMethod()
            _canZap.value = method != ZapManager.ZapMethod.NotAvailable
        }
    }

    fun refreshZapStatus() {
        checkZapAvailability()
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
            // Reconnect any failed/disconnected relays before refreshing
            nostrClient.reconnect()
            feedRepository.clearCache()
            when (_feedMode.value) {
                FeedMode.FOLLOWING -> feedRepository.subscribeToFollowedFeed()
                FeedMode.TRENDING -> feedRepository.subscribeToFeed()
            }
            _isRefreshing.value = false
        }
    }

    fun likePost(post: PhotoPost) {
        feedRepository.likePost(post)
    }

    fun handleAmberSignedLike(signedEventJson: String) {
        feedRepository.handleAmberSignedLike(signedEventJson)
    }

    fun clearPendingLikeIntent() {
        feedRepository.clearPendingLikeIntent()
    }

    fun loadMorePosts() {
        // Could implement pagination with 'until' filter
        // For now, initial load is sufficient
    }

    fun initiateZap(post: PhotoPost) {
        // Check if user has a Lightning address
        if (post.authorLud16.isNullOrEmpty()) {
            _zapState.value = ZapState.Error("This user doesn't have a Lightning address set up")
            return
        }
        _zapState.value = ZapState.SelectingAmount(post)
    }

    fun cancelZap() {
        _zapState.value = ZapState.Idle
    }

    fun sendZap(post: PhotoPost, amountSats: Long) {
        viewModelScope.launch {
            _zapState.value = ZapState.Processing(post)

            val result = zapManager.initiateZap(
                recipientPubkey = post.authorPubkey,
                recipientLud16 = post.authorLud16,
                amountSats = amountSats,
                eventId = post.id,
                comment = "Zap for your photo!"
            )

            when (result) {
                is ZapManager.ZapResult.Success -> {
                    _zapState.value = ZapState.Success(post, amountSats)
                }
                is ZapManager.ZapResult.OpenLightningWallet -> {
                    _zapState.value = ZapState.OpenWallet(result.intent, post, amountSats)
                }
                is ZapManager.ZapResult.NeedsAmberEncryption -> {
                    // For now, fall back to Lightning wallet since full NWC requires signing too
                    val lightningIntent = zapManager.createLightningIntent(
                        result.pendingZap.invoice ?: ""
                    )
                    if (lightningIntent != null) {
                        _zapState.value = ZapState.OpenWallet(lightningIntent, post, amountSats)
                    } else {
                        _zapState.value = ZapState.Error("No Lightning wallet installed")
                    }
                }
                is ZapManager.ZapResult.NeedsAmberDecryption -> {
                    _zapState.value = ZapState.Error("NWC decryption not yet implemented")
                }
                is ZapManager.ZapResult.Error -> {
                    _zapState.value = ZapState.Error(result.message)
                }
            }
        }
    }

    fun onWalletOpened() {
        // User opened the wallet, assume they might pay
        val currentState = _zapState.value
        if (currentState is ZapState.OpenWallet) {
            _zapState.value = ZapState.WalletOpened(currentState.post, currentState.amountSats)
        }
    }

    fun clearZapState() {
        _zapState.value = ZapState.Idle
    }
}

sealed class ZapState {
    object Idle : ZapState()
    data class SelectingAmount(val post: PhotoPost) : ZapState()
    data class Processing(val post: PhotoPost) : ZapState()
    data class OpenWallet(val intent: Intent, val post: PhotoPost, val amountSats: Long) : ZapState()
    data class WalletOpened(val post: PhotoPost, val amountSats: Long) : ZapState()
    data class Success(val post: PhotoPost, val amountSats: Long) : ZapState()
    data class Error(val message: String) : ZapState()
}

data class FeedUiState(
    val posts: List<PhotoPost> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isConnected: Boolean = false,
    val isEmpty: Boolean = true,
    val error: String? = null,
    val canZap: Boolean = false,
    val feedMode: FeedMode = FeedMode.TRENDING,
    val hasFollows: Boolean = false
)
