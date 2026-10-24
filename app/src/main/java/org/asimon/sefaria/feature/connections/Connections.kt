package org.asimon.sefaria.feature.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.asimon.sefaria.ui.LocalTextFonts
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
import org.asimon.sefaria.data.remote.LinkBookDto
import org.asimon.sefaria.data.remote.LinkGroupDto
import org.asimon.sefaria.data.AppError
import org.asimon.sefaria.data.repo.LinksRepository
import org.asimon.sefaria.data.Load
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.ui.components.Art
import org.asimon.sefaria.ui.components.EmptyState
import org.asimon.sefaria.ui.components.ErrorMessage
import org.asimon.sefaria.ui.components.text
import org.asimon.sefaria.ui.theme.categoryColor
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

/** A book in the panel with the group it belongs to (for its colour). */
data class PanelBook(val group: LinkGroupDto, val book: LinkBookDto)

data class ConnectionsState(
    val ref: String? = null,
    val book: String? = null, // the book of [ref]
    val loading: Boolean = false,
    val error: AppError? = null,
    val groups: List<LinkGroupDto> = emptyList(),
    val filter: String? = null, // the group category shown; null = all (kept from verse to verse)
    val focus: String? = null, // the book title open in the swipe view; null = the list
    /** The open book as last seen, kept while following the reading through verses it has nothing on. */
    val focusBook: PanelBook? = null,
    val texts: Map<String, BookTexts> = emptyMap(), // book title -> its linked texts for [ref]
) {
    /** The books the list shows, in group order. */
    val books: List<PanelBook>
        get() = groups.filter { filter == null || it.category == filter }.flatMap { g -> g.books.map { PanelBook(g, it) } }

    /** Pinned books present for this verse (in pin order), then the others: the swipe order. */
    fun ordered(pinned: List<String>): List<PanelBook> {
        val all = books
        val pins = pinned.flatMap { k -> all.filter { it.book.pinKey == k } }
        return pins + (all - pins.toSet())
    }
}

/**
 * Connections of the selected segment (APP_SPEC §4.7): category chips, pinned commentators
 * with their text, then every book with its count. Tapping a book opens it full-panel, where
 * swiping moves to the next book.
 */
@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val repo: LinksRepository,
    private val settings: Settings,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectionsState())
    val state = _state.asStateFlow()
    val pinned = settings.prefs.map { it.pinned }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.prefs.value.pinned)

    fun load(ref: String, book: String) {
        if (_state.value.ref == ref && _state.value.error == null) return
        val keep = _state.value
        // keep the old groups on screen until the new ones arrive, so following the reading doesn't flash
        _state.value = ConnectionsState(
            ref = ref, book = book, loading = true, filter = keep.filter, focus = keep.focus, focusBook = keep.focusBook, groups = keep.groups,
        )
        viewModelScope.launch {
            val r = repo.links(ref, book)
            if (_state.value.ref != ref) return@launch
            _state.update {
                when (r) {
                    is Load.Ok -> {
                        val groups = r.value.groups
                        val books = groups.flatMap { g -> g.books.map { b -> PanelBook(g, b) } }
                        // The open book stays open from verse to verse: the same title, else the same
                        // commentator in this book (Rashi on Genesis → Rashi on Exodus), else none here
                        // (the panel says so, and the book comes back on the next verse that has it).
                        val same = it.focus?.let { f ->
                            books.firstOrNull { b -> b.book.title == f }
                                ?: books.firstOrNull { b -> b.book.pinKey == it.focusBook?.book?.pinKey }
                        }
                        it.copy(
                            loading = false, groups = groups,
                            filter = it.filter?.takeIf { f -> groups.any { g -> g.category == f } }
                                // a filter that would hide the open book's group gives way
                                ?.takeIf { f -> same == null || same.group.category == f },
                            focus = same?.book?.title ?: it.focus,
                            focusBook = same ?: it.focusBook,
                        )
                    }
                    is Load.Err -> it.copy(loading = false, error = r.error, groups = emptyList())
                }
            }
        }
    }

    fun setFilter(category: String?) = _state.update { it.copy(filter = category) }
    fun setFocus(book: String?) = _state.update { s ->
        s.copy(focus = book, focusBook = book?.let { t -> s.groups.flatMap { g -> g.books.map { PanelBook(g, it) } }.firstOrNull { it.book.title == t } })
    }
    fun togglePin(key: String) = viewModelScope.launch { settings.togglePin(key) }

    /** Fetches one book's texts for the current ref, once. */
    fun ensureTexts(book: String) {
        val ref = _state.value.ref ?: return
        val source = _state.value.book ?: return
        if (book in _state.value.texts) return
        _state.update { it.copy(texts = it.texts + (book to BookTexts())) }
        viewModelScope.launch {
            val p = settings.prefs.value
            val texts = when (val r = repo.withText(ref, source, book)) {
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
            if (_state.value.ref == ref) _state.update { it.copy(texts = it.texts + (book to texts)) }
        }
    }

    fun retryTexts(book: String) {
        _state.update { it.copy(texts = it.texts - book) }
        ensureTexts(book)
    }

    private fun refsOnly(book: String): List<LinkedText> =
        _state.value.groups.flatMap { it.books }.filter { it.title == book }.flatMap { b ->
            b.links.map { l -> LinkedText(b.title, l.ref, l.human.ifEmpty { l.ref.replace('_', ' ') }, l.heRef, null, null) }
        }
}

