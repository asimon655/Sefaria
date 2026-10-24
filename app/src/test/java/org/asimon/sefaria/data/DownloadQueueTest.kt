package org.asimon.sefaria.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.asimon.sefaria.data.remote.LibraryDto
import org.asimon.sefaria.data.remote.SefariaJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.asimon.sefaria.data.local.CacheDao
import org.asimon.sefaria.data.local.CacheEntry
import org.asimon.sefaria.data.local.Library
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.datasource.BuildInfo
import org.asimon.sefaria.data.datasource.CachedFetcher
import org.asimon.sefaria.data.repo.DownloadRepository
import org.asimon.sefaria.data.repo.DownloadState
import org.asimon.sefaria.data.datasource.FileDownloader
import org.asimon.sefaria.data.repo.LibraryListing
import org.asimon.sefaria.data.repo.SharedFiles
import kotlinx.coroutines.flow.first
import org.asimon.sefaria.testDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The download queue the worker runs: what starts it, what it resumes, and what it retries. */
class DownloadQueueTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val src = File(javaClass.getResource("/library/manifest.json")!!.toURI()).parentFile!!
    private val db = testDb()
    private var queued = 0

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
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val body = when {
                    path == "/v1/manifest" -> """{"build_id":"test-build"}""".toByteArray()
                    path == "/v1/library" -> File(src, "manifest.json").readBytes()
                    path.startsWith("/v1/library/") -> File(src, path.removePrefix("/v1/library/")).takeIf { it.isFile }?.readBytes()
                    else -> null
                } ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().body(Buffer().write(body)).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private class Parts(val downloads: DownloadRepository, val listing: LibraryListing)

    private fun repo() = parts().downloads

    private fun parts(): Parts {
        val client = OkHttpClient()
        val base = suspend { server.url("/").toString() }
        val api = Api(client, base)
        val store = Library(File(tmp.root, "library"), db.installed())
        val listing = LibraryListing(api, CachedFetcher(api, NoCache, BuildInfo(), store.reader), db.listing(), scope)
        val downloader = FileDownloader(client, base)
        val downloads = DownloadRepository(
            api, listing, SharedFiles(api, listing, store, downloader, scope), store, downloader, scope,
            onQueued = { queued++ },
        )
        return Parts(downloads, listing)
    }

    private fun listing() = SefariaJson.decodeFromString(LibraryDto.serializer(), File(src, "manifest.json").readText())

    @Test
    fun aDownloadStartsTheWorker() = runBlocking {
        val p = parts()
        val r = p.downloads
        r.download("Genesis")
        assertEquals(1, queued)
        withTimeout(10_000) { r.awaitIdle() }
        withTimeout(10_000) { r.installed.first { "Genesis" in it } }
        assertFalse(r.hasPending())
        assertEquals(0L, p.listing.extra.first { it == 0L }) // the links file, forms table and dictionary came with it
    }

    @Test
    fun resumesTheQueueALastProcessLeft() = runBlocking {
        // what a last process saved: the listing, with two books queued
        db.listing().putListing(listing(), "test-build")
        db.listing().setQueued(listOf("Genesis", "Leviticus"))
        val r = repo()
        assertTrue(r.hasSavedQueue())
        r.resumePending()
        withTimeout(10_000) { r.awaitIdle() }
        withTimeout(10_000) { r.installed.first { it.keys == setOf("Genesis", "Leviticus") } }
        assertFalse(r.hasPending())
    }

    @Test
    fun theQueueIsSavedInTheListingAndSurvivesANewListing() = runBlocking {
        val dao = db.listing()
        dao.putListing(listing(), "b1")
        dao.setQueued(listOf("Genesis", "Leviticus", "Not Listed"))
        assertEquals(setOf("Genesis", "Leviticus"), dao.queuedBooks().toSet())
        dao.putListing(listing(), "b2") // a new build's listing keeps what is queued
        assertEquals(setOf("Genesis", "Leviticus"), dao.queuedBooks().toSet())
        dao.setQueued(listOf("Leviticus"))
        assertEquals(listOf("Leviticus"), dao.queuedBooks())
        dao.setQueued(emptyList())
        assertFalse(dao.hasQueued())
    }

    @Test
    fun aFinishedDownloadLeavesTheSavedQueue() = runBlocking {
        val r = repo()
        r.download("Genesis")
        withTimeout(10_000) { r.awaitIdle() }
        withTimeout(10_000) { r.installed.first { "Genesis" in it } }
        // the queue is saved at most once a second
        withTimeout(10_000) { while (db.listing().hasQueued()) delay(100) }
        assertFalse(db.listing().hasQueued())
    }

    @Test
    fun theListingIsStoredForOfflineUse() = runBlocking {
        val r = parts().listing
        assertEquals(null, r.refresh())
        withTimeout(10_000) { r.available.first { it.size == 4 } }
        assertTrue(r.extra.first { it > 0 } > 0) // nothing downloaded yet
        server.close()
        // a new process offline: the stored listing still serves
        val again = parts().listing
        assertEquals(setOf("Genesis", "Pesach Haggadah", "Rashi on Genesis", "Leviticus"), withTimeout(10_000) { again.available.first { it.isNotEmpty() } }.keys)
        assertEquals(null, again.refresh()) // no build known: the stored one is current
    }

    @Test
    fun networkFailureStaysPendingForARetry() = runBlocking {
        val r = repo()
        r.download("Genesis") // the listing isn't known yet, so the network is needed
        server.close()
        withTimeout(10_000) { r.awaitIdle() }
        assertTrue(r.downloads.value["Genesis"] is DownloadState.Failed)
        assertTrue(r.hasPending()) // the worker answers retry
    }
}
