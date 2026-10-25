package org.asimon.sefaria

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Which way a finished horizontal swipe moves through the tabs: +1 to the next tab, -1 to the
 * previous, 0 to stay. [dx] is how far the finger moved (negative: to the left) and [velocity]
 * how fast it was moving at the end (px/s). A swipe counts once it travels [distance], or a
 * third of it when flung faster than [fling]. In a right-to-left layout the tabs run the other
 * way, so the swipe does too.
 */
fun swipeStep(dx: Float, velocity: Float, distance: Float, fling: Float, rtl: Boolean): Int {
    val far = abs(dx) >= distance || (abs(dx) >= distance / 3 && abs(velocity) >= fling && velocity * dx > 0)
    if (!far) return 0
    val towardNext = dx < 0 // the finger pulls the next tab in from the right
    return (if (towardNext) 1 else -1) * (if (rtl) -1 else 1)
}

/**
 * Switches tabs ([onStep] with +1 or -1) on a horizontal swipe. A swipe on content that doesn't
 * scroll sideways is a drag here; one on content that does (a pager, a row of chips) goes to that
 * content first, and only what it can't use (past its last page) counts here.
 */
@Composable
fun Modifier.tabSwipe(enabled: Boolean, onStep: (Int) -> Unit): Modifier {
    val density = LocalDensity.current
    val distance = with(density) { 96.dp.toPx() }
    val fling = with(density) { 600.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val step = rememberUpdatedState(onStep)
    val swipe = remember { FloatArray(1) } // the current swipe's travel
    fun finish(velocity: Float) {
        val s = swipeStep(swipe[0], velocity, distance, fling, rtl)
        swipe[0] = 0f
        if (s != 0) step.value(s)
    }
    val leftovers = remember(rtl) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // only a sideways swipe the content couldn't use: vertical lists pass none
                if (source == NestedScrollSource.UserInput && abs(available.x) > abs(available.y)) swipe[0] += available.x
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                finish(available.x)
                return Velocity.Zero
            }
        }
    }
    val drag = rememberDraggableState { swipe[0] += it }
    if (!enabled) return this
    return this
        .nestedScroll(leftovers)
        .draggable(drag, Orientation.Horizontal, onDragStopped = { v -> finish(v) })
}
