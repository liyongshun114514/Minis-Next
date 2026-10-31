package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-omnibot-composer-motion] Plain-JVM contract tests for the composer shell
 * morph, the focus glow and the flow ring.
 *
 * These exercise the real production objects ([ComposerMorphSpec],
 * [ComposerMorphMath], [ComposerFlowMath]) exactly as the draw path does, so a
 * wrong radius, duration, gradient stop or opacity ramp fails here instead of
 * only being visible when a human watches a device.
 *
 * Not covered here (deliberately not claimed): the actual pixels. `drawBehind`,
 * the shadow layer and the blur need a device/instrumentation test; this suite
 * proves the numbers and the arithmetic they feed.
 */
class ChatComposerMotionTest {

    // ── measured geometry ────────────────────────────────────────────────────

    @Test
    fun shellGeometryMatchesReference() {
        assertEquals(72f, ComposerMorphSpec.MinShellHeight.value, 0f)
        assertEquals(20f, ComposerMorphSpec.ShellRadius.value, 0f)
        assertEquals(1.5f, ComposerMorphSpec.BorderInset.value, 0f)
        assertEquals(1.5f, ComposerMorphSpec.FlowStroke.value, 0f)

        val padding = ComposerMorphSpec.ContentPadding
        assertEquals(14f, padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).value, 0f)
        assertEquals(8f, padding.calculateTopPadding().value, 0f)
        assertEquals(12f, padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).value, 0f)
        assertEquals(8f, padding.calculateBottomPadding().value, 0f)
    }

    @Test
    fun morphDurationsAreTheReferencePair() {
        assertEquals(220, ComposerMorphSpec.SHAPE_DURATION_MS)
        assertEquals(240, ComposerMorphSpec.INSET_DURATION_MS)
        // The shape must finish before the rim settles: the reference's
        // container animation is shorter than its padding animation.
        assertTrue(ComposerMorphSpec.SHAPE_DURATION_MS < ComposerMorphSpec.INSET_DURATION_MS)
        assertEquals(2400, ComposerMorphSpec.FLOW_PERIOD_MS)
    }

    @Test
    fun easeOutCubicHasUnitEndpointsAndIsMonotonic() {
        val easing = ComposerMorphSpec.Easing
        assertEquals(0f, easing.transform(0f), 1e-4f)
        assertEquals(1f, easing.transform(1f), 1e-4f)
        var previous = easing.transform(0f)
        var step = 0.05f
        while (step <= 1.0001f) {
            val value = easing.transform(step)
            assertTrue("easing must not go backwards at $step", value >= previous - 1e-4f)
            previous = value
            step += 0.05f
        }
        // easeOutCubic front-loads: more than half the distance by the middle.
        assertTrue(easing.transform(0.5f) > 0.6f)
    }

    // ── what drives the morph ───────────────────────────────────────────────

    @Test
    fun expandedRequiresFocusDraftOrAttachment() {
        assertFalse(ComposerMorphMath.isExpanded(focused = false, hasText = false, attachmentCount = 0))
        assertTrue("focus alone expands", ComposerMorphMath.isExpanded(true, false, 0))
        assertTrue("a draft alone expands", ComposerMorphMath.isExpanded(false, true, 0))
        assertTrue("an attachment alone expands", ComposerMorphMath.isExpanded(false, false, 1))
        assertTrue(ComposerMorphMath.isExpanded(true, true, 2))
    }

    @Test
    fun expandedSurvivesKeyboardDismissalWhileDraftExists() {
        // The point of the text/attachment terms: after the IME closes, a
        // composer that still holds a draft must stay a card.
        assertTrue(ComposerMorphMath.isExpanded(focused = false, hasText = true, attachmentCount = 0))
    }

    // ── shape interpolation ──────────────────────────────────────────────────

    @Test
    fun stadiumRadiusIsHalfTheShortestSide() {
        assertEquals(36f, ComposerMorphMath.stadiumRadiusPx(360f, 72f), 1e-3f)
        assertEquals(50f, ComposerMorphMath.stadiumRadiusPx(100f, 400f), 1e-3f)
        assertEquals(0f, ComposerMorphMath.stadiumRadiusPx(0f, 0f), 0f)
    }

    @Test
    fun cornerRadiusInterpolatesStadiumToRestingCard() {
        val stadium = ComposerMorphMath.stadiumRadiusPx(360f, 72f)
        assertEquals(stadium, ComposerMorphMath.cornerRadiusPx(0f, 360f, 72f, 20f), 1e-3f)
        assertEquals(20f, ComposerMorphMath.cornerRadiusPx(1f, 360f, 72f, 20f), 1e-3f)
        val mid = ComposerMorphMath.cornerRadiusPx(0.5f, 360f, 72f, 20f)
        assertEquals((stadium + 20f) / 2f, mid, 1e-3f)
        // The radius shrinks monotonically towards the 20dp card.
        val quarter = ComposerMorphMath.cornerRadiusPx(0.25f, 360f, 72f, 20f)
        assertTrue("radius must shrink from stadium to card", quarter > mid)
        assertTrue("progress must be clamped low", ComposerMorphMath.cornerRadiusPx(-3f, 360f, 72f, 20f) == stadium)
        assertTrue("progress must be clamped high", ComposerMorphMath.cornerRadiusPx(9f, 360f, 72f, 20f) == 20f)
    }

    @Test
    fun cornerRadiusNeverExceedsStadiumForTallComposers() {
        // A 6-line draft: height 240 against a 360 wide composer.
        val stadium = ComposerMorphMath.stadiumRadiusPx(360f, 240f)
        assertEquals(120f, stadium, 1e-3f)
        // A resting radius larger than the stadium is clamped, never inverted.
        assertEquals(stadium, ComposerMorphMath.cornerRadiusPx(1f, 360f, 240f, 300f), 1e-3f)
        val radius = ComposerMorphMath.cornerRadiusPx(0.37f, 360f, 240f, 20f)
        assertTrue(radius <= stadium + 1e-3f)
        assertTrue(radius >= 20f - 1e-3f)
    }

    @Test
    fun rimInsetAnimatesFromEdgeToBorderInset() {
        assertEquals(0f, ComposerMorphMath.rimInsetPx(0f, 1.5f), 1e-4f)
        assertEquals(1.5f, ComposerMorphMath.rimInsetPx(1f, 1.5f), 1e-4f)
        assertEquals(0.75f, ComposerMorphMath.rimInsetPx(0.5f, 1.5f), 1e-4f)
        assertEquals(1.5f, ComposerMorphMath.rimInsetPx(4f, 1.5f), 1e-4f)
    }

    @Test
    fun innerRadiusFollowsTheInsetAndNeverGoesNegative() {
        assertEquals(18.5f, ComposerMorphMath.innerRadiusPx(20f, 1.5f), 1e-3f)
        assertEquals(0f, ComposerMorphMath.innerRadiusPx(1f, 4f), 1e-3f)
        assertEquals(0f, ComposerMorphMath.innerRadiusPx(-2f, 1f), 1e-3f)
    }

    // ── focus glow ───────────────────────────────────────────────────────────

    @Test
    fun glowAlphaRampMatchesReference() {
        assertEquals(0.20f, ComposerFlowMath.glowAlpha(focused = true, hovered = false, isDark = false), 1e-4f)
        assertEquals(0.18f, ComposerFlowMath.glowAlpha(true, false, true), 1e-4f)
        assertEquals(0.15f, ComposerFlowMath.glowAlpha(false, true, false), 1e-4f)
        assertEquals(0.12f, ComposerFlowMath.glowAlpha(false, true, true), 1e-4f)
        assertEquals(0.10f, ComposerFlowMath.glowAlpha(false, false, false), 1e-4f)
        assertEquals(0.08f, ComposerFlowMath.glowAlpha(false, false, true), 1e-4f)
        // Focus wins over hover.
        assertEquals(
            ComposerFlowMath.glowAlpha(true, true, false),
            ComposerFlowMath.glowAlpha(true, false, false),
            1e-4f,
        )
    }

    @Test
    fun glowBlurWidensWhenFocused() {
        assertEquals(18f, ComposerFlowMath.glowBlurDp(true), 0f)
        assertEquals(12f, ComposerFlowMath.glowBlurDp(false), 0f)
    }

    // ── flow ring ───────────────────────────────────────────────────────────

    @Test
    fun breathIsASineThatStartsAtHalf() {
        assertEquals(0.5f, ComposerFlowMath.breath(0f), 1e-3f)
        assertEquals(1f, ComposerFlowMath.breath(0.25f), 1e-3f)
        assertEquals(0.5f, ComposerFlowMath.breath(0.5f), 1e-3f)
        assertEquals(0f, ComposerFlowMath.breath(0.75f), 1e-3f)
        // Cyclic: phase 1 is phase 0, phase -0.25 is phase 0.75.
        assertEquals(ComposerFlowMath.breath(0f), ComposerFlowMath.breath(1f), 1e-3f)
        assertEquals(ComposerFlowMath.breath(0.75f), ComposerFlowMath.breath(-0.25f), 1e-3f)
    }

    @Test
    fun flowSweepIsFasterWhileFocused() {
        assertEquals(1f, ComposerFlowMath.speed(false), 0f)
        assertEquals(1.6f, ComposerFlowMath.speed(true), 0f)
        assertEquals(-1f, ComposerFlowMath.shift(0f, focused = false), 1e-4f)
        assertEquals(0f, ComposerFlowMath.shift(0.5f, focused = false), 1e-4f)
        // Phase 1 is phase 0 (cyclic), so the sweep restarts at the left edge.
        assertEquals(-1f, ComposerFlowMath.shift(1f, focused = false), 1e-4f)
        assertEquals(0.6f, ComposerFlowMath.shift(0.5f, focused = true), 1e-4f)
        // Shift stays inside [-1, 1] for any phase, including negatives.
        var phase = -3f
        while (phase <= 3f) {
            val shift = ComposerFlowMath.shift(phase, focused = true)
            assertTrue("shift out of range at $phase: $shift", shift in -1.0001f..1.0001f)
            phase += 0.05f
        }
    }

    @Test
    fun ringOpacityIsFocusDrivenAndBreathesOnlyAtRest() {
        assertEquals(1f, ComposerFlowMath.opacity(focused = true, hovered = false, breath = 0.3f), 1e-4f)
        assertEquals(0.82f, ComposerFlowMath.opacity(false, true, 0.3f), 1e-4f)
        assertEquals(0.5f, ComposerFlowMath.opacity(false, false, 0.5f), 1e-4f)
        assertEquals(0.70f, ComposerFlowMath.opacity(false, false, 1f), 1e-4f)
        assertEquals(0.30f, ComposerFlowMath.opacity(false, false, 0f), 1e-4f)
        assertEquals(0.9f, ComposerFlowMath.opacity(false, false, 0f, forceStrong = true), 1e-4f)
        // Idle breathing never reaches the focused strength.
        assertTrue(ComposerFlowMath.opacity(false, false, 1f) < ComposerFlowMath.opacity(true, false, 1f))
    }

    @Test
    fun flowGradientUsesSixStopsAndKeepsHueOrder() {
        val stops = ComposerMorphSpec.FlowStops
        assertEquals(6, stops.size)
        assertEquals(0f, stops.first(), 0f)
        assertEquals(1f, stops.last(), 0f)
        var index = 1
        while (index < stops.size) {
            assertTrue("stops must ascend", stops[index] > stops[index - 1])
            index += 1
        }
        assertEquals(6, ComposerMorphSpec.LightFlowColors.size)
        assertEquals(6, ComposerMorphSpec.DarkFlowColors.size)

        val colors = ComposerMorphSpec.LightFlowColors
        assertEquals(Color(0xFFFF6A01), colors.first())
        assertEquals(colors.first(), colors.last())
        assertNotEquals(colors[0], colors[2])

        val resolved = ComposerFlowMath.colorStops(colors, 0.5f)
        assertEquals(6, resolved.size)
        assertEquals(0f, resolved.first().first, 0f)
        assertEquals(colors[2], resolved[2].second.copy(alpha = 1f))
        // Color alpha is 8-bit quantised (0.5 -> 0.5019…), so compare loosely.
        assertEquals(0.5f, resolved[2].second.alpha, 2e-3f)
        // Alpha is clamped, never extrapolated.
        assertEquals(1f, ComposerFlowMath.colorStops(colors, 4f)[0].second.alpha, 0f)
        assertEquals(0f, ComposerFlowMath.colorStops(colors, -1f)[0].second.alpha, 0f)
    }

    // ── interactive geometry ────────────────────────────────────────────────

    @Test
    fun tapSlotIsFortyEightAroundTheVisualCircle() {
        assertEquals(48f, ComposerMorphSpec.TapSlot.value, 0f)
        assertEquals(38f, ComposerMorphSpec.IconVisualSize.value, 0f)
        assertTrue(
            "the 48dp slot must contain the visual circle",
            ComposerMorphSpec.TapSlot > ComposerMorphSpec.IconVisualSize,
        )
        assertTrue(ComposerMorphSpec.TapSlot >= 48.dp)
    }
}
