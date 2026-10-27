package com.openminis.app.ui.chat

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pure geometry / colour / particle maths for the native port of the
 * Composer effort slider.
 *
 * Source of truth: **dsh-better-panel** (`Bai-2008/dsh-better-panel`),
 * commit `4f798ffcecf17d52cd15aaa617124c21b9784c12` (2026-10-25),
 * `lib/client.js` — the `CodexEffortSlider` component. Every constant and
 * formula below is a transcription of that file:
 *
 *  - `KNOB_SIZE = 28` (l.194), `TRACK_HEIGHT = 20` (l.195),
 *    `TRACK_RADIUS = 10` (l.204)
 *  - `DETENT_THIRD = 1 / 3` (l.209)
 *  - `SLIDE_EASE` / `SLIDE_EASE_SLOW` (l.217, l.223)
 *  - `ENERGY_START = 1/3`, `ENERGY_END = 2/3` (l.251, l.453)
 *  - `COLOR_BLUE/VIOLET/DEEP/TEXT_VIOLET` (l.456-465)
 *  - `PARTICLES` (l.703-741), `starHash01` (l.838), `starBrightnessFor`
 *    (l.844), `starDurationFor` (l.853), `starDelayFor` (l.867),
 *    `starCountFor` (l.875), `starLayerOpacityFor` (l.887)
 *  - `fillColorFor` (l.760), `valueColorFor` (l.785), `energyFor` (l.813),
 *    `speedFor` (l.822)
 *  - pointer maths `pctFromIndex` (l.674), `unitAt` (l.2270),
 *    `mappingOf` (l.2288), `resolveTarget` (l.2302)
 *
 * Kept free of Compose and Android imports so the whole visual model can be
 * exercised on a plain JVM and cross-checked against the upstream bundle.
 *
 * The upstream "Max" energy layer is position-driven, not level-driven:
 * `energyFor(p) = 0` for every `p <= 1/3`, so with the four-level table
 * (off / low / high / max) Off and Low have **zero** stars and no nebula.
 * That is the upstream behaviour and is reproduced verbatim; see
 * `docs/native-composer-port.md` §4 for the conflict note with the old port.
 */
internal object ComposerPortMath {

    /** `var PARTICLES` has 22 entries upstream. */
    const val PARTICLE_COUNT = 22

    /** Golden-ratio low-discrepancy phase (upstream `GOLDEN_RATIO`). */
    const val GOLDEN_RATIO = 0.6180339887498949

    /** Upstream `STARFIELD_DURATION_MEAN` / `_SPREAD` / `STARFIELD_MIN`. */
    const val STARFIELD_DURATION_MEAN = 1.5f
    const val STARFIELD_DURATION_SPREAD = 0.08f
    const val STARFIELD_MIN = 0.5f

    /** Upstream `MAX_SPEED_FACTOR`, `REDUCED_MOTION_SLOWDOWN`, `MIN_HEIGHT_SLOTS`. */
    const val MAX_SPEED_FACTOR = 2f
    const val REDUCED_MOTION_SLOWDOWN = 2.6f
    const val MIN_HEIGHT_SLOTS = 3

    /** Energy anchors: flat blue up to 1/3, violet from 2/3. */
    const val ENERGY_START = 1f / 3f
    const val ENERGY_END = 2f / 3f

    /** Detent: a step happens `1/3` of a level pitch past the current stop. */
    const val DETENT_THIRD = 1f / 3f

    /** `COLOR_BLUE`, `COLOR_VIOLET`, `COLOR_DEEP`, `COLOR_TEXT_VIOLET`. */
    const val BLUE_ARGB = 0xFF4D93F8.toInt()
    const val VIOLET_ARGB = 0xFF9333EA.toInt()
    const val DEEP_ARGB = 0xFF4C1D95.toInt()
    const val TEXT_VIOLET_ARGB = 0xFF8B5CF6.toInt()

    /**
     * Chip / label colour for the "off" stop. Upstream `valueColorFor`
     * explicitly returns no inline colour for an off level (the host's own
     * grey shows through), so the port uses the nearest neutral instead of
     * tinting it blue.
     */
    const val OFF_GREY_ARGB = 0xFF8B9099.toInt()

