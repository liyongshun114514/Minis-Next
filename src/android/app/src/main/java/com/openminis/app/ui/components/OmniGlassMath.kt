/*
 * Minis Next — Omni liquid glass.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Provenance: this file translates the *optics* of the FlClash liquid-glass
 * surface (bevel profile, rim line, chromatic parting, saturation/brightness
 * handling) as reached through omnimind-ai/OmniBot commit
 * c4728736964245509404670ee992d3b658efaecd
 * (ui/lib/widgets/flclash/liquid_glass.dart, ui/shaders/liquid_glass.frag),
 * whose FlClash-derived parts are GPLv3 — upstream chen08209/FlClash
 * 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7, see OmniBot's
 * docs/third-party/flclash-NOTICE.md. Re-expressed as Kotlin float math for
 * Compose. No OmniBot AGPL-only code, business runtime, font or brand asset is
 * copied.
 */
package com.openminis.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Capture/refresh profiles for [OmniGlassScope].
 *
 * A glass surface can only show what lies behind it, so the scope renders its
 * own content once more into a downscaled bitmap whenever the backdrop may
 * have changed. Every profile here is a hard cost ceiling, not a hint:
 *
 * * [capturePixelBudget] caps the bitmap the shader samples (and therefore the
 *   memory held while the scope lives),
 * * [maxCaptureScale] caps the bitmap's linear resolution,
 * * [refreshIntervalMs] caps how often the extra render may happen; when the
 *   UI is idle no frame is drawn at all, so the glass costs nothing then.
 */
enum class OmniGlassQuality(
    internal val capturePixelBudget: Int,
    internal val refreshIntervalMs: Long,
    internal val maxCaptureScale: Float,
) {
    /** One capture per layout/geometry change plus a slow refresh. Cheapest. */
    Static(capturePixelBudget = 320_000, refreshIntervalMs = 1_500L, maxCaptureScale = 0.5f),

    /** Default: good refraction detail at well under one extra frame in ten. */
    Balanced(capturePixelBudget = 1_000_000, refreshIntervalMs = 250L, maxCaptureScale = 0.75f),

    /** Motion profile: refreshed on active draw frames, capped at 30Hz and
     * 480k pixels. No timer/continuous capture is started while the UI is idle.
     * It is a real profile, not an alias of the slow static-quality setting. */
    Live(capturePixelBudget = 480_000, refreshIntervalMs = 33L, maxCaptureScale = 0.5f),
    ;
}

/** Fully resolved (pixel) optics of one glass pane. Pure data: JVM testable. */
internal class OmniGlassOptics(
    /** Signed refraction depth in px; negative renders a raised lens. */
    val refractionPx: Float,
    /** Width of the refractive bevel band in px. */
    val bevelPx: Float,
    /** Frost sigma in px applied to the sampled backdrop. */
    val blurPx: Float,
    val saturation: Float,
    val brightness: Float,
    val tintRed: Float,
    val tintGreen: Float,
    val tintBlue: Float,
    val tintAlpha: Float,
    /** Rim highlight strength, 0..1. */
    val highlight: Float,
    /** Width in px over which the rim highlight falls off. */
    val highlightBandPx: Float,
    /** Dark hairline strength just inside the rim, 0..1. */
    val lineAlpha: Float,
    /** Chromatic parting in px between the red and blue channel samples. */
    val chromaPx: Float,
    /** Whole-pane opacity, 0..1. */
    val alpha: Float = 1f,
) {
    internal fun copy(
        refractionPx: Float = this.refractionPx,
        bevelPx: Float = this.bevelPx,
        blurPx: Float = this.blurPx,
        saturation: Float = this.saturation,
        brightness: Float = this.brightness,
        tintRed: Float = this.tintRed,
        tintGreen: Float = this.tintGreen,
        tintBlue: Float = this.tintBlue,
        tintAlpha: Float = this.tintAlpha,
        highlight: Float = this.highlight,
        highlightBandPx: Float = this.highlightBandPx,
        lineAlpha: Float = this.lineAlpha,
        chromaPx: Float = this.chromaPx,
        alpha: Float = this.alpha,
    ): OmniGlassOptics = OmniGlassOptics(
        refractionPx = refractionPx,
        bevelPx = bevelPx,
        blurPx = blurPx,
        saturation = saturation,
        brightness = brightness,
        tintRed = tintRed,
        tintGreen = tintGreen,
        tintBlue = tintBlue,
        tintAlpha = tintAlpha,
        highlight = highlight,
        highlightBandPx = highlightBandPx,
        lineAlpha = lineAlpha,
        chromaPx = chromaPx,
        alpha = alpha,
    )
}

