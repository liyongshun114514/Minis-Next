package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import com.openminis.app.ui.theme.ChatColors

/** Both status rows sample the opaque transcript, never each other or the composer. */
@Composable
internal fun Modifier.chatStatusBackground(backdrop: Backdrop?, shape: GlassShape): Modifier {
    // Match the composer's material, edge strokes, shadow and fallback exactly.
    // A separate 72%-opaque tint made these rows look solid beside the composer.
    return glassPanel(
        backdrop = backdrop,
        shape = shape,
        style = GlassDefaults.style(ChatColors.isDark),
        material = GlassMaterials.puredThinGlass(ChatColors.isDark),
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
