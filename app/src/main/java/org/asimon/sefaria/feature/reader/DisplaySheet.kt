package org.asimon.sefaria.feature.reader

import androidx.compose.foundation.layout.size
import org.asimon.sefaria.ui.components.label
import androidx.compose.ui.res.stringResource
import org.asimon.sefaria.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.asimon.sefaria.data.Layout
import org.asimon.sefaria.data.Prefs
import org.asimon.sefaria.data.TextFlow
import org.asimon.sefaria.ui.components.ThemePicker
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DisplaySheet(prefs: Prefs, vm: ReaderViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.layout), style = MaterialTheme.typography.titleSmall)
            val layouts = listOf(Layout.Hebrew to R.string.layout_hebrew, Layout.English to R.string.layout_english, Layout.Stacked to R.string.layout_both, Layout.SideBySide to R.string.layout_side)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                layouts.forEachIndexed { i, (l, label) ->
                    SegmentedButton(
                        selected = prefs.layout == l,
                        onClick = { vm.setLayout(l) },
                        shape = SegmentedButtonDefaults.itemShape(i, layouts.size),
                    ) { Text(stringResource(label), maxLines = 1) }
                }
            }
            Text(stringResource(R.string.font_size), style = MaterialTheme.typography.titleSmall)
            var scale by remember { mutableStateOf(prefs.fontScale) }
            Slider(value = scale, onValueChange = { scale = it }, onValueChangeFinished = { vm.setFontScale(scale) }, valueRange = 0.7f..2f)
            Text(stringResource(R.string.text_flow), style = MaterialTheme.typography.titleSmall)
            val flows = listOf(TextFlow.Lines to R.string.flow_lines, TextFlow.Paragraph to R.string.flow_paragraph)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                flows.forEachIndexed { i, (f, label) ->
                    SegmentedButton(
                        selected = prefs.flow == f,
                        onClick = { vm.setFlow(f) },
                        shape = SegmentedButtonDefaults.itemShape(i, flows.size),
                    ) { Text(stringResource(label), maxLines = 1) }
                }
            }
            SwitchRow(stringResource(R.string.niqqud), prefs.niqqud, vm::setNiqqud)
            SwitchRow(stringResource(R.string.cantillation), prefs.cantillation, vm::setCantillation)
            SwitchRow(stringResource(R.string.keep_screen_on), prefs.keepScreenOn, vm::setKeepScreenOn)
            SwitchRow(stringResource(R.string.snap_to_verse), prefs.snapToVerse, vm::setSnapToVerse)
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
            ThemePicker(prefs.theme, vm::setTheme)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
