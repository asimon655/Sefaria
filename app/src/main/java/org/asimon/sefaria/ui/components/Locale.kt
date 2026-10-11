package org.asimon.sefaria.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import org.asimon.sefaria.R
import org.asimon.sefaria.data.ThemeMode
import org.asimon.sefaria.data.repo.AppError

/**
 * Interface language. It follows the device (or the per-app language on Android 13+) through
 * resources: values/ is English, values-iw/ is Hebrew. Data that comes in both languages
 * (titles, descriptions) is picked with [pick].
 */
@Composable
@ReadOnlyComposable
fun hebrewUi(): Boolean = LocalConfiguration.current.locales[0].language.let { it == "iw" || it == "he" }

/** The interface-language variant of a bilingual value, falling back to the other one. */
@Composable
@ReadOnlyComposable
fun pick(en: String, he: String): String = if (hebrewUi()) he.ifEmpty { en } else en.ifEmpty { he }

/**
 * Descriptive text (descriptions, categories) in the interface language only: a Hebrew
 * interface shows nothing rather than English. English falls back to Hebrew.
 */
@Composable
@ReadOnlyComposable
fun prose(en: String, he: String): String = if (hebrewUi()) he else en.ifEmpty { he }

/**
 * The Hebrew variant as a secondary line in an English interface, or "". A Hebrew interface
 * shows no English.
 */
@Composable
@ReadOnlyComposable
fun other(en: String, he: String): String = if (hebrewUi()) "" else he.takeIf { it != pick(en, he) }.orEmpty()

@Composable
fun AppError.text(): String = when (this) {
    AppError.Network -> stringResource(R.string.error_network)
    // the server's detail is English: only an English interface shows it
    is AppError.NotFound -> if (hebrewUi()) stringResource(R.string.error_not_found_plain) else stringResource(R.string.error_not_found, detail)
    is AppError.Server -> stringResource(R.string.error_server, status)
    is AppError.BadResponse -> stringResource(R.string.error_bad_response)
    AppError.NoText -> stringResource(R.string.error_no_text)
}

@Composable
fun ThemeMode.label(): String = stringResource(
    when (this) {
        ThemeMode.System -> R.string.theme_system
        ThemeMode.Light -> R.string.theme_light
        ThemeMode.Sepia -> R.string.theme_sepia
        ThemeMode.Dark -> R.string.theme_dark
    }
)

/** Aligns a line with the screen's reading side even when its text runs the other way. */
@Composable
@ReadOnlyComposable
fun screenAlign(): TextAlign = if (hebrewUi()) TextAlign.Right else TextAlign.Left
