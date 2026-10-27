package com.openminis.app.ui.chat

/**
 * [T-native-composer-slider-geometry] Single source of truth for the effort
 * slider's layout: where the pill is drawn, where every tick sits, how wide the
 * knob is, and — crucially — how an x coordinate maps back to a level.
 *
 * Upstream reference: `Bai-2008/dsh-better-panel` @
 * `4f798ffcecf17d52cd15aaa617124c21b9784c12`, `lib/client.js`:
 *
 *  - `KNOB_SIZE = 28`, `TRACK_HEIGHT = 20`, `TRACK_RADIUS = 10` (l.194-204)
 *  - `.ces-track{height:20px;border-radius:999px}` (l.495)
 *  - `.ces-knob{width:28px;…;margin:-14px 0 0 -14px}` (l.509)
 *  - `.ces-fill{left:0;border-radius:10px 0 0 10px}` (l.501)
 *  - tick centre = `calc(10px + pct * (100% - 20px))` (`knobOffsetOf`, l.896)
 *  - pointer: `usable = width - 2 * 10`, `pct = clamp01((x - left - 10)/usable)`
 *    (`pctAt`, l.2253)
 *
 * Consequences that the code below makes explicit:
 *
 *  - the knob (r = 14dp) is **larger** than the track (10dp radius), exactly as
 *    upstream: it floats over the pill and overhangs each rounded end by
 *    4dp at the first/last stop;
 *  - because Compose canvases clip, the canvas reserves a gutter of at least
 *    that 4dp overhang (plus the label gutter), so the knob never leaves the
 *    composable and never gets cut on a narrow screen;
 *  - the vertical slot stays 30dp (the old track height) and the 20dp pill is
 *    centred in it, so the host sheet's height and the label band do not move;
 *  - ticks and labels share one x, and the pointer maps through the SAME
 *    [Track] so what is drawn and what is hit-tested cannot drift.
 *
 * Pure Kotlin (no Compose, no Android) so plain JVM tests exercise it.
 */
internal object ThinkingSliderGeometry {

    /** Upstream `TRACK_HEIGHT`. */
    const val TRACK_HEIGHT_DP = 20f

    /** Upstream `KNOB_SIZE` (diameter, not radius). */
    const val KNOB_SIZE_DP = 28f

    /**
     * Vertical slot the 20dp pill is centred in. Kept at the old 30dp track
     * height so the sheet keeps its previous total height.
     */
    const val SLOT_HEIGHT_DP = 30f

    /** Minimum gap between the outermost label and the canvas edge. */
    const val TRACK_EDGE_PAD_DP = 2f

    /** Vertical gap between the slot's bottom edge and the label band. */
    const val LABEL_GAP_DP = 5f

    /** Height of the band the mode names are centred in. */
    const val LABEL_BAND_DP = 17f

    /** Mode-name text size. Lowercase names keep seven of them short. */
    const val LABEL_SIZE_SP = 11f

    /** Never let long labels eat more than this share of the width. */
    const val MAX_GUTTER_FRACTION = 0.30f

