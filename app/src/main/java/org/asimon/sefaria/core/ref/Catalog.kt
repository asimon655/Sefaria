package org.asimon.sefaria.core.ref

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import org.asimon.sefaria.core.text.Hebrew
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.NodeDto
import org.asimon.sefaria.data.remote.OffsetsDto

/**
 * The shape of a jagged array (mirrors the server's catalog.Shape): at the segment level
 * it is a count [n]; above it, [sub] holds one child shape per section (null = empty).
 */
class Shape(n: Int, val sub: List<Shape?>?) {
    val n: Int = sub?.size ?: n
    private val starts: IntArray // starts[i] = slots before sub[i]
    val size: Int

    init {
        if (sub == null) {
            starts = IntArray(0)
            size = n
        } else {
            starts = IntArray(sub.size + 1)
            for (i in sub.indices) starts[i + 1] = starts[i] + (sub[i]?.size ?: 0)
            size = starts[sub.size]
        }
    }

    /** The slot range covered by a section path (0-based, possibly shorter than the depth), or null if out of range. */
    fun range(path: List<Int>): IntRange? {
        var off = 0
        var cur = this
        for (i in path) {
            if (i < 0 || i >= cur.n) return null
            val s = cur.sub ?: return off + i until off + i + 1 // segment level
            off += cur.starts[i]
            cur = s[i] ?: return null // an empty section does not exist
        }
        return off until off + cur.size
    }

    /** The full section path of a slot (inverse of [range]). */
    fun path(slot: Int): List<Int> {
        val p = ArrayList<Int>(3)
        var cur: Shape? = this
        var s = slot
        while (cur != null) {
            val sub = cur.sub ?: return p.apply { add(s) }
            var lo = 0
            var hi = sub.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (cur.starts[mid] <= s) lo = mid else hi = mid - 1
            }
            while (lo < sub.size - 1 && cur.starts[lo + 1] <= s) lo++ // skip empty children
            p.add(lo)
            s -= cur.starts[lo]
            cur = sub[lo]
        }
        return p
    }

    companion object {
        fun parse(e: JsonElement?): Shape? = when (e) {
            null, JsonNull -> null
            is JsonPrimitive -> Shape(e.int, null)
            is JsonArray -> Shape(0, e.map(::parse))
            else -> null
        }
    }
}

/** A schema node: complex nodes have [children]; jagged-array nodes have [addressTypes] and a [shape]. */
class Node(
    val key: String,
    val en: String,
    val he: String,
    val altTitles: List<String>,
    val isDefault: Boolean,
    val children: List<Node>?,
    val addressTypes: List<String>,
    val sectionNames: List<String>,
    val shape: Shape?,
    val slotStart: Int,
    val slotEnd: Int,
    val offsets: OffsetsDto? = null,
) {
    var parent: Node? = null
        private set
    private val byTitle = HashMap<String, Node>()

    val isJagged get() = children == null
    val depth get() = addressTypes.size

    init {
        children?.forEach { ch ->
            ch.parent = this
            if (!ch.isDefault) {
                for (t in listOf(ch.key, ch.en, ch.he) + ch.altTitles) {
                    val k = Hebrew.normalizeTitle(t)
                    if (k.isNotEmpty()) byTitle.putIfAbsent(k, ch)
                }
            }
        }
    }

    fun child(title: String): Node? = byTitle[Hebrew.normalizeTitle(title)]

    /**
     * Numbering offset of [level] (0-based) given the indices above it: index i is labeled
     * i + 1 + offset. Mirrors catalog.Node.Offset on the server.
     */
    fun offset(level: Int, above: List<Int>): Int {
        val o = offsets ?: return 0
        return when (level) {
            0 -> o.top
            1 -> above.firstOrNull()?.let { o.sub.getOrNull(it) } ?: 0
            else -> 0
        }
    }
    fun defaultChild(): Node? = children?.firstOrNull { it.isDefault }

    /** The named nodes from the book root (exclusive) down to this node. */
    fun path(): List<Node> {
        val p = ArrayList<Node>()
        var x: Node? = this
        while (x?.parent != null) {
            if (!x.isDefault) p.add(0, x)
            x = x.parent
        }
        return p
    }

    companion object {
        fun from(d: NodeDto): Node = Node(
            key = d.key, en = d.en, he = d.he, altTitles = d.altTitles, isDefault = d.default,
            children = d.children?.map(::from),
            addressTypes = d.addressTypes, sectionNames = d.sectionNames,
            shape = Shape.parse(d.shape), slotStart = d.slotStart, slotEnd = d.slotEnd, offsets = d.offsets,
        )
    }
}

class Book(val dto: BookDto) {
    val title get() = dto.title
    val he get() = dto.he
    val root: Node = Node.from(dto.schema)
    val slots get() = root.slotEnd
}

/** Books by any of their titles (primary first, so an alternate title never shadows a primary one). */
class Catalog(val books: List<Book>) {
    private val byTitle = HashMap<String, Book>()

    init {
        fun add(t: String, b: Book) {
            val k = Hebrew.normalizeTitle(t)
            if (k.isNotEmpty()) byTitle.putIfAbsent(k, b)
        }
        for (b in books) {
            add(b.title, b)
            add(b.he, b)
        }
        for (b in books) for (t in b.dto.altTitles) {
            add(t, b)
            add(t.removeSuffix("."), b) // "Ber." also matches "Ber 2a"
        }
    }

    fun bookByTitle(title: String): Book? = byTitle[Hebrew.normalizeTitle(title)]
}
