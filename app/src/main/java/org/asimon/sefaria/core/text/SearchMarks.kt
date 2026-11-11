package org.asimon.sefaria.core.text

import org.asimon.sefaria.core.debugAssert
import org.asimon.sefaria.core.debugOnly

/**
 * The words a search result marks in the reader, as search tokens (SearchText.normalize, folded;
 * a word* query's last word as "word*", matching any token it starts):
 *  - the words in <b> in the hit's snippet: the forms that matched there, prefixed and other
 *    forms included, which the query alone doesn't name;
 *  - the query's own words, for matches outside the snippet's 40 words.
 */
object SearchMarks {
    /** The words to mark for a hit with [snippet], found by [query]. */
    fun words(snippet: String, query: String): List<String> {
        val terms = SearchText.parse(query)
        val queryTokens = terms.flatMap { it.tokens }.map(SearchText::fold)
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < snippet.length) {
            val open = snippet.indexOf("<b>", i)
            if (open < 0) break
            val close = snippet.indexOf("</b>", open).let { if (it < 0) snippet.length else it }
            out += boldTokens(snippet.substring(open + 3, close), queryTokens)
            i = close + 4
        }
        for (t in terms) t.tokens.forEachIndexed { j, tok ->
            out += SearchText.fold(tok) + if (t.prefix && j == t.tokens.lastIndex) "*" else ""
        }
        return out.toList()
    }

    /**
     * A bold word's tokens. The snippet bolds a whole word between spaces when any token of it
     * matched, so אל־הארץ is bold for הארץ: of a word with several tokens, only those holding a
     * query word (a prefixed form ends with it) are taken, else those of three letters or more,
     * so that short words (אל, כל) aren't marked all over the text.
     */
    private fun boldTokens(word: String, queryTokens: List<String>): List<String> {
        val toks = SearchText.normalize(word).split(' ').filter { it.isNotEmpty() }.map(SearchText::fold)
        if (toks.size <= 1) return toks
        val holding = toks.filter { t -> queryTokens.any { it.isNotEmpty() && it in t } }
        return holding.ifEmpty { toks.filter { it.codePointCount(0, it.length) >= 3 } }
    }

    /** Finds the words of [words] (from [SearchMarks.words]) in displayed text. */
    class Matcher(words: List<String>) {
        private val exact = words.filterNot { it.endsWith("*") }.toHashSet()
        private val prefixes = words.filter { it.endsWith("*") }.map { it.dropLast(1) }.filter { it.isNotEmpty() }

        val isEmpty get() = exact.isEmpty() && prefixes.isEmpty()

        fun matches(token: String) = token in exact || prefixes.any { token.startsWith(it) }

        /**
         * Where [text] (plain, as displayed) holds a word to mark, in order. A word's range is its
         * letters and digits with the niqqud, cantillation and quote marks inside it (רש"י), not
         * the punctuation around it; a maqaf or other mark between words separates them.
         */
        fun ranges(text: String): List<IntRange> {
            if (isEmpty) return emptyList()
            val out = ArrayList<IntRange>()
            var start = -1
            var end = -1 // after the run's last letter, digit or mark: a quote after it isn't marked
            fun close() {
                if (start < 0) return
                val tok = SearchText.fold(SearchText.normalize(text.substring(start, end)))
                if (matches(tok)) out += start until end
                start = -1
            }
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val n = Character.charCount(cp)
                when {
                    Character.isLetter(cp) || Character.isDigit(cp) -> {
                        if (start < 0) start = i
                        end = i + n
                    }
                    start >= 0 && isMark(cp) -> end = i + n
                    start >= 0 && isQuote(cp) -> {}
                    else -> close()
                }
                i += n
            }
            close()
            debugOnly {
                for (k in out.indices) {
                    debugAssert(out[k].first >= 0 && out[k].last < text.length && !out[k].isEmpty()) { "mark ${out[k]} outside the text (${text.length} chars)" }
                    if (k > 0) debugAssert(out[k - 1].last < out[k].first) { "marks ${out[k - 1]} and ${out[k]} overlap or are out of order" }
                }
            }
            return out
        }

        private fun isMark(cp: Int) =
            Character.getType(cp) == Character.NON_SPACING_MARK.toInt() || (cp < 0x10000 && Hebrew.isMark(cp.toChar()))

        // As SearchText's: dropped inside a word by normalize.
        private fun isQuote(cp: Int) = cp < 0x10000 && cp.toChar() in "\"'׳״‘’“”`"
    }
}
