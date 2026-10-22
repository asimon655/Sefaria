package org.asimon.sefaria.data.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.asimon.sefaria.data.remote.SefariaJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/*
 * Library files (SERVER_SPEC §7), read as downloaded: a header, then zlib blocks
 * inflated one at a time when the reader needs them.
 *
 *   book file  "SFB1" · header length (uint32 LE) · header JSON · blocks
 *   links file "SFL1" · header length (uint32 LE) · header JSON · per-book records
 */

@Serializable
data class Span(val off: Long, val len: Long)

@Serializable
data class BookFileHeader(
    val format: Int,
    val book: String,
    val he: String = "",
    val dict: String,
    val meta: Span,
    val texts: List<BookText> = emptyList(),
)

/** One version's text: a line per slot. Block i holds slots [blocks[i][0], blocks[i+1][0]). */
@Serializable
data class BookText(val lang: String, val version: Int, val slots: Int, val blocks: List<List<Long>>) {
    fun first(i: Int) = blocks[i][0].toInt()
    fun end(i: Int) = if (i + 1 < blocks.size) first(i + 1) else slots

    /** The block holding [slot]. */
    fun blockOf(slot: Int): Int {
        var lo = 0
        var hi = blocks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (first(mid) <= slot) lo = mid else hi = mid - 1
        }
        return lo
    }
}

@Serializable
data class LinksFileHeader(
    val format: Int,
    @SerialName("link_types") val linkTypes: List<String> = emptyList(),
    val categories: List<CategoryName> = emptyList(),
    val books: List<LinksBook> = emptyList(),
)

@Serializable
data class CategoryName(val en: String, val he: String = "")

/** [file] is the book file the records were built against. */
@Serializable
data class LinksBook(val title: String, val file: String, val meta: Span, val links: Span, val n: Int)

/** A book's link records as columns, sorted by source start (links.bin order). */
class LinkRecords(
    val srcStart: IntArray,
    val srcEnd: IntArray,
    val dstBook: IntArray,
    val dstStart: IntArray,
    val dstEnd: IntArray,
    val type: IntArray,
) {
    val size get() = srcStart.size
    /** The longest source interval, to binary search for links overlapping a range. */
    val span: Int = (0 until size).maxOfOrNull { srcEnd[it] - srcStart[it] } ?: 0

    fun srcCoarse(i: Int) = type[i] and SRC_COARSE != 0
    fun typeId(i: Int) = type[i] and TYPE_MASK

    /** Records touching slots [a, b), in file order. */
    fun overlapping(a: Int, b: Int): List<Int> {
        var lo = 0
        var hi = size
        while (lo < hi) { // first record with srcStart >= a - span
            val mid = (lo + hi) / 2
            if (srcStart[mid] >= a - span) hi = mid else lo = mid + 1
        }
        val out = ArrayList<Int>()
        for (i in lo until size) {
            if (srcStart[i] >= b) break
            if (srcEnd[i] > a) out += i
        }
        return out
    }

    companion object {
        const val SRC_COARSE = 1 shl 31
        const val TYPE_MASK = (1 shl 30) - 1

        /** Decodes uvarint columns: source start (delta), source length, target book, target start, target length, type. */
        fun decode(p: ByteArray, n: Int): LinkRecords {
            var pos = 0
            fun next(): Int {
                var x = 0L
                var shift = 0
                while (true) {
                    val b = p[pos++].toInt() and 0xff
                    x = x or ((b and 0x7f).toLong() shl shift)
                    if (b < 0x80) return x.toInt()
                    shift += 7
                }
            }
            val ss = IntArray(n); val se = IntArray(n); val db = IntArray(n)
            val ds = IntArray(n); val de = IntArray(n); val ty = IntArray(n)
            for (i in 0 until n) ss[i] = next() + if (i > 0) ss[i - 1] else 0
            for (i in 0 until n) se[i] = ss[i] + next()
            for (i in 0 until n) db[i] = next()
            for (i in 0 until n) ds[i] = next()
            for (i in 0 until n) de[i] = ds[i] + next()
            for (i in 0 until n) ty[i] = next()
            return LinkRecords(ss, se, db, ds, de, ty)
        }
    }
}

