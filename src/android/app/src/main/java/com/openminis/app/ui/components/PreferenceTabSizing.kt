package com.openminis.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Keep translated tabs readable. Miuix scrolls when these widths exceed the viewport. */
@Composable
internal fun preferenceTabMinWidth(labels: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = MiuixTheme.textStyles.body2.fontSize, fontWeight = FontWeight.Bold)
    val widest = labels.maxOfOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } ?: 0
    return maxOf(TabRowDefaults.TabRowWithContourMinWidth, with(LocalDensity.current) { widest.toDp() } + 16.dp)
}

@Composable
internal fun preferenceTabHeight(): Dp =
    maxOf(48.dp, with(LocalDensity.current) { MiuixTheme.textStyles.body2.fontSize.toDp() } + 24.dp)
