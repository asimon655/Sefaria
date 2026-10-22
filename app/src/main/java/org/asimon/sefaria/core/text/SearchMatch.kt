package org.asimon.sefaria.core.text

import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * The search matcher and the Hebrew forms table (SERVER_SPEC §6.6). Mirrors the server's
 * internal/search (match.go, forms.go), so offline hits are the server's hits for the books
 * on the phone; checked against the same match.tsv and forms.tsv vectors.
 */

/** How a query word matched: the word itself, one of its prefix forms, or another form. A lower kind is a better match. */
enum class Kind(val wire: String) {
    EXACT("exact"), PREFIX("prefix"), FORM("form");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire }
    }
}

/** A query word and the tokens it matches. [prefix]: word*, any token starting with [text]. */
data class Word(val text: String, val prefix: Boolean = false, val forms: Map<String, Kind> = emptyMap()) {
    fun match(tok: String): Kind? =
        if (tok == text || prefix && tok.startsWith(text)) Kind.EXACT else forms[tok]
}

/** A query term: one word, or a phrase whose words must be adjacent and in order. Every clause must match. */
typealias Clause = List<Word>

/** Which forms a query uses: none ([off]), also all forms of the words' headwords ([all]), minus [drop]. */
data class Expansion(val off: Boolean = false, val all: Boolean = false, val drop: Set<String> = emptySet()) {
    companion object {
        /** The options column of match.tsv: "-", "off", "all" or "drop=w1,w2". */
        fun parse(s: String): Expansion = when {
            s == "off" -> Expansion(off = true)
            s == "all" -> Expansion(all = true)
            s.startsWith("drop=") -> Expansion(drop = s.removePrefix("drop=").split(',').map(SearchText::normalize).toSet())
            else -> Expansion()
        }
    }
}

/** Where a clause matched: the token positions its occurrences start at, and its best kind. */
data class ClauseHit(val kind: Kind, val pos: List<Int>)

/** A verified match: the worst of the clauses' best kinds, and where each clause matched. */
data class Hit(val kind: Kind, val clauses: List<ClauseHit>)

/** A query word's line in the forms table: its prefix forms and dominant headwords. */
data class FormEntry(val prefix: List<String>, val heads: List<String>)

/** The Hebrew forms table: a word's entry, and the words of a headword. */
interface Forms {
    fun entry(word: String): FormEntry?
    fun group(head: String): List<String>

    /** The forms [q] also matches, without [q]: its prefix forms and, if [all], its headwords' other words with their prefix forms. */
    fun expand(q: String, all: Boolean): Map<String, Kind> {
        val kind = LinkedHashMap<String, Kind>()
        val e = entry(q) ?: return kind
        for (f in e.prefix) kind[f] = Kind.PREFIX
        if (!all) return kind
        for (h in e.heads) for (g in group(h)) {
            if (g == q) continue
            kind.putIfAbsent(g, Kind.FORM)
            entry(g)?.prefix?.forEach { f -> if (f != q) kind.putIfAbsent(f, Kind.FORM) }
        }
        return kind
    }
}

/** A forms table read from its TSV (hebrew_forms.tsv): "word\tprefix forms\theadwords" and "=headword\twords". */
class TsvForms(text: String) : Forms {
    private val words = HashMap<String, FormEntry>()
    private val groups = HashMap<String, List<String>>()

    init {
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val f = line.split('\t')
            if (f.size == 2 && f[0].startsWith("=")) groups[f[0].substring(1)] = fields(f[1])
            else if (f.size == 3) words[f[0]] = FormEntry(fields(f[1]), fields(f[2]))
        }
    }

    override fun entry(word: String) = words[word]
    override fun group(head: String) = groups[head].orEmpty()
}

/**
 * The forms table on disk, read by binary search over its sorted lines (byte order, as the
 * server sorts them): only the offsets of its lines are kept in memory.
 */
class FormsFile(file: File) : Forms {
    private val buf: MappedByteBuffer = RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
    private val starts: IntArray

