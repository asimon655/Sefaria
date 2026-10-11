package org.asimon.sefaria.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.asimon.sefaria.R
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.core.TrailStep
import org.asimon.sefaria.data.local.HistoryEntry
import org.asimon.sefaria.data.repo.UserDataRepository
import org.asimon.sefaria.feature.search.SearchBox
import org.asimon.sefaria.ui.components.pick
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(user: UserDataRepository) : ViewModel() {
    /** null until loaded, so the empty state doesn't flash. */
    val recent = user.recentBooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

/** Where was I, and where to go: search, the last book read, and other recent books. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Any) -> Unit, onSettings: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val recent by vm.recent.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_home)) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            item(key = "search") { SearchBox(onOpen, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            val list = recent ?: return@LazyColumn
            val first = list.firstOrNull()
            if (first == null) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.home_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                return@LazyColumn
            }
            item(key = "continue") { ContinueCard(first) { onOpen(resume(first)) } }
            if (list.size > 1) {
                item(key = "recent-title") {
                    Text(
                        stringResource(R.string.recent_books),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(list.drop(1), key = { it.id }) { h ->
                    ListItem(
                        headlineContent = { Text(pick(h.label, h.heLabel), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { onOpen(resume(h)) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

/** Reopens a history entry where it was left, with the trail that led there. */
fun resume(h: HistoryEntry) = ReaderRoute(h.book, h.ref, highlight = false, trail = TrailStep.split(h.trail))

@Composable
private fun ContinueCard(h: HistoryEntry, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.continue_reading), style = MaterialTheme.typography.labelLarge)
            Text(pick(h.label, h.heLabel), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val trail = TrailStep.decodeAll(TrailStep.split(h.trail))
            if (trail.isNotEmpty()) Text(
                trailText(trail.map { pick(it.en, it.he) }),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Genesis 1:2 › Rashi …" (the arrow follows the reading direction through the bidi algorithm). */
fun trailText(labels: List<String>) = labels.joinToString(" › ")
