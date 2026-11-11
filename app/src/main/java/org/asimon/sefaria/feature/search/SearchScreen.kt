package org.asimon.sefaria.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import org.asimon.sefaria.core.text.TitleMatch
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.asimon.sefaria.core.text.Kind
import org.asimon.sefaria.data.remote.TocNodeDto
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.asimon.sefaria.R
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.SearchRoute
import org.asimon.sefaria.core.text.SearchText
import org.asimon.sefaria.data.remote.SearchFilter
import org.asimon.sefaria.data.remote.SearchHitDto
import org.asimon.sefaria.data.AppError
import org.asimon.sefaria.data.repo.TocRepository
import org.asimon.sefaria.data.Load
import org.asimon.sefaria.data.repo.SearchRepository
import org.asimon.sefaria.ui.components.BackButton
import org.asimon.sefaria.ui.components.ErrorMessage
import org.asimon.sefaria.ui.components.Art
import org.asimon.sefaria.ui.components.EmptyState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.ui.components.text
import javax.inject.Inject

/**
 * [books] and [categories] (TOC paths, "Tanakh/Torah") are the scope, their union; both empty
 * means the whole library. [drop] holds forms the user turned off; [forms] are the forms the
 * last page used, per query word.
 */
data class SearchResultsState(
    val q: String,
    val books: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
    val lang: String = "",
    val allForms: Boolean = false,
    val drop: Set<String> = emptySet(),
    val forms: Map<String, List<String>> = emptyMap(),
    val toc: List<TocNodeDto> = emptyList(),
    /** [toc] as the scope picker reads it; built once, off the main thread. */
    val tree: ScopeTree = ScopeTree(emptyList()),
    val hits: List<SearchHitDto> = emptyList(),
    val next: String? = null,
    val loading: Boolean = false,
    val done: Boolean = false,
    val offline: Boolean = false,
    val error: AppError? = null,
)

/**
 * Full-text results (APP_SPEC §4.5): ranked by the server, or offline in two tiers; infinite
 * scroll, a scope of any books and categories, the language, and the words' forms.
 */
@HiltViewModel
class SearchResultsViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val search: SearchRepository,
    private val tocRepo: TocRepository,
) : ViewModel() {
    private val route = handle.toRoute<SearchRoute>()
    private val _state = MutableStateFlow(
        SearchResultsState(q = route.q, books = setOfNotNull(route.book), lang = if (SearchText.isHebrew(route.q)) "he" else "en")
    )
    val state = _state.asStateFlow()
    private var job: Job? = null

    init {
        val tree = viewModelScope.launch {
            val toc = tocRepo.toc()
            if (toc is Load.Ok) {
                val tree = withContext(Dispatchers.Default) { ScopeTree(toc.value.tree) }
                _state.update { s -> s.copy(toc = toc.value.tree, tree = tree) }
            }
        }
        if (route.scope.isEmpty()) more()
        else viewModelScope.launch {
            // a scope chosen before searching (the search tab)
            val downloaded = if (Scope.needsDownloaded(route.scope)) search.downloaded.first() else emptySet()
            val scope = Scope.decode(route.scope, downloaded)
            _state.update { it.copy(books = it.books + scope.books, categories = scope.categories) }
            // the tree says it in few names (ScopeTree.compact); without it every title would go in the request
            if (scope.books.size > COMPACT_FROM) tree.join()
            more()
        }
    }

    private companion object {
        /** A scope of more books than this waits for the tree, to be sent as categories where it can. */
        const val COMPACT_FROM = 20
    }

    /** A new query typed on the results page: its results replace these, with the scope kept. */
    fun setQuery(q: String) = restart {
        it.copy(q = q, lang = if (SearchText.isHebrew(q)) "he" else "en", allForms = false, drop = emptySet(), forms = emptyMap())
    }

    fun setScope(books: Set<String>, categories: Set<String>) = restart { it.copy(books = books, categories = categories) }
    fun setLang(l: String) = restart { it.copy(lang = l) }
    fun setAllForms(on: Boolean) = restart { it.copy(allForms = on) }
    fun toggleForm(form: String) = restart { it.copy(drop = if (form in it.drop) it.drop - form else it.drop + form) }
    /** Titles of the downloaded books, for the scope picker's shortcut. */
    val downloaded = search.downloaded.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
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
            // as few names as say it (ScopeTree.compact): they travel in the request
            val scope = s.tree.compact(Scope(s.books, s.categories))
            val filter = SearchFilter(
                lang = s.lang, books = scope.books.sorted(), categories = scope.categories.sorted(),
                allForms = s.allForms, drop = s.drop,
            )
            when (val r = search.page(s.q, filter, s.next)) {
                is Load.Ok -> _state.update {
                    // A place the list already has isn't added again: a ranking made anew between
                    // two pages (the server keeps one for a minute) can repeat one, and the list
                    // names its rows by place, where a second row of the same name is a crash.
                    val have = it.hits.mapTo(HashSet()) { h -> hitKey(h) }
                    val fresh = r.value.hits.filter { h -> have.add(hitKey(h)) }
                    it.copy(
                        hits = it.hits + fresh, next = r.value.next, done = r.value.next == null,
                        loading = false, offline = r.value.offline,
                        // a dropped form is no longer in the response: keep showing its chip, off
                        forms = if (s.next == null) mergeForms(r.value.forms, it.forms, it.drop) else it.forms,
                    )
                }
                is Load.Err -> _state.update { it.copy(loading = false, error = r.error) }
            }
        }
    }

    /** The page closed: what offline search opened goes with it. */
    override fun onCleared() = search.release()

    private fun mergeForms(now: Map<String, List<String>>, before: Map<String, List<String>>, drop: Set<String>) =
        (now.keys + before.keys).associateWith { w ->
            ((now[w].orEmpty()) + before[w].orEmpty().filter { it in drop }).distinct().sorted()
        }.filterValues { it.isNotEmpty() }
}

