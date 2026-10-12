package org.asimon.sefaria.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.zip.InflaterInputStream

/** A downloaded book. */
data class InstalledPack(val book: String, val he: String, val build: String, val bytes: Long)

/** A full-text match in a downloaded book: [section] and [i] locate its text in the stored section response. */
data class PackHit(val book: String, val slot: Int, val ref: String, val human: String, val heRef: String, val section: String, val i: Int)

/** Server responses stored in downloaded books, by book title and cache key; null if absent. */
fun interface PackSource {
    suspend fun get(book: String, key: String): String?
}

/**
 * Downloaded books (offline packs, SERVER_SPEC §7): one read-only SQLite file per book in
 * [dir], holding the server's responses zlib-compressed under the response-cache keys
 * ("book:Genesis", "text:Genesis.1|he=d,en=d", "links:Genesis.1.1"). Files are used as
 * downloaded. Connections stay open; a mutex serializes access since a connection is not
 * safe for concurrent use.
 */
class PackStore(private val dir: File, private val driver: BundledSQLiteDriver = BundledSQLiteDriver()) : PackSource {
    private val lock = Mutex()
    private val open = HashMap<String, SQLiteConnection>()
    private var scanned = false
    private val _installed = MutableStateFlow<Map<String, InstalledPack>>(emptyMap())
    val installed: StateFlow<Map<String, InstalledPack>> = _installed.asStateFlow()

    fun fileFor(book: String) = File(dir, name(book) + ".sqlite")

    /** Where a download of [book] with checksum [sha256] is written before it is installed. */
    fun partFor(book: String, sha256: String) = File(dir, name(book) + "." + sha256.take(16) + ".part")

    /** Deletes partial downloads of [book] other than [keep]. */
    fun dropParts(book: String, keep: File) {
        val prefix = name(book) + "."
        dir.listFiles()?.filter { it.name.startsWith(prefix) && it.name.endsWith(".part") && it != keep }?.forEach { it.delete() }
    }

    suspend fun scan() = locked { }

    override suspend fun get(book: String, key: String): String? = locked {
        val c = open[book] ?: return@locked null
        c.prepare("SELECT z FROM response WHERE key = ?").use { st ->
            st.bindText(1, key)
            if (st.step()) InflaterInputStream(st.getBlob(0).inputStream()).readBytes().decodeToString() else null
        }
    }

    /**
     * Full-text search in [book]'s pack: up to [limit] hits after slot [after] in reading order,
     * for an FTS5 [expr] (SearchText.expr) over [lang]'s table. Empty if the book is not
     * downloaded or its pack predates search.
     */
    suspend fun search(book: String, lang: String, expr: String, after: Int, limit: Int): List<PackHit> = locked {
        require(lang == "he" || lang == "en")
        val c = open[book] ?: return@locked emptyList()
        try {
            c.prepare(
                "SELECT s.slot, s.ref, s.human, s.he_ref, s.section, s.i FROM $lang JOIN seg s ON s.slot = $lang.rowid " +
                    "WHERE $lang MATCH ? AND $lang.rowid > ? ORDER BY $lang.rowid LIMIT ?"
            ).use { st ->
                st.bindText(1, expr)
                st.bindLong(2, after.toLong())
                st.bindLong(3, limit.toLong())
                buildList {
                    while (st.step()) add(PackHit(book, st.getInt(0), st.getText(1), st.getText(2), st.getText(3), st.getText(4), st.getInt(5)))
                }
            }
        } catch (_: Exception) { // a pack built before search has no index
            emptyList()
        }
    }

    /**
     * Installs a verified download as [book]'s pack, replacing an older one. Returns an error
     * message if the file is not a pack of this book in a format this app reads.
     */
    suspend fun install(book: String, file: File): String? = locked {
        val meta = try {
            read(file)
        } catch (e: Exception) {
            file.delete()
            return@locked "not a pack: ${e.message}"
        }
        if (meta == null || meta.book != book) {
            file.delete()
            return@locked "not a pack of $book"
        }
        open.remove(book)?.close()
        val dst = fileFor(book)
        if (!file.renameTo(dst)) return@locked "cannot install ${dst.name}"
        attach(dst)
        null
    }

    suspend fun delete(book: String) = locked {
        open.remove(book)?.close()
        fileFor(book).delete()
        _installed.value -= book
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!scanned) {
                scanned = true
                dir.mkdirs()
                dir.listFiles { f -> f.name.endsWith(".sqlite") }?.forEach { f ->
                    try {
                        attach(f)
                    } catch (_: Exception) {
                        f.delete() // unreadable or from an older app: download again
                    }
                }
            }
            block()
        }
    }

    // Opens a pack and lists it; throws if it is not a readable pack.
    private fun attach(f: File) {
        val c = driver.open(f.path, SQLITE_OPEN_READONLY)
        val m = try {
            meta(c)
        } catch (e: Exception) {
            c.close()
            throw e
        }
        if (m == null) {
            c.close()
            throw IllegalStateException("unsupported pack ${f.name}")
        }
        open.put(m.book, c)?.close()
        _installed.value += m.book to m.copy(bytes = f.length())
    }

    private fun read(f: File): InstalledPack? {
        val c = driver.open(f.path, SQLITE_OPEN_READONLY)
        try {
            return meta(c)
        } finally {
            c.close()
        }
    }

    // null if the pack's format is not one this app reads
    private fun meta(c: SQLiteConnection): InstalledPack? {
        val m = HashMap<String, String>()
        c.prepare("SELECT key, value FROM meta").use { st -> while (st.step()) m[st.getText(0)] = st.getText(1) }
        if (m["format"] != FORMAT.toString()) return null
        return InstalledPack(m["book"] ?: return null, m["he"].orEmpty(), m["build_id"].orEmpty(), 0)
    }

    private fun name(book: String) = URLEncoder.encode(book, "UTF-8")

    companion object {
        const val FORMAT = 1
    }
}
