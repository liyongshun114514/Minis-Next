package com.openminis.app.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-native-composer-soul-limit] [T-soul-unlimited-default] Pure-JVM tests for
 * the length policy.
 *
 * Only `SoulStore.isOverLimit(body, settings)` is touched: no Context, no
 * SharedPreferences, no prompt construction. The security boundary is
 * covered separately by the source contract test (injection patterns and
 * scrubber byte-identical to HEAD).
 *
 * [T-soul-unlimited-default] The policy's DEFAULT is now UNLIMITED, and a
 * stored NATIVE is folded into it. The first tests below pin that; the
 * NATIVE-cap tests are kept as CUSTOM tests so the caps themselves stay
 * covered (that is the only mode that still counts anything).
 */
class SoulBodyLimitPolicyTest {

    private val defaultSettings = SoulBodyLimitSettings()

    @After
    fun clearForcedPolicy() {
        SoulBodyLimitPolicy.forced = null
    }

    @Test
    fun `default mode is unlimited`() {
        assertEquals(SoulBodyLimitMode.UNLIMITED, defaultSettings.mode)
        assertTrue(defaultSettings.mode.isUnlimited)
    }

    @Test
    fun `unlimited and stale native are both unlimited, only custom counts`() {
        assertTrue(SoulBodyLimitMode.UNLIMITED.isUnlimited)
        assertTrue("a stored NATIVE from an older build reads as unlimited",
            SoulBodyLimitMode.NATIVE.isUnlimited)
        assertEquals(false, SoulBodyLimitMode.CUSTOM.isUnlimited)
    }

    @Test
    fun `default unlimited accepts a body far beyond either native cap`() {
        assertSame(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit("汉".repeat(50_000)))
        assertSame(
            SoulBodyLimitCheck.Ok,
            SoulStore.isOverLimit((1..20_000).joinToString(" ") { "w$it" }),
        )
    }

    @Test
    fun `unlimited accepts a body far beyond either native cap`() {
        val unlimited = SoulBodyLimitSettings(mode = SoulBodyLimitMode.UNLIMITED)
        assertSame(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit("汉".repeat(50_000), unlimited))
        assertSame(
            SoulBodyLimitCheck.Ok,
            SoulStore.isOverLimit((1..20_000).joinToString(" ") { "w$it" }, unlimited),
        )
    }

    @Test
    fun `a stored native limit no longer rejects a long body`() {
        // What an older build left on disk: mode=NATIVE with the caps filled
        // in (they were the defaults). It must NOT gate the save any more.
        val staleNative = SoulBodyLimitSettings(
            mode = SoulBodyLimitMode.NATIVE,
            chineseCharLimit = 1600,
            englishWordLimit = 1000,
        )
        assertSame(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit("汉".repeat(50_000), staleNative))
        assertSame(
            SoulBodyLimitCheck.Ok,
            SoulStore.isOverLimit((1..5_000).joinToString(" ") { "w$it" }, staleNative),
        )
    }

    @Test
    fun `custom CJK cap is enforced at the code-point boundary`() {
        val custom = SoulBodyLimitSettings(
            mode = SoulBodyLimitMode.CUSTOM,
            chineseCharLimit = 1600,
            englishWordLimit = 1000,
        )
        assertSame(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit("汉".repeat(1600), custom))
        val over = SoulStore.isOverLimit("汉".repeat(1601), custom)
        assertTrue(over is SoulBodyLimitCheck.OverLimitChinese)
        assertEquals(1601, (over as SoulBodyLimitCheck.OverLimitChinese).chars)
        assertEquals(1600, over.cap)
    }

    @Test
    fun `custom English cap is enforced at the word boundary`() {
        val custom = SoulBodyLimitSettings(
            mode = SoulBodyLimitMode.CUSTOM,
            chineseCharLimit = 1600,
            englishWordLimit = 1000,
        )
        val at = (1..1000).joinToString(" ") { "w$it" }
        val overBody = (1..1001).joinToString(" ") { "w$it" }
        assertSame(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit(at, custom))
        val over = SoulStore.isOverLimit(overBody, custom)
        assertTrue(over is SoulBodyLimitCheck.OverLimitEnglish)
        assertEquals(1001, (over as SoulBodyLimitCheck.OverLimitEnglish).words)
        assertEquals(1000, over.cap)
    }

    @Test
    fun `custom caps are enforced per majority language`() {
        val custom = SoulBodyLimitSettings(
            mode = SoulBodyLimitMode.CUSTOM,
            chineseCharLimit = 10,
            englishWordLimit = 5,
        )
        assertSame(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit("汉".repeat(10), custom))
        val cjkOver = SoulStore.isOverLimit("汉".repeat(11), custom)
        assertTrue(cjkOver is SoulBodyLimitCheck.OverLimitChinese)
        assertEquals(10, (cjkOver as SoulBodyLimitCheck.OverLimitChinese).cap)

        assertSame(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit("a b c d e", custom))
        val enOver = SoulStore.isOverLimit("a b c d e f", custom)
        assertTrue(enOver is SoulBodyLimitCheck.OverLimitEnglish)
        assertEquals(5, (enOver as SoulBodyLimitCheck.OverLimitEnglish).cap)
    }

    @Test
    fun `empty and whitespace bodies stay ok in every mode`() {
        for (mode in SoulBodyLimitMode.entries) {
            val settings = SoulBodyLimitSettings(mode = mode)
            assertSame(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit("", settings))
            assertSame(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit("  \n\t ", settings))
        }
    }

    @Test
    fun `settings normalise custom caps into the supported range`() {
        val low = SoulBodyLimitSettings(
            mode = SoulBodyLimitMode.CUSTOM,
            chineseCharLimit = 0,
            englishWordLimit = -5,
        ).normalized()
        assertEquals(SoulBodyLimitSettings.MIN_CUSTOM_LIMIT, low.chineseCharLimit)
        assertEquals(SoulBodyLimitSettings.MIN_CUSTOM_LIMIT, low.englishWordLimit)

        val high = SoulBodyLimitSettings(
            mode = SoulBodyLimitMode.CUSTOM,
            chineseCharLimit = Int.MAX_VALUE,
            englishWordLimit = Int.MAX_VALUE,
        ).normalized()
        assertEquals(SoulBodyLimitSettings.MAX_CUSTOM_LIMIT, high.chineseCharLimit)
        assertEquals(SoulBodyLimitSettings.MAX_CUSTOM_LIMIT, high.englishWordLimit)
    }

    /**
     * [T-soul-unlimited-default] The cache the Context-less calls read must
     * itself resolve to UNLIMITED out of the box — this is the state a fresh
     * install boots in, before any SharedPreferences read.
     */
    @Test
    fun `the process-wide default cache is unlimited`() {
        assertNull(SoulBodyLimitPolicy.forced)
        assertEquals(SoulBodyLimitMode.UNLIMITED, SoulBodyLimitPolicy.current().mode)
        // The value the Context-less `isOverLimit(body)` overload reads is
        // derived from that same cache, so the two cannot disagree.
        // The Context-less `isOverLimit(body)` overload routes through
        // current(), so pin the cache and observe the public entry point.
        SoulBodyLimitPolicy.forced = SoulBodyLimitSettings(mode = SoulBodyLimitMode.UNLIMITED)
        assertSame(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit("汉".repeat(50_000)))
    }
}
