package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-browser-preview-live] Executable rules of the live-preview pump.
 *
 * Every test drives production [BrowserPreviewPump] with an injected clock, so
 * "did the thumbnail update?" is answered here instead of on a device. These
 * cover the four defects the old in-composable poller had; see the class doc.
 */
class BrowserPreviewPumpTest {

    private var clock = 1_000L
    private val policy = BrowserPreviewPump.Policy(
        minIntervalMs = 100L,
        maxIntervalMs = 800L,
        followUpDelaysMs = listOf(20L, 60L),
        minSpacingMs = 10L,
        idleIntervalMs = 200L,
    )

    private fun pump() = BrowserPreviewPump<String>(policy) { clock }

    @Test
    fun `a target change publishes only after a capture, never another tab's pixels`() {
        val pump = pump()
        pump.onTarget(PreviewTarget(1, "a"))
        pump.beginAttempt()
        pump.onCaptureSuccess(1, "page-1")
        assertEquals("page-1", pump.frame?.image)

        // Same tab, new page: the old image stays until the new frame lands, so
        // the thumbnail does not blink, and a capture is due immediately (the
        // spacing floor still applies between two attempts).
        clock += policy.minSpacingMs
        pump.onTarget(PreviewTarget(1, "b"))
        assertTrue("new signature must schedule a capture", pump.isDue())
        assertEquals("page-1", pump.frame?.image)

        // Different tab: the previous tab's frame must not be shown at all.
        clock += 5
        pump.onTarget(PreviewTarget(2, "c"))
        assertNull("a different tab must not reuse the previous frame", pump.frame)
        pump.beginAttempt()
        pump.onCaptureSuccess(2, "page-2")
        assertEquals(2, pump.frame?.tabId)
        assertEquals("page-2", pump.frame?.image)
    }

    @Test
    fun `a fast action still gets a post-action frame`() {
        // The old loop captured once on the leading edge and then slept 3 s, so
        // a 150 ms action only ever produced its PRE-action frame.
        val pump = pump()
        pump.onTarget(PreviewTarget(1, "before"))
        clock += 1
        pump.beginAttempt()
        pump.onCaptureSuccess(1, "before-shot")

        // The action finishes and the page changes; the follow-up must be due
        // well before the steady-state interval elapses.
        clock += 150
        pump.onActivity()
        assertTrue("activity schedules the leading capture", pump.isDue())
        pump.beginAttempt()
        pump.onCaptureSuccess(1, "after-shot")
        assertEquals("after-shot", pump.frame?.image)

        // ...and once more after the renderer settles, even with no further
        // event: that is the second follow-up offset (60 ms).
        clock += 20
        pump.beginAttempt()
        pump.onCaptureSuccess(1, "after-shot")
        assertTrue("follow-up still pending", !pump.isDue())
        clock += 45
        assertTrue("follow-up capture must be due", pump.isDue())
    }

    @Test
    fun `a failed capture keeps the last image, marks it stale and retries with backoff`() {
        val pump = pump()
        pump.onTarget(PreviewTarget(1, "a"))
        pump.beginAttempt()
        pump.onCaptureSuccess(1, "shot")

        clock += 100
        pump.beginAttempt()
        pump.onCaptureFailure(1, "gpu miss")
        assertEquals("the last good pixels are kept", "shot", pump.frame?.image)
        assertTrue("but they are marked stale", pump.frame?.stale == true)
        assertEquals("gpu miss", pump.frame?.reason)
        assertEquals(1, pump.failureCount)

        // Backoff grows but never stops.
        val firstRetry = pump.delayUntilDue()
        assertTrue("retry scheduled", firstRetry > 0)
        clock += firstRetry
        assertTrue("retry is due after the backoff", pump.isDue())
        pump.beginAttempt()
        pump.onCaptureFailure(1, "gpu miss")
        val secondRetry = pump.delayUntilDue()
        assertTrue("backoff must grow, was $firstRetry then $secondRetry", secondRetry > firstRetry)
        assertTrue("but stays bounded", secondRetry <= policy.maxIntervalMs)

        // A success clears the ladder and the stale flag (recovery, not a
        // permanent downgrade).
        clock += secondRetry
        pump.beginAttempt()
        pump.onCaptureSuccess(1, "fresh")
        assertEquals("fresh", pump.frame?.image)
        assertFalse(pump.frame?.stale == true)
        assertNull(pump.frame?.reason)
        assertEquals(0, pump.failureCount)
    }

    @Test
    fun `an untrusted frame is published but counted as a miss`() {
        val pump = pump()
        pump.onTarget(PreviewTarget(1, "a"))
        clock += 1
        pump.beginAttempt()
        pump.onCapturePartial(1, "detached-draw", "detached software draw")
        assertEquals("show something rather than nothing", "detached-draw", pump.frame?.image)
        assertTrue("but never call it live", pump.frame?.stale == true)
        assertEquals(1, pump.failureCount)
    }

    @Test
    fun `background pauses and foreground forces a fresh capture`() {
        val pump = pump()
        pump.onTarget(PreviewTarget(1, "a"))
        pump.beginAttempt()
        pump.onCaptureSuccess(1, "shot")
        clock += 100
        pump.onPaused()
        assertFalse("no captures while backgrounded", pump.isDue())
        clock += 10_000
        assertFalse("still paused after a long wait", pump.isDue())

        pump.onResumed()
        assertTrue("returning to the foreground must re-capture", pump.isDue())
        pump.beginAttempt()
        pump.onCaptureSuccess(1, "resumed")
        assertEquals("resumed", pump.frame?.image)
    }

    @Test
    fun `spacing floor prevents follow-ups from hammering the renderer`() {
        val pump = pump()
        pump.onTarget(PreviewTarget(1, "a"))
        clock += 1
        pump.beginAttempt()      // last attempt at 1001
        pump.onCaptureSuccess(1, "shot")
        pump.onActivity()        // follow-ups at clock+20 / clock+60
        assertFalse("min spacing gates the immediate follow-up", pump.isDue())
        clock += policy.minSpacingMs
        assertTrue(pump.isDue())
    }

    @Test
    fun `frame identity is replaced on success, not mutated in place`() {
        // Compose only re-reads state when the value changes; the old producer
        // assigned a new Bitmap per tick, so this pins the same contract.
        val pump = pump()
        pump.onTarget(PreviewTarget(1, "a"))
        pump.beginAttempt()
        pump.onCaptureSuccess(1, "one")
        val first = pump.frame
        clock += 100
        pump.beginAttempt()
        pump.onCaptureSuccess(1, "two")
        val second = pump.frame
        assertTrue(!(first === second))
        assertEquals("two", second?.image)
        assertSame("two", pump.frame?.image)
    }
}
