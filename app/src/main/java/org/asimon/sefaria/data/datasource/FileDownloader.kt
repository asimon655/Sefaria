package org.asimon.sefaria.data.datasource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.asimon.sefaria.data.local.ListedFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import org.asimon.sefaria.data.AppError

/**
 * Downloads a library file, resuming a partial one with a Range request ([fetch]), and verifies
 * its size and sha256, its name ([verify]). [download] does both.
 */
class FileDownloader(private val client: Call.Factory, private val baseUrl: suspend () -> String) {
    /** Downloads and verifies [part]; null on success. */
    suspend fun download(path: String, f: ListedFile, part: File, progress: (Long) -> Unit = {}): AppError? =
        fetch(path, f, part, progress) ?: verify(f, part)

    /** Downloads [part] without checking it; null once it has all its bytes. */
    suspend fun fetch(path: String, f: ListedFile, part: File, progress: (Long) -> Unit = {}): AppError? = withContext(Dispatchers.IO) {
        val url = (baseUrl().trimEnd('/') + path).toHttpUrlOrNull()
            ?: return@withContext AppError.BadResponse("bad URL $path")
        val what = f.book.ifEmpty { f.file }
        try {
            var have = if (part.length() <= f.bytes) part.length() else 0L.also { part.delete() }
            if (have < f.bytes) {
                val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                client.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        206 -> Unit
                        200 -> have = 0
                        else -> return@withContext if (resp.code == 404) AppError.NotFound(what)
                            else AppError.Server(resp.code, "HTTP ${resp.code}")
                    }
                    FileOutputStream(part, have > 0).use { out ->
                        val input = resp.body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        var reported = System.nanoTime()
                        while (true) {
                            coroutineContext.ensureActive() // cancel keeps the partial file for a resume
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            // a few times a second: more only redraws the library list for nothing
                            if (System.nanoTime() - reported >= PROGRESS_NS) {
                                progress(have)
                                reported = System.nanoTime()
                            }
                        }
                    }
                }
            }
            progress(part.length())
            null
        } catch (e: IOException) {
            AppError.Network
        }
    }

    /** Checks [part]'s size and sha256; a mismatch deletes it. Null if it is good. */
    suspend fun verify(f: ListedFile, part: File): AppError? = withContext(Dispatchers.IO) {
        val ok = try {
            part.length() == f.bytes && sha256(part) == f.sha256
        } catch (e: IOException) {
            false
        }
        if (ok) return@withContext null
        part.delete()
        AppError.BadResponse("checksum mismatch for ${f.book.ifEmpty { f.file }}")
    }

    private companion object {
        const val PROGRESS_NS = 250_000_000L
    }

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
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
