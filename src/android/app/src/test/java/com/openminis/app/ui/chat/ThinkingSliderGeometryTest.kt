package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-native-composer-slider-geometry] Pure-JVM checks for the effort slider's
 * shared geometry. Everything the Canvas draws and the pointer handler hit-
 * tests comes from [ThinkingSliderGeometry.Track], so these assertions pin the
 * user-visible contract of the upstream design
 * (`Bai-2008/dsh-better-panel` @ `4f798ffc`, 28dp knob on a 20dp track):
 *
 *  - the knob is **larger** than the pill (upstream 28 / 20) and overhangs each
 *    rounded end by exactly 4dp at the first/last stop, yet stays inside the
 *    composable even on a 90dp-wide canvas;
 *  - the stop centres are inset by the pill's own radius (10dp), so the fill's
 *    left cap is fully covered by the knob and the thumb never leaves the bar
 *    horizontally;
 *  - the pill stays vertically centred in the unchanged 30dp slot;
 *  - dots are evenly spaced, labels share the dot x, tapping a dot selects it,
 *    and the 1/3 detent is the same mapping the gesture uses.
 */
class ThinkingSliderGeometryTest {

    private val density = 2.75f // 440dpi phone

    private fun track(
        widthDp: Float = 320f,
        count: Int = 7,
        labelHalfWidthPx: Float = 47f,
    ): ThinkingSliderGeometry.Track = ThinkingSliderGeometry.track(
        widthPx = widthDp * density,
        slotHeightPx = ThinkingSliderGeometry.SLOT_HEIGHT_DP * density,
        barHeightPx = ThinkingSliderGeometry.TRACK_HEIGHT_DP * density,
        knobRadiusPx = ThinkingSliderGeometry.knobRadiusPx(
            ThinkingSliderGeometry.KNOB_SIZE_DP * density,
        ),
        labelHalfWidthPx = labelHalfWidthPx,
        edgePadPx = ThinkingSliderGeometry.TRACK_EDGE_PAD_DP * density,
        count = count,
    )

    @Test
    fun `upstream proportions are preserved`() {
        val t = track()
        assertEquals(20f * density, t.barHeightPx, 1e-3f)
        assertEquals(10f * density, t.trackRadius, 1e-3f)
        assertEquals(14f * density, t.knobRadiusPx, 1e-3f)
        // 28dp knob on a 20dp track: the knob overhangs each end by 4dp.
        assertEquals(4f * density, t.knobOverhang, 1e-3f)
        assertTrue(t.knobRadiusPx > t.trackRadius)
        // The 20dp pill stays centred in the unchanged 30dp slot.
        assertEquals(15f * density, t.cy, 1e-3f)
        assertEquals(5f * density, t.barTop, 1e-3f)
        assertEquals(25f * density, t.barBottom, 1e-3f)
        assertTrue(t.barTop >= 0f)
        assertTrue(t.barBottom <= t.slotHeightPx)
    }

    @Test
    fun `stops are inset by the pill radius`() {
        val t = track()
        assertEquals(t.barLeft + t.trackRadius, t.firstTickX, 1e-3f)
        assertEquals(t.barRight - t.trackRadius, t.lastTickX, 1e-3f)
        assertEquals(t.barHeightPx / 2f, t.endInset, 1e-3f)
        assertEquals(t.tickX(0), t.xForValue(0f), 1e-3f)
        assertEquals(t.tickX(t.count - 1), t.xForValue(1f), 1e-3f)
    }

    @Test
    fun `knob overhangs the pill ends but never the canvas`() {
        val t = track()
        // At the first/last stop the knob sticks out past the pill by exactly
        // the upstream 4dp, while still covered by the canvas gutter.
        for (i in intArrayOf(0, t.count - 1)) {
            val overhangLeft = t.barLeft - t.knobLeft(i)
            if (i == 0) {
                assertEquals(t.knobOverhang, overhangLeft, 1e-3f)
            }
            assertTrue("knob $i leaves the canvas", t.knobFits(i))
        }
        // Pressing does not grow the knob upstream, so the resting geometry is
        // the whole contract.
        for (i in 0 until t.count) {
            assertTrue("knob $i leaves the canvas", t.knobFits(i))
        }
    }

    @Test
    fun `knob is inside the canvas on narrow screens`() {
        for (widthDp in listOf(280f, 320f, 360f, 400f, 90f)) {
            val t = track(widthDp = widthDp, labelHalfWidthPx = 60f)
            assertTrue("track collapsed at ${widthDp}dp: ${t.trackWidth}", t.trackWidth > 0f)
            assertTrue("bar inverted at ${widthDp}dp", t.barRight > t.barLeft)
            for (i in 0 until t.count) {
                assertTrue("knob $i clipped at ${widthDp}dp", t.knobFits(i, 1.10f * t.knobRadiusPx))
                assertTrue(
                    "knob $i overflows the slot at ${widthDp}dp",
                    t.cy - t.knobRadiusPx >= 0f && t.cy + t.knobRadiusPx <= t.slotHeightPx,
                )
            }
        }
    }

