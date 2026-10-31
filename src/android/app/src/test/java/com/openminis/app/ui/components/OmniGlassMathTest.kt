/*
 * Minis Next — glass geometry/capture maths unit tests.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Pure JVM: no Android type is touched, so these assertions are executed rather
 * than compiled away. They cover the sizes and coordinates a real layout
 * produces (phone panes, tiny panes, panes hanging off the scope, degenerate
 * sizes) — not rendering.
 */
package com.openminis.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class OmniGlassMathTest {

    private val corners22 = OmniGlassCorners(22f, 22f, 22f, 22f)

    // --- capture sizing ---------------------------------------------------

    @Test
    fun captureScaleHonoursThePixelBudget() {
        val phone = Size(1080f, 2400f)
        val scale = OmniGlassCaptureMath.captureScale(phone, 1_000_000, 0.75f)
        assertTrue("scale $scale must be > 0", scale > 0f)
        assertTrue("scale $scale must respect the cap", scale <= 0.75f + 1e-6f)
        val size = OmniGlassCaptureMath.captureSize(phone, scale)
        val pixels = size.width.toLong() * size.height.toLong()
        assertTrue("pixels $pixels must fit the budget", pixels <= 1_000_000L)
        assertTrue("a capture must stay useful, got $size", size.width >= 100 && size.height >= 200)
    }

    @Test
    fun captureScaleNeverCollapses() {
        // An 8K-sized scope still yields the floor, not a thumbnail.
        val huge = Size(7680f, 4320f)
        val scale = OmniGlassCaptureMath.captureScale(huge, 320_000, 1f)
        assertEquals(OmniGlassCaptureMath.MIN_CAPTURE_SCALE, scale, 1e-6f)
        val size = OmniGlassCaptureMath.captureSize(huge, scale)
        assertTrue(size.width >= 1000)
    }

    @Test
    fun captureSizeIsAtLeastTwoPixels() {
        val tiny = OmniGlassCaptureMath.captureSize(Size(1f, 1f), 0.2f)
        assertEquals(OmniGlassCaptureMath.MIN_CAPTURE_SIDE, tiny.width)
        assertEquals(OmniGlassCaptureMath.MIN_CAPTURE_SIDE, tiny.height)
        val zero = OmniGlassCaptureMath.captureSize(Size(0f, 0f), 1f)
        assertEquals(0, zero.width)
        assertEquals(0, zero.height)
    }

    @Test
    fun degenerateCaptureInputsAreRejected() {
        assertFalse(OmniGlassCaptureMath.isDrawable(Size.Zero))
        assertFalse(OmniGlassCaptureMath.isDrawable(Size(Float.NaN, 10f)))
        assertFalse(OmniGlassCaptureMath.isDrawable(Size(10f, Float.POSITIVE_INFINITY)))
        assertFalse(OmniGlassCaptureMath.isDrawable(Size(0.4f, 10f)))
        assertEquals(
            OmniGlassCaptureMath.MIN_CAPTURE_SCALE,
            OmniGlassCaptureMath.captureScale(Size.Zero, 1_000, 1f),
            1e-6f,
        )
    }

    @Test
    fun paneCoordinatesMapIntoTheCapture() {
        // A pane 40 px right and 120 px below the scope's origin, capture at 0.5.
        val mapped = OmniGlassCaptureMath.toCapture(Offset(10f, 20f), Offset(40f, 120f), 0.5f)
        assertEquals(25f, mapped.x, 1e-4f)
        assertEquals(70f, mapped.y, 1e-4f)
        // A pane that hangs off the left/top edge maps to negative capture pixels,
        // which the shader's clamp turns into the nearest real pixel.
        val outside = OmniGlassCaptureMath.toCapture(Offset.Zero, Offset(-30f, -10f), 0.5f)
        assertEquals(-15f, outside.x, 1e-4f)
        assertEquals(-5f, outside.y, 1e-4f)
        // Garbage scale cannot produce NaN coordinates.
        val safe = OmniGlassCaptureMath.toCapture(Offset(4f, 4f), Offset.Zero, Float.NaN)
        assertTrue(safe.x.isFinite() && safe.y.isFinite())
    }

    // --- rim sampling -----------------------------------------------------

    @Test
    fun insetIsZeroOnEveryEdge() {
        val w = 200f
        val h = 120f
        val samples = listOf(
            Offset(100f, 0f),
            Offset(100f, h),
            Offset(0f, 60f),
            Offset(w, 60f),
            Offset(22f, 0f),
            Offset(w - 22f, h),
        )
        for (p in samples) {
            val s = OmniGlassGeometryMath.sample(p.x, p.y, w, h, corners22, 5f)
            assertEquals("rim at $p", 0f, s.inset, 0.02f)
        }
    }

    @Test
    fun insetGrowsTowardsTheCentre() {
        val w = 200f
        val h = 120f
        var previous = -1f
        for (y in listOf(0f, 2f, 6f, 12f, 24f, 40f, 60f)) {
            val s = OmniGlassGeometryMath.sample(100f, y, w, h, corners22, 5f)
            assertTrue("inset must grow from the edge, y=$y gave ${s.inset}", s.inset > previous)
            previous = s.inset
        }
        // Straight side: the inset is the distance to the edge it is nearest to.
        assertEquals(24f, OmniGlassGeometryMath.sample(100f, 24f, w, h, corners22, 5f).inset, 0.05f)
    }

    @Test
    fun outsidePointsGiveANegativeInset() {
        val s = OmniGlassGeometryMath.sample(100f, -5f, 200f, 120f, corners22, 5f)
        assertTrue("outside must be negative, got ${s.inset}", s.inset < 0f)
    }

    @Test
    fun normalsPointOutOfTheShape() {
        val w = 200f
        val h = 120f
        val top = OmniGlassGeometryMath.sample(100f, 4f, w, h, corners22, 5f)
        assertEquals(0f, top.normalX, 1e-4f)
        assertEquals(-1f, top.normalY, 1e-4f)
        val left = OmniGlassGeometryMath.sample(4f, 60f, w, h, corners22, 5f)
        assertEquals(-1f, left.normalX, 1e-4f)
        assertEquals(0f, left.normalY, 1e-4f)
        // In a corner the normal points diagonally outward.
        val corner = OmniGlassGeometryMath.sample(4f, 4f, w, h, corners22, 5f)
        assertTrue(corner.normalX < -0.4f && corner.normalY < -0.4f)
        assertEquals(1f, sqrt(corner.normalX * corner.normalX + corner.normalY * corner.normalY), 1e-3f)
    }

    @Test
    fun cornerAndEdgeRegionsAgreeWhereTheyMeet() {
        // The corner octant is only used inside |x| > hw - r && |y| > hh - r; on
        // that boundary both formulas must give the same inset, otherwise the
        // refraction band would show a step.
        val w = 200f
        val h = 120f
        for (y in listOf(2f, 6f, 11f, 15f, 20f)) {
            val a = OmniGlassGeometryMath.sample(178f, y, w, h, corners22, 5f).inset
            val b = OmniGlassGeometryMath.sample(177.9f, y, w, h, corners22, 5f).inset
            assertEquals("continuity at y=$y", a, b, 0.6f)
        }
    }

    @Test
    fun exponentTwoMatchesACircularCorner() {
        val w = 200f
        val h = 120f
        // A point on the 45-degree diagonal of the top-left corner: for a circle
        // of radius 22 the inset is 22*(1-sqrt(0.5)) from the corner arc.
        val cx = 22f
        val cy = 22f
        val d = 22f / sqrt(2f)
        val point = Offset(cx - d, cy - d)
        val s = OmniGlassGeometryMath.sample(point.x, point.y, w, h, corners22, 2f)
        assertEquals(0f, s.inset, 0.35f)
        // With n = 5 the same direction lies further out, i.e. the rim is later.
        val s5 = OmniGlassGeometryMath.sample(point.x, point.y, w, h, corners22, 5f)
        // The squircle is fuller, so the circle's rim point is strictly inside it.
        assertTrue("squircle corner is fuller: n=2 inset ${s.inset}, n=5 inset ${s5.inset}", s5.inset > 1f)
    }

    @Test
    fun radiusZeroDegradesToABox() {
        val s = OmniGlassGeometryMath.sample(1f, 10f, 100f, 100f, OmniGlassCorners(0f, 0f, 0f, 0f), 5f)
        assertEquals(1f, s.inset, 1e-4f)
        assertEquals(-1f, s.normalX, 1e-4f)
        assertEquals(0f, s.normalY, 1e-4f)
    }

    @Test
    fun exponentAndRadiusClamping() {
        assertEquals(2f, OmniGlassGeometryMath.clampExponent(0.5f), 1e-6f)
        assertEquals(16f, OmniGlassGeometryMath.clampExponent(99f), 1e-6f)
        assertEquals(
            OmniGlassGeometryMath.DEFAULT_EXPONENT,
            OmniGlassGeometryMath.clampExponent(Float.NaN),
            1e-6f,
        )
        assertEquals(0f, OmniGlassGeometryMath.clampRadius(-4f, 50f, 50f), 1e-6f)
        assertEquals(30f, OmniGlassGeometryMath.clampRadius(9_999f, 50f, 30f), 1e-6f)
        assertEquals(0f, OmniGlassGeometryMath.clampRadius(Float.NaN, 50f, 50f), 1e-6f)
        // A degenerate pane never yields NaN.
        val degenerate = OmniGlassGeometryMath.sample(0f, 0f, 0f, 0f, corners22, 5f)
        assertTrue(degenerate.inset.isFinite() && degenerate.normalX.isFinite())
    }

    // --- refraction -------------------------------------------------------

    @Test
    fun refractionIsDeepestAtTheRimAndZeroAtTheBevel() {
        val bevel = 14f
        val depth = 6f
        val atRim = OmniGlassGeometryMath.refractionShift(0f, 0f, -1f, bevel, depth)
        assertEquals(6f, abs(atRim.y), 1e-4f)
        // The outward normal is up (0,-1), so sampling inward moves down (+y).
        assertTrue("shift must sample inside: $atRim", atRim.y > 0f)
        val atBevel = OmniGlassGeometryMath.refractionShift(bevel, 0f, -1f, bevel, depth)
        assertEquals(0f, atBevel.x, 1e-6f)
        assertEquals(0f, atBevel.y, 1e-6f)
        val deep = OmniGlassGeometryMath.refractionShift(bevel * 4f, 0f, -1f, bevel, depth)
        assertEquals(0f, deep.y, 1e-6f)
        // Monotone across the band.
        var previous = Float.MAX_VALUE
        for (inset in listOf(0f, 2f, 5f, 9f, 13f)) {
            val shift = abs(OmniGlassGeometryMath.refractionShift(inset, 0f, -1f, bevel, depth).y)
            assertTrue("shift must fall towards the bevel at $inset ($shift > $previous)", shift <= previous + 1e-5f)
            previous = shift
        }
    }

    @Test
    fun refractionHandlesLensesAndBadInput() {
        val lens = OmniGlassGeometryMath.refractionShift(0f, 1f, 0f, 8f, -4f)
        assertTrue("a raised lens reads outward: $lens", lens.x > 0f)
        assertEquals(0f, OmniGlassGeometryMath.refractionShift(0f, 1f, 0f, 8f, 0f).x, 1e-6f)
        assertEquals(0f, OmniGlassGeometryMath.refractionShift(0f, 1f, 0f, 8f, Float.NaN).x, 1e-6f)
        // A zero-width bevel is treated as the thinnest real bevel, not a divide.
        val thin = OmniGlassGeometryMath.refractionShift(0f, 0f, -1f, 0f, 3f)
        assertTrue(thin.y.isFinite())
    }

    @Test
    fun chromaticPartingFadesOutWithDepth() {
        val near = OmniGlassGeometryMath.chromaOffset(0f, 0f, -1f, 0.4f, 14f)
        val far = OmniGlassGeometryMath.chromaOffset(14f, 0f, -1f, 0.4f, 14f)
        assertEquals(0.2f, abs(near.y), 1e-4f)
        assertEquals(0f, far.y, 1e-6f)
        assertEquals(0f, OmniGlassGeometryMath.chromaOffset(0f, 0f, -1f, 0f, 14f).y, 1e-6f)
    }

    // --- capture scheduling ----------------------------------------------

    @Test
    fun shouldCaptureRespectsEveryGuard() {
        val size = Size(1080f, 2400f)
        // Idle, attached, first capture.
        assertTrue(
            OmniGlassCaptureMath.shouldCapture(true, false, 1, size, 1_000, 0, 250, false),
        )
        // Disabled / capturing / nothing attached / degenerate.
        assertFalse(OmniGlassCaptureMath.shouldCapture(false, false, 1, size, 1_000, 0, 250, false))
        assertFalse(OmniGlassCaptureMath.shouldCapture(true, true, 1, size, 1_000, 0, 250, false))
        assertFalse(OmniGlassCaptureMath.shouldCapture(true, false, 0, size, 1_000, 0, 250, false))
        assertFalse(OmniGlassCaptureMath.shouldCapture(true, false, 1, Size.Zero, 1_000, 0, 250, false))
        // Throttle.
        assertFalse(OmniGlassCaptureMath.shouldCapture(true, false, 1, size, 1_100, 1_000, 250, false))
        assertTrue(OmniGlassCaptureMath.shouldCapture(true, false, 1, size, 1_250, 1_000, 250, false))
        // Geometry change bypasses the throttle.
        assertTrue(OmniGlassCaptureMath.shouldCapture(true, false, 1, size, 1_001, 1_000, 250, true))
        // The loop guard wins over everything, including a geometry change.
        assertFalse(OmniGlassCaptureMath.shouldCapture(true, true, 1, size, 9_999, 1_000, 0, true))
    }
}
