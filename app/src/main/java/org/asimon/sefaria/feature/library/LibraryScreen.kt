package org.asimon.sefaria.feature.library

import androidx.compose.foundation.clickable
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
    val error: String? = null,
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
            is Load.Err -> LibraryState(route, error = r.message)
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
    onSettings: () -> Unit,
    onBack: () -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.title?.en ?: "Library", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (s.route.names.isNotEmpty()) BackButton(onBack) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        val items = s.items
        when {
            s.error != null -> ErrorMessage(s.error!!, vm::load, Modifier.padding(padding))
            items == null -> Loading(Modifier.padding(padding))
            else -> LazyColumn(contentPadding = padding) {
                s.title?.let { t ->
                    val desc = t.desc.en.ifEmpty { t.short.en }
                    if (desc.isNotEmpty()) item {
                        Text(
                            desc.trim(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(items, key = { (if (it.isBook) "b:" + it.title else "c:" + it.en) }) { node ->
                    TocRow(node) { if (node.isBook) onBook(node.title) else onCategory(s.route.child(node.en)) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TocRow(node: TocNodeDto, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
    ) {
        val en = if (node.isBook) node.title else node.en
        Text(
            en,
            style = if (node.isBook) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            color = if (node.isBook) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        )
        if (node.he.isNotEmpty()) Text(
            node.he,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Rtl),
            modifier = Modifier.fillMaxWidth(),
        )
        if (node.short.en.isNotEmpty()) Text(
            node.short.en,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
