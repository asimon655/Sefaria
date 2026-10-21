package org.asimon.sefaria.feature.search

import org.asimon.sefaria.ui.components.other
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.ui.components.text
import org.asimon.sefaria.data.repo.AppError
import androidx.compose.ui.res.stringResource
import org.asimon.sefaria.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.asimon.sefaria.BookRoute
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.SearchRoute
import org.asimon.sefaria.data.remote.CompletionDto
import org.asimon.sefaria.data.repo.SearchRepository
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import org.asimon.sefaria.data.repo.Load
import javax.inject.Inject

/**
 * [items] and [error] belong to [shownQuery], which may lag behind [query] while typing.
 * [typed]: the query was typed here, not filled in by the results page (only then do suggestions show).
 */
data class SearchState(
    val query: String = "",
    val typed: Boolean = false,
    val items: List<CompletionDto> = emptyList(),
    val error: AppError? = null,
    val shownQuery: String = "",
)

/** Completions for the search field: the query as a ref, then books by title (/v1/complete). */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(private val search: SearchRepository) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()
    private val queries = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queries.debounce(250).collectLatest { q -> complete(q.trim()) }
        }
    }

    fun onQuery(q: String) {
        _state.update { it.copy(query = q, typed = true) }
        queries.value = q
    }

    /** Shows [q] in the field without suggestions: the query whose results are on screen. */
    fun show(q: String) = _state.update { it.copy(query = q, typed = false) }

    private suspend fun complete(q: String) {
        if (q.isEmpty()) return _state.update { it.copy(items = emptyList(), error = null, shownQuery = "") }
        _state.update {
            when (val r = search.complete(q)) {
                is Load.Ok -> it.copy(items = r.value, error = null, shownQuery = q)
                is Load.Err -> it.copy(items = emptyList(), error = r.error, shownQuery = q)
            }
        }
    }
}

/**
 * The search field (Home, Library, and the results page with its query in it): a ref the
 * query parses as, matching books, and a row that searches the texts. Go opens the ref if
 * there is one, else the full-text results. [shown] is the query of the results on screen.
 */
@Composable
fun SearchBox(onOpen: (Any) -> Unit, modifier: Modifier = Modifier, shown: String? = null, vm: SearchViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(shown) { if (shown != null) vm.show(shown) }
    val q = s.query.trim()
    val focus = LocalFocusManager.current
    val open = { c: CompletionDto -> onOpen(if (c.kind == "book") BookRoute(c.book) else ReaderRoute(c.book, c.ref)) }
    val fullText = {
        focus.clearFocus()
        onOpen(SearchRoute(q))
    }
    Column(modifier) {
        OutlinedTextField(
            value = s.query,
            onValueChange = vm::onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (s.query.isEmpty()) null else {
                { IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear)) } }
            },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                val ref = s.items.firstOrNull()?.takeIf { it.kind == "ref" && s.shownQuery == q }
                if (ref != null) open(ref) else if (q.isNotEmpty()) fullText()
            }),
        )
        if (q.isEmpty() || !s.typed) return@Column
        if (s.shownQuery == q) for (c in s.items) {
            val title = pick(c.title, c.he)
            ListItem(
                headlineContent = { Text(if (c.kind == "ref") stringResource(R.string.go_to, title) else title) },
                supportingContent = other(c.title, c.he).takeIf { it.isNotEmpty() }?.let { o ->
                    { Text(o, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)) }
                },
                leadingContent = {
                    Icon(if (c.kind == "ref") Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null)
                },
                modifier = Modifier.clickable { open(c) },
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.search_texts, q)) },
            leadingContent = { Icon(Icons.Outlined.Search, contentDescription = null) },
            modifier = Modifier.clickable(onClick = fullText),
        )
        if (s.shownQuery == q && s.error != null && s.error !is AppError.Network) {
            Text(s.error!!.text(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp))
        }
    }
}