/** Callbacks of the panel's content. */
class ConnectionsActions(
    val onFilter: (String?) -> Unit,
    val onFocus: (String?) -> Unit,
    val onPin: (String) -> Unit,
    val ensureTexts: (String) -> Unit,
    val retryTexts: (String) -> Unit,
    val onRetry: () -> Unit,
    val onOpen: (ReaderRoute) -> Unit,
)

/** The list: chips, pinned commentators with their text, then all books (LazyColumn items). */
fun LazyListScope.connectionList(s: ConnectionsState, pinned: List<String>, prefs: Prefs, a: ConnectionsActions) {
    when {
        s.loading && s.groups.isEmpty() -> item(key = "c:loading") { Spinner() }
        s.error != null -> item(key = "c:error") { ErrorMessage(s.error, a.onRetry, compact = true) }
        s.groups.isEmpty() -> item(key = "c:empty") {
            EmptyState(Art.BrokenLink, stringResource(R.string.no_connections), stringResource(R.string.no_connections_body), compact = true)
        }
        else -> {
            item(key = "c:chips") { CategoryChips(s, a.onFilter) }
            val ordered = s.ordered(pinned)
            var lastGroup: String? = null
            for (pb in ordered) {
                val isPinned = pb.book.pinKey in pinned
                if (isPinned) {
                    item(key = "pin:" + pb.book.title) {
                        LaunchedEffect(s.ref, pb.book.title) { a.ensureTexts(pb.book.title) }
                        PinnedBook(pb, s.texts[pb.book.title], prefs, a)
                    }
                    continue
                }
                if (pb.group.category != lastGroup) {
                    lastGroup = pb.group.category
                    item(key = "g:" + pb.group.category) { GroupHeader(pb.group) }
                }
                item(key = "b:" + pb.book.title) {
                    BookRow(pb, onPin = { a.onPin(pb.book.pinKey) }) { a.onFocus(pb.book.title) }
                }
            }
        }
    }
}

@Composable
private fun CategoryChips(s: ConnectionsState, onFilter: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        item {
            FilterChip(
                selected = s.filter == null, onClick = { onFilter(null) },
                label = { Text(stringResource(R.string.all_categories) + " " + s.groups.sumOf { it.count }) },
            )
        }
        items(s.groups, key = { it.category }) { g ->
            FilterChip(
                selected = s.filter == g.category,
                onClick = { onFilter(if (s.filter == g.category) null else g.category) },
                label = { Text(pick(g.en, g.he) + " " + g.count) },
                leadingIcon = { Box(Modifier.size(8.dp).background(categoryColor(g.category), RoundedCornerShape(4.dp))) },
            )
        }
    }
}

@Composable
private fun ColorMark(category: String) {
    Box(Modifier.size(width = 3.dp, height = 14.dp).background(categoryColor(category)))
}

