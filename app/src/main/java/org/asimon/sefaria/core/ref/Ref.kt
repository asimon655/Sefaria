package org.asimon.sefaria.core.ref

import org.asimon.sefaria.core.text.Hebrew

/**
 * A text reference. Mirrors the server's internal/ref and passes the same test vectors.
 *
 * Accepted input forms (case-insensitive, "_" equals space):
 *   URL:    Genesis.1.1-5   Shulchan_Arukh,_Orach_Chayim.1.1   Berakhot.2a.5-3b.2
 *   Human:  Genesis 1:1-5   Shulchan Arukh, Orach Chayim 1:1   Pesach Haggadah, Kadesh 2
 *   Hebrew: בראשית א:א-ה    ברכות ב ע"א:ה
 *
 * Sections are 0-based indices; Talmud index 2 is daf "2a".
 */
data class Ref(
    val book: Book,
    val node: Node, // deepest node; jagged unless the ref is a whole complex node
    val sections: List<Int> = emptyList(),
    val to: List<Int>? = null, // range end (same length as sections), null if not a range
) {
    val isRange get() = to != null
    val isSegment get() = node.isJagged && to == null && sections.size == node.depth

    /** The slot range within the book; throws [RefException] with [RefError.OutOfRange]. */
    fun slots(): IntRange {
        val n = node
        if (!n.isJagged || sections.isEmpty()) return n.slotStart until n.slotEnd
        val shape = n.shape ?: throw RefException(RefError.OutOfRange, url())
        val r = shape.range(sections) ?: throw RefException(RefError.OutOfRange, url())
        var end = r.last + 1
        if (to != null) {
            val r2 = shape.range(to) ?: throw RefException(RefError.OutOfRange, url())
            if (r2.last + 1 < end) throw RefException(RefError.BadSection, "range end before start")
            end = r2.last + 1
        }
        return n.slotStart + r.first until n.slotStart + end
    }

    /** The ref without its last level (the section a segment is in). */
    fun section(): Ref = copy(sections = sections.dropLast(1), to = null)

    /** Canonical URL form: Shulchan_Arukh,_Orach_Chayim.1.1-5 */
    fun url(): String = format(false, ".", ".", ::label).replace(' ', '_')

    /** Sefaria's normal form: Shulchan Arukh, Orach Chayim 1:1-5 */
    fun human(): String = format(false, " ", ":", ::label)

    /** Hebrew form: שולחן ערוך, אורח חיים א:א-ה */
    fun hebrew(): String = format(true, " ", ":", ::heLabel)

    private fun format(he: Boolean, first: String, sep: String, label: (String, Int) -> String): String {
        val b = StringBuilder(if (he) book.he else book.title)
        for (n in node.path()) b.append(", ").append(if (he && n.he.isNotEmpty()) n.he else n.en)
        val types = node.addressTypes
        fun labels(idx: List<Int>, offset: Int) =
            idx.withIndex().joinToString(sep) { (i, v) -> label(types[offset + i], v) }
        if (sections.isNotEmpty()) b.append(first).append(labels(sections, 0))
        if (to != null && sections != to) {
            var k = 0
            while (k < to.size - 1 && to[k] == sections[k]) k++
            b.append('-').append(labels(to.drop(k), k))
        }
        return b.toString()
    }

    override fun toString() = url()

    companion object {
        /** Parses a ref in any accepted form; throws [RefException]. */
        fun parse(catalog: Catalog, input: String): Ref {
            val s = Hebrew.normalizeTitle(input)
            val (book, rest0) = matchBook(catalog, s)
                ?: throw RefException(RefError.UnknownTitle, input)
            var rest = rest0
            var node = book.root
            while (!node.isJagged) {
                rest = rest.trimStart(' ', ',', '.')
                if (rest.isEmpty()) return Ref(book, node)
                val m = matchChild(node, rest)
                if (m != null) {
                    node = m.first
                    rest = m.second
                } else {
                    node = node.defaultChild()
                        ?: throw RefException(RefError.UnknownTitle, "no section \"$rest\" in ${book.title}")
                }
            }
            return parseSections(Ref(book, node), rest.trimStart(' ', ',', '.', ':'))
        }

        /** Like [parse] but returns null instead of throwing. */
        fun parseOrNull(catalog: Catalog, input: String): Ref? =
            try { parse(catalog, input) } catch (_: RefException) { null }

        /** The segment ref of a slot in a book (slot in [0, book.slots)). */
        fun at(book: Book, slot: Int): Ref {
            var n = book.root
            while (!n.isJagged) {
                n = n.children!!.firstOrNull { slot >= it.slotStart && slot < it.slotEnd }
                    ?: return Ref(book, book.root)
            }
            return Ref(book, n, n.shape!!.path(slot - n.slotStart))
        }

        // cut positions where a title may end: end of string or before a separator
        private fun titleCuts(s: String): List<Int> {
            val cuts = arrayListOf(s.length)
            for (i in s.length - 1 downTo 1) if (s[i] in " ,.:") cuts.add(i)
            return cuts
        }

        private fun matchBook(c: Catalog, s: String): Pair<Book, String>? {
            for (i in titleCuts(s)) c.bookByTitle(s.substring(0, i))?.let { return it to s.substring(i) }
            return null
        }

        private fun matchChild(n: Node, s: String): Pair<Node, String>? {
            for (i in titleCuts(s)) n.child(s.substring(0, i))?.let { return it to s.substring(i) }
            return null
        }

        private fun parseSections(r: Ref, input: String): Ref {
            if (input.isEmpty()) return r
            val s = input.replace(" ע\"א", "a").replace(" ע\"ב", "b")
                .replace("ע\"א", "a").replace("ע\"ב", "b").replace('–', '-')
            val dash = s.indexOf('-')
            val from = if (dash < 0) s else s.substring(0, dash)
            val types = r.node.addressTypes
            val fromTokens = splitSections(from)
            val start = parseLabels(types, fromTokens)
            if (dash < 0) {
                // a bare daf number ("Nedarim 37") means both amudim
                val last = start.size - 1
                if (types[last] == "Talmud" && fromTokens[last].all(Char::isDigit)) {
                    return r.copy(sections = start, to = start.toMutableList().also { it[last]++ })
                }
                return r.copy(sections = start)
            }
            val endTokens = splitSections(s.substring(dash + 1))
            if (endTokens.size > start.size) throw RefException(RefError.BadSection, "range end longer than start")
            val k = start.size - endTokens.size // right-aligned: Genesis 1:1-5
            val end = parseLabels(types.drop(k), endTokens)
            return r.copy(sections = start, to = start.take(k) + end)
        }

        private fun splitSections(s: String): List<String> =
            s.split('.', ':', ' ', ',').filter { it.isNotEmpty() }

        private fun parseLabels(types: List<String>, tokens: List<String>): List<Int> =
            tokens.mapIndexed { i, t ->
                if (i >= types.size) throw RefException(RefError.BadSection, "too many sections")
                parseLabel(types[i], t)
            }

        /** Parses one section label ("12", "יב", "2a") to a 0-based index. */
        fun parseLabel(addrType: String, token: String): Int {
            var t = token
            if (addrType == "Talmud") {
                var amud = 0
                if (t.length > 1 && (t.last() == 'a' || t.last() == 'b')) {
                    if (t.last() == 'b') amud = 1
                    t = t.dropLast(1)
                }
                return (parseNumber(t) - 1) * 2 + amud
            }
            return parseNumber(t) - 1
        }

        private fun parseNumber(t: String): Int {
            t.toIntOrNull()?.let { if (it > 0) return it }
            Hebrew.parseGematria(t)?.let { if (it > 0) return it }
            throw RefException(RefError.BadSection, "\"$t\"")
        }

        /** Formats a 0-based section index ("2a", "12"). */
        fun label(addrType: String, i: Int): String =
            if (addrType == "Talmud") "${i / 2 + 1}${'a' + i % 2}" else "${i + 1}"

        /** Formats a 0-based section index in Hebrew ("ב ע\"א", "יב"). */
        fun heLabel(addrType: String, i: Int): String =
            if (addrType == "Talmud") Hebrew.gematria(i / 2 + 1) + (if (i % 2 == 0) " ע\"א" else " ע\"ב")
            else Hebrew.gematria(i + 1)
    }
}

enum class RefError { UnknownTitle, BadSection, OutOfRange }

class RefException(val error: RefError, message: String) : Exception("$error: $message")
