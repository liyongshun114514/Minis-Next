package com.openminis.app.ui.components

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import top.yukonga.miuix.kmp.blur.textureBlur
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
            val blurAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (scene?.backgroundBlurred == true) 1f else 0f,
                animationSpec = androidx.compose.animation.core.tween(180),
                label = "dialogBackgroundBlur",
            )
            val recordingModifier = if (scene?.recording == true) Modifier.layerBackdrop(scene.backdrop) else Modifier
            Box(
                Modifier.fillMaxSize().then(recordingModifier).then(
                    // The last modal releases its lease before the blur fades out.
                    if (scene != null && !scene.recording && blurAlpha > 0f) Modifier.layerBackdrop(scene.backdrop) else Modifier,
                ),
            ) { content() }
            // This sibling is outside the recording. Popups remain above it in
            // MiuixPopupHost, so neither the scrim nor the dialog can sample itself.
            if (scene != null && blurAlpha > 0f) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer { alpha = blurAlpha }
                        .textureBlur(
                            backdrop = scene.backdrop,
                            shape = androidx.compose.ui.graphics.RectangleShape,
                            blurRadius = 16f,
                            noiseCoefficient = 0f,
                            colors = top.yukonga.miuix.kmp.blur.BlurDefaults.blurColors(),
                        ),
                )
            }
        }
    }
}
