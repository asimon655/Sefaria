package org.asimon.sefaria.core.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

/** A parsed segment: styled text. */
data class RichText(val text: AnnotatedString) {
    val plain get() = text.text
}

/**
 * Parses the HTML subset in Sefaria texts (SERVER_SPEC §6.3.1) into an AnnotatedString.
 * Unknown tags render as their inner text, and a "<" that does not start a well-formed tag
 * is literal text (the corpus has some). Footnotes are dropped, their markers
 * (`<sup class="footnote-marker">`) and bodies (`<i class="footnote">`) both, whatever the class's
 * spelling: the server's build
 * removes them too (2026-10-14), and this covers texts cached or downloaded before. Runs off the
 * main thread; composables never parse.
 */
object Html {
    private val Muted = Color(0xFF8A8A8A)

    private val voidTags = setOf("br", "img", "hr")
    private val entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "thinsp" to " ", "ensp" to " ", "emsp" to " ", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "shy" to "",
    )

    private class Open(val name: String, val cls: String, val start: Int, val dropped: Boolean)

    fun parse(html: String, stripNiqqud: Boolean = false, stripCantillation: Boolean = false): RichText {
        val out = AnnotatedString.Builder(html.length)
        var dropDepth = -1 // stack depth of the open footnote element, -1 if none
        val stack = ArrayList<Open>()

        fun text(s: String) {
            if (s.isEmpty() || dropDepth >= 0) return
            out.append(Hebrew.strip(s, stripNiqqud, stripCantillation))
        }

        fun newline() {
            if (dropDepth < 0 && out.length > 0 && !out.toAnnotatedString().text.endsWith('\n')) out.append('\n')
        }

        fun close(o: Open) {
            if (o.dropped) {
                dropDepth = -1
                return
            }
            if (dropDepth >= 0) return // inside a footnote
            val end = out.length
            if (end <= o.start) return
            styleFor(o.name, o.cls)?.let { out.addStyle(it, o.start, end) }
            if (o.name == "p" || o.name == "div") newline()
        }

        var i = 0
        val plain = StringBuilder()
        fun flush() {
            text(plain.toString())
            plain.clear()
        }
        while (i < html.length) {
            val c = html[i]
            if (c == '&') {
                val semi = html.indexOf(';', i + 1)
                if (semi in i + 2..i + 10) {
                    val decoded = decodeEntity(html.substring(i + 1, semi))
                    if (decoded != null) {
                        plain.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
                plain.append(c)
                i++
                continue
            }
            if (c != '<') {
                plain.append(c)
                i++
                continue
            }
            val tag = readTag(html, i)
            if (tag == null) {
                plain.append(c)
                i++
                continue
            }
            flush()
            i = tag.end
            val (name, closing, attrs) = tag
            if (closing) {
                val idx = stack.indexOfLast { it.name == name }
                if (idx >= 0) {
                    // close misnested children first
                    while (stack.size > idx) close(stack.removeAt(stack.size - 1))
                }
                continue
            }
            when (name) {
                "br" -> {
                    if (dropDepth < 0) out.append('\n')
                    continue
                }
                "img", "hr" -> continue
                "li" -> {
                    newline()
                    text("• ")
                }
                "p", "div" -> newline()
            }
            if (name in voidTags || attrs.endsWith("/")) continue
            val cls = classOf(attrs)
            // the dump spells the class many ways (FOOTNOTE, “footnote”, endFootnote), so any mention counts
            val footnote = dropDepth < 0 && (name == "i" || name == "sup") && attrs.contains("footnote", ignoreCase = true)
            stack.add(Open(name, cls, out.length, footnote))
            if (footnote) dropDepth = stack.size
        }
        flush()
        while (stack.isNotEmpty()) close(stack.removeAt(stack.size - 1))
        return RichText(trimEnd(out.toAnnotatedString()))
    }

    private fun styleFor(name: String, cls: String): SpanStyle? = when (name) {
        "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
        "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
        "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
        "big" -> SpanStyle(fontSize = 1.2.em)
        "small" -> SpanStyle(fontSize = 0.8.em)
        "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.7.em)
        "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.7.em)
        "span" -> when {
            "mam-spi" in cls || "mediumGrey" in cls || "mam-kq-trivial" in cls -> SpanStyle(color = Muted)
            else -> null
        }
        else -> null
    }

    private data class Tag(val name: String, val closing: Boolean, val attrs: String, val end: Int)

    /** Reads a well-formed `<name ...>` or `</name>` at [start]; null otherwise. */
    private fun readTag(s: String, start: Int): Tag? {
        var j = start + 1
        val closing = j < s.length && s[j] == '/'
        if (closing) j++
        val nameStart = j
        while (j < s.length && s[j].isLetterOrDigit() && s[j].code < 128) j++
        if (j == nameStart) return null
        if (!s[nameStart].isLetter()) return null
        val name = s.substring(nameStart, j).lowercase()
        if (j < s.length && s[j] != '>' && s[j] != ' ' && s[j] != '/') return null
        val gt = s.indexOf('>', j)
        if (gt < 0) return null
        val lt = s.indexOf('<', j)
        if (lt in 0 until gt) return null // "<b <i>" is not a tag
        return Tag(name, closing, s.substring(j, gt).trim(), gt + 1)
    }

    private fun classOf(attrs: String): String {
        val k = attrs.indexOf("class=")
        if (k < 0) return ""
        val q = attrs.getOrNull(k + 6) ?: return ""
        if (q != '"' && q != '\'') return attrs.substring(k + 6).substringBefore(' ')
        val end = attrs.indexOf(q, k + 7)
        return if (end < 0) "" else attrs.substring(k + 7, end)
    }

    private fun decodeEntity(e: String): String? {
        if (e.startsWith("#")) {
            val code = if (e.startsWith("#x") || e.startsWith("#X")) e.substring(2).toIntOrNull(16)
            else e.substring(1).toIntOrNull()
            return code?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
        }
        return entities[e]
    }

    private fun trimEnd(a: AnnotatedString): AnnotatedString {
        var end = a.length
        while (end > 0 && a.text[end - 1].isWhitespace()) end--
        var start = 0
        while (start < end && a.text[start].isWhitespace()) start++
        return if (start == 0 && end == a.length) a else a.subSequence(start, end)
    }
}
