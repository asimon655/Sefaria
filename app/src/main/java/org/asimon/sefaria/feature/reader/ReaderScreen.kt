package org.asimon.sefaria.feature.reader

import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.activity.compose.BackHandler
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.asimon.sefaria.core.TrailStep
import org.asimon.sefaria.ui.theme.categoryColor
import androidx.compose.material3.LinearProgressIndicator
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.PaddingValues
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.ui.components.label
import org.asimon.sefaria.ui.components.hebrewUi
import org.asimon.sefaria.ui.components.text
import org.asimon.sefaria.data.repo.AppError
import androidx.compose.ui.res.stringResource
import org.asimon.sefaria.R
import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.ReadOnlyComposable
import org.asimon.sefaria.ui.LocalTextFonts
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
    // a range selection (long-press, then tap to extend)
    val sel = rememberReaderSelection()
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
    val scroll = rememberReaderScroll(list, rows)
    val selection: Set<String> = remember(sel.anchor, sel.end, s.items) { sel.keys(s.items) }
    val selecting = selection.isNotEmpty()
    val currentBookName by rememberUpdatedState(bookName)

    val title by remember {
        derivedStateOf {
            when (val row = scroll.rows.rows.getOrNull(list.firstVisibleItemIndex)) {
                is LineRow -> row.item.sectionTitle
                is ParagraphRow -> row.segments.first().sectionTitle
                is HeaderRow -> row.item.title
                null -> currentBookName
            }
        }
    }

    PageAsScrolled(scroll, s.initialIndex, s.items, onInitialDone = vm::initialScrollDone, onVisible = vm::onVisible)
    FollowReading(scroll, active = selectedKey != null, snap = prefs.snapToVerse, onSelect = { selectedKey = it })
    BackHandler(enabled = selecting) { sel.clear() }
    BackHandler(enabled = !selecting && selectedKey != null) { selectedKey = null }

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun selectedSegments() = s.items.filterIsInstance<SegmentItem>().filter { it.key in selection }
    fun citation() = selectionCitation(selectedSegments(), prefs.layout, heUi)

    Scaffold(
        topBar = {
            Column {
                if (selecting) TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selection.size)) },
                    navigationIcon = {
                        IconButton(onClick = { sel.clear() }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        val range = selectionRef(selectedSegments())
                        val marked by remember(range?.url()) { range?.let(vm::bookmarked) ?: flowOf(false) }.collectAsState(initial = false)
                        if (range != null) IconButton(onClick = { vm.toggleBookmark(range, selectionPreview(selectedSegments()), marked) }) {
                            Icon(
                                if (marked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = stringResource(if (marked) R.string.remove_bookmark else R.string.bookmark),
                            )
                        }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(citation()))
                            sel.clear()
                        }) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy_with_citation)) }
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, citation())
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
            s.error != null -> ErrorMessage(s.error!!, { vm.open() }, Modifier.padding(padding))
            s.loading -> Loading(Modifier.padding(padding))
            else -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val wide = maxWidth >= 600.dp
                val tap = { item: SegmentItem ->
                    if (selecting) {
                        sel.extendTo(item.key)
                    } else {
                        val opening = selectedKey == null
                        selectedKey = item.key
                        when {
                            // snapping: the tapped verse settles at the snap line, like a scroll would
                            prefs.snapToVerse -> scope.launch { scroll.scrollTo(item.key, animate = true, line = scroll.snapLine) }
                            // on a phone the panel takes the lower part: keep the tapped segment in view
                            opening && !wide -> scope.launch { scroll.scrollTo(item.key, animate = true) }
                        }
                    }
                }
                val longPress = { item: SegmentItem ->
                    selectedKey = null // the selection bar replaces the panel
                    sel.start(item.key)
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
                                        onLayout = { scroll.paragraphLayouts[row.key] = it },
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
                    val from = selected?.let { TrailStep(it.ref.book.title, it.key, it.ref.human(), it.ref.hebrew(), s.bookCategory) }
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
private fun TrailBar(steps: List<TrailStep>, current: String, currentCategory: String, onStep: (Int) -> Unit) {
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

@Composable
@ReadOnlyComposable
private fun heStyle(scale: Float) = TextStyle(
    fontFamily = LocalTextFonts.current.he, fontSize = (21 * scale).sp, lineHeight = 1.55.em, textDirection = TextDirection.Rtl,
)

@Composable
@ReadOnlyComposable
private fun enStyle(scale: Float) = TextStyle(
    fontFamily = LocalTextFonts.current.en, fontSize = (17 * scale).sp, lineHeight = 1.5.em, textDirection = TextDirection.Ltr,
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
            // the number, with the link count under it, so text columns stay aligned
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

