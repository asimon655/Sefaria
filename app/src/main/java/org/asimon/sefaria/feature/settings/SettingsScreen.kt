package org.asimon.sefaria.feature.settings

import org.asimon.sefaria.ui.AppLanguage
import androidx.compose.runtime.remember
import androidx.activity.compose.LocalActivity
import org.asimon.sefaria.ui.components.label
import androidx.compose.ui.res.stringResource
import org.asimon.sefaria.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.asimon.sefaria.BuildConfig
import org.asimon.sefaria.data.Settings
import org.asimon.sefaria.data.ThemeMode
import org.asimon.sefaria.data.repo.LibraryRepository
import org.asimon.sefaria.ui.components.BackButton
import org.asimon.sefaria.ui.components.ThemePicker
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: Settings,
    private val library: LibraryRepository,
) : ViewModel() {
    val prefs = settings.prefs
    private val _build = MutableStateFlow<String?>(null)
    val build = _build.asStateFlow()

    init { refresh() }

    private fun refresh() = viewModelScope.launch { _build.value = library.refreshBuild() }

    fun setServerUrl(url: String) = viewModelScope.launch {
        settings.setServerUrl(url)
        refresh()
    }

    fun setTheme(t: ThemeMode) = viewModelScope.launch { settings.setTheme(t) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val build by vm.build.collectAsStateWithLifecycle()
    var url by rememberSaveable(prefs.serverUrl) { mutableStateOf(prefs.serverUrl) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
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
            HorizontalDivider()
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
            ThemePicker(prefs.theme, vm::setTheme)
            HorizontalDivider()
            Text(stringResource(R.string.server), style = MaterialTheme.typography.titleSmall)
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
            HorizontalDivider()
            Text(stringResource(R.string.about), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.about_text, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