    /** Upstream nebula gradient stops (`--ces-energy` background). */
    const val NEBULA_START_ARGB = 0xFF3B1178.toInt()
    const val NEBULA_MID_ARGB = 0xFF6D28D9.toInt()
    const val NEBULA_HIGH_ARGB = 0xFF9333EA.toInt()
    const val NEBULA_END_ARGB = 0xFFC084FC.toInt()

    /** One deterministic star slot: `y` in track percent, `phase` in 0..1. */
    data class Particle(val yPercent: Float, val phase: Float)

    /**
     * `PARTICLES` exactly as built upstream: phase from the golden-ratio
     * sequence, Y from a salt-searched permutation of evenly spaced slots so
     * phase-neighbours keep at least [MIN_HEIGHT_SLOTS] slots apart.
     *
     * `y = 8 + slot / (count - 1) * 84` (percent of track height).
     */
    val PARTICLES: List<Particle> = buildParticles()

    private fun buildParticles(): List<Particle> {
        val count = PARTICLE_COUNT
        val phase = DoubleArray(count) { i -> ((i + 1) * GOLDEN_RATIO) % 1.0 }
        val byPhase = (0 until count).sortedWith(
            compareBy({ phase[it] }, { it }),
        )
        var slots: List<Int>? = null
        var salt = 71
        while (salt < 4000 && slots == null) {
            val candidate = (0 until count).sortedWith(
                compareBy({ starHash01(it, salt) }, { it }),
            )
            var ok = true
            for (k in 1 until count) {
                if (abs(candidate[k] - candidate[k - 1]) < MIN_HEIGHT_SLOTS) {
                    ok = false
                    break
                }
            }
            if (ok) slots = candidate
            salt += 1
        }
        // Fallback upstream: step 5 is coprime with 22, so it is always a
        // permutation with a constant gap of 5 slots (>= MIN_HEIGHT_SLOTS).
        val chosen = slots ?: (0 until count).map { (it * 5) % count }
        val y = FloatArray(count)
        for (rank in 0 until count) {
            y[byPhase[rank]] = 8f + chosen[rank].toFloat() / (count - 1).toFloat() * 84f
        }
        return List(count) { Particle(yPercent = y[it], phase = phase[it].toFloat()) }
    }

    fun clamp01(value: Float): Float = if (!(value > 0f)) 0f else if (value > 1f) 1f else value

    /**
     * Level index -> track percentage. **A single level sits at 0**, as
     * upstream (`pctFromIndex` returns 0 when `count <= 1`); the thumb then
     * rests on the left cap. The slider is never drawn with one level.
     */
    fun pct(index: Int, count: Int): Float {
        if (count <= 1) return 0f
        val safe = index.coerceIn(0, count - 1)
        return safe.toFloat() / (count - 1).toFloat()
    }

    /** Nearest discrete level (`Math.round(u)`, clamped) — the press target. */
    fun nearestIndex(value: Float, count: Int): Int {
        if (count < 2) return 0
        return (clamp01(value) * (count - 1)).roundToInt().coerceIn(0, count - 1)
    }

    /**
     * Detent mapping from a pointer position in **level units**
     * (0 = first stop, `count - 1` = last stop):
     * `floor(u - 1/3) + 1`, clamped. Upstream `mappingOf`.
     */
    fun detentIndex(unit: Float, count: Int): Int {
        if (count < 2) return 0
        val last = count - 1
        return (floor(unit - DETENT_THIRD) + 1).toInt().coerceIn(0, last)
    }

    /**
     * Press dead-zone: while the finger is still `active` on a press whose
     * nearest stop disagrees with the detent mapping, the detent must not
     * steal the step until the finger either reaches the mapped stop or
     * travels a full third of a pitch. Upstream `resolveTarget` returns
     * `press.index` while this holds.
     */
    fun deadzoneHolds(
        unit: Float,
        pressUnit: Float,
        mapped: Int,
        pressIndex: Int,
        conflict: Boolean,
        active: Boolean,
    ): Boolean = active && conflict && mapped != pressIndex &&
        abs(unit - pressUnit) < DETENT_THIRD

    /** Energy 0..1, pure position: 0 up to 1/3, 0.5 at 2/3, 1 at the end. */
    fun energy(p: Float): Float = clamp01((clamp01(p) - ENERGY_START) / (1f - ENERGY_START))

