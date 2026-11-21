package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import com.openminis.app.ui.theme.ChatColors

/** Both status rows sample the opaque transcript, never each other or the composer. */
@Composable
internal fun Modifier.chatStatusBackground(backdrop: Backdrop?, shape: Shape): Modifier {
    // The floating tool bar uses the same surface as a user message bubble.
    // Keep the backdrop blur and only change the composited tint.
    val surface = ChatColors.userBubble
    if (backdrop == null) return clip(shape).background(surface)
    val blends = remember(surface) { listOf(BlendColorEntry(surface.copy(alpha = 0.72f))) }
    return textureBlur(
        backdrop = backdrop,
        shape = shape,
        blurRadius = 20f,
        noiseCoefficient = 0f,
        colors = BlurDefaults.blurColors(blendColors = blends),
    )
}
