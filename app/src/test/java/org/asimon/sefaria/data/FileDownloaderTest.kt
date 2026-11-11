package org.asimon.sefaria.data

import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.asimon.sefaria.data.local.FileKind
import org.asimon.sefaria.data.local.ListedFile
import org.asimon.sefaria.data.datasource.FileDownloader
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class FileDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var dl: FileDownloader
    private val data = ByteArray(300_000) { (it * 7 % 251).toByte() }
    private val file = ListedFile(sha(data) + ".sfb", FileKind.BOOK, "Genesis", bytes = data.size.toLong())
    private val path = "/v1/library/" + file.file

    @Before
    fun setUp() {
        server.start()
        dl = FileDownloader(OkHttpClient()) { server.url("/").toString() }
    }

    @After
    fun tearDown() = server.close()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun body(b: ByteArray, code: Int = 200) = MockResponse.Builder().code(code).body(Buffer().write(b)).build()

    @Test
    fun downloadsAndVerifies() = runTest {
        server.enqueue(body(data))
        val part = tmp.root.resolve("g.part")
        val seen = ArrayList<Long>()
        assertNull(dl.download(path, file, part) { seen += it })
        assertArrayEquals(data, part.readBytes())
        assertEquals(data.size.toLong(), seen.last())
        assertEquals(path, server.takeRequest().url.encodedPath)
    }

    @Test
    fun resumesWithRange() = runTest {
        val part = tmp.root.resolve("g.part")
        part.writeBytes(data.copyOf(100_000))
        server.enqueue(body(data.copyOfRange(100_000, data.size), code = 206))
        assertNull(dl.download(path, file, part))
        assertArrayEquals(data, part.readBytes())
        assertEquals("bytes=100000-", server.takeRequest().headers["Range"])
    }

    @Test
    fun serverIgnoringRangeRestarts() = runTest {
        val part = tmp.root.resolve("g.part")
        part.writeBytes(ByteArray(100_000))
        server.enqueue(body(data)) // 200: the whole file
        assertNull(dl.download(path, file, part))
        assertArrayEquals(data, part.readBytes())
    }

    @Test
    fun badChecksumDeletesPart() = runTest {
        val part = tmp.root.resolve("g.part")
        server.enqueue(body(data.copyOf().also { it[5] = 0x55 }))
        assertEquals(AppError.BadResponse("checksum mismatch for Genesis"), dl.download(path, file, part))
        assertFalse(part.exists())
    }

    @Test
    fun fetchDoesNotVerify() = runTest {
        val part = tmp.root.resolve("g.part")
        server.enqueue(body(data.copyOf().also { it[5] = 0x55 }))
        assertNull(dl.fetch(path, file, part)) // the bytes came: checking them is verify's job
        assertEquals(data.size.toLong(), part.length())
        assertEquals(AppError.BadResponse("checksum mismatch for Genesis"), dl.verify(file, part))
        assertFalse(part.exists())
    }

    @Test
    fun errors() = runTest {
        val part = tmp.root.resolve("g.part")
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(AppError.NotFound("Genesis"), dl.download(path, file, part))
        server.close()
        assertEquals(AppError.Network, dl.download(path, file, part))
    }

    @Test
    fun errorsSayWhetherToTryAgain() = runTest {
        val part = tmp.root.resolve("g.part")
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "7").build())
        assertEquals(AppError.Busy(7), dl.fetch(path, file, part))
        server.enqueue(MockResponse.Builder().code(503).build())
        assertTrue(dl.fetch(path, file, part)!!.transient) // the download stays queued
        server.enqueue(MockResponse.Builder().code(404).build())
        assertFalse(dl.fetch(path, file, part)!!.transient)
    }

    @Test
    fun noRoomIsSaidBeforeDownloading() = runTest {
        val huge = file.copy(bytes = Long.MAX_VALUE / 4)
        val e = dl.fetch(path, huge, tmp.root.resolve("g.part"))
        assertTrue("$e", e is AppError.Storage && e.needed == huge.bytes && e.free < huge.bytes)
        assertEquals(0, server.requestCount) // not a byte asked for
    }

    @Test
    fun aFileDownloadedWholeIsNotReadAgainToVerify() = runTest {
        server.enqueue(body(data))
        val part = tmp.root.resolve("g.part")
        assertNull(dl.fetch(path, file, part))
        // Its checksum was taken as it arrived. (Shown by changing the file behind the
        // downloader's back: a verify that read it would refuse it.)
        part.writeBytes(data.copyOf().also { it[5] = 0x55 })
        assertNull(dl.verify(file, part))
        // the remembered checksum is used once: the next verify reads the file
        assertEquals(AppError.BadResponse("checksum mismatch for Genesis"), dl.verify(file, part))
    }
}
