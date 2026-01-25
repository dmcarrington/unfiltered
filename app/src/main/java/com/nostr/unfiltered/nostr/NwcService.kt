package com.nostr.unfiltered.nostr

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.nwcDataStore by preferencesDataStore(name = "nwc_settings")

/**
 * Service for Nostr Wallet Connect (NIP-47) - enables zaps.
 *
 * Note: Full NWC implementation requires NIP-04 encryption which is not yet
 * fully supported in the rust-nostr Kotlin bindings. This service provides
 * connection management, with payment functionality as a placeholder.
 */
@Singleton
class NwcService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectionStringKey = stringPreferencesKey("nwc_connection_string")

    data class NwcConnection(
        val walletPubkey: String,
        val relayUrl: String,
        val secret: String
    )

    data class ZapResult(
        val success: Boolean,
        val preimage: String? = null,
        val error: String? = null
    )

    /**
     * Parse a NWC connection string (nostr+walletconnect://...)
     */
    fun parseConnectionString(connectionString: String): NwcConnection? {
        return try {
            val uri = android.net.Uri.parse(connectionString)

            if (uri.scheme != "nostr+walletconnect") return null

            val walletPubkey = uri.host ?: return null
            val relayUrl = uri.getQueryParameter("relay") ?: return null
            val secret = uri.getQueryParameter("secret") ?: return null

            NwcConnection(walletPubkey, relayUrl, secret)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Save NWC connection string
     */
    suspend fun saveConnectionString(connectionString: String): Boolean {
        val connection = parseConnectionString(connectionString) ?: return false

        context.nwcDataStore.edit { prefs ->
            prefs[connectionStringKey] = connectionString
        }

        return true
    }

    /**
     * Get saved NWC connection
     */
    suspend fun getConnection(): NwcConnection? {
        val connectionString = context.nwcDataStore.data
            .map { prefs -> prefs[connectionStringKey] }
            .first()

        return connectionString?.let { parseConnectionString(it) }
    }

    /**
     * Check if NWC is configured
     */
    suspend fun isConfigured(): Boolean {
        return getConnection() != null
    }

    /**
     * Clear NWC connection
     */
    suspend fun clearConnection() {
        context.nwcDataStore.edit { prefs ->
            prefs.remove(connectionStringKey)
        }
    }

    /**
     * Send a zap (pay invoice) via NWC
     * @param bolt11 The Lightning invoice to pay
     * @return ZapResult with success/failure info
     *
     * Note: This is a placeholder. Full NWC requires NIP-04 encryption
     * which requires additional cryptography dependencies.
     */
    suspend fun payInvoice(bolt11: String): ZapResult = withContext(Dispatchers.IO) {
        val connection = getConnection()
            ?: return@withContext ZapResult(false, error = "NWC not configured")

        // TODO: Implement full NWC payment flow with NIP-04 encryption
        // This requires:
        // 1. ECDH shared secret generation using secp256k1
        // 2. AES-256-CBC encryption with the shared secret
        // 3. WebSocket connection to the wallet relay
        // 4. Sending encrypted pay_invoice request
        // 5. Receiving and decrypting the response

        ZapResult(
            success = false,
            error = "NWC payments require NIP-04 encryption (coming soon)"
        )
    }

    /**
     * Get a Lightning invoice for zapping a user (requires fetching their LNURL)
     */
    suspend fun getZapInvoice(
        recipientPubkey: String,
        amountSats: Long,
        comment: String = "",
        eventId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        // TODO: Implement full zap flow
        // This requires:
        // 1. Looking up the user's lud16 from their metadata
        // 2. Fetching the LNURL pay endpoint
        // 3. Creating a zap request event
        // 4. Getting an invoice from the LNURL endpoint

        Result.failure(Exception("Zap flow requires LNURL integration (coming soon)"))
    }
}
