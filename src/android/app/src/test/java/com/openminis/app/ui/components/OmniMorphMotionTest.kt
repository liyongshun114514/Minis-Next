package com.openminis.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Real unit tests for the measured container/menu motion table.
 *
 * These are not structural checks: every value the two transforms interpolate
 * (duration, phased fade, rect/radius/colour interpolation, the popup window
 * clamp, the constant-speed interruption maths, curve symmetry) is computed here
 * and compared against the measured reference parameters.
 */
class OmniMorphMotionTest {

    // ── measured durations ──────────────────────────────────────────────────

    @Test
    fun menuDurationsMatchTheReferencePopupLayer() {
        assertEquals(450, OmniMorphMotion.MenuOpenMillis)
        assertEquals(500, OmniMorphMotion.MenuCloseMillis)
        assertEquals(200, OmniMorphMotion.MenuContextMillis)
        assertEquals(300, OmniMorphMotion.ContainerOpenMillis)
        assertEquals(300, OmniMorphMotion.ContainerCloseMillis)
    }

    @Test
    fun contextMenusUseTwoHundredMillisecondsInBothDirections() {
        assertEquals(200, OmniMorphMotion.menuDurationMillis(contextMenu = true, closing = false))
        assertEquals(200, OmniMorphMotion.menuDurationMillis(contextMenu = true, closing = true))
        assertEquals(450, OmniMorphMotion.menuDurationMillis(contextMenu = false, closing = false))
        assertEquals(500, OmniMorphMotion.menuDurationMillis(contextMenu = false, closing = true))
    }

    // ── phased fades ────────────────────────────────────────────────────────

    @Test
    fun containerColourFadesInTheSecondFifthAndOutInTheFourth() {
        // Opening: nothing before 20%, complete at 40%.
        assertEquals(0f, OmniMorphMotion.containerFade(0f, closing = false), 1e-6f)
        assertEquals(0f, OmniMorphMotion.containerFade(0.2f, closing = false), 1e-6f)
        assertEquals(0.5f, OmniMorphMotion.containerFade(0.3f, closing = false), 1e-6f)
        assertEquals(1f, OmniMorphMotion.containerFade(0.4f, closing = false), 1e-6f)
        assertEquals(1f, OmniMorphMotion.containerFade(1f, closing = false), 1e-6f)
        // Returning: still complete at 80%, gone at 60%.
        assertEquals(1f, OmniMorphMotion.containerFade(0.8f, closing = true), 1e-6f)
        assertEquals(0.5f, OmniMorphMotion.containerFade(0.7f, closing = true), 1e-6f)
        assertEquals(0f, OmniMorphMotion.containerFade(0.6f, closing = true), 1e-6f)
        assertEquals(0f, OmniMorphMotion.containerFade(0f, closing = true), 1e-6f)
    }

    @Test
    fun menuContentAppearsLateAndLeavesEarly() {
        assertEquals(0f, OmniMorphMotion.menuContentAlpha(0f, closing = false), 1e-6f)
        assertEquals(0f, OmniMorphMotion.menuContentAlpha(0.30f, closing = false), 1e-6f)
        assertEquals(1f, OmniMorphMotion.menuContentAlpha(0.75f, closing = false), 1e-6f)
        assertEquals(1f, OmniMorphMotion.menuContentAlpha(1f, closing = false), 1e-6f)
        // Returning: the panel is empty before the container is halfway home.
        assertEquals(1f, OmniMorphMotion.menuContentAlpha(0.45f, closing = true), 1e-6f)
        assertEquals(0f, OmniMorphMotion.menuContentAlpha(0f, closing = true), 1e-6f)
    }

    @Test
    fun pageEntryIsSegmentedBarThenBody() {
        // The bar is already settling while the body is still invisible.
        assertTrue(OmniMorphMotion.headerAlpha(0.15f) > 0f)
        assertEquals(0f, OmniMorphMotion.firstBlockAlpha(0.15f), 1e-6f)
        // Both are complete when the page has settled.
        assertEquals(1f, OmniMorphMotion.headerAlpha(1f), 1e-6f)
        assertEquals(1f, OmniMorphMotion.firstBlockAlpha(1f), 1e-6f)
        assertEquals(1f, OmniMorphMotion.bodyAlpha(1f), 1e-6f)
        // Ordering: bar reaches full opacity before the body does.
        assertTrue(OmniMorphMotion.headerAlpha(0.5f) >= OmniMorphMotion.firstBlockAlpha(0.5f))
        assertTrue(OmniMorphMotion.firstBlockAlpha(0.5f) >= OmniMorphMotion.bodyAlpha(0.5f))
    }

    @Test
    fun segmentIsClampedAndDegenerateSegmentsAreSafe() {
        assertEquals(0f, OmniMorphMotion.segment(-1f, 0.2f, 0.4f), 1e-6f)
        assertEquals(1f, OmniMorphMotion.segment(2f, 0.2f, 0.4f), 1e-6f)
        // Degenerate (start == end): passed the point -> complete, before it -> 0.
        assertEquals(1f, OmniMorphMotion.segment(0.5f, 0.4f, 0.4f), 1e-6f)
        assertEquals(0f, OmniMorphMotion.segment(0.5f, 0.6f, 0.6f), 1e-6f)
    }

    // ── geometry ────────────────────────────────────────────────────────────

    @Test
    fun containerRectInterpolatesSizeAndPosition() {
        val source = Rect(10f, 400f, 310f, 470f)
        val target = Rect(0f, 0f, 1080f, 2340f)
        assertEquals(source, OmniMorphMotion.rectAt(source, target, 0f))
        assertEquals(target, OmniMorphMotion.rectAt(source, target, 1f))
        val mid = OmniMorphMotion.rectAt(source, target, 0.5f)
        assertEquals((10f + 0f) / 2f, mid.left, 1e-4f)
        assertEquals((470f + 2340f) / 2f, mid.bottom, 1e-4f)
        // An out-of-range fraction cannot overshoot the target.
        assertEquals(target, OmniMorphMotion.rectAt(source, target, 3f))
    }

