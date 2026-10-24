package org.asimon.sefaria.data.datasource

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.asimon.sefaria.data.local.CacheDao
import org.asimon.sefaria.data.local.CacheEntry
import org.asimon.sefaria.data.local.OfflineSource
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiError
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.Load
import org.asimon.sefaria.data.AppError
import org.asimon.sefaria.data.toAppError

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
