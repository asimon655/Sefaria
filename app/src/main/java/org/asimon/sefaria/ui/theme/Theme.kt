package org.asimon.sefaria.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.asimon.sefaria.data.ThemeMode

/*
 * Every theme sets all the roles the app uses, so no Material default (purple) shows
 * through. Accent: teal for actions and links; secondary: brass for highlights and counts.
 */

private val Teal = Color(0xFF1F5F6B)
private val Brass = Color(0xFF9A7530)

private fun light(
    page: Color, ink: Color, muted: Color, accent: Color, accentSoft: Color, brass: Color, brassSoft: Color,
    container: Color, containerLow: Color, containerHigh: Color, line: Color,
) = lightColorScheme(
    primary = accent, onPrimary = page, primaryContainer = accentSoft, onPrimaryContainer = ink,
    secondary = brass, onSecondary = page, secondaryContainer = brassSoft, onSecondaryContainer = ink,
    tertiary = accent, onTertiary = page, tertiaryContainer = accentSoft, onTertiaryContainer = ink,
    background = page, onBackground = ink, surface = page, onSurface = ink,
    surfaceVariant = container, onSurfaceVariant = muted, surfaceTint = accent,
    surfaceContainerLowest = page, surfaceContainerLow = containerLow, surfaceContainer = container,
    surfaceContainerHigh = containerHigh, surfaceContainerHighest = containerHigh,
    surfaceBright = page, surfaceDim = container,
    outline = muted, outlineVariant = line, inverseSurface = ink, inverseOnSurface = page, inversePrimary = accentSoft,
)

private fun dark(
    page: Color, ink: Color, muted: Color, accent: Color, accentSoft: Color, brass: Color, brassSoft: Color,
    container: Color, containerLow: Color, containerHigh: Color, line: Color,
) = darkColorScheme(
    primary = accent, onPrimary = page, primaryContainer = accentSoft, onPrimaryContainer = ink,
    secondary = brass, onSecondary = page, secondaryContainer = brassSoft, onSecondaryContainer = ink,
    tertiary = accent, onTertiary = page, tertiaryContainer = accentSoft, onTertiaryContainer = ink,
    background = page, onBackground = ink, surface = page, onSurface = ink,
    surfaceVariant = container, onSurfaceVariant = muted, surfaceTint = accent,
    surfaceContainerLowest = page, surfaceContainerLow = containerLow, surfaceContainer = container,
    surfaceContainerHigh = containerHigh, surfaceContainerHighest = containerHigh,
    surfaceBright = containerHigh, surfaceDim = page,
    outline = muted, outlineVariant = line, inverseSurface = ink, inverseOnSurface = page, inversePrimary = Teal,
)

private val Light = light(
    page = Color(0xFFFFFFFF), ink = Color(0xFF1B2329), muted = Color(0xFF5D6670),
    accent = Teal, accentSoft = Color(0xFFDDECEE), brass = Brass, brassSoft = Color(0xFFF3E8D2),
    container = Color(0xFFF2F4F5), containerLow = Color(0xFFF7F8F9), containerHigh = Color(0xFFE8ECEE), line = Color(0xFFD8DDE1),
)

/** Warm grey like Jerusalem stone. */
private val Stone = light(
    page = Color(0xFFF3F1EA), ink = Color(0xFF1F2A2E), muted = Color(0xFF5F625C),
    accent = Color(0xFF2C6E6A), accentSoft = Color(0xFFD9E6E2), brass = Color(0xFF9A7530), brassSoft = Color(0xFFEADFC4),
    container = Color(0xFFE9E6DC), containerLow = Color(0xFFEEECE4), containerHigh = Color(0xFFE1DDD1), line = Color(0xFFD3CFC2),
)

private val Sepia = light(
    page = Color(0xFFF5ECD9), ink = Color(0xFF3B2F25), muted = Color(0xFF6E5E4E),
    accent = Color(0xFF6B4E31), accentSoft = Color(0xFFE9DCC0), brass = Color(0xFF9A6A1E), brassSoft = Color(0xFFEBD9B4),
    container = Color(0xFFEDE1C8), containerLow = Color(0xFFF1E7D1), containerHigh = Color(0xFFE5D6B8), line = Color(0xFFD9C8A6),
)

