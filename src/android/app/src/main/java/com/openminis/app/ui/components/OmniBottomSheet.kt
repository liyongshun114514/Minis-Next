package com.openminis.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.OmniColors
import kotlinx.coroutines.launch

/** Source sheet over-pull: 0.55 resistance, bounded 100dp, k/m=1000/3. */
internal object OmniSheetPhysics {
    const val Resistance = 0.55f
    const val LimitDp = 100f
    const val Stiffness = 1000f / 3f

    fun pull(offset: Float, delta: Float, limit: Float): Float {
        if (!offset.isFinite() || !delta.isFinite() || !limit.isFinite() || limit <= 0f) return 0f
        val resisted = delta * Resistance * (1f - kotlin.math.abs(offset) / limit).coerceIn(0f, 1f)
        return (offset + resisted).coerceIn(-limit, 0f)
    }
}

/**
 * Same business/state contract as Material's modal sheet. Only its presentation
 * changes: neutral surface/scrim, 24dp top corners and bounded upward rubber.
 * Downward dismissal, back/secure-window policy, nested scroll consumption and
 * caller-owned SheetState stay with Material, including its accessibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    containerColor: Color = OmniColors.surfacePrimary,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = OmniColors.overlayScrim,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val overPull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val limit = with(LocalDensity.current) { OmniSheetPhysics.LimitDp.dp.toPx() }
    val connection = remember(overPull, limit) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Only child-unconsumed upward drag at its boundary. Never
                // consume the downward displacement Material needs to dismiss.
                if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                val next = OmniSheetPhysics.pull(overPull.value, available.y, limit)
                val used = next - overPull.value
                if (used != 0f) scope.launch { overPull.snapTo(next) }
                return Offset(0f, used)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                overPull.animateTo(0f, spring(dampingRatio = 1f, stiffness = OmniSheetPhysics.Stiffness))
                return Velocity.Zero
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.graphicsLayer { translationY = overPull.value }.nestedScroll(connection),
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
        contentWindowInsets = contentWindowInsets,
        properties = properties,
        content = content,
    )
}
