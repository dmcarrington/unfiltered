package com.nostr.unfiltered.nostr

import android.util.Log
import com.nostr.unfiltered.repository.ZapDatabase
import com.nostr.unfiltered.repository.VelocityEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nostr.event.client.Event
import nostr.event.client.Filter
import nostr.event.client.RelayPool
import java.time.Instant

/**
 * ZapBoost: Real-time Lightning zap velocity tracking
 * 
 * Listens to NIP-57 zap receipts (kind 9735) on public relays,
 * aggregates by e-tag (referenced post), and calculates rolling
 * velocity metrics (sats/hour, zaps/hour).
 */
class ZapVelocityService(
    private val relayPool: RelayPool,
    private val database: ZapDatabase
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()
    
    companion object {
        private const val TAG = "ZapVelocityService"
        private const val VELOCITY_WINDOW_HOURS = 1
        private const val CACHE_UPDATE_INTERVAL_MS = 300_000L // 5 minutes
    }
    
    /**
     * Start monitoring public zap receipts
     * Subscribes to kind 9735 events on connected relays
     */
    fun startMonitoring() {
        if (_isMonitoring.value) {
            Log.w(TAG, "Already monitoring, skipping")
            return
        }
        
        serviceScope.launch {
            try {
                val oneHourAgo = Instant.now().minusSeconds(3600L)
                
                val filter = Filter(
                    kinds = listOf(9735), // Zap receipt
                    since = oneHourAgo
                )
                
                relayPool.subscribe(listOf(filter)) { event ->
                    processZapReceipt(event)
                }
                
                _isMonitoring.value = true
                Log.i(TAG, "Started monitoring zap receipts")
                
                // Start cache update loop
                startCacheUpdater()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start monitoring", e)
                _isMonitoring.value = false
            }
        }
    }
    
    /**
     * Stop monitoring zap receipts
     */
    fun stopMonitoring() {
        _isMonitoring.value = false
        relayPool.unsubscribeAll()
        Log.i(TAG, "Stopped monitoring zap receipts")
    }
    
    private fun processZapReceipt(event: Event) {
        serviceScope.launch {
            try {
                // Extract e-tag (the post being zapped)
                val eTag = event.tags.find { it.identifier() != null }?.identifier()
                    ?: return@launch
                
                // Extract sats amount from zap receipt
                val amountSats = extractSatsFromZap(event)
                    ?: return@launch
                
                // Extract recipient (p-tag)
                val recipientNpub = event.tags.find { it.code() == "p" }?.identifier()
                    ?: return@launch
                
                // Extract sender (pubkey of zap receipt event)
                val senderNpub = event.pubKey
                
                val zapEntity = ZapEntity(
                    id = event.id,
                    postId = eTag,
                    recipientNpub = recipientNpub,
                    amountSats = amountSats,
                    timestamp = event.createdAt,
                    senderNpub = senderNpub
                )
                
                database.insertZap(zapEntity)
                Log.d(TAG, "Recorded zap: ${amountSats}sats to post $eTag")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing zap receipt", e)
            }
        }
    }
    
    private fun extractSatsFromZap(event: Event): Long? {
        // Try to extract amount from bolt11 invoice in zap receipt
        // Or from the 'amount' tag if present
        val amountTag = event.tags.find { it.code() == "amount" }?.identifier()
        if (amountTag != null) {
            return amountTag.toLongOrNull()
        }
        
        // Fallback: parse bolt11 invoice (complex, may need external lib)
        // For now, return null if amount not in tags
        return null
    }
    
    private fun startCacheUpdater() {
        serviceScope.launch {
            while (_isMonitoring.value) {
                try {
                    updateVelocityCache()
                    kotlinx.coroutines.delay(CACHE_UPDATE_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating velocity cache", e)
                }
            }
        }
    }
    
    private suspend fun updateVelocityCache() {
        val now = Instant.now()
        val oneHourAgo = now.minusSeconds(3600L)
        
        // Get all zaps in the last hour
        val recentZaps = database.getZapsSince(oneHourAgo)
        
        // Group by post and calculate velocity
        val velocityMap = recentZaps.groupBy { it.postId }.mapValues { (_, zaps) ->
            ZapVelocity(
                satsPerHour = zaps.sumOf { it.amountSats },
                zapsPerHour = zaps.size,
                lastUpdated = now
            )
        }
        
        // Update cache
        velocityMap.forEach { (postId, velocity) ->
            database.updateVelocityCache(
                VelocityEntity(
                    postId = postId,
                    satsPerHour = velocity.satsPerHour,
                    zapsPerHour = velocity.zapsPerHour,
                    lastUpdated = now
                )
            )
        }
        
        Log.d(TAG, "Updated velocity cache for ${velocityMap.size} posts")
    }
    
    /**
     * Get velocity for a specific post
     */
    suspend fun getVelocity(postId: String): ZapVelocity {
        return database.getVelocityForPost(postId)
            ?: ZapVelocity(0, 0, Instant.now())
    }
    
    /**
     * Get trending posts sorted by zap velocity
     */
    suspend fun getTrendingPosts(limit: Int = 50): List<TrendingPost> {
        return database.getTrendingPosts(limit).map { entity ->
            TrendingPost(
                postId = entity.postId,
                satsPerHour = entity.satsPerHour,
                zapsPerHour = entity.zapsPerHour,
                velocityTrend = calculateTrend(entity)
            )
        }
    }
    
    private fun calculateTrend(entity: VelocityEntity): VelocityTrend {
        // Compare current hour to previous hour
        // For now, return UNKNOWN until we implement historical tracking
        return VelocityTrend.STABLE
    }
}

/**
 * Data class for zap records
 */
data class ZapEntity(
    val id: String,
    val postId: String,
    val recipientNpub: String,
    val amountSats: Long,
    val timestamp: Instant,
    val senderNpub: String?
)

/**
 * Velocity metrics for a post
 */
data class ZapVelocity(
    val satsPerHour: Long,
    val zapsPerHour: Int,
    val lastUpdated: Instant
)

/**
 * Trending post with velocity data
 */
data class TrendingPost(
    val postId: String,
    val satsPerHour: Long,
    val zapsPerHour: Int,
    val velocityTrend: VelocityTrend
)

/**
 * Velocity trend direction
 */
enum class VelocityTrend {
    RISING,
    FALLING,
    STABLE,
    UNKNOWN
}
