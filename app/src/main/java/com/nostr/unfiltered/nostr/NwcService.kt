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
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.nwcDataStore by preferencesDataStore(name = "nwc_settings")

/**
 * Service for Nostr Wallet Connect (NIP-47) - enables wallet operations.
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

    data class NwcTransaction(
        val type: String,           // "incoming" or "outgoing"
        val invoice: String?,
        val description: String?,
        val preimage: String?,
        val paymentHash: String?,
        val amount: Long,           // in millisats
        val feesPaid: Long?,
        val createdAt: Long,
        val settledAt: Long?
    )

    data class PaymentResult(
        val preimage: String
    )

    /**
     * Parse a NWC connection string (nostr+walletconnect://... or nostrwalletconnect://...)
     */
    fun parseConnectionString(connectionString: String): NwcConnection? {
        return try {
            // Handle both URI schemes
            val normalized = connectionString
                .replace("nostrwalletconnect://", "nostr+walletconnect://")

            val uri = android.net.Uri.parse(normalized)

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
     * Get the saved connection string (for display/debugging)
     */
    suspend fun getConnectionString(): String? {
        return context.nwcDataStore.data
            .map { prefs -> prefs[connectionStringKey] }
            .first()
    }
}
