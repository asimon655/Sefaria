package org.asimon.sefaria.data.local

import org.asimon.sefaria.core.text.FormsFile
import java.io.File
import java.util.zip.InflaterInputStream

/**
 * Installs verified downloads into the library and deletes downloaded books. Each install checks
 * that the file is what it claims to be, moves it into place and records it in [files], replacing
 * an older file of the same kind; [reader] then follows the new rows. An install answers null, or
 * why it failed (the download is deleted then).
 */
class LibraryInstaller(private val files: LibraryFiles, private val reader: LibraryReader) {
    /** Installs a verified dictionary download. */
    suspend fun installDict(part: File, sha: String): String? = writing {
        val dst = files.file("$sha.dict")
        if (!part.renameTo(dst)) return@writing "cannot install ${dst.name}"
        files.replace(emptyList(), InstalledFile(dst.name, FileKind.DICT, bytes = dst.length()))
        null
    }

    /** Installs a verified book download, replacing an older file of the same book (and its index). */
    suspend fun installBook(title: String, part: File, file: String): String? = writing {
        val b = try {
            BookFile(part) { reader.dict(it) }
        } catch (e: Exception) {
            part.delete()
            return@writing "not a book file: ${e.message}"
        }
        if (b.title != title) {
            part.delete()
            return@writing "not a book file of $title"
        }
        val dst = files.file(file)
        if (!part.renameTo(dst)) return@writing "cannot install $file"
        val old = listOfNotNull(
            files.row(FileKind.BOOK, title)?.takeIf { it.file != file },
            files.row(FileKind.INDEX, title)?.takeIf { it.sfb != file },
        )
        files.replace(old, InstalledFile(file, FileKind.BOOK, title, b.header.he, dst.length()))
        null
    }

    /** Installs a verified index file download for [title]'s installed book file. */
    suspend fun installIndex(title: String, part: File, file: String): String? = writing {
        val h = try {
            IndexFile(part).header
        } catch (e: Exception) {
            part.delete()
            return@writing "not an index file: ${e.message}"
        }
        if (h.book != title || h.sfb != files.row(FileKind.BOOK, title)?.file) {
            part.delete()
            return@writing "index file of another book file"
        }
        if (h.norm != IndexFile.NORMALIZE_VERSION) { // built for another normaliser: search scans the book
            part.delete()
            return@writing null
        }
        val dst = files.file(file)
        if (!part.renameTo(dst)) return@writing "cannot install $file"
        val old = listOfNotNull(files.row(FileKind.INDEX, title)?.takeIf { it.file != file })
        files.replace(old, InstalledFile(file, FileKind.INDEX, title, bytes = dst.length(), sfb = h.sfb, norm = h.norm))
        null
    }

    /** Installs a verified forms table download (zlib) by inflating it, replacing the older one. */
    suspend fun installForms(part: File, sha: String): String? = writing {
        val dst = files.file("$sha.forms")
        try {
            InflaterInputStream(part.inputStream()).use { input -> dst.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            dst.delete()
            return@writing "not a forms table: ${e.message}"
        } finally {
            part.delete()
        }
        try {
            FormsFile(dst)
        } catch (e: Exception) {
            dst.delete()
            return@writing "not a forms table: ${e.message}"
        }
        files.replace(listOfNotNull(files.row(FileKind.FORMS)?.takeIf { it.file != dst.name }), InstalledFile(dst.name, FileKind.FORMS, bytes = dst.length()))
        null
    }

    /** Installs a verified links file download, replacing the older one. */
    suspend fun installLinks(part: File, file: String): String? = writing {
        try {
            LinksFile(part)
        } catch (e: Exception) {
            part.delete()
            return@writing "not a links file: ${e.message}"
        }
        val dst = files.file(file)
        if (!part.renameTo(dst)) return@writing "cannot install $file"
        files.replace(listOfNotNull(files.row(FileKind.LINKS)?.takeIf { it.file != file }), InstalledFile(file, FileKind.LINKS, bytes = dst.length()))
        null
    }

    /** Removes a downloaded book; the links file goes with the last one. */
    suspend fun delete(title: String) = deleteAll(listOf(title))

    /** Removes downloaded books in one go; the links file and forms table go with the last one. */
    suspend fun deleteAll(titles: Collection<String>) = writing {
        files.remove(files.rowsOf(titles.toSet()))
        if (!files.hasBooks()) files.remove(listOfNotNull(files.row(FileKind.LINKS), files.row(FileKind.FORMS)))
    }

    // The reader's shared files first (a book file needs its dictionary), then the write.
    private suspend fun <T> writing(block: suspend () -> T): T {
        reader.load()
        return files.writing(block)
    }
}
