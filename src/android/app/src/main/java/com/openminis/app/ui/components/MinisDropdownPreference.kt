package com.openminis.app.ui.components

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
) {
    var expanded by remember { mutableStateOf(false) }
    var mounted by remember { mutableStateOf(false) }
    val active = enabled && items.isNotEmpty()
    LaunchedEffect(active) { if (!active) expanded = false }
    val colors = MiuixTheme.colorScheme
    Box {
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
            holdDownState = mounted,
            onClick = { expanded = true; mounted = true },
            endActions = {
                Row(
                    modifier = Modifier.minisJellyPress(active),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showValue) items.getOrNull(selectedIndex)?.let { value ->
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
            onDismissFinished = { if (!expanded || !active) mounted = false },
            alignEnd = true,
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
