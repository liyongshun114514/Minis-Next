package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.material.GlassColorBlendMode
import top.yukonga.miuix.kmp.material.GlassColorLayer
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import com.openminis.app.ui.theme.ChatColors

/**
 * The composer's ordered glass layers, shared by the composer and both status
 * rows. The explicit surface layer keeps the material visibly filled when the
 * recorded backdrop is a flat page surface or a device renders the shader
 * layers too close to that surface.
 */
@Composable
internal fun chatComposerGlassMaterial(): GlassMaterial {
    val base = GlassMaterials.puredThinGlass(ChatColors.isDark)
    val surface = ChatColors.inputBg
    return remember(base, surface) {
        base.copy(
            third = GlassColorLayer(
                color = surface.copy(alpha = 0.72f),
                mode = GlassColorBlendMode.SrcOver,
            ),
        )
    }
}

/** Same glass renderer for the input, upper sub-agent row and lower work row. */
@Composable
internal fun Modifier.chatStatusBackground(backdrop: Backdrop?, shape: GlassShape): Modifier {
    return glassPanel(
        backdrop = backdrop,
        shape = shape,
        style = GlassDefaults.style(ChatColors.isDark),
        material = chatComposerGlassMaterial(),
        stroke = GlassEdgeStrokes.forTheme(
            isDark = ChatColors.isDark,
            light = GlassEdgeStrokes.SmallLight,
            dark = GlassEdgeStrokes.SmallDark,
        ),
        shadow = GlassShadows.Regular,
        shading = false,
        fallback = Modifier.clip(shape).background(ChatColors.inputBg),
    )
}
