package org.asimon.sefaria.feature.settings

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
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            val themes = ThemeMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                themes.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = prefs.theme == t,
                        onClick = { vm.setTheme(t) },
                        shape = SegmentedButtonDefaults.itemShape(i, themes.size),
                    ) { Text(t.name, maxLines = 1) }
                }
            }
            HorizontalDivider()
            Text("Server", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Server URL") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.setServerUrl(url) }, enabled = url != prefs.serverUrl) { Text("Save") }
                OutlinedButton(onClick = { vm.setServerUrl("") }) { Text("Default") }
            }
            Text(
                "Build: ${build ?: "unreachable"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleSmall)
            Text(
                "Version ${BuildConfig.VERSION_NAME}. Texts come from the Sefaria library; each version " +
                    "carries its own license, listed on the book page.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
