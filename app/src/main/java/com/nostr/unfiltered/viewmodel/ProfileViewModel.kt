package com.nostr.unfiltered.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.SearchService
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
    private val feedRepository: FeedRepository,
    private val searchService: SearchService
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
            // Observe follow list for isFollowing status and count
            feedRepository.followList.collect { follows ->
                _uiState.update { state ->
                    state.copy(
                        isFollowing = follows.contains(pubkey),
                        followingCount = if (state.isOwnProfile) follows.size else state.followingCount
                    )
                }
            }
        }

        // Observe pending Amber follow intent
        viewModelScope.launch {
            feedRepository.pendingFollowIntent.collect { intent ->
                _uiState.update { it.copy(pendingFollowIntent = intent) }
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
                            _uiState.update { state ->
                                // Update existing posts with the new metadata
                                val updatedPosts = state.posts.map { post ->
                                    post.copy(
                                        authorName = metadata.bestName,
                                        authorAvatar = metadata.picture,
                                        authorNip05 = metadata.nip05,
                                        authorLud16 = metadata.lud16
                                    )
                                }
                                state.copy(metadata = metadata, posts = updatedPosts, isLoading = false)
                            }
                        }
                        1, 20 -> {
                            // Kind 1 (note) or Kind 20 (picture post)
                            // Try to parse as photo post - will extract image URL if present
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
            _uiState.update { state ->
                // Update existing posts with the cached metadata
                val updatedPosts = state.posts.map { post ->
                    post.copy(
                        authorName = metadata.bestName,
                        authorAvatar = metadata.picture,
                        authorNip05 = metadata.nip05,
                        authorLud16 = metadata.lud16
                    )
                }
                state.copy(metadata = metadata, posts = updatedPosts, isLoading = false)
            }
        }

        // Load cached posts by this author
        val cachedPosts = feedRepository.getPostsByAuthor(pubkey)
        if (cachedPosts.isNotEmpty()) {
            _uiState.update { state ->
                // Enrich cached posts with metadata if available
                val enrichedPosts = state.metadata?.let { metadata ->
                    cachedPosts.map { post ->
                        post.copy(
                            authorName = metadata.bestName,
                            authorAvatar = metadata.picture,
                            authorNip05 = metadata.nip05,
                            authorLud16 = metadata.lud16
                        )
                    }
                } ?: cachedPosts
                state.copy(posts = enrichedPosts)
            }
        }

        // Directly fetch posts from relays (most reliable method)
        viewModelScope.launch {
            try {
                val fetchedPosts = searchService.fetchUserPosts(pubkey)
                if (fetchedPosts.isNotEmpty()) {
                    _uiState.update { state ->
                        val existingIds = state.posts.map { it.id }.toSet()
                        val newPosts = fetchedPosts.filter { it.id !in existingIds }
                        // Enrich posts with metadata if available
                        val enrichedPosts = newPosts.map { post ->
                            state.metadata?.let { metadata ->
                                post.copy(
                                    authorName = metadata.bestName,
                                    authorAvatar = metadata.picture,
                                    authorNip05 = metadata.nip05,
                                    authorLud16 = metadata.lud16
                                )
                            } ?: post
                        }
                        state.copy(
                            posts = (state.posts + enrichedPosts).sortedByDescending { it.createdAt },
                            isLoading = false
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }

        // Also observe FeedRepository's posts flow for this author
        // This catches posts added by any subscription
        viewModelScope.launch {
            feedRepository.posts.collect { allPosts ->
                val authorPosts = allPosts.filter { it.authorPubkey == pubkey }
                if (authorPosts.isNotEmpty()) {
                    _uiState.update { state ->
                        // Merge with existing posts, avoiding duplicates
                        val existingIds = state.posts.map { it.id }.toSet()
                        val newPosts = authorPosts.filter { it.id !in existingIds }
                        if (newPosts.isNotEmpty()) {
                            state.copy(
                                posts = (state.posts + newPosts).sortedByDescending { it.createdAt }
                            )
                        } else {
                            state
                        }
                    }
                }
            }
        }
    }

    private fun subscribeToUserPosts(pubkey: String) {
        try {
            val pk = PublicKey.fromHex(pubkey)

            // Subscribe to user's kind 20 picture posts
            val pictureFilter = Filter()
                .kind(Kind(20u))
                .author(pk)
                .limit(50u)

            nostrClient.subscribe("profile_posts_$pubkey", listOf(pictureFilter))

            // Also subscribe to kind 1 notes (may contain images)
            val notesFilter = Filter()
                .kind(Kind(1u))
                .author(pk)
                .limit(50u)

            nostrClient.subscribe("profile_notes_$pubkey", listOf(notesFilter))

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
                feedRepository.unfollowUser(pubkey)
            } else {
                feedRepository.followUser(pubkey)
            }
        }
    }

    /**
     * Handle the signed contact list event returned from Amber
     */
    fun handleAmberSignedFollow(signedEventJson: String) {
        feedRepository.handleAmberSignedContactList(signedEventJson)
    }

    /**
     * Clear pending follow intent after it's been handled or cancelled
     */
    fun clearPendingFollowIntent() {
        feedRepository.clearPendingFollowIntent()
    }

    override fun onCleared() {
        super.onCleared()
        currentPubkey?.let { pubkey ->
            nostrClient.unsubscribe("profile_posts_$pubkey")
            nostrClient.unsubscribe("profile_notes_$pubkey")
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
    val error: String? = null,
    val followingCount: Int = 0,
    val pendingFollowIntent: Intent? = null
)
