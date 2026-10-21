package org.asimon.sefaria.feature.book

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.IconButton
import org.asimon.sefaria.SearchRoute
import org.asimon.sefaria.ui.components.screenAlign
import org.asimon.sefaria.ui.components.text
import org.asimon.sefaria.ui.components.other
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.ui.components.prose
import org.asimon.sefaria.ui.components.hebrewUi
import org.asimon.sefaria.data.repo.AppError
import androidx.compose.ui.res.stringResource
import org.asimon.sefaria.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.asimon.sefaria.BookRoute
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.ref.Node
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.core.text.Hebrew
import org.asimon.sefaria.data.Settings
import org.asimon.sefaria.data.remote.AltNodeDto
import org.asimon.sefaria.data.repo.LibraryRepository
import org.asimon.sefaria.feature.downloads.BookDownload
import org.asimon.sefaria.feature.downloads.AskNotificationsOnDownload
import org.asimon.sefaria.feature.downloads.DownloadsViewModel
import org.asimon.sefaria.data.repo.Load
import org.asimon.sefaria.data.repo.UserDataRepository
import org.asimon.sefaria.ui.components.BackButton
import org.asimon.sefaria.ui.components.ErrorMessage
import org.asimon.sefaria.ui.components.Loading
import javax.inject.Inject

data class BookState(val book: Book? = null, val error: AppError? = null)

@HiltViewModel
class BookViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val library: LibraryRepository,
    user: UserDataRepository,
    settings: Settings,
) : ViewModel() {
    val title = handle.toRoute<BookRoute>().title
    private val _state = MutableStateFlow(BookState())
    val state = _state.asStateFlow()
    val position = user.position(title).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val prefs = settings.prefs

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = BookState()
        _state.value = when (val r = library.book(title)) {
            is Load.Ok -> BookState(r.value)
            is Load.Err -> BookState(error = r.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    onOpen: (Any) -> Unit,
    onBack: () -> Unit,
    vm: BookViewModel = hiltViewModel(),
    dvm: DownloadsViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    AskNotificationsOnDownload(dvm)
    val position by vm.position.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.search_in_book)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onOpen(SearchRoute(query.trim(), vm.title)) }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Text(s.book?.let { pick(it.title, it.he) } ?: if (hebrewUi()) "" else vm.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { BackButton { if (searching) searching = false else onBack() } },
                actions = {
                    if (!searching) IconButton(onClick = { searching = true }) {
                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search_in_book))
                    }
                },
            )
        },
    ) { padding ->
        val book = s.book
        when {
            s.error != null -> ErrorMessage(s.error!!, vm::load, Modifier.padding(padding))
            book == null -> Loading(Modifier.padding(padding))
            else -> {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val alt = book.dto.altStructs.entries.firstOrNull()
                val open = { ref: String -> onOpen(ReaderRoute(book.title, ref)) }
                LazyColumn(contentPadding = padding) {
                    item { Header(book) }
                    item {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val pos = position
                            if (pos != null) {
                                Button(onClick = { onOpen(ReaderRoute(book.title, pos.ref, highlight = false)) }) { Text(stringResource(R.string.continue_reading)) }
                                OutlinedButton(onClick = { open(book.title) }) { Text(stringResource(R.string.start)) }
                            } else {
                                Button(onClick = { open(book.title) }) { Text(stringResource(R.string.start_reading)) }
                            }
                        }
                    }
                    item { BookDownload(book.title, book.he, dvm) }
                    if (alt != null) item {
                        PrimaryTabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.contents)) })
                            Tab(tab == 1, onClick = { tab = 1 }, text = { Text(altTitle(alt.key)) })
                        }
                    }
                    if (alt != null && tab == 1) {
                        altNodes(alt.value, open)
                    } else {
                        item { Structure(book, book.root, hebrewUi(), open, top = true) }
                    }
                    item { Versions(book) }
                }
            }
        }
    }
}

// Hebrew names of the common alternate structures (the server sends only English keys)
private val altHe = mapOf(
    "Parasha" to "פרשות", "Chapters" to "פרקים", "Chapter" to "פרקים", "Topic" to "נושאים",
    "Letter" to "מכתבים", "Venice" to "דפוס ונציה", "Vilna" to "דפוס וילנא", "Pages" to "דפים",
    "Daf" to "דפים", "Gate" to "שערים", "Book" to "ספרים", "Contents" to "תוכן", "Siman" to "סימנים",
    "Section" to "חלקים", "Daf Yomi" to "דף יומי",
)

