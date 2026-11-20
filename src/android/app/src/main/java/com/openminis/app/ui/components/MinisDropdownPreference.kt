package com.openminis.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Native Miuix row + native Glass dropdown, sharing MinisMenu's window/IME,
 * exit completion and demand-scene contract. Values remain caller-controlled.
 * [valueText] can show the saved value when it differs from the selected preset label.
 * [anchorAtTap] uses the pointer position, with the row as the keyboard and accessibility fallback.
 */
@Composable
internal fun MinisDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showValue: Boolean = true,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    valueText: String? = null,
    anchorAtTap: Boolean = false,
    onDismissFinished: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var mounted by remember { mutableStateOf(false) }
    val active = enabled && items.isNotEmpty()
    LaunchedEffect(active) { if (!active) expanded = false }
    val colors = MiuixTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var tapForClick by remember { mutableStateOf<Offset?>(null) }
    var menuTap by remember { mutableStateOf<Offset?>(null) }
    val tapObserver = if (anchorAtTap && active) {
        Modifier.onGloballyPositioned { coordinates = it }.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                if (up != null) {
                    // Only expose this point during the native click's Main pass.
                    // Scrolling, Back and accessibility clicks cannot reuse an old point.
                    tapForClick = coordinates?.takeIf { it.isAttached }?.localToWindow(up.position)
                    try {
                        awaitPointerEvent(PointerEventPass.Final)
                    } finally {
                        tapForClick = null
                    }
                }
            }
        }
    } else Modifier
    Box(tapObserver) {
        BasicComponent(
            title = title,
            summary = summary,
            startAction = startAction,
            // BasicComponent omits clickable entirely when disabled. Keep the
            // row's identity/role and disabled state discoverable without adding
            // a second click action that could bypass its native input gate.
            // The row's press plane stays flush with its neighbouring dividers.
            // Only its value/action content moves; Miuix owns the full-row tint.
            modifier = modifier.semantics(mergeDescendants = true) {
                role = Role.DropdownList
                if (!active) disabled()
            },
            insideMargin = insideMargin,
            enabled = active,
            role = Role.DropdownList,
            interactionSource = interactions,
            holdDownState = mounted,
            onClick = {
                menuTap = tapForClick
                mounted = true
                expanded = true
            },
            endActions = {
                Row(
                    modifier = Modifier.minisJellyPress(active),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showValue) (valueText ?: items.getOrNull(selectedIndex))?.let { value ->
                        Text(value, style = MiuixTheme.textStyles.body2,
                            color = colors.onSurfaceVariantActions,
                            modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp))
                    }
                    DropdownArrowEndAction(actionColor = if (active) colors.onSurfaceVariantActions else colors.disabledOnSecondaryVariant)
                }
            },
        )
        MinisMenu(
            expanded = expanded && active,
            onDismissRequest = { expanded = false },
            onDismissFinished = {
                if (!expanded || !active) {
                    mounted = false
                    menuTap = null
                    onDismissFinished?.invoke()
                }
            },
            alignEnd = true,
            tapPositionInWindow = menuTap,
        ) {
            items.forEachIndexed { index, label ->
                GlassPopupItem(
                    text = label,
                    selected = index == selectedIndex,
                    onClick = {
                        if (expanded && active) {
                            expanded = false
                            onSelectedIndexChange(index)
                        }
                    },
                )
            }
        }
    }
}
