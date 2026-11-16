package com.openminis.app.agent

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [K-global-prompt-only] There is no application-side body length policy any
 * more — not for the default, not for a stored NATIVE/CUSTOM value left on
 * disk by an older build, and not for `forced` test overrides (the type that
 * carried them is gone). This file used to pin the language-aware caps; it now
 * pins their absence at both the source and the prompt-build level.
 */
class SoulBodyLimitPolicyTest {

    private val soul = ProductionSources.read("agent/SoulStore.kt")
    private val builtins = ProductionSources.read("config/ConfigBuiltins.kt")

    @Test
    fun `the length policy types no longer exist in production`() {
        val offenders = ProductionSources.allKotlinFiles().filter { f ->
            val text = f.readText()
            text.contains("SoulBodyLimitPolicy") ||
                text.contains("SoulBodyLimitSettings") ||
                text.contains("SoulBodyLimitCheck") ||
                text.contains("isOverLimit")
        }.map { it.name }
        assertEquals("no length gate type or call site may remain: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `a body far past the old chinese cap is sent whole`() {
        val body = "汉".repeat(60_000)
        val block = SystemPromptBuilder.applicationPrompt(SoulFile(SoulMetadata.DEFAULT, body))
        assertNotNull(block)
        assertTrue("every character must reach the provider", block!!.contains(body))
        assertEquals(SystemPromptBuilder.SUPREME_PREAMBLE, block.substringBefore("\n\n"))

        val rendered = SystemPromptBuilder.render(SoulFile(SoulMetadata.DEFAULT, body))
        assertTrue(rendered.contains(body))
    }

    @Test
    fun `a body far past the old english cap is sent whole`() {
        val body = (1..20_000).joinToString(" ") { "word$it" }
        val block = SystemPromptBuilder.applicationPrompt(SoulFile(SoulMetadata.DEFAULT, body))
        assertNotNull(block)
        assertTrue(block!!.contains(body))
        assertTrue(block.contains("word20000"))
    }

    @Test
    fun `legacy stored settings and forced overrides cannot gate anything`() {
        // The SharedPreferences namespace that held mode/caps is not read by
        // any production source, so an old CUSTOM / NATIVE value on a device
        // has no effect: there is no reader to honour it.
        assertFalse(soul.contains("soul_body_limit"))
        assertFalse(soul.contains("SharedPreferences"))
        assertFalse(soul.contains("CHINESE_CHAR_LIMIT"))
        assertFalse(soul.contains("ENGLISH_WORD_LIMIT"))
        assertFalse(soul.contains("SoulBodyLimitMode"))
        for (f in ProductionSources.allKotlinFiles()) {
            assertFalse(
                "unexpected legacy length-prefs read in ${f.name}",
                f.readText().contains("soul_body_limit"),
            )
        }
    }

    @Test
    fun `the writer accepts a long body and only rejects injection`() {
        assertFalse(builtins.contains("isOverLimit"))
        assertFalse(builtins.contains("OverLimitChinese"))
        assertTrue("the injection guard must stay", builtins.contains("containsInjectionPattern(raw)"))
        val writer = builtins.substringAfter("path = \"soul.body\"")
        assertTrue("the body is stored verbatim", writer.contains("saveCurrent(cur.copy(body = raw))"))
        assertFalse("no length rejection text", writer.contains("Over limit:"))
    }

    @Test
    fun `no omission or truncation branch is left in the prompt builder`() {
        assertFalse(soul.contains("over the configured length limit"))
        assertFalse(soul.contains("falling back to identity-only"))
        assertFalse(soul.contains("custom prompt rule"))
        // The only body transformation left is the injection scrubber.
        assertTrue(soul.contains("scrubInjections(trimmed)"))
    }
}
