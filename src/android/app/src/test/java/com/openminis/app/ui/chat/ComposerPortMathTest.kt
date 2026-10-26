package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [T-native-composer-slider] Pure-JVM checks for the ported effort slider.
 *
 * These assert the contracts that the Canvas / gesture code relies on:
 * discrete tick positions, nearest-level snapping, the blue -> violet ->
 * deep-purple ramp, and the deterministic 22-star field. No Android or
 * Compose class is loaded, so this runs in the normal unit-test task.
 */
class ComposerPortMathTest {

    @Test
    fun `level positions are discrete and clamped`() {
        assertEquals(0f, ComposerPortMath.pct(0, 7), 0f)
        assertEquals(1f, ComposerPortMath.pct(6, 7), 0f)
        assertEquals(1f, ComposerPortMath.pct(0, 1), 0f)
        assertEquals(1f, ComposerPortMath.pct(99, 7), 0f)
    }

    @Test
    fun `nearest index snaps to the closest level`() {
        assertEquals(0, ComposerPortMath.nearestIndex(0f, 7))
        assertEquals(3, ComposerPortMath.nearestIndex(0.5f, 7))
        assertEquals(6, ComposerPortMath.nearestIndex(1f, 7))
        assertEquals(0, ComposerPortMath.nearestIndex(-2f, 7))
        assertEquals(6, ComposerPortMath.nearestIndex(2f, 7))
        assertEquals(0, ComposerPortMath.nearestIndex(0.7f, 1))
    }

    @Test
    fun `energy starts at the first third`() {
        assertEquals(0f, ComposerPortMath.energy(0f), 0f)
        assertEquals(0f, ComposerPortMath.energy(1f / 3f), 0f)
        assertEquals(0.5f, ComposerPortMath.energy(2f / 3f), 1e-5f)
        assertEquals(1f, ComposerPortMath.energy(1f), 0f)
    }

    @Test
    fun `fill endpoint follows blue violet deep`() {
        assertEquals(ComposerPortMath.BLUE_ARGB, ComposerPortMath.fillEndArgb(0f))
        assertEquals(ComposerPortMath.DEEP_ARGB, ComposerPortMath.fillEndArgb(1f))
        val mid = ComposerPortMath.fillEndArgb(0.5f)
        assertNotEquals(ComposerPortMath.BLUE_ARGB, mid)
        assertNotEquals(ComposerPortMath.DEEP_ARGB, mid)
        assertEquals(0xFF, (mid ushr 24) and 0xFF)
    }

    @Test
    fun `colour mixing stays opaque and bounded`() {
        assertEquals(
            0xFF000000.toInt(),
            ComposerPortMath.mixArgb(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f),
        )
        assertEquals(
            0xFFFFFFFF.toInt(),
            ComposerPortMath.mixArgb(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f),
        )
        val half = ComposerPortMath.mixArgb(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)
        assertTrue(((half shr 16) and 0xFF) in 127..128)
    }

    @Test
    fun `off level is grey and active levels are coloured`() {
        assertEquals(ComposerPortMath.OFF_GREY_ARGB, ComposerPortMath.levelColorArgb(0, 7))
        assertNotEquals(ComposerPortMath.OFF_GREY_ARGB, ComposerPortMath.levelColorArgb(6, 7))
    }

    @Test
    fun `star count follows energy and never exceeds 22`() {
        assertEquals(0, ComposerPortMath.starCount(0f))
        assertEquals(11, ComposerPortMath.starCount(0.5f))
        assertEquals(22, ComposerPortMath.starCount(1f))
        assertEquals(0, ComposerPortMath.starCount(-1f))
        assertEquals(22, ComposerPortMath.starCount(2f))
    }

    @Test
    fun `star field is deterministic and distinct`() {
        val phases = (0 until ComposerPortMath.PARTICLE_COUNT).map { ComposerPortMath.starPhase(it) }
        assertTrue(phases.all { it in 0f..1f })
        assertEquals(phases.size, phases.toSet().size)
        val ys = (0 until ComposerPortMath.PARTICLE_COUNT).map { ComposerPortMath.starY(it) }
        assertTrue(ys.all { it in 0f..1f })
        val brightness = (0 until ComposerPortMath.PARTICLE_COUNT)
            .map { ComposerPortMath.starBrightness(it) }
        assertTrue(brightness.all { it in 0.5f..1f })
    }

    @Test
    fun `speed and alpha follow the upstream envelope`() {
        assertEquals(0.35f, ComposerPortMath.speedAt(0f), 0f)
        assertEquals(2f, ComposerPortMath.speedAt(1f), 0f)
        assertEquals(0.5f, ComposerPortMath.speedAt(0.5f), 1e-5f)
        assertEquals(0f, ComposerPortMath.starAlpha(0f, 1f, 1f), 0f)
        assertEquals(1f, ComposerPortMath.starAlpha(0.5f, 1f, 1f), 0f)
        assertEquals(0f, ComposerPortMath.starAlpha(1f, 1f, 1f), 0f)
        assertTrue(abs(ComposerPortMath.starAlpha(0.03f, 1f, 1f) - 0.5f) < 1e-5f)
    }
}
