package org.asimon.sefaria.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A downloaded book; [file] is its library file name (its sha256 + ".sfb"). */
data class InstalledBook(val book: String, val he: String, val file: String, val bytes: Long) {
    val sha256 get() = file.substringBefore('.')
}

/** Server responses available without the network, by book title and cache key; null if absent. */
fun interface OfflineSource {
    suspend fun get(book: String, key: String): String?
}

/**
 * The downloaded library (SERVER_SPEC §7a), kept as downloaded in [dir]: book files
 * (<sha256>.sfb), the dictionary they were compressed with (<sha256>.dict) and the
 * links file (<sha256>.sfl). Reads inflate one block at a time; the last few
 * blocks, link tables and book entries stay decoded in small LRUs.
 */
class LibraryStore(private val dir: File) : OfflineSource {
    private val lock = Mutex()
    private var scanned = false
    private val books = HashMap<String, BookFile>()
    private val dicts = HashMap<String, ByteArray>()
    private var links: LinksFile? = null
    private val _installed = MutableStateFlow<Map<String, InstalledBook>>(emptyMap())
    val installed: StateFlow<Map<String, InstalledBook>> = _installed.asStateFlow()
    private val _bytes = MutableStateFlow(0L)
    /** Disk used by the library: books, links file and dictionary. */
    val bytes: StateFlow<Long> = _bytes.asStateFlow()

    private val blocks = lru<String, List<String>>(16) // "<file>|<lang>|<block>" -> lines
    private val records = lru<Int, LinkRecords>(4) // links file book index -> records
    private val metas = lru<Int, String>(256) // links file book index -> entry JSON

    /** Where a download of [file] is written before it is installed. */
    fun partFor(file: String) = File(dir, "$file.part")

    suspend fun scan() = locked { }

    /** The links file's sha256 (its name), if one is installed. */
    suspend fun linksSha(): String? = locked { links?.file?.name?.substringBefore('.') }

    suspend fun hasDict(sha: String): Boolean = locked { sha in dicts }

    suspend fun book(title: String): BookFile? = locked { books[title] }

    suspend fun linksFile(): LinksFile? = locked { links }

    /** A downloaded book's metadata, served for "book:<title>" as the server would. */
    override suspend fun get(book: String, key: String): String? =
        if (key == "book:$book") locked { books[book]?.meta() } else null

    /** Slots [start, end) of [t] in [b], or null if out of range. */
    suspend fun lines(b: BookFile, t: BookText, start: Int, end: Int): List<String>? = locked {
        if (start < 0 || end > t.slots || start > end) return@locked null
        val out = ArrayList<String>(end - start)
        if (start == end) return@locked out
        for (i in t.blockOf(start)..t.blockOf(end - 1)) {
            val lines = blockLocked(b, t, i)
            val first = t.first(i)
            out.addAll(lines.subList(maxOf(start - first, 0), minOf(end, t.end(i)) - first))
        }
        out
    }

    /** All lines of block [i] of [t] (for scanning). */
    suspend fun block(b: BookFile, t: BookText, i: Int): List<String> = locked { blockLocked(b, t, i) }

    private fun blockLocked(b: BookFile, t: BookText, i: Int): List<String> =
        blocks.getOrPut(b.file.name + "|" + t.lang + "|" + i) { b.block(t, i) }

    /** Link records of the links file's book [i]. */
    suspend fun records(i: Int): LinkRecords? = locked { links?.let { l -> records.getOrPut(i) { l.records(i) } } }

    /** The links file's entry for book [i] (BookDto JSON). */
    suspend fun linkMeta(i: Int): String? = locked { links?.let { l -> metas.getOrPut(i) { l.meta(i) } } }

    /** Installs a verified dictionary download. */
    suspend fun installDict(part: File, sha: String): String? = locked {
        val dst = File(dir, "$sha.dict")
        if (!part.renameTo(dst)) return@locked "cannot install ${dst.name}"
        dicts[sha] = dst.readBytes()
        updateBytes()
        null
    }

    /** Installs a verified book download, replacing an older file of the same book. */
    suspend fun installBook(title: String, part: File, file: String): String? = locked {
        val b = try {
            open(part)
        } catch (e: Exception) {
            part.delete()
            return@locked "not a book file: ${e.message}"
        }
        if (b.title != title) {
            part.delete()
            return@locked "not a book file of $title"
        }
        val dst = File(dir, file)
        if (!part.renameTo(dst)) return@locked "cannot install $file"
        books.remove(title)?.file?.takeIf { it != dst }?.delete()
        attach(dst)
        null
    }

    /** Installs a verified links file download, replacing the older one. */
    suspend fun installLinks(part: File, file: String): String? = locked {
        try {
            LinksFile(part)
        } catch (e: Exception) {
            part.delete()
            return@locked "not a links file: ${e.message}"
        }
        val dst = File(dir, file)
        if (!part.renameTo(dst)) return@locked "cannot install $file"
        links?.file?.takeIf { it != dst }?.delete()
        links = LinksFile(dst)
        records.clear()
        metas.clear()
        updateBytes()
        null
    }

    /** Removes a downloaded book; the links file goes with the last one. */
    suspend fun delete(title: String) = locked {
        books.remove(title)?.file?.delete()
        _installed.value -= title
        blocks.clear()
        if (books.isEmpty()) {
            links?.file?.delete()
            links = null
            records.clear()
            metas.clear()
        }
        updateBytes()
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!scanned) {
                scanned = true
                dir.mkdirs()
                val files = dir.listFiles().orEmpty()
                files.filter { it.name.endsWith(".dict") }.forEach { dicts[it.name.substringBefore('.')] = it.readBytes() }
                files.filter { it.name.endsWith(".sfb") }.sortedBy { it.lastModified() }.forEach { f ->
                    try {
                        val b = open(f)
                        books[b.title]?.file?.delete() // an older file of the same book
                        attach(f, b)
                    } catch (_: Exception) {
                        f.delete() // unreadable, or its dictionary is gone: download again
                    }
                }
                files.filter { it.name.endsWith(".sfl") }.sortedByDescending { it.lastModified() }.forEachIndexed { i, f ->
                    if (i > 0) f.delete() else links = try { LinksFile(f) } catch (_: Exception) { f.delete(); null }
                }
                updateBytes()
            }
            block()
        }
    }

    private fun open(f: File) = BookFile(f, dicts::get)

    private fun attach(f: File, b: BookFile = open(f)) {
        books[b.title] = b
        _installed.value += b.title to InstalledBook(b.title, b.header.he, f.name, f.length())
        updateBytes()
    }

    private fun updateBytes() {
        _bytes.value = dir.listFiles { f -> !f.name.endsWith(".part") }.orEmpty().sumOf { it.length() }
    }

    private fun <K, V> lru(n: Int) = object : LinkedHashMap<K, V>(n, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > n
    }
}
