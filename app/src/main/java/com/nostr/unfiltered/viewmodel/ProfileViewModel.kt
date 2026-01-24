package com.nostr.unfiltered.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.models.PhotoPost
import com.nostr.unfiltered.nostr.models.UserMetadata
import com.nostr.unfiltered.repository.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rust.nostr.protocol.Filter
import rust.nostr.protocol.Kind
import rust.nostr.protocol.PublicKey
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val keyManager: KeyManager,
    private val feedRepository: FeedRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private var currentPubkey: String? = null

    fun loadProfile(pubkey: String) {
        if (currentPubkey == pubkey) return
        currentPubkey = pubkey

        _uiState.update {
            it.copy(
                isLoading = true,
                npub = try {
                    PublicKey.fromHex(pubkey).toBech32()
                } catch (e: Exception) {
                    pubkey
                },
                isOwnProfile = pubkey == keyManager.getPublicKeyHex()
            )
        }

        viewModelScope.launch {
            // Check if already following
            feedRepository.followList.collect { follows ->
                _uiState.update { it.copy(isFollowing = follows.contains(pubkey)) }
            }
        }

        // Subscribe to this user's metadata
        feedRepository.fetchUserMetadata(pubkey)

        // Subscribe to this user's posts
        subscribeToUserPosts(pubkey)

        // Observe events for this user
        viewModelScope.launch {
            nostrClient.events.collect { nostrEvent ->
                val event = nostrEvent.event
                val authorPubkey = event.author().toHex()

                if (authorPubkey == pubkey) {
                    when (event.kind().asU16().toInt()) {
                        0 -> {
                            // Metadata
                            val metadata = UserMetadata.fromJson(
                                pubkey = authorPubkey,
                                json = event.content(),
                                createdAt = event.createdAt().asSecs().toLong()
                            )
                            _uiState.update { it.copy(metadata = metadata, isLoading = false) }
                        }
                        20 -> {
                            // Photo post
                            val post = feedRepository.parsePhotoPost(event)
                            if (post != null) {
                                _uiState.update { state ->
                                    val existingIds = state.posts.map { it.id }.toSet()
                                    if (post.id !in existingIds) {
                                        state.copy(
                                            posts = (state.posts + post).sortedByDescending { it.createdAt }
                                        )
                                    } else {
                                        state
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Check cached metadata
        feedRepository.getUserMetadata(pubkey)?.let { metadata ->
            _uiState.update { it.copy(metadata = metadata, isLoading = false) }
        }
    }

    private fun subscribeToUserPosts(pubkey: String) {
        try {
            val pk = PublicKey.fromHex(pubkey)

            // Subscribe to user's kind 20 posts
            val filter = Filter()
                .kind(Kind(20u))
                .author(pk)
                .limit(50u)

            nostrClient.subscribe("profile_posts_$pubkey", listOf(filter))

            // Also get their metadata
            val metadataFilter = Filter()
                .kind(Kind(0u))
                .author(pk)
                .limit(1u)

            nostrClient.subscribe("profile_metadata_$pubkey", listOf(metadataFilter))

            _uiState.update { it.copy(isLoading = false) }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    fun toggleFollow() {
        val pubkey = currentPubkey ?: return

        viewModelScope.launch {
            if (_uiState.value.isFollowing) {
                // Unfollow - would need to implement in repository
                _uiState.update { it.copy(isFollowing = false) }
            } else {
                // Follow - would need to implement in repository
                _uiState.update { it.copy(isFollowing = true) }
            }
            // TODO: Publish updated contact list (kind 3)
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentPubkey?.let { pubkey ->
            nostrClient.unsubscribe("profile_posts_$pubkey")
            nostrClient.unsubscribe("profile_metadata_$pubkey")
        }
    }
}

data class ProfileUiState(
    val isLoading: Boolean = true,
    val npub: String = "",
    val metadata: UserMetadata? = null,
    val posts: List<PhotoPost> = emptyList(),
    val isFollowing: Boolean = false,
    val isOwnProfile: Boolean = false,
    val error: String? = null
)
