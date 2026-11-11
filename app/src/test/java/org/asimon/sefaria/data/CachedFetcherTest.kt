package org.asimon.sefaria.data

import kotlinx.coroutines.test.runTest
import org.asimon.sefaria.data.local.CacheDao
import org.asimon.sefaria.data.local.CacheEntry
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiError
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.datasource.BuildInfo
import org.asimon.sefaria.data.datasource.CachedFetcher
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class CachedFetcherTest {
    private class FakeCache : CacheDao {
        val rows = HashMap<String, CacheEntry>()
        override suspend fun get(key: String) = rows[key]
        override suspend fun put(entry: CacheEntry) { rows[entry.key] = entry }
        override suspend fun delete(key: String) { rows.remove(key) }
        override suspend fun touch(key: String, now: Long) { rows[key]?.let { rows[key] = it.copy(lastAccess = now) } }
        override suspend fun bytes() = rows.values.sumOf { it.json.length.toLong() }
        override suspend fun trim(keepBytes: Long) = Unit
    }

    private val cache = FakeCache()
    private val build = BuildInfo()
    private val fetcher = CachedFetcher(Api(OkHttpClient(), { "http://unused" }), cache, build) { 1000L }
    private val calls = ArrayList<String?>() // etags sent

    private fun respond(r: ApiResult<String>): suspend (String?) -> ApiResult<String> = { etag -> calls += etag; r }

    @Test
    fun fetchesAndStoresWhenNotCached() = runTest {
        build.current = "b1"
        val r = fetcher.get("k", { it }, fetch = respond(ApiResult.Ok("v", "\"raw\"", "\"b1\"")))
        assertEquals(Load.Ok("v"), r)
        assertEquals(listOf<String?>(null), calls)
        assertEquals("\"raw\"", cache.rows["k"]!!.json)
    }

    @Test
    fun currentBuildCacheSkipsNetwork() = runTest {
        cache.put(CacheEntry("k", "cached", "\"b1\"", 0, 0))
        build.current = "b1"
        assertEquals(Load.Ok("cached"), fetcher.get("k", { it }, fetch = respond(ApiResult.Ok("new", "new", null))))
        build.current = null // unknown build (offline at startup): use the cache
        assertEquals(Load.Ok("cached"), fetcher.get("k", { it }, fetch = respond(ApiResult.Ok("new", "new", null))))
        assertEquals(emptyList<String?>(), calls)
    }

    @Test
    fun staleBuildRevalidates() = runTest {
        cache.put(CacheEntry("k", "cached", "\"b1\"", 0, 0))
        build.current = "b2"
        assertEquals(Load.Ok("cached"), fetcher.get("k", { it }, fetch = respond(ApiResult.NotModified)))
        assertEquals(listOf<String?>("\"b1\""), calls)
        assertEquals("b2", cache.rows["k"]!!.etag) // next read skips the network
        assertEquals(Load.Ok("cached"), fetcher.get("k", { it }, fetch = respond(ApiResult.NotModified)))
        assertEquals(1, calls.size)
    }

    @Test
    fun networkFailureServesCacheOffline() = runTest {
        cache.put(CacheEntry("k", "cached", "\"b1\"", 0, 0))
        build.current = "b2"
        val r = fetcher.get("k", { it }, fetch = respond(ApiResult.Failure(ApiError.Network(IOException("down")))))
        assertEquals(Load.Ok("cached", offline = true), r)
    }

    @Test
    fun errorsWithoutCache() = runTest {
        val r = fetcher.get("k", { it }, fetch = respond(ApiResult.Failure(ApiError.Http(404, "ref_not_found", "unknown title"))))
        assertEquals(Load.Err(AppError.NotFound("unknown title")), r)
        assertNull(cache.rows["k"])
    }

    @Test
    fun downloadedPackComesFirst() = runTest {
        val packed = CachedFetcher(Api(OkHttpClient(), { "http://unused" }), cache, build, { book, key ->
            if (book == "Genesis" && key == "k") "from pack" else null
        }) { 1000L }
        build.current = "b2"
        cache.rows["k"] = CacheEntry("k", "cached", "\"b1\"", 0, 0)
        assertEquals(Load.Ok("from pack"), packed.get("k", { it }, book = "Genesis", fetch = respond(ApiResult.Ok("net", "net", null))))
        assertEquals(emptyList<String?>(), calls) // no request
        // another book, or no book given: the usual cache/network path
        assertEquals(Load.Ok("net"), packed.get("k", { it }, book = "Exodus", fetch = respond(ApiResult.Ok("net", "net", "\"b2\""))))
    }

    @Test
    fun busyOrFailingServerServesCache() = runTest {
        cache.put(CacheEntry("k", "cached", "\"b1\"", 0, 0))
        build.current = "b2"
        // what waiting may cure (5xx, 429): the copy we have, marked offline
        for (e in listOf(ApiError.Http(503, "internal", "down"), ApiError.Http(429, "rate_limited", "slow down", retryAfter = 1))) {
            assertEquals(Load.Ok("cached", offline = true), fetcher.get("k", { it }, fetch = respond(ApiResult.Failure(e))))
        }
        // what it won't (the new build doesn't have this ref): the error
        val gone = fetcher.get("k", { it }, fetch = respond(ApiResult.Failure(ApiError.Http(404, "ref_not_found", "gone"))))
        assertEquals(Load.Err(AppError.NotFound("gone")), gone)
    }

    @Test
    fun unreadableCacheEntryIsDroppedAndFetchedAgain() = runTest {
        cache.put(CacheEntry("k", "old shape", "\"b1\"", 0, 0))
        build.current = "b1" // current: without the drop it would fail on every read
        val decode: suspend (String) -> String = { if (it == "old shape") throw IllegalArgumentException("unknown field") else it }
        assertEquals(Load.Ok("new"), fetcher.get("k", decode, fetch = respond(ApiResult.Ok("new", "new", "\"b1\""))))
        assertEquals(listOf<String?>(null), calls) // asked without an ETag: a 304 would be no help
        assertEquals("new", cache.rows["k"]!!.json)
    }

    @Test
    fun aResponseFromALaterBuildMovesTheBuildOn() = runTest {
        build.current = "2025-09-21-20261013T2345"
        fetcher.get("a", { it }, fetch = respond(ApiResult.Ok("v", "v", "W/\"2025-12-01-20261201T0000\"")))
        assertEquals("2025-12-01-20261201T0000", build.current)
        // an older one (a cache's stale copy) doesn't move it back
        fetcher.get("b", { it }, fetch = respond(ApiResult.Ok("v", "v", "\"2025-09-21-20261013T2345\"")))
        assertEquals("2025-12-01-20261201T0000", build.current)
    }
}
