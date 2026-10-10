package org.asimon.sefaria.data

import kotlinx.coroutines.test.runTest
import org.asimon.sefaria.data.local.CacheDao
import org.asimon.sefaria.data.local.CacheEntry
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiError
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.repo.BuildInfo
import org.asimon.sefaria.data.repo.CachedFetcher
import org.asimon.sefaria.data.repo.Load
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
        val r = fetcher.get("k", { it }, respond(ApiResult.Ok("v", "\"raw\"", "\"b1\"")))
        assertEquals(Load.Ok("v"), r)
        assertEquals(listOf<String?>(null), calls)
        assertEquals("\"raw\"", cache.rows["k"]!!.json)
    }

    @Test
    fun currentBuildCacheSkipsNetwork() = runTest {
        cache.put(CacheEntry("k", "cached", "\"b1\"", 0, 0))
        build.current = "b1"
        assertEquals(Load.Ok("cached"), fetcher.get("k", { it }, respond(ApiResult.Ok("new", "new", null))))
        build.current = null // unknown build (offline at startup): use the cache
        assertEquals(Load.Ok("cached"), fetcher.get("k", { it }, respond(ApiResult.Ok("new", "new", null))))
        assertEquals(emptyList<String?>(), calls)
    }

    @Test
    fun staleBuildRevalidates() = runTest {
        cache.put(CacheEntry("k", "cached", "\"b1\"", 0, 0))
        build.current = "b2"
        assertEquals(Load.Ok("cached"), fetcher.get("k", { it }, respond(ApiResult.NotModified)))
        assertEquals(listOf<String?>("\"b1\""), calls)
        assertEquals("b2", cache.rows["k"]!!.etag) // next read skips the network
        assertEquals(Load.Ok("cached"), fetcher.get("k", { it }, respond(ApiResult.NotModified)))
        assertEquals(1, calls.size)
    }

    @Test
    fun networkFailureServesCacheOffline() = runTest {
        cache.put(CacheEntry("k", "cached", "\"b1\"", 0, 0))
        build.current = "b2"
        val r = fetcher.get("k", { it }, respond(ApiResult.Failure(ApiError.Network(IOException("down")))))
        assertEquals(Load.Ok("cached", offline = true), r)
    }

    @Test
    fun errorsWithoutCache() = runTest {
        val r = fetcher.get("k", { it }, respond(ApiResult.Failure(ApiError.Http(404, "ref_not_found", "unknown title"))))
        assertEquals(Load.Err("unknown title", notFound = true), r)
        assertNull(cache.rows["k"])
    }
}
