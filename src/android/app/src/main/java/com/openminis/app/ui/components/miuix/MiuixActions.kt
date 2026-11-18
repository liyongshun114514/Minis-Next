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

@Immutable
data class IconButtonColors(val containerColor: Color, val contentColor: Color)
object IconButtonDefaults {
    @Composable fun filledIconButtonColors(containerColor: Color = MiuixTheme.colorScheme.secondaryContainer,
        contentColor: Color = MiuixTheme.colorScheme.onSecondaryContainer) = IconButtonColors(containerColor, contentColor)
}
@Composable
fun FilledIconButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(), content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier, backgroundColor = colors.containerColor) {
        CompositionLocalProvider(LocalContentColor provides colors.contentColor, content = content)
    }
}
@Composable
fun SmallFloatingActionButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    containerColor: Color = MiuixTheme.colorScheme.primary,
    contentColor: Color = MiuixTheme.colorScheme.onPrimary, content: @Composable () -> Unit) {
    FloatingActionButton(onClick = onClick, modifier = modifier, containerColor = containerColor,
        minWidth = 40.dp, minHeight = 40.dp) {
        CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
    }
}

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
