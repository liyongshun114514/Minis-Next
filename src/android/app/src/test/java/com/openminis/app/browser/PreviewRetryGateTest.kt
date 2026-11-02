package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-browser-preview-live] The GPU capture path must degrade, never die.
 *
 * The old kill switch (`disabled` after three failures, for the life of the
 * process) turned one trip through the background into a permanently frozen
 * thumbnail: every later capture fell back to the detached software draw, which
 * returns Chromium's last composited frame. These tests pin the replacement
 * contract — bounded cooldown, always a later retry, reset on success.
 */
class PreviewRetryGateTest {

    private var clock = 10_000L
    private val gate = PreviewRetryGate(now = { clock })

    @Test
    fun `attempts are allowed until something fails`() {
        assertTrue(gate.allow())
        gate.recordSuccess()
        assertTrue(gate.allow())
        assertEquals(0, gate.failureCount)
    }

    @Test
    fun `a failure cools down for a bounded time and then retries`() {
        gate.recordFailure("Presentation.show: BadTokenException")
        assertFalse("immediately after a failure", gate.allow())
        assertEquals("Presentation.show: BadTokenException", gate.lastReason)
        clock += PreviewRetryGate.DEFAULT_COOLDOWNS_MS[1]
        assertTrue("the path must come back after the cooldown", gate.allow())
    }

    @Test
    fun `repeated failures back off but never stop trying`() {
        var lastCooldown = 0L
        repeat(50) { attempt ->
            if (attempt > 0) {
                clock += lastCooldown
                assertTrue("attempt $attempt must be allowed after its cooldown", gate.allow())
            }
            gate.recordFailure("no composited frame")
            val cooldown = gate.cooldownRemainingMs()
            assertTrue("cooldown is positive", cooldown > 0)
            assertTrue("cooldown is bounded ($cooldown)", cooldown <= PreviewRetryGate.MAX_COOLDOWN_MS)
            lastCooldown = cooldown
        }
        // Even after 50 failures the next allowed attempt is a minute away, not
        // never.
        clock += lastCooldown
        assertTrue(gate.allow())
    }

    @Test
    fun `a success clears the ladder and the recorded reason`() {
        gate.recordFailure("host setup failed")
        gate.recordFailure("host setup failed")
        gate.recordSuccess()
        assertEquals(0, gate.failureCount)
        assertEquals(null, gate.lastReason)
        assertTrue(gate.allow())
        // …and the next failure starts the ladder from the bottom again.
        gate.recordFailure("host setup failed")
        assertFalse(gate.allow())
        clock += PreviewRetryGate.DEFAULT_COOLDOWNS_MS[1]
        assertTrue(gate.allow())
    }
}
