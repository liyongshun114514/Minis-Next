package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/** Inset group for content such as the rootfs detail list. */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SmallTitle(text = title)
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            content = content,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Thin divider for rows inside a [SettingsSection]. Pads in 16dp on
 * either side so the line stops short of the rounded card edge,
 * matching iOS Settings' inset divider style.
 */
@Composable
fun SettingsRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.65f),
    )
}
