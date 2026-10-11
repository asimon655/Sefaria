package org.asimon.sefaria.feature.onboarding

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.asimon.sefaria.R
import org.asimon.sefaria.data.Layout
import org.asimon.sefaria.ui.AppLanguage

/**
 * First launch only: the interface language and the text layout. Everything else keeps its
 * default and lives in Settings and the reader's display sheet.
 */
@Composable
fun Onboarding(layout: Layout, onLayout: (Layout) -> Unit, onDone: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.welcome), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.welcome_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
            val activity = LocalActivity.current
            val current = remember { activity?.let { AppLanguage.current(it) } ?: AppLanguage.Device }
            val langs = AppLanguage.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                langs.forEachIndexed { i, l ->
                    SegmentedButton(
                        selected = current == l,
                        onClick = { if (activity != null && l != current) AppLanguage.set(activity, l) },
                        shape = SegmentedButtonDefaults.itemShape(i, langs.size),
                    ) {
                        Text(
                            when (l) {
                                AppLanguage.Device -> stringResource(R.string.language_device)
                                AppLanguage.English -> "English"
                                AppLanguage.Hebrew -> "עברית"
                            },
                            maxLines = 1,
                        )
                    }
                }
            }

            Text(stringResource(R.string.text_layout), style = MaterialTheme.typography.titleMedium)
            val layouts = listOf(Layout.Hebrew to R.string.layout_hebrew, Layout.English to R.string.layout_english, Layout.Stacked to R.string.layout_both, Layout.SideBySide to R.string.layout_side)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                layouts.forEachIndexed { i, (l, label) ->
                    SegmentedButton(
                        selected = layout == l,
                        onClick = { onLayout(l) },
                        shape = SegmentedButtonDefaults.itemShape(i, layouts.size),
                    ) { Text(stringResource(label), maxLines = 1) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.get_started)) }
        }
    }
}
