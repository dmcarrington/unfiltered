package com.nostr.unfiltered.nostr

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Velocity cache entity for ZapBoost
 * Stores pre-computed zap velocity metrics per post
 */
@Entity(tableName = "velocity_cache")
data class VelocityEntity(
    @PrimaryKey
    val postId: String,
    val satsPerHour: Long,
    val zapsPerHour: Int,
    val lastUpdated: Instant
)
