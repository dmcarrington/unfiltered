package com.nostr.unfiltered.repository

import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.NostrEvent
import com.nostr.unfiltered.nostr.models.RelayDirection
import com.nostr.unfiltered.nostr.models.RelayTopology
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import rust.nostr.protocol.Event
import rust.nostr.protocol.EventBuilder
import rust.nostr.protocol.Filter
import rust.nostr.protocol.Kind
import rust.nostr.protocol.PublicKey
import rust.nostr.protocol.Tag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the user's NIP-65 (kind 10002) relay list.
 *
 * - [loadCurrent] subscribes to the user's own kind 10002 and waits for
 *   the first event, then parses it into a [RelayTopology].
 * - [publish] signs and publishes a kind 10002 from a [RelayTopology].
 * - [topology] is a [StateFlow] holding the latest known topology.
 *
 * Lifecycle:
 *   1. On every app start, call [loadCurrent] to populate [topology] from
 *      what's already on the relays.
 *   2. If no kind 10002 exists, call [ensurePublishedDefault] to seed
 *      [RelayTopology.DEFAULT] so writes have a destination.
 *
 * Note: like [com.nostr.unfiltered.repository.MuteListRepository], this class
 * is independent of the feed repository so it can be observed without
 * triggering feed loads.
 */
@Singleton
class RelayListRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val keyManager: KeyManager
) {

    private val _topology = MutableStateFlow(RelayTopology.EMPTY)
    val topology: StateFlow<RelayTopology> = _topology.asStateFlow()

    private val _publishState = MutableStateFlow<PublishState>(PublishState.Idle)
    val publishState: StateFlow<PublishState> = _publishState.asStateFlow()

    private val _hasPublishedOnce = MutableStateFlow(false)
    val hasPublishedOnce: StateFlow<Boolean> = _hasPublishedOnce.asStateFlow()

    /**
     * Set to true after we attempted to publish the default. Prevents
     * re-attempting every app launch for Amber users (who can't publish
     * without external signing) while still allowing manual retries via
     * the Settings UI.
     */
    private var hasAttemptedDefaultPublish = false

    /**
     * Subscribe to my own kind 10002 and resolve the topology. Suspends
     * until an event arrives or [timeoutMs] elapses (returns false).
     *
     * If no event is found within the timeout, [_topology] stays at its
     * current value. Caller may then call [ensurePublishedDefault] to seed.
     */
    suspend fun loadCurrent(timeoutMs: Long = 4000L): Boolean {
        val pubkeyHex = keyManager.getPublicKeyHex() ?: return false

        val subId = "relay_list_${System.currentTimeMillis()}"

        // Subscribe to my own kind 10002 — newest only.
        val filter = Filter()
            .kind(Kind(10002u))
            .author(PublicKey.fromHex(pubkeyHex))
            .limit(1u)

        nostrClient.subscribe(subId, listOf(filter))

        val event: NostrEvent? = withTimeoutOrNull(timeoutMs) {
            nostrClient.events
                .filter { it.subscriptionId == subId }
                .first()
        }

        nostrClient.unsubscribe(subId)

        if (event != null) {
            _topology.value = parseEvent(event.event)
            _hasPublishedOnce.value = true
            return true
        }
        return false
    }

    /**
     * If [loadCurrent] did not find an existing kind 10002, publish the
     * [RelayTopology.DEFAULT] list so we have somewhere to write to.
     *
     * Idempotent — only publishes once per app install (tracked via
     * [_hasPublishedOnce]).
     */
    suspend fun ensurePublishedDefault() {
        if (_hasPublishedOnce.value) return
        if (hasAttemptedDefaultPublish) return
        if (_topology.value != RelayTopology.EMPTY) return
        hasAttemptedDefaultPublish = true
        publish(RelayTopology.DEFAULT)
    }

    /**
     * Sign and publish a [RelayTopology] as kind 10002.
     *
     * Uses the local nsec if available; for Amber users this returns
     * [PublishState.NeedExternalSigner] so the caller can present an
     * Amber sign-event intent (out of scope for this PR — we'll prompt
     * via Settings UI when needed).
     */
    fun publish(topology: RelayTopology): PublishState {
        val pubkeyHex = keyManager.getPublicKeyHex()
            ?: return PublishState.Failed("not signed in").also { _publishState.value = it }

        // Build tags per NIP-65: ["r", url] or ["r", url, "read"|"write"].
        // We track both the typed [Tag] form (for EventBuilder) and the
        // raw List<String> form (for building the Amber unsigned-event
        // JSON envelope — Tag.asJson() isn't available in this binding).
        val typedTags = mutableListOf<Tag>()
        val rawTags = mutableListOf<List<String>>()
        val readOnly = topology.readRelays - topology.writeRelays
        val writeOnly = topology.writeRelays - topology.readRelays
        val both = topology.readWriteRelays

        for (url in both.sorted()) {
            val parts = listOf("r", url)
            typedTags.add(Tag.parse(parts))
            rawTags.add(parts)
        }
        for (url in readOnly.sorted()) {
            val parts = listOf("r", url, "read")
            typedTags.add(Tag.parse(parts))
            rawTags.add(parts)
        }
        for (url in writeOnly.sorted()) {
            val parts = listOf("r", url, "write")
            typedTags.add(Tag.parse(parts))
            rawTags.add(parts)
        }

        val keys = keyManager.getKeys()
        if (keys == null) {
            // Amber path — caller must invoke Amber sign-event flow.
            return PublishState.NeedExternalSigner(
                pubkeyHex = pubkeyHex,
                unsignedEventJson = buildUnsignedEventJson(pubkeyHex, rawTags)
            )
        }

        return try {
            val event = EventBuilder(Kind(10002u), "", typedTags).toEvent(keys)
            nostrClient.publish(event)
            _topology.value = topology
            _hasPublishedOnce.value = true
            PublishState.Published.also { _publishState.value = it }
        } catch (e: Exception) {
            PublishState.Failed(e.message ?: "publish failed")
                .also { _publishState.value = it }
        }
    }

    /**
     * Apply an Amber-signed event (after the user has returned from Amber)
     * and update local state. Caller is responsible for verifying the
     * signed event actually came back from Amber.
     */
    fun applyExternalSignedEvent(signedEventJson: String): PublishState {
        return try {
            val ev = Event.fromJson(signedEventJson)
            // Update topology from the signed event so UI reflects it.
            _topology.value = parseEvent(ev)
            _hasPublishedOnce.value = true
            PublishState.Published.also { _publishState.value = it }
        } catch (e: Exception) {
            PublishState.Failed(e.message ?: "bad signed event")
                .also { _publishState.value = it }
        }
    }

    /**
     * Build the unsigned event JSON for an Amber signer to sign.
     * Mirrors the structure used by [EventBuilder] but stays as a String
     * (Amber wants the JSON envelope, not the typed Event).
     */
    private fun buildUnsignedEventJson(pubkeyHex: String, rawTags: List<List<String>>): String {
        val tagsJson = JSONArray()
        for (tagParts in rawTags) {
            val tagJson = JSONArray()
            for (part in tagParts) tagJson.put(part)
            tagsJson.put(tagJson)
        }
        return JSONObject().apply {
            put("kind", 10002)
            put("pubkey", pubkeyHex)
            put("created_at", System.currentTimeMillis() / 1000)
            put("tags", tagsJson)
            put("content", "")
        }.toString()
    }

    /**
     * Parse a kind 10002 event into a [RelayTopology].
     */
    private fun parseEvent(event: Event): RelayTopology {
        val read = mutableSetOf<String>()
        val write = mutableSetOf<String>()

        // event.asJson() returns the standard Nostr event JSON:
        //   {"id":..,"pubkey":..,"created_at":..,"kind":10002,
        //    "tags":[["r","wss://.."],..],"content":"","sig":..}
        // We also accept the wire format as a defensive fallback
        // (["EVENT", subId, event]).
        val tagsJson: JSONArray? = try {
            val raw = event.asJson()
            if (raw.trimStart().startsWith("[")) {
                JSONArray(raw).getJSONArray(2)
            } else {
                JSONObject(raw).optJSONArray("tags")
            }
        } catch (e: Exception) {
            null
        }

        if (tagsJson != null) {
            for (i in 0 until tagsJson.length()) {
                val tag = tagsJson.optJSONArray(i) ?: continue
                if (tag.length() < 2) continue
                if (tag.getString(0) != "r") continue
                val url = RelayTopology.normalise(tag.getString(1))
                val marker = if (tag.length() >= 3) tag.getString(2) else null
                when (RelayDirection.parse(marker)) {
                    RelayDirection.READ -> {
                        read.add(url)
                        // NIP-65: "read" means ONLY read — do NOT add to write.
                    }
                    RelayDirection.WRITE -> {
                        write.add(url)
                    }
                    RelayDirection.BOTH -> {
                        read.add(url)
                        write.add(url)
                    }
                }
            }
        }

        return RelayTopology(readRelays = read, writeRelays = write)
    }

    sealed class PublishState {
        object Idle : PublishState()
        object Published : PublishState()
        data class NeedExternalSigner(
            val pubkeyHex: String,
            val unsignedEventJson: String
        ) : PublishState()
        data class Failed(val message: String) : PublishState()
    }
}