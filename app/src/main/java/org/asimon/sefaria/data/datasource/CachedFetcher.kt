package org.asimon.sefaria.data.datasource

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.asimon.sefaria.core.debugAssert
import org.asimon.sefaria.data.local.CacheDao
import org.asimon.sefaria.data.local.CacheEntry
import org.asimon.sefaria.data.local.OfflineSource
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.Load
import org.asimon.sefaria.data.AppError
import org.asimon.sefaria.data.toAppError

/**
 * What the server is serving now, from /v1/manifest: its build ([current]) and the checksum of
 * its library listing ([library], "" from a server that doesn't say). Both null until the
 * server has answered (e.g. offline).
 */
class BuildInfo {
    @Volatile var current: String? = null
    @Volatile var library: String? = null
    /** When the server last told its build (elapsed ms on [CachedFetcher]'s clock); 0: never. */
    @Volatile var checkedAt: Long = 0
}

/** The build id an ETag names: without its quotes, and without the W/ a cache in between may add. */
fun buildOf(etag: String?): String? = etag?.removePrefix("W/")?.trim('"')?.takeIf { it.isNotEmpty() }

/**
 * Library-first, then cache-first reads. A response the downloaded library holds is used
 * as is (pass [get]'s book). A cached response from the current build is used as is; one from an
 * older build is revalidated with If-None-Match (a 304 costs a few bytes); if the server can't be
 * asked just now (no network, or it is busy or failing: [AppError.transient]), the cached copy is
 * still served. Responses are pure functions of (build, request), so this never shows mixed
 * builds within one response.
 */
class CachedFetcher(
    private val api: Api,
    private val cache: CacheDao,
    private val buildInfo: BuildInfo,
    private val offline: OfflineSource = OfflineSource { _, _ -> null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    val build get() = buildInfo.current

    /** The checksum of the server's library listing; null until the server has answered. */
    val library get() = buildInfo.library

    /** Drops a cached response, so the next [get] fetches it again. */
    suspend fun forget(key: String) = cache.delete(key)

    suspend fun refreshBuild(): String? {
        val r = api.manifest()
        if (r is ApiResult.Ok) {
            buildInfo.current = r.value.buildId
            buildInfo.library = r.value.library
            buildInfo.checkedAt = now()
        }
        return buildInfo.current
    }

    /**
     * Asks the server for its build again if it hasn't said for [maxAge] ms (or never did): the
     * app came back to the screen, perhaps hours after it started or after it started offline.
     */
    suspend fun refreshBuildIfStale(maxAge: Long = BUILD_MAX_AGE) = asking.withLock { // one asks, the others find it fresh
        val at = buildInfo.checkedAt
        if (at == 0L || now() - at >= maxAge) refreshBuild()
    }

    private val asking = Mutex()

    suspend fun <T> get(
        key: String,
        decode: suspend (String) -> T,
        book: String? = null, // the book the response belongs to, if it may be downloaded
        fetch: suspend (etag: String?) -> ApiResult<T>,
    ): Load<T> = try {
        load(key, decode, book, fetch)
    } catch (e: IllegalArgumentException) { // SerializationException: a downloaded book's response this app can't read
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
        // A cached response this app can't read (an older app wrote it) is dropped and fetched
        // again; kept, it would fail every time until the build changed.
        var cached = safe { cache.get(key) }
        val value: T? = cached?.let { c ->
            try {
                decode(c.json)
            } catch (e: IllegalArgumentException) {
                Log.w("CachedFetcher", "dropping unreadable $key: ${e.message}")
                safe { cache.delete(key) }
                cached = null
                null
            }
        }
        val entry = cached
        val current = buildInfo.current
        if (entry != null && value != null && (current == null || buildOf(entry.etag) == current)) {
            safe { cache.touch(key, now()) }
            return Load.Ok(value)
        }
        return when (val r = fetch(entry?.etag)) {
            is ApiResult.Ok -> {
                safe { cache.put(CacheEntry(key, r.raw, r.etag ?: current, now(), now())) }
                // A response from a later build than the one we knew: the server moved on. Build
                // ids start with their dates, so they sort by age; an earlier one is some cache's
                // old copy, and doesn't take us back.
                buildOf(r.etag)?.let { b -> if (buildInfo.current.let { it != null && b > it }) buildInfo.current = b }
                Load.Ok(r.value)
            }
            ApiResult.NotModified -> {
                debugAssert(entry != null && value != null) { "304 for $key, which was asked for without an ETag" }
                if (entry == null || value == null) return Load.Err(AppError.BadResponse("304 without a cached copy"))
                safe { cache.put(entry.copy(etag = current ?: entry.etag, fetchedAt = now(), lastAccess = now())) }
                Load.Ok(value)
            }
            is ApiResult.Failure -> {
                val error = r.error.toAppError()
                if (value != null && error.transient) Load.Ok(value, offline = true) else Load.Err(error)
            }
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

    companion object {
        /** How long the server's build is taken as known before it is asked again. */
        const val BUILD_MAX_AGE = 10 * 60 * 1000L
    }
}
