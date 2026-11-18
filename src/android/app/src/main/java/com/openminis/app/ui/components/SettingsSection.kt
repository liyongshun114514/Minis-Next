package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Surface
import com.openminis.app.ui.components.miuix.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.i18n.uppercaseForDisplay

/**
 * iOS Settings-style "inset grouped" section. Renders an uppercase
 * teal header above a 16dp rounded surface card containing the
 * caller's [content] rows. Pair with [SettingsRowDivider] between
 * rows so the dividers stop short of the card's curved edges.
 *
 * Apply this to any settings detail screen that wants the
 * SF-Symbols Settings.app look — initially adopted by
 * RootfsManagementScreen (T168).
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = title.uppercaseForDisplay(),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MiuixTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
        Spacer(modifier = Modifier.height(16.dp))
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
