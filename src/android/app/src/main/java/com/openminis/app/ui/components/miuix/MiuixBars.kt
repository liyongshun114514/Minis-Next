package com.openminis.app.ui.components.miuix

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.components.LocalMinisMenuInteractive
import com.openminis.app.ui.components.MinisMenu
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Immutable
data class MenuItemColors(val textColor: Color, val leadingIconColor: Color, val trailingIconColor: Color)
object MenuDefaults {
    @Composable fun itemColors(
        textColor: Color = MiuixTheme.colorScheme.onSurface,
        leadingIconColor: Color = textColor,
        trailingIconColor: Color = textColor,
    ) = MenuItemColors(textColor, leadingIconColor, trailingIconColor)
}

/** Miuix surface + native press indication; arbitrary labels/icons preserve app menu semantics. */
@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
) {
    val alpha = if (enabled) 1f else 0.38f
    val interactive = LocalMinisMenuInteractive.current
    val currentInteractive by rememberUpdatedState(interactive)
    top.yukonga.miuix.kmp.basic.BasicComponent(
        modifier = modifier,
        onClick = { if (currentInteractive) onClick() },
        enabled = enabled && interactive,
        insideMargin = contentPadding,
        startAction = leadingIcon?.let { icon -> {
            CompositionLocalProvider(LocalContentColor provides colors.leadingIconColor.copy(alpha = alpha)) { icon() }
        } },
        endActions = trailingIcon?.let { icon -> {
            CompositionLocalProvider(LocalContentColor provides colors.trailingIconColor.copy(alpha = alpha)) { icon() }
        } },
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.textColor.copy(alpha = alpha)) { text() }
    }
}

@Composable
fun DropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    MinisMenu(expanded = expanded, onDismissRequest = onDismissRequest, minWidth = 200.dp, content = content)
}
