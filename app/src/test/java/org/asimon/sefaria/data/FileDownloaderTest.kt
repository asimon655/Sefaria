package org.asimon.sefaria.data

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.asimon.sefaria.data.remote.LibraryFileDto
import org.asimon.sefaria.data.repo.AppError
import org.asimon.sefaria.data.repo.FileDownloader
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
    private val file = LibraryFileDto("Genesis", file = sha(data) + ".sfb", bytes = data.size.toLong())
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
    fun errors() = runTest {
        val part = tmp.root.resolve("g.part")
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(AppError.NotFound("Genesis"), dl.download(path, file, part))
        server.close()
        assertEquals(AppError.Network, dl.download(path, file, part))
    }
}
