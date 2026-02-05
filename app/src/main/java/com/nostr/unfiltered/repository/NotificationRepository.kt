package com.nostr.unfiltered.repository

import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.MetadataCache
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.NostrEvent
import com.nostr.unfiltered.nostr.NotificationService
import com.nostr.unfiltered.nostr.models.Notification
import com.nostr.unfiltered.nostr.models.NotificationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import rust.nostr.protocol.Event
import rust.nostr.protocol.Filter
import rust.nostr.protocol.Kind
import rust.nostr.protocol.PublicKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val notificationService: NotificationService,
    private val metadataCache: MetadataCache,
    private val keyManager: KeyManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track user's own post IDs for filtering inbound notifications
    private val myPostIds = mutableSetOf<String>()

    private var isInitialized = false

    fun initialize() {
        if (isInitialized) return
        isInitialized = true

        try {
            scope.launch {
                try {
                    notificationService.initialize()
                } catch (e: Exception) {
                    android.util.Log.e("NotificationRepo", "Failed to initialize service", e)
                }
            }
            observeEvents()
            subscribeToNotifications()
        } catch (e: Exception) {
            android.util.Log.e("NotificationRepo", "Failed to initialize", e)
        }
    }

    private fun observeEvents() {
        scope.launch {
            nostrClient.events.collect { nostrEvent ->
                handleEvent(nostrEvent)
            }
        }
    }

    private fun handleEvent(nostrEvent: NostrEvent) {
        val event = nostrEvent.event
        val kind = event.kind().asU16().toInt()

        when (kind) {
            7 -> handleReactionEvent(event)
            9735 -> handleZapReceiptEvent(event)
            1 -> handlePotentialMention(event)
            20 -> trackMyPost(event)
        }
    }

    private fun trackMyPost(event: Event) {
        val myPubkey = keyManager.getPublicKeyHex() ?: return
        if (event.author().toHex() == myPubkey) {
            myPostIds.add(event.id().toHex())
        }
    }

    private fun handleReactionEvent(event: Event) {
        val tags = parseTagsFromEvent(event)
        val targetEventId = tags.find { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
        val targetAuthorPubkey = tags.find { it.size >= 2 && it[0] == "p" }?.get(1)

        val myPubkey = keyManager.getPublicKeyHex() ?: return

        // Check if this reaction is on one of our posts
        if (targetAuthorPubkey == myPubkey || myPostIds.contains(targetEventId)) {
            val actorPubkey = event.author().toHex()
            if (actorPubkey == myPubkey) return // Don't notify for own reactions

            val metadata = metadataCache.get(actorPubkey)

            scope.launch {
                notificationService.addNotification(
                    Notification(
                        id = event.id().toHex(),
                        type = NotificationType.REACTION,
                        timestamp = event.createdAt().asSecs().toLong(),
                        actorPubkey = actorPubkey,
                        actorName = metadata?.bestName,
                        actorAvatar = metadata?.picture,
                        targetPostId = targetEventId
                    )
                )
            }
        }
    }

    private fun handleZapReceiptEvent(event: Event) {
        // Kind 9735 zap receipt parsing
        val tags = parseTagsFromEvent(event)
        val targetEventId = tags.find { it.size >= 2 && it[0] == "e" }?.get(1)
        val targetPubkey = tags.find { it.size >= 2 && it[0] == "p" }?.get(1)

        val myPubkey = keyManager.getPublicKeyHex() ?: return
        if (targetPubkey != myPubkey) return

        // Extract zapper pubkey from description tag (contains the zap request event)
        val descriptionTag = tags.find { it.size >= 2 && it[0] == "description" }?.get(1)
        val zapperPubkey = parseZapperFromDescription(descriptionTag)
        if (zapperPubkey == null || zapperPubkey == myPubkey) return

        // Extract amount from bolt11 tag
        val bolt11 = tags.find { it.size >= 2 && it[0] == "bolt11" }?.get(1)
        val amount = parseBolt11Amount(bolt11)

        val metadata = metadataCache.get(zapperPubkey)

        scope.launch {
            notificationService.addNotification(
                Notification(
                    id = event.id().toHex(),
                    type = NotificationType.ZAP,
                    timestamp = event.createdAt().asSecs().toLong(),
                    actorPubkey = zapperPubkey,
                    actorName = metadata?.bestName,
                    actorAvatar = metadata?.picture,
                    targetPostId = targetEventId ?: "",
                    zapAmount = amount
                )
            )
        }
    }

    private fun handlePotentialMention(event: Event) {
        val myPubkey = keyManager.getPublicKeyHex() ?: return
        val authorPubkey = event.author().toHex()
        if (authorPubkey == myPubkey) return

        val tags = parseTagsFromEvent(event)
        val mentionsPubkeys = tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }

        if (mentionsPubkeys.contains(myPubkey)) {
            val metadata = metadataCache.get(authorPubkey)

            scope.launch {
                notificationService.addNotification(
                    Notification(
                        id = event.id().toHex(),
                        type = NotificationType.MENTION,
                        timestamp = event.createdAt().asSecs().toLong(),
                        actorPubkey = authorPubkey,
                        actorName = metadata?.bestName,
                        actorAvatar = metadata?.picture,
                        targetPostId = event.id().toHex()
                    )
                )
            }
        }
    }

    private fun subscribeToNotifications() {
        val myPubkey = keyManager.getPublicKeyHex() ?: return

        try {
            val pk = PublicKey.fromHex(myPubkey)

            // Reactions where we're tagged in the p tag
            val reactionsFilter = Filter()
                .kind(Kind(7u))
                .pubkeys(listOf(pk))
                .limit(100u)

            // Zap receipts where we're the recipient
            val zapsFilter = Filter()
                .kind(Kind(9735u))
                .pubkeys(listOf(pk))
                .limit(100u)

            // Mentions - notes that tag us
            val mentionsFilter = Filter()
                .kind(Kind(1u))
                .pubkeys(listOf(pk))
                .limit(50u)

            nostrClient.subscribe("notifications", listOf(reactionsFilter, zapsFilter, mentionsFilter))
        } catch (e: Exception) {
            // Handle subscription error
        }
    }

    private fun parseTagsFromEvent(event: Event): List<List<String>> {
        return try {
            val eventJson = JSONObject(event.asJson())
            val tagsArray = eventJson.optJSONArray("tags") ?: return emptyList()

            val result = mutableListOf<List<String>>()
            for (i in 0 until tagsArray.length()) {
                val tagArray = tagsArray.optJSONArray(i) ?: continue
                val tag = mutableListOf<String>()
                for (j in 0 until tagArray.length()) {
                    tag.add(tagArray.optString(j, ""))
                }
                result.add(tag)
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Parse the zapper's pubkey from the description tag.
     * The description contains the zap request event JSON.
     */
    private fun parseZapperFromDescription(description: String?): String? {
        if (description == null) return null
        return try {
            val zapRequest = JSONObject(description)
            zapRequest.optString("pubkey", null)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse the amount in millisatoshis from a bolt11 invoice.
     * Returns amount in satoshis.
     */
    private fun parseBolt11Amount(bolt11: String?): Long? {
        if (bolt11 == null) return null
        return try {
            // BOLT11 format: ln[network][amount][multiplier]...
            // Amount is after "ln" and network prefix, before the rest
            val lower = bolt11.lowercase()
            val amountRegex = Regex("ln(?:bc|tb|tbs|bcrt)?(\\d+)([munp])?")
            val match = amountRegex.find(lower) ?: return null

            val amountStr = match.groupValues[1]
            val multiplier = match.groupValues.getOrNull(2) ?: ""

            val amountNum = amountStr.toLongOrNull() ?: return null

            // Convert to millisatoshis based on multiplier, then to satoshis
            val msats = when (multiplier) {
                "m" -> amountNum * 100_000_000L  // milli-bitcoin = 0.001 BTC
                "u" -> amountNum * 100_000L      // micro-bitcoin = 0.000001 BTC
                "n" -> amountNum * 100L          // nano-bitcoin = 0.000000001 BTC
                "p" -> amountNum / 10L           // pico-bitcoin = 0.000000000001 BTC
                else -> amountNum * 100_000_000_000L // No multiplier = full BTC
            }

            msats / 1000 // Convert millisatoshis to satoshis
        } catch (e: Exception) {
            null
        }
    }
}
