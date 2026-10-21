package org.asimon.sefaria.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.asimon.sefaria.data.remote.Bilingual
import org.asimon.sefaria.data.remote.LibraryDto
import org.asimon.sefaria.data.remote.LibraryFileDto
import org.asimon.sefaria.data.remote.TocDto
import org.asimon.sefaria.data.remote.TocNodeDto

/** What a library file holds (SERVER_SPEC §7a), in [ListedFile] and [InstalledFile]. */
object FileKind {
    const val BOOK = "book" // <sha256>.sfb
    const val INDEX = "index" // <sha256>.sfi, a book's search index
    const val DICT = "dict" // <sha256>.dict, the dictionary book files are compressed with
    const val LINKS = "links" // <sha256>.sfl
    const val FORMS = "forms" // <sha256>.sff as listed, inflated to <sha256>.forms when installed
}

/** A file the server's library offers for download (/v1/library), as of the build in [Synced]. */
@Entity(tableName = "listed_file", indices = [Index("book")])
data class ListedFile(
    @PrimaryKey val file: String,
    val kind: String,
    val book: String = "",
    val he: String = "",
    val bytes: Long,
    /** A book's index file, downloaded with it; "" if it has none. */
    @ColumnInfo(name = "index_file") val index: String = "",
    @ColumnInfo(name = "index_bytes") val indexBytes: Long = 0,
) {
    val sha256 get() = file.substringBefore('.')

    /** The book's index file as a file to download, if it has one. */
    val indexFile get() = if (index.isEmpty()) null else ListedFile(index, FileKind.INDEX, book, he, indexBytes)

    /** What downloading the book takes on the phone: its book file and index. */
    val downloadBytes get() = bytes + indexBytes
}

/** A file of the downloaded library (files/library) and what it holds. */
@Entity(tableName = "installed_file", indices = [Index("book")])
data class InstalledFile(
    @PrimaryKey val file: String,
    val kind: String,
    /** Book and index files: the book's title; [he] only for book files. */
    val book: String = "",
    val he: String = "",
    val bytes: Long,
    /** Index files: the book file they were built for, and their normaliser version. */
    val sfb: String = "",
    val norm: Int = 0,
)

/** A downloaded book; [file] is its book file, [bytes] counts its index file too. */
data class InstalledBook(val book: String, val he: String, val file: String, val bytes: Long)

/** Which server build the stored copy of [what] ([LISTING], [TOC]) came from. */
@Entity(tableName = "synced")
data class Synced(@PrimaryKey val what: String, val build: String) {
    companion object {
        const val LISTING = "listing"
        const val TOC = "toc"
    }
}

/** A node of the library's category tree; [id] is its pre-order position, [parent] null at the top. */
@Entity(tableName = "toc_node")
data class TocNode(
    @PrimaryKey val id: Int,
    val parent: Int?,
    val kind: String,
    val en: String,
    val he: String,
    val title: String,
    @ColumnInfo(name = "desc_en") val descEn: String,
    @ColumnInfo(name = "desc_he") val descHe: String,
    @ColumnInfo(name = "short_en") val shortEn: String,
    @ColumnInfo(name = "short_he") val shortHe: String,
    /** Books only: a commentary or targum, searched after the texts. */
    @ColumnInfo(defaultValue = "0") val dependent: Boolean = false,
)

/**
 * The library's own data: the server's listing, the downloaded files and the category tree.
 * Rows of the server's (listing, tree) are replaced whole, with the build they came from.
 */
@Dao
abstract class LibraryDao {
    // ---- the server's listing

    @Query("SELECT * FROM listed_file WHERE kind = 'book'")
    abstract fun listedBooks(): Flow<List<ListedFile>>

    @Query("SELECT * FROM listed_file WHERE kind = 'book' AND book = :book")
    abstract suspend fun listedBook(book: String): ListedFile?

    /** The listed dictionary, links file or forms table. */
    @Query("SELECT * FROM listed_file WHERE kind = :kind")
    abstract suspend fun listed(kind: String): ListedFile?

    /** Every file name the listing offers, index files included. */
    @Query("SELECT file FROM listed_file UNION SELECT index_file FROM listed_file WHERE index_file != ''")
    abstract suspend fun listedNames(): List<String>

    /**
     * What a first book download adds beyond the book: the listed dictionary, links file and
     * forms table that aren't downloaded (by sha256: an installed forms table is renamed).
     */
    @Query(
        """SELECT COALESCE(SUM(l.bytes), 0) FROM listed_file l WHERE l.kind IN ('dict', 'links', 'forms')
           AND NOT EXISTS (SELECT 1 FROM installed_file i WHERE i.kind = l.kind
             AND substr(i.file, 1, instr(i.file, '.')) = substr(l.file, 1, instr(l.file, '.')))"""
    )
    abstract fun firstDownloadBytes(): Flow<Long>

    /** Downloaded books, in the file the listing has, that lack the index file it offers. */
    @Query(
        """SELECT l.* FROM listed_file l JOIN installed_file b ON b.kind = 'book' AND b.file = l.file
           WHERE l.kind = 'book' AND l.index_file != ''
           AND NOT EXISTS (SELECT 1 FROM installed_file i WHERE i.kind = 'index' AND i.book = l.book)"""
    )
    abstract suspend fun missingIndexes(): List<ListedFile>

