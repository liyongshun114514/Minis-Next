package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.settings.SettingsGlassTopBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared compact page bar, sampling only the sibling body owned by its scaffold. */
@Composable
internal fun MinisTopAppBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    color: Color = MiuixTheme.colorScheme.surface,
    blurBackground: Boolean = true,
    @Suppress("UNUSED_PARAMETER") expandable: Boolean = false,
) {
    SettingsGlassTopBar(
        title = title,
        backdrop = LocalMinisActionBackdrop.current,
        overlap = { 1f },
        centerTitle = false,
        onBack = null,
        navigation = navigationIcon,
        actions = { Row(content = actions) },
        background = color,
        blurBackground = blurBackground,
    )
}
