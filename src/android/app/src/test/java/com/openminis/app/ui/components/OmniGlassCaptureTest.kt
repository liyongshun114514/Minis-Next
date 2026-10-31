/*
 * Minis Next — glass capture lifecycle and throttling tests.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * The controller is the only place that decides whether the scope pays for a
 * second render of its content, so its bookkeeping is tested directly: attach
 * counting (a pane that never detaches would keep a full-screen bitmap alive),
 * the loop guard (a capture inside a capture), and the failure latch (a device
 * that cannot rasterise must stop trying). No bitmap is allocated here — that
 * needs Android — but every *decision* is executed for real.
 */
package com.openminis.app.ui.components

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniGlassCaptureTest {

    private fun controller(
        quality: OmniGlassQuality = OmniGlassQuality.Balanced,
        enabled: Boolean = true,
    ) = OmniGlassCaptureController(quality, enabled)

    @Test
    fun paneAttachmentsAreCountedAndReleased() {
        val c = controller()
        assertEquals(0, c.attachedPanels)
        c.noteScopeSize(Size(1080f, 2400f))
        assertFalse("no pane, no capture", c.shouldCapture(1_000))

        c.attachPanel()
        assertEquals(1, c.attachedPanels)
        assertTrue(c.shouldCapture(1_000))

        c.attachPanel()
        assertEquals(2, c.attachedPanels)
        c.detachPanel()
        assertEquals(1, c.attachedPanels)
        c.detachPanel()
        assertEquals(0, c.attachedPanels)
        // Never underflow: a stray detach must not wrap the counter around.
        c.detachPanel()
        assertEquals(0, c.attachedPanels)
        assertFalse(c.shouldCapture(9_999))
    }

    @Test
    fun lastPaneLeavingDropsTheBackdropReference() {
        val c = controller()
        c.attachPanel()
        c.detachPanel()
        assertNull("the capture must not outlive the panes", c.backdrop)
        c.release()
        assertEquals(0, c.attachedPanels)
    }

    @Test
    fun invalidateForcesAFreshCapture() {
        val c = controller(OmniGlassQuality.Static)
        c.attachPanel()
        c.noteScopeSize(Size(800f, 600f))
        assertTrue(c.shouldCapture(1_000))
        // Simulate a publish without allocating a bitmap.
        c.publishCaptureForTest(1_000)
        assertFalse("throttled right after a capture", c.shouldCapture(1_100))
        c.invalidate()
        assertTrue("an explicit invalidate bypasses the throttle", c.shouldCapture(1_100))
    }

    @Test
    fun scopeSizeChangesAreTreatedAsGeometryChanges() {
        val c = controller(OmniGlassQuality.Static)
        c.attachPanel()
        c.noteScopeSize(Size(800f, 600f))
        c.publishCaptureForTest(1_000)
        assertFalse(c.shouldCapture(1_010))
        c.noteScopeSize(Size(800f, 1200f))
        assertTrue("a resize must refresh the capture", c.shouldCapture(1_010))
    }

    @Test
    fun aCaptureInFlightBlocksAnother() {
        val c = controller()
        c.attachPanel()
        c.noteScopeSize(Size(800f, 600f))
        val nested = OmniGlassCaptureGuard.suppressing {
            // The scope asks again from inside the recording pass.
            c.shouldCapture(1_000)
        }
        assertFalse("a capture must never start inside a capture", nested)
        assertTrue("the guard is balanced", c.shouldCapture(1_001))
    }

    @Test
    fun guardIsBalancedEvenWhenTheCaptureBodyThrows() {
        assertFalse(OmniGlassCaptureGuard.capturing)
        try {
            OmniGlassCaptureGuard.suppressing {
                assertTrue(OmniGlassCaptureGuard.capturing)
                throw IllegalStateException("rasteriser failed")
            }
        } catch (expected: IllegalStateException) {
            // ignored
        }
        assertFalse("a failed capture must not leave every pane invisible", OmniGlassCaptureGuard.capturing)
    }

    @Test
    fun repeatedFailuresLatchTheScopeOff() {
        val c = controller()
        c.attachPanel()
        c.noteScopeSize(Size(800f, 600f))
        repeat(3) { c.noteCaptureFailure() }
        assertFalse("after three failures the scope stops capturing", c.shouldCapture(10_000))
        assertEquals(3, c.captureFailures)
    }

    @Test
    fun disablingTheScopeStopsCapturesAndDropsTheBitmap() {
        val c = controller()
        c.attachPanel()
        c.noteScopeSize(Size(800f, 600f))
        assertTrue(c.shouldCapture(1_000))
        c.enabled = false
        assertFalse(c.shouldCapture(2_000))
        assertNull(c.backdrop)
        c.enabled = true
        assertTrue("re-enabling refreshes immediately", c.shouldCapture(2_000))
    }

    @Test
    fun qualityProfilesAreOrderedFromCheapToRich() {
        val static = OmniGlassQuality.Static
        val balanced = OmniGlassQuality.Balanced
        assertTrue(static.capturePixelBudget < balanced.capturePixelBudget)
        assertTrue(static.refreshIntervalMs > balanced.refreshIntervalMs)
        assertTrue(static.maxCaptureScale <= balanced.maxCaptureScale)
        for (profile in OmniGlassQuality.entries) {
            assertTrue(profile.capturePixelBudget in 100_000..4_000_000)
            assertTrue(profile.refreshIntervalMs >= 33L)
            assertTrue(profile.maxCaptureScale in OmniGlassCaptureMath.MIN_CAPTURE_SCALE..1f)
        }
    }

    @Test
    fun liveProfileHasAnExplicitMotionBudgetNotASlowAlias() {
        val live = OmniGlassQuality.Live
        assertTrue(live !== OmniGlassQuality.Balanced)
        assertEquals(33L, live.refreshIntervalMs)
        assertEquals(480_000, live.capturePixelBudget)
        assertEquals(0.5f, live.maxCaptureScale, 0f)
        val c = controller(live)
        c.attachPanel()
        c.noteScopeSize(Size(1080f, 2400f))
        c.publishCaptureForTest(1_000L)
        assertFalse(c.shouldCapture(1_032L))
        assertTrue(c.shouldCapture(1_033L))
    }

    @Test
    fun aPhoneScopeFitsEveryProfilesBudget() {
        val phone = Size(1080f, 2400f)
        for (profile in OmniGlassQuality.entries) {
            val scale = OmniGlassCaptureMath.captureScale(phone, profile.capturePixelBudget, profile.maxCaptureScale)
            val size = OmniGlassCaptureMath.captureSize(phone, scale)
            val pixels = size.width.toLong() * size.height.toLong()
            assertTrue("${profile.name} produced $pixels px", pixels <= profile.capturePixelBudget)
            // Big enough to refract text and images rather than mush.
            assertTrue("${profile.name} produced $size", size.width >= 200)
        }
    }

    @Test
    fun theFirstFrameCapturesWithoutGeometryHistory() {
        // A static screen must still get glass on its very first frame: the
        // capture is not tied to a scroll or a resize.
        val c = controller()
        c.attachPanel()
        c.noteScopeSize(Size(1080f, 2400f))
        assertTrue("a fresh scope must capture immediately", c.shouldCapture(0L))
    }

    @Test
    fun publishingDoesNotScheduleAnotherCapture() {
        // The publish -> invalidation -> capture loop is the self-spinning bug.
        // A published capture must not mark geometry dirty, and must hold the
        // throttle, so an idle screen settles instead of re-rendering forever.
        val c = controller(OmniGlassQuality.Balanced)
        c.attachPanel()
        c.noteScopeSize(Size(800f, 600f))
        assertTrue(c.shouldCapture(1_000))
        c.publishCaptureForTest(1_000)
        repeat(10) { step ->
            assertFalse("frame $step must not capture", c.shouldCapture(1_000L + step * 16L))
        }
        // ...and it becomes eligible again once the interval really elapsed.
        assertTrue(c.shouldCapture(1_000L + OmniGlassQuality.Balanced.refreshIntervalMs))
    }

    @Test
    fun aPaneAppearingMidSessionRequestsACapture() {
        val c = controller(OmniGlassQuality.Static)
        c.noteScopeSize(Size(800f, 600f))
        c.publishCaptureForTest(5_000)
        assertFalse("no pane, nothing to capture", c.shouldCapture(6_000))
        c.attachPanel()
        assertTrue("a new pane must see a fresh backdrop", c.shouldCapture(6_000))
    }

    @Test
    fun anIdleScopeDrawsNoCapture() {
        // No pane, no capture: the scope must be a plain draw call in that case.
        val c = controller()
        c.noteScopeSize(Size(1080f, 2400f))
        repeat(10) { frame -> assertFalse(c.shouldCapture(frame * 16L)) }
        assertEquals(0L, c.captures)
    }
}
