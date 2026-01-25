package com.nostr.unfiltered.nostr

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import rust.nostr.protocol.EventBuilder
import rust.nostr.protocol.Keys
import rust.nostr.protocol.Kind
import rust.nostr.protocol.PublicKey
import rust.nostr.protocol.Tag
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages zap payments using either:
 * 1. NWC with Amber for NIP-04 encryption (for Amber users)
 * 2. Direct Lightning wallet intent (fallback for all users)
 */
@Singleton
class ZapManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager,
    private val nwcService: NwcService
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    sealed class ZapMethod {
        object NwcWithAmber : ZapMethod()
        object LightningUri : ZapMethod()
        object NotAvailable : ZapMethod()
    }

    sealed class ZapResult {
        object Success : ZapResult()
        data class NeedsAmberEncryption(
            val intent: Intent,
            val pendingZap: PendingNwcZap
        ) : ZapResult()
        data class NeedsAmberDecryption(
            val intent: Intent,
            val pendingZap: PendingNwcZap
        ) : ZapResult()
        data class OpenLightningWallet(val intent: Intent) : ZapResult()
        data class Error(val message: String) : ZapResult()
    }

    data class PendingNwcZap(
        val recipientPubkey: String,
        val amountSats: Long,
        val eventId: String?,
        val invoice: String? = null,
        val encryptedRequest: String? = null
    )

    data class LnurlPayInfo(
        val callback: String,
        val minSendable: Long,
        val maxSendable: Long,
        val allowsNostr: Boolean,
        val nostrPubkey: String?
    )

    /**
     * Determine the best zap method for the current user
     */
    suspend fun getAvailableZapMethod(): ZapMethod {
        return when {
            keyManager.isAmberConnected() && nwcService.isConfigured() -> ZapMethod.NwcWithAmber
            else -> ZapMethod.LightningUri
        }
    }

    /**
     * Initiate a zap to a user
     */
    suspend fun initiateZap(
        recipientPubkey: String,
        recipientLud16: String?,
        amountSats: Long,
        eventId: String? = null,
        comment: String = ""
    ): ZapResult = withContext(Dispatchers.IO) {
        // First, get the Lightning invoice from the recipient's LNURL
        if (recipientLud16.isNullOrEmpty()) {
            return@withContext ZapResult.Error("Recipient doesn't have a Lightning address")
        }

        val lnurlInfo = fetchLnurlPayInfo(recipientLud16)
            ?: return@withContext ZapResult.Error("Failed to fetch Lightning address info")

        // Check amount bounds
        val amountMsats = amountSats * 1000
        if (amountMsats < lnurlInfo.minSendable || amountMsats > lnurlInfo.maxSendable) {
            return@withContext ZapResult.Error(
                "Amount must be between ${lnurlInfo.minSendable / 1000} and ${lnurlInfo.maxSendable / 1000} sats"
            )
        }

        // Create zap request event if the service supports it
        val zapRequestJson = if (lnurlInfo.allowsNostr && keyManager.getKeys() != null) {
            createZapRequestEvent(
                recipientPubkey = recipientPubkey,
                amountMsats = amountMsats,
                eventId = eventId,
                comment = comment,
                lnurl = recipientLud16
            )
        } else null

        // Fetch the invoice
        val invoice = fetchInvoice(lnurlInfo.callback, amountMsats, zapRequestJson, comment)
            ?: return@withContext ZapResult.Error("Failed to get Lightning invoice")

        // Determine payment method
        when (getAvailableZapMethod()) {
            ZapMethod.NwcWithAmber -> {
                // Use NWC with Amber for encryption
                initiateNwcPayment(recipientPubkey, amountSats, eventId, invoice)
            }
            ZapMethod.LightningUri -> {
                // Open Lightning wallet directly
                val intent = createLightningIntent(invoice)
                if (intent != null) {
                    ZapResult.OpenLightningWallet(intent)
                } else {
                    ZapResult.Error("No Lightning wallet installed")
                }
            }
            ZapMethod.NotAvailable -> {
                ZapResult.Error("No payment method available")
            }
        }
    }

    /**
     * Initiate NWC payment (requires Amber for encryption)
     */
    private suspend fun initiateNwcPayment(
        recipientPubkey: String,
        amountSats: Long,
        eventId: String?,
        invoice: String
    ): ZapResult {
        val connection = nwcService.getConnection()
            ?: return ZapResult.Error("NWC not configured")

        // Create the pay_invoice request JSON
        val requestContent = JSONObject().apply {
            put("method", "pay_invoice")
            put("params", JSONObject().apply {
                put("invoice", invoice)
            })
        }.toString()

        // Create intent for Amber to encrypt
        val encryptIntent = keyManager.createAmberNip04EncryptIntent(
            plaintext = requestContent,
            recipientPubkey = connection.walletPubkey
        )

        return ZapResult.NeedsAmberEncryption(
            intent = encryptIntent,
            pendingZap = PendingNwcZap(
                recipientPubkey = recipientPubkey,
                amountSats = amountSats,
                eventId = eventId,
                invoice = invoice
            )
        )
    }

    /**
     * Continue NWC payment after Amber encryption
     */
    suspend fun continueNwcAfterEncryption(
        encryptedContent: String,
        pendingZap: PendingNwcZap
    ): ZapResult = withContext(Dispatchers.IO) {
        val connection = nwcService.getConnection()
            ?: return@withContext ZapResult.Error("NWC not configured")

        val userPubkey = keyManager.getPublicKeyHex()
            ?: return@withContext ZapResult.Error("No public key")

        // Create the NWC request event (kind 23194)
        val tags = listOf(
            Tag.parse(listOf("p", connection.walletPubkey))
        )

        // For Amber users, we need to sign the event with Amber too
        // But for simplicity, we'll create an unsigned event and note this limitation
        // A full implementation would require another Amber round-trip for signing

        // For now, return an error indicating we need to implement signing
        // This is a limitation that requires the full Amber signing flow
        ZapResult.Error("NWC requires event signing - use Lightning wallet instead")
    }

    /**
     * Create a Lightning wallet intent
     */
    fun createLightningIntent(invoice: String): Intent? {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("lightning:$invoice")
        }

        // Check if any app can handle this intent
        val activities = context.packageManager.queryIntentActivities(intent, 0)
        return if (activities.isNotEmpty()) intent else null
    }

    /**
     * Check if a Lightning wallet is installed
     */
    fun isLightningWalletInstalled(): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("lightning:test"))
        val activities = context.packageManager.queryIntentActivities(intent, 0)
        return activities.isNotEmpty()
    }

    /**
     * Fetch LNURL pay info from a Lightning address (lud16)
     */
    private suspend fun fetchLnurlPayInfo(lud16: String): LnurlPayInfo? {
        return try {
            // Parse the Lightning address (user@domain.com)
            val parts = lud16.split("@")
            if (parts.size != 2) return null

            val username = parts[0]
            val domain = parts[1]

            // Fetch the LNURL pay endpoint
            val url = "https://$domain/.well-known/lnurlp/$username"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) return null

            val json = JSONObject(response.body?.string() ?: return null)

            LnurlPayInfo(
                callback = json.getString("callback"),
                minSendable = json.getLong("minSendable"),
                maxSendable = json.getLong("maxSendable"),
                allowsNostr = json.optBoolean("allowsNostr", false),
                nostrPubkey = json.optString("nostrPubkey", null)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetch a Lightning invoice from the LNURL callback
     */
    private suspend fun fetchInvoice(
        callback: String,
        amountMsats: Long,
        zapRequest: String?,
        comment: String
    ): String? {
        return try {
            val urlBuilder = StringBuilder(callback)
            urlBuilder.append(if (callback.contains("?")) "&" else "?")
            urlBuilder.append("amount=$amountMsats")

            if (!comment.isBlank()) {
                urlBuilder.append("&comment=${Uri.encode(comment)}")
            }

            if (zapRequest != null) {
                urlBuilder.append("&nostr=${Uri.encode(zapRequest)}")
            }

            val request = Request.Builder().url(urlBuilder.toString()).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) return null

            val json = JSONObject(response.body?.string() ?: return null)
            json.optString("pr", null)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create a zap request event (NIP-57)
     */
    private fun createZapRequestEvent(
        recipientPubkey: String,
        amountMsats: Long,
        eventId: String?,
        comment: String,
        lnurl: String
    ): String? {
        return try {
            val keys = keyManager.getKeys() ?: return null

            val tags = mutableListOf(
                Tag.parse(listOf("p", recipientPubkey)),
                Tag.parse(listOf("amount", amountMsats.toString())),
                Tag.parse(listOf("relays", "wss://relay.damus.io", "wss://relay.primal.net")),
                Tag.parse(listOf("lnurl", lnurl))
            )

            if (eventId != null) {
                tags.add(Tag.parse(listOf("e", eventId)))
            }

            val event = EventBuilder(Kind(9734u), comment, tags)
                .toEvent(keys)

            event.asJson()
        } catch (e: Exception) {
            null
        }
    }
}
