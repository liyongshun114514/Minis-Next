package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-native-composer-soul-limit] Pure-JVM tests for the length policy.
 *
 * Only `SoulStore.isOverLimit(body, settings)` is touched: no Context, no
 * SharedPreferences, no prompt construction. The security boundary is
 * covered separately by the source contract test (injection patterns and
 * scrubber byte-identical to HEAD).
 */
class SoulBodyLimitPolicyTest {

    private val native = SoulBodyLimitSettings()

    @Test
    fun `native CJK cap is unchanged at 1600 code points`() {
        assertSame(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit("汉".repeat(1600), native))
        val over = SoulStore.isOverLimit("汉".repeat(1601), native)
        assertTrue(over is SoulBodyLimitCheck.OverLimitChinese)
        assertEquals(1601, (over as SoulBodyLimitCheck.OverLimitChinese).chars)
        assertEquals(1600, over.cap)
    }

    @Test
    fun `native English cap is unchanged at 1000 words`() {
        val at = (1..1000).joinToString(" ") { "w$it" }
        val overBody = (1..1001).joinToString(" ") { "w$it" }
        assertSame(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit(at, native))
        val over = SoulStore.isOverLimit(overBody, native)
        assertTrue(over is SoulBodyLimitCheck.OverLimitEnglish)
        assertEquals(1001, (over as SoulBodyLimitCheck.OverLimitEnglish).words)
        assertEquals(1000, over.cap)
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
}