/** Corner radii of one pane in px, in the order the shader uses: TL, TR, BR, BL. */
internal class OmniGlassCorners(val tl: Float, val tr: Float, val br: Float, val bl: Float) {
    internal fun at(x: Float, y: Float): Float = when {
        x < 0f && y < 0f -> tl
        x >= 0f && y < 0f -> tr
        x >= 0f -> br
        else -> bl
    }

    internal fun toFloatArray(): FloatArray = floatArrayOf(tl, tr, br, bl)

    override fun toString(): String = "OmniGlassCorners($tl, $tr, $br, $bl)"
}

/** Distance from the rim plus the outward normal of the pane at one point. */
internal class OmniGlassRimSample(val inset: Float, val normalX: Float, val normalY: Float)

/**
 * The geometry shared by the path builder ([OmniSuperellipseShape]), the AGSL
 * shader and the API 26–32 mesh renderer. One definition, so the three cannot
 * disagree about where the rim is.
 */
internal object OmniGlassGeometryMath {
    /** Below this the corner is drawn with a circular profile. */
    const val MIN_EXPONENT = 2f
    const val MAX_EXPONENT = 16f
    const val DEFAULT_EXPONENT = 5f

    /** A degenerate pane never produces NaN/Inf: it is simply not drawn. */
    const val MIN_DRAWABLE_SIDE_PX = 0.5f

    internal fun clampExponent(exponent: Float): Float =
        if (exponent.isFinite()) exponent.coerceIn(MIN_EXPONENT, MAX_EXPONENT) else DEFAULT_EXPONENT

    internal fun clampRadius(radius: Float, halfWidth: Float, halfHeight: Float): Float {
        val limit = min(halfWidth, halfHeight)
        if (!radius.isFinite() || radius <= 0f || limit <= 0f) return 0f
        return min(radius, limit)
    }

    internal fun corners(
        tl: Float,
        tr: Float,
        br: Float,
        bl: Float,
        width: Float,
        height: Float,
    ): OmniGlassCorners {
        val hw = width * 0.5f
        val hh = height * 0.5f
        return OmniGlassCorners(
            tl = clampRadius(tl, hw, hh),
            tr = clampRadius(tr, hw, hh),
            br = clampRadius(br, hw, hh),
            bl = clampRadius(bl, hw, hh),
        )
    }

