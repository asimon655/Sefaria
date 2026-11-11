package org.asimon.sefaria.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.asimon.sefaria.R
import org.asimon.sefaria.core.text.TitleMatch
import org.asimon.sefaria.data.remote.TocNodeDto
import org.asimon.sefaria.ui.components.pick

/**
 * The scope of a search as chips in a row, on the search tab (before searching) and above the
 * results: one that opens the picker ([onPick]), then each choice, removable on its own
 * ([onScope] gets the scope without it). "The [downloaded] books" is one choice, shown as one
 * chip, however many books it is.
 */
internal fun LazyListScope.scopeChips(
    tree: ScopeTree, scope: Scope, downloaded: Set<String>, onPick: () -> Unit, onScope: (Scope) -> Unit,
) {
    val allDownloaded = scope.books.size > 1 && scope.books == downloaded
    item(key = "scope") {
        FilterChip(
            selected = !scope.isAll,
            onClick = onPick,
            label = { Text(stringResource(if (scope.isAll) R.string.search_scope_all else R.string.search_scope_title)) },
            leadingIcon = { Icon(Icons.Outlined.FilterList, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
        )
    }
    items(scope.categories.sorted(), key = { "c:$it" }) { c ->
        ScopeChip(categoryName(tree.roots, c)) { onScope(scope.copy(categories = scope.categories - c)) }
    }
    if (allDownloaded) item(key = "downloaded") {
        ScopeChip(stringResource(R.string.search_scope_downloaded_n, scope.books.size)) { onScope(scope.copy(books = emptySet())) }
    }
    else items(scope.books.sorted(), key = { "b:$it" }) { t ->
        ScopeChip(bookName(tree.roots, t)) { onScope(scope.copy(books = scope.books - t)) }
    }
}

/**
 * The scope in a few words, for "search in …": its first choice's name, and how many more
 * there are. Null for the whole library.
 */
@Composable
internal fun scopeLabel(tree: ScopeTree, scope: Scope, downloaded: Set<String>): String? {
    if (scope.isAll) return null
    val allDownloaded = scope.books.size > 1 && scope.books == downloaded
    val first = when {
        scope.categories.isNotEmpty() -> categoryName(tree.roots, scope.categories.min())
        allDownloaded -> stringResource(R.string.search_scope_downloaded_n, scope.books.size)
        else -> bookName(tree.roots, scope.books.min())
    }
    val more = scope.categories.size + (if (allDownloaded) 1 else scope.books.size) - 1
    return if (more == 0) stringResource(R.string.search_in_one, first) else stringResource(R.string.search_in_more, first, more)
}

/** A chosen book or category; a tap removes it. */
@Composable
internal fun ScopeChip(name: String, onRemove: () -> Unit) = InputChip(
    selected = true,
    onClick = onRemove,
    label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_scope_remove, name), Modifier.size(InputChipDefaults.IconSize)) },
)

@Composable
internal fun categoryName(toc: List<TocNodeDto>, path: String): String {
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
internal fun bookName(toc: List<TocNodeDto>, title: String): String {
    fun find(nodes: List<TocNodeDto>): TocNodeDto? {
        for (n in nodes) (if (n.isBook && n.title == title) n else find(n.children))?.let { return it }
        return null
    }
    return find(toc)?.let { pick(it.title, it.he) } ?: title
}

/**
 * The scope picker, full height: what's chosen as chips at the top (tap to remove), shortcuts
 * for the whole library and the downloaded books, a filter, and the TOC as a tree. A category's
 * box covers all its books, shows "partly" when only some of what's under it is chosen, and its
 * row opens it; a book's row ticks it. The filter finds categories and books by either name,
 * niqqud and typos forgiven, each with the categories it sits in.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ScopeSheet(tree: ScopeTree, initial: Scope, downloaded: Set<String>, onDone: (Scope) -> Unit, onDismiss: () -> Unit) {
    var scope by remember { mutableStateOf(initial) }
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    var filter by rememberSaveable { mutableStateOf("") }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.search_scope_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { onDone(scope) }) { Text(stringResource(R.string.search_scope_done)) }
        }
        // what's chosen
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = scope.isAll, onClick = { scope = Scope() }, label = { Text(stringResource(R.string.search_scope_clear)) })
            if (downloaded.isNotEmpty()) {
                val dl = Scope(books = downloaded)
                FilterChip(selected = scope == dl, onClick = { scope = dl }, label = { Text(stringResource(R.string.search_scope_downloaded_n, downloaded.size)) })
            }
            if (scope != Scope(books = downloaded)) {
                for (c in scope.categories.sorted()) ScopeChip(categoryName(tree.roots, c)) { scope = tree.setCategory(scope, c, false) }
                for (b in scope.books.sorted()) ScopeChip(bookName(tree.roots, b)) { scope = tree.setBook(scope, b, false) }
            }
        }
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.search_scope_filter)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (filter.isEmpty()) null else {
                { IconButton(onClick = { filter = "" }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear)) } }
            },
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        val f = filter.trim()
        // the rows: the open part of the tree; with a filter, the best matches, flat
        val rows = remember(tree, open, f) {
            if (f.isNotEmpty()) TitleMatch.rank(f, tree.entries.distinctBy { it.key }, SCOPE_MATCHES) { e ->
                if (e.isBook) listOf(e.node.title, e.node.he) else listOf(e.node.en, e.node.he)
            }
            else tree.entries.filter { e -> e.trail.indices.all { i -> pathOf(e.trail, i) in open } }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(rows, key = { it.key + "|" + it.path }) { e ->
                ScopeRow(
                    e, tree, scope, flat = f.isNotEmpty(), expanded = e.path in open,
                    onToggle = { on -> scope = if (e.isBook) tree.setBook(scope, e.node.title, on) else tree.setCategory(scope, e.path, on) },
                    onExpand = { open = if (e.path in open) open - e.path else open + e.path },
                )
            }
            if (rows.isEmpty()) item {
                Text(
                    stringResource(R.string.search_scope_no_match, f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}

private const val SCOPE_MATCHES = 60

/** The path of the [i]th category of a trail: its names up to there. */
private fun pathOf(trail: List<TocNodeDto>, i: Int) = trail.take(i + 1).joinToString("/") { it.en }

@Composable
private fun ScopeRow(
    e: ScopeTree.Entry, tree: ScopeTree, scope: Scope, flat: Boolean, expanded: Boolean,
    onToggle: (Boolean) -> Unit, onExpand: () -> Unit,
) {
    val covered = tree.covered(scope, e.path, e.isBook)
    val on = covered || if (e.isBook) e.node.title in scope.books else e.path in scope.categories
    val state = when {
        on -> ToggleableState.On
        !e.isBook && tree.partly(scope, e.path) -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }
    val depth = if (flat) 0 else e.depth
    // a category's row opens it (unless filtering: then it ticks, as there is nothing to open)
    val rowClick = if (e.isBook || flat) ({ if (!covered) onToggle(!on) }) else onExpand
    Row(
        Modifier.fillMaxWidth().clickable(onClick = rowClick).padding(start = (8 + 20 * depth).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = state, enabled = !covered, onClick = { onToggle(state != ToggleableState.On) })
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                if (e.isBook) pick(e.node.title, e.node.he) else pick(e.node.en, e.node.he),
                style = if (e.isBook) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall,
                color = if (e.isBook) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            )
            // where a match sits, when the tree isn't there to show it
            if (flat && e.trail.isNotEmpty()) Text(
                e.trail.map { pick(it.en, it.he) }.joinToString(" › "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (!e.isBook) {
            Text(
                tree.count(e.path).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (!flat) Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

