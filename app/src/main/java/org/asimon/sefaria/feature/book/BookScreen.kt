package org.asimon.sefaria.feature.book

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.asimon.sefaria.data.repo.Load
import org.asimon.sefaria.data.repo.UserDataRepository
import org.asimon.sefaria.ui.components.BackButton
import org.asimon.sefaria.ui.components.ErrorMessage
import org.asimon.sefaria.ui.components.Loading
import javax.inject.Inject

data class BookState(val book: Book? = null, val error: String? = null)

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
            is Load.Err -> BookState(error = r.message)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(onOpen: (ReaderRoute) -> Unit, onBack: () -> Unit, vm: BookViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.book?.title ?: vm.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
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
                                Button(onClick = { onOpen(ReaderRoute(book.title, pos.ref, highlight = false)) }) { Text("Continue reading") }
                                OutlinedButton(onClick = { open(book.title) }) { Text("Start") }
                            } else {
                                Button(onClick = { open(book.title) }) { Text("Start reading") }
                            }
                        }
                    }
                    if (alt != null) item {
                        PrimaryTabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Contents") })
                            Tab(tab == 1, onClick = { tab = 1 }, text = { Text(if (alt.key == "Parasha") "Parashot" else alt.key) })
                        }
                    }
                    if (alt != null && tab == 1) {
                        altNodes(alt.value, prefs.hebrewLabels, open)
                    } else {
                        item { Structure(book, book.root, prefs.hebrewLabels, open, top = true) }
                    }
                    item { Versions(book) }
                }
            }
        }
    }
}

@Composable
private fun Header(book: Book) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(book.he, style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Rtl), modifier = Modifier.fillMaxWidth())
        if (book.dto.categories.isNotEmpty()) Text(
            book.dto.categories.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (book.dto.authors.isNotEmpty()) Text(book.dto.authors.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
        val desc = book.dto.desc.en.ifEmpty { book.dto.shortDesc.en }
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
            OutlinedButton(onClick = { open(nodeRef.url()) }, Modifier.padding(16.dp)) { Text("Open") }
            return
        }
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

private fun LazyListScope.altNodes(nodes: List<AltNodeDto>, hebrew: Boolean, open: (String) -> Unit) {
    items(nodes) { n ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = n.ref.isNotEmpty()) { open(n.ref) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(n.en, Modifier.weight(1f))
            if (n.he.isNotEmpty()) Text(n.he, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Rtl))
        }
        HorizontalDivider()
    }
}

@Composable
private fun Versions(book: Book) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text("Versions (${book.dto.versions.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (expanded) for (v in book.dto.versions.sortedWith(compareBy({ it.lang }, { !it.default }))) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    (if (v.default) "★ " else "") + v.title,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    listOf(v.lang, v.license).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
