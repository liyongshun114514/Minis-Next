package com.openminis.app.ui.chat

import kotlin.math.roundToInt

/**
 * Pure geometry / colour / particle maths for the native port of the
 * Composer effort slider.
 *
 * Kept free of Compose and Android imports so it can be compiled and
 * exercised on a plain JVM. The values mirror the visual model of the
 * upstream component (`EffortSlider.java` from MinisAllInOne 1.0.2):
 *
 *  - a discrete track: index 0 => 0.0, last index => 1.0
 *  - the fill starts changing only after the first third ("energy")
 *  - blue -> violet -> deep-purple endpoint, flat blue up to the 2nd level
 *  - 22 stars, golden-ratio phases, deterministic per-index Y/brightness
 *  - speed ramps from 0.35x to 2x with the selected position
 *
 * This file is UI-only and intentionally has no dependency on the host's
 * thinking-level enum.
 */
internal object ComposerPortMath {

    const val PARTICLE_COUNT = 22
    const val GOLDEN_RATIO = 0.6180339887498949

    const val BLUE_ARGB = 0xFF4D93F8.toInt()
    const val VIOLET_ARGB = 0xFF9333EA.toInt()
    const val DEEP_ARGB = 0xFF4C1D95.toInt()
    const val LEVEL_VIOLET_ARGB = 0xFF8B5CF6.toInt()
    const val OFF_GREY_ARGB = 0xFF8B9099.toInt()

    private const val E0 = 1f / 3f
    private const val E1 = 2f / 3f

    fun clamp01(value: Float): Float = if (value < 0f) 0f else if (value > 1f) 1f else value

    /** Position of [index] on the track. A single level sits at the right edge. */
    fun pct(index: Int, count: Int): Float {
        if (count < 2) return 1f
        return clamp01(index.toFloat() / (count - 1).toFloat())
    }

    /** Nearest discrete level for a continuous track position. */
    fun nearestIndex(value: Float, count: Int): Int {
        if (count < 2) return 0
        return (clamp01(value) * (count - 1)).roundToInt().coerceIn(0, count - 1)
    }

    /** 0 at the first third of the track, 1 at the far right. */
    fun energy(p: Float): Float = clamp01((clamp01(p) - E0) / (1f - E0))

    fun mixArgb(a: Int, b: Int, t: Float): Int {
        val k = clamp01(t)
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * k).roundToInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * k).roundToInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * k).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** Fill right-end colour: flat blue up to the 2nd level, then violet, then deep. */
    fun fillEndArgb(p: Float): Int {
        val t = clamp01(p)
        if (t <= E0) return BLUE_ARGB
        if (t <= E1) return mixArgb(BLUE_ARGB, VIOLET_ARGB, (t - E0) / (E1 - E0))
        return mixArgb(VIOLET_ARGB, DEEP_ARGB, (t - E1) / (1f - E1))
    }

    /** Value-label / chip colour: blue at Off, violet as energy rises. */
    fun levelColorArgb(index: Int, count: Int): Int {
        if (index <= 0) return OFF_GREY_ARGB
        return mixArgb(BLUE_ARGB, LEVEL_VIOLET_ARGB, energy(pct(index, count)))
    }

    fun starCount(energy: Float): Int =
        (clamp01(energy) * PARTICLE_COUNT).roundToInt().coerceIn(0, PARTICLE_COUNT)

    private fun frac(value: Double): Float {
        val f = value - kotlin.math.floor(value)
        return f.toFloat()
    }

    /** Deterministic golden-ratio phase for star [index]. */
    fun starPhase(index: Int): Float = frac((index + 1) * GOLDEN_RATIO)

    /** Deterministic vertical position (0 top .. 1 bottom) for star [index]. */
    fun starY(index: Int): Float = frac((index + 1) * 0.3819660112501051 + 0.17)

    /** Deterministic brightness (0.5 .. 1.0) for star [index]. */
    fun starBrightness(index: Int): Float =
        0.5f + 0.5f * frac((index + 1) * 0.7548776662466927)

    /** Per-star duration spread, as in the upstream 0.92 .. 1.06 range. */
    fun starSpread(index: Int): Float =
        0.92f + 0.14f * frac((index + 1) * 0.4142135623730950)

    /** Star speed: slow at Off, fastest at the right edge. */
    fun speedAt(value: Float): Float {
        val v = 3f * clamp01(value) - 1f
        return if (v < 0.35f) 0.35f else if (v > 2f) 2f else v
    }

    /** Fade in over the first 6% of travel and out over the last 6%. */
    fun starAlpha(progress: Float, brightness: Float, layer: Float): Float {
        val p = clamp01(progress)
        val fade = when {
            p < 0.06f -> p / 0.06f
            p > 0.94f -> (1f - p) / 0.06f
            else -> 1f
        }
        return clamp01(fade * brightness * layer)
    }
}
