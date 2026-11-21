package com.openminis.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/** A click's window position, with the visible control's centre for keyboard/accessibility. */
@Stable
internal class MinisMenuAnchor {
    internal var coordinates: LayoutCoordinates? = null
    internal var pointer: Offset? = null

    fun clickPosition(): Offset? = pointer ?: coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.center
}

@Composable
internal fun rememberMinisMenuAnchor(): MinisMenuAnchor = remember { MinisMenuAnchor() }

internal fun Modifier.minisMenuAnchor(anchor: MinisMenuAnchor, enabled: Boolean = true): Modifier =
    onGloballyPositioned { anchor.coordinates = it }
        .pointerInput(anchor, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                if (up != null) {
                    anchor.pointer = anchor.coordinates?.takeIf { it.isAttached }?.localToWindow(up.position)
                    try {
                        // The child's clickable consumes UP in Main; retain this point until then.
                        awaitPointerEvent(PointerEventPass.Final)
                    } finally {
                        anchor.pointer = null
                    }
                }
            }
        }
