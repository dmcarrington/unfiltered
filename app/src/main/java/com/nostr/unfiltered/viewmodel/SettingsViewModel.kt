package com.nostr.unfiltered.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.NwcService
import com.nostr.unfiltered.nostr.SearchService
import com.nostr.unfiltered.nostr.models.UserMetadata
import com.nostr.unfiltered.repository.FeedRepository
import com.nostr.unfiltered.repository.MuteListRepository
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
    private val searchService: SearchService
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        observeRelayStatus()
        loadNwcStatus()
        observeFollowList()
        observeMuteList()
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

    fun addRelay(url: String) {
        val normalizedUrl = normalizeRelayUrl(url)
        if (normalizedUrl.isNotEmpty()) {
            viewModelScope.launch {
                nostrClient.connect(listOf(normalizedUrl))
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
            // Then reconnect
            nostrClient.connect(listOf(normalizedUrl))
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
    MUTED
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
    val isLoadingMuteList: Boolean = false
)

data class RelayInfo(
    val url: String,
    val isConnected: Boolean,
    val status: String
)
