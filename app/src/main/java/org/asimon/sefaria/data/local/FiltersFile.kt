package org.asimon.sefaria.data.local

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/*
 * The word filter file (SERVER_SPEC §7.3, "<sha256>.sfw"): a binary fuse filter per book of the
 * library, of the hashes (IndexFile.hash) of every word of the book, so offline search can skip
 * a book that lacks a query word without opening its index file. Mirrors the server's
 * bookfile/filters.go and fuse/fuse.go, little-endian:
 *
 *   "SFW1" · format · normaliser version · books · group size · groups (uint32 each)
 *   per book, then per group, 56 bytes: book file sha256 (32), seed (uint64),
 *     fingerprints offset, segment length, segment count (uint32 each), 4 zero bytes
 *   the fingerprints, (segment count + 2) × segment length bytes a filter
 *
 * A filter says "maybe" for every word of its book and for about 1 absent word in 256. The file
 * is memory-mapped and read in place; group filters, if any, are not used.
 */
class FiltersFile(val file: File) {
    private val buf: ByteBuffer
    private val books: Int
    private val bySfb: HashMap<String, Int>

    init {
        buf = RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        buf.order(ByteOrder.LITTLE_ENDIAN)
        require(buf.limit() >= HEADER) { "not a filter file" }
        val magic = ByteArray(4).also { buf.position(0); buf.get(it) }
        require(String(magic) == MAGIC) { "not a filter file" }
        require(buf.getInt(4) == FORMAT) { "filter file format ${buf.getInt(4)}" }
        books = buf.getInt(12)
        val entries = books.toLong() + buf.getInt(20)
        require(books >= 0 && entries >= books && HEADER + ENTRY * entries <= buf.limit()) { "truncated filter file" }
        bySfb = HashMap(books * 2)
        val sha = ByteArray(32)
        for (i in 0 until books) {
            val e = HEADER + ENTRY * i
            val len = size(e)
            require(len == 0L || (segLen(e) and (segLen(e) - 1)) == 0 && off(e) + len <= buf.limit()) { "filter $i out of range" }
            buf.position(e)
            buf.get(sha)
            bySfb[hex(sha) + ".sfb"] = i
        }
    }

    /** The normaliser version the words were normalised with (search.NormalizeVersion). */
    val norm: Int get() = buf.getInt(8)

    /** The filter of book file [sfb] (its name), or null if this file has none for it. */
    fun filter(sfb: String): BookFilter? = bySfb[sfb]?.let { BookFilter(this, HEADER + ENTRY * it) }

    /** Whether the filter at entry [e] may hold the word hashed to [key]. */
    internal fun mayHold(e: Int, key: Long): Boolean {
        val segLen = segLen(e)
        val count = buf.getInt(e + 48).toLong() and 0xFFFFFFFFL
        if (count == 0L) return false
        val h = mix(key, buf.getLong(e + 32))
        val a = mulHi(h, count * segLen).toInt()
        val mask = segLen - 1
        val b = (a + segLen) xor ((h ushr 18).toInt() and mask)
        val c = (a + 2 * segLen) xor (h.toInt() and mask)
        val off = off(e).toInt()
        val fp = (h xor (h ushr 32)).toInt() and 0xFF
        return fp == (buf.get(off + a).toInt() xor buf.get(off + b).toInt() xor buf.get(off + c).toInt()) and 0xFF
    }

    private fun segLen(e: Int) = buf.getInt(e + 44)
    private fun off(e: Int) = buf.getInt(e + 40).toLong() and 0xFFFFFFFFL
    private fun size(e: Int): Long {
        val count = buf.getInt(e + 48).toLong() and 0xFFFFFFFFL
        return if (count == 0L) 0 else (count + 2) * (buf.getInt(e + 44).toLong() and 0xFFFFFFFFL)
    }

    companion object {
        const val MAGIC = "SFW1"
        const val FORMAT = 1
        private const val HEADER = 24
        private const val ENTRY = 56

        // murmur3's 64-bit finaliser of key + seed (fuse.mix)
        private fun mix(key: Long, seed: Long): Long {
            var h = key + seed
            h = h xor (h ushr 33)
            h *= -0xae502812aa7333L // 0xff51afd7ed558ccd
            h = h xor (h ushr 33)
            h *= -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
            return h xor (h ushr 33)
        }

        /** The high 64 bits of the unsigned product a × b (Math.unsignedMultiplyHigh needs API 35). */
        internal fun mulHi(a: Long, b: Long): Long {
            val m = 0xFFFFFFFFL
            val aLo = a and m
            val aHi = a ushr 32
            val bLo = b and m
            val bHi = b ushr 32
            val t = aHi * bLo + (aLo * bLo ushr 32)
            val w = (t and m) + aLo * bHi
            return aHi * bHi + (t ushr 32) + (w ushr 32)
        }

        private fun hex(b: ByteArray): String {
            val digits = "0123456789abcdef"
            val s = CharArray(b.size * 2)
            for (i in b.indices) {
                s[2 * i] = digits[(b[i].toInt() shr 4) and 15]
                s[2 * i + 1] = digits[b[i].toInt() and 15]
            }
            return String(s)
        }
    }
}

/** One book's word filter, in [file]. */
class BookFilter internal constructor(private val file: FiltersFile, private val entry: Int) {
    /** Whether the book may hold [word] (a normalised token): always true if it does. */
    fun mayHold(word: String): Boolean = file.mayHold(entry, IndexFile.hash(word))
}
