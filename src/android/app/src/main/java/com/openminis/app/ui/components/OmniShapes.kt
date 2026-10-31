/*
 * Minis Next — Omni liquid glass shapes.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Provenance: the rounded-superellipse silhouette (a superellipse corner
 * octant joined to straight sides, rather than a circular rounded rectangle)
 * follows the geometry FlClash draws its glass panes with, reached through
 * omnimind-ai/OmniBot commit c4728736964245509404670ee992d3b658efaecd
 * (ui/lib/widgets/flclash/liquid_glass.dart, "LiquidGlassCorners", and
 * ui/shaders/liquid_glass.frag "octantInset"), whose FlClash-derived parts are
 * GPLv3 — upstream chen08209/FlClash
 * 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7, see OmniBot's
 * docs/third-party/flclash-NOTICE.md. The Path construction, the cubic fitting
 * and the Kotlin/Compose API are this project's own; no OmniBot Dart source,
 * brand asset or AGPL-only code is copied.
 */
package com.openminis.app.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One drawing instruction of a generated outline. Pure data, JVM testable. */
internal sealed class OmniPathOp {
    internal data class MoveTo(val x: Float, val y: Float) : OmniPathOp()
    internal data class LineTo(val x: Float, val y: Float) : OmniPathOp()
    internal data class CubicTo(
        val c1x: Float,
        val c1y: Float,
        val c2x: Float,
        val c2y: Float,
        val x: Float,
        val y: Float,
    ) : OmniPathOp()

    internal data object Close : OmniPathOp()
}

/**
 * The actual superellipse path.
 *
 * A rounded rectangle replaces its corner with a quarter circle, i.e. the curve
 * `u² + v² = 1`. Here the corner is the quarter of `|u|ⁿ + |v|ⁿ = 1` scaled to
 * the corner radius, so at 45° the curve sits at `2^(-1/n)·r` from the corner
 * centre instead of `0.707·r`: for the default exponent 5 that is 0.871·r, a
 * visibly fuller corner with a zero-curvature joint with the straight sides.
 * `n = 2` degenerates to the circular rounded rectangle, which is what the unit
 * tests pin down on both sides of the identity.
 */
internal object OmniSuperellipseOutline {
    /** Segments per corner; four keep the fit inside 0.1 % of the radius. */
    const val SEGMENTS_PER_CORNER = 4

    /** Corner point on `|u|ⁿ + |v|ⁿ = 1`, traversed from (0,1) to (1,0). */
    internal fun cornerPoint(theta: Float, exponent: Float): FloatArray {
        val n = OmniGlassGeometryMath.clampExponent(exponent)
        val e = 2f / n
        return floatArrayOf(
            max(cos(theta), 0f).pow(e),
            max(sin(theta), 0f).pow(e),
        )
    }

    /**
     * Unit tangent of the corner in traversal order (θ decreasing), written in a
     * form that stays finite at both ends:
     * `sin^(2-2/n)θ` and `-cos^(2-2/n)θ`.
     */
    internal fun cornerTangent(theta: Float, exponent: Float): FloatArray {
        val n = OmniGlassGeometryMath.clampExponent(exponent)
        val e = 2f - 2f / n
        var x = max(sin(theta), 0f).pow(e)
        var y = -max(cos(theta), 0f).pow(e)
        val len = sqrt(x * x + y * y)
        if (!(len > 1e-6f)) {
            // Exactly at an end of the quarter: fall back to the analytic limit.
            return if (cos(theta) >= sin(theta)) floatArrayOf(1f, 0f) else floatArrayOf(0f, -1f)
        }
        x /= len
        y /= len
        return floatArrayOf(x, y)
    }

