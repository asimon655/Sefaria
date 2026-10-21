package org.asimon.sefaria.data.repo

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.TrailStep
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.ref.Catalog
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.data.local.Bookmark
import org.asimon.sefaria.data.local.CacheDao
import org.asimon.sefaria.data.local.CacheEntry
import org.asimon.sefaria.data.local.HistoryEntry
import org.asimon.sefaria.data.local.OfflineSource
import org.asimon.sefaria.data.local.ReadingPosition
import org.asimon.sefaria.data.local.LibraryDao
import org.asimon.sefaria.data.local.Synced
import org.asimon.sefaria.data.local.UserDao
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiError
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.LinksDto
import org.asimon.sefaria.data.remote.ResolveDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TextDto
import org.asimon.sefaria.data.remote.TocDto
import org.asimon.sefaria.data.remote.VersionDto

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
 * Library-first, then cache-first reads. A response the downloaded library holds is used
 * as is (pass [get]'s book). A cached response from the current build is used as is; one from an
 * older build is revalidated with If-None-Match (a 304 costs a few bytes); if the network
 * fails, the cached copy is still served. Responses are pure functions of
 * (build, request), so this never shows mixed builds within one response.
 */
class CachedFetcher(
    private val api: Api,
    private val cache: CacheDao,
    private val buildInfo: BuildInfo,
    private val offline: OfflineSource = OfflineSource { _, _ -> null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    val build get() = buildInfo.current

    /** Drops a cached response, so the next [get] fetches it again. */
    suspend fun forget(key: String) = cache.delete(key)

    suspend fun refreshBuild(): String? {
        val r = api.manifest()
        if (r is ApiResult.Ok) buildInfo.current = r.value.buildId
        return buildInfo.current
    }

    suspend fun <T> get(
        key: String,
        decode: suspend (String) -> T,
        book: String? = null, // the book the response belongs to, if it may be downloaded
        fetch: suspend (etag: String?) -> ApiResult<T>,
    ): Load<T> = try {
        load(key, decode, book, fetch)
    } catch (e: IllegalArgumentException) { // SerializationException: a stored response this app can't read
        Log.w("CachedFetcher", "cannot decode $key: ${e.message}")
        Load.Err(AppError.BadResponse(e.message ?: "invalid JSON"))
    }

    private suspend fun <T> load(
        key: String,
        decode: suspend (String) -> T,
        book: String?,
        fetch: suspend (etag: String?) -> ApiResult<T>,
    ): Load<T> {
        if (book != null) offline.get(book, key)?.let { return Load.Ok(decode(it)) }
        val cached = safe { cache.get(key) }
        val current = buildInfo.current
        if (cached != null && (current == null || cached.etag?.trim('"') == current)) {
            safe { cache.touch(key, now()) }
            return Load.Ok(decode(cached.json))
        }
        return when (val r = fetch(cached?.etag)) {
            is ApiResult.Ok -> {
                safe { cache.put(CacheEntry(key, r.raw, r.etag ?: current, now(), now())) }
                Load.Ok(r.value)
            }
            ApiResult.NotModified -> {
                cached!!
                safe { cache.put(cached.copy(etag = current ?: cached.etag, fetchedAt = now(), lastAccess = now())) }
                Load.Ok(decode(cached.json))
            }
            is ApiResult.Failure ->
                if (cached != null && r.error !is ApiError.Http) Load.Ok(decode(cached.json), offline = true)
                else Load.Err(r.error.toAppError())
        }
    }

    // The cache lives in the cache folder, which the system or the user may empty while the app
    // runs: then it can't be read or written until the next start, and requests go to the server.
    private suspend fun <T> safe(f: suspend () -> T): T? = try {
        f()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("CachedFetcher", "cache unavailable: ${e.message}")
        null
    }
}

/**
 * The library's category tree and books. The tree is kept in the toc_node table (not the
 * response cache) so it is there offline and after the cache is cleared; it is fetched again
 * when the server's build changes.
 */
class LibraryRepository(private val api: Api, private val fetcher: CachedFetcher, private val dao: LibraryDao) {
    private val books = object : LinkedHashMap<String, Book>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Book>?) = size > 24
    }
    private val lock = Mutex()
    private val tocLock = Mutex()

    suspend fun refreshBuild() = fetcher.refreshBuild()

    @Volatile private var tocMemo: TocDto? = null // as last read or fetched; its buildId is the build it came from

    /** The category tree: from memory or the table while the build is the same, else from the server. */
    suspend fun toc(): Load<TocDto> = tocLock.withLock {
        val build = fetcher.build
        val have = tocMemo ?: dao.toc()?.also { tocMemo = it }
        if (have != null && (build == null || have.buildId == build)) return@withLock Load.Ok(have)
        when (val r = api.toc(have?.buildId?.let { "\"$it\"" })) {
            is ApiResult.Ok -> {
                val toc = r.value.let { if (it.buildId.isEmpty()) it.copy(buildId = r.etag?.trim('"') ?: build.orEmpty()) else it }
                tocMemo = toc
                runCatching { dao.putToc(toc) }
                Load.Ok(toc)
            }
            ApiResult.NotModified -> {
                // the same tree in the new build
                val toc = if (build == null) have!! else have!!.copy(buildId = build).also { dao.stamp(Synced(Synced.TOC, build)) }
                tocMemo = toc
                Load.Ok(toc)
            }
            is ApiResult.Failure ->
                if (have != null && r.error !is ApiError.Http) Load.Ok(have, offline = true)
                else Load.Err(r.error.toAppError())
        }
    }

    /** A book's schema, versions and metadata; decoded books stay in memory (small LRU). */
    suspend fun book(title: String): Load<Book> {
        val key = title.replace('_', ' ')
        lock.withLock { books[key] }?.let { return Load.Ok(it) }
        val r = fetcher.get("book:$key", { decodeBook(it) }, book = key) { etag -> api.book(key, etag) }
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
    val linkCounts: List<Int> = emptyList(),
) {
    val size get() = maxOf(he.size, en.size)
}

class TextRepository(private val api: Api, private val fetcher: CachedFetcher, private val offline: OfflineLibrary? = null) {
    /** Loads exactly one section (the paging unit) in the book's default versions; a downloaded book's come from the library. */
    suspend fun section(section: Ref): Load<SectionText> {
        offline?.section(section)?.let { return Load.Ok(it) }
        val ref = section.url()
        return fetcher.get("text:$ref", ::decode, book = section.book.title) { etag -> api.text(ref, etag) }
            .map { t ->
                val s = t.sections.firstOrNull()
                SectionText(s?.ref ?: ref, s?.heRef ?: t.heRef, s?.first ?: 1, s?.he.orEmpty(), s?.en.orEmpty(), t.versions, s?.linkCounts.orEmpty())
            }
    }

    private suspend fun decode(json: String): TextDto =
        withContext(Dispatchers.Default) { SefariaJson.decodeFromString(TextDto.serializer(), json) }
}

/**
 * Connections (Phase 4). The downloaded links file answers first; otherwise they are cached
 * like texts, so connections of read segments work offline.
 */
class LinksRepository(
    private val api: Api,
    private val fetcher: CachedFetcher,
    private val offline: OfflineLibrary? = null,
    private val library: LibraryRepository? = null,
) {
    /** All connections of a segment of [source], grouped, without text. */
    suspend fun links(ref: String, source: String): Load<LinksDto> {
        local(ref, source) { offline?.links(it) }?.let { return Load.Ok(it) }
        return fetcher.get("links:$ref", ::decode) { etag -> api.links(ref, etag = etag) }
    }

    /** [book]'s connections of a segment of [source], with their text. */
    suspend fun withText(ref: String, source: String, book: String): Load<LinksDto> {
        local(ref, source) { offline?.links(it, only = book, withText = true) }?.let { return Load.Ok(it) }
        return fetcher.get("links:$ref|$book", ::decode) { etag -> api.links(ref, withText = true, book = book, etag = etag) }
    }

    private suspend fun local(ref: String, source: String, f: suspend (Ref) -> LinksDto?): LinksDto? {
        if (offline == null || library == null) return null
        val b = (library.book(source) as? Load.Ok)?.value ?: return null
        val r = Ref.parseOrNull(Catalog(listOf(b)), ref.replace('_', ' ')) ?: return null
        return f(r)
    }

    private suspend fun decode(json: String): LinksDto =
        withContext(Dispatchers.Default) { SefariaJson.decodeFromString(LinksDto.serializer(), json) }
}

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
