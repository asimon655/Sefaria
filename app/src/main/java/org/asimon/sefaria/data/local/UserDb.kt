package org.asimon.sefaria.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.asimon.sefaria.core.TrailStep

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

// (book, visited_at): the latest visit of each book (Home) is a lookup per book, not a scan per row
@Entity(tableName = "history", indices = [Index("visited_at"), Index("book", "visited_at")])
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

    /** Drops all but the latest [keep] visits (MaintenanceWorker): the screens show 500, and nothing reads further back. */
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visited_at DESC LIMIT :keep)")
    suspend fun pruneHistory(keep: Int)

    @Query("SELECT COUNT(*) FROM history")
    suspend fun historyCount(): Int

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
 * The user's own data (files/databases/user.db): bookmarks, history and reading positions. A
 * database of its own, apart from the library's state (SefariaDb): it is what a backup should
 * carry to a new phone, where the old phone's downloads and listing mean nothing, and it can be
 * small and simple (a few hundred rows).
 */
@Database(entities = [Bookmark::class, HistoryEntry::class, ReadingPosition::class], version = 1)
@TypeConverters(Converters::class)
abstract class UserDb : RoomDatabase() {
    abstract fun user(): UserDao

    companion object {
        const val FILE = "user.db"
        /** History rows kept by the daily tidy. */
        const val HISTORY_KEEP = 2000
    }
}

/** Column types Room doesn't know. */
class Converters {
    @TypeConverter fun trailToColumn(t: List<TrailStep>): String = TrailStep.encode(t)
    @TypeConverter fun trailFromColumn(s: String): List<TrailStep> = TrailStep.decode(s)
}
