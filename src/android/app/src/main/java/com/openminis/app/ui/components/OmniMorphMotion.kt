package com.openminis.app.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * [T-omnibot-ui-port] Motion + geometry table for the two measured container
 * transforms of the Omni presentation port:
 *
 *  • a popup menu opening out of (and returning into) the control that owns it —
 *    450ms out, 500ms back, 200ms for a context menu;
 *  • a settings row expanding into its destination page — 300ms
 *    `fastOutSlowIn` out and back.
 *
 * ## Provenance
 *
 * The numbers are measured presentation parameters of the reference app (its
 * `flclash`-derived popup motion and its row→page container transition), not
 * copied source: the reference is a Flutter widget tree under a segmented
 * AGPL/commercial licence, while everything below is an independent Compose
 * expression of the same geometry (rect/radius/colour interpolation, phased
 * content fade). No reference widget, asset, font, shader or state machine is
 * incorporated; see `docs/ui-port/provenance.md`.
 *
 * ## What is genuinely different on Android
 *
 * Flutter drives these transforms with springs (`SpringSimulation`). Compose's
 * `Animatable` accepts an `AnimationSpec`, so the springs are expressed as the
 * closest single cubic segment with the same settle direction (fast out, slow
 * into place). Springs are not silently faked: the difference is documented
 * here, in `docs/ui-port/acceptance.md` terms ("spring physics is a gap").
 *
 * Everything in this object is pure arithmetic so the interpolation can be
 * unit-tested on a plain JVM without Compose UI or a device.
 */
object OmniMorphMotion {

    /** Menu expands out of its source control. */
    const val MenuOpenMillis = 450

    /** Menu returns into its source control — deliberately longer than open. */
    const val MenuCloseMillis = 500

    /** Context menus (long-press) and popups without a source control. */
    const val MenuContextMillis = 200

    /** Settings row → destination page. */
    const val ContainerOpenMillis = 300

    /** Destination page → settings row (back / cancel / save). */
    const val ContainerCloseMillis = 300

    /**
     * Flutter `Curves.fastOutSlowIn` = cubic(0.4, 0.0, 0.2, 1.0). Used for the
     * settings container in both directions.
     */
    val ContainerEasing: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /**
     * `Curves.fastOutSlowIn.flipped` — the same curve point-mirrored
     * (1 - p2, 1 - p1) = cubic(0.8, 0.0, 0.6, 1.0), so the return decelerates
     * into the row exactly like the expansion decelerated into the page.
     */
    val ContainerReturnEasing: Easing = CubicBezierEasing(0.8f, 0f, 0.6f, 1f)

    /** Menu open: decelerating swell (stand-in for the reference spring). */
    val MenuOpenEasing: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** Menu close: accelerating dip back into the control. */
    val MenuCloseEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** Radius of an Omni popup panel. */
    val MenuPanelRadius: Dp = 24.dp

    /** Radius of a source control that owns a menu (buttons, chips, pills). */
    val MenuSourceRadius: Dp = 12.dp

    /**
     * The settings container's own corner radius while it is still a row: rows
     * live inside a 22dp grouped card, so the expansion starts from that card's
     * contour instead of from a hard rectangle.
     */
    val RowSourceRadius: Dp = 22.dp

    /** Destination page: square, because the page is the window. */
    val PageRadius: Dp = 0.dp

    // ── Phased content fade ──────────────────────────────────────────────────

    /** Container colour/edge fade: second fifth on the way in… */
    const val ContainerFadeInStart = 0.2f
    const val ContainerFadeInEnd = 0.4f

    /** …and the fourth fifth on the way out. */
    const val ContainerFadeOutStart = 0.6f
    const val ContainerFadeOutEnd = 0.8f

    /** Menu content appears just after the panel has taken its frame. */
    const val MenuContentInStart = 0.30f
    const val MenuContentInEnd = 0.75f

    /** Menu content leaves early so the returning panel is empty. */
    const val MenuContentOutStart = 0f
    const val MenuContentOutEnd = 0.45f

    /**
     * Normalised progress of [value] inside [start]..[end], clamped. Returns 0
     * for a degenerate segment (`end <= start`) instead of dividing by zero.
     */
    fun segment(value: Float, start: Float, end: Float): Float {
        if (end <= start) return if (value >= end) 1f else 0f
        return ((value - start) / (end - start)).coerceIn(0f, 1f)
    }

    /**
     * How far the destination page and the container colour have faded in.
     * Mirrors the measured "changes over a fifth of the transition" behaviour:
     * 20%→40% when opening, 80%→60% when closing.
     */
    fun containerFade(progress: Float, closing: Boolean): Float = if (closing) {
        segment(progress, ContainerFadeOutStart, ContainerFadeOutEnd)
    } else {
        segment(progress, ContainerFadeInStart, ContainerFadeInEnd)
    }