/** An opened library file: its header, and its data read on demand. */
open class LibraryFile(val file: File, magic: String) {
    protected val dataStart: Long
    protected val headerJson: String

    init {
        RandomAccessFile(file, "r").use { f ->
            val pre = ByteArray(8)
            f.readFully(pre)
            if (String(pre, 0, 4, Charsets.US_ASCII) != magic) throw DataFormatException("not a $magic file")
            val n = (pre[4].toInt() and 0xff) or ((pre[5].toInt() and 0xff) shl 8) or
                ((pre[6].toInt() and 0xff) shl 16) or ((pre[7].toInt() and 0xff) shl 24)
            if (n < 0 || 8L + n > f.length()) throw DataFormatException("truncated header")
            val h = ByteArray(n)
            f.readFully(h)
            headerJson = h.decodeToString()
            dataStart = 8L + n
        }
    }

    protected fun read(s: Span): ByteArray {
        if (s.off < 0 || s.len < 0 || dataStart + s.off + s.len > file.length()) throw DataFormatException("span out of range")
        return RandomAccessFile(file, "r").use { f ->
            f.seek(dataStart + s.off)
            ByteArray(s.len.toInt()).also(f::readFully)
        }
    }
}

/**
 * A downloaded book: its metadata (the /v1/books response) and its default versions' text.
 * [dicts] finds the preset dictionary the header names.
 */
class BookFile(file: File, dicts: (String) -> ByteArray?) : LibraryFile(file, "SFB1") {
    val header: BookFileHeader = SefariaJson.decodeFromString(BookFileHeader.serializer(), headerJson)
    private val dict: ByteArray

    init {
        if (header.format != FORMAT) throw DataFormatException("book file format ${header.format}")
        dict = dicts(header.dict) ?: throw DataFormatException("missing dictionary ${header.dict}")
    }

    val title get() = header.book

    fun meta(): String = inflate(read(header.meta), dict).decodeToString()

    fun text(lang: String): BookText? = header.texts.firstOrNull { it.lang == lang }

    /** The lines of block [i] of [t] (one per slot). */
    fun block(t: BookText, i: Int): List<String> {
        val b = t.blocks[i]
        val s = inflate(read(Span(b[1], b[2])), dict).decodeToString()
        return s.removeSuffix("\n").split('\n')
    }

    companion object {
        const val FORMAT = 1
    }
}

/** The library links file: every book's link records and a short entry per book. */
class LinksFile(file: File) : LibraryFile(file, "SFL1") {
    val header: LinksFileHeader = SefariaJson.decodeFromString(LinksFileHeader.serializer(), headerJson)
    private val index = header.books.withIndex().associate { (i, b) -> b.title to i }

    init {
        if (header.format != BookFile.FORMAT) throw DataFormatException("links file format ${header.format}")
    }

    fun indexOf(title: String): Int? = index[title]

    /** Book [i]'s entry (BookDto fields: titles, categories, dependence, schema). */
    fun meta(i: Int): String = inflate(read(header.books[i].meta), null).decodeToString()

    fun records(i: Int): LinkRecords = LinkRecords.decode(inflate(read(header.books[i].links), null), header.books[i].n)
}

/** Inflates a zlib stream, with the preset dictionary [dict] if it asks for one. */
fun inflate(z: ByteArray, dict: ByteArray?): ByteArray {
    val inf = Inflater()
    try {
        inf.setInput(z)
        val out = ByteArrayOutputStream(maxOf(64, z.size * 4))
        val buf = ByteArray(64 * 1024)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0) {
                when {
                    inf.needsDictionary() -> inf.setDictionary(dict ?: throw DataFormatException("needs a dictionary"))
                    inf.needsInput() -> throw DataFormatException("truncated block")
                    !inf.finished() -> throw DataFormatException("inflate stalled")
                }
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    } finally {
        inf.end()
    }
}