    @Test
    fun cornerRadiusInterpolatesAndIsClampedToTheRect() {
        val roomy = Rect(0f, 0f, 100f, 200f)
        // 22dp -> 0dp over the transition.
        assertEquals(22f, OmniMorphMotion.radiusAt(22f, 0f, 0f, roomy), 1e-4f)
        assertEquals(11f, OmniMorphMotion.radiusAt(22f, 0f, 0.5f, roomy), 1e-4f)
        assertEquals(0f, OmniMorphMotion.radiusAt(22f, 0f, 1f, roomy), 1e-4f)
        // A radius wider than half the rect is clamped, not drawn inside out:
        // 40dp tall -> at most 20, 8dp tall -> at most 4.
        val flat = Rect(0f, 0f, 100f, 40f)
        assertEquals(20f, OmniMorphMotion.radiusAt(22f, 0f, 0f, flat), 1e-4f)
        val tiny = Rect(0f, 0f, 12f, 8f)
        assertEquals(4f, OmniMorphMotion.radiusAt(22f, 24f, 0.5f, tiny), 1e-4f)
    }

    @Test
    fun colourInterpolatesInSrgb() {
        val black = Color(0f, 0f, 0f, 1f)
        val white = Color(1f, 1f, 1f, 1f)
        val mid = OmniMorphMotion.colorAt(black, white, 0.5f)
        // Color quantises channels to 8 bit, so compare within one step.
        assertEquals(0.5f, mid.red, 0.01f)
        assertEquals(0.5f, mid.green, 0.01f)
        assertEquals(0.5f, mid.blue, 0.01f)
        assertEquals(1f, mid.alpha, 0.01f)
        assertEquals(black, OmniMorphMotion.colorAt(black, white, 0f))
        assertEquals(white, OmniMorphMotion.colorAt(black, white, 1f))
    }

    @Test
    fun popupSeedRectIsClampedIntoThePopupWindow() {
        val panel = Rect(0f, 0f, 280f, 300f)
        // The classic case: the control sits above the panel. Only its bottom
        // strip can be painted inside the popup window.
        val above = Rect(40f, -60f, 200f, -8f)
        val seed = OmniMorphMotion.clampedSourceRect(above, panel)
        // Zero intersection -> a visible strip along the nearest edge instead of
        // a degenerate rect the panel could never show.
        assertTrue(seed.width > 0f && seed.height > 0f)
        assertEquals(panel.top, seed.top, 1e-4f)
        assertTrue(seed.left >= panel.left && seed.right <= panel.right)

        // A control fully inside the window is used as-is.
        val inside = Rect(10f, 10f, 100f, 60f)
        assertEquals(inside, OmniMorphMotion.clampedSourceRect(inside, panel))

        // A control entirely outside still produces a visible seed.
        val outside = Rect(400f, 900f, 500f, 950f)
        val fallback = OmniMorphMotion.clampedSourceRect(outside, panel)
        assertTrue(fallback.width > 0f && fallback.height > 0f)
        assertTrue(fallback.left >= panel.left && fallback.right <= panel.right)
        assertTrue(fallback.top >= panel.top && fallback.bottom <= panel.bottom)
    }

    @Test
    fun interruptedAnimationKeepsAConstantSpeed() {
        assertEquals(450, OmniMorphMotion.remainingMillis(450, 0f))
        assertEquals(225, OmniMorphMotion.remainingMillis(450, 0.5f))
        // Never zero: a single frame still has to be animated.
        assertEquals(1, OmniMorphMotion.remainingMillis(450, 1f))
        assertEquals(500, OmniMorphMotion.remainingMillis(500, 0f))
    }

    // ── easing ──────────────────────────────────────────────────────────────

    @Test
    fun containerEasingRunsFromZeroToOneMonotonically() {
        val easing = OmniMorphMotion.ContainerEasing
        assertEquals(0f, easing.transform(0f), 1e-4f)
        assertEquals(1f, easing.transform(1f), 1e-4f)
        var previous = -1f
        for (i in 0..20) {
            val value = easing.transform(i / 20f)
            assertTrue("easing must not regress at $i", value >= previous - 1e-4f)
            previous = value
        }
        // fastOutSlowIn leaves early: more than half the distance by the middle.
        assertTrue(easing.transform(0.5f) > 0.5f)
    }

    @Test
    fun returnEasingIsThePointMirroredCurve() {
        val forward = OmniMorphMotion.ContainerEasing
        val backward = OmniMorphMotion.ContainerReturnEasing
        for (i in 1..19) {
            val t = i / 20f
            val mirrored = 1f - forward.transform(1f - t)
            assertTrue(
                "flipped curve mismatch at $t: $mirrored vs ${backward.transform(t)}",
                abs(mirrored - backward.transform(t)) < 1e-3f,
            )
        }
    }

    @Test
    fun dpLerpAndRadiusDefaultsAreTheMeasuredOnes() {
        assertEquals(11f, OmniMorphMotion.lerp(22.dp, 0.dp, 0.5f), 1e-3f)
        assertEquals(22f, OmniMorphMotion.RowSourceRadius.value, 1e-4f)
        assertEquals(24f, OmniMorphMotion.MenuPanelRadius.value, 1e-4f)
        assertEquals(12f, OmniMorphMotion.MenuSourceRadius.value, 1e-4f)
        assertEquals(0f, OmniMorphMotion.PageRadius.value, 1e-4f)
    }
}
