package com.nostr.unfiltered.repository

import android.content.Context
import androidx.room.*
import com.nostr.unfiltered.nostr.VelocityEntity
import com.nostr.unfiltered.nostr.ZapEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * ZapBoost: SQLite database for tracking Lightning zap velocity
 */
@Database(
    entities = [ZapEntity::class, VelocityEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ZapDatabase : RoomDatabase() {
    abstract fun zapDao(): ZapDao
    abstract fun velocityDao(): VelocityDao
    
    companion object {
        @Volatile
        private var INSTANCE: ZapDatabase? = null
        
        fun getDatabase(context: Context): ZapDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ZapDatabase::class.java,
                    "zapboost_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * DAO for zap receipts
 */
@Dao
interface ZapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertZap(zap: ZapEntity)
    
    @Query("SELECT * FROM zaps WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getZapsSince(since: Instant): List<ZapEntity>
    
    @Query("SELECT * FROM zaps WHERE postId = :postId AND timestamp >= :since")
    suspend fun getZapsForPost(postId: String, since: Instant): List<ZapEntity>
    
    @Query("DELETE FROM zaps WHERE timestamp < :olderThan")
    suspend fun deleteOldZaps(olderThan: Instant)
}

/**
 * DAO for velocity cache
 */
@Dao
interface VelocityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateVelocity(entity: VelocityEntity)
    
    @Query("SELECT * FROM velocity_cache WHERE postId = :postId")
    suspend fun getVelocityForPost(postId: String): VelocityEntity?
    
    @Query("SELECT * FROM velocity_cache ORDER BY satsPerHour DESC LIMIT :limit")
    suspend fun getTrendingPosts(limit: Int): List<VelocityEntity>
    
    @Query("SELECT * FROM velocity_cache ORDER BY satsPerHour DESC")
    fun getTrendingPostsFlow(): Flow<List<VelocityEntity>>
}

/**
 * Type converters for Instant
 */
class Converters {
    @TypeConverter
    fun fromInstant(instant: Instant): Long = instant.epochSecond
    
    @TypeConverter
    fun toInstant(epochSecond: Long): Instant = Instant.ofEpochSecond(epochSecond)
}
