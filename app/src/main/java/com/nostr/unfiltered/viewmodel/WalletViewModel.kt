package com.nostr.unfiltered.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.NwcClient
import com.nostr.unfiltered.nostr.NwcService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val nwcService: NwcService,
    private val nwcClient: NwcClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    init {
        checkConfiguration()
    }

    private fun checkConfiguration() {
        viewModelScope.launch {
            try {
                val isConfigured = nwcService.isConfigured()
                _uiState.update { it.copy(isConfigured = isConfigured) }

                if (isConfigured) {
                    loadWalletData()
                }
            } catch (e: Exception) {
                android.util.Log.e("WalletViewModel", "Failed to check configuration", e)
                _uiState.update { it.copy(isConfigured = false) }
            }
        }
    }

    fun loadWalletData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Get balance
            val balanceResult = nwcClient.sendRequest("get_balance")
            balanceResult
                .onSuccess { response ->
                    val result = response.optJSONObject("result")
                    val balanceMsats = result?.optLong("balance", 0) ?: 0
                    val balanceSats = balanceMsats / 1000
                    _uiState.update { it.copy(balance = balanceSats) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = "Failed to get balance: ${error.message}") }
                }

            // Get transactions
            val txParams = JSONObject().apply {
                put("limit", 20)
            }
            val txResult = nwcClient.sendRequest("list_transactions", txParams)
            txResult
                .onSuccess { response ->
                    val result = response.optJSONObject("result")
                    val txArray = result?.optJSONArray("transactions")
                    val transactions = mutableListOf<WalletTransaction>()

                    if (txArray != null) {
                        for (i in 0 until txArray.length()) {
                            val tx = txArray.getJSONObject(i)
                            transactions.add(
                                WalletTransaction(
                                    type = tx.optString("type", "unknown"),
                                    amount = tx.optLong("amount", 0) / 1000, // Convert msats to sats
                                    description = tx.optString("description", null),
                                    createdAt = tx.optLong("created_at", 0),
                                    settledAt = tx.optLong("settled_at", 0).takeIf { it > 0 }
                                )
                            )
                        }
                    }

                    _uiState.update { it.copy(transactions = transactions, isLoading = false) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false) }
                    // Transaction listing might not be supported by all wallets
                }
        }
    }

    fun sendPayment(invoice: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, error = null, lastPaymentSuccess = false) }

            val params = JSONObject().apply {
                put("invoice", invoice)
            }

            val result = nwcClient.sendRequest("pay_invoice", params)
            result
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            lastPaymentSuccess = true,
                            invoiceInput = ""
                        )
                    }
                    // Refresh balance after payment
                    loadWalletData()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            error = "Payment failed: ${error.message}"
                        )
                    }
                }
        }
    }

    fun connectWallet(connectionString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val success = nwcService.saveConnectionString(connectionString)
            if (success) {
                _uiState.update { it.copy(isConfigured = true) }
                loadWalletData()
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Invalid connection string"
                    )
                }
            }
        }
    }

    fun disconnectWallet() {
        viewModelScope.launch {
            nwcService.clearConnection()
            nwcClient.disconnect()
            _uiState.update {
                WalletUiState(isConfigured = false)
            }
        }
    }

    fun updateInvoiceInput(invoice: String) {
        _uiState.update { it.copy(invoiceInput = invoice) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearPaymentSuccess() {
        _uiState.update { it.copy(lastPaymentSuccess = false) }
    }
}

data class WalletUiState(
    val isConfigured: Boolean = false,
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val balance: Long = 0,
    val transactions: List<WalletTransaction> = emptyList(),
    val error: String? = null,
    val lastPaymentSuccess: Boolean = false,
    val invoiceInput: String = ""
)

data class WalletTransaction(
    val type: String,           // "incoming" or "outgoing"
    val amount: Long,           // in sats
    val description: String?,
    val createdAt: Long,
    val settledAt: Long?
)