    /**
     * Signed distance-ish measure: [OmniGlassRimSample.inset] is `0` exactly on
     * the rim, positive inside and negative outside; the normal points outward.
     *
     * The corner octant is the superellipse `|u|^n + |v|^n = 1` measured in a
     * frame of one corner radius, which is what makes this a squircle and not a
     * rounded rectangle; along the straight sides it reduces to the distance to
     * the nearer edge, and the two agree on their shared boundary.
     */
    internal fun sample(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        corners: OmniGlassCorners,
        exponent: Float,
    ): OmniGlassRimSample {
        val hw = width * 0.5f
        val hh = height * 0.5f
        if (!(hw > 0f) || !(hh > 0f) || !x.isFinite() || !y.isFinite()) {
            return OmniGlassRimSample(0f, 0f, 0f)
        }
        val ax = abs(x - hw)
        val ay = abs(y - hh)
        val sx = if (x < hw) -1f else 1f
        val sy = if (y < hh) -1f else 1f
        val r = clampRadius(corners.at(x - hw, y - hh), hw, hh)
        val cx = hw - r
        val cy = hh - r
        if (r > 0f && ax > cx && ay > cy) {
            val n = clampExponent(exponent)
            val u = (ax - cx) / r
            val v = (ay - cy) / r
            val un = u.pow(n)
            val vn = v.pow(n)
            val norm = (un + vn).pow(1f / n)
            // d(norm)/du = norm^(1-n) * u^(n-1); the 1/r factor of u,v cancels
            // against the radius in the distance estimate.
            val safeNorm = max(norm, 1e-4f)
            val gx = safeNorm.pow(1f - n) * u.pow(n - 1f)
            val gy = safeNorm.pow(1f - n) * v.pow(n - 1f)
            val gl = sqrt(gx * gx + gy * gy)
            if (!(gl > 1e-6f)) {
                // Dead centre of the corner frame: deep inside, no useful
                // normal. Report it as far from the rim so nothing shifts.
                return OmniGlassRimSample(r * 4f + hw, 0f, -1f)
            }
            return OmniGlassRimSample(
                inset = r * (1f - norm) / gl,
                normalX = sx * gx / gl,
                normalY = sy * gy / gl,
            )
        }
        val dx = hw - ax
        val dy = hh - ay
        return if (dx < dy) {
            OmniGlassRimSample(dx, sx, 0f)
        } else {
            OmniGlassRimSample(dy, 0f, sy)
        }
    }

    /**
     * Where to read the backdrop for a point at [inset] from the rim: the
     * refraction pulls content inward across the bevel by the circular-segment
     * profile `depth * (1 - sqrt(1 - rise^2))`, which is 0 at the bevel's inner
     * edge and the full depth at the rim. A negative depth mirrors it, which
     * reads as a raised lens.
     */
    internal fun refractionShift(
        inset: Float,
        normalX: Float,
        normalY: Float,
        bevelPx: Float,
        depthPx: Float,
    ): Offset {
        if (!depthPx.isFinite() || depthPx == 0f) return Offset.Zero
        val bevel = max(bevelPx, 0.5f)
        if (inset >= bevel) return Offset.Zero
        val rise = 1f - (inset / bevel).coerceIn(0f, 1f)
        val profile = 1f - sqrt(max(1f - rise * rise, 0f))
        val shift = depthPx * profile
        if (!shift.isFinite()) return Offset.Zero
        return Offset(-normalX * shift, -normalY * shift)
    }

    /** Chromatic parting: red is read one way along the normal, blue the other. */
    internal fun chromaOffset(
        inset: Float,
        normalX: Float,
        normalY: Float,
        chromaPx: Float,
        bandPx: Float,
    ): Offset {
        if (chromaPx <= 0f || !chromaPx.isFinite()) return Offset.Zero
        val band = max(bandPx, 0.5f)
        if (inset >= band) return Offset.Zero
        val falloff = 1f - (inset / band).coerceIn(0f, 1f)
        val part = chromaPx * 0.5f * falloff
        return Offset(normalX * part, normalY * part)
    }
}

/** Capture-side maths: how big the backdrop bitmap is and where a pane reads it. */
internal object OmniGlassCaptureMath {
    /** Never let the capture collapse to a thumbnail; refraction needs detail. */
    const val MIN_CAPTURE_SCALE = 0.2f

    /** A capture bitmap smaller than this on either side is useless. */
    const val MIN_CAPTURE_SIDE = 2

    internal fun isDrawable(size: Size): Boolean =
        size.width.isFinite() && size.height.isFinite() &&
            size.width >= OmniGlassGeometryMath.MIN_DRAWABLE_SIDE_PX &&
            size.height >= OmniGlassGeometryMath.MIN_DRAWABLE_SIDE_PX