/** What names a hit in the list: its place. */
private fun hitKey(h: SearchHitDto) = h.ref + "|" + h.book

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpen: (Any) -> Unit, onBack: () -> Unit, vm: SearchResultsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 5 } ?: false } }
    // the cursor is a key too: a page that added no rows (all of it already listed) still leads to the next
    LaunchedEffect(nearEnd, s.hits.size, s.next) { if (nearEnd && s.error == null) vm.more() }
    Scaffold(
        topBar = {
            // the field stays, with the query in it: edit it and search again without going back
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(top = 8.dp, end = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.padding(top = 4.dp)) { BackButton(onBack) }
                SearchBox(
                    onOpen = { r -> if (r is SearchRoute) vm.setQuery(r.q) else onOpen(r) },
                    modifier = Modifier.weight(1f),
                    shown = s.q,
                )
            }
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
            items(s.hits, key = ::hitKey) { h ->
                Hit(h, Modifier.clickable { onOpen(ReaderRoute(h.book, h.ref)) })
                HorizontalDivider()
            }
            item(key = "status") {
                when {
                    s.error != null -> ErrorMessage(s.error!!, onRetry = vm::retry)
                    s.loading -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                    }
                    s.done && s.hits.isEmpty() -> EmptyState(Art.NoResults, stringResource(R.string.search_none, s.q), stringResource(R.string.search_none_body))
                }
            }
        }
    }
}

@Composable
private fun Filters(s: SearchResultsState, vm: SearchResultsViewModel) {
    var picking by remember { mutableStateOf(false) }
    val scope = Scope(s.books, s.categories)
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            scopeChips(s.tree, scope, downloaded, onPick = { picking = true }, onScope = { vm.setScope(it.books, it.categories) })
            item(key = "he") { FilterChip(selected = s.lang == "he", onClick = { vm.setLang("he") }, label = { Text(stringResource(R.string.search_lang_he)) }) }
            item(key = "en") { FilterChip(selected = s.lang == "en", onClick = { vm.setLang("en") }, label = { Text(stringResource(R.string.search_lang_en)) }) }
        }
        if (s.forms.isNotEmpty() || s.allForms) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = s.allForms, onClick = { vm.setAllForms(!s.allForms) }, label = { Text(stringResource(R.string.search_all_forms)) }) }
                // two query words can share a form: the word is in the key so both chips can show
                for ((word, forms) in s.forms.toSortedMap()) items(forms, key = { "form:$word:$it" }) { f ->
                    FilterChip(selected = f !in s.drop, onClick = { vm.toggleForm(f) }, label = { Text(f) })
                }
            }
        }
    }
    if (picking) ScopeSheet(s.tree, scope, downloaded, onDone = { picking = false; vm.setScope(it.books, it.categories) }, onDismiss = { picking = false })
}

@Composable
private fun Hit(h: SearchHitDto, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pick(h.human.ifEmpty { h.ref }, h.heRef), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            when (Kind.of(h.match)) {
                Kind.PREFIX -> MatchLabel(stringResource(R.string.search_match_prefix))
                Kind.FORM -> MatchLabel(stringResource(R.string.search_match_form))
                else -> {}
            }
        }
        Text(bold(h.snippet), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
    }
}

@Composable
private fun MatchLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