@Composable
private fun GroupHeader(g: LinkGroupDto) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ColorMark(g.category)
        Text(
            "${pick(g.en, g.he)} · ${g.count}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun PinButton(pinned: Boolean, onPin: () -> Unit) {
    IconButton(onClick = onPin) {
        Icon(
            if (pinned) Icons.Filled.Star else Icons.Outlined.StarOutline,
            contentDescription = stringResource(if (pinned) R.string.unpin else R.string.pin),
            tint = if (pinned) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BookRow(pb: PanelBook, onPin: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(pick(pb.book.title, pb.book.he), Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        Text("${pb.book.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PinButton(false, onPin)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PinnedBook(pb: PanelBook, texts: BookTexts?, prefs: Prefs, a: ConnectionsActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { a.onFocus(pb.book.title) }.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorMark(pb.group.category)
            Text(
                pick(pb.book.title, pb.book.he), Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            PinButton(true) { a.onPin(pb.book.pinKey) }
        }
        TextsOfBook(pb.book.title, texts, prefs, a, startPad = 12.dp)
    }
}

/** A book's texts for the verse, or its loading/error/offline state (a book has few texts). */
@Composable
private fun TextsOfBook(book: String, texts: BookTexts?, prefs: Prefs, a: ConnectionsActions, startPad: Dp) {
    when {
        texts == null || texts.loading -> Spinner()
        texts.error != null -> TextButton(onClick = { a.retryTexts(book) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(texts.error.text() + " · " + stringResource(R.string.retry))
        }
        else -> Column {
            if (texts.offline) Text(
                stringResource(R.string.connection_texts_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = startPad, end = 16.dp, bottom = 4.dp),
            )
            for (l in texts.links) LinkedTextRow(l, prefs, startPad) { a.onOpen(ReaderRoute(l.book, l.ref)) }
        }
    }
}

/**
 * One book full-panel, opened from the list; swipe sideways for the next or previous book
 * in the list's order (pinned first, within the category filter), so you choose where to
 * start and never swipe through books you don't want just to get there.
 */
@Composable
fun CommentaryPager(s: ConnectionsState, pinned: List<String>, prefs: Prefs, a: ConnectionsActions, modifier: Modifier = Modifier) {
    val books = s.ordered(pinned)
    if (books.none { it.book.title == s.focus }) {
        // following the reading onto a verse the open book has nothing on: say so, and stay
        val fb = s.focusBook
        Column(modifier) {
            PagerHeader(fb, label = null, pinned = fb != null && fb.book.pinKey in pinned, a)
            HorizontalDivider()
            if (s.loading) Spinner()
            else LazyColumn(Modifier.fillMaxSize()) {
                item {
                    val name = fb?.let { pick(it.book.title, it.book.he) } ?: s.focus.orEmpty()
                    EmptyState(Art.BrokenLink, stringResource(R.string.no_book_here, name), stringResource(R.string.no_book_here_body), compact = true)
                }
            }
        }
        return
    }
    val start = books.indexOfFirst { it.book.title == s.focus }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { books.size }
    // following the reading can bring a verse whose list is different: stay on the same book
    LaunchedEffect(s.ref, books.size) { if (pager.currentPage != start) pager.scrollToPage(start) }
    LaunchedEffect(pager, books) {
        snapshotFlow { pager.settledPage }.collect { p ->
            books.getOrNull(p)?.let { if (it.book.title != s.focus) a.onFocus(it.book.title) }
        }
    }
    val current = books.getOrNull(pager.currentPage) ?: books[0]
    Column(modifier) {
        PagerHeader(current, stringResource(R.string.n_of_m, pager.currentPage + 1, books.size), current.book.pinKey in pinned, a)
        HorizontalDivider()
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { books[it].book.title }) { page ->
            val pb = books[page]
            LaunchedEffect(s.ref, pb.book.title) { a.ensureTexts(pb.book.title) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { TextsOfBook(pb.book.title, s.texts[pb.book.title], prefs, a, startPad = 16.dp) }
            }
        }
    }
}

/** Back to the list, the book's colour and name, its place in the swipe order, and its pin. */
@Composable
private fun PagerHeader(book: PanelBook?, label: String?, pinned: Boolean, a: ConnectionsActions) {
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { a.onFocus(null) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_to_list))
        }
        if (book != null) ColorMark(book.group.category)
        Text(
            book?.let { pick(it.book.title, it.book.he) }.orEmpty(), Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (book != null) PinButton(pinned) { a.onPin(book.book.pinKey) }
    }
}

@Composable
private fun LinkedTextRow(l: LinkedText, prefs: Prefs, startPad: Dp, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = startPad, end = 16.dp, top = 6.dp, bottom = 10.dp),
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
                fontFamily = LocalTextFonts.current.he, fontSize = (18 * scale).sp, textDirection = TextDirection.Rtl,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (showEn && l.en != null) Text(
            l.en.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = LocalTextFonts.current.en, fontSize = (15 * scale).sp, textDirection = TextDirection.Ltr,
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