    /**
     * Cubic segments of one corner in the unit frame, from the junction with the
     * horizontal side `(0,1)` to the junction with the vertical side `(1,0)`.
     * Each segment is `[p0x,p0y,c1x,c1y,c2x,c2y,p3x,p3y]`.
     *
     * The fit keeps the exact end points and end tangents and interpolates the
     * true mid-point, solving for the two handle lengths; the unit tests measure
     * the residual against the implicit equation instead of trusting it.
     */
    internal fun cornerCubics(
        exponent: Float,
        segments: Int = SEGMENTS_PER_CORNER,
    ): List<FloatArray> {
        val n = OmniGlassGeometryMath.clampExponent(exponent)
        // A high exponent approaches a square corner, where the parameterisation
        // is most uneven: spend a few more segments rather than lose accuracy.
        val requested = segments.coerceIn(1, 12)
        val count = (if (n >= 8f) requested + 4 else requested).coerceAtMost(12)
        val half = PI.toFloat() * 0.5f
        // Equal-arc-length split: uniform θ would put most of the corner into the
        // first and last segment, which makes the handles lopsided.
        val steps = 96
        val lengths = FloatArray(steps + 1)
        var total = 0f
        var previous = cornerPoint(half, n)
        for (i in 1..steps) {
            val theta = half * (1f - i.toFloat() / steps)
            val point = cornerPoint(theta, n)
            total += sqrt(
                (point[0] - previous[0]) * (point[0] - previous[0]) +
                    (point[1] - previous[1]) * (point[1] - previous[1]),
            )
            lengths[i] = total
            previous = point
        }
        fun thetaAt(distance: Float): Float {
            if (total <= 0f) return half
            val target = distance.coerceIn(0f, total)
            var low = 0
            var high = steps
            while (low < high) {
                val mid = (low + high) / 2
                if (lengths[mid] < target) low = mid + 1 else high = mid
            }
            val index = low
            val before = if (index == 0) 0f else lengths[index - 1]
            val span = lengths[index] - before
            val fraction = if (span > 1e-6f) {
                ((target - before) / span).coerceIn(0f, 1f)
            } else {
                0f
            }
            if (index == 0) return half
            val lowerTheta = half * (1f - (index - 1).toFloat() / steps)
            val upperTheta = half * (1f - index.toFloat() / steps)
            return lowerTheta + (upperTheta - lowerTheta) * fraction
        }

        val result = ArrayList<FloatArray>(count)
        for (i in 0 until count) {
            val ta = thetaAt(total * (i.toFloat() / count))
            val tb = thetaAt(total * ((i + 1).toFloat() / count))
            val p0 = cornerPoint(ta, n)
            val p3 = cornerPoint(tb, n)
            val mid = cornerPoint((ta + tb) * 0.5f, n)
            val d0 = cornerTangent(ta, n)
            val d3 = cornerTangent(tb, n)
            val chord = sqrt(
                (p3[0] - p0[0]) * (p3[0] - p0[0]) + (p3[1] - p0[1]) * (p3[1] - p0[1]),
            )
            // 3/8 * (a*d0 - b*d3) = mid - (p0 + p3)/2
            val rx = 8f / 3f * (mid[0] - (p0[0] + p3[0]) * 0.5f)
            val ry = 8f / 3f * (mid[1] - (p0[1] + p3[1]) * 0.5f)
            val det = -d0[0] * d3[1] + d3[0] * d0[1]
            var a: Float
            var b: Float
            if (abs(det) > 1e-4f) {
                a = (-rx * d3[1] + d3[0] * ry) / det
                b = (d0[0] * ry - d0[1] * rx) / det
            } else {
                a = chord / 3f
                b = chord / 3f
            }
            val limit = max(chord * 3f, 1e-4f)
            if (!a.isFinite() || a < 0f) a = chord / 3f
            if (!b.isFinite() || b < 0f) b = chord / 3f
            a = a.coerceIn(0f, limit)
            b = b.coerceIn(0f, limit)
            result.add(
                floatArrayOf(
                    p0[0], p0[1],
                    p0[0] + a * d0[0], p0[1] + a * d0[1],
                    p3[0] - b * d3[0], p3[1] - b * d3[1],
                    p3[0], p3[1],
                ),
            )
        }
        return result
    }

