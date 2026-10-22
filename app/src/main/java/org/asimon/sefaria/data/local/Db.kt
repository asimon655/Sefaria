package org.asimon.sefaria.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.TypeConverter
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow
import org.asimon.sefaria.core.TrailStep

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

@Entity(tableName = "bookmark", indices = [Index("ref", unique = true)])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val book: String,
    val ref: String, // canonical URL ref
    val label: String, // human form
    @ColumnInfo(name = "he_label") val heLabel: String,
    val preview: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "history", indices = [Index("visited_at")])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val book: String,
    val ref: String,
    val label: String,
    @ColumnInfo(name = "he_label") val heLabel: String,
    @ColumnInfo(name = "visited_at") val visitedAt: Long,
    /** The source trail that led here (core/Trail.kt), stored as JSON. */
    val trail: List<TrailStep> = emptyList(),
)

@Entity(tableName = "reading_position")
data class ReadingPosition(
    @PrimaryKey val book: String,
    val ref: String, // segment (or section) ref at the top of the screen
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
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

@Dao
interface UserDao {
    @Query("SELECT * FROM bookmark ORDER BY created_at DESC")
    fun bookmarks(): Flow<List<Bookmark>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmark WHERE ref = :ref)")
    fun isBookmarked(ref: String): Flow<Boolean>

    @Upsert
    suspend fun addBookmark(b: Bookmark)

    @Query("DELETE FROM bookmark WHERE ref = :ref")
    suspend fun removeBookmark(ref: String)

    @Query("SELECT * FROM history ORDER BY visited_at DESC LIMIT 500")
    fun history(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY visited_at DESC LIMIT 1")
    suspend fun lastVisit(): HistoryEntry?

    @Upsert
    suspend fun putHistory(h: HistoryEntry): Long

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    /** The latest visit of each book, most recent first (Home's "continue reading"). */
    @Query("SELECT * FROM history h WHERE visited_at = (SELECT MAX(visited_at) FROM history WHERE book = h.book) ORDER BY visited_at DESC LIMIT :limit")
    fun recentBooks(limit: Int): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM reading_position WHERE book = :book")
    suspend fun position(book: String): ReadingPosition?

    @Query("SELECT * FROM reading_position WHERE book = :book")
    fun positionFlow(book: String): Flow<ReadingPosition?>

    @Upsert
    suspend fun putPosition(p: ReadingPosition)
}

/**
 * The app's own data (files/databases/sefaria.db): the user's bookmarks, history and reading
 * positions, and the library's (LibraryDao): the server's listing, the downloaded files and
 * the category tree.
 */
@Database(
    entities = [
        Bookmark::class, HistoryEntry::class, ReadingPosition::class,
        ListedFile::class, InstalledFile::class, Synced::class, TocNode::class,
    ],
    version = 2,
)
@TypeConverters(Converters::class)
abstract class SefariaDb : RoomDatabase() {
    abstract fun user(): UserDao
    abstract fun library(): LibraryDao

    companion object {
        /**
         * Version 2: the download queue moves from files/downloads.pending into listed_file.queued,
         * and toc_node gets indexes for walking the tree and joining on titles. A migration, not a
         * fresh start: dropping installed_file would make the next tidy delete every download.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `listed_file` ADD COLUMN `queued` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_toc_node_parent` ON `toc_node` (`parent`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_toc_node_title` ON `toc_node` (`title`)")
            }
        }
    }
}

/** Column types Room doesn't know. */
class Converters {
    @TypeConverter fun trailToColumn(t: List<TrailStep>): String = TrailStep.encode(t)
    @TypeConverter fun trailFromColumn(s: String): List<TrailStep> = TrailStep.decode(s)
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
