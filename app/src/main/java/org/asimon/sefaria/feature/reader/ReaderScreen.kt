package org.asimon.sefaria.feature.reader

import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.flow.flowOf
import org.asimon.sefaria.core.ref.Ref
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.asimon.sefaria.core.TrailStep
import org.asimon.sefaria.ui.theme.categoryColor
import androidx.compose.material3.LinearProgressIndicator
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.PaddingValues
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.feature.connections.connectionList
import org.asimon.sefaria.feature.connections.CommentaryPager
import org.asimon.sefaria.feature.connections.ConnectionsActions
import org.asimon.sefaria.feature.connections.ConnectionsViewModel
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.ui.components.label
import org.asimon.sefaria.ui.components.hebrewUi
import org.asimon.sefaria.ui.components.text
import org.asimon.sefaria.data.repo.AppError
import androidx.compose.ui.res.stringResource
import org.asimon.sefaria.R
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.asimon.sefaria.core.text.RichText
import org.asimon.sefaria.data.Layout
import org.asimon.sefaria.data.Prefs
import org.asimon.sefaria.data.ThemeMode
import org.asimon.sefaria.data.TextFlow
import org.asimon.sefaria.ui.components.ThemePicker
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.asimon.sefaria.ui.components.BackButton
import org.asimon.sefaria.ui.components.ErrorMessage
import org.asimon.sefaria.ui.components.Loading

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onOpen: (ReaderRoute) -> Unit,
    onTrail: (Int) -> Unit, // go back to step i of the source trail
    vm: ReaderViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val storedPrefs by vm.prefs.collectAsStateWithLifecycle()
    // pinching shows the new size at once; it is stored when the fingers lift
    var liveScale by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(storedPrefs.fontScale) { liveScale = null }
    val prefs = liveScale?.let { storedPrefs.copy(fontScale = it) } ?: storedPrefs
    val baseScale by rememberUpdatedState(storedPrefs.fontScale) // the pinch handler is long-lived
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showDisplay by rememberSaveable { mutableStateOf(false) }
    // the segment whose connections are open; null = text only
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    // a range selection (long-press, then tap to extend): anchor and end segment keys
    var selAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    var selEnd by rememberSaveable { mutableStateOf<String?>(null) }
    val heUi = hebrewUi()
    LaunchedEffect(heUi) { vm.setHebrewUi(heUi) }
    // in a Hebrew interface, no English title while the book's Hebrew name is unknown
    val bookName = if (heUi) s.bookHe else s.bookTitle

    val view = LocalView.current
    DisposableEffect(prefs.keepScreenOn) {
        view.keepScreenOn = prefs.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val paragraphs = prefs.paragraphs()
    val rows = remember(s.items, paragraphs) { ReaderRows.build(s.items, paragraphs) }
    val paragraphLayouts = remember { mutableMapOf<String, ParagraphLayout>() } // row key -> layout
    val segIndex = remember(s.items) { s.items.withIndex().associate { (i, it) -> it.key to i } }
    val selection: Set<String> = remember(selAnchor, selEnd, s.items) {
        val a = segIndex[selAnchor] ?: return@remember emptySet()
        val e = segIndex[selEnd] ?: a
        (minOf(a, e)..maxOf(a, e)).mapNotNull { (s.items[it] as? SegmentItem)?.key }.toSet()
    }
    val selecting = selection.isNotEmpty()
    val currentRows by rememberUpdatedState(rows)
    val currentBookName by rememberUpdatedState(bookName)

    val title by remember {
        derivedStateOf {
            when (val row = currentRows.rows.getOrNull(list.firstVisibleItemIndex)) {
                is LineRow -> row.item.sectionTitle
                is ParagraphRow -> row.segments.first().sectionTitle
                is HeaderRow -> row.item.title
                null -> currentBookName
            }
        }
    }

    /** Scrolls so that a segment is at the top (inside its paragraph in paragraph flow). */
    suspend fun scrollTo(key: String, animate: Boolean) {
        val row = rows.rowOf(key) ?: return
        val offset = (rows.rows[row] as? ParagraphRow)?.let { paragraphLayouts[it.key]?.topOf(key) } ?: 0
        if (animate) list.animateScrollToItem(row, offset) else list.scrollToItem(row, offset)
    }

    // scroll to the target once, then let the view model page in both directions
    LaunchedEffect(s.initialIndex) {
        val idx = s.initialIndex ?: return@LaunchedEffect
        val key = s.items.getOrNull(idx)?.key ?: return@LaunchedEffect
        val row = rows.rowOf(key) ?: return@LaunchedEffect
        val rowKey = rows.rows[row].key
        list.scrollToItem(row)
        // page only once the target is laid out; a prepend before that would shift the
        // pending index into the previous section
        withTimeoutOrNull(2000) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == rowKey } }.first { it }
        }
        if (rows.rows[row] is ParagraphRow) scrollTo(key, animate = false)
        vm.initialScrollDone()
        vm.onVisible(rows.firstItemOf(list.firstVisibleItemIndex), rows.lastItemOf(list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: row))
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
            .collect { (first, last) -> vm.onVisible(currentRows.firstItemOf(first), currentRows.lastItemOf(last)) }
    }
    // While connections are open they follow the reading: when a scroll ends, the segment at
    // the top of the text becomes the selected one.
    LaunchedEffect(list, selectedKey != null) {
        if (selectedKey == null) return@LaunchedEffect
        snapshotFlow { list.isScrollInProgress }.drop(1).filter { !it }.collect {
            for (info in list.layoutInfo.visibleItemsInfo) {
                val key = when (val row = currentRows.rows.getOrNull(info.index)) {
                    is LineRow -> row.item.key.takeIf { info.offset >= -info.size / 3 }
                    is ParagraphRow -> paragraphLayouts[row.key]?.segmentAt(Offset(1f, maxOf(0, -info.offset) + 1f))?.key
                        ?: row.segments.first().key
                    else -> null
                } ?: continue
                selectedKey = key
                break
            }
        }
    }
    BackHandler(enabled = selecting) { selAnchor = null; selEnd = null }
    BackHandler(enabled = !selecting && selectedKey != null) { selectedKey = null }

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun selectedSegments() = s.items.filterIsInstance<SegmentItem>().filter { it.key in selection }

    /** The selection as one ref (a segment, or a range within one node), for bookmarking. */
    fun selectionRef(): Ref? {
        val segs = selectedSegments().takeIf { it.isNotEmpty() } ?: return null
        val a = segs.first().ref
        val b = segs.last().ref
        if (a == b) return a
        return if (a.node === b.node && a.sections.size == b.sections.size) Ref(a.book, a.node, a.sections, to = b.sections) else a
    }

    fun selectionPreview() = selectedSegments().firstOrNull()?.let { (it.he ?: it.en)?.plain }.orEmpty()

    fun selectionCitation(): String {
        val segs = s.items.filterIsInstance<SegmentItem>().filter { it.key in selection }
        if (segs.isEmpty()) return ""
        return buildString {
            for (seg in segs) {
                val body = when (prefs.layout) {
                    Layout.Hebrew -> (seg.he ?: seg.en)?.plain
                    Layout.English -> (seg.en ?: seg.he)?.plain
                    else -> listOfNotNull(seg.he?.plain, seg.en?.plain).joinToString("\n")
                }
                if (!body.isNullOrBlank()) append(body).append('\n')
            }
            append("— ").append(rangeLabel(segs.first(), segs.last(), heUi))
        }
    }

    Scaffold(
        topBar = {
            Column {
                if (selecting) TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selection.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selAnchor = null; selEnd = null }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        val range = selectionRef()
                        val marked by remember(range?.url()) { range?.let(vm::bookmarked) ?: flowOf(false) }.collectAsState(initial = false)
                        if (range != null) IconButton(onClick = { vm.toggleBookmark(range, selectionPreview(), marked) }) {
                            Icon(
                                if (marked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = stringResource(if (marked) R.string.remove_bookmark else R.string.bookmark),
                            )
                        }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(selectionCitation()))
                            selAnchor = null; selEnd = null
                        }) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy_with_citation)) }
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, selectionCitation())
                            context.startActivity(Intent.createChooser(send, null))
                        }) { Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.share)) }
                    },
                ) else CenterAlignedTopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { BackButton(onBack) },
                    actions = {
                        if (s.offline) Icon(Icons.Outlined.CloudOff, contentDescription = stringResource(R.string.offline_copy), Modifier.padding(end = 8.dp))
                        IconButton(onClick = { showDisplay = true }) { Icon(Icons.Outlined.TextFields, contentDescription = stringResource(R.string.display_settings)) }
                    },
                )
                if (vm.trail.isNotEmpty()) TrailBar(vm.trail, current = title, currentCategory = s.bookCategory, onStep = onTrail)
            }
        },
    ) { padding ->
        when {
            s.error != null -> ErrorMessage(s.error!!.text(), { vm.open() }, Modifier.padding(padding))
            s.loading -> Loading(Modifier.padding(padding))
            else -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val wide = maxWidth >= 600.dp
                val tap = { item: SegmentItem ->
                    if (selecting) {
                        selEnd = item.key
                    } else {
                        val opening = selectedKey == null
                        selectedKey = item.key
                        // on a phone the panel takes the lower part: keep the tapped segment in view
                        if (opening && !wide) scope.launch { scrollTo(item.key, animate = true) }
                    }
                }
                val longPress = { item: SegmentItem ->
                    selectedKey = null // the selection bar replaces the panel
                    selAnchor = item.key
                    selEnd = item.key
                }
                val focusColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                val selColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                val hlColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
                val mark = { item: SegmentItem ->
                    when {
                        item.key in selection -> selColor
                        item.key == selectedKey -> focusColor
                        item.highlighted -> hlColor
                        else -> null
                    }
                }
                val text = @Composable { m: Modifier ->
                    Box(m.pinchToScale(
                        onScale = { f -> liveScale = (baseScale * f).coerceIn(0.7f, 2f) },
                        onEnd = { liveScale?.let(vm::setFontScale) },
                    )) {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                            // No item above the first section: the first visible item must be text, so that
                            // LazyColumn keeps it in place when the previous section is prepended. Loading
                            // or failing to load that section shows as an overlay instead.
                            items(rows.rows, key = { it.key }, contentType = { it::class }) { row ->
                                when (row) {
                                    is HeaderRow -> SectionHeader(row.item.title)
                                    is LineRow -> SegmentRow(row.item, prefs, mark(row.item), onClick = { tap(row.item) }, onLongClick = { longPress(row.item) })
                                    is ParagraphRow -> ParagraphText(
                                        row, prefs,
                                        style = if (prefs.layout == Layout.Hebrew) heStyle(prefs.fontScale) else enStyle(prefs.fontScale),
                                        mark = mark, onTap = tap, onLongPress = longPress,
                                        onLayout = { paragraphLayouts[row.key] = it },
                                    )
                                }
                            }
                            item(key = "bottom") {
                                EdgeRow(loading = s.loadingNext, error = s.nextError, atEdge = s.atEnd, edgeText = stringResource(R.string.end_of_book, bookName), onRetry = vm::loadNext)
                            }
                        }
                        if (s.loadingPrev) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                        s.prevError?.let { e ->
                            TextButton(onClick = vm::loadPrev, modifier = Modifier.align(Alignment.TopCenter)) {
                                Text(e.text() + " · " + stringResource(R.string.retry))
                            }
                        }
                    }
                }
                val selected = s.items.firstOrNull { it.key == selectedKey } as? SegmentItem
                val openLink = { r: ReaderRoute ->
                    // the selected segment becomes the newest step of the trail
                    val from = selected?.let { TrailStep(it.ref.book.title, it.key, it.ref.human(), it.ref.hebrew(), s.bookCategory).encode() }
                    onOpen(r.copy(trail = vm.trail + listOfNotNull(from)))
                }
                when {
                    selected == null -> text(Modifier.fillMaxSize())
                    // side pane: tablets, phones in landscape, unfolded foldables
                    wide -> Row(Modifier.fillMaxSize()) {
                        text(Modifier.weight(1.4f).fillMaxHeight())
                        VerticalDivider()
                        ConnectionsPanel(selected, prefs, openLink, onClose = { selectedKey = null }, Modifier.weight(1f).fillMaxHeight())
                    }
                    // split: text above, connections below, with a handle to resize
                    else -> {
                        var split by rememberSaveable { mutableFloatStateOf(0.45f) } // connections' share of the height
                        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
                        Column(Modifier.fillMaxSize()) {
                            text(Modifier.weight(1f - split).fillMaxWidth())
                            ConnectionsPanel(
                                selected, prefs, openLink, onClose = { selectedKey = null }, Modifier.weight(split).fillMaxWidth(),
                                onDrag = { delta -> split = (split - delta / heightPx).coerceIn(0.2f, 0.85f) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDisplay) DisplaySheet(prefs, vm, onDismiss = { showDisplay = false })
}

/** The steps that led to this text, then the current place; tapping a step goes back to it. */
@Composable
private fun TrailBar(trail: List<String>, current: String, currentCategory: String, onStep: (Int) -> Unit) {
    val steps = remember(trail) { TrailStep.decodeAll(trail) }
    val state = rememberLazyListState()
    LaunchedEffect(steps.size) { state.scrollToItem(steps.size) } // show the newest steps
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).semantics { contentDescription = "" },
    ) {
        itemsIndexed(steps) { i, step ->
            AssistChip(
                onClick = { onStep(i) },
                label = { Text(pick(step.en, step.he), maxLines = 1) },
                leadingIcon = if (step.category.isEmpty()) null else {
                    { Box(Modifier.size(8.dp).background(categoryColor(step.category), RoundedCornerShape(4.dp))) }
                },
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp).size(16.dp),
            )
        }
        item {
            FilterChip(
                selected = true, onClick = {}, label = { Text(current, maxLines = 1) },
                leadingIcon = { Box(Modifier.size(8.dp).background(categoryColor(currentCategory), RoundedCornerShape(4.dp))) },
            )
        }
    }
}

/**
 * The top of the connections panel as one thin strip: the ref at the start, the grabber in
 * the middle (the whole strip drags to resize the split), close at the end. Copy, share and
 * bookmark live in the long-press selection bar.
 */
@Composable
private fun PanelStrip(label: String, onClose: () -> Unit, onDrag: ((Float) -> Unit)?) {
    val desc = stringResource(R.string.resize_connections)
    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                if (onDrag != null) Modifier.draggable(rememberDraggableState(onDrag), Orientation.Vertical).semantics { contentDescription = desc }
                else Modifier
            ),
    ) {
        Text(
            label, Modifier.align(Alignment.CenterStart).padding(start = 16.dp).fillMaxWidth(0.4f),
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (onDrag != null) Box(
            Modifier.align(Alignment.Center).size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
        )
        Box(
            Modifier.align(Alignment.CenterEnd).size(40.dp, 32.dp).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close_connections), Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EdgeRow(loading: Boolean, error: AppError?, atEdge: Boolean, edgeText: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(Modifier.width(24.dp))
            error != null -> TextButton(onClick = onRetry) { Text(error.text() + " · " + stringResource(R.string.retry)) }
            atEdge && edgeText.isNotEmpty() -> Text(edgeText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
    )
}

private fun heStyle(scale: Float) = TextStyle(
    fontFamily = FontFamily.Serif, fontSize = (21 * scale).sp, lineHeight = 1.55.em, textDirection = TextDirection.Rtl,
)

private fun enStyle(scale: Float) = TextStyle(
    fontFamily = FontFamily.Serif, fontSize = (17 * scale).sp, lineHeight = 1.5.em, textDirection = TextDirection.Ltr,
)

@Composable
private fun SegmentRow(item: SegmentItem, prefs: Prefs, mark: Color?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bg = mark ?: MaterialTheme.colorScheme.surface
    val he = item.he
    val en = item.en
    val refLabel = pick(item.ref.human(), item.ref.hebrew())
    val rtlRow = when (prefs.layout) {
        Layout.Hebrew, Layout.Stacked -> he != null
        Layout.English, Layout.SideBySide -> en == null
    }
    CompositionLocalProvider(LocalLayoutDirection provides if (rtlRow) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(bg)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics { contentDescription = refLabel }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // the number, with a notes marker under it, so text columns stay aligned
            Column(Modifier.widthIn(min = 28.dp).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (item.links > 0) Text(
                    item.links.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.semantics { contentDescription = "" },
                )
                if ((he?.notes.orEmpty() + en?.notes.orEmpty()).isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Outlined.Notes, contentDescription = stringResource(R.string.has_notes), Modifier.padding(top = 4.dp).width(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(6.dp))
            val scale = prefs.fontScale
            when (prefs.layout) {
                Layout.Hebrew -> SegText(he ?: en, if (he != null) heStyle(scale) else enStyle(scale), Modifier.weight(1f))
                Layout.English -> SegText(en ?: he, if (en != null) enStyle(scale) else heStyle(scale), Modifier.weight(1f))
                Layout.Stacked -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SegText(he, heStyle(scale))
                    SegText(en, enStyle(scale))
                }
                Layout.SideBySide -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (en != null) SegText(en, enStyle(scale), Modifier.weight(1f))
                    if (he != null) SegText(he, heStyle(scale), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SegText(text: RichText?, style: TextStyle, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(text.text, style = style, color = MaterialTheme.colorScheme.onSurface, modifier = modifier.fillMaxWidth())
}

/** The selected segment's actions, notes and connections (in the split or the side pane). */
@Composable
private fun ConnectionsPanel(
    item: SegmentItem,
    prefs: Prefs,
    onOpen: (ReaderRoute) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onDrag: ((Float) -> Unit)? = null, // the split's resize; null in the side pane
    cvm: ConnectionsViewModel = hiltViewModel(),
) {
    LaunchedEffect(item.key) { cvm.load(item.key, item.ref.book.title) }
    val conn by cvm.state.collectAsStateWithLifecycle()
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        PanelStrip(pick(item.ref.human(), item.ref.hebrew()), onClose, onDrag)
        val pinned by cvm.pinned.collectAsStateWithLifecycle()
        val actions = remember(cvm, onOpen) {
            ConnectionsActions(
                onFilter = cvm::setFilter, onFocus = cvm::setFocus, onPin = { cvm.togglePin(it) },
                ensureTexts = cvm::ensureTexts, retryTexts = cvm::retryTexts,
                onRetry = { cvm.load(item.key, item.ref.book.title) }, onOpen = onOpen,
            )
        }
        BackHandler(enabled = conn.focus != null) { cvm.setFocus(null) }
        if (conn.focus != null) {
            CommentaryPager(conn, pinned, prefs, actions, Modifier.fillMaxSize())
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            val notes = item.he?.notes.orEmpty() + item.en?.notes.orEmpty()
            if (notes.isNotEmpty()) {
                item(key = "notes") {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        for ((i, n) in notes.withIndex()) Text(
                            "${i + 1}. $n",
                            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
            }
            connectionList(conn, pinned, prefs, actions)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplaySheet(prefs: Prefs, vm: ReaderViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.layout), style = MaterialTheme.typography.titleSmall)
            val layouts = listOf(Layout.Hebrew to R.string.layout_hebrew, Layout.English to R.string.layout_english, Layout.Stacked to R.string.layout_both, Layout.SideBySide to R.string.layout_side)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                layouts.forEachIndexed { i, (l, label) ->
                    SegmentedButton(
                        selected = prefs.layout == l,
                        onClick = { vm.setLayout(l) },
                        shape = SegmentedButtonDefaults.itemShape(i, layouts.size),
                    ) { Text(stringResource(label), maxLines = 1) }
                }
            }
            Text(stringResource(R.string.font_size), style = MaterialTheme.typography.titleSmall)
            var scale by remember { mutableStateOf(prefs.fontScale) }
            Slider(value = scale, onValueChange = { scale = it }, onValueChangeFinished = { vm.setFontScale(scale) }, valueRange = 0.7f..2f)
            Text(stringResource(R.string.text_flow), style = MaterialTheme.typography.titleSmall)
            val flows = listOf(TextFlow.Lines to R.string.flow_lines, TextFlow.Paragraph to R.string.flow_paragraph)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                flows.forEachIndexed { i, (f, label) ->
                    SegmentedButton(
                        selected = prefs.flow == f,
                        onClick = { vm.setFlow(f) },
                        shape = SegmentedButtonDefaults.itemShape(i, flows.size),
                    ) { Text(stringResource(label), maxLines = 1) }
                }
            }
            SwitchRow(stringResource(R.string.niqqud), prefs.niqqud, vm::setNiqqud)
            SwitchRow(stringResource(R.string.cantillation), prefs.cantillation, vm::setCantillation)
            SwitchRow(stringResource(R.string.keep_screen_on), prefs.keepScreenOn, vm::setKeepScreenOn)
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
            ThemePicker(prefs.theme, vm::setTheme)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