    /**
     * Immutable per-layout geometry.
     *
     * @param widthPx      canvas width
     * @param slotHeightPx vertical slot (30dp); the pill is centred in it
     * @param barHeightPx  pill height (20dp)
     * @param gutterPx     canvas -> pill gap, at least the 4dp knob overhang
     * @param knobRadiusPx knob radius (14dp)
     * @param count        number of discrete levels
     */
    data class Track(
        val widthPx: Float,
        val slotHeightPx: Float,
        val barHeightPx: Float,
        val gutterPx: Float,
        val knobRadiusPx: Float,
        val count: Int,
    ) {
        /** Left edge of the rounded pill. */
        val barLeft: Float get() = gutterPx

        /** Right edge of the rounded pill. */
        val barRight: Float get() = (widthPx - gutterPx).coerceAtLeast(barLeft + 2f * barHeightPx)

        /** Width of the rounded pill. */
        val barWidth: Float get() = (barRight - barLeft).coerceAtLeast(0f)

        /** Vertical centre of the pill and of the knob. */
        val cy: Float get() = slotHeightPx / 2f

        /** Top edge of the pill. */
        val barTop: Float get() = cy - barHeightPx / 2f

        /** Bottom edge of the pill. */
        val barBottom: Float get() = barTop + barHeightPx

        /** Corner radius of the stadium (= upstream `TRACK_RADIUS`). */
        val trackRadius: Float get() = barHeightPx / 2f

        /** Alias kept for the label tests/readers. */
        val pillRadius: Float get() = trackRadius

        /**
         * Horizontal distance from a pill end to the first/last stop. This is
         * the pill's own radius, so the knob centre lands on the cap's centre
         * and the fill's left cap is fully covered by the knob.
         */
        val endInset: Float get() = trackRadius

        /** Centre of the first (Off) stop. */
        val firstTickX: Float get() = barLeft + endInset

        /** Centre of the last stop. */
        val lastTickX: Float get() = (barRight - endInset).coerceAtLeast(firstTickX)

        /** Drawable span between the first and the last stop. */
        val trackWidth: Float get() = (lastTickX - firstTickX).coerceAtLeast(0f)

        /** How far the knob sticks out past the pill at an end stop. */
        val knobOverhang: Float get() = (knobRadiusPx - trackRadius).coerceAtLeast(0f)

        fun pctFor(index: Int): Float = ComposerPortMath.pct(index, count)

        /** X of the stop — and of its label and of the resting knob centre. */
        fun tickX(index: Int): Float = firstTickX + pctFor(index) * trackWidth

        /** X for a continuous fill position in 0..1. */
        fun xForValue(value: Float): Float =
            firstTickX + ComposerPortMath.clamp01(value) * trackWidth

        /** Continuous 0..1 value for an x coordinate (clamped at both ends). */
        fun valueForX(x: Float): Float {
            if (trackWidth <= 0f) return 0f
            return ((x - firstTickX) / trackWidth).coerceIn(0f, 1f)
        }

        /** Pointer position in level units: 0 = first stop, count-1 = last. */
        fun unitForX(x: Float): Float = valueForX(x) * (count - 1).coerceAtLeast(0)

        /** Nearest stop for a press ("tap where you mean"). */
        fun indexForX(x: Float): Int =
            ComposerPortMath.nearestIndex(valueForX(x), count)

        /** Detent stop for a drag (`mappingOf`). */
        fun detentIndexForX(x: Float): Int =
            ComposerPortMath.detentIndex(unitForX(x), count)

        /** Centre of the label under [index]; identical to the stop's centre. */
        fun labelX(index: Int): Float = tickX(index)

        /** Left edge of a [radiusPx] knob centred on [index]. */
        fun knobLeft(index: Int, radiusPx: Float = knobRadiusPx): Float =
            tickX(index) - radiusPx

        /** Right edge of a [radiusPx] knob centred on [index]. */
        fun knobRight(index: Int, radiusPx: Float = knobRadiusPx): Float =
            tickX(index) + radiusPx

        /** Whether the knob at [index] is fully inside the canvas. */
        fun knobFits(index: Int, radiusPx: Float = knobRadiusPx): Boolean =
            knobLeft(index, radiusPx) >= -0.01f && knobRight(index, radiusPx) <= widthPx + 0.01f
    }

    /** Knob radius from its diameter (upstream 28dp -> 14dp). */
    fun knobRadiusPx(knobSizePx: Float): Float = knobSizePx / 2f

    /**
     * Canvas gutter: at least the knob's 4dp overhang at an end stop, plus
     * enough room for half of the widest label outside the first/last stop.
     * Capped by [MAX_GUTTER_FRACTION] and by half the width so the pill keeps
     * a real (non-negative) span on a narrow screen.
     */
    fun gutterPx(
        widthPx: Float,
        barHeightPx: Float,
        knobRadiusPx: Float,
        labelHalfWidthPx: Float,
        edgePadPx: Float,
    ): Float {
        val trackRadius = barHeightPx / 2f
        val knobNeed = (knobRadiusPx - trackRadius).coerceAtLeast(0f)
        val labelNeed = (labelHalfWidthPx + edgePadPx - trackRadius).coerceAtLeast(0f)
        val need = maxOf(knobNeed, labelNeed)
        val cap = (widthPx * MAX_GUTTER_FRACTION).coerceAtLeast(0f)
        val room = (widthPx / 2f - trackRadius - 1f).coerceAtLeast(0f)
        return need.coerceAtMost(cap).coerceAtMost(room)
    }

    /** Build the full geometry from a measured width. */
    fun track(
        widthPx: Float,
        slotHeightPx: Float,
        barHeightPx: Float,
        knobRadiusPx: Float,
        labelHalfWidthPx: Float,
        edgePadPx: Float,
        count: Int,
    ): Track {
        val safeWidth = widthPx.coerceAtLeast(1f)
        return Track(
            widthPx = safeWidth,
            slotHeightPx = slotHeightPx.coerceAtLeast(barHeightPx),
            barHeightPx = barHeightPx.coerceAtLeast(1f),
            gutterPx = gutterPx(
                widthPx = safeWidth,
                barHeightPx = barHeightPx.coerceAtLeast(1f),
                knobRadiusPx = knobRadiusPx,
                labelHalfWidthPx = labelHalfWidthPx,
                edgePadPx = edgePadPx,
            ),
            knobRadiusPx = knobRadiusPx,
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
