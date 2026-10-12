package org.asimon.sefaria.core.text

/**
 * Search normalisation and query parsing. Mirrors the server's internal/search, so a pack's
 * offline index and the server's index agree; checked against the same normalize.tsv vectors.
 */
object SearchText {
    /** One search term: a token or a phrase, the last token optionally a prefix. */
    data class Term(val tokens: List<String>, val prefix: Boolean = false)

    /** The shortest prefix searched as one; a shorter word* matches exactly. */
    private const val MIN_PREFIX = 2

    private fun final(cp: Int): Int = when (cp) {
        'ך'.code -> 'כ'.code; 'ם'.code -> 'מ'.code; 'ן'.code -> 'נ'.code; 'ף'.code -> 'פ'.code; 'ץ'.code -> 'צ'.code
        else -> cp
    }

    private fun isQuote(cp: Int) = cp < 0x10000 && cp.toChar() in "\"'׳״‘’“”`"

    /** Tags that can sit inside a word, as in <big>ב</big>ראשית; others (br, sup, footnotes) separate words. */
    internal fun inline(tag: String): Boolean {
        var t = tag.removePrefix("/")
        val i = t.indexOfAny(charArrayOf(' ', '/'))
        if (i >= 0) t = t.substring(0, i)
        return t.lowercase() in setOf("b", "big", "small", "span", "u", "strong", "em")
    }

    /**
     * Turns text (which may hold HTML) into space-separated search tokens: no tags or entities, no
     * niqqud or cantillation, final letters unified, quote marks inside words dropped (רש"י → רשי),
     * Latin lowercased, every other character a separator.
     */
    fun normalize(s: String): String {
        val b = StringBuilder(s.length)
        var sep = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp == '<'.code) {
                val j = s.indexOf('>', i)
                if (j >= 0) {
                    sep = sep || !inline(s.substring(i + 1, j))
                    i = j + 1
                    continue
                }
            } else if (cp == '&'.code) {
                val j = s.indexOf(';', i)
                if (j > i && j - i < 10) {
                    i = j + 1
                    sep = true
                    continue
                }
            }
            i += Character.charCount(cp)
            when {
                Character.getType(cp) == Character.NON_SPACING_MARK.toInt() || isQuote(cp) ||
                    (cp < 0x10000 && Hebrew.isMark(cp.toChar())) -> {}
                Character.isLetter(cp) || Character.isDigit(cp) -> {
                    if (sep && b.isNotEmpty()) b.append(' ')
                    sep = false
                    b.appendCodePoint(Character.toLowerCase(final(cp)))
                }
                else -> sep = true
            }
        }
        return b.toString()
    }

    /**
     * Reads a query: words (all must match), "quoted phrases" and word* prefixes. A " inside a
     * word is a gershayim (רש"י), not a phrase quote.
     */
    fun parse(q: String): List<Term> {
        val terms = mutableListOf<Term>()
        fun add(s: String) {
            val toks = normalize(s).split(' ').filter { it.isNotEmpty() }
            if (toks.isEmpty()) return
            val prefix = s.trim().endsWith("*") && toks.last().codePointCount(0, toks.last().length) >= MIN_PREFIX
            terms += Term(toks, prefix)
        }
        fun space(i: Int) = i < 0 || i >= q.length || q[i].isWhitespace()
        var start = 0
        var inPhrase = false
        for (i in 0..q.length) {
            when {
                i == q.length -> add(q.substring(start, i))
                q[i] == '"' && !inPhrase && space(i - 1) -> { start = i + 1; inPhrase = true }
                q[i] == '"' && inPhrase && space(i + 1) -> { add(q.substring(start, i)); start = i + 1; inPhrase = false }
                space(i) && !inPhrase -> { add(q.substring(start, i)); start = i + 1 }
            }
        }
        return terms
    }

    /** The FTS5 MATCH expression; tokens hold only letters and digits, so quoting them is safe. */
    fun expr(terms: List<Term>): String =
        terms.joinToString(" AND ") { "\"" + it.tokens.joinToString(" ") + "\"" + if (it.prefix) "*" else "" }

    fun isHebrew(q: String): Boolean = q.any { it in 'א'..'ת' }

    const val SNIPPET_WORDS = 40

    /**
     * [text] (HTML) as plain text with the words matching [terms] in <b>; a segment longer than
     * [SNIPPET_WORDS] is cut around the first match. Mirrors the server's search.Snippet.
     */
    fun snippet(text: String, terms: List<Term>): String {
        val exact = HashSet<String>()
        val prefixes = mutableListOf<String>()
        for (t in terms) t.tokens.forEachIndexed { i, tok -> if (t.prefix && i == t.tokens.lastIndex) prefixes += tok else exact += tok }
        fun match(word: String) = normalize(word).split(' ').any { tok ->
            tok.isNotEmpty() && (tok in exact || prefixes.any { tok.startsWith(it) })
        }
        val words = fields(plain(text))
        var first = -1
        for (i in words.indices) if (match(words[i])) {
            if (first < 0) first = i
            words[i] = "<b>" + words[i] + "</b>"
        }
        if (words.size <= SNIPPET_WORDS) return words.joinToString(" ")
        val to = minOf(words.size, maxOf(0, first - SNIPPET_WORDS / 4) + SNIPPET_WORDS)
        val from = maxOf(0, to - SNIPPET_WORDS)
        return (if (from > 0) "… " else "") + words.subList(from, to).joinToString(" ") + (if (to < words.size) " …" else "")
    }

    // Splits on whitespace as Go's strings.Fields does (Unicode spaces included).
    private fun fields(s: String): MutableList<String> {
        val out = mutableListOf<String>()
        var start = -1
        for (i in 0..s.length) {
            val space = i == s.length || s[i].isWhitespace()
            if (space && start >= 0) {
                out += s.substring(start, i)
                start = -1
            } else if (!space && start < 0) start = i
        }
        return out
    }

    private val entities = mapOf("&nbsp;" to " ", "&thinsp;" to " ", "&amp;" to "&", "&lt;" to "‹", "&gt;" to "›", "&quot;" to "\"", "&#39;" to "'")

    private fun plain(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '<') {
                val j = s.indexOf('>', i)
                if (j >= 0) {
                    if (!inline(s.substring(i + 1, j))) b.append(' ')
                    i = j + 1
                    continue
                }
            }
            b.append(s[i])
            i++
        }
        var out = b.toString()
        for ((k, v) in entities) out = out.replace(k, v)
        return out
    }
}
