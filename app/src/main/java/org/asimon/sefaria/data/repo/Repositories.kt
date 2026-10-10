package org.asimon.sefaria.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.data.local.Bookmark
import org.asimon.sefaria.data.local.CacheDao
import org.asimon.sefaria.data.local.CacheEntry
import org.asimon.sefaria.data.local.HistoryEntry
import org.asimon.sefaria.data.local.ReadingPosition
import org.asimon.sefaria.data.local.UserDao
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiError
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.ResolveDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TextDto
import org.asimon.sefaria.data.remote.TocDto
import org.asimon.sefaria.data.remote.VersionDto
import org.asimon.sefaria.data.remote.VersionSelection

/** A loaded value, or an error to show. [offline] marks a cached value served because the network failed. */
sealed interface Load<out T> {
    data class Ok<T>(val value: T, val offline: Boolean = false) : Load<T>
    data class Err(val error: AppError) : Load<Nothing>
}

/** Errors the UI can show; it turns them into localized text (ui/components/Errors.kt). */
sealed interface AppError {
    data object Network : AppError
    /** The server does not know this book or ref (400/404). [detail] is the server's English message. */
    data class NotFound(val detail: String) : AppError
    data class Server(val status: Int, val detail: String) : AppError
    data class BadResponse(val detail: String) : AppError
    data object NoText : AppError
}

fun ApiError.toAppError(): AppError = when (this) {
    is ApiError.Network -> AppError.Network
    is ApiError.Http -> if (status == 400 || status == 404) AppError.NotFound(message) else AppError.Server(status, message)
    is ApiError.BadResponse -> AppError.BadResponse(message)
}

inline fun <T, R> Load<T>.map(f: (T) -> R): Load<R> = when (this) {
    is Load.Ok -> Load.Ok(f(value), offline)
    is Load.Err -> this
}

/** The build the server is serving now (from /v1/manifest); null until known (e.g. offline). */
class BuildInfo {
    @Volatile var current: String? = null
}

/**
 * Cache-first reads. A cached response from the current build is used as is; one from an
 * older build is revalidated with If-None-Match (a 304 costs a few bytes); if the network
 * fails, the cached copy is still served. Responses are pure functions of
 * (build, request), so this never shows mixed builds within one response.
 */
class CachedFetcher(
    private val api: Api,
    private val cache: CacheDao,
    private val buildInfo: BuildInfo,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val build get() = buildInfo.current

    suspend fun refreshBuild(): String? {
        val r = api.manifest()
        if (r is ApiResult.Ok) buildInfo.current = r.value.buildId
        return buildInfo.current
    }

    suspend fun <T> get(key: String, decode: suspend (String) -> T, fetch: suspend (etag: String?) -> ApiResult<T>): Load<T> {
        val cached = cache.get(key)
        val current = buildInfo.current
        if (cached != null && (current == null || cached.etag?.trim('"') == current)) {
            cache.touch(key, now())
            return Load.Ok(decode(cached.json))
        }
        return when (val r = fetch(cached?.etag)) {
            is ApiResult.Ok -> {
                cache.put(CacheEntry(key, r.raw, r.etag ?: current, now(), now()))
                Load.Ok(r.value)
            }
            ApiResult.NotModified -> {
                cached!!
                cache.put(cached.copy(etag = current ?: cached.etag, fetchedAt = now(), lastAccess = now()))
                Load.Ok(decode(cached.json))
            }
            is ApiResult.Failure ->
                if (cached != null && r.error !is ApiError.Http) Load.Ok(decode(cached.json), offline = true)
                else Load.Err(r.error.toAppError())
        }
    }
}

class LibraryRepository(private val api: Api, private val fetcher: CachedFetcher) {
    private val books = object : LinkedHashMap<String, Book>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Book>?) = size > 24
    }
    private val lock = Mutex()

    suspend fun refreshBuild() = fetcher.refreshBuild()

    @Volatile private var tocMemo: Pair<String?, TocDto>? = null // (build, toc)

    /** The category tree; decoded once per build since every library screen needs it. */
    suspend fun toc(): Load<TocDto> {
        tocMemo?.let { (b, toc) -> if (b == fetcher.build) return Load.Ok(toc) }
        val r = fetcher.get("toc", { withContext(Dispatchers.Default) { SefariaJson.decodeFromString(TocDto.serializer(), it) } }) { api.toc(it) }
        if (r is Load.Ok && !r.offline) tocMemo = fetcher.build to r.value
        return r
    }

    /** A book's schema, versions and metadata; decoded books stay in memory (small LRU). */
    suspend fun book(title: String): Load<Book> {
        val key = title.replace('_', ' ')
        lock.withLock { books[key] }?.let { return Load.Ok(it) }
        val r = fetcher.get("book:$key", { decodeBook(it) }) { etag -> api.book(key, etag) }
            .let { it.map(::Book) }
        if (r is Load.Ok) lock.withLock { books[key] = r.value }
        return r
    }

    suspend fun resolve(q: String): Load<ResolveDto> = when (val r = api.resolve(q)) {
        is ApiResult.Ok -> Load.Ok(r.value)
        is ApiResult.Failure -> Load.Err(r.error.toAppError())
        ApiResult.NotModified -> Load.Err(AppError.BadResponse("unexpected 304"))
    }

    private fun decodeBook(json: String) = SefariaJson.decodeFromString(BookDto.serializer(), json)
}

/** One section of text in both languages; segment i has number first + i. */
data class SectionText(
    val ref: String,
    val heRef: String,
    val first: Int,
    val he: List<String>,
    val en: List<String>,
    val versions: Map<String, VersionDto>,
) {
    val size get() = maxOf(he.size, en.size)
}

class TextRepository(private val api: Api, private val fetcher: CachedFetcher) {
    /** Loads exactly one section (the paging unit). */
    suspend fun section(section: Ref, versions: VersionSelection = VersionSelection()): Load<SectionText> {
        val ref = section.url()
        return fetcher.get("text:$ref|${versions.key}", ::decode) { etag -> api.text(ref, versions, etag) }
            .map { t ->
                val s = t.sections.firstOrNull()
                SectionText(s?.ref ?: ref, s?.heRef ?: t.heRef, s?.first ?: 1, s?.he.orEmpty(), s?.en.orEmpty(), t.versions)
            }
    }

    private suspend fun decode(json: String): TextDto =
        withContext(Dispatchers.Default) { SefariaJson.decodeFromString(TextDto.serializer(), json) }
}

class UserDataRepository(private val dao: UserDao, private val now: () -> Long = System::currentTimeMillis) {
    fun bookmarks(): Flow<List<Bookmark>> = dao.bookmarks()
    fun history(): Flow<List<HistoryEntry>> = dao.history()
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
    suspend fun recordPosition(ref: Ref) {
        val t = now()
        dao.putPosition(ReadingPosition(ref.book.title, ref.url(), t))
        val last = dao.lastVisit()
        val id = if (last != null && last.book == ref.book.title && t - last.visitedAt < SESSION_MS) last.id else 0
        dao.putHistory(HistoryEntry(id, ref.book.title, ref.url(), ref.human(), ref.hebrew(), t))
    }

    suspend fun lastPosition(book: String): ReadingPosition? = dao.position(book)
    suspend fun clearHistory() = dao.clearHistory()

    companion object {
        const val SESSION_MS = 30 * 60 * 1000L
    }
}
