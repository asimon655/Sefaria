package org.asimon.sefaria.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

data class SearchState(val query: String = "", val result: ResolveDto? = null, val message: String? = null)

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
        if (q.isBlank()) return _state.update { it.copy(result = null, message = null) }
        _state.update {
            when (val r = library.resolve(q.trim())) {
                is Load.Ok -> it.copy(result = r.value, message = null)
                is Load.Err -> it.copy(result = null, message = if (r.notFound) "No book or ref matches “$q”" else r.message)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpen: (Any) -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val open = { r: ResolveDto -> onOpen(if (r.kind == "book") BookRoute(r.book) else ReaderRoute(r.book, r.ref)) }
    Scaffold(topBar = { TopAppBar(title = { Text("Search") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = s.query,
                onValueChange = vm::onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Genesis 1:1 · Ber 2a · בראשית א") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { s.result?.let(open) }),
            )
            s.result?.let { r ->
                ListItem(
                    headlineContent = { Text("Go to ${r.human.ifEmpty { r.ref }}") },
                    supportingContent = { Text(r.heRef, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Rtl)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                    modifier = Modifier.clickable { open(r) },
                )
            }
            s.message?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            }
        }
    }
}
