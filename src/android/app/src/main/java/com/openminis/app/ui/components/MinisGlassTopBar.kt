package com.openminis.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.glass.glassPress
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Observe the native clickable without adding a second click handler or a painted overlay. */
internal fun Modifier.minisJellyPress(enabled: Boolean = true): Modifier = composed {
    val interactions = remember { MutableInteractionSource() }
    this.minisPressEvents(interactions, enabled).glassPress(interactions, enabled)
}

/** Observe at the resting surface; content motion must not move its shadow/anchor. */
private fun Modifier.minisPressEvents(interactions: MutableInteractionSource, enabled: Boolean): Modifier = composed {
    val scope = rememberCoroutineScope()
    this.pointerInput(enabled, interactions) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val press = PressInteraction.Press(down.position)
            scope.launch { interactions.emit(press) }
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                } while (event.changes.any { it.pressed })
            } finally {
                scope.launch { interactions.emit(PressInteraction.Cancel(press)) }
            }
        }
    }
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
    val resolvedBackdrop = backdrop ?: LocalMinisActionBackdrop.current
    // The official band tints with 30% surface. A heavier wash hides the very
    // texture it is supposed to blur and reads as "gradient only".
    val blendColors = remember(background) { listOf(BlendColorEntry(background.copy(alpha = 0.30f))) }
    val blurColors = BlurDefaults.blurColors(blendColors = blendColors)
    val fallback = remember(background) { GlassTopAppBarDefaults.bandBrush(background) }
    Layout(
        modifier = modifier,
        content = {
            if (resolvedBackdrop != null) {
                Box(
                    Modifier.graphicsLayer {
                        alpha = backgroundAlpha().coerceIn(0f, 1f)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                        // Native progressive blur: full strength at the bar,
                        // releasing into the page over the overhang band.
                        .progressiveTextureBlur(
                            backdrop = resolvedBackdrop,
                            shape = RectangleShape,
                            blurRadius = 48f,
                            gradient = ProgressiveBlur.Top.copy(curve = 0.5f),
                            noiseCoefficient = 0f,
                            colors = blurColors,
                        ),
                )
            } else {
                Box(Modifier.drawBehind { drawRect(fallback, alpha = backgroundAlpha().coerceIn(0f, 1f)) })
            }
            CompositionLocalProvider(LocalMinisActionBackdrop provides resolvedBackdrop) {
                Box { content() }
            }
        },
    ) { measurables, constraints ->
        val foreground = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        // The fade reaches below the toolbar without enlarging its touch bounds.
        val bandHeight = foreground.height + 12.dp.roundToPx()
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
    backdrop: Backdrop? = LocalMinisActionBackdrop.current,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val sameWindow = LocalMinisOverlayWindow.current === LocalView.current.rootView
    val effectiveBackdrop = backdrop.takeIf { sameWindow }
    val currentClick by rememberUpdatedState(onClick)
    val currentLongClick by rememberUpdatedState(onLongClick)
    val pressInteractions = remember { MutableInteractionSource() }
    val pressEnabled = enabled && (menuMotion || onLongClick != null)
    // GlassIconButton owns an internal clickable. A second recognizer that only observes
    // DOWN is cancelled by that clickable's consumption. For long-press actions claim DOWN
    // first, then own BOTH tap and hold; the inner clickable remains the semantics action.
    val longPressModifier = if (onLongClick == null || !enabled) Modifier else Modifier.pointerInput(enabled) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Initial)
            down.consume()
            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation(pass = PointerEventPass.Initial) != null
            }
            when (released) {
                true -> {
                    currentEvent.changes.forEach { it.consume() }
                    currentClick()
                }
                null -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentLongClick?.invoke()
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
        // Keep the actual surface/anchor at resting bounds. Scaling its shadow
        // and sampling plane creates a second material handoff on popup exit.
        // The native morph supplies the surface motion; only the glyph presses.
        modifier = modifier
            .minisPressEvents(pressInteractions, pressEnabled)
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
        backdrop = effectiveBackdrop,
        // The native button and morph endpoint use the same single shadow/material.
        // Do not add a second app-owned press/dimming surface around that silhouette.
        shadow = GlassShadows.Regular,
        size = size,
    ) {
        Box(Modifier.glassPress(pressInteractions, pressEnabled)) {
            content()
        }
    }
}
