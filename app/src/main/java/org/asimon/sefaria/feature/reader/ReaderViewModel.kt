package org.asimon.sefaria.feature.reader

import org.asimon.sefaria.data.repo.AppError
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.core.TrailStep
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.ref.Catalog
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.core.ref.RefException
import org.asimon.sefaria.core.ref.nextSection
import org.asimon.sefaria.core.ref.openSection
import org.asimon.sefaria.core.ref.prevSection
import org.asimon.sefaria.core.ref.segment
import org.asimon.sefaria.core.text.Hebrew
import org.asimon.sefaria.core.text.Html
import org.asimon.sefaria.core.text.RichText
import org.asimon.sefaria.data.Layout
import org.asimon.sefaria.data.Prefs
import org.asimon.sefaria.data.Settings
import org.asimon.sefaria.data.ThemeMode
import org.asimon.sefaria.data.TextFlow
import org.asimon.sefaria.data.repo.LibraryRepository
import org.asimon.sefaria.data.repo.Load
import org.asimon.sefaria.data.repo.SectionText
import org.asimon.sefaria.data.repo.TextRepository
import org.asimon.sefaria.data.repo.UserDataRepository
import javax.inject.Inject

sealed interface ReaderItem {
    val key: String
}

data class HeaderItem(override val key: String, val title: String) : ReaderItem

data class SegmentItem(
    override val key: String, // segment URL ref
    val ref: Ref,
    val label: String, // segment number ("5" / "ה")
    val he: RichText?,
    val en: RichText?,
    val sectionTitle: String,
    val highlighted: Boolean,
    val links: Int = 0, // connections touching this segment
) : ReaderItem

data class ReaderState(
    val bookTitle: String = "",
    val bookHe: String = "",
    val bookCategory: String = "", // top-level category (colour of the trail's current step)
    val items: List<ReaderItem> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
    val offline: Boolean = false,
    val loadingPrev: Boolean = false,
    val loadingNext: Boolean = false,
    val prevError: AppError? = null,
    val nextError: AppError? = null,
    val atStart: Boolean = false,
    val atEnd: Boolean = false,
    /** Set once after the first load: the item to scroll to. Consumed by [ReaderViewModel.initialScrollDone]. */
    val initialIndex: Int? = null,
)

/**
 * What changes the parsed items; layout and font size are applied at draw time.
 * [hebrewLabels] follows the interface language (refs and segment numbers in Hebrew).
 */
private data class DisplayOpts(val niqqud: Boolean, val cantillation: Boolean, val hebrewLabels: Boolean) {
    constructor(p: Prefs, hebrewUi: Boolean) : this(p.niqqud, p.cantillation, hebrewUi)
}

private class Chunk(val section: Ref, val text: SectionText) {
    val key get() = section.url()
}

