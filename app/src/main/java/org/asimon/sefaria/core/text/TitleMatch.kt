package org.asimon.sefaria.core.text

/**
 * Offline title completion: ranks book names against what the user typed, forgiving typos.
 * Names and query are folded as search folds text (SearchText.normalize: lowercase, no niqqud or
 * diacritics, final letters unified, quote marks dropped), so "Bereshit", "רש\"י" and "rashi"
 * compare as their letters. From best to worst:
 *
 * 0. the whole name ("genesis");
 * 1. the name's start ("gen" → Genesis);
 * 2. the start of a later word ("orach" → Shulchan Arukh, Orach Chayim);
 * 3. every query word starts some word of the name, in any order ("arukh shulchan");
 * 4. inside a word, for queries of 3 letters or more ("aggad" → Haggadah);
 * 5. with typos: every query word of 4 letters or more is within 1 edit (2 from 7 letters) of
 *    the start of some word of the name ("berachot", "shulhan"); shorter words must match exactly.
 *
 * Ties keep the input's order (the TOC's), so Genesis comes before Genesis Rabbah.
 */
object TitleMatch {
    /** The best of [items] for [query], at most [limit]; [names] gives each item's names. */
    fun <T> rank(query: String, items: List<T>, limit: Int, names: (T) -> List<String>): List<T> {
        val q = fold(query)
        if (q.isEmpty() || limit <= 0) return emptyList()
        val words = q.split(' ')
        val folded = items.map { item -> names(item).map(::fold).filter { it.isNotEmpty() } }
        val scored = ArrayList<Triple<Int, Int, T>>() // (score, input position, item)
        val missed = ArrayList<Int>()
        for ((i, item) in items.withIndex()) {
            val s = folded[i].minOfOrNull { exact(q, words, it) ?: Int.MAX_VALUE }
            if (s != null && s != Int.MAX_VALUE) scored += Triple(s, i, item) else missed += i
        }
        // typos only when the exact tiers leave room: they could never outrank those
        if (scored.size < limit && q.length >= MIN_FUZZY) for (i in missed) {
            val s = folded[i].minOfOrNull { fuzzy(words, it) ?: Int.MAX_VALUE } ?: continue
            if (s != Int.MAX_VALUE) scored += Triple(s, i, items[i])
        }
        return scored.sortedWith(compareBy({ it.first }, { it.second })).take(limit).map { it.third }
    }

    /** [s] folded for comparison: search tokens, Latin diacritics removed, joined by single spaces. */
    fun fold(s: String): String = SearchText.fold(SearchText.normalize(s))

    private const val MIN_FUZZY = 4
    private const val TIER = 1000

    // tiers 0-4: a lower score is better; null if none applies
    private fun exact(q: String, words: List<String>, name: String): Int? = when {
        name == q -> 0
        name.startsWith(q) -> TIER + name.length
        name.contains(" $q") -> 2 * TIER + name.length
        prefixesOf(words, name.split(' ')) -> 3 * TIER + name.length
        q.length >= 3 && name.contains(q) -> 4 * TIER + name.length
        else -> null
    }

    // Each query word starts a different word of the name.
    private fun prefixesOf(words: List<String>, nameWords: List<String>): Boolean {
        val used = BooleanArray(nameWords.size)
        for (w in words) {
            val j = nameWords.indices.firstOrNull { !used[it] && nameWords[it].startsWith(w) } ?: return false
            used[j] = true
        }
        return true
    }

    // tier 5: the sum of each query word's edits to the closest start of a name word
    private fun fuzzy(words: List<String>, name: String): Int? {
        val nameWords = name.split(' ')
        var total = 0
        for (w in words) {
            val allowed = when {
                w.length < MIN_FUZZY -> 0
                w.length < 7 -> 1
                else -> 2
            }
            var best = Int.MAX_VALUE
            for (nw in nameWords) {
                // the query word may be a prefix of what it means: compare with the name word's starts near its length
                for (len in maxOf(1, w.length - 1)..minOf(nw.length, w.length + 1)) {
                    best = minOf(best, distance(w, nw, len, allowed + 1))
                }
                if (nw.length < w.length - 1) best = minOf(best, distance(w, nw, nw.length, allowed + 1))
            }
            if (best > allowed) return null
            total += best
        }
        return 5 * TIER + total * 100 + name.length
    }

    /**
     * Edits (insert, delete, substitute, swap two neighbours) from [a] to the first [len] chars of
     * [b]; stops counting at [cap].
     */
    private fun distance(a: String, b: String, len: Int, cap: Int): Int {
        val n = a.length
        val m = len
        if (kotlin.math.abs(n - m) >= cap) return cap
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var d = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d = minOf(d, prev2[j - 2] + 1)
                cur[j] = d
                rowMin = minOf(rowMin, d)
            }
            if (rowMin >= cap) return cap
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return minOf(prev[m], cap)
    }
}
