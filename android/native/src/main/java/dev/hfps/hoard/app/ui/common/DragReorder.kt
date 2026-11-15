package dev.hfps.hoard.app.ui.common

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import kotlin.math.roundToInt

/**
 * Drag to reorder rows of one group in a list (Hoard 1's drag handles on favourites, machines and addresses): the row
 * follows the finger, the rows it passes make room, and the new order is committed on release. Rows of a group are
 * about the same height, so the target is the offset over the dragged row's height.
 */
@Stable
class DragReorder(private val onMove: (from: Int, to: Int) -> Unit) {
    var dragging by mutableIntStateOf(-1)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    private var rowHeight by mutableFloatStateOf(1f)
    var count by mutableIntStateOf(0)

    /** Where the dragged row would land now. */
    val target: Int get() = if (dragging < 0) -1 else (dragging + (offset / rowHeight).roundToInt()).coerceIn(0, (count - 1).coerceAtLeast(0))

    fun start(index: Int) {
        dragging = index
        offset = 0f
    }

    fun drag(dy: Float) {
        offset += dy
    }

    fun end() {
        val from = dragging
        val to = target
        dragging = -1
        offset = 0f
        if (from >= 0 && to >= 0 && from != to) onMove(from, to)
    }

    fun measured(height: Int) {
        if (height > 0) rowHeight = height.toFloat()
    }

    /** How far row [index] is shifted while another row is dragged (or how far the dragged one follows the finger). */
    fun shift(index: Int): Float {
        if (dragging < 0) return 0f
        if (index == dragging) return offset
        val t = target
        return when {
            dragging < index && index <= t -> -rowHeight
            t <= index && index < dragging -> rowHeight
            else -> 0f
        }
    }
}

@Composable
fun rememberDragReorder(count: Int, onMove: (from: Int, to: Int) -> Unit): DragReorder {
    val state = remember { DragReorder(onMove) }
    state.count = count
    return state
}

/** The row's place while dragging: follows [DragReorder.shift], above the others while it is the dragged one. */
fun Modifier.reorderable(state: DragReorder, index: Int): Modifier = this
    .zIndex(if (state.dragging == index) 1f else 0f)
    .graphicsLayer {
        translationY = state.shift(index)
        if (state.dragging == index) {
            shadowElevation = 8.dp.toPx()
            alpha = 0.96f
        }
    }
    .onSizeChanged { state.measured(it.height) }

/** The drag handle (dots), the only place a drag starts: the list still scrolls everywhere else. */
@Composable
fun DragHandle(state: DragReorder, index: Int, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .semantics { contentDescription = label }
            .testTag("drag-$index")
            .pointerInput(index) {
                detectDragGestures(
                    onDragStart = { state.start(index) },
                    onDragEnd = { state.end() },
                    onDragCancel = { state.end() },
                    onDrag = { change, amount ->
                        change.consume()
                        state.drag(amount.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) { Icon(PhosphorRegular.DotsSixVertical, null, Modifier.size(20.dp), tint = HfpsTheme.colors.mutedForeground) }
}

/** Pure: [ids] with the item at [from] moved to [to]. */
fun <T> moved(ids: List<T>, from: Int, to: Int): List<T> {
    if (from !in ids.indices) return ids
    val list = ids.toMutableList()
    list.add(to.coerceIn(0, list.lastIndex), list.removeAt(from))
    return list
}
