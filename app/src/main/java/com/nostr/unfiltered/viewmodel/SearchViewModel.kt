package com.nostr.unfiltered.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.MetadataCache
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.models.UserMetadata
import com.nostr.unfiltered.repository.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import rust.nostr.protocol.Filter
import rust.nostr.protocol.Kind
import rust.nostr.protocol.Nip19Profile
import rust.nostr.protocol.PublicKey
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val metadataCache: MetadataCache,
    private val feedRepository: FeedRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    companion object {
        private const val SEARCH_NAME_SUB = "search_name"
        private const val SEARCH_DIRECT_PREFIX = "search_direct_"
        private const val SEARCH_RELAY = "wss://relay.nostr.band"
    }

    init {
        observeSearchResults()
    }

    /**
     * Observe events for both direct pubkey lookups and name searches
     */
    private fun observeSearchResults() {
        viewModelScope.launch {
            nostrClient.events.collect { nostrEvent ->
                val event = nostrEvent.event
                val kind = event.kind().asU16().toInt()
                if (kind != 0) return@collect

                val subId = nostrEvent.subscriptionId
                if (!subId.startsWith(SEARCH_DIRECT_PREFIX) && subId != SEARCH_NAME_SUB) return@collect

                val pubkey = event.author().toHex()
                val metadata = UserMetadata.fromJson(
                    pubkey = pubkey,
                    json = event.content(),
                    createdAt = event.createdAt().asSecs().toLong()
                )
                metadataCache.put(metadata)

                _uiState.update { state ->
                    val existingPubkeys = state.results.map { it.pubkey }.toSet()
                    if (pubkey !in existingPubkeys) {
                        state.copy(results = state.results + metadata)
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }

        // Cancel previous search
        searchJob?.cancel()
        nostrClient.unsubscribe(SEARCH_NAME_SUB)

        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            // Debounce
            delay(300)

            _uiState.update { it.copy(isSearching = true, results = emptyList()) }

            // Ensure we're connected
            nostrClient.reconnect()

            // Check if query is an npub or nprofile
            val directPubkey = tryParseNostrIdentifier(query)
            if (directPubkey != null) {
                // Direct lookup via Nostr relay
                fetchUserMetadata(directPubkey)
                return@launch
            }

            // Search by name using NIP-50 via existing NostrClient connection
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

            nostrClient.subscribe("$SEARCH_DIRECT_PREFIX$pubkey", listOf(filter))

            // Timeout for direct lookup
            viewModelScope.launch {
                delay(3000)
                _uiState.update { it.copy(isSearching = false) }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isSearching = false) }
        }
    }

    private fun searchByName(query: String) {
        val filterJson = JSONObject().apply {
            put("kinds", JSONArray().put(0))
            put("search", query)
            put("limit", 30)
        }

        nostrClient.subscribeRaw(SEARCH_NAME_SUB, filterJson, listOf(SEARCH_RELAY))

        // Wait for EOSE or timeout, then finalize results
        viewModelScope.launch {
            withTimeoutOrNull(5000) {
                nostrClient.eoseEvents.first { it == SEARCH_NAME_SUB }
            }
            nostrClient.unsubscribe(SEARCH_NAME_SUB)
            _uiState.update { state ->
                state.copy(
                    results = state.results.sortedBy { it.bestName?.lowercase() ?: "zzz" },
                    isSearching = false
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        nostrClient.unsubscribe(SEARCH_NAME_SUB)
        _uiState.value.results.forEach { user ->
            nostrClient.unsubscribe("$SEARCH_DIRECT_PREFIX${user.pubkey}")
        }
    }
}

data class SearchUiState(
    val searchQuery: String = "",
    val results: List<UserMetadata> = emptyList(),
    val isSearching: Boolean = false
)
