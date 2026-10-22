package org.asimon.sefaria.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.PaddingValues
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.feature.connections.connectionList
import org.asimon.sefaria.feature.connections.CommentaryPager
import org.asimon.sefaria.feature.connections.ConnectionsActions
import org.asimon.sefaria.feature.connections.ConnectionsViewModel
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.ui.components.label
import androidx.compose.ui.res.stringResource
import org.asimon.sefaria.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.asimon.sefaria.data.Prefs

/** The selected segment's actions and connections (in the split or the side pane). */
@Composable
internal fun ConnectionsPanel(
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
            connectionList(conn, pinned, prefs, actions)
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
