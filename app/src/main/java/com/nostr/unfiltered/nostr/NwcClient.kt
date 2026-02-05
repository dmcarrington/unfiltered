package com.nostr.unfiltered.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import rust.nostr.protocol.EventBuilder
import rust.nostr.protocol.Kind
import rust.nostr.protocol.Keys
import rust.nostr.protocol.PublicKey
import rust.nostr.protocol.SecretKey
import rust.nostr.protocol.Tag
import rust.nostr.sdk.NostrSigner
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * WebSocket client for NWC (Nostr Wallet Connect) relay communication.
 * Handles NIP-47 protocol for wallet operations.
 */
@Singleton
class NwcClient @Inject constructor(
    private val nwcService: NwcService
) {
    private var webSocket: WebSocket? = null
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private var isConnected = false
    private var connectionKeys: Keys? = null
    private var signer: NostrSigner? = null
    private var walletPubkey: String? = null

    /**
     * Connect to the NWC relay
     */
    suspend fun connect(): Boolean {
        val connection = nwcService.getConnection() ?: return false

        // Parse the secret to create ephemeral keys for this connection
        try {
            val secretKey = SecretKey.fromHex(connection.secret)
            val keys = Keys(secretKey)
            connectionKeys = keys
            signer = NostrSigner.keys(keys)
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
        signer = null
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
        val nwcSigner = signer ?: return Result.failure(Exception("No signer"))
        val walletPk = walletPubkey ?: return Result.failure(Exception("No wallet pubkey"))

        val requestId = UUID.randomUUID().toString().replace("-", "").take(16)

        // Build request JSON
        val requestJson = JSONObject().apply {
            put("method", method)
            put("params", params)
        }.toString()

        // Encrypt with NIP-04 using rust-nostr's proper ECDH
        val walletPublicKey = try {
            PublicKey.fromHex(walletPk)
        } catch (e: Exception) {
            return Result.failure(Exception("Invalid wallet pubkey: ${e.message}"))
        }

        val encryptedContent = try {
            nwcSigner.nip04Encrypt(walletPublicKey, requestJson)
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

            // Decrypt response using rust-nostr's proper ECDH
            val decrypted = nwcSigner.nip04Decrypt(walletPublicKey, responseContent)
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

}
