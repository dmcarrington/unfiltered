package com.nostr.unfiltered.nostr

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rust.nostr.protocol.Keys
import rust.nostr.protocol.PublicKey
import rust.nostr.protocol.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Nostr key storage and Amber signer integration (NIP-55).
 * Keys are stored encrypted using Android's EncryptedSharedPreferences.
 */
@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        "nostr_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private var cachedKeys: Keys? = null

    private val _authState = MutableStateFlow(AuthState.UNKNOWN)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        // Check initial auth state
        _authState.value = when {
            isAmberConnected() -> AuthState.AUTHENTICATED_AMBER
            hasLocalKeys() -> AuthState.AUTHENTICATED_LOCAL
            else -> AuthState.NOT_AUTHENTICATED
        }
    }

    /**
     * Check if user has any form of authentication set up
     */
    fun isAuthenticated(): Boolean = hasLocalKeys() || isAmberConnected()

    /**
     * Check if local nsec is stored
     */
    fun hasLocalKeys(): Boolean = securePrefs.contains("nsec")

    /**
     * Check if connected to Amber
     */
    fun isAmberConnected(): Boolean = securePrefs.getBoolean("amber_connected", false)

    /**
     * Get the user's public key (works for both Amber and local keys)
     */
    fun getPublicKey(): PublicKey? {
        val npub = securePrefs.getString("npub", null) ?: return null
        return try {
            PublicKey.fromBech32(npub)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the user's public key as hex string
     */
    fun getPublicKeyHex(): String? {
        return getPublicKey()?.toHex()
    }

    /**
     * Get the user's public key as npub string
     */
    fun getNpub(): String? {
        return securePrefs.getString("npub", null)
    }

    /**
     * Get Keys object for signing (only available with local nsec, not Amber)
     */
    fun getKeys(): Keys? {
        if (isAmberConnected()) return null // Must use Amber for signing

        if (cachedKeys != null) return cachedKeys

        val nsec = securePrefs.getString("nsec", null) ?: return null
        return try {
            val secretKey = SecretKey.fromBech32(nsec)
            Keys(secretKey).also { cachedKeys = it }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Import an existing nsec
     */
    fun importNsec(nsec: String): Result<Keys> {
        return try {
            // Validate and parse the nsec
            val secretKey = SecretKey.fromBech32(nsec.trim())
            val keys = Keys(secretKey)

            // Store securely
            securePrefs.edit()
                .putString("nsec", nsec.trim())
                .putString("npub", keys.publicKey().toBech32())
                .putBoolean("amber_connected", false)
                .apply()

            cachedKeys = keys
            _authState.value = AuthState.AUTHENTICATED_LOCAL
            Result.success(keys)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Generate a new keypair
     */
    fun generateNewKeys(): Keys {
        val keys = Keys.generate()

        securePrefs.edit()
            .putString("nsec", keys.secretKey().toBech32())
            .putString("npub", keys.publicKey().toBech32())
            .putBoolean("amber_connected", false)
            .apply()

        cachedKeys = keys
        _authState.value = AuthState.AUTHENTICATED_LOCAL
        return keys
    }

    /**
     * Clear all stored keys and logout
     */
    fun clearKeys() {
        securePrefs.edit().clear().apply()
        cachedKeys = null
        _authState.value = AuthState.NOT_AUTHENTICATED
    }

    // ==================== Amber Integration (NIP-55) ====================

    /**
     * Check if Amber signer app is installed
     */
    fun isAmberInstalled(): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        val activities = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        return activities.isNotEmpty()
    }

    /**
     * Create intent to get public key from Amber
     */
    fun createAmberGetPublicKeyIntent(): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.Builder()
                .scheme("nostrsigner")
                .authority("get_public_key")
                .appendQueryParameter("callbackUrl", "nostr-unfiltered://callback")
                .build()
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    /**
     * Create intent to sign an event with Amber
     */
    fun createAmberSignEventIntent(eventJson: String, eventId: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.Builder()
                .scheme("nostrsigner")
                .authority("sign_event")
                .appendQueryParameter("event", eventJson)
                .appendQueryParameter("id", eventId)
                .appendQueryParameter("callbackUrl", "nostr-unfiltered://callback")
                .build()
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    /**
     * Create intent to encrypt a message with NIP-04
     */
    fun createAmberNip04EncryptIntent(plaintext: String, recipientPubkey: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.Builder()
                .scheme("nostrsigner")
                .authority("nip04_encrypt")
                .appendQueryParameter("plaintext", plaintext)
                .appendQueryParameter("pubkey", recipientPubkey)
                .appendQueryParameter("callbackUrl", "nostr-unfiltered://callback")
                .build()
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    /**
     * Create intent to decrypt a message with NIP-04
     */
    fun createAmberNip04DecryptIntent(ciphertext: String, senderPubkey: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.Builder()
                .scheme("nostrsigner")
                .authority("nip04_decrypt")
                .appendQueryParameter("ciphertext", ciphertext)
                .appendQueryParameter("pubkey", senderPubkey)
                .appendQueryParameter("callbackUrl", "nostr-unfiltered://callback")
                .build()
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    /**
     * Save public key received from Amber
     */
    fun saveAmberPublicKey(npub: String): Result<Unit> {
        return try {
            // Validate the npub
            val pubkey = PublicKey.fromBech32(npub.trim())

            securePrefs.edit()
                .putString("npub", npub.trim())
                .putBoolean("amber_connected", true)
                .remove("nsec") // Never store nsec when using Amber
                .apply()

            cachedKeys = null
            _authState.value = AuthState.AUTHENTICATED_AMBER
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse callback URI from Amber
     */
    fun parseAmberCallback(uri: Uri): AmberCallbackResult {
        return when (uri.host) {
            "callback" -> {
                val signature = uri.getQueryParameter("signature")
                val event = uri.getQueryParameter("event")
                val npub = uri.getQueryParameter("npub")
                val error = uri.getQueryParameter("error")

                when {
                    error != null -> AmberCallbackResult.Error(error)
                    npub != null -> AmberCallbackResult.PublicKey(npub)
                    signature != null -> AmberCallbackResult.Signature(signature, event)
                    event != null -> AmberCallbackResult.SignedEvent(event)
                    else -> AmberCallbackResult.Error("Unknown callback format")
                }
            }
            else -> AmberCallbackResult.Error("Unknown callback host: ${uri.host}")
        }
    }

    enum class AuthState {
        UNKNOWN,
        NOT_AUTHENTICATED,
        AUTHENTICATED_LOCAL,
        AUTHENTICATED_AMBER
    }
}

sealed class AmberCallbackResult {
    data class PublicKey(val npub: String) : AmberCallbackResult()
    data class Signature(val signature: String, val event: String?) : AmberCallbackResult()
    data class SignedEvent(val eventJson: String) : AmberCallbackResult()
    data class Error(val message: String) : AmberCallbackResult()
}
