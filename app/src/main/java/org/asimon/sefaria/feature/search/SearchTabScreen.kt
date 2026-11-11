package org.asimon.sefaria.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.asimon.sefaria.R
import org.asimon.sefaria.SearchRoute
import org.asimon.sefaria.data.Load
import org.asimon.sefaria.data.repo.SearchRepository
import org.asimon.sefaria.data.repo.TocRepository
import javax.inject.Inject

/**
 * The search tab's scope: where the texts will be searched, chosen before searching. It is
 * kept as Scope.encode writes it, in as few names as say it, so it survives the process and
 * stays small.
 */
@HiltViewModel
class SearchTabViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    tocRepo: TocRepository,
    search: SearchRepository,
) : ViewModel() {
    private companion object {
        const val KEY_SCOPE = "scope"
    }

    private val _tree = MutableStateFlow(ScopeTree(emptyList()))

    /** The TOC as the scope picker reads it; empty until it is read, or when there is none. */
    val tree = _tree.asStateFlow()

    /** Titles of the downloaded books, for the picker's shortcut. */
    val downloaded = search.downloaded.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val scope = combine(handle.getStateFlow(KEY_SCOPE, ""), downloaded) { e, d -> Scope.decode(e, d) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Scope())

    init {
        viewModelScope.launch {
            val toc = tocRepo.toc()
            if (toc is Load.Ok) _tree.value = withContext(Dispatchers.Default) { ScopeTree(toc.value.tree) }
        }
    }

    fun setScope(s: Scope) {
        handle[KEY_SCOPE] = encode(s)
    }

    /** [s] for a route or saved state: "the downloaded books" as that, anything else compacted. */
    fun encode(s: Scope): String {
        val d = downloaded.value
        return (if (s.books == d) s else _tree.value.compact(s)).encode(d)
    }
}

/**
 * The search tab: the field at the top, under it where the texts will be searched (the whole
 * library until something is chosen), then things to try while the field is empty and its
 * suggestions while typing. Each new value of [focusRequests] (the tab tapped while it shows)
 * focuses the field and raises the keyboard, as in Google's apps; reaching the tab otherwise
 * (a first tap, a swipe) doesn't.
 */
@Composable
fun SearchTabScreen(onOpen: (Any) -> Unit, focusRequests: Int, vm: SearchTabViewModel = hiltViewModel()) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // requests already answered: coming back to the tab doesn't focus the field again
    var handled by rememberSaveable { mutableIntStateOf(focusRequests) }
    LaunchedEffect(focusRequests) {
        if (focusRequests == handled) return@LaunchedEffect
        handled = focusRequests
        runCatching { focus.requestFocus() } // the field may not be placed yet on the first frame
        keyboard?.show()
    }
    val tree by vm.tree.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val scope by vm.scope.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    Scaffold { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).imePadding().padding(top = 8.dp)) {
            SearchBox(
                onOpen = { r -> onOpen(if (r is SearchRoute) r.copy(scope = vm.encode(scope)) else r) },
                modifier = Modifier.fillMaxWidth(),
                focus = focus,
                inset = 16.dp,
                scopeLabel = scopeLabel(tree, scope, downloaded),
                filters = {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        scopeChips(tree, scope, downloaded, onPick = { picking = true }, onScope = vm::setScope)
                    }
                },
                empty = { fill ->
                    Examples { example ->
                        fill(example)
                        runCatching { focus.requestFocus() }
                    }
                },
            )
        }
    }
    if (picking) ScopeSheet(tree, scope, downloaded, onDone = { picking = false; vm.setScope(it) }, onDismiss = { picking = false })
}

/** What the field takes, as things to tap: each fills the field, and its suggestions follow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Examples(onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ExampleGroup(stringResource(R.string.search_examples_open_title), stringArrayResource(R.array.search_examples_open), onPick)
        ExampleGroup(stringResource(R.string.search_examples_words_title), stringArrayResource(R.array.search_examples_words), onPick)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExampleGroup(title: String, examples: Array<String>, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (e in examples) SuggestionChip(onClick = { onPick(e) }, label = { Text(e) })
        }
    }
}
