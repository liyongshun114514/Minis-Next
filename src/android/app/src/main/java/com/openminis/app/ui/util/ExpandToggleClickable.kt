package com.openminis.app.ui.util

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.draw.clip
import top.yukonga.miuix.kmp.utils.MiuixIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * [ISS-017] Click handling for a header that expands or collapses a card in
 * the chat list (thinking block, "Ran N tools" fold row, resolved question
 * card). Same as `clickable`, minus the Material ripple.
 *
 * A user reported a dropped frame or two when dragging the list right after
 * tapping such a header, and that it went smooth once they removed the ripple
 * in a build of their own. The ripple is a ~300 ms RenderThread animation
 * over the header, running exactly while the expansion relayouts the list and
 * the finger starts a scroll. That causation is the user's inference, not
 * measured here; the change stands on its own regardless — the expansion is
 * itself the feedback for these taps (iOS has no ripple on them either).
 *
 * Press feedback uses MiuixIndication (not Material ripple), clipped to the
 * actual header/segment shape. An enclosing Card can additionally own the
 * group's outside corners. Click labels, roles and long-press semantics stay
 * identical to the native Foundation input contract.
 *
 * Do NOT use this for buttons or for rows that navigate somewhere (tool pills
 * that open a detail sheet, the sub agent card): those keep their ripple.
 */
fun Modifier.expandToggleClickable(
    onClickLabel: String? = null,
    role: Role? = null,
    pressedShape: Shape = androidx.compose.ui.graphics.RectangleShape,
    /** Optional long-press (e.g. a settings shortcut menu); null keeps plain `clickable`. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val ink = MiuixTheme.colorScheme.onSurface
    val indication = remember(ink) { MiuixIndication(color = ink) }
    // Shape belongs to the actual header/segment, not an unrelated tint bed.
    val base = this.clip(pressedShape)
    if (onLongClick == null) {
        base.clickable(
            interactionSource = interaction,
            indication = indication,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
    } else {
        base.combinedClickable(
            interactionSource = interaction,
            indication = indication,
            onClickLabel = onClickLabel,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }
}
