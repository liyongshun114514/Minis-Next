package com.openminis.app.ui.components

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import top.yukonga.miuix.kmp.basic.Scaffold

internal val LocalMinisOverlayWindow = staticCompositionLocalOf<View?> { null }

/** Root menus are siblings of the recorded scene: no popup/scrim can sample itself. */
@Composable
internal fun MinisOverlayHost(content: @Composable () -> Unit) {
    val background = MiuixTheme.colorScheme.surface
    val layer = if (isRuntimeShaderSupported()) rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    } else null
    val scene = remember(layer) { layer?.let(::MinisSceneBackdrop) }
    CompositionLocalProvider(
        LocalMinisOverlayWindow provides LocalView.current.rootView,
        LocalMinisSceneBackdrop provides scene,
    ) {
        Scaffold(contentWindowInsets = WindowInsets(0), containerColor = background) {
            Box(
                Modifier.fillMaxSize().then(
                    if (scene?.recording == true) Modifier.layerBackdrop(scene.backdrop) else Modifier,
                ),
            ) { content() }
        }
    }
}
