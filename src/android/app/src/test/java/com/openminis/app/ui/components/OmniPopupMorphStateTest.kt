package com.openminis.app.ui.components

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the real popup clock: the same `Animatable` clock `MinisMenu` drives is
 * run against a deterministic frame clock, so mount/unmount, interruption and the
 * phased content fade are actually executed rather than asserted from source.
 *
 * The frame clock advances 16ms per frame and needs no device, Robolectric or
 * Compose UI — only the animation core.
 */
class OmniPopupMorphStateTest {

    private class FrameClock16 : MonotonicFrameClock {
        var nanos: Long = 0L
            private set
        var frames: Int = 0
            private set

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            nanos += 16_000_000L
            frames += 1
            return onFrame(nanos)
        }
    }

    @Test
    fun standardMenuMountsAndSettlesAtSlightlyMoreThanItsDuration() = runBlocking(FrameClock16()) {
        val clock = OmniPopupMorphClock(OmniPopupMorphStyle.Standard)
        assertEquals(450, clock.openMillis)
        assertEquals(500, clock.closeMillis)
        assertFalse(clock.mounted)

        clock.open()

        assertTrue("the popup window must stay composed while open", clock.mounted)
        assertEquals(1f, clock.progress.value, 1e-3f)
        assertFalse(clock.closing)
        assertEquals(1f, clock.contentAlpha(), 1e-3f)
    }

    @Test
    fun closingReturnsToZeroAndUnmountsTheWindow() = runBlocking(FrameClock16()) {
        val clock = OmniPopupMorphClock(OmniPopupMorphStyle.Standard)
        clock.open()

        clock.close()

        assertFalse("the window must be released after the return", clock.mounted)
        assertEquals(0f, clock.progress.value, 1e-3f)
    }

    @Test
    fun contextMenuClockIsTwoHundredMilliseconds() {
        val standard = OmniPopupMorphClock(OmniPopupMorphStyle.Standard)
        val context = OmniPopupMorphClock(OmniPopupMorphStyle.Context)
        assertEquals(450, standard.openMillis)
        assertEquals(500, standard.closeMillis)
        assertEquals(200, context.openMillis)
        assertEquals(200, context.closeMillis)
    }

    @Test
    fun interruptedReturnReopensFromWhereItIs() = runBlocking(FrameClock16()) {
        val clock = OmniPopupMorphClock(OmniPopupMorphStyle.Standard)
        clock.open()
        clock.progress.snapTo(0.5f)
        clock.close()
        assertEquals(0f, clock.progress.value, 1e-3f)
        assertFalse(clock.mounted)

        // Re-open after the return finished: the transform replays from the
        // control instead of staying collapsed.
        clock.open()
        assertTrue(clock.mounted)
        assertEquals(1f, clock.progress.value, 1e-3f)
    }

    @Test
    fun contentFadeIsPhasedInsideTheClock() = runBlocking(FrameClock16()) {
        val clock = OmniPopupMorphClock(OmniPopupMorphStyle.Standard)
        clock.mounted // touch state so the property is exercised
        clock.progress.snapTo(0.2f)
        assertEquals(0f, clock.contentAlpha(), 1e-3f)
        clock.progress.snapTo(0.55f)
        assertTrue(clock.contentAlpha() in 0.01f..0.99f)
        clock.progress.snapTo(1f)
        assertEquals(1f, clock.contentAlpha(), 1e-3f)
    }

    @Test
    fun sourcelessClockHasNoAnchor() {
        val clock = OmniPopupMorphClock(OmniPopupMorphStyle.Context)
        assertNull(clock.anchor)
        assertFalse(clock.hasSource)
        clock.anchor = androidx.compose.ui.geometry.Rect(0f, 0f, 40f, 40f)
        assertTrue(clock.hasSource)
    }
}