    /**
     * Closed outline of a width×height pane with the four radius corners and the
     * superellipse exponent. Returns an empty list for a degenerate size.
     */
    internal fun outline(
        width: Float,
        height: Float,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        exponent: Float,
    ): List<OmniPathOp> {
        if (!(width > 0f) || !(height > 0f) || !width.isFinite() || !height.isFinite()) {
            return emptyList()
        }
        val corners = OmniGlassGeometryMath.corners(
            topStart, topEnd, bottomEnd, bottomStart, width, height,
        )
        val n = OmniGlassGeometryMath.clampExponent(exponent)
        val tl = corners.tl
        val tr = corners.tr
        val br = corners.br
        val bl = corners.bl
        val ops = ArrayList<OmniPathOp>(32)

        fun corner(cx: Float, cy: Float, r: Float, sx: Float, sy: Float, reversed: Boolean) {
            if (!(r > 0.01f)) {
                // Sharp corner: both side junctions are the same point.
                ops.add(OmniPathOp.LineTo(cx, cy))
                return
            }
            val segments = cornerCubics(n, SEGMENTS_PER_CORNER)
            val ordered = if (reversed) segments.asReversed() else segments
            for (seg in ordered) {
                val p0x = if (reversed) seg[6] else seg[0]
                val p0y = if (reversed) seg[7] else seg[1]
                val c1x = if (reversed) seg[4] else seg[2]
                val c1y = if (reversed) seg[5] else seg[3]
                val c2x = if (reversed) seg[2] else seg[4]
                val c2y = if (reversed) seg[3] else seg[5]
                val p3x = if (reversed) seg[0] else seg[6]
                val p3y = if (reversed) seg[1] else seg[7]
                ops.add(
                    OmniPathOp.CubicTo(
                        cx + sx * r * c1x, cy + sy * r * c1y,
                        cx + sx * r * c2x, cy + sy * r * c2y,
                        cx + sx * r * p3x, cy + sy * r * p3y,
                    ),
                )
            }
        }

        // Clockwise from the top-left corner's junction with the top side.
        ops.add(OmniPathOp.MoveTo(tl, 0f))
        ops.add(OmniPathOp.LineTo(width - tr, 0f))
        corner(width - tr, tr, tr, 1f, -1f, reversed = false)
        ops.add(OmniPathOp.LineTo(width, height - br))
        corner(width - br, height - br, br, 1f, 1f, reversed = true)
        ops.add(OmniPathOp.LineTo(bl, height))
        corner(bl, height - bl, bl, -1f, 1f, reversed = false)
        ops.add(OmniPathOp.LineTo(0f, tl))
        corner(tl, tl, tl, -1f, -1f, reversed = true)
        ops.add(OmniPathOp.Close)
        return ops
    }

    /** Appends [ops] to a Compose [Path]. */
    internal fun appendTo(path: Path, ops: List<OmniPathOp>) {
        for (op in ops) {
            when (op) {
                is OmniPathOp.MoveTo -> path.moveTo(op.x, op.y)
                is OmniPathOp.LineTo -> path.lineTo(op.x, op.y)
                is OmniPathOp.CubicTo -> path.cubicTo(op.c1x, op.c1y, op.c2x, op.c2y, op.x, op.y)
                OmniPathOp.Close -> path.close()
            }
        }
    }

    /** A [Path] for the same geometry, or null for a degenerate size. */
    internal fun path(
        width: Float,
        height: Float,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        exponent: Float,
    ): Path? {
        val ops = outline(width, height, topStart, topEnd, bottomEnd, bottomStart, exponent)
        if (ops.isEmpty()) return null
        return Path().also { appendTo(it, ops) }
    }
}

/** Corner radii of a pane as resolved for [Shape]s the glass renderer understands. */
internal class OmniGlassShapeGeometry(
    val corners: FloatArray,
    val exponent: Float,
    /** False when the [Shape] was approximated by a rounded rectangle + clip. */
    val exact: Boolean,
)

/**
 * The superellipse silhouette as a Compose [Shape].
 *
 * Radii are clamped to half the smaller side, and `topStart`/`topEnd` follow
 * [LayoutDirection] like every other Compose shape.
 */
data class OmniSuperellipseShape(
    val all: Dp? = 24.dp,
    val topStart: Dp = all ?: 0.dp,
    val topEnd: Dp = all ?: 0.dp,
    val bottomEnd: Dp = all ?: 0.dp,
    val bottomStart: Dp = all ?: 0.dp,
    val exponent: Float = OmniGlassGeometryMath.DEFAULT_EXPONENT,
) : Shape {
    constructor(radius: Dp, exponent: Float = OmniGlassGeometryMath.DEFAULT_EXPONENT) : this(
        all = radius,
        topStart = radius,
        topEnd = radius,
        bottomEnd = radius,
        bottomStart = radius,
        exponent = exponent,
    )

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (!(size.width > 0f) || !(size.height > 0f)) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        val rtl = layoutDirection == LayoutDirection.Rtl
        val start = with(density) { (if (rtl) topEnd else topStart).toPx() }
        val end = with(density) { (if (rtl) topStart else topEnd).toPx() }
        val bottomLeft = with(density) { (if (rtl) bottomEnd else bottomStart).toPx() }
        val bottomRight = with(density) { (if (rtl) bottomStart else bottomEnd).toPx() }
        val path = OmniSuperellipseOutline.path(
            width = size.width,
            height = size.height,
            topStart = start,
            topEnd = end,
            bottomEnd = bottomRight,
            bottomStart = bottomLeft,
            exponent = exponent,
        ) ?: return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        return Outline.Generic(path)
    }
}

