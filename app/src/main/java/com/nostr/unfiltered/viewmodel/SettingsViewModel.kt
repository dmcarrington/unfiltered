package com.nostr.unfiltered.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.NwcService
import com.nostr.unfiltered.nostr.SearchService
import com.nostr.unfiltered.nostr.models.PhotoPost
import com.nostr.unfiltered.nostr.models.RelayTopology
import com.nostr.unfiltered.nostr.models.UserMetadata
import com.nostr.unfiltered.repository.FeedRepository
import com.nostr.unfiltered.repository.MuteListRepository
import com.nostr.unfiltered.repository.RelayListRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rust.nostr.protocol.PublicKey
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val nostrClient: NostrClient,
    private val nwcService: NwcService,
    private val feedRepository: FeedRepository,
    private val muteListRepository: MuteListRepository,
    private val searchService: SearchService,
    private val relayListRepository: RelayListRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val relayTopology: StateFlow<RelayTopology> = relayListRepository.topology
    val relayListPublishState: StateFlow<RelayListRepository.PublishState> =
        relayListRepository.publishState
    val relayListHasPublishedOnce: StateFlow<Boolean> =
        relayListRepository.hasPublishedOnce

    init {
        loadSettings()
        observeRelayStatus()
        loadNwcStatus()
        observeFollowList()
        observeMuteList()
        observeRelayTopology()
        loadRelayList()
    }

    /**
     * Load the user's NIP-65 (kind 10002) relay list. Best-effort: if it
     * can't be fetched (no relay response, no kind 10002 on the relay),
     * the topology stays empty and the user can publish the default.
     */
    private fun loadRelayList() {
        viewModelScope.launch {
            relayListRepository.loadCurrent(timeoutMs = 4000L)
            // If no kind 10002 exists yet, publish the default so we have
            // somewhere to write.
            relayListRepository.ensurePublishedDefault()
        }
    }

    private fun observeRelayTopology() {
        viewModelScope.launch {
            relayListRepository.topology.collect { topology ->
                _uiState.update { it.copy(relayTopology = topology) }
            }
        }
        viewModelScope.launch {
            relayListRepository.publishState.collect { state ->
                _uiState.update { it.copy(relayPublishState = state) }
            }
        }
        viewModelScope.launch {
            relayListRepository.hasPublishedOnce.collect { has ->
                _uiState.update { it.copy(relayHasPublishedOnce = has) }
            }
        }
    }

    /**
     * Build a topology from the currently connected relays and publish it
     * as kind 10002. The user can also use [setRelayTopologyReadWrite] /
     * [removeRelayFromTopology] for finer control.
     */
    fun publishRelayListFromConnected() {
        val connected = nostrClient.getConnectedRelays()
        if (connected.isEmpty()) return
        relayListRepository.publish(RelayTopology.fromRelays(connected))
    }

    /**
     * Toggle whether a given relay is in the read set, write set, both,
     * or neither. Publishes the updated topology.
     */
    fun setRelayReadWrite(url: String, read: Boolean, write: Boolean) {
        val norm = RelayTopology.normalise(url)
        val current = relayListRepository.topology.value
        val next = RelayTopology(
            readRelays = if (read) current.readRelays + norm else current.readRelays - norm,
            writeRelays = if (write) current.writeRelays + norm else current.writeRelays - norm
        )
        if (next.isEmpty()) {
            _uiState.update { it.copy(relayPublishState = RelayListRepository.PublishState.Failed("need at least one relay")) }
            return
        }
        relayListRepository.publish(next)
    }

    /**
     * Add a brand-new relay to both read+write and publish.
     */
    fun addRelayToTopology(url: String, read: Boolean = true, write: Boolean = true) {
        val norm = RelayTopology.normalise(url)
        val current = relayListRepository.topology.value
        val next = RelayTopology(
            readRelays = if (read) current.readRelays + norm else current.readRelays,
            writeRelays = if (write) current.writeRelays + norm else current.writeRelays
        )
        if (next.readRelays.isEmpty() && next.writeRelays.isEmpty()) return
        relayListRepository.publish(next)
    }

    fun removeRelayFromTopology(url: String) {
        val norm = RelayTopology.normalise(url)
        val current = relayListRepository.topology.value
        val next = RelayTopology(
            readRelays = current.readRelays - norm,
            writeRelays = current.writeRelays - norm
        )
        if (next.isEmpty()) return
        relayListRepository.publish(next)
    }

    private fun loadNwcStatus() {
        viewModelScope.launch {
            val isConfigured = nwcService.isConfigured()
            _uiState.update { it.copy(isNwcConfigured = isConfigured) }
        }
    }

    private fun loadSettings() {
        val pubkeyHex = keyManager.getPublicKeyHex()
        val npub = pubkeyHex?.let {
            try {
                PublicKey.fromHex(it).toBech32()
            } catch (e: Exception) {
                null
            }
        }

        _uiState.update {
            it.copy(
                pubkeyHex = pubkeyHex,
                npub = npub,
                defaultRelays = nostrClient.defaultRelays
            )
        }
    }

    private fun observeRelayStatus() {
        viewModelScope.launch {
            nostrClient.relayStatus.collect { statusMap ->
                _uiState.update { state ->
                    state.copy(
                        relayStatuses = statusMap.map { (url, status) ->
                            RelayInfo(
                                url = url,
                                isConnected = status == NostrClient.RelayStatus.Connected,
                                status = when (status) {
                                    NostrClient.RelayStatus.Connected -> "Connected"
                                    NostrClient.RelayStatus.Connecting -> "Connecting..."
                                    NostrClient.RelayStatus.Disconnected -> "Disconnected"
                                    is NostrClient.RelayStatus.Error -> "Error: ${status.message}"
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    private fun observeFollowList() {
        viewModelScope.launch {
            feedRepository.followList.collect { follows ->
                _uiState.update { it.copy(followingCount = follows.size, followingUsers = emptyList()) }
            }
        }
    }

    private fun observeMuteList() {
        viewModelScope.launch {
            muteListRepository.muteList.collect { muted ->
                _uiState.update { it.copy(mutedCount = muted.size, mutedUsers = emptyList()) }
            }
        }
    }

    fun selectTab(tab: SettingsTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        when (tab) {
            SettingsTab.FOLLOWING -> loadFollowingUsers()
            SettingsTab.MUTED -> loadMutedUsers()
            SettingsTab.MY_POSTS -> loadMyPosts()
            SettingsTab.SETTINGS -> {}
        }
    }

    private fun loadFollowingUsers() {
        if (_uiState.value.followingUsers.isNotEmpty()) return
        _uiState.update { it.copy(isLoadingFollowList = true) }

        viewModelScope.launch {
            val follows = feedRepository.followList.value.toList()
            val cached = follows.mapNotNull { feedRepository.getUserMetadata(it) }
            if (cached.isNotEmpty()) {
                _uiState.update { it.copy(followingUsers = cached.sortedBy { u -> u.bestName?.lowercase() ?: "zzz" }) }
            }
            val missingPubkeys = follows.filter { feedRepository.getUserMetadata(it) == null }
            if (missingPubkeys.isNotEmpty()) {
                try {
                    val fetched = searchService.fetchMetadataForPubkeys(missingPubkeys)
                    val allUsers = (cached + fetched).distinctBy { it.pubkey }
                    _uiState.update { it.copy(followingUsers = allUsers.sortedBy { u -> u.bestName?.lowercase() ?: "zzz" }) }
                } catch (_: Exception) { }
            }
            _uiState.update { it.copy(isLoadingFollowList = false) }
        }
    }

    private fun loadMutedUsers() {
        if (_uiState.value.mutedUsers.isNotEmpty()) return
        _uiState.update { it.copy(isLoadingMuteList = true) }

        viewModelScope.launch {
            val muted = muteListRepository.muteList.value.toList()
            val cached = muted.mapNotNull { feedRepository.getUserMetadata(it) }
            if (cached.isNotEmpty()) {
                _uiState.update { it.copy(mutedUsers = cached.sortedBy { u -> u.bestName?.lowercase() ?: "zzz" }) }
            }
            val missingPubkeys = muted.filter { feedRepository.getUserMetadata(it) == null }
            if (missingPubkeys.isNotEmpty()) {
                try {
                    val fetched = searchService.fetchMetadataForPubkeys(missingPubkeys)
                    val allUsers = (cached + fetched).distinctBy { it.pubkey }
                    _uiState.update { it.copy(mutedUsers = allUsers.sortedBy { u -> u.bestName?.lowercase() ?: "zzz" }) }
                } catch (_: Exception) { }
            }
            _uiState.update { it.copy(isLoadingMuteList = false) }
        }
    }

    private fun loadMyPosts() {
        if (_uiState.value.myPosts.isNotEmpty()) return
        val pubkey = keyManager.getPublicKeyHex() ?: return
        _uiState.update { it.copy(isLoadingMyPosts = true) }

        viewModelScope.launch {
            // Load cached posts first
            val cachedPosts = feedRepository.getPostsByAuthor(pubkey)
            if (cachedPosts.isNotEmpty()) {
                _uiState.update { it.copy(myPosts = deduplicatePosts(cachedPosts)) }
            }

            // Fetch from relays
            try {
                val fetchedPosts = searchService.fetchUserPosts(pubkey)
                if (fetchedPosts.isNotEmpty()) {
                    _uiState.update { state ->
                        val existingIds = state.myPosts.map { it.id }.toSet()
                        val newPosts = fetchedPosts.filter { it.id !in existingIds }
                        state.copy(
                            myPosts = deduplicatePosts(state.myPosts + newPosts)
                        )
                    }
                }
            } catch (_: Exception) { }

            _uiState.update { it.copy(isLoadingMyPosts = false) }
        }
    }

    /**
     * Deduplicate posts by imageUrl. When the same image appears in both a Kind 20
     * and Kind 1 event (cross-client compatibility), keep only one.
     */
    private fun deduplicatePosts(posts: List<PhotoPost>): List<PhotoPost> {
        return posts
            .sortedByDescending { it.createdAt }
            .distinctBy { it.imageUrl }
    }

    fun addRelay(url: String) {
        val normalizedUrl = normalizeRelayUrl(url)
        if (normalizedUrl.isNotEmpty()) {
            viewModelScope.launch {
                // Merge the new relay into the existing topology rather than
                // clobbering it (NIP-65 aware).
                val current = nostrClient.getCurrentTopology()
                val merged = if (current.isEmpty()) {
                    RelayTopology.fromRelays(listOf(normalizedUrl))
                } else {
                    RelayTopology(
                        readRelays = current.readRelays + normalizedUrl,
                        writeRelays = current.writeRelays + normalizedUrl
                    )
                }
                nostrClient.connectWithTopology(merged)
            }
        }
    }

    fun removeRelay(url: String) {
        nostrClient.disconnectRelay(url)
    }

    fun reconnectRelay(url: String) {
        val normalizedUrl = normalizeRelayUrl(url)
        viewModelScope.launch {
            // First remove the existing failed connection
            nostrClient.disconnectRelay(normalizedUrl)
            // Reconnect against current topology (preserves write flags etc.)
            nostrClient.reconnect()
        }
    }

    fun reconnectAllRelays() {
        viewModelScope.launch {
            nostrClient.reconnect()
        }
    }

    fun logout() {
        keyManager.clearKeys()
        nostrClient.disconnect()
        _uiState.update { it.copy(isLoggedOut = true) }
    }

    fun copyToClipboard(text: String) {
        _uiState.update { it.copy(clipboardText = text) }
    }

    fun clearClipboardNotification() {
        _uiState.update { it.copy(clipboardText = null) }
    }

    fun saveNwcConnection(connectionString: String) {
        viewModelScope.launch {
            val success = nwcService.saveConnectionString(connectionString)
            _uiState.update {
                it.copy(
                    isNwcConfigured = success,
                    nwcError = if (!success) "Invalid NWC connection string" else null
                )
            }
        }
    }

    fun clearNwcConnection() {
        viewModelScope.launch {
            nwcService.clearConnection()
            _uiState.update { it.copy(isNwcConfigured = false) }
        }
    }

    private fun normalizeRelayUrl(url: String): String {
        var normalized = url.trim()
        if (normalized.isEmpty()) return ""

        if (!normalized.startsWith("wss://") && !normalized.startsWith("ws://")) {
            normalized = "wss://$normalized"
        }
        return normalized.trimEnd('/')
    }
}

enum class SettingsTab {
    SETTINGS,
    FOLLOWING,
    MUTED,
    MY_POSTS
}

data class SettingsUiState(
    val pubkeyHex: String? = null,
    val npub: String? = null,
    val defaultRelays: List<String> = emptyList(),
    val relayStatuses: List<RelayInfo> = emptyList(),
    val isNwcConfigured: Boolean = false,
    val nwcError: String? = null,
    val isLoggedOut: Boolean = false,
    val clipboardText: String? = null,
    val selectedTab: SettingsTab = SettingsTab.SETTINGS,
    val followingCount: Int = 0,
    val mutedCount: Int = 0,
    val followingUsers: List<UserMetadata> = emptyList(),
    val mutedUsers: List<UserMetadata> = emptyList(),
    val isLoadingFollowList: Boolean = false,
    val isLoadingMuteList: Boolean = false,
    val myPosts: List<PhotoPost> = emptyList(),
    val isLoadingMyPosts: Boolean = false,
    // NIP-65 relay list state
    val relayTopology: RelayTopology = RelayTopology.EMPTY,
    val relayPublishState: RelayListRepository.PublishState = RelayListRepository.PublishState.Idle,
    val relayHasPublishedOnce: Boolean = false
)

data class RelayInfo(
    val url: String,
    val isConnected: Boolean,
    val status: String
)