@Composable
private fun altTitle(key: String): String = when {
    key == "Parasha" -> stringResource(R.string.parashot)
    hebrewUi() -> altHe[key] ?: stringResource(R.string.other_divisions)
    else -> key
}

@Composable
private fun Header(book: Book) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // the top bar has the title in the interface language; show the other one here
        val second = other(book.title, book.he)
        if (second.isNotEmpty()) Text(second, style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Content), textAlign = screenAlign(), modifier = Modifier.fillMaxWidth())
        val categories = if (hebrewUi()) book.dto.heCategories else book.dto.categories
        if (categories.isNotEmpty()) Text(
            categories.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        // older builds send only slugs ("joseph-karo"), which are not worth showing
        if (book.dto.authorNames.isNotEmpty()) Text(book.dto.authorNames.map { pick(it.en, it.he) }.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
        val desc = prose(book.dto.desc.en, book.dto.desc.he).ifEmpty { prose(book.dto.shortDesc.en, book.dto.shortDesc.he) }
        if (desc.isNotEmpty()) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            Text(
                desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}

/** A compact section label for grids: "17"/"יז", "2a"/"ב." */
private fun gridLabel(addrType: String, i: Int, hebrew: Boolean): String = when {
    !hebrew -> Ref.label(addrType, i)
    addrType == "Talmud" -> Hebrew.gematria(i / 2 + 1) + (if (i % 2 == 0) "." else ":")
    else -> Hebrew.gematria(i + 1)
}

/** The schema tree: complex nodes expand; jagged nodes show a grid of their top-level sections. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Structure(book: Book, node: Node, hebrew: Boolean, open: (String) -> Unit, top: Boolean = false) {
    if (node.isJagged) {
        val shape = node.shape
        val nodeRef = Ref(book, node)
        if (shape == null || node.depth < 2) {
            if (!top) return // a depth-1 node is opened from its title row
            OutlinedButton(onClick = { open(nodeRef.url()) }, Modifier.padding(16.dp)) { Text(stringResource(R.string.open)) }
            return
        }
        // Hebrew numbering reads right to left
        CompositionLocalProvider(LocalLayoutDirection provides if (hebrew) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        FlowRow(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val type = node.addressTypes[0]
            for (i in 0 until shape.n) {
                if (shape.sub?.get(i) == null) continue
                OutlinedCard(onClick = { open(Ref(book, node, listOf(i)).url()) }, Modifier.widthIn(min = 48.dp)) {
                    Text(
                        gridLabel(type, i, hebrew),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp).widthIn(min = 28.dp),
                    )
                }
            }
        }
        }
        return
    }
    Column(Modifier.padding(start = if (top) 0.dp else 12.dp)) {
        for (child in node.children.orEmpty()) {
            if (child.slotEnd == child.slotStart) continue // no text in this node
            if (child.isDefault) {
                Structure(book, child, hebrew, open)
                continue
            }
            val leaf = child.isJagged && child.depth < 2
            var expanded by rememberSaveable(child.key) { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { if (leaf) open(Ref(book, child).url()) else expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (hebrew && child.he.isNotEmpty()) child.he else child.en, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (!leaf) Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, Modifier.size(20.dp))
            }
            if (expanded) Structure(book, child, hebrew, open)
            HorizontalDivider()
        }
    }
}

private fun LazyListScope.altNodes(nodes: List<AltNodeDto>, open: (String) -> Unit) {
    items(nodes) { n ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = n.ref.isNotEmpty()) { open(n.ref) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(pick(n.en, n.he), Modifier.weight(1f))
            val second = other(n.en, n.he)
            if (second.isNotEmpty()) Text(second, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Rtl))
        }
        HorizontalDivider()
    }
}

/** The editions the reader shows (each language's default version), with their licenses. */
@Composable
private fun Versions(book: Book) {
    val shown = book.dto.versions.filter { it.default }.sortedBy { it.lang }
    if (shown.isEmpty()) return
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.editions), style = MaterialTheme.typography.titleSmall)
        for (v in shown) Column(Modifier.padding(vertical = 6.dp)) {
            Text(pick(v.title, v.he), style = MaterialTheme.typography.bodyMedium)
            Text(
                listOf(v.lang, v.license).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
