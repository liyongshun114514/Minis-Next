package com.openminis.app.ui.chat

/**
 * [T-native-composer-slider-geometry] Single source of truth for the effort
 * slider's layout: where the pill is drawn, where every tick sits, how wide the
 * thumb is, and — crucially — how an x coordinate maps back to a level.
 *
 * The Canvas draw pass and the pointer hit-test BOTH read a [Track] built here,
 * so the dot you see and the dot you tap can never drift apart.
 *
 * Three different x spaces are kept strictly apart, because conflating them is
 * what made the thumb hang half outside the pill before:
 *
 *  - the CANVAS (`0 .. widthPx`) — the label band needs a gutter so the first
 *    and last mode names are not clipped;
 *  - the PILL (`barLeft .. barRight`) — the rounded track itself;
 *  - the TICK span (`firstTickX .. lastTickX`) — the dots the thumb can reach.
 *    It is inset from the pill's ends by `barHeightPx / 2`, i.e. by the pill's
 *    own corner radius, so a circular thumb centred on the first/last tick is
 *    fully inside the stadium shape rather than sitting on its edge.
 *
 * Labels are centred on `tickX(i)` — the same value the pointer handler snaps
 * to — and the ticks are evenly spaced, so every dot and its name line up and
 * every gap is identical.
 *
 * Pure Kotlin on purpose: no Compose, no Android, so the geometry is exercised
 * by plain JVM unit tests (see ThinkingSliderGeometryTest).
 */
internal object ThinkingSliderGeometry {

    /** Height of the pill itself. */
    const val TRACK_HEIGHT_DP = 30f

    /** Gap between the thumb's rim and the pill's rim (top, bottom and ends). */
    const val THUMB_INSET_DP = 4f

    /** Minimum gap between the outermost label and the canvas edge. */
    const val TRACK_EDGE_PAD_DP = 2f

    /** Vertical gap between the pill's bottom edge and the label band. */
    const val LABEL_GAP_DP = 5f

    /** Height of the band the mode names are centred in. */
    const val LABEL_BAND_DP = 17f

    /** Mode-name text size. Lowercase names keep seven of them short. */
    const val LABEL_SIZE_SP = 11f

    /** Thumb grows a little while held, so the knob still reads as "inside". */
    const val PRESSED_SCALE = 1.10f

    /** Never let long labels eat more than this share of the width. */
    const val MAX_GUTTER_FRACTION = 0.30f

    /** Hard floor for the thumb radius (never degenerate). */
    const val MIN_THUMB_RADIUS_DP = 5f

