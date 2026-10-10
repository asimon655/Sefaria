package org.asimon.sefaria.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.asimon.sefaria.data.ThemeMode

private val Blue = Color(0xFF18345D) // Sefaria-like navy
private val Gold = Color(0xFFB8860B)

private val Light = lightColorScheme(
    primary = Blue,
    secondary = Gold,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF4F4F2),
)

private val Sepia = lightColorScheme(
    primary = Color(0xFF5B4636),
    secondary = Gold,
    background = Color(0xFFF5ECD9),
    surface = Color(0xFFF5ECD9),
    surfaceContainer = Color(0xFFEDE1C8),
    onBackground = Color(0xFF3B2F25),
    onSurface = Color(0xFF3B2F25),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DB8E0),
    secondary = Color(0xFFE0B860),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
)

@Composable
fun SefariaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val scheme = when (mode) {
        ThemeMode.System -> if (isSystemInDarkTheme()) Dark else Light
        ThemeMode.Light -> Light
        ThemeMode.Sepia -> Sepia
        ThemeMode.Dark -> Dark
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
