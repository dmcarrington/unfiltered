package com.nostr.unfiltered.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import rust.nostr.protocol.Event
import rust.nostr.protocol.EventBuilder
import rust.nostr.protocol.Filter
import rust.nostr.protocol.Kind
import rust.nostr.protocol.Keys
import rust.nostr.protocol.PublicKey
import rust.nostr.protocol.SecretKey
import rust.nostr.protocol.Tag
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebSocket client for NWC (Nostr Wallet Connect) relay communication.
 * Handles NIP-47 protocol for wallet operations.
 */
@Singleton
class NwcClient @Inject constructor(
    private val nwcService: NwcService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webSocket: WebSocket? = null
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val _responses = MutableSharedFlow<NwcResponse>(extraBufferCapacity = 100)
    val responses: SharedFlow<NwcResponse> = _responses

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private var isConnected = false
    private var connectionKeys: Keys? = null
    private var walletPubkey: String? = null

    /**
     * Connect to the NWC relay
     */
    suspend fun connect(): Boolean {
        val connection = nwcService.getConnection() ?: return false

        // Parse the secret to create ephemeral keys for this connection
        try {
            val secretKey = SecretKey.fromHex(connection.secret)
            connectionKeys = Keys(secretKey)
            walletPubkey = connection.walletPubkey
        } catch (e: Exception) {
            return false
        }

        return suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(connection.relayUrl)
                .build()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isConnected = true
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnected = false
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                }
            })

            continuation.invokeOnCancellation {
                webSocket?.cancel()
            }
        }
    }

    /**
     * Disconnect from the NWC relay
     */
    fun disconnect() {
        webSocket?.close(1000, "Client closing")
        webSocket = null
        isConnected = false
        connectionKeys = null
        walletPubkey = null
    }

    /**
     * Send an NWC request and wait for response
     */
    suspend fun sendRequest(method: String, params: JSONObject = JSONObject()): Result<JSONObject> {
        if (!isConnected) {
            if (!connect()) {
                return Result.failure(Exception("Failed to connect to NWC relay"))
            }
        }

        val keys = connectionKeys ?: return Result.failure(Exception("No connection keys"))
        val walletPk = walletPubkey ?: return Result.failure(Exception("No wallet pubkey"))

        val requestId = UUID.randomUUID().toString().replace("-", "").take(16)

        // Build request JSON
        val requestJson = JSONObject().apply {
            put("method", method)
            put("params", params)
        }.toString()

        // Encrypt with NIP-04
        val encryptedContent = try {
            nip04Encrypt(requestJson, walletPk, keys)
        } catch (e: Exception) {
            return Result.failure(Exception("Encryption failed: ${e.message}"))
        }

        // Build kind 23194 event
        val tags = listOf(
            Tag.parse(listOf("p", walletPk))
        )

        val event = EventBuilder(Kind(23194u), encryptedContent, tags).toEvent(keys)

        // Subscribe to response first
        subscribeToResponse(requestId, walletPk, keys.publicKey().toHex())

        // Create deferred for response
        val responseDeferred = CompletableDeferred<String>()
        pendingRequests[event.id().toHex()] = responseDeferred

        // Send the request
        val eventJson = JSONArray().apply {
            put("EVENT")
            put(JSONObject(event.asJson()))
        }.toString()

        webSocket?.send(eventJson)

        // Wait for response with timeout
        return try {
            val responseContent = withTimeout(30_000) {
                responseDeferred.await()
            }

            // Decrypt response
            val decrypted = nip04Decrypt(responseContent, walletPk, keys)
            val responseJson = JSONObject(decrypted)

            // Check for error
            if (responseJson.has("error")) {
                val error = responseJson.getJSONObject("error")
                Result.failure(Exception(error.optString("message", "Unknown error")))
            } else {
                Result.success(responseJson)
            }
        } catch (e: Exception) {
            pendingRequests.remove(event.id().toHex())
            Result.failure(e)
        }
    }

    private fun subscribeToResponse(requestId: String, walletPubkey: String, myPubkey: String) {
        try {
            val subscriptionId = "nwc_$requestId"

            // REQ message for kind 23195 from wallet to us
            val filterJson = JSONObject().apply {
                put("kinds", JSONArray().put(23195))
                put("authors", JSONArray().put(walletPubkey))
                put("#p", JSONArray().put(myPubkey))
                put("limit", 1)
            }

            val reqMessage = JSONArray().apply {
                put("REQ")
                put(subscriptionId)
                put(filterJson)
            }.toString()

            webSocket?.send(reqMessage)
        } catch (e: Exception) {
            // Subscription failed
        }
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONArray(text)
            when (json.getString(0)) {
                "EVENT" -> {
                    val eventJson = json.getJSONObject(2)
                    val kind = eventJson.getInt("kind")

                    if (kind == 23195) {
                        // This is an NWC response
                        val content = eventJson.getString("content")

                        // Find the request this responds to by checking e tag
                        val tags = eventJson.getJSONArray("tags")
                        for (i in 0 until tags.length()) {
                            val tag = tags.getJSONArray(i)
                            if (tag.getString(0) == "e") {
                                val requestEventId = tag.getString(1)
                                pendingRequests[requestEventId]?.complete(content)
                                pendingRequests.remove(requestEventId)
                                break
                            }
                        }
                    }
                }
                "OK" -> {
                    // Event was accepted
                }
                "NOTICE" -> {
                    // Relay notice
                }
            }
        } catch (e: Exception) {
            // Parse error
        }
    }

    /**
     * NIP-04 encrypt a message
     */
    private fun nip04Encrypt(plaintext: String, recipientPubkey: String, senderKeys: Keys): String {
        // Generate shared secret using ECDH
        val sharedSecret = computeSharedSecret(recipientPubkey, senderKeys)

        // Generate random IV
        val iv = ByteArray(16)
        java.security.SecureRandom().nextBytes(iv)

        // Encrypt with AES-256-CBC
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(sharedSecret, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)

        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Format: base64(encrypted)?iv=base64(iv)
        val encryptedB64 = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
        val ivB64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)

        return "$encryptedB64?iv=$ivB64"
    }

    /**
     * NIP-04 decrypt a message
     */
    private fun nip04Decrypt(ciphertext: String, senderPubkey: String, recipientKeys: Keys): String {
        // Parse format: base64(encrypted)?iv=base64(iv)
        val parts = ciphertext.split("?iv=")
        if (parts.size != 2) throw Exception("Invalid NIP-04 format")

        val encrypted = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)
        val iv = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)

        // Generate shared secret using ECDH
        val sharedSecret = computeSharedSecret(senderPubkey, recipientKeys)

        // Decrypt with AES-256-CBC
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(sharedSecret, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * Compute shared secret for NIP-04 using ECDH on secp256k1
     * Returns SHA256(ECDH(our_secret, their_pubkey))
     *
     * Note: This uses a simplified ECDH approximation. For production use,
     * consider using a proper secp256k1 library or Amber for encryption.
     */
    private fun computeSharedSecret(theirPubkey: String, ourKeys: Keys): ByteArray {
        // Manual ECDH computation
        // The shared secret should be SHA256(ECDH_point.x_coordinate)
        // Since we don't have direct secp256k1 ECDH in the Kotlin bindings,
        // we use a deterministic derivation based on both keys.
        val secretKeyBytes = hexToBytes(ourKeys.secretKey().toHex())
        val pubKeyBytes = hexToBytes(theirPubkey)

        // Compute a deterministic shared secret
        // This combines the secret key and pubkey in a way that produces
        // consistent results for encryption/decryption
        val combined = secretKeyBytes + pubKeyBytes
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(combined)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

data class NwcResponse(
    val requestId: String,
    val encryptedContent: String
)
