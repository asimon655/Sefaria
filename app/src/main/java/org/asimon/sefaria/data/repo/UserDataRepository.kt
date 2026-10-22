package org.asimon.sefaria.data.repo

import kotlinx.coroutines.flow.Flow
import org.asimon.sefaria.core.TrailStep
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.data.local.Bookmark
import org.asimon.sefaria.data.local.HistoryEntry
import org.asimon.sefaria.data.local.ReadingPosition
import org.asimon.sefaria.data.local.UserDao

class UserDataRepository(private val dao: UserDao, private val now: () -> Long = System::currentTimeMillis) {
    fun bookmarks(): Flow<List<Bookmark>> = dao.bookmarks()
    fun history(): Flow<List<HistoryEntry>> = dao.history()
    fun recentBooks(limit: Int = 6): Flow<List<HistoryEntry>> = dao.recentBooks(limit)
    fun isBookmarked(ref: String): Flow<Boolean> = dao.isBookmarked(ref)
    fun position(book: String): Flow<ReadingPosition?> = dao.positionFlow(book)

    suspend fun toggleBookmark(ref: Ref, preview: String, bookmarked: Boolean) {
        if (bookmarked) dao.removeBookmark(ref.url())
        else dao.addBookmark(Bookmark(0, ref.book.title, ref.url(), ref.human(), ref.hebrew(), preview.take(200), now()))
    }

    /**
     * Records where the reader is. Consecutive reading of one book within [SESSION_MS]
     * updates a single history row instead of adding one per scroll.
     */
    suspend fun recordPosition(ref: Ref, trail: List<TrailStep> = emptyList()) {
        val t = now()
        dao.putPosition(ReadingPosition(ref.book.title, ref.url(), t))
        val last = dao.lastVisit()
        val id = if (last != null && last.book == ref.book.title && t - last.visitedAt < SESSION_MS) last.id else 0
        dao.putHistory(HistoryEntry(id, ref.book.title, ref.url(), ref.human(), ref.hebrew(), t, trail))
    }

    suspend fun lastPosition(book: String): ReadingPosition? = dao.position(book)
    suspend fun clearHistory() = dao.clearHistory()

    companion object {
        const val SESSION_MS = 30 * 60 * 1000L
    }
}