    init {
        val s = ArrayList<Int>()
        var at = 0
        val n = buf.limit()
        while (at < n) {
            s += at
            while (at < n && buf.get(at) != '\n'.code.toByte()) at++
            at++
        }
        starts = s.toIntArray()
    }

    override fun entry(word: String): FormEntry? = find(word)?.let { f -> if (f.size == 3) FormEntry(fields(f[1]), fields(f[2])) else null }
    override fun group(head: String): List<String> = find("=$head")?.takeIf { it.size == 2 }?.let { fields(it[1]) }.orEmpty()

    private fun find(key: String): List<String>? {
        val k = key.toByteArray()
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val c = compareKey(starts[mid], k)
            when {
                c == 0 -> return line(starts[mid]).split('\t')
                c < 0 -> lo = mid + 1
                else -> hi = mid
            }
        }
        return null
    }

    // The line's key (up to its first tab) against k, as unsigned bytes. A tab sorts before every
    // letter, so "key\t…" orders like the bare key.
    private fun compareKey(at: Int, k: ByteArray): Int {
        var i = 0
        while (true) {
            val b = if (at + i < buf.limit()) buf.get(at + i).toInt() and 0xFF else '\n'.code
            val end = b == '\t'.code || b == '\n'.code
            if (i == k.size) return if (end) 0 else 1
            if (end) return -1
            val kb = k[i].toInt() and 0xFF
            if (b != kb) return b - kb
            i++
        }
    }

    private fun line(at: Int): String {
        var end = at
        while (end < buf.limit() && buf.get(end) != '\n'.code.toByte()) end++
        val bytes = ByteArray(end - at)
        for (i in bytes.indices) bytes[i] = buf.get(at + i)
        return String(bytes)
    }
}

private fun fields(s: String) = s.split(' ').filter { it.isNotEmpty() }

object SearchMatch {
    /**
     * Parsed terms as clauses, each word with its forms from [forms] (null for none). A word* takes
     * no forms, and in a phrase only the first word does: Hebrew prefixes attach to a phrase's
     * first word (ובית המקדש). As the server's search.Expand.
     */
    fun expand(terms: List<SearchText.Term>, forms: Forms?, x: Expansion = Expansion()): List<Clause> = terms.map { t ->
        t.tokens.mapIndexed { i, tok ->
            val prefix = t.prefix && i == t.tokens.lastIndex
            val w = SearchText.fold(tok)
            val f = if (i == 0 && !prefix && !x.off && forms != null) {
                forms.expand(tok, x.all).filterKeys { it !in x.drop }
            } else emptyMap()
            Word(w, prefix, f)
        }
    }

    /** A segment (HTML) as the tokens [match] works on: normalised, Latin diacritics folded. */
    fun tokens(segment: String): List<String> =
        SearchText.normalize(segment).split(' ').filter { it.isNotEmpty() }.map(SearchText::fold)

    /** Whether [tokens] match every clause of [q], and how. */
    fun match(tokens: List<String>, q: List<Clause>): Hit? {
        var worst = Kind.EXACT
        val hits = ArrayList<ClauseHit>(q.size)
        for (c in q) {
            var best: Kind? = null
            val pos = ArrayList<Int>()
            for (s in 0..tokens.size - c.size) {
                var k = Kind.EXACT
                var ok = true
                for (j in c.indices) {
                    val m = c[j].match(tokens[s + j])
                    if (m == null) { ok = false; break }
                    if (m > k) k = m
                }
                if (ok) {
                    pos += s
                    if (best == null || k < best) best = k
                }
            }
            if (best == null) return null
            hits += ClauseHit(best, pos)
            if (best > worst) worst = best
        }
        return Hit(worst, hits)
    }

    /**
     * [text] (HTML) as plain text with the words matching [q] in <b>; a segment longer than
     * [SearchText.SNIPPET_WORDS] is cut around the first match. As the server's search.Snippet.
     */
    fun snippet(text: String, q: List<Clause>): String = SearchText.snippetWith(text) { tok ->
        val t = SearchText.fold(tok)
        q.any { c -> c.any { it.match(t) != null } }
    }
}
