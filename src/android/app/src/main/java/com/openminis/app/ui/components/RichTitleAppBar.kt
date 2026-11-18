package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Header for screens with a title that includes separate interactive or live content. */
@Composable
internal fun RichTitleAppBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    color: Color = MiuixTheme.colorScheme.surface,
    windowInsets: WindowInsets = WindowInsets.statusBars,
    expandedHeight: Dp = 56.dp,
) {
    Surface(color = color) {
        Row(
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(windowInsets)
                .heightIn(min = expandedHeight).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon()
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) { title() }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
