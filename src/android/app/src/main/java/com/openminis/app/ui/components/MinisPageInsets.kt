package com.openminis.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/** Top space for a scrollable body whose viewport extends under its bar. */
internal val LocalMinisPageTopInset = staticCompositionLocalOf { 0.dp }

@Composable
internal fun minisPageContentPadding(
    padding: PaddingValues = PaddingValues(),
): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = padding.calculateStartPadding(direction),
        top = padding.calculateTopPadding() + LocalMinisPageTopInset.current,
        end = padding.calculateEndPadding(direction),
        bottom = padding.calculateBottomPadding(),
    )
}

/** Apply after verticalScroll so the padding scrolls beneath the glass band. */
@Composable
internal fun Modifier.minisPageScrollPadding(): Modifier = padding(top = LocalMinisPageTopInset.current)
