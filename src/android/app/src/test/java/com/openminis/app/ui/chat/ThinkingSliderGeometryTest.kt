package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [T-native-composer-slider-geometry] Pure-JVM checks for the effort slider's
 * shared geometry. Everything the Canvas draws and the pointer handler hit-
 * tests comes from [ThinkingSliderGeometry.Track], so these assertions pin the
 * user-visible contract:
 *
 *  - the round thumb is fully inside the rounded pill (not just the canvas),
 *    including in its pressed state and at the first/last tick;
 *  - the dots are evenly spaced, and the first/last labels are not clipped;
 *  - tapping exactly on a dot selects that dot (draw == hit-test);
 *  - the x -> level mapping clamps outside the track.
 */
class ThinkingSliderGeometryTest {

    private val density = 2.75f // 440dpi phone

    private fun track(
        widthPx: Float = 320f * density,
        count: Int = 7,
        labelHalfWidthPx: Float = 47f,
    ): ThinkingSliderGeometry.Track {
        val barHeight = ThinkingSliderGeometry.TRACK_HEIGHT_DP * density
        val thumbRadius = ThinkingSliderGeometry.thumbRadiusPx(
            barHeightPx = barHeight,
            insetPx = ThinkingSliderGeometry.THUMB_INSET_DP * density,
            minRadiusPx = ThinkingSliderGeometry.MIN_THUMB_RADIUS_DP * density,
        )
        return ThinkingSliderGeometry.track(
            widthPx = widthPx,
            barHeightPx = barHeight,
            thumbRadiusPx = thumbRadius,
            labelHalfWidthPx = labelHalfWidthPx,
            edgePadPx = ThinkingSliderGeometry.TRACK_EDGE_PAD_DP * density,
            count = count,
        )
    }

    @Test
    fun `thumb circle sits inside the pill, not on its edge`() {
        val t = track()
        val barHalfHeight = t.barHeightPx / 2f
        assertTrue(
            "thumb radius ${t.thumbRadiusPx} must be smaller than the pill radius $barHalfHeight",
            t.thumbRadiusPx < barHalfHeight,
        )
        // The pill's rounded end used to be the first tick's centre, which put
        // half the thumb outside the pill. Every dot must clear the end cap.
        for (i in 0 until t.count) {
            assertTrue("thumb $i pokes out of the pill", t.thumbInsidePill(i))
        }
        assertTrue(t.firstTickX >= t.barLeft + barHalfHeight - 1e-3f)
        assertTrue(t.lastTickX <= t.barRight - barHalfHeight + 1e-3f)
    }

    @Test
    fun `pressed thumb still fits inside the pill`() {
        val t = track()
        val pressed = t.thumbRadiusPx * ThinkingSliderGeometry.PRESSED_SCALE
        assertTrue("pressed thumb escaped the pill", pressed <= t.pillRadius)
        for (i in 0 until t.count) {
            assertTrue("pressed thumb $i pokes out", t.circleInsidePill(i, pressed))
        }
    }

    @Test
    fun `thumb stays inside the canvas at both ends`() {
        val t = track()
        for (i in 0 until t.count) {
            assertTrue("thumb $i leaves the canvas", t.thumbFits(i))
        }
    }

    @Test
    fun `pill is inset by its own corner radius at both ends`() {
        val t = track()
        assertEquals(t.barLeft + t.pillRadius, t.firstTickX, 1e-3f)
        assertEquals(t.barRight - t.pillRadius, t.lastTickX, 1e-3f)
        assertEquals(t.barHeightPx / 2f, t.endInset, 1e-3f)
    }

    @Test
    fun `dots are evenly spaced and span the tick range`() {
        val t = track()
        assertEquals(t.firstTickX, t.tickX(0), 1e-3f)
        assertEquals(t.lastTickX, t.tickX(t.count - 1), 1e-3f)
        val step = (t.lastTickX - t.firstTickX) / (t.count - 1)
        for (i in 0 until t.count) {
            assertEquals("dot $i spacing", t.firstTickX + step * i, t.tickX(i), 1e-2f)
        }
    }

    @Test
    fun `first and last labels are fully inside the canvas`() {
        val halfLabel = 47f
        for (widthDp in listOf(320f, 360f, 400f)) {
            val t = track(widthPx = widthDp * density, labelHalfWidthPx = halfLabel)
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
            assertEquals("draw position of $i", t.tickX(i), t.xForValue(t.pctFor(i)), 1e-2f)
        }
        // The thumb's own draw position IS the dot position, so the gesture and
        // the render agree by construction.
        for (i in 0 until t.count) {
            assertEquals(t.tickX(i), t.xForValue(t.pctFor(i)), 1e-2f)
        }
    }

    @Test
    fun `drag positions snap and clamp`() {
        val t = track()
        assertEquals(0, t.indexForX(t.firstTickX - 500f))
        assertEquals(t.count - 1, t.indexForX(t.lastTickX + 500f))
        assertEquals(0f, t.valueForX(t.firstTickX - 1f), 0f)
        assertEquals(1f, t.valueForX(t.lastTickX + 1f), 0f)
        assertEquals(0, t.indexForX(t.firstTickX))
        assertEquals(t.count - 1, t.indexForX(t.lastTickX))
        val mid = (t.tickX(2) + t.tickX(3)) / 2f
        assertEquals(2, t.indexForX(mid - 1f))
        assertEquals(3, t.indexForX(mid + 1f))
    }

    @Test
    fun `value for x is monotonic`() {
        val t = track()
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
        // "medium" at 11sp is ~94px wide on a 440dpi phone; a step wider than
        // that keeps every name clear of its neighbours at 320/360/400dp.
        val halfLabel = 47f
        for (widthDp in listOf(320f, 360f, 400f)) {
            val t = track(widthPx = widthDp * density, labelHalfWidthPx = halfLabel)
            val step = t.trackWidth / (t.count - 1)
            assertTrue(
                "labels collide at ${widthDp}dp (step $step <= ${2 * halfLabel})",
                step > 2f * halfLabel,
            )
        }
    }

    @Test
    fun `narrow width keeps a usable track`() {
        val t = track(widthPx = 90f, labelHalfWidthPx = 60f)
        assertTrue("track collapsed: ${t.trackWidth}", t.trackWidth >= 0f)
        assertTrue(t.barRight > t.barLeft)
        assertTrue(t.firstTickX >= t.barLeft)
        assertTrue(t.lastTickX <= t.barRight)
    }

    @Test
    fun `single level degenerates to the last dot`() {
        val t = track(count = 1)
        assertEquals(1f, t.pctFor(0), 0f)
        assertEquals(t.lastTickX, t.tickX(0), 1e-3f)
        assertEquals(0, t.indexForX(t.firstTickX))
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
        // Visual centre of the glyph box (baseline + ascent .. baseline + descent)
        // lands on the band centre, and the glyph box stays inside the band.
        assertEquals(120f, baseline + (ascent + descent) / 2f, 1e-3f)
        assertTrue(baseline + ascent >= 100f)
        assertTrue(baseline + descent <= 140f)
        assertTrue(abs((baseline + ascent + baseline + descent) / 2f - 120f) < 1e-3f)
    }
}
