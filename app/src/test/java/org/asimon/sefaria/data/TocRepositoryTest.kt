package org.asimon.sefaria.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.asimon.sefaria.data.local.CacheDao
import org.asimon.sefaria.data.local.CacheEntry
import org.asimon.sefaria.data.local.importToc
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TocDto
import org.asimon.sefaria.data.repo.BuildInfo
import org.asimon.sefaria.data.repo.CachedFetcher
import org.asimon.sefaria.data.repo.CompletionRepository
import org.asimon.sefaria.data.repo.TocRepository
import org.asimon.sefaria.data.repo.Load
import org.asimon.sefaria.data.remote.TocNodeDto
import kotlinx.coroutines.runBlocking
import org.asimon.sefaria.fixture
import org.asimon.sefaria.tocDbFile
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.room.useReaderConnection
import okio.Buffer
import org.asimon.sefaria.testDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The category tree's lifecycle: downloaded once per build as SQLite into its table, served from it offline. */
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

    private fun repo() = TocRepository(api, CachedFetcher(api, NoCache, build), db, tmp.root.resolve("toc"))

    private fun toc(buildId: String) = SefariaJson.decodeFromString(TocDto.serializer(), fixture("toc.json")).copy(buildId = buildId)

    private fun file(t: TocDto, version: Int = 1) = tocDbFile(t, tmp.newFile(), version)

    private suspend fun store(t: TocDto) = db.importToc(file(t))

    private fun respond(t: TocDto, version: Int = 1) = server.enqueue(
        MockResponse.Builder().body(Buffer().write(file(t, version).readBytes())).addHeader("ETag", "\"${t.buildId}\"").build()
    )

    @Test
    fun fetchedOnceThenReadFromTheTable() = runTest {
        build.current = "b1"
        respond(toc("b1"))
        assertEquals(toc("b1"), (repo().toc() as Load.Ok).value)
        assertEquals(toc("b1"), db.toc().toc())
        // a new process (new repository) reads the table without asking the server
        val again = repo().toc()
        assertEquals(Load.Ok(toc("b1")), again)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun newBuildFetchesAgainWithTheOldEtag() = runTest {
        store(toc("b1"))
        build.current = "b2"
        respond(toc("b2"))
        assertEquals("b2", (repo().toc() as Load.Ok).value.buildId)
        val req = server.takeRequest()
        assertEquals("/v1/toc.sqlite", req.url.encodedPath)
        assertEquals("\"b1\"", req.headers["If-None-Match"])
        assertEquals("b2", db.toc().toc()!!.buildId)
    }

    @Test
    fun notModifiedKeepsTheTreeForTheNewBuild() = runTest {
        store(toc("b1"))
        build.current = "b2"
        server.enqueue(MockResponse.Builder().code(304).build())
        assertEquals(toc("b2"), (repo().toc() as Load.Ok).value)
        assertEquals(toc("b2"), repo().toc().let { (it as Load.Ok).value }) // a new process doesn't ask again
        assertEquals(1, server.requestCount)
    }

    @Test
    fun offlineServesTheTable() = runTest {
        store(toc("b1"))
        build.current = "b2"
        server.close() // the network fails
        val r = repo().toc()
        assertTrue(r is Load.Ok && r.offline && r.value.buildId == "b1")
    }

    @Test
    fun unknownBuildUsesTheTable() = runTest {
        store(toc("b1"))
        assertEquals(Load.Ok(toc("b1")), repo().toc())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aFileOfAnotherVersionKeepsTheTable() = runTest {
        store(toc("b1"))
        build.current = "b2"
        respond(toc("b2"), version = 2)
        assertEquals(Load.Ok(toc("b1")), repo().toc())
        assertEquals("b1", db.toc().toc()!!.buildId)
        assertTrue(tmp.root.resolve("toc").listFiles().orEmpty().isEmpty()) // the download is deleted
    }

    /** The server's toc_node (the shared schema) has the columns of the Room table it is copied into. */
    @Test
    fun serverSchemaMatchesTheRoomTable() = runTest {
        val info = "PRAGMA table_info(toc_node)" // name, type, notnull, pk of each column
        fun columns(st: SQLiteStatement) = buildList { while (st.step()) add(listOf(st.getText(1), st.getText(2), st.getLong(3), st.getLong(5))) }
        val room = db.useReaderConnection { c -> c.usePrepared(info, ::columns) }
        val server = BundledSQLiteDriver().open(file(toc("b1")).path).use { c -> c.prepare(info).use(::columns) }
        assertEquals(room, server)
    }

    @Test
    fun levelsAreReadFromTheTable() = runTest {
        build.current = "b1"
        store(toc("b1"))
        val top = (repo().level(emptyList()) as Load.Ok).value
        assertEquals(null, top.node)
        assertEquals(listOf("Tanakh", "Talmud", "Halakhah", "Kabbalah", "Liturgy"), top.children.map { it.en })
        assertEquals(listOf("Genesis", "Exodus", "Rashi on Genesis"), top.booksUnder["c:Tanakh"])
        assertEquals(11, top.books.size)
        val torah = (repo().level(listOf("Tanakh", "Torah")) as Load.Ok).value
        assertEquals("Torah", torah.node?.en)
        assertEquals(listOf("Genesis", "Exodus"), torah.children.map { it.title })
        assertEquals(listOf("Genesis"), torah.booksUnder["b:Genesis"])
        val gone = (repo().level(listOf("Tanakh", "Not a category")) as Load.Ok).value
        assertEquals(emptyList<TocNodeDto>(), gone.children)
    }

    @Test
    fun offlineCompletionUsesTheBookNames() = runTest {
        build.current = "b1"
        store(toc("b1"))
        server.close() // no network: completion comes from the table
        val completion = CompletionRepository(api, repo())
        fun titles(q: String) = runBlocking { (completion.complete(q) as Load.Ok).value.map { it.book } }
        assertEquals("Berakhot", titles("berachot").first())
        assertEquals(listOf("Rashi on Genesis"), titles("רשי"))
        assertEquals("Shulchan Arukh, Orach Chayim", titles("orach").first())
        assertEquals(listOf("Genesis", "Rashi on Genesis"), titles("gen"))
    }
}
