package com.nostr.unfiltered.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.AmberCallbackResult
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val nostrClient: NostrClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    val isAuthenticated: StateFlow<Boolean> = keyManager.authState
        .map { it == KeyManager.AuthState.AUTHENTICATED_LOCAL || it == KeyManager.AuthState.AUTHENTICATED_AMBER }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), keyManager.isAuthenticated())

    init {
        checkAmberAvailability()
    }

    private fun checkAmberAvailability() {
        _uiState.value = _uiState.value.copy(
            isAmberAvailable = keyManager.isAmberInstalled()
        )
    }

    /**
     * Get intent to launch Amber for public key retrieval
     */
    fun getAmberIntent(): Intent {
        return keyManager.createAmberGetPublicKeyIntent()
    }

    /**
     * Handle result from Amber Activity Result (NIP-55)
     * Amber returns the public key via intent extra "result" or "signature"
     */
    fun handleAmberActivityResult(resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK) {
            _uiState.value = _uiState.value.copy(
                error = "Amber authorization was cancelled"
            )
            return
        }

        // Amber may return the public key under different extra keys
        var pubkey = data?.getStringExtra("result")
            ?: data?.getStringExtra("signature")
            ?: data?.getStringExtra("pubkey")
            ?: data?.getStringExtra("npub")

        // Also check if it's in the data URI as a query parameter
        if (pubkey.isNullOrBlank()) {
            val uri = data?.data
            pubkey = uri?.getQueryParameter("result")
                ?: uri?.getQueryParameter("pubkey")
                ?: uri?.getQueryParameter("npub")
        }

        if (pubkey.isNullOrBlank()) {
            // Debug: show what we received
            val extras = data?.extras?.keySet()?.joinToString(", ") ?: "none"
            val dataUri = data?.data?.toString() ?: "none"
            _uiState.value = _uiState.value.copy(
                error = "No public key from Amber. Extras: $extras, Data: $dataUri"
            )
            return
        }

        keyManager.saveAmberPublicKeyAny(pubkey)
            .onSuccess {
                _uiState.value = _uiState.value.copy(
                    isAuthenticated = true,
                    npub = keyManager.getNpub(),
                    error = null
                )
                connectToRelays()
            }
            .onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    error = "Invalid public key from Amber: ${e.message}"
                )
            }
    }

    /**
     * Handle result from Amber callback (legacy deep link approach)
     */
    fun handleAmberResult(result: AmberCallbackResult) {
        when (result) {
            is AmberCallbackResult.PublicKey -> {
                keyManager.saveAmberPublicKey(result.npub)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(
                            isAuthenticated = true,
                            npub = result.npub,
                            error = null
                        )
                        connectToRelays()
                    }
                    .onFailure { e ->
                        _uiState.value = _uiState.value.copy(
                            error = "Invalid public key from Amber: ${e.message}"
                        )
                    }
            }
            is AmberCallbackResult.Error -> {
                _uiState.value = _uiState.value.copy(
                    error = "Amber error: ${result.message}"
                )
            }
            else -> {
                // Other callback types handled elsewhere
            }
        }
    }

    /**
     * Import nsec from user input
     */
    fun importNsec(nsec: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        keyManager.importNsec(nsec)
            .onSuccess { keys ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isAuthenticated = true,
                    npub = keys.publicKey().toBech32(),
                    error = null
                )
                connectToRelays()
            }
            .onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Invalid nsec: ${e.message}"
                )
            }
    }

    /**
     * Generate new keypair
     */
    fun generateNewAccount() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        try {
            val keys = keyManager.generateNewKeys()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isAuthenticated = true,
                npub = keys.publicKey().toBech32(),
                nsecGenerated = keys.secretKey().toBech32(),
                showBackupWarning = true,
                error = null
            )
            connectToRelays()
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Failed to generate keys: ${e.message}"
            )
        }
    }

    /**
     * Dismiss backup warning after user has seen/copied nsec
     */
    fun dismissBackupWarning() {
        _uiState.value = _uiState.value.copy(
            showBackupWarning = false,
            nsecGenerated = null
        )
    }

    /**
     * Show nsec import dialog
     */
    fun showNsecDialog() {
        _uiState.value = _uiState.value.copy(showNsecDialog = true)
    }

    /**
     * Hide nsec import dialog
     */
    fun hideNsecDialog() {
        _uiState.value = _uiState.value.copy(showNsecDialog = false, error = null)
    }

    /**
     * Clear any error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Logout and clear all data
     */
    fun logout() {
        nostrClient.disconnect()
        keyManager.clearKeys()
        _uiState.value = AuthUiState(isAmberAvailable = keyManager.isAmberInstalled())
    }

    private fun connectToRelays() {
        viewModelScope.launch {
            nostrClient.connect()
        }
    }
}

data class AuthUiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val isAmberAvailable: Boolean = false,
    val npub: String? = null,
    val nsecGenerated: String? = null,
    val showBackupWarning: Boolean = false,
    val showNsecDialog: Boolean = false,
    val error: String? = null
)