/** Factories for the glass silhouettes. */
object OmniShapes {
    /** OmniBot/FlClash default: 22 dp superellipse corners, exponent 5. */
    val Superellipse: Shape = OmniSuperellipseShape(22.dp, OmniGlassGeometryMath.DEFAULT_EXPONENT)

    /** A capsule is a superellipse whose radii reach half the short side. */
    val Capsule: Shape = OmniSuperellipseShape(1_000.dp, OmniGlassGeometryMath.DEFAULT_EXPONENT)

    fun superellipse(
        radius: Dp = 22.dp,
        exponent: Float = OmniGlassGeometryMath.DEFAULT_EXPONENT,
    ): Shape = OmniSuperellipseShape(radius, exponent)

    fun superellipse(
        topStart: Dp,
        topEnd: Dp,
        bottomEnd: Dp,
        bottomStart: Dp,
        exponent: Float = OmniGlassGeometryMath.DEFAULT_EXPONENT,
    ): Shape = OmniSuperellipseShape(
        all = null,
        topStart = topStart,
        topEnd = topEnd,
        bottomEnd = bottomEnd,
        bottomStart = bottomStart,
        exponent = exponent,
    )
}

/**
 * Turns a [Shape] into the parameters the shader needs.
 *
 * [OmniSuperellipseShape] is exact. A [RoundedCornerShape] is exact too, as the
 * `n = 2` member of the same family. Anything else is clipped to its real
 * outline but its optics follow a sharp-cornered box, which callers avoid by
 * passing a superellipse shape.
 */
internal object OmniGlassShapeResolver {
    internal fun resolve(
        shape: Shape,
        size: Size,
        density: Density,
        width: Float,
        height: Float,
    ): OmniGlassShapeGeometry {
        val hw = width * 0.5f
        val hh = height * 0.5f
        return when (shape) {
            is OmniSuperellipseShape -> OmniGlassShapeGeometry(
                corners = floatArrayOf(
                    OmniGlassGeometryMath.clampRadius(with(density) { shape.topStart.toPx() }, hw, hh),
                    OmniGlassGeometryMath.clampRadius(with(density) { shape.topEnd.toPx() }, hw, hh),
                    OmniGlassGeometryMath.clampRadius(with(density) { shape.bottomEnd.toPx() }, hw, hh),
                    OmniGlassGeometryMath.clampRadius(with(density) { shape.bottomStart.toPx() }, hw, hh),
                ),
                exponent = OmniGlassGeometryMath.clampExponent(shape.exponent),
                exact = true,
            )

            is RoundedCornerShape -> OmniGlassShapeGeometry(
                corners = floatArrayOf(
                    clampCornerSize(shape.topStart, size, density, hw, hh),
                    clampCornerSize(shape.topEnd, size, density, hw, hh),
                    clampCornerSize(shape.bottomEnd, size, density, hw, hh),
                    clampCornerSize(shape.bottomStart, size, density, hw, hh),
                ),
                exponent = 2f,
                exact = true,
            )

            CircleShape -> {
                val r = min(hw, hh)
                OmniGlassShapeGeometry(
                    corners = floatArrayOf(r, r, r, r),
                    exponent = 2f,
                    exact = true,
                )
            }

            else -> OmniGlassShapeGeometry(
                corners = floatArrayOf(0f, 0f, 0f, 0f),
                exponent = 2f,
                exact = false,
            )
        }
    }

    private fun clampCornerSize(
        corner: CornerSize,
        size: Size,
        density: Density,
        hw: Float,
        hh: Float,
    ): Float = OmniGlassGeometryMath.clampRadius(corner.toPx(size, density), hw, hh)
}
