package org.asimon.sefaria.feature.saved

import androidx.compose.ui.res.stringResource
import org.asimon.sefaria.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.data.Settings
import org.asimon.sefaria.data.repo.UserDataRepository
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.core.TrailStep
import org.asimon.sefaria.feature.home.resume
import org.asimon.sefaria.feature.home.trailText
import org.asimon.sefaria.feature.downloads.DownloadsList
import org.asimon.sefaria.feature.downloads.DownloadsViewModel
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class SavedViewModel @Inject constructor(private val user: UserDataRepository, settings: Settings) : ViewModel() {
    val bookmarks = user.bookmarks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = user.history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val prefs = settings.prefs

    fun clearHistory() = viewModelScope.launch { user.clearHistory() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(onOpen: (ReaderRoute) -> Unit, vm: SavedViewModel = hiltViewModel(), dvm: DownloadsViewModel = hiltViewModel()) {
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_saved)) },
                actions = {
                    if (tab == 1 && history.isNotEmpty()) IconButton(onClick = vm::clearHistory) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.clear_history))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.bookmarks)) })
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.history)) })
                Tab(tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.downloads)) })
            }
            if (tab == 2) {
                DownloadsList(onOpen, dvm)
                return@Column
            }
            val empty = if (tab == 0) bookmarks.isEmpty() else history.isEmpty()
            if (empty) Text(
                stringResource(if (tab == 0) R.string.empty_bookmarks else R.string.empty_history),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            LazyColumn {
                if (tab == 0) items(bookmarks, key = { it.id }) { b ->
                    ListItem(
                        headlineContent = { Text(pick(b.label, b.heLabel)) },
                        supportingContent = { Text(b.preview, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { onOpen(ReaderRoute(b.book, b.ref)) },
                    )
                    HorizontalDivider()
                } else items(history, key = { it.id }) { h ->
                    ListItem(
                        headlineContent = { Text(pick(h.label, h.heLabel)) },
                        supportingContent = {
                            val trail = TrailStep.decodeAll(TrailStep.split(h.trail))
                            Column {
                                if (trail.isNotEmpty()) Text(
                                    trailText(trail.map { pick(it.en, it.he) }),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(date.format(Date(h.visitedAt)))
                            }
                        },
                        modifier = Modifier.clickable { onOpen(resume(h)) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