    /** Menu panel content opacity for the given normalised progress. */
    fun menuContentAlpha(progress: Float, closing: Boolean): Float = if (closing) {
        segment(progress, MenuContentOutStart, MenuContentOutEnd)
    } else {
        segment(progress, MenuContentInStart, MenuContentInEnd)
    }

    /**
     * Entry/exit phasing for a destination page: the bar settles first, then
     * the first group of rows, then the rest of the page. Three overlapping
     * segments over one 300ms clock, so nothing pops in at a hard edge.
     */
    fun headerAlpha(progress: Float): Float = segment(progress, 0.10f, 0.55f)
    fun firstBlockAlpha(progress: Float): Float = segment(progress, 0.22f, 0.80f)
    fun bodyAlpha(progress: Float): Float = segment(progress, 0.38f, 1f)

    // ── Geometry interpolation ───────────────────────────────────────────────

    /** Linear interpolation, clamped to `0..1` so an interrupted clock can't overshoot. */
    fun lerp(start: Float, stop: Float, fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        return start + (stop - start) * f
    }

    /** Linear interpolation between two dp values. */
    fun lerp(start: Dp, stop: Dp, fraction: Float): Float =
        lerp(start.value, stop.value, fraction)

    /**
     * Interpolates the container rectangle between the source control's bounds
     * and the destination's bounds — the measured size morph. Both rects are in
     * the same coordinate space (window/caller-local); the caller converts.
     */
    fun rectAt(source: Rect, target: Rect, fraction: Float): Rect {
        val f = fraction.coerceIn(0f, 1f)
        return Rect(
            left = lerp(source.left, target.left, f),
            top = lerp(source.top, target.top, f),
            right = lerp(source.right, target.right, f),
            bottom = lerp(source.bottom, target.bottom, f),
        )
    }

    /**
     * A corner radius can never exceed half of the shorter side of the rect it
     * rounds, otherwise the outline self-intersects. Both the source and the
     * interpolated radius are clamped at draw time.
     */
    fun radiusAt(sourceRadius: Float, targetRadius: Float, fraction: Float, rect: Rect): Float {
        val raw = lerp(sourceRadius, targetRadius, fraction)
        val limit = min(rect.width, rect.height) / 2f
        return if (limit <= 0f) 0f else raw.coerceIn(0f, limit)
    }

    /**
     * Colour interpolation in sRGB, matching Compose's own `Color` lerp for the
     * opaque surfaces used here (all container colours are fully opaque).
     */
    fun colorAt(source: Color, target: Color, fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        return Color(
            red = lerp(source.red, target.red, f),
            green = lerp(source.green, target.green, f),
            blue = lerp(source.blue, target.blue, f),
            alpha = lerp(source.alpha, target.alpha, f),
        )
    }

    /**
     * How much of the menu panel's start rect lies inside the popup window.
     *
     * The popup is its own window sized to the panel, so nothing may be painted
     * outside it: the morph starts from the part of the source control that the
     * window can show (the edge nearest the panel) and grows to the full panel.
     * That intersection is what makes the panel visibly "come out of" the
     * button instead of merely scaling in place from a distant corner.
     */
    fun clampedSourceRect(anchor: Rect, window: Rect): Rect {
        if (window.width <= 0f || window.height <= 0f) return window
        val left = anchor.left.coerceIn(window.left, window.right)
        val top = anchor.top.coerceIn(window.top, window.bottom)
        val right = anchor.right.coerceIn(window.left, window.right)
        val bottom = anchor.bottom.coerceIn(window.top, window.bottom)
        // A degenerate intersection (anchor entirely outside the window) still
        // needs a visible seed, so fall back to the nearest edge strip.
        if (right - left <= 0.5f || bottom - top <= 0.5f) {
            val seedWidth = min(anchor.width, window.width)
            val seedHeight = min(anchor.height, window.height)
            val seedLeft = (anchor.center.x - seedWidth / 2f).coerceIn(window.left, window.right - seedWidth)
            val seedTop = (anchor.center.y - seedHeight / 2f).coerceIn(window.top, window.bottom - seedHeight)
            return Rect(seedLeft, seedTop, seedLeft + seedWidth, seedTop + seedHeight)
        }
        return Rect(left, top, right, bottom)
    }

    /**
     * Remaining duration for an animation that must keep a constant speed when
     * interrupted at [progress] (an interrupted close re-opens at the same rate
     * instead of snapping or crawling). Never returns 0 so the caller can still
     * animate a single frame.
     */
    fun remainingMillis(totalMillis: Int, progress: Float): Int {
        val remaining = totalMillis * (1f - progress.coerceIn(0f, 1f))
        return remaining.roundToInt().coerceIn(1, totalMillis)
    }

    /** Menu open/close clock for the given style (context menus use 200ms). */
    fun menuDurationMillis(contextMenu: Boolean, closing: Boolean): Int = when {
        contextMenu -> MenuContextMillis
        closing -> MenuCloseMillis
        else -> MenuOpenMillis
    }
}