/**
 * Continuous, bidirectional reading. Holds the loaded sections in order and extends the
 * window at either end as the list nears it. prev/next come from the book schema, so
 * paging works offline over cached sections.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ReaderViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val library: LibraryRepository,
    private val texts: TextRepository,
    private val user: UserDataRepository,
    private val settings: Settings,
) : ViewModel() {
    private val route = handle.toRoute<ReaderRoute>(ReaderRoute.typeMap)
    /** The source trail that led here, oldest first. */
    val trail: List<TrailStep> = route.trail
    val prefs: StateFlow<Prefs> = settings.prefs

    private val _state = MutableStateFlow(ReaderState(bookTitle = route.book))
    val state = _state.asStateFlow()

    private var book: Book? = null
    private val chunks = ArrayList<Chunk>() // mutated on the main thread only
    private var highlight: IntRange = IntRange.EMPTY // slots
    private val hebrewUi = MutableStateFlow(false)
    private var opts = DisplayOpts(settings.prefs.value, hebrewUi.value)
    private val parsed = HashMap<String, List<ReaderItem>>() // chunk key -> items for opts
    private var awaitingInitialScroll = true
    private val position = MutableStateFlow<Ref?>(null)

    init {
        open()
        viewModelScope.launch {
            combine(settings.prefs, hebrewUi, ::DisplayOpts).distinctUntilChanged().drop(1).collect {
                opts = it
                parsed.clear()
                publish()
            }
        }
        viewModelScope.launch {
            position.filterNotNull().debounce(800).collect { user.recordPosition(it, trail) }
        }
    }

    /** The interface language, from the screen (the view model outlives a language switch). */
    fun setHebrewUi(v: Boolean) {
        hebrewUi.value = v
    }

    fun open() = viewModelScope.launch {
        _state.value = ReaderState(bookTitle = route.book)
        val b = when (val r = library.book(route.book)) {
            is Load.Ok -> r.value
            is Load.Err -> return@launch fail(r.error)
        }
        book = b
        _state.update { it.copy(bookTitle = b.title, bookHe = b.he, bookCategory = b.dto.categories.firstOrNull().orEmpty()) }
        val target = Ref.parseOrNull(Catalog(listOf(b)), route.ref) ?: Ref(b, b.root)
        val section = try {
            target.openSection()
        } catch (e: RefException) {
            try { Ref(b, b.root).openSection() } catch (_: RefException) { return@launch fail(AppError.NoText) }
        }
        val focus: Int? = runCatching {
            if (target.isSegment || target.isRange) target.slots() else null
        }.getOrNull()?.let { slots ->
            if (route.highlight) highlight = if (slots.count() <= MAX_HIGHLIGHT) slots else slots.first..slots.first
            slots.first
        }
        when (val r = texts.section(section)) {
            is Load.Ok -> {
                chunks.clear()
                chunks.add(Chunk(section, r.value))
                _state.update { it.copy(offline = r.offline) }
            }
            is Load.Err -> return@launch fail(r.error)
        }
        val items = publish()
        val start = section.slots().first
        val idx = focus?.let { f -> items.indexOfFirst { it is SegmentItem && it.slot(start) == f } }?.takeIf { it >= 0 } ?: 0
        _state.update { it.copy(loading = false, initialIndex = idx) }
    }

    /** Called by the UI after it scrolled to [ReaderState.initialIndex]; paging starts then. */
    fun initialScrollDone() {
        awaitingInitialScroll = false
        _state.update { it.copy(initialIndex = null) }
    }

    /** The UI reports the visible item range; loads neighbours near the ends and tracks the position. */
    fun onVisible(first: Int, last: Int) {
        if (awaitingInitialScroll) return
        val items = _state.value.items
        if (items.isEmpty()) return
        if (first <= PREFETCH_ITEMS) loadPrev()
        if (last >= items.size - 1 - PREFETCH_ITEMS) loadNext()
        val top = items.drop(first.coerceAtLeast(0)).firstOrNull { it is SegmentItem } as? SegmentItem
        if (top != null) position.value = top.ref
    }

    fun loadNext() {
        val b = book ?: return
        val s = _state.value
        if (s.loadingNext || chunks.isEmpty()) return
        val next = b.nextSection(chunks.last().section) ?: return
        _state.update { it.copy(loadingNext = true, nextError = null) }
        viewModelScope.launch {
            when (val r = texts.section(next)) {
                is Load.Ok -> {
                    chunks.add(Chunk(next, r.value))
                    publish()
                    _state.update { it.copy(loadingNext = false, offline = it.offline || r.offline) }
                }
                is Load.Err -> _state.update { it.copy(loadingNext = false, nextError = r.error) }
            }
        }
    }

    fun loadPrev() {
        val b = book ?: return
        val s = _state.value
        if (s.loadingPrev || chunks.isEmpty()) return
        val prev = b.prevSection(chunks.first().section) ?: return
        _state.update { it.copy(loadingPrev = true, prevError = null) }
        viewModelScope.launch {
            when (val r = texts.section(prev)) {
                is Load.Ok -> {
                    chunks.add(0, Chunk(prev, r.value))
                    publish()
                    _state.update { it.copy(loadingPrev = false, offline = it.offline || r.offline) }
                }
                is Load.Err -> _state.update { it.copy(loadingPrev = false, prevError = r.error) }
            }
        }
    }

    fun bookmarked(ref: Ref): Flow<Boolean> = user.isBookmarked(ref.url())

    fun toggleBookmark(ref: Ref, preview: String, bookmarked: Boolean) = viewModelScope.launch {
        user.toggleBookmark(ref, preview, bookmarked)
    }

    fun setLayout(v: Layout) = viewModelScope.launch { settings.setLayout(v) }
    fun setFontScale(v: Float) = viewModelScope.launch { settings.setFontScale(v) }
    fun setNiqqud(v: Boolean) = viewModelScope.launch { settings.setNiqqud(v) }
    fun setCantillation(v: Boolean) = viewModelScope.launch { settings.setCantillation(v) }
    fun setTheme(v: ThemeMode) = viewModelScope.launch { settings.setTheme(v) }
    fun setFlow(v: TextFlow) = viewModelScope.launch { settings.setFlow(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { settings.setKeepScreenOn(v) }
    fun setSnapToVerse(v: Boolean) = viewModelScope.launch { settings.setSnapToVerse(v) }

    private fun fail(error: AppError) = _state.update { it.copy(loading = false, error = error) }

    private suspend fun publish(): List<ReaderItem> {
        val b = book ?: return emptyList()
        val snapshot = chunks.toList()
        if (snapshot.isEmpty()) return emptyList() // options changed before the first section arrived
        val o = opts
        val hl = highlight
        val items = ArrayList<ReaderItem>()
        for (c in snapshot) {
            items += parsed[c.key] ?: withContext(Dispatchers.Default) { itemsFor(c, o, hl) }.also {
                if (o == opts) parsed[c.key] = it
            }
        }
        _state.update {
            it.copy(
                items = items,
                atStart = b.prevSection(snapshot.first().section) == null,
                atEnd = b.nextSection(snapshot.last().section) == null,
            )
        }
        return items
    }

    private fun itemsFor(c: Chunk, o: DisplayOpts, hl: IntRange): List<ReaderItem> {
        val section = c.section
        val title = if (o.hebrewLabels) c.text.heRef.ifEmpty { section.hebrew() } else section.human()
        val start = section.slots().first
        val type = section.node.addressTypes.lastOrNull() ?: "Integer"
        val out = ArrayList<ReaderItem>(c.text.size + 1)
        out += HeaderItem("h:" + c.key, title)
        for (i in 0 until c.text.size) {
            val number = c.text.first + i
            val he = c.text.he.getOrNull(i)?.takeIf { it.isNotBlank() }
            val en = c.text.en.getOrNull(i)?.takeIf { it.isNotBlank() }
            if (he == null && en == null) continue // an empty slot
            val ref = section.segment(number)
            out += SegmentItem(
                key = ref.url(),
                ref = ref,
                label = if (o.hebrewLabels && type != "Talmud") Hebrew.gematria(number) else number.toString(),
                he = he?.let { Html.parse(it, stripNiqqud = !o.niqqud, stripCantillation = !o.cantillation) },
                en = en?.let { Html.parse(it) },
                sectionTitle = title,
                highlighted = (start + i) in hl, // whole-section responses: segment i is slot start + i
                links = c.text.linkCounts.getOrNull(i) ?: 0,
            )
        }
        return out
    }

    private fun SegmentItem.slot(sectionStart: Int) = runCatching { ref.slots().first }.getOrDefault(sectionStart)

    companion object {
        const val PREFETCH_ITEMS = 4
        const val MAX_HIGHLIGHT = 30
    }
}
