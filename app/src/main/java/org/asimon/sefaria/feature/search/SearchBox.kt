package org.asimon.sefaria.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.asimon.sefaria.BookRoute
import org.asimon.sefaria.R
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.SearchRoute
import org.asimon.sefaria.core.debugAssert
import org.asimon.sefaria.data.AppError
import org.asimon.sefaria.data.Load
import org.asimon.sefaria.data.remote.CompletionDto
import org.asimon.sefaria.data.repo.CompletionRepository
import org.asimon.sefaria.ui.components.other
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.ui.components.text
import javax.inject.Inject

/**
 * [items] and [error] belong to [shownQuery], which may lag behind [query] while typing.
 * [settled]: the server answered for [shownQuery] (or couldn't be reached and the phone's own
 * list stands in); false while [items] are only the phone's matches, shown until it does.
 * [typed]: the query was typed here, not filled in by the results page (only then do suggestions show).
 */
data class SearchState(
    val query: String = "",
    val typed: Boolean = false,
    val items: List<CompletionDto> = emptyList(),
    val error: AppError? = null,
    val shownQuery: String = "",
    val settled: Boolean = false,
)

/**
 * Completions for the search field: the query as a ref, books by title, then places in books.
 * Each letter gets the books the phone can match by itself at once (the stored TOC's names),
 * and the server's fuller list (/v1/complete) replaces them when it arrives.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(private val completion: CompletionRepository) : ViewModel() {
    private companion object {
        /** How long Enter waits for the server to say whether the query is a ref. */
        const val RESOLVE_MS = 1500L

        /** How long typing must pause before the server is asked; the phone's own matches don't wait. */
        const val PAUSE_MS = 90L
    }

    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()
    private val queries = MutableStateFlow("")

    init {
        viewModelScope.launch { completion.warm() }
        viewModelScope.launch {
            queries.collectLatest { q -> suggest(q.trim()) }
        }
    }

    fun onQuery(q: String) {
        _state.update { it.copy(query = q, typed = true) }
        queries.value = q
    }

    /** Shows [q] in the field without suggestions: the query whose results are on screen. */
    fun show(q: String) = _state.update { it.copy(query = q, typed = false) }

    private var going: Job? = null

    /**
     * The keyboard's search key on [q]: opens it as a ref if it is one ([onRef]), else searches
     * the texts for it ([onText]). The completions say whether it is a ref, but a fast typist's
     * Enter comes before they do: then the server is asked this one thing, briefly, rather than
     * "Ber 2a" being searched for as words.
     */
    fun go(q: String, onRef: (CompletionDto) -> Unit, onText: () -> Unit) {
        val s = _state.value
        if (s.shownQuery == q && s.settled) {
            val ref = s.items.firstOrNull()?.takeIf { it.kind == "ref" }
            return if (ref != null) onRef(ref) else onText()
        }
        if (going?.isActive == true) return
        going = viewModelScope.launch {
            val ref = withTimeoutOrNull(RESOLVE_MS) { completion.ref(q) }
            if (ref != null) onRef(ref) else onText()
        }
    }

    /** Runs for each new query, the one before it cancelled. */
    private suspend fun suggest(q: String) {
        if (q.isEmpty()) return _state.update { it.copy(items = emptyList(), error = null, shownQuery = "", settled = false) }
        val now = _state.value
        if (now.shownQuery == q && now.settled) return // a space typed after it
        // asked before (a letter deleted, the same word again): no waiting at all
        completion.answered(q)?.let { a -> return _state.update { it.copy(items = a, error = null, shownQuery = q, settled = true) } }
        if (now.shownQuery != q) {
            val local = completion.local(q)
            // nothing of the phone's own (a ref, a place in a book): what's shown stays until the server answers
            if (local.isNotEmpty()) _state.update { it.copy(items = local, error = null, shownQuery = q, settled = false) }
        }
        delay(PAUSE_MS)
        val r = completion.complete(q)
        _state.update {
            when (r) {
                is Load.Ok -> it.copy(items = r.value, error = null, shownQuery = q, settled = true)
                is Load.Err -> it.copy(items = emptyList(), error = r.error, shownQuery = q, settled = true)
            }
        }
        debugAssert(_state.value.shownQuery == q) { "suggestions for \"$q\" were stored under \"${_state.value.shownQuery}\"" }
    }
}

