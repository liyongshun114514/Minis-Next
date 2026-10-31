package com.openminis.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp

/**
 * [T-omnibot-ui-port] How a popup menu enters and leaves.
 *
 *  • [Standard] — a menu anchored to a control: 450ms out of the control's real
 *    bounds, 500ms back into them.
 *  • [Context] — a long-press / context menu: 200ms, the measured `shrinking`
 *    duration of the reference popup layer.
 */
enum class OmniPopupMorphStyle { Standard, Context }

/**
 * [T-omnibot-ui-port] Clock + geometry hand-off for the shared-container menu
 * transform.
 *
 * The Android popup is its own window sized to the panel, so the container cannot
 * start outside it: the transform begins at the part of the source control's real
 * rect that the window can show (the edge nearest the panel) and grows to the
 * whole panel, interpolating size, corner radius and surface colour. That is the
 * honest native equivalent of the reference's captured-source popup — the pixels
 * of the owning control are not reachable across a Compose `Popup` root, and
 * faking a bitmap of another window is not something this port does.
 *
 * All arithmetic lives in [OmniMorphMotion] so it can be unit-tested without a
 * device; this class only owns the clock and the "is the popup still mounted"
 * state that keeps the window alive through its 500ms return.
 */
@Stable
class OmniPopupMorphClock(
    val style: OmniPopupMorphStyle,
) {
    /** 0 = the control's rect, 1 = the settled panel. */
    val progress = Animatable(0f)

    /** True while the popup window must stay composed (open *and* closing). */
    var mounted: Boolean by mutableStateOf(false)
        private set

    /** True once the panel has been asked to return; drives the content fade. */
    var closing: Boolean by mutableStateOf(false)
        private set

    /** The source control's rect in popup-local pixels, or null for a sourceless popup. */
    var anchor: Rect? by mutableStateOf(null)

    /** True when the transform starts from a real control. */
    val hasSource: Boolean get() = anchor != null

    private var started = false

    /** Total open duration for this style. */
    val openMillis: Int get() = OmniMorphMotion.menuDurationMillis(
        contextMenu = style == OmniPopupMorphStyle.Context,
        closing = false,
    )

    /** Total close duration for this style. */
    val closeMillis: Int get() = OmniMorphMotion.menuDurationMillis(
        contextMenu = style == OmniPopupMorphStyle.Context,
        closing = true,
    )

    /** Mounts the window and animates out of the control. */
    suspend fun open() {
        mounted = true
        closing = false
        if (!started) {
            started = true
            progress.snapTo(0f)
        }
        if (progress.value >= 1f) return
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = OmniMorphMotion.remainingMillis(openMillis, progress.value),
                easing = OmniMorphMotion.MenuOpenEasing,
            ),
        )
    }

    /**
     * Returns into the control over the full close duration, measured at constant
     * speed from wherever the panel currently is, then unmounts the window.
     */
    suspend fun close() {
        if (!mounted) return
        closing = true
        if (progress.value > 0f) {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = OmniMorphMotion.remainingMillis(
                        totalMillis = closeMillis,
                        progress = 1f - progress.value,
                    ),
                    easing = OmniMorphMotion.MenuCloseEasing,
                ),
            )
        }
        mounted = false
    }

    /** Content opacity: appears late on the way out, leaves early on the way back. */
    fun contentAlpha(): Float = OmniMorphMotion.menuContentAlpha(progress.value, closing)

    /** Container colour blend factor. */
    fun containerBlend(): Float = OmniMorphMotion.containerFade(progress.value, closing)
}

/**
 * One drawn state of the menu container: the outline, its colour, the corner
 * radius it currently has, and the rect it covers.
 */
class OmniPopupFrame(
    val path: Path,
    val color: Color,
    val radiusPx: Float,
    val rect: Rect,
)

/**
 * Computes the current menu container outline inside a panel of [size].
 *
 * [anchor] is in popup-local pixels. When it is null the panel simply takes its
 * final frame (a dialog or a sourceless menu only fades/scales).
 */
fun omniPopupFrame(
    progress: Float,
    closing: Boolean,
    anchor: Rect?,
    size: Size,
    sourceRadiusPx: Float,
    targetRadiusPx: Float,
    sourceColor: Color,
    targetColor: Color,
): OmniPopupFrame {
    val panel = Rect(Offset.Zero, size)
    val fade = OmniMorphMotion.containerFade(progress, closing)
    val color = if (sourceColor == Color.Unspecified) {
        targetColor
    } else {
        OmniMorphMotion.colorAt(sourceColor, targetColor, fade)
    }
    if (anchor == null) {
        val radius = targetRadiusPx.coerceIn(0f, size.minDimension / 2f)
        return OmniPopupFrame(
            path = roundedRectPath(panel, radius),
            color = color,
            radiusPx = radius,
            rect = panel,
        )
    }
    val seed = OmniMorphMotion.clampedSourceRect(anchor, panel)
    val rect = OmniMorphMotion.rectAt(seed, panel, progress)
    val radius = OmniMorphMotion.radiusAt(sourceRadiusPx, targetRadiusPx, progress, rect)
    return OmniPopupFrame(
        path = roundedRectPath(rect, radius),
        color = color,
        radiusPx = radius,
        rect = rect,
    )
}

/** Outline of the settled panel as a path, in the current draw scope's density. */
internal fun DrawScope.shapeOutlinePath(shape: Shape): Path {
    val outline = shape.createOutline(size, layoutDirection, this)
    return Path().apply { addOutline(outline) }
}

internal fun roundedRectPath(rect: Rect, radiusPx: Float): Path = Path().apply {
    addRoundRect(RoundRect(rect = rect, cornerRadius = CornerRadius(radiusPx)))
}

