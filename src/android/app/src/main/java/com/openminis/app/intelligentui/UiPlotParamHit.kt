package com.openminis.app.intelligentui

import kotlin.math.max
import kotlin.math.min

/**
 * One evaluated sample of a parametric / polar curve, keeping the t (or θ) that produced it.
 *
 * A parametric curve is not a function of x: a circle passes over the same screen column
 * twice and a polar circle puts θ = π on the left edge and θ = π/2 at the top. Mapping the
 * horizontal tap position back to t therefore jumps to a point that is nowhere near the
 * finger. Keeping t next to every painted sample lets the gesture pass resolve the tap to
 * the point that is actually under the finger.
 */
internal data class ParamSample(val t: Double, val x: Double, val y: Double)

/** Painted polylines of a parametric / polar curve; breaks at domain holes are separate segments. */
internal data class ParamSeries(val segments: List<List<ParamSample>>, val error: String?)

/**
 * Nearest-point search in screen pixels between a touch position and the polyline the plot
 * actually paints. The returned value is the real t of the hit, interpolated along the hit
 * segment — never a mapping from the horizontal position.
 *
 * Only segments with a visible part take part, so a curve that leaves the viewport (or a
 * branch behind a domain break) can never win over the pixels the user can see.
 */
internal object UiPlotParamHit {
    /** Slack around the plot rectangle that still counts as "visible" (anti-aliased edge). */
    private const val VISIBLE_SLACK_PX = 1f

    /**
     * @param tapX/tapY pointer position in node pixels (same space as the draw pass).
     * @return the parameter of the closest visible point, or null when no segment is visible.
     */
    fun nearestT(
        segments: List<List<ParamSample>>,
        tapX: Float,
        tapY: Float,
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        rect: PlotRectPx,
    ): Double? {
        if (rect.isEmpty) return null
        val left = rect.left - VISIBLE_SLACK_PX
        val right = rect.right + VISIBLE_SLACK_PX
        val top = rect.top - VISIBLE_SLACK_PX
        val bottom = rect.bottom + VISIBLE_SLACK_PX
        var bestDistanceSq = Float.MAX_VALUE
        var bestT: Double? = null
        for (segment in segments) {
            if (segment.size < 2) continue
            for (i in 0 until segment.size - 1) {
                val a = segment[i]
                val b = segment[i + 1]
                if (!a.t.isFinite() || !b.t.isFinite()) continue
                val ax = UiPlotGeometry.screenX(a.x, xMin, xMax, rect, clamp = false)
                val ay = UiPlotGeometry.screenY(a.y, yMin, yMax, rect, clamp = false)
                val bx = UiPlotGeometry.screenX(b.x, xMin, xMax, rect, clamp = false)
                val by = UiPlotGeometry.screenY(b.y, yMin, yMax, rect, clamp = false)
                if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite()) continue
                // Reject primitives whose bounding box misses the painted rectangle: an
                // off-screen branch must not attract a tap that lands on a visible one.
                if (max(ax, bx) < left || min(ax, bx) > right) continue
                if (max(ay, by) < top || min(ay, by) > bottom) continue
                val dx = bx - ax
                val dy = by - ay
                val lengthSq = dx * dx + dy * dy
                val u = if (lengthSq <= 1e-12f) {
                    0f
                } else {
                    (((tapX - ax) * dx + (tapY - ay) * dy) / lengthSq).coerceIn(0f, 1f)
                }
                val projectionX = ax + dx * u
                val projectionY = ay + dy * u
                val offsetX = tapX - projectionX
                val offsetY = tapY - projectionY
                val distanceSq = offsetX * offsetX + offsetY * offsetY
                if (distanceSq < bestDistanceSq) {
                    bestDistanceSq = distanceSq
                    bestT = a.t + (b.t - a.t) * u
                }
            }
        }
        return bestT
    }
}
