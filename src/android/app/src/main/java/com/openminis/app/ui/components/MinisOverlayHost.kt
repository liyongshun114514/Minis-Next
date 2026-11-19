package com.openminis.app.ui.components

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import top.yukonga.miuix.kmp.basic.Scaffold

internal val LocalMinisOverlayWindow = staticCompositionLocalOf<View?> { null }

/** Root Miuix host for overlays declared outside an individual page Scaffold. */
@Composable
internal fun MinisOverlayHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinisOverlayWindow provides LocalView.current.rootView) {
        Scaffold(contentWindowInsets = WindowInsets(0)) { content() }
    }
}