    @Transaction
    open suspend fun putListing(l: LibraryDto, build: String) {
        clearListing()
        fun row(f: LibraryFileDto, kind: String) = ListedFile(f.file, kind, f.book, f.he, f.bytes, f.index, f.indexBytes)
        insertListed(
            l.books.map { row(it, FileKind.BOOK) } + row(l.dict, FileKind.DICT) +
                listOfNotNull(l.links?.let { row(it, FileKind.LINKS) }, l.forms?.let { row(it, FileKind.FORMS) })
        )
        stamp(Synced(Synced.LISTING, build))
    }

    @Query("DELETE FROM listed_file")
    protected abstract suspend fun clearListing()

    @Insert
    protected abstract suspend fun insertListed(files: List<ListedFile>)

    // ---- the downloaded library

    @Query("SELECT * FROM installed_file")
    abstract suspend fun installedFiles(): List<InstalledFile>

    /** A book's book or index file, or (with no book) the dictionary, links file or forms table. */
    @Query("SELECT * FROM installed_file WHERE kind = :kind AND book = :book LIMIT 1")
    abstract suspend fun installed(kind: String, book: String = ""): InstalledFile?

    @Query("SELECT * FROM installed_file WHERE book IN (:books)")
    abstract suspend fun installedOf(books: Collection<String>): List<InstalledFile>

    @Query(INSTALLED_BOOKS)
    abstract fun installedBooks(): Flow<List<InstalledBook>>

    @Query(INSTALLED_BOOKS)
    abstract suspend fun installedBooksNow(): List<InstalledBook>

    @Query("SELECT EXISTS(SELECT 1 FROM installed_file WHERE kind = 'book')")
    abstract suspend fun hasBooks(): Boolean

    /** Disk used by the downloaded library. */
    @Query("SELECT COALESCE(SUM(bytes), 0) FROM installed_file")
    abstract fun installedBytes(): Flow<Long>

    /** Forgets the files [drop] and records [put], in one step. */
    @Transaction
    open suspend fun replaceInstalled(drop: Collection<String>, put: List<InstalledFile>) {
        if (drop.isNotEmpty()) dropInstalled(drop)
        if (put.isNotEmpty()) putInstalled(put)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putInstalled(files: List<InstalledFile>)

    @Query("DELETE FROM installed_file WHERE file IN (:files)")
    protected abstract suspend fun dropInstalled(files: Collection<String>)

    // ---- the category tree

    /** The stored tree, with the build it came from; null if none is stored. */
    @Transaction
    open suspend fun toc(): TocDto? {
        val build = synced(Synced.TOC) ?: return null
        return TocDto(build, tocTree(tocNodes()))
    }

    @Transaction
    open suspend fun putToc(toc: TocDto) {
        clearToc()
        insertToc(tocRows(toc.tree))
        stamp(Synced(Synced.TOC, toc.buildId))
    }

    @Query("SELECT * FROM toc_node ORDER BY id")
    protected abstract suspend fun tocNodes(): List<TocNode>

    @Query("DELETE FROM toc_node")
    protected abstract suspend fun clearToc()

    @Insert
    protected abstract suspend fun insertToc(nodes: List<TocNode>)

    /**
     * Downloaded books in offline search order (the server's library order): texts, then
     * commentaries and targumim, each in TOC order; books missing from the TOC go last, by title.
     */
    @Query(
        """SELECT b.book FROM installed_file b
            LEFT JOIN toc_node n ON n.kind = 'book' AND n.title = b.book
            WHERE b.kind = 'book'
            ORDER BY n.dependent, n.id IS NULL, n.id, b.book"""
    )
    abstract suspend fun searchOrder(): List<String>

    // ---- builds

    @Query("SELECT build FROM synced WHERE what = :what")
    abstract suspend fun synced(what: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun stamp(s: Synced)

    private companion object {
        /** Each book file with its size and its index file's. */
        const val INSTALLED_BOOKS = """SELECT b.book, b.he, b.file, b.bytes + COALESCE(i.bytes, 0) AS bytes
            FROM installed_file b LEFT JOIN installed_file i ON i.kind = 'index' AND i.book = b.book
            WHERE b.kind = 'book'"""
    }
}

/** The tree's nodes in pre-order, each with its parent's position. */
fun tocRows(tree: List<TocNodeDto>): List<TocNode> {
    val out = ArrayList<TocNode>()
    fun add(n: TocNodeDto, parent: Int?) {
        val id = out.size
        out += TocNode(id, parent, n.kind, n.en, n.he, n.title, n.desc.en, n.desc.he, n.short.en, n.short.he, n.dependent)
        n.children.forEach { add(it, id) }
    }
    tree.forEach { add(it, null) }
    return out
}

/** The tree back from [tocRows]' nodes (in id order). */
fun tocTree(rows: List<TocNode>): List<TocNodeDto> {
    val children = rows.groupBy { it.parent }
    fun node(r: TocNode): TocNodeDto = TocNodeDto(
        r.kind, r.en, r.he, r.title, Bilingual(r.descEn, r.descHe), Bilingual(r.shortEn, r.shortHe),
        children[r.id].orEmpty().map(::node), r.dependent,
    )
    return children[null].orEmpty().map(::node)
}
