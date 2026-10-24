package org.asimon.sefaria.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.asimon.sefaria.data.remote.LibraryDto
import org.asimon.sefaria.data.remote.LibraryFileDto

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
    /** Books: queued for download (or stopped by the network), so a restart resumes it. */
    @ColumnInfo(defaultValue = "0") val queued: Boolean = false,
) {
    val sha256 get() = file.substringBefore('.')

    /** The book's index file as a file to download, if it has one. */
    val indexFile get() = if (index.isEmpty()) null else ListedFile(index, FileKind.INDEX, book, he, indexBytes)

    /** What downloading the book takes on the phone: its book file and index. */
    val downloadBytes get() = bytes + indexBytes
}

/** An optional library file the listing offers ([FileKind.LINKS] or [FileKind.FILTERS]); [installed]: one of its kind is downloaded. */
data class OptionalFile(val kind: String, val file: String, val bytes: Long, val installed: Boolean)

/**
 * The server's listing of library files, replaced whole with the build it came from, and the
 * download queue kept in it (listed_file.queued).
 */
@Dao
abstract class ListingDao {
    @Query("SELECT * FROM listed_file WHERE kind = 'book'")
    abstract fun listedBooks(): Flow<List<ListedFile>>

    @Query("SELECT * FROM listed_file WHERE kind = 'book' AND book = :book")
    abstract suspend fun listedBook(book: String): ListedFile?

    /** The listed dictionary, links file, forms table or filter file. */
    @Query("SELECT * FROM listed_file WHERE kind = :kind")
    abstract suspend fun listed(kind: String): ListedFile?

    /** Every file name the listing offers, index files included. */
    @Query("SELECT file FROM listed_file UNION SELECT index_file FROM listed_file WHERE index_file != ''")
    abstract suspend fun listedNames(): List<String>

    /**
     * What a first book download adds beyond the book: the listed dictionary and forms table that
     * aren't downloaded (by sha256: an installed forms table is renamed).
     */
    @Query(
        """SELECT COALESCE(SUM(l.bytes), 0) FROM listed_file l WHERE l.kind IN ('dict', 'forms')
           AND NOT EXISTS (SELECT 1 FROM installed_file i WHERE i.kind = l.kind
             AND substr(i.file, 1, instr(i.file, '.')) = substr(l.file, 1, instr(l.file, '.')))"""
    )
    abstract fun firstDownloadBytes(): Flow<Long>

    /**
     * The optional files the listing offers (the links file and the filter file), each with
     * whether a file of its kind is downloaded (an older one counts: the library sync updates it).
     */
    @Query(
        """SELECT l.kind AS kind, l.file AS file, l.bytes AS bytes,
             EXISTS(SELECT 1 FROM installed_file i WHERE i.kind = l.kind) AS installed
           FROM listed_file l WHERE l.kind IN ('links', 'filters') ORDER BY l.kind"""
    )
    abstract fun optionalFiles(): Flow<List<OptionalFile>>

    /** Downloaded books, in the file the listing has, that lack the index file it offers. */
    @Query(
        """SELECT l.* FROM listed_file l JOIN installed_file b ON b.kind = 'book' AND b.file = l.file
           WHERE l.kind = 'book' AND l.index_file != ''
           AND NOT EXISTS (SELECT 1 FROM installed_file i WHERE i.kind = 'index' AND i.book = l.book)"""
    )
    abstract suspend fun missingIndexes(): List<ListedFile>

    /** Replaces the listing, keeping which books are queued. */
    @Transaction
    open suspend fun putListing(l: LibraryDto, build: String) {
        val queued = queuedBooks().toHashSet()
        clearListing()
        fun row(f: LibraryFileDto, kind: String) = ListedFile(f.file, kind, f.book, f.he, f.bytes, f.index, f.indexBytes, kind == FileKind.BOOK && f.book in queued)
        insertListed(
            l.books.map { row(it, FileKind.BOOK) } + row(l.dict, FileKind.DICT) +
                listOfNotNull(
                    l.links?.let { row(it, FileKind.LINKS) },
                    l.forms?.let { row(it, FileKind.FORMS) },
                    l.filters?.let { row(it, FileKind.FILTERS) },
                )
        )
        stampBuild(build)
    }

    /** The build the stored listing came from; null if none is stored. */
    @Query("SELECT build FROM synced WHERE what = '${Synced.LISTING}'")
    abstract suspend fun build(): String?

    /** Records that the stored listing is [build]'s (it didn't change in that build). */
    @Query("INSERT OR REPLACE INTO synced(what, build) VALUES('${Synced.LISTING}', :build)")
    abstract suspend fun stampBuild(build: String)

    @Query("DELETE FROM listed_file")
    protected abstract suspend fun clearListing()

    @Insert
    protected abstract suspend fun insertListed(files: List<ListedFile>)

    // ---- the download queue

    /** Books queued for download, as last saved. */
    @Query("SELECT book FROM listed_file WHERE queued AND kind = 'book'")
    abstract suspend fun queuedBooks(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM listed_file WHERE queued)")
    abstract suspend fun hasQueued(): Boolean

    /** Saves the queue: exactly [books] are queued (books not listed are left out). */
    @Transaction
    open suspend fun setQueued(books: Collection<String>) {
        clearQueued()
        // in chunks: a query has at most 32,766 parameters, and the library could outgrow one
        books.chunked(QUEUE_CHUNK).forEach { markQueued(it) }
    }

    @Query("UPDATE listed_file SET queued = 0 WHERE queued")
    protected abstract suspend fun clearQueued()

    @Query("UPDATE listed_file SET queued = 1 WHERE kind = 'book' AND book IN (:books)")
    protected abstract suspend fun markQueued(books: List<String>)

    private companion object {
        const val QUEUE_CHUNK = 10_000
    }
}
