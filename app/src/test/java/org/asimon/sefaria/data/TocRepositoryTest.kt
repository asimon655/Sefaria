package org.asimon.sefaria.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.asimon.sefaria.data.local.CacheDao
import org.asimon.sefaria.data.local.CacheEntry
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TocDto
import org.asimon.sefaria.data.repo.BuildInfo
import org.asimon.sefaria.data.repo.CachedFetcher
import org.asimon.sefaria.data.repo.LibraryRepository
import org.asimon.sefaria.data.repo.Load
import org.asimon.sefaria.fixture
import org.asimon.sefaria.testDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The category tree's lifecycle: fetched once per build into its file, served from it offline. */
class TocRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val build = BuildInfo()
    private lateinit var api: Api
    private val db = testDb()

    private object NoCache : CacheDao {
        override suspend fun get(key: String): CacheEntry? = null
        override suspend fun put(entry: CacheEntry) = Unit
        override suspend fun delete(key: String) = Unit
        override suspend fun touch(key: String, now: Long) = Unit
        override suspend fun bytes() = 0L
        override suspend fun trim(keepBytes: Long) = Unit
    }

    @Before
    fun setUp() {
        server.start()
        api = Api(OkHttpClient(), { server.url("/").toString() }, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun repo() = LibraryRepository(api, CachedFetcher(api, NoCache, build), db.library())

    private fun toc(buildId: String) = SefariaJson.decodeFromString(TocDto.serializer(), fixture("toc.json")).copy(buildId = buildId)

    private fun respond(t: TocDto) = server.enqueue(
        MockResponse.Builder().body(SefariaJson.encodeToString(TocDto.serializer(), t)).addHeader("ETag", "\"${t.buildId}\"").build()
    )

    @Test
    fun fetchedOnceThenReadFromTheTable() = runTest {
        build.current = "b1"
        respond(toc("b1"))
        assertEquals(toc("b1"), (repo().toc() as Load.Ok).value)
        assertEquals(toc("b1"), db.library().toc())
        // a new process (new repository) reads the table without asking the server
        val again = repo().toc()
        assertEquals(Load.Ok(toc("b1")), again)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun newBuildFetchesAgainWithTheOldEtag() = runTest {
        db.library().putToc(toc("b1"))
        build.current = "b2"
        respond(toc("b2"))
        assertEquals("b2", (repo().toc() as Load.Ok).value.buildId)
        assertEquals("\"b1\"", server.takeRequest().headers["If-None-Match"])
        assertEquals("b2", db.library().toc()!!.buildId)
    }

    @Test
    fun notModifiedKeepsTheTreeForTheNewBuild() = runTest {
        db.library().putToc(toc("b1"))
        build.current = "b2"
        server.enqueue(MockResponse.Builder().code(304).build())
        assertEquals(toc("b2"), (repo().toc() as Load.Ok).value)
        assertEquals(toc("b2"), repo().toc().let { (it as Load.Ok).value }) // a new process doesn't ask again
        assertEquals(1, server.requestCount)
    }

    @Test
    fun offlineServesTheTable() = runTest {
        db.library().putToc(toc("b1"))
        build.current = "b2"
        server.close() // the network fails
        val r = repo().toc()
        assertTrue(r is Load.Ok && r.offline && r.value.buildId == "b1")
    }

    @Test
    fun unknownBuildUsesTheTable() = runTest {
        db.library().putToc(toc("b1"))
        assertEquals(Load.Ok(toc("b1")), repo().toc())
        assertEquals(0, server.requestCount)
    }
}
