package org.asimon.sefaria.feature.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.asimon.sefaria.R
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.core.text.Html
import org.asimon.sefaria.core.text.RichText
import org.asimon.sefaria.data.Layout
import org.asimon.sefaria.data.Prefs
import org.asimon.sefaria.data.Settings
import org.asimon.sefaria.data.remote.LinkGroupDto
import org.asimon.sefaria.data.repo.AppError
import org.asimon.sefaria.data.repo.LinksRepository
import org.asimon.sefaria.data.repo.Load
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.ui.components.text
import javax.inject.Inject

/** A linked text, parsed for display. */
data class LinkedText(val book: String, val ref: String, val human: String, val heRef: String, val he: RichText?, val en: RichText?)

/** [offline]: the texts could not be fetched, so only the refs are listed. */
data class BookTexts(
    val loading: Boolean = true,
    val error: AppError? = null,
    val links: List<LinkedText> = emptyList(),
    val offline: Boolean = false,
)

data class ConnectionsState(
    val ref: String? = null,
    val book: String? = null, // the book of [ref]
    val loading: Boolean = false,
    val error: AppError? = null,
    val groups: List<LinkGroupDto> = emptyList(),
    val expanded: Map<String, BookTexts> = emptyMap(), // book title -> its linked texts
)

/**
 * Connections of the selected segment (APP_SPEC §4.4): groups and books with counts first;
 * a book's texts are fetched when it is expanded.
 */
@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val repo: LinksRepository,
    private val settings: Settings,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectionsState())
    val state = _state.asStateFlow()

    fun load(ref: String, book: String) {
        if (_state.value.ref == ref && _state.value.error == null) return
        _state.value = ConnectionsState(ref = ref, book = book, loading = true)
        viewModelScope.launch {
            val r = repo.links(ref, book)
            if (_state.value.ref != ref) return@launch
            _state.update {
                when (r) {
                    is Load.Ok -> it.copy(loading = false, groups = r.value.groups)
                    is Load.Err -> it.copy(loading = false, error = r.error)
                }
            }
        }
    }

    fun toggle(book: String) {
        val ref = _state.value.ref ?: return
        if (book in _state.value.expanded) {
            _state.update { it.copy(expanded = it.expanded - book) }
            return
        }
        _state.update { it.copy(expanded = it.expanded + (book to BookTexts())) }
        viewModelScope.launch {
            val p = settings.prefs.value
            val texts = when (val r = repo.withText(ref, book)) {
                is Load.Ok -> BookTexts(loading = false, links = withContext(Dispatchers.Default) {
                    r.value.groups.flatMap { g -> g.books }.filter { it.title == book }.flatMap { b ->
                        b.links.map { l ->
                            LinkedText(
                                b.title, l.ref, l.human.ifEmpty { l.ref.replace('_', ' ') }, l.heRef,
                                l.he.joinToString(" ").takeIf { it.isNotBlank() }
                                    ?.let { Html.parse(it, stripNiqqud = !p.niqqud, stripCantillation = !p.cantillation) },
                                l.en.joinToString(" ").takeIf { it.isNotBlank() }?.let { Html.parse(it) },
                            )
                        }
                    }
                })
                // offline: list the refs (from the connections without text) so they can still be opened
                is Load.Err -> if (r.error == AppError.Network) BookTexts(loading = false, offline = true, links = refsOnly(book))
                    else BookTexts(loading = false, error = r.error)
            }
            if (_state.value.ref == ref) _state.update { it.copy(expanded = it.expanded + (book to texts)) }
        }
    }

    private fun refsOnly(book: String): List<LinkedText> =
        _state.value.groups.flatMap { it.books }.filter { it.title == book }.flatMap { b ->
            b.links.map { l -> LinkedText(b.title, l.ref, l.human.ifEmpty { l.ref.replace('_', ' ') }, l.heRef, null, null) }
        }
}

/** The connections part of the segment sheet, as LazyColumn items. */
fun LazyListScope.connectionItems(
    s: ConnectionsState,
    prefs: Prefs,
    onToggle: (String) -> Unit,
    onRetry: () -> Unit,
    onOpen: (ReaderRoute) -> Unit,
) {
    item(key = "c:title") {
        Text(
            stringResource(R.string.connections),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    when {
        s.loading -> item(key = "c:loading") { Spinner() }
        s.error != null -> item(key = "c:error") {
            TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(s.error.text() + " · " + stringResource(R.string.retry))
            }
        }
        s.groups.isEmpty() -> item(key = "c:empty") {
            Text(
                stringResource(R.string.no_connections),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    for (g in s.groups) {
        item(key = "g:" + g.category) {
            Text(
                "${pick(g.en, g.he)} · ${g.count}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        for (b in g.books) {
            val texts = s.expanded[b.title]
            item(key = "b:" + b.title) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(b.title) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(pick(b.title, b.he), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("${b.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(if (texts != null) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, Modifier.size(20.dp))
                }
            }
            when {
                texts == null -> Unit
                texts.loading -> item(key = "bl:" + b.title) { Spinner() }
                texts.error != null -> item(key = "be:" + b.title) {
                    TextButton(onClick = { onToggle(b.title); onToggle(b.title) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(texts.error.text() + " · " + stringResource(R.string.retry))
                    }
                }
                else -> {
                    if (texts.offline) item(key = "bo:" + b.title) {
                        Text(
                            stringResource(R.string.connection_texts_offline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 28.dp, end = 16.dp, bottom = 4.dp),
                        )
                    }
                    for ((i, l) in texts.links.withIndex()) {
                        item(key = "t:" + b.title + ":" + i) { LinkedTextRow(l, prefs) { onOpen(ReaderRoute(l.book, l.ref)) } }
                    }
                }
            }
            item(key = "d:" + b.title) { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
        }
    }
}

@Composable
private fun LinkedTextRow(l: LinkedText, prefs: Prefs, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 28.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            pick(l.human, l.heRef),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        val scale = prefs.fontScale
        val showHe = prefs.layout != Layout.English || l.en == null
        val showEn = prefs.layout != Layout.Hebrew || l.he == null
        if (showHe && l.he != null) Text(
            l.he.text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Serif, fontSize = (18 * scale).sp, textDirection = TextDirection.Rtl,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (showEn && l.en != null) Text(
            l.en.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Serif, fontSize = (15 * scale).sp, textDirection = TextDirection.Ltr,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp))
    }
}
