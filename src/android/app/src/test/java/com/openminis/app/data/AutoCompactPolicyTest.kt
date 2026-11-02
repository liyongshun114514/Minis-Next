package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ctx-auto-threshold] The user's token threshold over the generated
 * [ContextPolicy]: the cap that keeps it from eating the headroom, the small
 * window it is allowed to re-enable, and the "not opted in" cases that must
 * stay byte-for-byte the old behaviour.
 *
 * Numbers are the tier table's own, so each assertion is checkable against
 * [ContextPolicy.forContextWindow] / [ContextPolicy.forUserCap].
 */
class AutoCompactPolicyTest {

    // ── "Not opted in" is exactly the base policy ───────────────────────

    @Test
    fun `disabled returns the base policy unchanged`() {
        val base = ContextPolicy.forContextWindow(128_000)
        assertSame(base, AutoCompactPolicy.apply(base, 128_000, enabled = false, thresholdTokens = 50_000))
    }

    @Test
    fun `null threshold returns the base policy unchanged`() {
        val base = ContextPolicy.forContextWindow(128_000)
        assertSame(base, AutoCompactPolicy.apply(base, 128_000, enabled = true, thresholdTokens = null))
    }

    @Test
    fun `zero threshold returns the base policy unchanged`() {
        val base = ContextPolicy.forContextWindow(128_000)
        assertSame(base, AutoCompactPolicy.apply(base, 128_000, enabled = true, thresholdTokens = 0))
    }

    @Test
    fun `negative threshold returns the base policy unchanged`() {
        val base = ContextPolicy.forContextWindow(128_000)
        assertSame(base, AutoCompactPolicy.apply(base, 128_000, enabled = true, thresholdTokens = -1))
        assertSame(base, AutoCompactPolicy.apply(base, 128_000, enabled = true, thresholdTokens = Int.MIN_VALUE))
    }

    @Test
    fun `unknown window returns the base policy unchanged`() {
        val base = ContextPolicy.forContextWindow(128_000)
        assertSame(base, AutoCompactPolicy.apply(base, 0, enabled = true, thresholdTokens = 50_000))
        assertSame(base, AutoCompactPolicy.apply(base, -1, enabled = true, thresholdTokens = 50_000))
    }

    // ── A base with its own line: min(user, base) ───────────────────────

    @Test
    fun `lower user threshold wins over the tier line`() {
        val base = ContextPolicy.forContextWindow(128_000)
        assertEquals(108_000, base.compactThreshold) // window - 20K
        val policy = AutoCompactPolicy.apply(base, 128_000, enabled = true, thresholdTokens = 60_000)
        assertEquals(60_000, policy.compactThreshold)
        // Everything else is the tier's, untouched.
        assertEquals(base.offloadThreshold, policy.offloadThreshold)
        assertEquals(base.offloadTarget, policy.offloadTarget)
        assertFalse(policy.exhaustedOnly)
        assertTrue(policy.manualCompactAllowed)
    }

    @Test
    fun `threshold above the tier line is capped by it`() {
        val base = ContextPolicy.forContextWindow(128_000)
        val policy = AutoCompactPolicy.apply(base, 128_000, enabled = true, thresholdTokens = Int.MAX_VALUE)
        assertEquals(108_000, policy.compactThreshold)
        assertEquals(base.offloadThreshold, policy.offloadThreshold)
        assertEquals(base.offloadTarget, policy.offloadTarget)
    }

    @Test
    fun `threshold equal to the tier line leaves it in place`() {
        val base = ContextPolicy.forContextWindow(128_000)
        val policy = AutoCompactPolicy.apply(base, 128_000, enabled = true, thresholdTokens = 108_000)
        assertEquals(base, policy)
    }

