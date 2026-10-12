package org.asimon.sefaria.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.asimon.sefaria.R
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.SearchRoute
import org.asimon.sefaria.core.text.SearchText
import org.asimon.sefaria.data.remote.SearchFilter
import org.asimon.sefaria.data.remote.SearchHitDto
import org.asimon.sefaria.data.repo.AppError
import org.asimon.sefaria.data.repo.LibraryRepository
import org.asimon.sefaria.data.repo.Load
import org.asimon.sefaria.data.repo.SearchRepository
import org.asimon.sefaria.ui.components.BackButton
import org.asimon.sefaria.ui.components.ErrorMessage
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.ui.components.text
import javax.inject.Inject

/** A top-level TOC category offered as a filter. */
data class CategoryChip(val en: String, val he: String)

data class SearchResultsState(
    val q: String,
    val book: String? = null,
    val category: String? = null, // a top-level category, or null for all
    val lang: String = "",
    val categories: List<CategoryChip> = emptyList(),
    val hits: List<SearchHitDto> = emptyList(),
    val next: String? = null,
    val loading: Boolean = false,
    val done: Boolean = false,
    val offline: Boolean = false,
    val error: AppError? = null,
)

/** Full-text results (APP_SPEC §4.5): reading order, infinite scroll, category and language filters. */
@HiltViewModel
class SearchResultsViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val search: SearchRepository,
    private val library: LibraryRepository,
) : ViewModel() {
    private val route = handle.toRoute<SearchRoute>()
    private val _state = MutableStateFlow(
        SearchResultsState(q = route.q, book = route.book, lang = if (SearchText.isHebrew(route.q)) "he" else "en")
    )
    val state = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val toc = library.toc()
            if (toc is Load.Ok) _state.update { s -> s.copy(categories = toc.value.tree.filter { !it.isBook }.map { CategoryChip(it.en, it.he) }) }
        }
        more()
    }

    fun setCategory(c: String?) = restart { it.copy(category = c) }
    fun setLang(l: String) = restart { it.copy(lang = l) }
    fun retry() = more()

    private fun restart(f: (SearchResultsState) -> SearchResultsState) {
        job?.cancel()
        _state.update { f(it).copy(hits = emptyList(), next = null, done = false, loading = false, error = null, offline = false) }
        more()
    }

    /** Loads the next page, unless one is loading or the list is complete. */
    fun more() {
        val s = _state.value
        if (s.loading || s.done) return
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            val filter = SearchFilter(lang = s.lang, book = s.book, category = s.category)
            when (val r = search.page(s.q, filter, s.next)) {
                is Load.Ok -> _state.update {
                    it.copy(
                        hits = it.hits + r.value.hits, next = r.value.next, done = r.value.next == null,
                        loading = false, offline = r.value.offline,
                    )
                }
                is Load.Err -> _state.update { it.copy(loading = false, error = r.error) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpen: (Any) -> Unit, onBack: () -> Unit, vm: SearchResultsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 5 } ?: false } }
    LaunchedEffect(nearEnd, s.hits.size) { if (nearEnd && s.error == null) vm.more() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.q, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content), maxLines = 1) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), state = list) {
            item(key = "filters") { Filters(s, vm) }
            if (s.offline) item(key = "offline") {
                Text(
                    stringResource(R.string.search_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(s.hits, key = { it.ref + "|" + it.book }) { h ->
                Hit(h, Modifier.clickable { onOpen(ReaderRoute(h.book, h.ref)) })
                HorizontalDivider()
            }
            item(key = "status") {
                when {
                    s.error != null -> ErrorMessage(s.error!!.text(), onRetry = vm::retry)
                    s.loading -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                    }
                    s.done && s.hits.isEmpty() -> Text(
                        stringResource(R.string.search_none, s.q),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Filters(s: SearchResultsState, vm: SearchResultsViewModel) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { FilterChip(selected = s.lang == "he", onClick = { vm.setLang("he") }, label = { Text(stringResource(R.string.search_lang_he)) }) }
        item { FilterChip(selected = s.lang == "en", onClick = { vm.setLang("en") }, label = { Text(stringResource(R.string.search_lang_en)) }) }
        if (s.book == null) {
            item { FilterChip(selected = s.category == null, onClick = { vm.setCategory(null) }, label = { Text(stringResource(R.string.search_all)) }) }
            items(s.categories, key = { it.en }) { c ->
                FilterChip(selected = s.category == c.en, onClick = { vm.setCategory(c.en) }, label = { Text(pick(c.en, c.he)) })
            }
        }
    }
}

@Composable
private fun Hit(h: SearchHitDto, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(pick(h.human.ifEmpty { h.ref }, h.heRef), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(bold(h.snippet), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
    }
}

/** The snippet's <b>…</b> as bold spans; the rest is plain text (the server strips other tags). */
internal fun bold(snippet: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < snippet.length) {
        val open = snippet.indexOf("<b>", i)
        if (open < 0) {
            append(snippet.substring(i))
            break
        }
        append(snippet.substring(i, open))
        val close = snippet.indexOf("</b>", open).let { if (it < 0) snippet.length else it }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(snippet.substring(open + 3, close)) }
        i = close + 4
    }
}
