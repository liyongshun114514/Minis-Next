package com.openminis.app.intelligentui

/**
 * Compose-free geometry shared by the math plot drawing pass and its gesture pass.
 *
 * The draw scope and the pointer-input scope have different coordinate APIs but the
 * same pixel space. Keeping the inset/rect math here guarantees that the grid, the
 * rendered curves, the probe marker and the tap/drag mapping all use byte-identical
 * pixels; a tap on a point of a curve therefore resolves to that point's data value.
 *
 * All members are pure float/double math so they are covered by plain JVM tests.
 */
internal data class PlotInsetsPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val horizontal: Float get() = left + right
    val vertical: Float get() = top + bottom

    companion object {
        const val LEFT_DP = 38f
        const val TOP_DP = 12f
        const val RIGHT_DP = 10f
        const val BOTTOM_DP = 24f

        /** Converts the fixed dp insets with the density of the node being drawn/hit-tested. */
        fun forDensity(density: Float): PlotInsetsPx {
            val d = if (density.isFinite() && density > 0f) density else 1f
            return PlotInsetsPx(LEFT_DP * d, TOP_DP * d, RIGHT_DP * d, BOTTOM_DP * d)
        }
    }
}

internal data class PlotRectPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

internal object UiPlotGeometry {
    /** The plotting rectangle, derived once per layout size + density. Never cached across sizes. */
    fun rectFor(widthPx: Float, heightPx: Float, insets: PlotInsetsPx): PlotRectPx {
        val w = if (widthPx.isFinite()) widthPx else 0f
        val h = if (heightPx.isFinite()) heightPx else 0f
        return PlotRectPx(
            left = insets.left,
            top = insets.top,
            right = (w - insets.right).coerceAtLeast(insets.left),
            bottom = (h - insets.bottom).coerceAtLeast(insets.top),
        )
    }

    fun rectFor(widthPx: Float, heightPx: Float, density: Float): PlotRectPx =
        rectFor(widthPx, heightPx, PlotInsetsPx.forDensity(density))

    /** Normalises a pointer position into 0..1 inside [rect]; outside positions clamp to the edge. */
    fun fractionAt(x: Float, y: Float, rect: PlotRectPx): Pair<Float, Float> {
        if (rect.isEmpty) return 0.5f to 0.5f
        val fx = ((x - rect.left) / rect.width).coerceIn(0f, 1f)
        val fy = ((y - rect.top) / rect.height).coerceIn(0f, 1f)
        return fx to fy
    }

    /** Horizontal fraction -> data value (x grows to the right). */
    fun dataX(fx: Float, min: Double, max: Double): Double = min + fx.coerceIn(0f, 1f) * (max - min)

    /** Vertical fraction -> data value (y grows upwards, screen y grows downwards). */
    fun dataY(fy: Float, min: Double, max: Double): Double = max - fy.coerceIn(0f, 1f) * (max - min)

    /** Data value -> screen x, clamped to [rect]. */
    fun screenX(x: Double, min: Double, max: Double, rect: PlotRectPx, clamp: Boolean = true): Float {
        val span = max - min
        val raw = if (span == 0.0) 0.5f else ((x - min) / span).toFloat()
        val fx = if (clamp) raw.coerceIn(0f, 1f) else raw
        return rect.left + fx * rect.width
    }

    /** Data value -> screen y, clamped to [rect]. */
    fun screenY(y: Double, min: Double, max: Double, rect: PlotRectPx, clamp: Boolean = true): Float {
        val span = max - min
        val raw = if (span == 0.0) 0.5f else ((max - y) / span).toFloat()
        val fy = if (clamp) raw.coerceIn(0f, 1f) else raw
        return rect.top + fy * rect.height
    }

    /**
     * Continuous slider value for the touched fraction: the live drag mapping never snaps.
     */
    fun sliderValue(fraction: Float, min: Double, max: Double): Double =
        min + fraction.coerceIn(0f, 1f) * (max - min)

}
