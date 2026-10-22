package org.asimon.sefaria.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.text.Forms
import org.asimon.sefaria.core.text.FormsFile
import java.io.IOException
import java.util.zip.DataFormatException

/** Server responses available without the network, by book title and cache key; null if absent. */
fun interface OfflineSource {
    suspend fun get(book: String, key: String): String?
}

/**
 * How search reads downloaded books: [LibraryReader] for a one-off read, a search session
 * (data/repo/SearchSession.kt) for a whole search, which keeps what it opens to itself.
 */
interface BookReader {
    suspend fun book(title: String): BookFile?
    /** A book's index file, if it has one built for its installed book file. */
    suspend fun index(title: String): IndexFile?
    /** The lines of block [i] of [t]; null if the file can't be read (deleted meanwhile). */
    suspend fun block(f: BookFile, t: BookText, i: Int): List<String>?
}

/**
 * Reads the downloaded library (SERVER_SPEC §7) in [files]: book files (<sha256>.sfb), their index
 * files (<sha256>.sfi), the dictionary they were compressed with (<sha256>.dict), the links file
 * (<sha256>.sfl) and the Hebrew forms table, inflated (<sha256 of the download>.forms). Reads
 * inflate one block at a time.
 *
 * What stays in memory, and for how long:
 * - for the app's lifetime, what every read needs: the dictionaries, the links file's header,
 *   the forms table's line offsets;
 * - in small LRUs, what the reader is reading: the last [BOOKS] book headers, [BLOCKS] blocks,
 *   [RECORDS] books' link records and [METAS] link entries;
 * - nothing for search: it reads through a session of its own ([openBook], [openIndex],
 *   [readBlock]), which ends with the search screen.
 *
 * The shared files load on first use ([load]) and then follow the installed_file rows as
 * [LibraryFiles] reports them, so installs and deletes never call the reader.
 *
 * Locking: reads take no lock while they read or inflate; the caches are guarded by
 * `synchronized` for the moment of a lookup or an insert, so the reader never waits behind a
 * search's block reads. A read racing a delete may find its file gone: it then answers null, as
 * for a book that isn't downloaded.
 */
class LibraryReader(private val files: LibraryFiles) : OfflineSource, BookReader {
    @Volatile private var loaded = false
    @Volatile private var dicts: Map<String, ByteArray> = emptyMap()
    @Volatile private var links: LinksFile? = null
    @Volatile private var forms: Pair<String, FormsFile>? = null // (sha256 of the download, table)

    // caches; each guarded by synchronized on itself
    private val books = lru<String, BookFile>(BOOKS) // title -> opened book file
    private val blocks = lru<String, List<String>>(BLOCKS) // "<file>|<lang>|<block>" -> lines
    private val records = lru<Int, LinkRecords>(RECORDS) // links file book index -> records
    private val metas = lru<Int, String>(METAS) // links file book index -> entry JSON

    init {
        files.listen(::changed)
    }

    /** Loads the shared files now (they load on first use otherwise). */
    suspend fun load() {
        if (!loaded) files.writing { if (!loaded) { loadLocked(); loaded = true } }
    }

    /** The dictionary named [sha], if downloaded (book files name the one they need). */
    fun dict(sha: String): ByteArray? = dicts[sha]

    /** The book file installed for [title] (without opening it). */
    suspend fun bookFile(title: String): String? = io { files.row(FileKind.BOOK, title)?.file }

    /** The links file's sha256 (its name), if one is installed. */
    suspend fun linksSha(): String? = io { links?.file?.name?.substringBefore('.') }

    suspend fun hasDict(sha: String): Boolean = io { sha in dicts }

    /** [title]'s book file, from the cache or opened now; null if not downloaded. */
    override suspend fun book(title: String): BookFile? = io {
        synchronized(books) { books[title] }?.let { return@io it }
        val row = files.row(FileKind.BOOK, title) ?: return@io null
        val f = openBook(row.file, title) ?: return@io null
        synchronized(books) { books.getOrPut(title) { f } }
    }

    suspend fun linksFile(): LinksFile? = io { links }

    /** A downloaded book's index file, opened now and not kept (search sessions keep their own). */
    override suspend fun index(title: String): IndexFile? = io {
        val row = files.row(FileKind.INDEX, title) ?: return@io null
        openIndex(row.file, title)
    }

    /** Whether a downloaded book has its index file (without opening it). */
    suspend fun hasIndex(title: String): Boolean = io { files.row(FileKind.INDEX, title) != null }

    /** The Hebrew forms table, if downloaded. */
    suspend fun forms(): Forms? = io { forms?.second }

    /** The sha256 of the downloaded forms table, if any. */
    suspend fun formsSha(): String? = io { forms?.first }

    /** A downloaded book's metadata, served for "book:<title>" as the server would. */
    override suspend fun get(book: String, key: String): String? =
        if (key == "book:$book") io { book(book)?.let { f -> readOrNull { f.meta() } } } else null