/** For e-paper screens: black on white, no tints, contrast from outlines. */
private val EInk = light(
    page = Color.White, ink = Color.Black, muted = Color(0xFF333333),
    accent = Color.Black, accentSoft = Color(0xFFE6E6E6), brass = Color.Black, brassSoft = Color(0xFFE6E6E6),
    container = Color.White, containerLow = Color.White, containerHigh = Color(0xFFF0F0F0), line = Color.Black,
)

private val Dark = dark(
    page = Color(0xFF14181D), ink = Color(0xFFE5E8EB), muted = Color(0xFF9AA3AD),
    accent = Color(0xFF7CC0BB), accentSoft = Color(0xFF1F3A3B), brass = Color(0xFFD4B066), brassSoft = Color(0xFF3A3122),
    container = Color(0xFF1C2228), containerLow = Color(0xFF181D22), containerHigh = Color(0xFF242B32), line = Color(0xFF323A42),
)

/** True black for OLED screens. */
private val Black = dark(
    page = Color.Black, ink = Color(0xFFE6E6E6), muted = Color(0xFF9A9A9A),
    accent = Color(0xFF7CC0BB), accentSoft = Color(0xFF15292A), brass = Color(0xFFD4B066), brassSoft = Color(0xFF2A2418),
    container = Color(0xFF0E0E0E), containerLow = Color(0xFF080808), containerHigh = Color(0xFF161616), line = Color(0xFF262626),
)

fun ThemeMode.isDark(systemDark: Boolean) = when (this) {
    ThemeMode.System -> systemDark
    ThemeMode.Dark, ThemeMode.Black -> true
    else -> false
}

fun ThemeMode.scheme(systemDark: Boolean): ColorScheme = when (this) {
    ThemeMode.System -> if (systemDark) Dark else Light
    ThemeMode.Light -> Light
    ThemeMode.Stone -> Stone
    ThemeMode.Sepia -> Sepia
    ThemeMode.EInk -> EInk
    ThemeMode.Dark -> Dark
    ThemeMode.Black -> Black
}

/*
 * Category colours: one muted colour per top-level library category, used wherever the
 * category shows (library rows, connection groups, trail chips). Keys are the TOC's English
 * category names; a light and a dark variant keep contrast in both kinds of theme.
 */
private val categoryLight = mapOf(
    "Tanakh" to 0xFF2F6F8F, "Mishnah" to 0xFF6F8A2F, "Talmud" to 0xFFA0522D, "Midrash" to 0xFF8A5A9E,
    "Halakhah" to 0xFF2E7D5B, "Kabbalah" to 0xFF5B5BA8, "Liturgy" to 0xFFB0763A, "Jewish Thought" to 0xFF7A6A4F,
    "Tosefta" to 0xFF8A7A2F, "Chasidut" to 0xFF3E8A8A, "Musar" to 0xFF9E5A6E, "Responsa" to 0xFF6E6E2E,
    "Second Temple" to 0xFF8F6F2F, "Reference" to 0xFF6B7280, "Commentary" to 0xFF1F5F6B, "Targum" to 0xFF4F7A9E,
    "Quoting Commentary" to 0xFF1F5F6B,
)

private fun lighten(c: Color) = Color(
    red = c.red + (1 - c.red) * 0.45f, green = c.green + (1 - c.green) * 0.45f, blue = c.blue + (1 - c.blue) * 0.45f,
)

/** How category colours are drawn in the current theme. */
private enum class CategoryInk { Light, Dark, Mono }

private val LocalCategoryInk = staticCompositionLocalOf { CategoryInk.Light }

/** The colour of a top-level category (English TOC name), or the outline colour if it has none. */
@Composable
@ReadOnlyComposable
fun categoryColor(category: String): Color {
    val base = categoryLight[category]?.let(::Color) ?: return MaterialTheme.colorScheme.outline
    return when (LocalCategoryInk.current) {
        CategoryInk.Light -> base
        CategoryInk.Dark -> lighten(base)
        CategoryInk.Mono -> Color.Black
    }
}

@Composable
fun SefariaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val ink = when {
        mode == ThemeMode.EInk -> CategoryInk.Mono
        mode.isDark(systemDark) -> CategoryInk.Dark
        else -> CategoryInk.Light
    }
    CompositionLocalProvider(LocalCategoryInk provides ink) {
        MaterialTheme(colorScheme = mode.scheme(systemDark), content = content)
    }
}
