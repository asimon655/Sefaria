package org.asimon.sefaria.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The downloaded library's folder ([dir]) and its installed_file rows: the one place that records,
 * replaces and deletes library files. Writes run one at a time ([writing]). Every change bumps
 * [version] and is reported to the [listeners] (LibraryReader keeps its open files and caches in
 * step with them).
 *
 * An install writes its file before its row, and drops a replaced file's row before the file, so a
 * run that ends midway leaves at most a stray file or a row without a file; [tidy] clears both.
 */
class LibraryFiles(val dir: File, private val dao: InstalledDao) {
    /** Told about each row added ([present] true) or dropped (false), inside the write that did it. */
    fun interface Listener {
        fun changed(row: InstalledFile, present: Boolean)
    }

    private val writes = Mutex()
    private val changes = AtomicLong()
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Bumped by every install and delete: a search session made before a change is stale. */
    val version: Long get() = changes.get()

    /** Downloaded books by title. */
    val installed: Flow<Map<String, InstalledBook>> = dao.installedBooks().map { l -> l.associateBy { it.book } }

    /** Disk used by the library: books, index files, links file, forms table and dictionary. */
    val bytes: Flow<Long> = dao.installedBytes()

    fun listen(l: Listener) {
        listeners += l
    }

    /** Downloaded books by title, now. */
    suspend fun installedNow(): Map<String, InstalledBook> = io { dao.installedBooksNow().associateBy { it.book } }

    /** Where a download of [file] is written before it is installed. */
    fun partFor(file: String) = File(dir, "$file.part")

    fun file(name: String) = File(dir, name)

    /** A book's book or index file, or (with no book) the dictionary, links file or forms table. */
    suspend fun row(kind: String, book: String = ""): InstalledFile? = dao.installed(kind, book)

    suspend fun rows(): List<InstalledFile> = dao.installedFiles()

    suspend fun rowsOf(books: Collection<String>): List<InstalledFile> = dao.installedOf(books)

    suspend fun hasBooks(): Boolean = dao.hasBooks()

    /** Runs [block] as the only write: installs and deletes one at a time. */
    suspend fun <T> writing(block: suspend () -> T): T = io { writes.withLock { block() } }

    /** Records [new] in place of [old], then deletes the old files. Call inside [writing]. */
    suspend fun replace(old: List<InstalledFile>, new: InstalledFile) {
        changes.incrementAndGet()
        dao.replaceInstalled(old.map { it.file }, listOf(new))
        old.forEach(::forget)
        notify(new, true)
    }

    /** Deletes files and their rows. Call inside [writing]. */
    suspend fun remove(rows: List<InstalledFile>) {
        if (rows.isEmpty()) return
        changes.incrementAndGet()
        dao.replaceInstalled(rows.map { it.file }, emptyList())
        rows.forEach(::forget)
    }

    /** Deletes [title]'s unreadable file of [kind] (a book file takes its index along): download it again. */
    suspend fun dropBroken(title: String, kind: String) = writing {
        remove(listOfNotNull(row(kind, title), if (kind == FileKind.BOOK) row(FileKind.INDEX, title) else null))
    }

    /** Deletes partial downloads of files not in [keep] (files of an older build, which never resume). */
    suspend fun dropParts(keep: Set<String>) = io {
        dir.listFiles { f -> f.name.endsWith(".part") && f.name.removeSuffix(".part") !in keep }.orEmpty().forEach { it.delete() }
    }

    /**
     * Tidies up after a run that ended mid-install (MaintenanceWorker): files without a row are
     * deleted, and rows of files that are gone dropped. Partial downloads (.part) stay for their
     * downloads to resume. Not done at start: listing thousands of files held up the first search,
     * and a stray file only costs disk until then.
     */
    suspend fun tidy() = writing {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }.orEmpty().associateBy { it.name }
        val rows = dao.installedFiles()
        val known = rows.mapTo(HashSet()) { it.file }
        files.values.filter { it.name !in known }.forEach { it.delete() }
        remove(rows.filter { it.file !in files })
    }

    private fun forget(row: InstalledFile) {
        File(dir, row.file).delete()
        notify(row, false)
    }

    private fun notify(row: InstalledFile, present: Boolean) = listeners.forEach { it.changed(row, present) }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