/**
 * The search field (the search tab, and the results page with its query in it), and under it
 * while typing: a row that searches the texts, a ref the query parses as, matching books, and
 * named places in books (a siddur's Minchah, an aliyah). Go opens the ref if there is one, else
 * the full-text results. [shown] is the query of the results on screen; [focus] lets the caller
 * focus the field. [inset] is the room left at the sides of everything but [filters], a row
 * that stays under the field and scrolls edge to edge; [scopeLabel] says where the texts will
 * be searched when that isn't everywhere; [empty] shows while the field is empty, and is given
 * a way to fill it. The suggestions scroll within the room the box is given.
 */
@Composable
fun SearchBox(
    onOpen: (Any) -> Unit,
    modifier: Modifier = Modifier,
    shown: String? = null,
    focus: FocusRequester? = null,
    inset: Dp = 0.dp,
    scopeLabel: String? = null,
    filters: (@Composable () -> Unit)? = null,
    empty: (@Composable (fill: (String) -> Unit) -> Unit)? = null,
    vm: SearchViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(shown) { if (shown != null) vm.show(shown) }
    val q = s.query.trim()
    val focusManager = LocalFocusManager.current
    val open = { c: CompletionDto -> onOpen(if (c.kind == "book") BookRoute(c.book) else ReaderRoute(c.book, c.ref)) }
    val fullText = {
        focusManager.clearFocus()
        onOpen(SearchRoute(q))
    }
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        TextField(
            value = s.query,
            onValueChange = vm::onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = inset).let { m -> if (focus != null) m.focusRequester(focus) else m },
            singleLine = true,
            shape = CircleShape,
            // a filled pill with no line under it, as a search field looks elsewhere on the phone
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceContainerHigh,
                unfocusedContainerColor = colors.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (s.query.isEmpty()) null else {
                { IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear)) } }
            },
            placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (q.isNotEmpty()) vm.go(q, onRef = open, onText = fullText) }),
        )
        if (filters != null) Box(Modifier.padding(top = 8.dp)) { filters() }
        if (q.isEmpty()) {
            empty?.invoke(vm::onQuery)
            return@Column
        }
        if (!s.typed) return@Column
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = inset).padding(top = 8.dp, bottom = 16.dp)) {
            // the search itself comes first and is always there: the list under it changes as
            // letters are typed and answers arrive, and this row doesn't move
            Surface(onClick = fullText, shape = MaterialTheme.shapes.large, color = colors.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.onSecondaryContainer)
                    Column {
                        Text(
                            stringResource(R.string.search_texts, q),
                            style = MaterialTheme.typography.bodyLarge, color = colors.onSecondaryContainer,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (scopeLabel != null) Text(
                            scopeLabel,
                            style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // the list may still be the query's of a letter ago: it stays until this one's comes
            for (c in s.items) key(c.kind, c.book, c.ref) { Suggestion(c, q) { open(c) } }
            if (s.shownQuery == q && s.error != null && s.error !is AppError.Network) {
                Text(s.error!!.text(), color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp))
            }
        }
    }
}

/**
 * A suggestion: where it leads, with the letters typed marked in it. A place in a book shows
 * its own name, and the book and what's above it on a second line.
 */
@Composable
private fun Suggestion(c: CompletionDto, q: String, onClick: () -> Unit) {
    val title = pick(c.title, c.he)
    val section = c.kind == "section" && title.contains(PATH)
    val head = when {
        c.kind == "ref" -> AnnotatedString(stringResource(R.string.go_to, title))
        section -> marked(title.substringAfterLast(PATH), q)
        else -> marked(title, q)
    }
    val below = if (section) title.substringBeforeLast(PATH) else other(c.title, c.he)
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                when (c.kind) {
                    "ref" -> Icons.AutoMirrored.Filled.ArrowForward
                    "section" -> Icons.AutoMirrored.Outlined.List // a place in a book's table of contents
                    else -> Icons.AutoMirrored.Outlined.MenuBook
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(head, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (below.isNotEmpty()) Text(
                below,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What joins the names of a place's path in the server's titles ("Siddur Ashkenaz - Weekday - Minchah"). */
private const val PATH = " - "

/** [text] with the first place [q] appears in it in a heavier weight; as it is when [q] isn't there letter for letter. */
internal fun marked(text: String, q: String): AnnotatedString {
    val at = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
    if (at < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(fontWeight = FontWeight.Bold), at, at + q.length)
    }
}