    @Test
    fun `dots are evenly spaced and span the tick range`() {
        val t = track()
        val step = (t.lastTickX - t.firstTickX) / (t.count - 1)
        for (i in 0 until t.count) {
            assertEquals("dot $i spacing", t.firstTickX + step * i, t.tickX(i), 1e-2f)
        }
    }

    @Test
    fun `first and last labels are fully inside the canvas`() {
        val halfLabel = 47f
        for (widthDp in listOf(320f, 360f, 400f)) {
            val t = track(widthDp = widthDp, labelHalfWidthPx = halfLabel)
            assertTrue("first label clipped at ${widthDp}dp", t.tickX(0) - halfLabel >= 0f)
            assertTrue(
                "last label clipped at ${widthDp}dp",
                t.tickX(t.count - 1) + halfLabel <= t.widthPx,
            )
        }
    }

    @Test
    fun `each label is centred under its own dot`() {
        val t = track()
        for (i in 0 until t.count) {
            assertEquals("label $i centre", t.tickX(i), t.labelX(i), 0f)
        }
    }

    @Test
    fun `tapping a dot selects that dot`() {
        val t = track()
        for (i in 0 until t.count) {
            assertEquals("tap on dot $i", i, t.indexForX(t.tickX(i)))
        }
        // Between two stops, the press picks the nearest one.
        for (i in 0 until t.count - 1) {
            val mid = (t.tickX(i) + t.tickX(i + 1)) / 2f
            assertEquals(i, t.indexForX(mid - 1f))
            assertEquals(i + 1, t.indexForX(mid + 1f))
        }
    }

    @Test
    fun `drag uses the one third detent`() {
        val t = track(count = 4)
        val pitch = t.trackWidth / 3f
        assertEquals(0, t.detentIndexForX(t.tickX(0)))
        assertEquals(0, t.detentIndexForX(t.tickX(0) + pitch * 0.30f))
        assertEquals(1, t.detentIndexForX(t.tickX(0) + pitch * 0.34f))
        assertEquals(1, t.detentIndexForX(t.tickX(1) + pitch * 0.30f))
        assertEquals(2, t.detentIndexForX(t.tickX(1) + pitch * 0.34f))
        assertEquals(3, t.detentIndexForX(t.lastTickX))
        // Beyond the track is clamped to the end stops.
        assertEquals(0, t.detentIndexForX(-500f))
        assertEquals(3, t.detentIndexForX(t.widthPx + 500f))
    }

    @Test
    fun `value for x is monotonic and clamped`() {
        val t = track()
        assertEquals(0f, t.valueForX(t.firstTickX - 1f), 0f)
        assertEquals(1f, t.valueForX(t.lastTickX + 1f), 0f)
        assertEquals(0f, t.unitForX(t.firstTickX), 0f)
        assertEquals((t.count - 1).toFloat(), t.unitForX(t.lastTickX), 1e-4f)
        var previous = -1f
        var x = t.firstTickX
        while (x <= t.lastTickX) {
            val v = t.valueForX(x)
            assertTrue("value went backwards at $x", v >= previous)
            previous = v
            x += 4f
        }
    }

    @Test
    fun `seven lowercase labels never collide on phone widths`() {
        val halfLabel = 47f
        for (widthDp in listOf(320f, 360f, 400f)) {
            val t = track(widthDp = widthDp, labelHalfWidthPx = halfLabel)
            val step = t.trackWidth / (t.count - 1)
            assertTrue(
                "labels collide at ${widthDp}dp (step $step <= ${2 * halfLabel})",
                step > 2f * halfLabel,
            )
        }
    }

    @Test
    fun `single level sits at the left stop`() {
        val t = track(count = 1)
        assertEquals(0f, t.pctFor(0), 0f)
        assertEquals(t.firstTickX, t.tickX(0), 1e-3f)
        assertEquals(0, t.indexForX(t.firstTickX))
        assertEquals(0, t.detentIndexForX(t.widthPx))
        assertFalse(t.knobFits(0, t.knobRadiusPx * 3f))
    }

    @Test
    fun `label baseline centres the glyph band`() {
        val ascent = -20f
        val descent = 6f
        val baseline = ThinkingSliderGeometry.labelBaseline(
            bandTopPx = 100f,
            bandHeightPx = 40f,
            ascent = ascent,
            descent = descent,
        )
        assertEquals(120f, baseline + (ascent + descent) / 2f, 1e-3f)
        assertTrue(baseline + ascent >= 100f)
        assertTrue(baseline + descent <= 140f)
    }
}
