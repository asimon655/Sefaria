package org.asimon.sefaria.data.datasource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.asimon.sefaria.core.debugAssert
import org.asimon.sefaria.data.local.ListedFile
import org.asimon.sefaria.data.remote.await
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import org.asimon.sefaria.data.AppError

/**
 * Downloads a library file, resuming a partial one with a Range request ([fetch]), and verifies
 * its size and sha256, its name ([verify]). [download] does both.
 *
 * A file downloaded in one go is hashed as it arrives, so verifying it doesn't read it again; a
 * resumed one is read once, by [verify]. Either way it is synced to disk before it counts as
 * downloaded: an install renames it into place, and a rename of unsynced data can leave an empty
 * file behind after a power cut.
 */
class FileDownloader(private val client: Call.Factory, private val baseUrl: suspend () -> String) {
    // sha256 of the .part files this process downloaded whole, by path, for [verify]
    private val hashed = ConcurrentHashMap<String, String>()

    /** Downloads and verifies [part]; null on success. */
    suspend fun download(path: String, f: ListedFile, part: File, progress: (Long) -> Unit = {}): AppError? =
        fetch(path, f, part, progress) ?: verify(f, part)

    /** Downloads [part] without checking it; null once it has all its bytes. */
    suspend fun fetch(path: String, f: ListedFile, part: File, progress: (Long) -> Unit = {}): AppError? = withContext(Dispatchers.IO) {
        val url = (baseUrl().trimEnd('/') + path).toHttpUrlOrNull()
            ?: return@withContext AppError.BadResponse("bad URL $path")
        val what = f.book.ifEmpty { f.file }
        val start = if (part.length() <= f.bytes) part.length() else 0L.also { part.delete() }
        if (start < f.bytes) {
            // no room: say so now, rather than fail halfway and call it the network
            val free = (part.parentFile ?: part).usableSpace
            if (free < f.bytes - start + SPARE_BYTES) return@withContext AppError.Storage(f.bytes - start, free)
            val req = Request.Builder().url(url).apply { if (start > 0) header("Range", "bytes=$start-") }.build()
            val job = coroutineContext
            val err: AppError? = try {
                client.newCall(req).await { resp ->
                    val have = when (resp.code) {
                        206 -> start
                        200 -> 0L
                        404 -> return@await AppError.NotFound(what)
                        429 -> return@await AppError.Busy(resp.header("Retry-After")?.toIntOrNull() ?: 1)
                        else -> return@await AppError.Server(resp.code, "HTTP ${resp.code}")
                    }
                    copy(resp.body.byteStream(), part, have, f, progress) { job.ensureActive() }
                }
            } catch (e: IOException) {
                AppError.Network
            }
            if (err != null) return@withContext err
        }
        progress(part.length())
        null
    }

    // Appends [input] to [part] from byte [have] on. A failed write is the phone's storage, not
    // the network: it is told apart here, where the two can be.
    private fun copy(input: java.io.InputStream, part: File, have: Long, f: ListedFile, progress: (Long) -> Unit, active: () -> Unit): AppError? {
        var done = have
        val digest = if (have == 0L) MessageDigest.getInstance("SHA-256") else null
        hashed.remove(part.path)
        val out = try {
            FileOutputStream(part, have > 0)
        } catch (e: IOException) {
            return storage(part, f.bytes - have)
        }
        out.use {
            val buf = ByteArray(64 * 1024)
            var reported = System.nanoTime()
            while (true) {
                active() // cancel keeps the partial file for a resume
                val n = input.read(buf) // an IOException here is the network's
                if (n < 0) break
                try {
                    out.write(buf, 0, n)
                } catch (e: IOException) {
                    return storage(part, f.bytes - done)
                }
                digest?.update(buf, 0, n)
                done += n
                // a few times a second: more only redraws the library list for nothing
                if (System.nanoTime() - reported >= PROGRESS_NS) {
                    progress(done)
                    reported = System.nanoTime()
                }
            }
            try {
                out.fd.sync()
            } catch (e: IOException) {
                return storage(part, 0)
            }
        }
        debugAssert(part.length() == done) { "${part.name}: wrote up to byte $done, the file has ${part.length()}" }
        if (digest != null && done == f.bytes) hashed[part.path] = digest.digest().hex()
        return null
    }

    private fun storage(part: File, needed: Long) = AppError.Storage(needed, (part.parentFile ?: part).usableSpace)

    /** Checks [part]'s size and sha256; a mismatch deletes it. Null if it is good. */
    suspend fun verify(f: ListedFile, part: File): AppError? = withContext(Dispatchers.IO) {
        val ok = try {
            part.length() == f.bytes && (hashed.remove(part.path) ?: sha256(part)) == f.sha256
        } catch (e: IOException) {
            false
        }
        if (ok) return@withContext null
        part.delete()
        AppError.BadResponse("checksum mismatch for ${f.book.ifEmpty { f.file }}")
    }

    private companion object {
        const val PROGRESS_NS = 250_000_000L
        /** Room left free beyond a download: the databases and the cache need some too. */
        const val SPARE_BYTES = 16L * 1024 * 1024
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private suspend fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive() // cancel keeps the whole file: the next try only verifies
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().hex()
    }
}
