package com.openminis.app.ui.components.miuix

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class SwipeToDismissBoxValue { Settled, StartToEnd, EndToStart }
@Stable
class SwipeToDismissBoxState internal constructor() {
    internal val drag = AnchoredDraggableState(SwipeToDismissBoxValue.Settled)
    val currentValue: SwipeToDismissBoxValue get() = drag.settledValue
}
@Composable
fun rememberSwipeToDismissBoxState() = remember { SwipeToDismissBoxState() }

/** Foundation gesture only: the row and its background use Miuix surfaces at the call site. */
@Composable
fun SwipeToDismissBox(state: SwipeToDismissBoxState, backgroundContent: @Composable () -> Unit,
    modifier: Modifier = Modifier, enableDismissFromStartToEnd: Boolean = true,
    content: @Composable () -> Unit) {
    Box(modifier.onSizeChanged { size ->
        state.drag.updateAnchors(DraggableAnchors {
            SwipeToDismissBoxValue.Settled at 0f
            SwipeToDismissBoxValue.EndToStart at -size.width.toFloat()
            if (enableDismissFromStartToEnd) SwipeToDismissBoxValue.StartToEnd at size.width.toFloat()
        })
    }) {
        backgroundContent()
        Box(Modifier.fillMaxWidth().offset {
            IntOffset(state.drag.offset.takeUnless { it.isNaN() }?.roundToInt() ?: 0, 0)
        }.anchoredDraggable(state.drag, Orientation.Horizontal)) { content() }
    }
}
