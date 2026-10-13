package com.nostr.unfiltered.nostr.models

/**
 * NIP-65 (Relay List Metadata) topology.
 *
 * A user's relay list (kind 10002) declares three sets of relays:
 *  - [readRelays]: relays the user reads from (subscribes for their own feed)
 *  - [writeRelays]: relays the user publishes to (their outbox)
 *  - readRelays ∩ writeRelays are relays that are both.
 *
 * NIP-65 also enables "inbox/outbox" routing — fetching each follow's
 * kind 10002 to know which relays they write to — but that's out of scope
 * for this PR. We support the local topology only.
 *
 * Relays are kept normalised (no trailing slash, wss:// scheme).
 */
data class RelayTopology(
    val readRelays: Set<String>,
    val writeRelays: Set<String>
) {

    /** Union of read + write. */
    val allRelays: Set<String>
        get() = readRelays + writeRelays

    /** Relays that are both read and write. */
    val readWriteRelays: Set<String>
        get() = readRelays intersect writeRelays

    fun isEmpty(): Boolean = readRelays.isEmpty() && writeRelays.isEmpty()

    fun isRead(url: String): Boolean = normalise(url) in readRelays
    fun isWrite(url: String): Boolean = normalise(url) in writeRelays

    companion object {
        /**
         * Empty topology — nothing connected. Caller should fall back to
         * [default].
         */
        val EMPTY = RelayTopology(emptySet(), emptySet())

        /**
         * The app's default topology. Used before the user has published a
         * kind 10002 and as a one-time first-publish migration.
         */
        val DEFAULT: RelayTopology = RelayTopology(
            readRelays = setOf(
                "wss://relay.damus.io",
                "wss://relay.primal.net",
                "wss://nos.lol"
            ),
            writeRelays = setOf(
                "wss://relay.damus.io",
                "wss://relay.primal.net",
                "wss://nos.lol"
            )
        )

        /**
         * Normalise a relay URL — trim, lowercase scheme/host, strip trailing
         * slash, force wss://.
         */
        fun normalise(raw: String): String {
            var u = raw.trim()
            if (!u.startsWith("wss://") && !u.startsWith("ws://")) {
                u = "wss://$u"
            }
            u = u.trimEnd('/')
            // Lowercase scheme and host only — path/query are case-sensitive
            // for some relays (rare). Keep this simple: lowercase the prefix.
            val schemeEnd = u.indexOf("://") + 3
            val schemeAndHost = u.substring(0, schemeEnd).lowercase()
            return schemeAndHost + u.substring(schemeEnd)
        }

        /**
         * Build a topology from a kind 10002 event's tags, OR from a flat
         * list of relay URLs (treated as read+write).
         */
        fun fromRelays(relays: Collection<String>): RelayTopology {
            val norm = relays.map { normalise(it) }.toSet()
            return RelayTopology(readRelays = norm, writeRelays = norm)
        }
    }
}

/**
 * Direction marker in a kind 10002 `r` tag's third element.
 * NIP-65 spec: "read" or "write" — if absent, the relay is both.
 */
enum class RelayDirection {
    READ,
    WRITE,
    BOTH;

    companion object {
        fun parse(raw: String?): RelayDirection = when (raw?.lowercase()) {
            "read" -> READ
            "write" -> WRITE
            null, "", "both" -> BOTH
            else -> BOTH // unknown marker treated as both (forward-compatible)
        }
    }
}