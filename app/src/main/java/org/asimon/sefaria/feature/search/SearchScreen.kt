package org.asimon.sefaria.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.asimon.sefaria.R
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.SearchRoute
import org.asimon.sefaria.core.text.SearchText
import org.asimon.sefaria.data.remote.SearchFilter
import org.asimon.sefaria.data.remote.SearchHitDto
import org.asimon.sefaria.data.repo.AppError
import org.asimon.sefaria.data.repo.TocRepository
import org.asimon.sefaria.data.repo.Load
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
        viewModelScope.launch {
            val toc = tocRepo.toc()
            if (toc is Load.Ok) _state.update { s -> s.copy(toc = toc.value.tree) }
        }
        more()
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
            val filter = SearchFilter(
                lang = s.lang, books = s.books.sorted(), categories = s.categories.sorted(),
                allForms = s.allForms, drop = s.drop,
            )
            when (val r = search.page(s.q, filter, s.next)) {
                is Load.Ok -> _state.update {
                    it.copy(
                        hits = it.hits + r.value.hits, next = r.value.next, done = r.value.next == null,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpen: (Any) -> Unit, onBack: () -> Unit, vm: SearchResultsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 5 } ?: false } }
    LaunchedEffect(nearEnd, s.hits.size) { if (nearEnd && s.error == null) vm.more() }
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
            items(s.hits, key = { it.ref + "|" + it.book }) { h ->
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = true, onClick = { picking = true }, label = { Text(scopeLabel(s)) }) }
            item { FilterChip(selected = s.lang == "he", onClick = { vm.setLang("he") }, label = { Text(stringResource(R.string.search_lang_he)) }) }
            item { FilterChip(selected = s.lang == "en", onClick = { vm.setLang("en") }, label = { Text(stringResource(R.string.search_lang_en)) }) }
        }
        if (s.forms.isNotEmpty() || s.allForms) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = s.allForms, onClick = { vm.setAllForms(!s.allForms) }, label = { Text(stringResource(R.string.search_all_forms)) }) }
                for ((_, forms) in s.forms.toSortedMap()) items(forms, key = { "form:$it" }) { f ->
                    FilterChip(selected = f !in s.drop, onClick = { vm.toggleForm(f) }, label = { Text(f) })
                }
            }
        }
    }
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    if (picking) ScopeSheet(s, downloaded, onDone = { b, c -> picking = false; vm.setScope(b, c) }, onDismiss = { picking = false })
}

/** "All of the library", or the scope's first names and how many more. */
@Composable
private fun scopeLabel(s: SearchResultsState): String {
    if (s.books.isEmpty() && s.categories.isEmpty()) return stringResource(R.string.search_scope_all)
    val names = s.categories.sorted().map { path -> categoryName(s.toc, path) } + s.books.sorted().map { t -> bookName(s.toc, t) }
    val shown = names.take(2).joinToString(" + ")
    return if (names.size > 2) shown + " " + stringResource(R.string.search_scope_more, names.size - 2) else shown
}

@Composable
private fun categoryName(toc: List<TocNodeDto>, path: String): String {
    var nodes = toc
    var name = path.substringAfterLast('/')
    for (part in path.split('/')) {
        val n = nodes.firstOrNull { !it.isBook && it.en == part } ?: break
        name = pick(n.en, n.he)
        nodes = n.children
    }
    return name
}

@Composable
private fun bookName(toc: List<TocNodeDto>, title: String): String {
    fun find(nodes: List<TocNodeDto>): TocNodeDto? {
        for (n in nodes) (if (n.isBook && n.title == title) n else find(n.children))?.let { return it }
        return null
    }
    return find(toc)?.let { pick(it.title, it.he) } ?: title
}

/**
 * The scope picker: the TOC as a tree with a box per category and book (a category covers all
 * its books), a title filter, and shortcuts for the whole library and the downloaded books.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeSheet(s: SearchResultsState, downloaded: Set<String>, onDone: (Set<String>, Set<String>) -> Unit, onDismiss: () -> Unit) {
    var books by remember { mutableStateOf(s.books) }
    var cats by remember { mutableStateOf(s.categories) }
    var open by remember { mutableStateOf(setOf<String>()) }
    var filter by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.search_scope_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onDone(books, cats) }) { Text(stringResource(R.string.search_scope_done)) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = books.isEmpty() && cats.isEmpty(), onClick = { books = emptySet(); cats = emptySet() }, label = { Text(stringResource(R.string.search_scope_clear)) }) }
            if (downloaded.isNotEmpty()) item {
                FilterChip(selected = cats.isEmpty() && books == downloaded, onClick = { books = downloaded; cats = emptySet() }, label = { Text(stringResource(R.string.search_scope_downloaded)) })
            }
        }
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.search_scope_filter)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // rows: (depth, node, path) of the visible tree; with a filter, the matching books flat
        val rows = remember(s.toc, open, filter) {
            val out = mutableListOf<Triple<Int, TocNodeDto, String>>()
            val f = filter.trim()
            fun walk(nodes: List<TocNodeDto>, depth: Int, path: String) {
                for (n in nodes) {
                    val p = if (n.isBook) path else if (path.isEmpty()) n.en else "$path/${n.en}"
                    if (f.isNotEmpty()) {
                        if (n.isBook && (n.title.contains(f, ignoreCase = true) || n.he.contains(f))) out += Triple(0, n, p)
                        else if (!n.isBook) walk(n.children, depth, p)
                    } else {
                        out += Triple(depth, n, p)
                        if (!n.isBook && p in open) walk(n.children, depth + 1, p)
                    }
                }
            }
            walk(s.toc, 0, "")
            out
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(rows, key = { (_, n, p) -> if (n.isBook) "b:" + n.title else "c:$p" }) { (depth, n, p) ->
                val covered = cats.any { c -> !n.isBook && p.startsWith("$c/") || n.isBook && (p == c || p.startsWith("$c/")) }
                val checked = covered || if (n.isBook) n.title in books else p in cats
                Row(
                    Modifier.fillMaxWidth().clickable { if (!n.isBook) open = if (p in open) open - p else open + p }
                        .padding(start = (8 + 16 * depth).dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, enabled = !covered, onCheckedChange = { on ->
                        if (n.isBook) books = if (on) books + n.title else books - n.title
                        else cats = if (on) cats.filterNot { it.startsWith("$p/") }.toSet() + p else cats - p
                    })
                    Text(
                        if (n.isBook) pick(n.title, n.he) else pick(n.en, n.he),
                        style = if (n.isBook) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    )
                    if (!n.isBook && filter.isBlank()) Text(if (p in open) "−" else "+", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
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
