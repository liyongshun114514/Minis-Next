package com.openminis.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.semantics.onLongClick as semanticsLongClick
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Draw-only spring: never changes the hit target or measured toolbar/anchor bounds. */
internal fun Modifier.minisJellyPress(enabled: Boolean = true): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "minisJellyPress",
    )
    this.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            pressed = true
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                } while (event.changes.any { it.pressed })
            } finally {
                pressed = false
            }
        }
    }.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Background band and controls are siblings; the controls never sample a recorded bar. */
@Composable
internal fun MinisGlassTopBar(
    backdrop: Backdrop?,
    background: Color,
    modifier: Modifier = Modifier,
    backgroundAlpha: () -> Float = { 1f },
    content: @Composable () -> Unit,
) {
    val blendColors = remember(background) { listOf(BlendColorEntry(background.copy(alpha = 0.80f))) }
    val blurColors = BlurDefaults.blurColors(blendColors = blendColors)
    val fallback = remember(background) { GlassTopAppBarDefaults.bandBrush(background) }
    val edgeFade = remember {
        Brush.verticalGradient(
            0f to Color.White, 0.42f to Color.White,
            0.78f to Color.White.copy(alpha = 0.42f), 1f to Color.Transparent,
        )
    }
    Layout(
        modifier = modifier,
        content = {
            if (backdrop != null) {
                Box(
                    Modifier.graphicsLayer {
                        alpha = backgroundAlpha().coerceIn(0f, 1f)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                        .drawWithContent {
                            drawContent()
                            drawRect(edgeFade, blendMode = BlendMode.DstIn)
                        }
                        .textureBlur(
                            backdrop = backdrop,
                            shape = RectangleShape,
                            blurRadius = 25f,
                            noiseCoefficient = 0f,
                            colors = blurColors,
                        ),
                )
            } else {
                Box(Modifier.drawBehind { drawRect(fallback, alpha = backgroundAlpha().coerceIn(0f, 1f)) })
            }
            Box { content() }
        },
    ) { measurables, constraints ->
        val foreground = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        // The fade reaches below the toolbar without enlarging its touch bounds.
        val bandHeight = foreground.height + GlassTopAppBarDefaults.BandOverhang.roundToPx()
        val band = measurables[0].measure(Constraints.fixed(foreground.width, bandHeight))
        layout(foreground.width, foreground.height) {
            band.placeRelative(0, 0)
            foreground.placeRelative(0, 0)
        }
    }
}

/** A glass silhouette with a uniform fill keeps its rim intact across inset boundaries. */
@Composable
internal fun MinisGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    enabled: Boolean = true,
    menuMotion: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // GlassIconButton owns an internal clickable. A second recognizer that only observes
    // DOWN is cancelled by that clickable's consumption. For long-press actions claim DOWN
    // first, then own BOTH tap and hold; the inner clickable remains the semantics action.
    val longPressModifier = if (onLongClick == null || !enabled) Modifier else Modifier.pointerInput(onClick, onLongClick) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Initial)
            down.consume()
            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation(pass = PointerEventPass.Initial) != null
            }
            when (released) {
                true -> {
                    currentEvent.changes.forEach { it.consume() }
                    onClick()
                }
                null -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
                false -> Unit // A drag/scroll cancelled the press: no action.
            }
        }
    }
    GlassIconButton(
        onClick = { if (enabled) onClick() },
        modifier = modifier.then(if (menuMotion) Modifier.minisJellyPress(enabled) else Modifier)
            .alpha(if (enabled) 1f else 0.38f)
            .then(longPressModifier)
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
                if (enabled && onLongClick != null) semanticsLongClick {
                    onLongClick.invoke()
                    true
                }
            },
        inheritTopBarMaterial = false,
        backdrop = null,
        fill = MiuixTheme.colorScheme.surfaceContainer,
        shadow = GlassShadows.Float,
        size = size,
        content = content,
    )
}
