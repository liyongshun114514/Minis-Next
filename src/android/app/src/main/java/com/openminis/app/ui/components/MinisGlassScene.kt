package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported

internal val LocalMinisGlassScene = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalMinisGlassWindow = staticCompositionLocalOf<android.view.View?> { null }

/** The rendered page, including its bars, but never the sibling overlay sampling it. */
@Composable
internal fun MinisGlassScene(background: Color, content: @Composable () -> Unit) {
    val scene = if (isRuntimeShaderSupported()) rememberLayerBackdrop() else null
    CompositionLocalProvider(
        LocalMinisGlassScene provides scene,
        LocalMinisGlassWindow provides LocalView.current.rootView,
    ) {
        Box(
            Modifier.fillMaxSize()
                .then(if (scene != null) Modifier.layerBackdrop(scene) else Modifier)
                .background(background),
        ) { content() }
    }
}
