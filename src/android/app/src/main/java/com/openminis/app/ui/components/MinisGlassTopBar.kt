package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults

private val LocalTopBarBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * The same scene -> progressive band -> button sampling order as Miuix GlassTopAppBar,
 * retaining Minis' rich title slots. Neither the title nor its buttons enter either recording.
 */
@Composable
internal fun MinisGlassTopBar(
    backdrop: LayerBackdrop?,
    background: Color,
    modifier: Modifier = Modifier,
    backgroundAlpha: () -> Float = { 1f },
    content: @Composable () -> Unit,
) {
    var bandCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val buttonBackdrop = if (backdrop != null) {
        rememberLayerBackdrop {
            // At zero overlap the band is transparent: retain the raw scene underneath it.
            val coordinates = bandCoordinates?.takeIf { it.isAttached }
            with(backdrop) { drawBackdrop(this@rememberLayerBackdrop, coordinates) }
            drawContent()
        }
    } else null
    val blendColors = remember(background) { listOf(BlendColorEntry(background.copy(alpha = 0.3f))) }
    val blurColors = BlurDefaults.blurColors(blendColors = blendColors)
    val fallback = remember(background) { GlassTopAppBarDefaults.bandBrush(background) }
    Layout(
        modifier = modifier,
        content = {
            if (backdrop != null && buttonBackdrop != null) {
                Box(
                    Modifier.onGloballyPositioned { bandCoordinates = it }
                        .layerBackdrop(buttonBackdrop)
                        .graphicsLayer { alpha = backgroundAlpha().coerceIn(0f, 1f) }
                        .progressiveTextureBlur(
                            backdrop = backdrop,
                            shape = RectangleShape,
                            blurRadius = GlassTopAppBarDefaults.ProgressiveBlurRadius.value,
                            gradient = ProgressiveBlur.Top.copy(curve = 0.5f),
                            colors = blurColors,
                        ),
                )
            } else {
                Box(Modifier.drawBehind { drawRect(fallback, alpha = backgroundAlpha().coerceIn(0f, 1f)) })
            }
            Box {
                CompositionLocalProvider(LocalTopBarBackdrop provides buttonBackdrop) { content() }
            }
        },
    ) { measurables, constraints ->
        val foreground = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        // The fade extends beyond the toolbar, without enlarging its layout or hit rectangle.
        val bandHeight = foreground.height + GlassTopAppBarDefaults.BandOverhang.roundToPx()
        val band = measurables[0].measure(Constraints.fixed(foreground.width, bandHeight))
        layout(foreground.width, foreground.height) {
            band.placeRelative(0, 0)
            foreground.placeRelative(0, 0)
        }
    }
}

/** Always floating; sample the rendered band rather than replacing it with the raw page. */
@Composable
internal fun MinisGlassIconButton(
    backdrop: Backdrop?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    GlassIconButton(
        onClick = onClick,
        modifier = modifier.semantics { role = Role.Button },
        inheritTopBarMaterial = false,
        backdrop = LocalTopBarBackdrop.current ?: backdrop,
        material = GlassTopAppBarDefaults.buttonMaterial(),
        size = 48.dp,
        content = content,
    )
}
