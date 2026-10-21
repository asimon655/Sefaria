package org.asimon.sefaria.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/** The fonts of the texts (Hebrew and English), from the settings; the interface keeps its own. */
data class TextFonts(val he: FontFamily = FontFamily.Serif, val en: FontFamily = FontFamily.Serif)

val LocalTextFonts = staticCompositionLocalOf { TextFonts() }

/** A font family on the device: [name] as stored in the settings, [label] for the picker. */
data class DeviceFont(val name: String, val label: String, val family: FontFamily)

/**
 * The fonts installed on the device, by family. A family is the file name before its first
 * "-" (NotoSerifHebrew-Bold.ttf → NotoSerifHebrew); collections (.ttc) are left out, since
 * Compose can't pick a font inside one. Reads the disk: call off the main thread.
 */
object DeviceFonts {
    private class FontFile(val file: File, val weight: Int, val italic: Boolean)

    private fun files(): List<FontFile> {
        val all = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SystemFonts.getAvailableFonts().mapNotNull { f ->
                val file = f.file ?: return@mapNotNull null
                FontFile(file, f.style.weight, f.style.slant == android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC)
            }
        } else {
            File("/system/fonts").listFiles().orEmpty().map { f -> FontFile(f, weightOf(f.name), "Italic" in f.name) }
        }
        return all.filter { it.file.extension.lowercase() in setOf("ttf", "otf") }.distinctBy { it.file.path }
    }

    private fun weightOf(name: String) = when {
        "Thin" in name -> 100
        "ExtraLight" in name -> 200
        "Light" in name -> 300
        "Medium" in name -> 500
        "SemiBold" in name -> 600
        "ExtraBold" in name -> 800
        "Black" in name -> 900
        "Bold" in name -> 700
        else -> 400
    }

    private fun familyOf(f: File) = f.nameWithoutExtension.substringBefore('-').substringBefore('[')

    /** "NotoSerifHebrew" → "Noto Serif Hebrew". */
    private fun labelOf(name: String) = name.replace(Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])"), " ")

    private fun familyFrom(files: List<FontFile>) = FontFamily(
        files.map { Font(it.file, FontWeight(it.weight.coerceIn(1, 1000)), if (it.italic) FontStyle.Italic else FontStyle.Normal) }
    )

    /** The family stored as [name] in the settings; the default serif if it is gone or "". */
    fun family(name: String): FontFamily {
        if (name.isEmpty()) return FontFamily.Serif
        val files = runCatching { files().filter { familyOf(it.file) == name } }.getOrDefault(emptyList())
        return if (files.isEmpty()) FontFamily.Serif else runCatching { familyFrom(files) }.getOrDefault(FontFamily.Serif)
    }

    /** The families that have a glyph for [sample], one letter (e.g. "א" for Hebrew), by label. */
    fun covering(sample: String): List<DeviceFont> {
        val paint = Paint()
        return runCatching { files() }.getOrDefault(emptyList()).groupBy { familyOf(it.file) }.mapNotNull { (name, files) ->
            val regular = files.minBy { kotlin.math.abs(it.weight - 400) + if (it.italic) 1000 else 0 }
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // no system fallback, or every font would seem to have every letter
                    val font = android.graphics.fonts.Font.Builder(regular.file).build()
                    paint.typeface = Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(font).build()).build()
                    paint.hasGlyph(sample)
                } else {
                    sample.any { it.isLetter() && it.code < 0x250 } || "Hebrew" in name
                }
            }.getOrDefault(false)
            if (ok) DeviceFont(name, labelOf(name), familyFrom(files)) else null
        }.sortedBy { it.label.lowercase() }
    }
}
