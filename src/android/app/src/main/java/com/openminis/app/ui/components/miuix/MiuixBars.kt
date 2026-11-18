package com.openminis.app.ui.components.miuix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Immutable
data class TopAppBarColors(val containerColor: Color)
object TopAppBarDefaults {
    @Composable fun topAppBarColors(containerColor: Color = MiuixTheme.colorScheme.surface,
        scrolledContainerColor: Color = containerColor) = TopAppBarColors(containerColor)
}

/** Rich chat titles need multiple reactive lines, which SmallTopAppBar's String API cannot express. */
@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    windowInsets: WindowInsets = WindowInsets.statusBars,
    expandedHeight: androidx.compose.ui.unit.Dp = 56.dp,
) {
    Surface(color = colors.containerColor, modifier = modifier) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(windowInsets).heightIn(min = expandedHeight).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            navigationIcon()
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                CompositionLocalProvider(LocalTextStyle provides MiuixTheme.textStyles.title3) { title() }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

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
    Surface(color = Color.Transparent, onClick = onClick, enabled = enabled, modifier = modifier) {
        Row(Modifier.fillMaxWidth().padding(contentPadding).heightIn(min = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leadingIcon?.let { CompositionLocalProvider(LocalContentColor provides colors.leadingIconColor.copy(alpha = alpha)) { it() } }
            Box(Modifier.weight(1f)) { CompositionLocalProvider(LocalContentColor provides colors.textColor.copy(alpha = alpha)) { text() } }
            trailingIcon?.let { CompositionLocalProvider(LocalContentColor provides colors.trailingIconColor.copy(alpha = alpha)) { it() } }
        }
    }
}

@Composable
fun DropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (expanded) Popup(onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MiuixTheme.colorScheme.surfaceContainer,
            shadowElevation = 8.dp, modifier = Modifier.widthIn(min = 180.dp, max = 320.dp)) {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
        }
    }
}

/** Anchoring scope for the few custom read-only input selectors. */
class ExposedDropdownMenuScope(private val onToggle: () -> Unit) {
    fun Modifier.menuAnchor(type: MenuAnchorType = MenuAnchorType.PrimaryNotEditable): Modifier = clickable(onClick = onToggle)
    @Composable fun ExposedDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
        DropdownMenu(expanded, onDismissRequest, content)
}
enum class MenuAnchorType { PrimaryNotEditable }
@Composable
fun ExposedDropdownMenuBox(expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, content: @Composable ExposedDropdownMenuScope.() -> Unit) {
    Box(modifier) { ExposedDropdownMenuScope { onExpandedChange(!expanded) }.content() }
}
object ExposedDropdownMenuDefaults {
    @Composable fun TrailingIcon(expanded: Boolean) {
        top.yukonga.miuix.kmp.basic.Text(if (expanded) "▴" else "▾")
    }
}

@Immutable
data class ListItemColors(val containerColor: Color, val headlineColor: Color)
object ListItemDefaults {
    @Composable fun colors(containerColor: Color = MiuixTheme.colorScheme.surface,
        headlineColor: Color = MiuixTheme.colorScheme.onSurface) = ListItemColors(containerColor, headlineColor)
}
@Composable
fun ListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
) {
    Surface(color = colors.containerColor, contentColor = colors.headlineColor, modifier = modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leadingContent?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                overlineContent?.invoke()
                headlineContent()
                supportingContent?.let { CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onSurfaceContainerVariant) { it() } }
            }
            trailingContent?.invoke()
        }
    }
}
