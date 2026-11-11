package org.asimon.sefaria.feature.book

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import org.asimon.sefaria.data.AppError
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
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.stringArrayResource
import org.asimon.sefaria.core.ref.Catalog
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
import org.asimon.sefaria.data.repo.BookRepository
import org.asimon.sefaria.feature.downloads.BookDownload
import org.asimon.sefaria.feature.downloads.AskNotificationsOnDownload
import org.asimon.sefaria.feature.downloads.DownloadsViewModel
import org.asimon.sefaria.data.Load
import org.asimon.sefaria.data.repo.UserDataRepository
import org.asimon.sefaria.ui.components.BackButton
import org.asimon.sefaria.ui.components.ErrorMessage
import org.asimon.sefaria.ui.components.Loading
import javax.inject.Inject

data class BookState(val book: Book? = null, val error: AppError? = null)

@HiltViewModel
class BookViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val books: BookRepository,
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
        _state.value = when (val r = books.book(title)) {
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
                // every alternate structure gets a tab, after the contents
                val alts = remember(book) { book.dto.altStructs.entries.toList() }
                val alt = alts.getOrNull(tab - 1)
                var altOpen by rememberSaveable { mutableStateOf(setOf<String>()) }
                val catalog = remember(book) { Catalog(listOf(book)) }
                val open = { ref: String -> onOpen(ReaderRoute(book.title, ref)) }
                // where Continue reading goes, as a slot: the parts holding it show a mark
                val last = remember(catalog, position?.ref) {
                    position?.ref?.let { Ref.parseOrNull(catalog, it) }?.let { runCatching { it.slots().first }.getOrNull() }
                }
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
                    if (alts.isNotEmpty()) item {
                        val tabs = @Composable {
                            Tab(tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.contents)) })
                            alts.forEachIndexed { i, a -> Tab(tab == i + 1, onClick = { tab = i + 1 }, text = { Text(altTitle(a.key)) }) }
                        }
                        if (alts.size == 1) PrimaryTabRow(selectedTabIndex = tab) { tabs() }
                        else PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) { tabs() }
                    }
                    if (alt != null) {
                        val rows = altRows(alt.key, alt.value, altOpen)
                        items(rows, key = { it.key }) { r ->
                            AltRow(r, catalog, book, last, expanded = r.key in altOpen, open = open) {
                                altOpen = if (r.key in altOpen) altOpen - r.key else altOpen + r.key
                            }
                            HorizontalDivider()
                        }
                    } else {
                        item { Structure(book, book.root, hebrewUi(), open, last, top = true) }
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

/** Marks the part of the book last read (the slot Continue reading opens at). */
@Composable
private fun LastReadIcon() = Icon(
    Icons.Outlined.History,
    contentDescription = stringResource(R.string.last_read),
    tint = MaterialTheme.colorScheme.primary,
    modifier = Modifier.size(16.dp),
)

/**
 * The schema tree: complex nodes expand; jagged nodes show a grid of their top-level sections.
 * The section and the nodes holding the slot [last] (the last read) carry [LastReadIcon].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Structure(book: Book, node: Node, hebrew: Boolean, open: (String) -> Unit, last: Int?, top: Boolean = false) {
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
            val lastSection = remember(node, last) {
                if (last == null || last !in node.slotStart until node.slotEnd) -1
                else (0 until shape.n).firstOrNull { i ->
                    shape.sub?.get(i) != null && runCatching { last in Ref(book, node, listOf(i)).slots() }.getOrDefault(false)
                } ?: -1
            }
            for (i in 0 until shape.n) {
                if (shape.sub?.get(i) == null) continue
                val here = i == lastSection
                OutlinedCard(
                    onClick = { open(Ref(book, node, listOf(i)).url()) },
                    Modifier.widthIn(min = 48.dp),
                    colors = if (here) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        else CardDefaults.outlinedCardColors(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(gridLabel(type, i, hebrew), textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 28.dp))
                        if (here) LastReadIcon()
                    }
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
                Structure(book, child, hebrew, open, last)
                continue
            }
            val here = last != null && last in child.slotStart until child.slotEnd
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
                if (here) LastReadIcon()
                if (!leaf) Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, Modifier.padding(start = 8.dp).size(20.dp))
            }
            if (expanded) Structure(book, child, hebrew, open, last)
            HorizontalDivider()
        }
    }
}

/**
 * A row of an alternate structure: a node ([aliyah] null) or one of a parasha's aliyot. A node
 * opens its [ref] if it has one; one with children (or aliyot) also expands, with its own button
 * when the row's tap opens it.
 */
private data class AltRowData(val key: String, val depth: Int, val node: AltNodeDto, val aliyah: Int?, val expandable: Boolean) {
    val ref get() = if (aliyah != null) node.refs[aliyah] else node.ref
}

/** A parasha's refs are its seven aliyot; other structures' refs have no names to show. */
private const val ALIYOT = 7

/** The open part of an alternate structure, as rows: each node, then what's under it when open. */
private fun altRows(structKey: String, nodes: List<AltNodeDto>, open: Set<String>): List<AltRowData> {
    val out = ArrayList<AltRowData>()
    val parasha = structKey == "Parasha"
    fun walk(ns: List<AltNodeDto>, depth: Int, prefix: String) {
        ns.forEachIndexed { i, n ->
            val key = "$prefix/$i"
            val aliyot = parasha && n.children.isEmpty() && n.refs.size == ALIYOT
            out += AltRowData(key, depth, n, null, n.children.isNotEmpty() || aliyot)
            if (key !in open) return@forEachIndexed
            if (aliyot) for (j in 0 until ALIYOT) out += AltRowData("$key/a$j", depth + 1, n, j, false)
            walk(n.children, depth + 1, key)
        }
    }
    walk(nodes, 0, structKey)
    return out
}

/** A ref without its book's name ("1:1-2:3"), for rows that have no name of their own. */
private fun inBook(catalog: Catalog, book: Book, url: String, hebrew: Boolean): String {
    val r = Ref.parseOrNull(catalog, url) ?: return ""
    val (full, name) = if (hebrew) r.hebrew() to book.he else r.human() to book.title
    return full.removePrefix(name).trimStart(' ', ',')
}

@Composable
private fun AltRow(r: AltRowData, catalog: Catalog, book: Book, last: Int?, expanded: Boolean, open: (String) -> Unit, onExpand: () -> Unit) {
    val hebrew = hebrewUi()
    val n = r.node
    // the row whose ref holds the last read slot (a node without a ref: none)
    val here = remember(r.ref, last) {
        last != null && r.ref.isNotEmpty() &&
            Ref.parseOrNull(catalog, r.ref)?.let { runCatching { last in it.slots() }.getOrDefault(false) } == true
    }
    val names = stringArrayResource(R.array.aliyah_names)
    val heNames = remember { listOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שביעי") }
    val range = remember(r.ref, hebrew) { if (r.ref.isEmpty()) "" else inBook(catalog, book, r.ref, hebrew) }
    val title = when {
        r.aliyah != null -> names[r.aliyah]
        else -> pick(n.en, n.he).ifEmpty { range }
    }
    val second = when {
        r.aliyah != null -> if (hebrew) "" else heNames[r.aliyah]
        else -> other(n.en, n.he)
    }
    val canOpen = r.ref.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpen || r.expandable) { if (canOpen) open(r.ref) else onExpand() }
            .padding(start = (16 + 20 * r.depth).dp, end = if (r.expandable) 4.dp else 16.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = if (r.expandable) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge)
                if (second.isNotEmpty()) Text(second, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Rtl))
                if (here) Row { Spacer(Modifier.width(8.dp)); LastReadIcon() }
            }
            // the verses an aliyah covers; the range of a named node is in the reader
            if (r.aliyah != null && range.isNotEmpty()) Text(
                range,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (r.expandable) {
            val icon = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore
            val desc = stringResource(if (expanded) R.string.collapse else R.string.expand)
            // the row opens the node, so expanding needs its own button
            if (canOpen) IconButton(onClick = onExpand) { Icon(icon, contentDescription = desc) }
            else Icon(icon, contentDescription = desc, Modifier.padding(12.dp).size(24.dp))
        }
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
