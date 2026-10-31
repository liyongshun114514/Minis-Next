package com.openminis.app.ui.navigation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-omnibot-ui-port] Contract tests for the page motion parameters that
 * `AppNavigation`'s NavHost transitions consume.
 *
 * These are plain-JVM tests (no `android.*`, no composition, no device): they
 * exercise the real [OmniMotion] object exactly as the navigation graph does,
 * so a wrong duration, a wrong easing control point or a non-monotonic curve
 * fails here instead of being invisible until someone watches a device.
 *
 * Not covered here (needs a device/instrumentation, deliberately not claimed):
 * the actual NavHost enter/exit wiring, frame pacing and visual smoothness.
 */
class OmniMotionTest {

    @Test
    fun pageTimelineMatchesReferenceDurations() {
        // Reference page transition: 450ms total, fade-in starts at 15% and
        // lasts until 55%, fade-out occupies the last 30%.
        assertEquals(450, OmniMotion.PageDuration)
        assertEquals(68, OmniMotion.FadeInDelay)
        assertEquals(180, OmniMotion.FadeInDuration)
        assertEquals(135, OmniMotion.FadeOutDuration)

        assertEquals(PageShare(0.15f), OmniMotion.FadeInDelay.toFloat(), 0.5f)
        assertEquals(PageShare(0.55f), (OmniMotion.FadeInDelay + OmniMotion.FadeInDuration).toFloat(), 0.5f)
        assertEquals(PageShare(0.30f), OmniMotion.FadeOutDuration.toFloat(), 0.5f)
    }

    @Test
    fun fadeWindowsFitInsideThePageDuration() {
        assertTrue(OmniMotion.FadeInDelay >= 0)
        assertTrue(OmniMotion.FadeInDuration > 0)
        assertTrue(OmniMotion.FadeOutDuration > 0)
        assertTrue(
            "fade-in must end before the page transition does",
            OmniMotion.FadeInDelay + OmniMotion.FadeInDuration <= OmniMotion.PageDuration,
        )
        assertTrue(
            "fade-out must end before the page transition does",
            OmniMotion.FadeInDuration + OmniMotion.FadeOutDuration <= OmniMotion.PageDuration,
        )
    }

    @Test
    fun pageEasingHasUnitEndpoints() {
        val easing = OmniMotion.PageEasing
        assertEquals(0f, easing.transform(0f), 1e-4f)
        assertEquals(1f, easing.transform(1f), 1e-4f)
        // Out-of-range input is clamped, never extrapolated.
        assertEquals(0f, easing.transform(-0.5f), 1e-4f)
        assertEquals(1f, easing.transform(1.5f), 1e-4f)
    }

    @Test
    fun pageEasingIsMonotonicBoundedAndNotLinear() {
        val easing = OmniMotion.PageEasing
        var previous = -1f
        var deviations = 0
        var samples = 0
        for (step in 0..200) {
            val x = step / 200f
            val y = easing.transform(x)
            assertTrue("easing($x) must stay inside the unit interval, was $y", y >= 0f && y <= 1f)
            assertTrue("easing must not go backwards at x=$x", y >= previous - 1e-5f)
            previous = y
            if (kotlin.math.abs(y - x) > 0.02f) deviations++
            samples++
        }
        assertTrue("the curve must not be an identity mapping", samples > 0 && deviations > 20)
    }

    @Test
    fun pageEasingIsContinuousAtTheSegmentJoin() {
        // The two cubic segments join at 1/6 of the timeline, where the
        // reference curve is 40% of the way through its travel.
        val easing = OmniMotion.PageEasing
        val seam = 0.166666f
        val atSeam = easing.transform(seam)
        assertEquals("seam value", 0.4f, atSeam, 0.01f)
        // The curve is steep here (about 10% of travel per 1% of time), so the
        // continuity probe has to use a proportionally small step.
        val left = easing.transform(seam - 1e-4f)
        val right = easing.transform(seam + 1e-4f)
        assertEquals("no jump across the segment join (left)", atSeam, left, 5e-3f)
        assertEquals("no jump across the segment join (right)", atSeam, right, 5e-3f)
        assertTrue("seam must be continuous, left=$left right=$right", kotlin.math.abs(left - right) < 1e-2f)
    }

    @Test
    fun pageEasingAcceleratesEarlyLikeTheReference() {
        // Reference motion is front-loaded: a sixth of the time already covers
        // 40% of the travel, and half the time covers more than 90%.
        val easing = OmniMotion.PageEasing
        assertTrue("easing(1/6) = ${easing.transform(0.166666f)}", easing.transform(0.166666f) > 0.35f)
        assertTrue("easing(0.5) = ${easing.transform(0.5f)}", easing.transform(0.5f) > 0.9f)
        assertTrue("easing(0.9) = ${easing.transform(0.9f)}", easing.transform(0.9f) > easing.transform(0.5f))
        assertTrue(
            "the tail must decelerate, not stop dead",
            easing.transform(1f) - easing.transform(0.95f) < easing.transform(0.1f) - easing.transform(0f),
        )
    }

    @Test
    fun fadeInEasingMatchesTheDocumentedCubicBezier() {
        // Same control points, solved independently here by de Casteljau +
        // bisection so a swapped x/y pair in OmniMotion cannot pass.
        val reference = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
        assertEquals(0f, OmniMotion.FadeInEasing.transform(0f), 1e-4f)
        assertEquals(1f, OmniMotion.FadeInEasing.transform(1f), 1e-4f)
        for (step in 0..50) {
            val x = step / 50f
            assertEquals(
                "fade-in easing at x=$x",
                reference.transform(x),
                OmniMotion.FadeInEasing.transform(x),
                1e-3f,
            )
        }
    }

    @Test
    fun fadeInEasingIsNotThePageEasing() {
        // The fade curve is deliberately gentler than the travel curve; if the
        // two objects were ever collapsed into one, this catches it.
        val page = OmniMotion.PageEasing
        val fade = OmniMotion.FadeInEasing
        assertNotEquals(page.transform(0.5f), fade.transform(0.5f))
        assertTrue("fade must be slower than the page curve at the midpoint", fade.transform(0.5f) < page.transform(0.5f))
    }

    /** Independent Bezier reference used only to sanity-check the shipped curve. */
    private class CubicBezierEasing(
        private val x1: Float,
        private val y1: Float,
        private val x2: Float,
        private val y2: Float,
    ) : Easing {
        override fun transform(fraction: Float): Float {
            val x = fraction.coerceIn(0f, 1f)
            if (x <= 0f || x >= 1f) return x
            var low = 0f
            var high = 1f
            repeat(40) {
                val t = (low + high) / 2f
                if (bezier(t, 0f, x1, x2, 1f) < x) low = t else high = t
            }
            return bezier((low + high) / 2f, 0f, y1, y2, 1f)
        }

        private fun bezier(t: Float, a: Float, b: Float, c: Float, d: Float): Float {
            val s = 1f - t
            return s * s * s * a + 3f * s * s * t * b + 3f * s * t * t * c + t * t * t * d
        }
    }

    private companion object {
        private fun PageShare(fraction: Float): Float = OmniMotion.PageDuration * fraction
    }
}
