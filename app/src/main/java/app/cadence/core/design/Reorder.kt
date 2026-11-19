package app.cadence.core.design

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Drag-to-reorder for fixed-height rows in a lazy list. Start a drag from a whole row (long-press,
 * so it never fights scrolling) or instantly from a drag handle. The local order updates live and
 * [onMove] is called once, on release, with the original and final positions.
 */
@Stable
class ReorderState<T>(private val rowPx: Float, private val haptic: (HapticFeedbackType) -> Unit) {
    var order by mutableStateOf<List<T>>(emptyList())
        private set
    var dragging by mutableIntStateOf(-1)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    private var from = -1
    private var source: List<T> = emptyList()

    /** Adopt the latest data unless a drag is in progress. */
    fun sync(items: List<T>) {
        source = items
        if (dragging < 0 && order != items) order = items
    }

    internal fun start(item: T) {
        dragging = order.indexOf(item)
        from = dragging
        offset = 0f
        haptic(HapticFeedbackType.GestureThresholdActivate)
    }

    internal fun drag(dy: Float) {
        if (dragging < 0) return
        offset += dy
        val cur = dragging
        if (offset > rowPx / 2 && cur < order.lastIndex) {
            order = order.toMutableList().apply { add(cur + 1, removeAt(cur)) }
            dragging = cur + 1; offset -= rowPx; haptic(HapticFeedbackType.SegmentTick)
        } else if (offset < -rowPx / 2 && cur > 0) {
            order = order.toMutableList().apply { add(cur - 1, removeAt(cur)) }
            dragging = cur - 1; offset += rowPx; haptic(HapticFeedbackType.SegmentTick)
        }
    }

    internal fun end(onMove: (from: Int, to: Int) -> Unit) {
        val to = dragging
        if (from >= 0 && to >= 0 && from != to) onMove(from, to)
        dragging = -1; offset = 0f
        haptic(HapticFeedbackType.GestureEnd)
    }

    internal fun cancel() {
        dragging = -1; offset = 0f; order = source
    }

    fun isDragging(index: Int) = index == dragging
}

@Composable
fun <T> rememberReorderState(rowHeight: Dp): ReorderState<T> {
    val px = with(LocalDensity.current) { rowHeight.toPx() }
    val haptic by rememberUpdatedState(rememberHaptic())
    return remember(px) { ReorderState(px) { haptic(it) } }
}

/** Long-press anywhere on the row, then drag. */
fun <T> Modifier.reorderOnLongPress(state: ReorderState<T>, item: T, enabled: Boolean = true, onMove: (Int, Int) -> Unit) =
    if (!enabled) this else pointerInput(item) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.start(item) },
            onDragEnd = { state.end(onMove) },
            onDragCancel = { state.cancel() },
        ) { change, amount -> change.consume(); state.drag(amount.y) }
    }

/** Immediate drag from a handle (no long-press needed). */
fun <T> Modifier.reorderHandle(state: ReorderState<T>, item: T, onMove: (Int, Int) -> Unit) = pointerInput(item) {
    detectVerticalDragGestures(
        onDragStart = { state.start(item) },
        onDragEnd = { state.end(onMove) },
        onDragCancel = { state.cancel() },
    ) { change, dy -> change.consume(); state.drag(dy) }
}

/** Lifts the dragged row above its neighbours and lets the others animate into place. */
@Composable
fun <T> Modifier.reorderItem(state: ReorderState<T>, index: Int, animate: Modifier): Modifier {
    val lifted = state.isDragging(index)
    return this
        .zIndex(if (lifted) 1f else 0f)
        .graphicsLayer {
            translationY = if (lifted) state.offset else 0f
            val s = if (lifted) 1.02f else 1f
            scaleX = s; scaleY = s
        }
        .then(if (lifted) Modifier.shadow(12.dp, RoundedCornerShape(20.dp)) else Modifier)
        .then(if (state.dragging < 0) animate else Modifier)
}
