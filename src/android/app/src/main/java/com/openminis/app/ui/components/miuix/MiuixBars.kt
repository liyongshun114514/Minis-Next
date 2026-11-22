package com.openminis.app.ui.components.miuix

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.components.LocalMinisMenuInteractive
import com.openminis.app.ui.components.MinisMenu
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
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

/** Native press indication, inset and rounded like GlassPopupItem, with arbitrary labels/icons. */
@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
) {
    val alpha = if (enabled) 1f else 0.38f
    val interactive = LocalMinisMenuInteractive.current
    val currentInteractive by rememberUpdatedState(interactive)
    val direction = LocalLayoutDirection.current
    val pressInset = GlassPopupDefaults.ItemPressInset
    top.yukonga.miuix.kmp.basic.BasicComponent(
        modifier = modifier
            .padding(horizontal = pressInset)
            .clip(RoundedCornerShape(GlassPopupDefaults.ItemPressRadius)),
        onClick = { if (currentInteractive) onClick() },
        enabled = enabled && interactive,
        // Move the press plane inward while keeping existing icon/text alignment.
        insideMargin = PaddingValues(
            start = (contentPadding.calculateStartPadding(direction) - pressInset).coerceAtLeast(0.dp),
            top = contentPadding.calculateTopPadding(),
            end = (contentPadding.calculateEndPadding(direction) - pressInset).coerceAtLeast(0.dp),
            bottom = contentPadding.calculateBottomPadding(),
        ),
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
    MinisMenu(expanded = expanded, onDismissRequest = onDismissRequest, content = content)
}