    /** Slots [start, end) of [t] in [b], or null if out of range or unreadable. */
    suspend fun lines(b: BookFile, t: BookText, start: Int, end: Int): List<String>? = io {
        if (start < 0 || end > t.slots || start > end) return@io null
        val out = ArrayList<String>(end - start)
        if (start == end) return@io out
        for (i in t.blockOf(start)..t.blockOf(end - 1)) {
            val lines = cachedBlock(b, t, i) ?: return@io null
            val first = t.first(i)
            out.addAll(lines.subList(maxOf(start - first, 0), minOf(end, t.end(i)) - first))
        }
        out
    }

    /** All lines of block [i] of [t], through the cache. */
    override suspend fun block(f: BookFile, t: BookText, i: Int): List<String>? = io { cachedBlock(f, t, i) }

    /**
     * Block [i] of [t] for a search: the cached copy if there is one, else read now and not kept,
     * so a search doesn't push out what the reader is reading. Blocking: call on a worker thread.
     */
    fun readBlock(f: BookFile, t: BookText, i: Int): List<String>? =
        synchronized(blocks) { blocks[key(f, t, i)] } ?: readOrNull { f.block(t, i) }

    /**
     * Opens book file [file] of [title] without caching it; an unreadable one is deleted with its
     * index (download it again). Blocking: call on a worker thread.
     */
    suspend fun openBook(file: String, title: String): BookFile? = try {
        BookFile(files.file(file)) { dicts[it] }
    } catch (_: Exception) {
        if (files.file(file).exists()) files.dropBroken(title, FileKind.BOOK) // gone: a delete raced this read
        null
    }

    /** Opens index file [file] of [title] without caching it; an unreadable one is deleted. Blocking. */
    suspend fun openIndex(file: String, title: String): IndexFile? = try {
        IndexFile(files.file(file))
    } catch (_: Exception) {
        if (files.file(file).exists()) files.dropBroken(title, FileKind.INDEX)
        null
    }

    /** Link records of the links file's book [i]. */
    suspend fun records(i: Int): LinkRecords? = io {
        val l = links ?: return@io null
        synchronized(records) { records[i] }?.let { return@io it }
        val r = readOrNull { l.records(i) } ?: return@io null
        synchronized(records) { records.getOrPut(i) { r } }
    }

    /** The links file's entry for book [i] (BookDto JSON). */
    suspend fun linkMeta(i: Int): String? = io {
        val l = links ?: return@io null
        synchronized(metas) { metas[i] }?.let { return@io it }
        val m = readOrNull { l.meta(i) } ?: return@io null
        synchronized(metas) { metas.getOrPut(i) { m } }
    }

    // Follows the installed_file rows: opens what was installed, lets go of what went.
    private fun changed(row: InstalledFile, present: Boolean) {
        when (row.kind) {
            FileKind.BOOK -> {
                synchronized(books) { books.remove(row.book) }
                if (!present) synchronized(blocks) { blocks.clear() }
            }
            FileKind.DICT -> if (present) dicts = dicts + (sha(row) to files.file(row.file).readBytes())
            FileKind.LINKS -> {
                links = if (present) LinksFile(files.file(row.file)) else links?.takeIf { it.file.name != row.file }
                synchronized(records) { records.clear() }
                synchronized(metas) { metas.clear() }
            }
            FileKind.FORMS -> forms = if (present) sha(row) to FormsFile(files.file(row.file)) else forms?.takeIf { it.first != sha(row) }
        }
    }

    // Runs [block] on the IO pool once the shared files are loaded. No lock: see the class comment.
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        load()
        block()
    }

    private fun key(f: BookFile, t: BookText, i: Int) = f.file.name + "|" + t.lang + "|" + i

    private fun cachedBlock(f: BookFile, t: BookText, i: Int): List<String>? {
        val k = key(f, t, i)
        synchronized(blocks) { blocks[k] }?.let { return it }
        val lines = readOrNull { f.block(t, i) } ?: return null
        return synchronized(blocks) { blocks.getOrPut(k) { lines } }
    }

    // A read that fails because the file went away (a delete) or is damaged answers null.
    private inline fun <T> readOrNull(f: () -> T): T? = try {
        f()
    } catch (_: IOException) {
        null
    } catch (_: DataFormatException) {
        null
    }

    /**
     * Loads what every book shares: the dictionaries, links file and forms table. A file that
     * is missing or unreadable loses its row (download it again); book and index files open
     * when read.
     */
    private suspend fun loadLocked() {
        files.dir.mkdirs()
        val d = HashMap<String, ByteArray>()
        for (r in files.rows()) {
            if (r.kind != FileKind.DICT && r.kind != FileKind.LINKS && r.kind != FileKind.FORMS) continue
            val f = files.file(r.file)
            try {
                when (r.kind) {
                    FileKind.DICT -> d[sha(r)] = f.readBytes()
                    FileKind.LINKS -> links = LinksFile(f)
                    FileKind.FORMS -> forms = sha(r) to FormsFile(f)
                }
            } catch (_: Exception) {
                files.remove(listOf(r))
            }
        }
        dicts = d
    }

    private fun sha(row: InstalledFile) = row.file.substringBefore('.')

    private fun <K, V> lru(n: Int) = object : LinkedHashMap<K, V>(n, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > n
    }

    companion object {
        /** Book headers the reader keeps open: the books on screen and in the connections panel. */
        const val BOOKS = 32
        const val BLOCKS = 16
        const val RECORDS = 4
        const val METAS = 256
    }
}
