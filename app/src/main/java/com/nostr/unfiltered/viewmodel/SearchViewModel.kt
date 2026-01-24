package com.nostr.unfiltered.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.SearchService
import com.nostr.unfiltered.nostr.models.UserMetadata
import com.nostr.unfiltered.repository.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rust.nostr.protocol.Filter
import rust.nostr.protocol.Kind
import rust.nostr.protocol.Nip19Profile
import rust.nostr.protocol.PublicKey
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val searchService: SearchService,
    private val feedRepository: FeedRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        observeDirectLookups()
    }

    /**
     * Observe events for direct pubkey lookups (npub/nprofile/hex)
     */
    private fun observeDirectLookups() {
        viewModelScope.launch {
            nostrClient.events.collect { nostrEvent ->
                val event = nostrEvent.event
                val kind = event.kind().asU16().toInt()

                // Only process events from direct lookup subscriptions
                if (kind == 0 && nostrEvent.subscriptionId.startsWith("search_direct_")) {
                    val pubkey = event.author().toHex()
                    val metadata = UserMetadata.fromJson(
                        pubkey = pubkey,
                        json = event.content(),
                        createdAt = event.createdAt().asSecs().toLong()
                    )

                    _uiState.update { state ->
                        // Add to results if not already present
                        val existingPubkeys = state.results.map { it.pubkey }.toSet()
                        if (pubkey !in existingPubkeys) {
                            state.copy(
                                results = state.results + metadata,
                                isSearching = false
                            )
                        } else {
                            state.copy(isSearching = false)
                        }
                    }
                }
            }
        }
    }

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }

        // Cancel previous search
        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            // Debounce
            delay(300)

            _uiState.update { it.copy(isSearching = true, results = emptyList()) }

            // Check if query is an npub or nprofile
            val directPubkey = tryParseNostrIdentifier(query)
            if (directPubkey != null) {
                // Direct lookup via Nostr relay
                fetchUserMetadata(directPubkey)
                return@launch
            }

            // Search by name using nostr.band HTTP API
            searchByName(query)
        }
    }

    private fun tryParseNostrIdentifier(query: String): String? {
        val trimmed = query.trim()

        // Try npub
        if (trimmed.startsWith("npub1")) {
            return try {
                PublicKey.fromBech32(trimmed).toHex()
            } catch (e: Exception) {
                null
            }
        }

        // Try nprofile
        if (trimmed.startsWith("nprofile1")) {
            return try {
                Nip19Profile.fromBech32(trimmed).publicKey().toHex()
            } catch (e: Exception) {
                null
            }
        }

        // Try hex pubkey
        if (trimmed.length == 64 && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return try {
                PublicKey.fromHex(trimmed).toHex()
            } catch (e: Exception) {
                null
            }
        }

        return null
    }

    private fun fetchUserMetadata(pubkey: String) {
        try {
            val pk = PublicKey.fromHex(pubkey)
            val filter = Filter()
                .kind(Kind(0u))
                .author(pk)
                .limit(1u)

            nostrClient.subscribe("search_direct_$pubkey", listOf(filter))

            // Timeout for direct lookup
            viewModelScope.launch {
                delay(3000)
                _uiState.update { it.copy(isSearching = false) }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isSearching = false) }
        }
    }

    private suspend fun searchByName(query: String) {
        try {
            val results = searchService.searchUsers(query)
            _uiState.update {
                it.copy(
                    results = results,
                    isSearching = false
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    results = emptyList(),
                    isSearching = false
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up any direct lookup subscriptions
        _uiState.value.results.forEach { user ->
            nostrClient.unsubscribe("search_direct_${user.pubkey}")
        }
    }
}

data class SearchUiState(
    val searchQuery: String = "",
    val results: List<UserMetadata> = emptyList(),
    val isSearching: Boolean = false
)
