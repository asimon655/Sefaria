package org.asimon.sefaria.core.text

/** Hebrew numerals and text normalization. Mirrors the server's internal/hebrew. */
object Hebrew {
    private val ones = arrayOf("", "א", "ב", "ג", "ד", "ה", "ו", "ז", "ח", "ט")
    private val tens = arrayOf("", "י", "כ", "ל", "מ", "נ", "ס", "ע", "פ", "צ")
    private val hundreds = arrayOf("", "ק", "ר", "ש", "ת")

    /** Formats n (1..9999) without punctuation, using טו/טז for 15/16. */
    fun gematria(n: Int): String {
        if (n <= 0) return ""
        val b = StringBuilder()
        var x = n
        if (x >= 1000) {
            b.append(gematria(x / 1000)).append('׳')
            x %= 1000
        }
        while (x >= 400) {
            b.append('ת')
            x -= 400
        }
        b.append(hundreds[x / 100])
        x %= 100
        when (x) {
            15 -> return b.append("טו").toString()
            16 -> return b.append("טז").toString()
        }
        return b.append(tens[x / 10]).append(ones[x % 10]).toString()
    }

    private fun letterValue(c: Char): Int = when (c) {
        'א' -> 1; 'ב' -> 2; 'ג' -> 3; 'ד' -> 4; 'ה' -> 5; 'ו' -> 6; 'ז' -> 7; 'ח' -> 8; 'ט' -> 9
        'י' -> 10; 'כ', 'ך' -> 20; 'ל' -> 30; 'מ', 'ם' -> 40; 'נ', 'ן' -> 50; 'ס' -> 60; 'ע' -> 70
        'פ', 'ף' -> 80; 'צ', 'ץ' -> 90; 'ק' -> 100; 'ר' -> 200; 'ש' -> 300; 'ת' -> 400
        else -> 0
    }

    /** Parses Hebrew numerals, ignoring geresh/gershayim and quotes; null if s has anything else. */
    fun parseGematria(s: String): Int? {
        var total = 0
        var seen = false
        for (c in s) {
            val v = letterValue(c)
            if (v > 0) {
                total += v
                seen = true
                continue
            }
            if (c in "׳״'\"’”") continue
            return null
        }
        return if (seen) total else null
    }

    /** Niqqud or cantillation, excluding the punctuation in the same block (maqaf, paseq, sof pasuq, nun hafukha). */
    fun isMark(c: Char): Boolean =
        c in '֑'..'ׇ' && c != '־' && c != '׀' && c != '׃' && c != '׆'

    /** Cantillation (te'amim): U+0591–U+05AF. */
    fun isCantillation(c: Char): Boolean = c in '֑'..'֯'

    fun stripMarks(s: String): String = s.filterNot(::isMark)

    /** Removes niqqud and/or cantillation, keeping everything else. */
    fun strip(s: String, niqqud: Boolean, cantillation: Boolean): String {
        if (!niqqud && !cantillation) return s
        return s.filterNot { c ->
            isMark(c) && (if (isCantillation(c)) cantillation else niqqud)
        }
    }

    /**
     * Folds a title or ref for lookup: lowercase, "_" as space, unified quote marks,
     * no Hebrew marks, collapsed whitespace.
     */
    fun normalizeTitle(s: String): String {
        val b = StringBuilder(s.length)
        var space = false
        for (ch in s) {
            var c = ch
            when {
                c == '_' || c.isWhitespace() -> {
                    space = true
                    continue
                }
                isMark(c) -> continue
                c == '״' || c == '“' || c == '”' -> c = '"'
                c == '׳' || c == '‘' || c == '’' || c == '`' -> c = '\''
                c == '־' -> c = '-'
            }
            if (space && b.isNotEmpty()) b.append(' ')
            space = false
            b.append(c.lowercaseChar())
        }
        return b.toString()
    }
}
