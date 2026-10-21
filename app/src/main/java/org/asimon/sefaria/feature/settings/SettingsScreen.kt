package org.asimon.sefaria.feature.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.asimon.sefaria.BuildConfig
import org.asimon.sefaria.R
import org.asimon.sefaria.data.Layout
import org.asimon.sefaria.data.Settings
import org.asimon.sefaria.data.TextFlow
import org.asimon.sefaria.data.ThemeMode
import org.asimon.sefaria.data.repo.LibraryRepository
import org.asimon.sefaria.data.repo.DownloadRepository
import org.asimon.sefaria.feature.downloads.ConfirmDelete
import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Delete
import org.asimon.sefaria.ui.AppLanguage
import org.asimon.sefaria.ui.DeviceFont
import org.asimon.sefaria.ui.DeviceFonts
import org.asimon.sefaria.ui.LocalTextFonts
import org.asimon.sefaria.ui.components.BackButton
import org.asimon.sefaria.ui.components.ThemePicker
import javax.inject.Inject

/** The device's fonts that have Hebrew letters, and those with Latin ones. */
data class FontChoices(val he: List<DeviceFont>, val en: List<DeviceFont>)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: Settings,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
) : ViewModel() {
    val prefs = settings.prefs
    /** Disk used by downloaded books, and how many there are. */
    val downloadBytes = downloads.bytes
    val downloaded = downloads.installed
    private val _build = MutableStateFlow<String?>(null)
    val build = _build.asStateFlow()
    private val _fonts = MutableStateFlow<FontChoices?>(null) // null until scanned
    val fonts = _fonts.asStateFlow()

    init { refresh() }

    private fun refresh() = viewModelScope.launch { _build.value = library.refreshBuild() }

    /** Scans the device's fonts once, when a font picker first opens. */
    fun loadFonts() {
        if (_fonts.value != null) return
        viewModelScope.launch {
            _fonts.value = withContext(Dispatchers.IO) { FontChoices(DeviceFonts.covering("א"), DeviceFonts.covering("a")) }
        }
    }

    fun setServerUrl(url: String) = viewModelScope.launch {
        settings.setServerUrl(url)
        refresh()
    }

    fun setTheme(t: ThemeMode) = viewModelScope.launch { settings.setTheme(t) }
    fun setDiaspora(v: Boolean) = viewModelScope.launch { settings.setDiaspora(v) }
    fun setSephardi(v: Boolean) = viewModelScope.launch { settings.setSephardi(v) }
    fun setNavLabels(v: Boolean) = viewModelScope.launch { settings.setNavLabels(v) }
    fun setLayout(v: Layout) = viewModelScope.launch { settings.setLayout(v) }
    fun setFlow(v: TextFlow) = viewModelScope.launch { settings.setFlow(v) }
    fun setFontScale(v: Float) = viewModelScope.launch { settings.setFontScale(v) }
    fun setHeFont(v: String) = viewModelScope.launch { settings.setHeFont(v) }
    fun setEnFont(v: String) = viewModelScope.launch { settings.setEnFont(v) }
    fun setNiqqud(v: Boolean) = viewModelScope.launch { settings.setNiqqud(v) }
    fun setCantillation(v: Boolean) = viewModelScope.launch { settings.setCantillation(v) }
    fun setSnapToVerse(v: Boolean) = viewModelScope.launch { settings.setSnapToVerse(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { settings.setKeepScreenOn(v) }
    fun deleteAllDownloads() = viewModelScope.launch { downloads.deleteAll() }
}

/** Which font picker is open. */
private enum class FontPick { He, En }

/** Settings in sections: the interface, reading, today's learning, the server, and about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val build by vm.build.collectAsStateWithLifecycle()
    var url by rememberSaveable(prefs.serverUrl) { mutableStateOf(prefs.serverUrl) }
    var picking by rememberSaveable { mutableStateOf<FontPick?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section(stringResource(R.string.section_interface)) {
                Label(stringResource(R.string.language))
                LanguageChoice()
                Label(stringResource(R.string.theme))
                ThemePicker(prefs.theme, vm::setTheme)
                SwitchRow(stringResource(R.string.nav_labels), prefs.navLabels, vm::setNavLabels, stringResource(R.string.nav_labels_body))
            }
            Section(stringResource(R.string.section_reading)) {
                Label(stringResource(R.string.layout))
                Choice(
                    listOf(stringResource(R.string.layout_hebrew), stringResource(R.string.layout_english), stringResource(R.string.layout_both), stringResource(R.string.layout_side)),
                    Layout.entries.indexOf(prefs.layout),
                ) { vm.setLayout(Layout.entries[it]) }
                Label(stringResource(R.string.text_flow))
                Choice(listOf(stringResource(R.string.flow_lines), stringResource(R.string.flow_paragraph)), TextFlow.entries.indexOf(prefs.flow)) {
                    vm.setFlow(TextFlow.entries[it])
                }
                Label(stringResource(R.string.font_size))
                var scale by remember(prefs.fontScale) { mutableStateOf(prefs.fontScale) }
                Slider(value = scale, onValueChange = { scale = it }, onValueChangeFinished = { vm.setFontScale(scale) }, valueRange = 0.7f..2f)
                val fonts = LocalTextFonts.current
                FontRow(stringResource(R.string.font_hebrew), prefs.heFont, SAMPLE_HE, fonts.he, rtl = true) { picking = FontPick.He; vm.loadFonts() }
                FontRow(stringResource(R.string.font_english), prefs.enFont, SAMPLE_EN, fonts.en, rtl = false) { picking = FontPick.En; vm.loadFonts() }
                HorizontalDivider()
                SwitchRow(stringResource(R.string.niqqud), prefs.niqqud, vm::setNiqqud)
                SwitchRow(stringResource(R.string.cantillation), prefs.cantillation, vm::setCantillation)
                SwitchRow(stringResource(R.string.snap_to_verse), prefs.snapToVerse, vm::setSnapToVerse)
                SwitchRow(stringResource(R.string.keep_screen_on), prefs.keepScreenOn, vm::setKeepScreenOn)
            }
            Section(stringResource(R.string.today_learning)) {
                Choice(listOf(stringResource(R.string.israel), stringResource(R.string.diaspora)), if (prefs.diaspora) 1 else 0) { vm.setDiaspora(it == 1) }
                Label(stringResource(R.string.haftarah_custom))
                Choice(listOf(stringResource(R.string.ashkenazi), stringResource(R.string.sephardi)), if (prefs.sephardi) 1 else 0) { vm.setSephardi(it == 1) }
            }
            Section(stringResource(R.string.downloads)) {
                val bytes by vm.downloadBytes.collectAsStateWithLifecycle()
                val books by vm.downloaded.collectAsStateWithLifecycle()
                var deleting by remember { mutableStateOf(false) }
                val used = Formatter.formatShortFileSize(LocalContext.current, bytes)
                Text(stringResource(R.string.downloaded_books, books.size, used), style = MaterialTheme.typography.bodyLarge)
                if (books.isNotEmpty()) OutlinedButton(onClick = { deleting = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.delete_all_downloads), Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
                }
                if (deleting) ConfirmDelete(
                    title = stringResource(R.string.delete_all_downloads),
                    body = stringResource(R.string.delete_all_body, books.size, used),
                    onConfirm = { deleting = false; vm.deleteAllDownloads() },
                    onDismiss = { deleting = false },
                )
            }
            Section(stringResource(R.string.server)) {
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.server_url)) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.setServerUrl(url) }, enabled = url != prefs.serverUrl) { Text(stringResource(R.string.save)) }
                    OutlinedButton(onClick = { vm.setServerUrl("") }) { Text(stringResource(R.string.use_default)) }
                }
                Text(
                    stringResource(R.string.build, build ?: stringResource(R.string.unreachable)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Section(stringResource(R.string.about)) {
                Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    picking?.let { which ->
        val choices by vm.fonts.collectAsStateWithLifecycle()
        val he = which == FontPick.He
        FontSheet(
            title = stringResource(if (he) R.string.font_hebrew else R.string.font_english),
            current = if (he) prefs.heFont else prefs.enFont,
            fonts = choices?.let { if (he) it.he else it.en },
            sample = if (he) SAMPLE_HE else SAMPLE_EN,
            rtl = he,
            onPick = { name -> if (he) vm.setHeFont(name) else vm.setEnFont(name); picking = null },
            onDismiss = { picking = null },
        )
    }
}

private const val SAMPLE_HE = "בְּרֵאשִׁית בָּרָא אֱלֹהִים"
private const val SAMPLE_EN = "In the beginning God created"

/** A titled group of settings on a rounded card. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
private fun LanguageChoice() {
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
                // each language names itself, so it can be found from any interface language
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
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, body: String? = null) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A font setting: its name, and a line of text in the font itself. */
@Composable
private fun FontRow(label: String, name: String, sample: String, family: FontFamily, rtl: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(fontLabel(name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sample, style = sampleStyle(family, rtl), maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun fontLabel(name: String) =
    if (name.isEmpty()) stringResource(R.string.font_default) else name.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")

private fun sampleStyle(family: FontFamily, rtl: Boolean) =
    TextStyle(fontFamily = family, fontSize = 20.sp, textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr)

/** The device's fonts that can show the language, each as a line of text in it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontSheet(
    title: String, current: String, fonts: List<DeviceFont>?, sample: String, rtl: Boolean,
    onPick: (String) -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (fonts == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ModalBottomSheet
        }
        val all = listOf(DeviceFont("", stringResource(R.string.font_default), FontFamily.Serif)) + fonts
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(all, key = { it.name }) { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(f.name) }.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = f.name == current, onClick = { onPick(f.name) })
                    Column(Modifier.weight(1f)) {
                        Text(f.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(sample, style = sampleStyle(f.family, rtl), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            SegmentedButton(selected = i == selected, onClick = { onSelect(i) }, shape = SegmentedButtonDefaults.itemShape(i, labels.size)) {
                Text(l, maxLines = 1)
            }
        }
    }
}