    /**
     * Linear scale from scope px to capture px: the smaller of the profile's
     * resolution cap and the value that keeps the bitmap inside the profile's
     * pixel budget.
     */
    internal fun captureScale(sizePx: Size, budget: Int, maxScale: Float): Float {
        if (!isDrawable(sizePx)) return MIN_CAPTURE_SCALE
        val cap = maxScale.coerceIn(MIN_CAPTURE_SCALE, 1f)
        val area = sizePx.width * sizePx.height
        if (!(area > 0f) || budget <= 0) return MIN_CAPTURE_SCALE
        val byBudget = sqrt(budget.toFloat() / area)
        return byBudget.coerceIn(MIN_CAPTURE_SCALE, cap)
    }

    /** Bitmap size for one capture; always at least [MIN_CAPTURE_SIDE] px. */
    internal fun captureSize(sizePx: Size, scale: Float): IntSize {
        if (!isDrawable(sizePx)) return IntSize(0, 0)
        val s = if (scale.isFinite()) scale.coerceIn(MIN_CAPTURE_SCALE, 1f) else MIN_CAPTURE_SCALE
        val w = (sizePx.width * s).toInt().coerceAtLeast(MIN_CAPTURE_SIDE)
        val h = (sizePx.height * s).toInt().coerceAtLeast(MIN_CAPTURE_SIDE)
        return IntSize(w, h)
    }

    /**
     * The scale to apply to the scope's content while it is recorded into the
     * capture layer.
     *
     * The layer is `captureScale ×` the scope, so the content must be scaled
     * **by** [captureScale] to fit it — never by its reciprocal. A marker 100 px
     * from the scope origin lands 50 px into a 0.5 capture, which is exactly what
     * [toCapture] then reads back.
     */
    internal fun contentScaleFor(captureScale: Float): Float =
        if (captureScale.isFinite()) captureScale.coerceIn(MIN_CAPTURE_SCALE, 1f) else 1f

    /**
     * Where a scope-local point lands in the capture bitmap. This is the other
     * half of [contentScaleFor]: the draw transform and the shader's `uMap` must
     * be the same mapping, and `OmniGlassCaptureTest` rasterises a marker to
     * prove it on actual pixels.
     */
    internal fun capturePixelFor(point: Offset, origin: Offset, captureScale: Float): Offset {
        val scale = contentScaleFor(captureScale)
        val x = (point.x + origin.x) * scale
        val y = (point.y + origin.y) * scale
        return Offset(if (x.isFinite()) x else 0f, if (y.isFinite()) y else 0f)
    }

    /** Converts a point in a pane's local px into capture bitmap px. */
    internal fun toCapture(point: Offset, origin: Offset, scale: Float): Offset =
        capturePixelFor(point, origin, scale)

    /**
     * Whether the scope should render a fresh capture for this frame.
     *
     * Deliberately a pure function of the state so the loop guard can be
     * asserted: no capture while a capture is running (that is how a glass
     * surface would end up sampling itself), none when nothing is attached,
     * none when the frame is too small or the feature is off, and at most one
     * per [intervalMs] unless a geometry change asked for it immediately.
     */
    internal fun shouldCapture(
        enabled: Boolean,
        capturing: Boolean,
        attachedSurfaces: Int,
        sizePx: Size,
        nowMillis: Long,
        lastCaptureMillis: Long,
        intervalMs: Long,
        geometryChanged: Boolean,
    ): Boolean {
        if (!enabled || capturing) return false
        if (attachedSurfaces <= 0) return false
        if (!isDrawable(sizePx)) return false
        if (geometryChanged) return true
        if (lastCaptureMillis <= 0L) return true
        val interval = intervalMs.coerceAtLeast(0L)
        return nowMillis - lastCaptureMillis >= interval
    }
}