    @Test
    fun `a user cap keeps its protection and its flags`() {
        val base = ContextPolicy.forUserCap(128_000) // 85% line = 108_800
        val policy = AutoCompactPolicy.apply(base, 128_000, enabled = true, thresholdTokens = 50_000)
        assertEquals(50_000, policy.compactThreshold)
        assertEquals(89_600, policy.offloadThreshold) // 70%
        assertEquals(70_400, policy.offloadTarget) // 55%
        assertFalse(policy.exhaustedOnly)
        assertTrue(policy.manualCompactAllowed)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(50_000, 128_000),
        )
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(49_999, 128_000))
    }

    @Test
    fun `a user cap above the threshold still caps it at the user value`() {
        val base = ContextPolicy.forUserCap(128_000)
        val policy = AutoCompactPolicy.apply(base, 128_000, enabled = true, thresholdTokens = Int.MAX_VALUE)
        assertEquals(108_800, policy.compactThreshold)
    }

    // ── A base without a line: the window's 85% is the ceiling ──────────

    /** Under 32K the tier disables offload AND compaction and refuses a summary. */
    @Test
    fun `an under 32K window gets the explicit threshold and re-allows a summary`() {
        val base = ContextPolicy.forContextWindow(24_000) // no line, exhausted-only
        assertEquals(0, base.compactThreshold)
        assertTrue(base.exhaustedOnly)
        assertFalse(base.manualCompactAllowed)

        val policy = AutoCompactPolicy.apply(base, 24_000, enabled = true, thresholdTokens = 12_000)
        assertEquals(12_000, policy.compactThreshold)
        assertFalse(policy.exhaustedOnly)
        assertTrue(policy.manualCompactAllowed)
        // Offload stays exactly as the tier set it (disabled under 32K).
        assertEquals(0, policy.offloadThreshold)
        assertEquals(0, policy.offloadTarget)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(12_000, 24_000),
        )
    }

    @Test
    fun `a 32K window caps an oversized threshold at 85 percent`() {
        val base = ContextPolicy.forContextWindow(32_000) // line 0, offload at window - 10K
        assertEquals(0, base.compactThreshold)
        assertEquals(22_000, base.offloadThreshold)
        assertEquals(17_000, base.offloadTarget)

        val policy = AutoCompactPolicy.apply(base, 32_000, enabled = true, thresholdTokens = 100_000)
        assertEquals(27_200, policy.compactThreshold) // 32_000 * 85 / 100
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(27_200, 32_000),
        )
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(27_199, 32_000))
    }

    @Test
    fun `IntMax on a small window neither overflows nor escapes the cap`() {
        val base = ContextPolicy.forContextWindow(32_000)
        val policy = AutoCompactPolicy.apply(base, 32_000, enabled = true, thresholdTokens = Int.MAX_VALUE)
        assertEquals(27_200, policy.compactThreshold)
    }

    @Test
    fun `the 32K to 64K tier keeps its offload ladder`() {
        val base = ContextPolicy.forContextWindow(50_000)
        assertEquals(40_000, base.offloadThreshold)
        assertEquals(35_000, base.offloadTarget)
        assertEquals(0, base.compactThreshold)

        val policy = AutoCompactPolicy.apply(base, 50_000, enabled = true, thresholdTokens = 20_000)
        assertEquals(20_000, policy.compactThreshold)
        assertEquals(40_000, policy.offloadThreshold)
        assertEquals(35_000, policy.offloadTarget)
        assertFalse(policy.exhaustedOnly)
        assertTrue(policy.manualCompactAllowed)

        val capped = AutoCompactPolicy.apply(base, 50_000, enabled = true, thresholdTokens = Int.MAX_VALUE)
        assertEquals(42_500, capped.compactThreshold) // 50_000 * 85 / 100
    }

    /** 64K is the first tier with a line of its own (window - 10K). */
    @Test
    fun `64K tier line wins over a larger threshold`() {
        val base = ContextPolicy.forContextWindow(64_000)
        assertEquals(54_000, base.compactThreshold)
        val policy = AutoCompactPolicy.apply(base, 64_000, enabled = true, thresholdTokens = 120_000)
        assertEquals(54_000, policy.compactThreshold)
        assertEquals(44_000, policy.offloadThreshold) // window - 20K, untouched
    }

    // ── Boundaries of the threshold itself ──────────────────────────────

    @Test
    fun `the threshold is reached at exactly its value`() {
        val base = ContextPolicy.forContextWindow(128_000)
        val policy = AutoCompactPolicy.apply(base, 128_000, enabled = true, thresholdTokens = 80_000)
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(79_999, 128_000))
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(80_000, 128_000))
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(80_001, 128_000))
    }

    /** Only an upper cap exists: a tiny positive threshold is the user's call. */
    @Test
    fun `a threshold of one token is honoured`() {
        val base = ContextPolicy.forContextWindow(128_000)
        val policy = AutoCompactPolicy.apply(base, 128_000, enabled = true, thresholdTokens = 1)
        assertEquals(1, policy.compactThreshold)
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(1, 128_000))
    }

    @Test
    fun `applying twice is idempotent`() {
        val base = ContextPolicy.forContextWindow(50_000)
        val once = AutoCompactPolicy.apply(base, 50_000, enabled = true, thresholdTokens = 20_000)
        val twice = AutoCompactPolicy.apply(once, 50_000, enabled = true, thresholdTokens = 20_000)
        assertEquals(once, twice)
    }
}
