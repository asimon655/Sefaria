package org.asimon.sefaria.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.asimon.sefaria.data.Settings
import org.asimon.sefaria.data.remote.CalendarDto
import org.asimon.sefaria.data.repo.CalendarRepository
import org.asimon.sefaria.data.repo.Load
import org.asimon.sefaria.ui.components.ErrorMessage
import org.asimon.sefaria.ui.components.text
import java.time.LocalDate
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
import org.asimon.sefaria.data.local.HistoryEntry
import org.asimon.sefaria.data.repo.UserDataRepository
import org.asimon.sefaria.feature.search.SearchBox
import org.asimon.sefaria.ui.components.pick
import javax.inject.Inject

/** Today's learning: null while loading. [date] is the day it was loaded for. */
data class TodayState(val date: LocalDate, val day: Load<CalendarDto>?)

@HiltViewModel
class HomeViewModel @Inject constructor(
    user: UserDataRepository,
    private val calendar: CalendarRepository,
    private val settings: Settings,
) : ViewModel() {
    /** null until loaded, so the empty state doesn't flash. */
    val recent = user.recentBooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _today = MutableStateFlow(TodayState(LocalDate.now(), null))
    val today = _today.asStateFlow()
    private var job: Job? = null

    init {
        // reload when the calendar settings change
        viewModelScope.launch {
            settings.prefs.map { it.diaspora to it.sephardi }.distinctUntilChanged().collect { load() }
        }
    }

    /** Loads again if the date changed since (the app was left open overnight) or the last try failed. */
    fun refresh() {
        val s = _today.value
        if (s.date != LocalDate.now() || s.day is Load.Err) load()
    }

    fun load() {
        job?.cancel()
        val date = LocalDate.now()
        _today.value = TodayState(date, null)
        job = viewModelScope.launch {
            val p = settings.prefs.value
            val r = calendar.day(date, p.diaspora, p.sephardi)
            _today.value = TodayState(date, r)
            // the coming week, so Today works offline for a while
            if (r is Load.Ok && !r.offline) for (i in 1L..6L) calendar.day(date.plusDays(i), p.diaspora, p.sephardi)
        }
    }
}

/** Where was I, and where to go: search, the last book read, and other recent books. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Any) -> Unit, onSettings: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val recent by vm.recent.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
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
            if (first == null) item(key = "empty") {
                Text(
                    stringResource(R.string.home_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                item(key = "continue") { ContinueCard(first) { onOpen(resume(first)) } }
            }
            today(today, onOpen, vm::load)
            if (list.size > 1) {
                item(key = "recent-title") { SectionTitle(stringResource(R.string.recent_books)) }
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

@Composable
private fun SectionTitle(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
)

/** Today's learning (APP_SPEC §4.6): one row per schedule; a tap opens its first ref. */
private fun LazyListScope.today(s: TodayState, onOpen: (Any) -> Unit, retry: () -> Unit) {
    item(key = "today-title") { SectionTitle(stringResource(R.string.today_learning)) }
    when (val day = s.day) {
        null -> item(key = "today-loading") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        is Load.Err -> item(key = "today-error") { ErrorMessage(day.error, onRetry = retry, compact = true) }
        is Load.Ok -> items(day.value.items, key = { "today-" + it.id }) { it ->
            ListItem(
                overlineContent = { Text(pick(it.title.en, it.title.he)) },
                headlineContent = { Text(pick(it.display.en, it.display.he), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = it.note?.let { n -> { Text(pick(n.en, n.he)) } },
                modifier = Modifier.clickable { onOpen(ReaderRoute(it.book, it.ref)) },
            )
        }
    }
}

/** Reopens a history entry where it was left, with the trail that led there. */
fun resume(h: HistoryEntry) = ReaderRoute(h.book, h.ref, highlight = false, trail = h.trail)

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
            val trail = h.trail
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
