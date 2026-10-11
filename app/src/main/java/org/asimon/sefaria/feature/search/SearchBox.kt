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
import org.asimon.sefaria.data.remote.ResolveDto
import org.asimon.sefaria.data.repo.LibraryRepository
import org.asimon.sefaria.data.repo.Load
import javax.inject.Inject

/** [error] belongs to [errorQuery], which may lag behind [query] while typing. */
data class SearchState(val query: String = "", val result: ResolveDto? = null, val error: AppError? = null, val errorQuery: String = "")

/** v1 search is a ref/title jump via /v1/resolve; full-text search arrives in Phase 6. */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(private val library: LibraryRepository) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()
    private val queries = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queries.debounce(300).collectLatest { q -> resolve(q) }
        }
    }

    fun onQuery(q: String) {
        _state.update { it.copy(query = q) }
        queries.value = q
    }

    private suspend fun resolve(q: String) {
        if (q.isBlank()) return _state.update { it.copy(result = null, error = null) }
        _state.update {
            when (val r = library.resolve(q.trim())) {
                is Load.Ok -> it.copy(result = r.value, error = null)
                is Load.Err -> it.copy(result = null, error = r.error, errorQuery = q.trim())
            }
        }
    }
}

/**
 * The search field with its result (Home and Library). v1 jumps to a ref or a book; full-text
 * results arrive in Phase 6.
 */
@Composable
fun SearchBox(onOpen: (Any) -> Unit, modifier: Modifier = Modifier, vm: SearchViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val open = { r: ResolveDto -> onOpen(if (r.kind == "book") BookRoute(r.book) else ReaderRoute(r.book, r.ref)) }
    Column(modifier) {
        OutlinedTextField(
            value = s.query,
            onValueChange = vm::onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { s.result?.let(open) }),
        )
        if (s.query.isNotBlank()) s.result?.let { r ->
            ListItem(
                headlineContent = { Text(stringResource(R.string.go_to, pick(r.human.ifEmpty { r.ref }, r.heRef))) },
                supportingContent = other(r.human.ifEmpty { r.ref }, r.heRef).takeIf { it.isNotEmpty() }?.let { o ->
                    { Text(o, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)) }
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                modifier = Modifier.clickable { open(r) },
            )
        }
        if (s.query.isNotBlank()) s.error?.let { e ->
            val msg = if (e is AppError.NotFound) stringResource(R.string.no_match, s.errorQuery) else e.text()
            Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp))
        }
    }
}