/**
 * [T-omnibot-ui-port] The menu panel: shared-container morph, animated corner
 * radius, interpolated surface colour and phased content fade, drawn inside the
 * popup window.
 *
 * Replaces a plain `Surface` so the panel can *be* the transform instead of
 * scaling from a corner. A settled panel (progress == 1) matches the previous
 * Surface: same radius, same fill, same hairline border, same elevation.
 *
 * The shadow uses a graphics-layer outline (the same mechanism Surface uses), so
 * an animated radius keeps a real elevation shadow without recomposition.
 */
@Composable
fun OmniPopupMorphSurface(
    clock: OmniPopupMorphClock,
    containerColor: Color,
    modifier: Modifier = Modifier,
    /**
     * The source control's rect in popup-local pixels. Null renders a sourceless
     * popup (a dialog or a menu with no measurable anchor), which swells and
     * fades instead of morphing a rect.
     */
    anchor: Rect? = null,
    /** Settled outline — honoured exactly once the transform has finished. */
    panelShape: Shape = RoundedCornerShape(OmniMorphMotion.MenuPanelRadius),
    panelRadius: Dp = OmniMorphMotion.MenuPanelRadius,
    sourceRadius: Dp = OmniMorphMotion.MenuSourceRadius,
    sourceColor: Color = Color.Unspecified,
    borderColor: Color? = null,
    borderWidth: Dp = 0.75.dp,
    shadowElevation: Dp = 0.dp,
    /**
     * Refracting pane style for this popup, or null for a flat panel.
     *
     * Uses the shared [Modifier.omniGlassSurface]: the capture controller resolves
     * a pane's position with a `localToScreen` difference, which is the one
     * coordinate space a popup window shares with the activity window behind it.
     * (The container transform itself never needs that: its anchor rect and its
     * own placement are both produced inside the same window by the position
     * provider, so no cross-window arithmetic is involved.)
     */
    glassStyle: OmniGlassStyle? = null,
    /** Opaque fill alpha once the pane has settled (glass needs the sampling to show). */
    glassFillAlpha: Float = 0.45f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val targetRadiusPx = with(density) { panelRadius.toPx() }
    val sourceRadiusPx = with(density) { sourceRadius.toPx() }
    val shadowPx = with(density) { shadowElevation.toPx() }
    val borderPx = with(density) { borderWidth.toPx() }
    val hasSource = anchor != null
    // Glass pane over the shared capture scope. The modifier resolves its own
    // origin with a localToScreen difference, which is the one coordinate space a
    // popup window shares with the activity window behind it.
    val glassModifier = if (glassStyle != null) {
        Modifier.omniGlassSurface(shape = panelShape, style = glassStyle)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(glassModifier)
            .graphicsLayer {
                // A popup with no measured anchor keeps its final frame and only
                // phases its content in — no scale flash before the position
                // provider reports the anchor on the first layout pass.
                // `this.` is required: the function's own `shadowElevation`
                // parameter shadows GraphicsLayerScope's member.
                this.shadowElevation = shadowPx
                this.shape = panelShape
            }
            .drawBehind {
                val settled = clock.progress.value >= 1f
                val frame = omniPopupFrame(
                    progress = clock.progress.value,
                    closing = clock.closing,
                    anchor = anchor,
                    size = size,
                    sourceRadiusPx = sourceRadiusPx,
                    targetRadiusPx = targetRadiusPx,
                    sourceColor = sourceColor,
                    targetColor = containerColor,
                )
                val path = if (settled) shapeOutlinePath(panelShape) else frame.path
                val radius = if (settled) {
                    targetRadiusPx.coerceIn(0f, size.minDimension / 2f)
                } else {
                    frame.radiusPx
                }
                // The panel fill. With glass it cannot stay opaque or it would hide
                // the sampling (the shared pane draws the backdrop underneath this
                // path): it fades to glassFillAlpha as the panel settles. Without
                // glass it stays the flat Omni surface.
                val settle = OmniMorphMotion.segment(clock.progress.value, 0.55f, 1f)
                val fillAlpha = if (glassStyle != null) {
                    OmniMorphMotion.lerp(1f, glassFillAlpha, settle)
                } else {
                    1f
                }
                drawPath(path, frame.color.copy(alpha = frame.color.alpha * fillAlpha))
            }
            .drawWithContent {
                val settled = clock.progress.value >= 1f
                val frame = omniPopupFrame(
                    progress = clock.progress.value,
                    closing = clock.closing,
                    anchor = anchor,
                    size = size,
                    sourceRadiusPx = sourceRadiusPx,
                    targetRadiusPx = targetRadiusPx,
                    sourceColor = sourceColor,
                    targetColor = containerColor,
                )
                val clip = if (settled) {
                    shapeOutlinePath(panelShape)
                } else {
                    frame.path
                }
                clipPath(clip) { this@drawWithContent.drawContent() }
                if (borderColor != null) {
                    drawPath(
                        path = clip,
                        color = borderColor,
                        style = Stroke(width = borderPx),
                    )
                }
            },
    ) {
        Box(
            modifier = Modifier.graphicsLayer { alpha = clock.contentAlpha() },
        ) {
            content()
        }
    }
}

/**
 * Converts a source control's window-space bounds into popup-local pixels.
 *
 * Every [MinisMenu] uses this through its position provider; it is public so a
 * wrapper that already knows both rects (a finger-anchored menu) can seed the
 * transform without re-measuring.
 */
fun omniPopupLocalAnchor(
    anchorInWindow: IntRect,
    popupPosition: Offset,
): Rect = Rect(
    left = anchorInWindow.left - popupPosition.x,
    top = anchorInWindow.top - popupPosition.y,
    right = anchorInWindow.right - popupPosition.x,
    bottom = anchorInWindow.bottom - popupPosition.y,
)
