package com.nostr.unfiltered.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import rust.nostr.protocol.Event
import rust.nostr.protocol.Filter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages WebSocket connections to Nostr relays.
 * Handles subscriptions, event publishing, and connection state.
 */
@Singleton
class NostrClient @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WebSocket
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val relayConnections = ConcurrentHashMap<String, RelayConnection>()
    private val activeSubscriptions = ConcurrentHashMap<String, List<Filter>>()

    // Event stream - emits all received events
    private val _events = MutableSharedFlow<NostrEvent>(replay = 0, extraBufferCapacity = 1000)
    val events: SharedFlow<NostrEvent> = _events.asSharedFlow()

    // EOSE stream - emits subscription IDs when end-of-stored-events is received
    private val _eoseEvents = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 100)
    val eoseEvents: SharedFlow<String> = _eoseEvents.asSharedFlow()

    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var reconnectJob: Job? = null

    // Sequential message processing to preserve EVENT/EOSE ordering
    private data class RelayMessage(val relayUrl: String, val text: String)
    private val messageChannel = Channel<RelayMessage>(Channel.UNLIMITED)

    init {
        // Process messages sequentially to guarantee EVENTs are emitted before their EOSE
        scope.launch {
            for (msg in messageChannel) {
                processMessage(msg.relayUrl, msg.text)
            }
        }
    }

    // Relay status map
    private val _relayStatus = MutableStateFlow<Map<String, RelayStatus>>(emptyMap())
    val relayStatus: StateFlow<Map<String, RelayStatus>> = _relayStatus.asStateFlow()

    val defaultRelays = listOf(
        "wss://relay.damus.io",
        "wss://relay.primal.net",
        "wss://nos.lol"
    )

    /**
     * Connect to a list of relays
     */
    suspend fun connect(relayUrls: List<String> = defaultRelays) {
        _connectionState.value = ConnectionState.Connecting

        relayUrls.forEach { url ->
            if (!relayConnections.containsKey(url)) {
                connectToRelay(url)
            }
        }

        updateConnectionState()
        startAutoReconnect()
    }

    private fun startAutoReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            while (true) {
                delay(60_000L)
                val hasDisconnected = _relayStatus.value.any { (_, status) ->
                    status is RelayStatus.Disconnected || status is RelayStatus.Error
                }
                if (hasDisconnected) {
                    reconnect()
                }
            }
        }
    }

    /**
     * Connect to a single relay
     */
    private fun connectToRelay(url: String) {
        val normalizedUrl = normalizeRelayUrl(url)

        val request = Request.Builder()
            .url(normalizedUrl)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                updateRelayStatus(normalizedUrl, RelayStatus.Connected)
                updateConnectionState()

                // Resubscribe to active subscriptions
                activeSubscriptions.forEach { (subId, filters) ->
                    sendSubscription(webSocket, subId, filters)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(normalizedUrl, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                // Remove the closed connection so it can be reconnected
                relayConnections.remove(normalizedUrl)
                updateRelayStatus(normalizedUrl, RelayStatus.Disconnected)
                updateConnectionState()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Remove the failed connection so it can be reconnected
                relayConnections.remove(normalizedUrl)
                updateRelayStatus(normalizedUrl, RelayStatus.Error(t.message ?: "Unknown error"))
                updateConnectionState()
            }
        }

        val webSocket = httpClient.newWebSocket(request, listener)
        relayConnections[normalizedUrl] = RelayConnection(normalizedUrl, webSocket)
        updateRelayStatus(normalizedUrl, RelayStatus.Connecting)
    }

    /**
     * Reconnect to any relays that are disconnected or in error state
     */
    suspend fun reconnect(relayUrls: List<String> = defaultRelays) {
        val currentStatus = _relayStatus.value
        val needsReconnect = relayUrls.filter { url ->
            val normalizedUrl = normalizeRelayUrl(url)
            val status = currentStatus[normalizedUrl]
            // Reconnect if not connected or not currently connecting
            status != RelayStatus.Connected && status != RelayStatus.Connecting
        }

        if (needsReconnect.isNotEmpty()) {
            connect(needsReconnect)
        }
    }

    /**
     * Disconnect from all relays
     */
    fun disconnect() {
        relayConnections.values.forEach { connection ->
            connection.webSocket.close(1000, "Client disconnect")
        }
        relayConnections.clear()
        _connectionState.value = ConnectionState.Disconnected
        _relayStatus.value = emptyMap()
    }

    /**
     * Disconnect from a specific relay
     */
    fun disconnectRelay(url: String) {
        val normalizedUrl = normalizeRelayUrl(url)
        relayConnections.remove(normalizedUrl)?.webSocket?.close(1000, "Client disconnect")
        _relayStatus.value = _relayStatus.value - normalizedUrl
        updateConnectionState()
    }

    /**
     * Subscribe to events matching filters
     */
    fun subscribe(subscriptionId: String, filters: List<Filter>) {
        activeSubscriptions[subscriptionId] = filters

        relayConnections.values.forEach { connection ->
            sendSubscription(connection.webSocket, subscriptionId, filters)
        }
    }

    /**
     * Subscribe with a raw JSON filter (e.g., for NIP-50 search filters).
     * Optionally target specific relays instead of all connected ones.
     */
    fun subscribeRaw(subscriptionId: String, filterJson: JSONObject, relayUrls: List<String>? = null) {
        val message = JSONArray().apply {
            put("REQ")
            put(subscriptionId)
            put(filterJson)
        }.toString()

        val targets = if (relayUrls != null) {
            relayUrls.mapNotNull { relayConnections[normalizeRelayUrl(it)] }
        } else {
            relayConnections.values.toList()
        }

        targets.forEach { connection ->
            connection.webSocket.send(message)
        }
    }

    /**
     * Close a subscription
     */
    fun unsubscribe(subscriptionId: String) {
        activeSubscriptions.remove(subscriptionId)

        val closeMessage = JSONArray().apply {
            put("CLOSE")
            put(subscriptionId)
        }.toString()

        relayConnections.values.forEach { connection ->
            connection.webSocket.send(closeMessage)
        }
    }

    /**
     * Publish an event to all connected relays
     */
    fun publish(event: Event): Boolean {
        val eventJson = event.asJson()
        val message = JSONArray().apply {
            put("EVENT")
            put(JSONObject(eventJson))
        }.toString()

        var sentToAny = false
        relayConnections.values.forEach { connection ->
            if (_relayStatus.value[connection.url] == RelayStatus.Connected) {
                if (connection.webSocket.send(message)) {
                    sentToAny = true
                }
            }
        }

        return sentToAny
    }

    /**
     * Ensure a specific relay is connected, waiting up to [timeoutMs] for it to reach Connected status.
     * Returns true if the relay is connected within the timeout.
     */
    suspend fun ensureRelayConnected(relayUrl: String, timeoutMs: Long = 5000): Boolean {
        val normalizedUrl = normalizeRelayUrl(relayUrl)

        // Already connected
        if (_relayStatus.value[normalizedUrl] == RelayStatus.Connected) return true

        // Start connecting if not already
        if (!relayConnections.containsKey(normalizedUrl)) {
            connectToRelay(normalizedUrl)
        }

        // Wait for Connected status
        return withTimeoutOrNull(timeoutMs) {
            _relayStatus.first { statuses ->
                statuses[normalizedUrl] == RelayStatus.Connected
            }
            true
        } ?: false
    }

    /**
     * Get list of currently connected relay URLs
     */
    fun getConnectedRelays(): List<String> {
        return _relayStatus.value
            .filter { it.value == RelayStatus.Connected }
            .keys
            .toList()
    }

    // ==================== Private Helpers ====================

    private fun sendSubscription(webSocket: WebSocket, subscriptionId: String, filters: List<Filter>) {
        val message = JSONArray().apply {
            put("REQ")
            put(subscriptionId)
            filters.forEach { filter ->
                put(JSONObject(filter.asJson()))
            }
        }.toString()

        webSocket.send(message)
    }

    private fun handleMessage(relayUrl: String, message: String) {
        messageChannel.trySend(RelayMessage(relayUrl, message))
    }

    private suspend fun processMessage(relayUrl: String, message: String) {
        try {
            val json = JSONArray(message)
            when (json.getString(0)) {
                "EVENT" -> {
                    val subscriptionId = json.getString(1)
                    val eventJson = json.getJSONObject(2).toString()
                    val event = Event.fromJson(eventJson)
                    _events.emit(NostrEvent(event, relayUrl, subscriptionId))
                }
                "EOSE" -> {
                    val subscriptionId = json.getString(1)
                    _eoseEvents.emit(subscriptionId)
                }
                "OK" -> {
                    val eventId = json.getString(1)
                    val accepted = json.getBoolean(2)
                    val msg = if (json.length() > 3) json.getString(3) else ""
                    // Event publish confirmation
                }
                "NOTICE" -> {
                    val notice = json.getString(1)
                    // Relay notice
                }
                "CLOSED" -> {
                    val subscriptionId = json.getString(1)
                    val reason = if (json.length() > 2) json.getString(2) else ""
                    // Subscription closed by relay
                }
            }
        } catch (e: Exception) {
            // Parsing error
        }
    }

    private fun updateRelayStatus(url: String, status: RelayStatus) {
        _relayStatus.value = _relayStatus.value + (url to status)
    }

    private fun updateConnectionState() {
        val statuses = _relayStatus.value.values
        _connectionState.value = when {
            statuses.any { it == RelayStatus.Connected } -> ConnectionState.Connected
            statuses.any { it == RelayStatus.Connecting } -> ConnectionState.Connecting
            statuses.all { it is RelayStatus.Error } -> ConnectionState.Error
            else -> ConnectionState.Disconnected
        }
    }

    private fun normalizeRelayUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("wss://") && !normalized.startsWith("ws://")) {
            normalized = "wss://$normalized"
        }
        return normalized.trimEnd('/')
    }

    private data class RelayConnection(
        val url: String,
        val webSocket: WebSocket
    )

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        object Error : ConnectionState()
    }

    sealed class RelayStatus {
        object Connecting : RelayStatus()
        object Connected : RelayStatus()
        object Disconnected : RelayStatus()
        data class Error(val message: String) : RelayStatus()
    }
}

/**
 * Wrapper for received Nostr events with metadata
 */
data class NostrEvent(
    val event: Event,
    val relayUrl: String,
    val subscriptionId: String
)
