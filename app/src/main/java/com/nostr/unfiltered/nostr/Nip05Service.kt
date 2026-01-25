package com.nostr.unfiltered.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for NIP-05 identity verification.
 * Verifies that a user's NIP-05 identifier (user@domain.com) points to their pubkey.
 */
@Singleton
class Nip05Service @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Nip05Result(
        val isValid: Boolean,
        val pubkey: String? = null,
        val relays: List<String> = emptyList()
    )

    /**
     * Verify a NIP-05 identifier against a pubkey.
     * @param nip05 The NIP-05 identifier (e.g., "user@domain.com" or "_@domain.com")
     * @param expectedPubkey The pubkey to verify against (hex format)
     * @return Nip05Result with verification status
     */
    suspend fun verify(nip05: String, expectedPubkey: String): Nip05Result = withContext(Dispatchers.IO) {
        try {
            val (name, domain) = parseNip05(nip05) ?: return@withContext Nip05Result(false)

            val url = "https://$domain/.well-known/nostr.json?name=$name"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Nip05Result(false)
            }

            val body = response.body?.string() ?: return@withContext Nip05Result(false)
            val json = JSONObject(body)

            val names = json.optJSONObject("names") ?: return@withContext Nip05Result(false)
            val pubkey = names.optString(name, null) ?: return@withContext Nip05Result(false)

            // Check if pubkey matches (case-insensitive hex comparison)
            val isValid = pubkey.equals(expectedPubkey, ignoreCase = true)

            // Get recommended relays if available
            val relays = mutableListOf<String>()
            json.optJSONObject("relays")?.let { relaysObj ->
                relaysObj.optJSONArray(pubkey)?.let { relayArray ->
                    for (i in 0 until relayArray.length()) {
                        relayArray.optString(i)?.let { relays.add(it) }
                    }
                }
            }

            Nip05Result(isValid, pubkey, relays)
        } catch (e: Exception) {
            Nip05Result(false)
        }
    }

    /**
     * Lookup a NIP-05 identifier and return the associated pubkey.
     * @param nip05 The NIP-05 identifier
     * @return The pubkey if found, null otherwise
     */
    suspend fun lookup(nip05: String): String? = withContext(Dispatchers.IO) {
        try {
            val (name, domain) = parseNip05(nip05) ?: return@withContext null

            val url = "https://$domain/.well-known/nostr.json?name=$name"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val names = json.optJSONObject("names") ?: return@withContext null
            names.optString(name, null)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseNip05(nip05: String): Pair<String, String>? {
        val cleaned = nip05.trim().lowercase()

        // Handle _@domain.com format (root identifier)
        if (cleaned.startsWith("_@")) {
            val domain = cleaned.removePrefix("_@")
            if (domain.isNotEmpty() && domain.contains(".")) {
                return Pair("_", domain)
            }
        }

        // Handle user@domain.com format
        val parts = cleaned.split("@")
        if (parts.size == 2) {
            val name = parts[0]
            val domain = parts[1]
            if (name.isNotEmpty() && domain.isNotEmpty() && domain.contains(".")) {
                return Pair(name, domain)
            }
        }

        return null
    }
}
