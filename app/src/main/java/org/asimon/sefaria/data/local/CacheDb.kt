package org.asimon.sefaria.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * Response cache ([CacheDb], in the app's cache folder): server JSON stored verbatim, keyed by request
 * ("book:Genesis", "text:Genesis.1|he=d,en=d"). [etag] is the build id that
 * produced it, so a new build revalidates lazily with If-None-Match.
 */
@Entity(tableName = "cache")
data class CacheEntry(
    @PrimaryKey val key: String,
    val json: String,
    val etag: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "last_access") val lastAccess: Long,
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE `key` = :key")
    suspend fun get(key: String): CacheEntry?

    @Upsert
    suspend fun put(entry: CacheEntry)

    @Query("DELETE FROM cache WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("UPDATE cache SET last_access = :now WHERE `key` = :key")
    suspend fun touch(key: String, now: Long)

    @Query("SELECT COALESCE(SUM(LENGTH(json)), 0) FROM cache")
    suspend fun bytes(): Long

    /** Drops least recently used entries until about [keepBytes] remain. */
    @Query(
        """DELETE FROM cache WHERE `key` IN (
             SELECT `key` FROM (SELECT `key`, last_access,
               SUM(LENGTH(json)) OVER (ORDER BY last_access DESC) AS running FROM cache)
             WHERE running > :keepBytes)"""
    )
    suspend fun trim(keepBytes: Long)
}

/**
 * The response cache, a database of its own in the app's cache folder: the system may delete it
 * when storage runs low, and "Clear cache" in the app's info deletes it. Losing it only costs
 * requests, so it is rebuilt rather than migrated.
 */
@Database(entities = [CacheEntry::class], version = 1)
abstract class CacheDb : RoomDatabase() {
    abstract fun cache(): CacheDao

    companion object {
        const val FILE = "responses.db"
    }
}