    /**
     * Particle speed factor: `clamp(3p - 1, 0.35, 2)`.
     * high (2/3) = 1x, max (1) = 2x, left end = 0.35x.
     */
    fun speedFor(p: Float): Float {
        val v = 3f * clamp01(p) - 1f
        return if (v < 0.35f) 0.35f else if (v > MAX_SPEED_FACTOR) MAX_SPEED_FACTOR else v
    }

    fun mixArgb(from: Int, to: Int, t: Float): Int {
        val k = clamp01(t)
        val ar = (from shr 16) and 0xFF
        val ag = (from shr 8) and 0xFF
        val ab = from and 0xFF
        val br = (to shr 16) and 0xFF
        val bg = (to shr 8) and 0xFF
        val bb = to and 0xFF
        val r = (ar + (br - ar) * k).roundToInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * k).roundToInt().coerceIn(0, 255)
        val b = (ab + (bb - ab) * k).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Right end of the fill: flat blue to 1/3, blue->violet, then violet->deep. */
    fun fillEndArgb(p: Float): Int {
        val t = clamp01(p)
        if (t <= ENERGY_START) return BLUE_ARGB
        if (t <= ENERGY_END) {
            return mixArgb(BLUE_ARGB, VIOLET_ARGB, (t - ENERGY_START) / (ENERGY_END - ENERGY_START))
        }
        return mixArgb(VIOLET_ARGB, DEEP_ARGB, (t - ENERGY_END) / (1f - ENERGY_END))
    }

    /** Left end of the fill gradient — always the blue anchor. */
    const val FILL_START_ARGB: Int = BLUE_ARGB

    /**
     * Colour of the level chip / the active mode name. `valueColorFor`
     * upstream returns "no inline colour" at Off; the native chip has to
     * paint itself, so Off keeps the neutral grey.
     */
    fun levelColorArgb(index: Int, count: Int): Int {
        if (index <= 0) return OFF_GREY_ARGB
        return mixArgb(BLUE_ARGB, TEXT_VIOLET_ARGB, energy(pct(index, count)))
    }

    /** Deterministic pseudo-random [0,1) — upstream `starHash01`. */
    fun starHash01(index: Int, salt: Int): Double {
        val value = ((index + 1).toLong() * salt.toLong() * 2654435761L) % 4294967296L
        return ((value ushr 8) % 1000L) / 1000.0
    }

    /** Star brightness / size, `[0.5, 1]`. */
    fun starBrightnessFor(index: Int): Float =
        (STARFIELD_MIN + (1f - STARFIELD_MIN) * starHash01(index, 61)).toFloat()

    /**
     * Travel time in seconds across the **filled** segment: `1.5 * 2 / speed`
     * with a per-star ±8% spread. At max that is 1.5s, at high 3s, at the far
     * left about 8.6s.
     */
    fun starDurationFor(index: Int, speedFactor: Float): Float {
        val speed = speedFactor.coerceIn(0.35f, MAX_SPEED_FACTOR)
        val spread = (1.0 - STARFIELD_DURATION_SPREAD +
            2.0 * STARFIELD_DURATION_SPREAD * starHash01(index, 29)).toFloat()
        return STARFIELD_DURATION_MEAN * MAX_SPEED_FACTOR / speed * spread
    }

    /** Phase offset in 0..1: golden-ratio phase plus <=3% jitter, mod 1. */
    fun starPhase(index: Int): Float {
        val particle = PARTICLES.getOrElse(index) { PARTICLES[0] }
        val jitter = starHash01(index, 53) * 0.03
        return ((particle.phase + jitter) % 1.0).toFloat()
    }

    /** CSS `animation-delay`: negative seconds, `-phase * duration`. */
    fun starDelayFor(index: Int, duration: Float): Float = -starPhase(index) * duration

    /** Visible stars = `round(energy * 22)`: 0 at Off/Low, 11 at high, 22 at max. */
    fun starCountFor(pct: Float): Int = (energy(pct) * PARTICLE_COUNT).roundToInt()

    /** Star layer opacity: `0.6 + 0.4 * energy`. */
    fun starLayerOpacityFor(pct: Float): Float = 0.6f + 0.4f * energy(pct)

    /**
     * Dot opacity over one traversal, transcribed from the `ces-star-sweep`
     * keyframes: 0 at 0%, 1 by 6%, 1 until 94%, 0 at 100%.
     * [brightness] is the per-star `--ces-b` size/opacity factor and [layer]
     * the standalone star-layer opacity.
     */
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
