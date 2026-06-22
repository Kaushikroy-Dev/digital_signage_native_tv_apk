package com.digitalsignage.player.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "cached_playlist")
data class CachedPlaylistEntity(
    @PrimaryKey val deviceId: String,
    val jsonPayload: String,
    val timestamp: Long
)

@Entity(tableName = "cached_media")
data class CachedMediaEntity(
    @PrimaryKey val mediaKey: String,
    val remoteUrl: String,
    val localPath: String,
    val sizeBytes: Long,
    val timestamp: Long
)

@Dao
interface PlaylistCacheDao {
    @Query("SELECT * FROM cached_playlist WHERE deviceId = :deviceId LIMIT 1")
    suspend fun get(deviceId: String): CachedPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedPlaylistEntity)

    @Query("DELETE FROM cached_playlist WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}

@Dao
interface MediaCacheDao {
    @Query("SELECT * FROM cached_media WHERE mediaKey = :key LIMIT 1")
    suspend fun get(key: String): CachedMediaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedMediaEntity)

    @Query("SELECT SUM(sizeBytes) as total FROM cached_media")
    suspend fun totalSize(): Long?

    @Query("SELECT * FROM cached_media ORDER BY timestamp ASC")
    suspend fun allOrdered(): List<CachedMediaEntity>

    @Query("DELETE FROM cached_media WHERE mediaKey = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM cached_media")
    suspend fun clearAll()
}

@Database(
    entities = [CachedPlaylistEntity::class, CachedMediaEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PlayerDatabase : RoomDatabase() {
    abstract fun playlistCacheDao(): PlaylistCacheDao
    abstract fun mediaCacheDao(): MediaCacheDao
}
