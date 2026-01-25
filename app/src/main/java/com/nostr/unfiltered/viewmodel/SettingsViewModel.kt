package com.nostr.unfiltered.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.NwcService
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
    private val nwcService: NwcService
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        observeRelayStatus()
        loadNwcStatus()
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

data class SettingsUiState(
    val pubkeyHex: String? = null,
    val npub: String? = null,
    val defaultRelays: List<String> = emptyList(),
    val relayStatuses: List<RelayInfo> = emptyList(),
    val isNwcConfigured: Boolean = false,
    val nwcError: String? = null,
    val isLoggedOut: Boolean = false,
    val clipboardText: String? = null
)

data class RelayInfo(
    val url: String,
    val isConnected: Boolean,
    val status: String
)