    /**
     * Immutable per-layout geometry.
     *
     * [widthPx] canonical canvas width, [barHeightPx] the pill height,
     * [gutterPx] the canvas -> pill gap, [thumbRadiusPx] the thumb radius and
     * [count] the number of discrete levels.
     */
    data class Track(
        val widthPx: Float,
        val barHeightPx: Float,
        val gutterPx: Float,
        val thumbRadiusPx: Float,
        val count: Int,
    ) {
        /** Left edge of the rounded pill. */
        val barLeft: Float get() = gutterPx

        /** Right edge of the rounded pill. */
        val barRight: Float get() = (widthPx - gutterPx).coerceAtLeast(barLeft + 2f * barHeightPx)

        /** Width of the rounded pill. */
        val barWidth: Float get() = barRight - barLeft

        /** Vertical centre of the pill, and of the thumb. */
        val cy: Float get() = barHeightPx / 2f

        /** Corner radius of the stadium. */
        val pillRadius: Float get() = barHeightPx / 2f

        /**
         * Distance from a pill end to the first/last tick centre. Equal to the
         * pill's corner radius, so the thumb clears the rounded end by
         * `pillRadius - thumbRadius == THUMB_INSET_DP`.
         */
        val endInset: Float get() = pillRadius

        /** Centre of the first (Off) dot — always inside the pill's left cap. */
        val firstTickX: Float get() = barLeft + endInset

        /** Centre of the last dot — always inside the pill's right cap. */
        val lastTickX: Float get() = (barRight - endInset).coerceAtLeast(firstTickX)

        /** Drawable span between the first and the last tick. */
        val trackWidth: Float get() = lastTickX - firstTickX

        fun pctFor(index: Int): Float = ComposerPortMath.pct(index, count)

        /** X of the dot for [index] — and therefore of its label. */
        fun tickX(index: Int): Float = firstTickX + pctFor(index) * trackWidth

        /** X for a continuous drag position in 0..1. */
        fun xForValue(value: Float): Float = firstTickX + ComposerPortMath.clamp01(value) * trackWidth

        /** Continuous 0..1 value for an x coordinate (clamped at both ends). */
        fun valueForX(x: Float): Float = ((x - firstTickX) / trackWidth).coerceIn(0f, 1f)

        /** Discrete level nearest to an x coordinate. */
        fun indexForX(x: Float): Int = ComposerPortMath.nearestIndex(valueForX(x), count)

        /** Centre of the label under [index]; identical to the dot's centre. */
        fun labelX(index: Int): Float = tickX(index)

        /**
         * Distance from a point on the pill's centre line to the stadium's
         * inner segment — 0 anywhere between the two corner centres.
         */
        fun distanceToPillCore(index: Int): Float {
            val x = tickX(index)
            val coreStart = barLeft + pillRadius
            val coreEnd = barRight - pillRadius
            return when {
                x < coreStart -> coreStart - x
                x > coreEnd -> x - coreEnd
                else -> 0f
            }
        }

        /**
         * Whether a [radiusPx] circle centred on [index]'s dot lies completely
         * inside the rounded pill (not merely inside the canvas).
         */
        fun circleInsidePill(index: Int, radiusPx: Float): Boolean =
            distanceToPillCore(index) + radiusPx <= pillRadius + 1e-3f

        /** Whether the (possibly pressed) thumb at [index] is inside the pill. */
        fun thumbInsidePill(index: Int): Boolean = circleInsidePill(index, thumbRadiusPx)

        /** Whether the thumb at [index] also stays inside the canvas. */
        fun thumbFits(index: Int): Boolean {
            val x = tickX(index)
            return x - thumbRadiusPx >= 0f && x + thumbRadiusPx <= widthPx
        }
    }

    /** Thumb radius for a [barHeightPx] tall pill: inside it, with [insetPx] air. */
    fun thumbRadiusPx(
        barHeightPx: Float,
        insetPx: Float,
        minRadiusPx: Float,
    ): Float = (barHeightPx / 2f - insetPx).coerceAtLeast(minRadiusPx)

    /**
     * Canvas gutter: far enough from the edge that half of the widest label
     * fits outside the first/last tick, minus the `endInset` the pill already
     * contributes. Narrow labels therefore let the pill use the full width,
     * while wide ones push the pill inwards instead of being clipped.
     */
    fun gutterPx(
        widthPx: Float,
        barHeightPx: Float,
        labelHalfWidthPx: Float,
        edgePadPx: Float,
    ): Float {
        val endInset = barHeightPx / 2f
        val need = (labelHalfWidthPx + edgePadPx - endInset).coerceAtLeast(0f)
        val cap = (widthPx * MAX_GUTTER_FRACTION).coerceAtLeast(0f)
        // Keep room for at least a stub of a track plus both end insets.
        val room = (widthPx / 2f - endInset - 1f).coerceAtLeast(0f)
        return need.coerceAtMost(cap).coerceAtMost(room)
    }

    /** Build the full geometry from a measured width. */
    fun track(
        widthPx: Float,
        barHeightPx: Float,
        thumbRadiusPx: Float,
        labelHalfWidthPx: Float,
        edgePadPx: Float,
        count: Int,
    ): Track {
        val safeWidth = widthPx.coerceAtLeast(1f)
        return Track(
            widthPx = safeWidth,
            barHeightPx = barHeightPx,
            gutterPx = gutterPx(
                widthPx = safeWidth,
                barHeightPx = barHeightPx,
                labelHalfWidthPx = labelHalfWidthPx,
                edgePadPx = edgePadPx,
            ),
            thumbRadiusPx = thumbRadiusPx,
            count = count.coerceAtLeast(1),
        )
    }

    /**
     * Baseline for a vertically centred label inside a [bandHeightPx] tall band
     * that starts at [bandTopPx]. Uses the paint's ascent/descent so the visual
     * centre (not the font box) lands on the band's centre.
     */
    fun labelBaseline(
        bandTopPx: Float,
        bandHeightPx: Float,
        ascent: Float,
        descent: Float,
    ): Float = bandTopPx + bandHeightPx / 2f - (ascent + descent) / 2f
}
