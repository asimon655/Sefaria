package org.asimon.sefaria.feature.library

import org.asimon.sefaria.ui.components.screenAlign
import org.asimon.sefaria.ui.components.text
import org.asimon.sefaria.ui.components.other
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.ui.components.prose
import org.asimon.sefaria.ui.theme.categoryColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import org.asimon.sefaria.feature.search.SearchBox
import org.asimon.sefaria.data.repo.AppError
import androidx.compose.ui.res.stringResource
import org.asimon.sefaria.R
import androidx.compose.foundation.clickable
import org.asimon.sefaria.feature.downloads.GroupDownload
import org.asimon.sefaria.feature.downloads.AskNotificationsOnDownload
import org.asimon.sefaria.feature.downloads.DownloadsViewModel
import org.asimon.sefaria.feature.downloads.BookRowDownload
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.asimon.sefaria.LibraryRoute
import org.asimon.sefaria.data.remote.TocNodeDto
import org.asimon.sefaria.data.repo.LibraryRepository
import org.asimon.sefaria.data.repo.Load
import org.asimon.sefaria.ui.components.BackButton
import org.asimon.sefaria.ui.components.ErrorMessage
import org.asimon.sefaria.ui.components.Loading
import javax.inject.Inject

data class LibraryState(
    val route: LibraryRoute,
    val title: TocNodeDto? = null, // the category shown; null at the top level
    val items: List<TocNodeDto>? = null,
    val error: AppError? = null,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val library: LibraryRepository,
) : ViewModel() {
    private val route = handle.toRoute<LibraryRoute>()
    private val _state = MutableStateFlow(LibraryState(route))
    val state = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = LibraryState(route)
        _state.value = when (val r = library.toc()) {
            is Load.Err -> LibraryState(route, error = r.error)
            is Load.Ok -> {
                var nodes = r.value.tree
                var title: TocNodeDto? = null
                for (name in route.names) {
                    title = nodes.firstOrNull { !it.isBook && it.en == name }
                    nodes = title?.children.orEmpty()
                }
                LibraryState(route, title, nodes)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onCategory: (LibraryRoute) -> Unit,
    onBook: (String) -> Unit,
    onOpen: (Any) -> Unit, // a search result
    onBack: () -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
    downloads: DownloadsViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    AskNotificationsOnDownload(downloads)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.title?.let { pick(it.en, it.he) } ?: stringResource(R.string.tab_library), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (s.route.names.isNotEmpty()) BackButton(onBack) },
                // the whole level: the library at the top, else this category
                actions = {
                    s.items?.let { items ->
                        val name = s.title?.let { pick(it.en, it.he) } ?: stringResource(R.string.whole_library)
                        GroupDownload(name, items.flatMap(::booksUnder), downloads, asButton = true)
                    }
                },
            )
        },
    ) { padding ->
        val items = s.items
        when {
            s.error != null -> ErrorMessage(s.error!!, vm::load, Modifier.padding(padding))
            items == null -> Loading(Modifier.padding(padding))
            else -> {
              val desc = s.title?.let { t -> prose(t.desc.en, t.desc.he).ifEmpty { prose(t.short.en, t.short.he) } }.orEmpty()
              LazyColumn(contentPadding = padding) {
                if (s.route.names.isEmpty()) item(key = "search") {
                    SearchBox(onOpen, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                if (desc.isNotEmpty()) {
                    item {
                        Text(
                            desc.trim(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(items, key = { (if (it.isBook) "b:" + it.title else "c:" + it.en) }) { node ->
                    TocRow(node, category = s.route.names.firstOrNull() ?: node.en, onClick = { if (node.isBook) onBook(node.title) else onCategory(s.route.child(node.en)) }) {
                        if (node.isBook) BookRowDownload(node.title, node.he, downloads)
                        else GroupDownload(pick(node.en, node.he), booksUnder(node), downloads)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = RowMargin))
                }
              }
            }
        }
    }
}

/** Space between the screen's edges and the rows, so their colour bars aren't flush with the edge. */
private val RowMargin = 12.dp

/** The titles of every book under [node] (itself, if it is a book). */
private fun booksUnder(node: TocNodeDto): List<String> = if (node.isBook) listOf(node.title) else node.children.flatMap(::booksUnder)

@Composable
private fun TocRow(node: TocNodeDto, category: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    // the top-level category's colour, as a thin bar on the reading side
    val bar = categoryColor(category)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = RowMargin, vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .drawBehind { drawRect(bar, size = Size(4.dp.toPx(), size.height), topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - 4.dp.toPx() else 0f, 0f)) }
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        val en = if (node.isBook) node.title else node.en
        Text(
            pick(en, node.he),
            style = if (node.isBook) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            color = if (node.isBook) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        )
        val second = other(en, node.he)
        if (second.isNotEmpty()) Text(
            second,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = screenAlign(),
            modifier = Modifier.fillMaxWidth(),
        )
        val short = prose(node.short.en, node.short.he)
        if (short.isNotEmpty()) Text(
            short,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
      }
      trailing()
    }
}
