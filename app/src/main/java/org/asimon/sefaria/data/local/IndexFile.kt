package org.asimon.sefaria.data.local

import kotlinx.serialization.Serializable
import org.asimon.sefaria.data.remote.SefariaJson
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/*
 * A book index file (SERVER_SPEC §7a, "<sha256>.sfi"): which blocks of a book file hold each
 * word, so offline search inflates only those blocks. Mirrors the server's bookfile/index.go:
 *
 *   "SFI1" · header length (uint32 LE) · header JSON · per text: skip table, hashes, postings
 *
 * Hashes are Golomb–Rice coded gaps; a posting is the word's block count (Elias gamma), its
 * first block in ⌈log₂ blocks⌉ bits, then the gaps to its next blocks (Elias gamma).
 *
 * Words are hashed (FNV-1a 64, mixed by murmur3's finaliser) and keep the top [IndexText.bits]
 * bits, so about 1 in 256 lookups of an absent word finds a false match: an extra block to read,
 * never a wrong hit, since every candidate is verified.
 */

@Serializable
data class IndexText(val lang: String, val words: Int, val bits: Int, val blocks: Int = 0, val skip: Span, val hashes: Span, val postings: Span)

/** [sfb] names the book file whose blocks the index lists; [norm] is the normaliser version it was built with. */
@Serializable
data class IndexFileHeader(val format: Int, val book: String, val sfb: String, val norm: Int, val texts: List<IndexText> = emptyList())

class IndexFile(val file: File) {
    val header: IndexFileHeader
    private val data: ByteBuffer

    init {
        val buf = RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        buf.order(ByteOrder.LITTLE_ENDIAN)
        // relative bulk gets and slice(): the absolute forms need API 34
        val magic = ByteArray(4).also { buf.position(0); buf.get(it) }
        require(String(magic) == MAGIC) { "not an index file" }
        val n = buf.getInt(4)
        val hj = ByteArray(n).also { buf.position(8); buf.get(it) }
        header = SefariaJson.decodeFromString(IndexFileHeader.serializer(), String(hj))
        require(header.format == FORMAT) { "index format ${header.format}" }
        buf.position(8 + n)
        data = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    /** The blocks of the [lang] text that may hold [word] (a normalised token), ascending. */
    fun blocks(lang: String, word: String): IntArray {
        val t = header.texts.firstOrNull { it.lang == lang } ?: return EMPTY
        if (t.words == 0) return EMPTY
        val h = hash(word) ushr (64 - t.bits)
        val skip = t.skip.off.toInt()
        val groups = (t.skip.len / SKIP_SIZE).toInt()
        fun first(g: Int) = data.getLong(skip + g * SKIP_SIZE)
        // the last group whose first hash is <= h (hashes compare unsigned)
        var lo = 0
        var hi = groups
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (java.lang.Long.compareUnsigned(first(mid), h) > 0) hi = mid else lo = mid + 1
        }
        val g = lo - 1
        if (g < 0) return EMPTY
        val bits = BitReader(data, t.hashes.off.toInt(), data.getInt(skip + g * SKIP_SIZE + 8).toLong() and 0xFFFFFFFFL)
        val n = minOf(SKIP_EVERY, t.words - g * SKIP_EVERY)
        var cur = first(g)
        var j = -1
        for (k in 0 until n) {
            cur += bits.rice()
            val c = java.lang.Long.compareUnsigned(cur, h)
            if (c == 0) { j = k; break }
            if (c > 0) return EMPTY
        }
        if (j < 0) return EMPTY
        val p = BitReader(data, t.postings.off.toInt(), data.getInt(skip + g * SKIP_SIZE + 12).toLong() and 0xFFFFFFFFL)
        val width = bitLen(maxOf(t.blocks - 1, 0))
        repeat(j) {
            val n = p.gamma()
            p.skip(width)
            repeat((n - 1).toInt()) { p.gamma() }
        }
        val out = IntArray(p.gamma().toInt())
        out[0] = p.fixed(width).toInt()
        for (i in 1 until out.size) out[i] = out[i - 1] + p.gamma().toInt()
        return out
    }

    private fun bitLen(x: Int) = 32 - Integer.numberOfLeadingZeros(x)

    private class BitReader(private val b: ByteBuffer, private val base: Int, private var n: Long) {
        private fun bit(): Long {
            val byte = b.get(base + (n ushr 3).toInt()).toInt() and 0xFF
            val v = (byte ushr (7 - (n and 7).toInt())) and 1
            n++
            return v.toLong()
        }

        fun skip(bits: Int) {
            n += bits
        }

        fun fixed(bits: Int): Long {
            var x = 0L
            repeat(bits) { x = (x shl 1) or bit() }
            return x
        }

        /** Elias gamma: as many zeros as the value has bits after the first, then its bits. */
        fun gamma(): Long {
            var z = 0
            while (bit() == 0L) z++
            return (1L shl z) or fixed(z)
        }

        fun rice(): Long {
            var q = 0L
            while (bit() == 1L) q++
            var x = q shl RICE_K
            for (i in RICE_K - 1 downTo 0) x = x or (bit() shl i)
            return x
        }
    }

    companion object {
        const val MAGIC = "SFI1"
        const val FORMAT = 2
        const val SKIP_EVERY = 256
        const val RICE_K = 8
        const val SKIP_SIZE = 16
        /** search.NormalizeVersion of the normaliser this app implements; an index built with another is ignored. */
        const val NORMALIZE_VERSION = 1
        private val EMPTY = IntArray(0)

        /** FNV-1a 64 of the word's UTF-8 bytes, mixed by murmur3's 64-bit finaliser (as bookfile.Hash). */
        fun hash(word: String): Long {
            var h = -0x340d631b7bdddcdbL // 14695981039346656037
            for (b in word.toByteArray()) {
                h = h xor (b.toLong() and 0xFF)
                h *= 0x100000001b3L
            }
            h = h xor (h ushr 33)
            h *= -0xae502812aa7333L // 0xff51afd7ed558ccd
            h = h xor (h ushr 33)
            h *= -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
            h = h xor (h ushr 33)
            return h
        }
    }
}
