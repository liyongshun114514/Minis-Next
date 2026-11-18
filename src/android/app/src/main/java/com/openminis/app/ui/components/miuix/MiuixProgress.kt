package com.openminis.app.ui.components.miuix

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
    progress: (() -> Float)? = null,
) {
    top.yukonga.miuix.kmp.basic.CircularProgressIndicator(
        modifier = modifier, progress = progress?.invoke(), strokeWidth = strokeWidth,
        colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = color),
    )
}

@Composable
fun LinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.primary,
    trackColor: Color = MiuixTheme.colorScheme.secondaryContainer,
    progress: (() -> Float)? = null,
) {
    top.yukonga.miuix.kmp.basic.LinearProgressIndicator(
        modifier = modifier, progress = progress?.invoke(),
        colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = color, backgroundColor = trackColor),
    )
}
