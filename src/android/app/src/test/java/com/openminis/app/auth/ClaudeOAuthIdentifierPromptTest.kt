package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression contract for the Claude Code OAuth identity line.
 *
 * The property used to THROW when the build had no
 * `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT`, and ordinary sends read it — including
 * before the OAuth check in ChatViewModel — so a public build surfaced
 * "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT is not configured" on every message.
 * The getter is now total: it resolves a built-in line and never fails.
 */
class ClaudeOAuthIdentifierPromptTest {

    @Test
    fun `built-in default is the Claude Code identity line`() {
        assertEquals(
            "You are Claude Code, Anthropic's official CLI for Claude.",
            ClaudeOAuthManager.DEFAULT_ANTHROPIC_OAUTH_IDENTIFIER_PROMPT,
        )
        // The gate matches the identity as a sentence; trailing punctuation is
        // part of the text Claude Code sends.
        assertTrue(ClaudeOAuthManager.DEFAULT_ANTHROPIC_OAUTH_IDENTIFIER_PROMPT.endsWith("."))
    }

    @Test
    fun `the getter is never empty and never throws`() {
        // The exact bug: this call sat on every send path, OAuth or not.
        val value = ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        assertTrue(value.isNotBlank())
        assertEquals(value, value.trim())
    }

    @Test
    fun `an unconfigured build falls back to the built-in default`() {
        assertEquals(
            ClaudeOAuthManager.DEFAULT_ANTHROPIC_OAUTH_IDENTIFIER_PROMPT,
            ClaudeOAuthManager.resolveOAuthIdentifierPrompt(""),
        )
        assertEquals(
            ClaudeOAuthManager.DEFAULT_ANTHROPIC_OAUTH_IDENTIFIER_PROMPT,
            ClaudeOAuthManager.resolveOAuthIdentifierPrompt(null),
        )
        // properties files often keep a stray blank after '='
        assertEquals(
            ClaudeOAuthManager.DEFAULT_ANTHROPIC_OAUTH_IDENTIFIER_PROMPT,
            ClaudeOAuthManager.resolveOAuthIdentifierPrompt("   \n\t "),
        )
    }

    @Test
    fun `a configured value wins and is trimmed`() {
        assertEquals("Custom identity", ClaudeOAuthManager.resolveOAuthIdentifierPrompt("Custom identity"))
        assertEquals("Custom identity", ClaudeOAuthManager.resolveOAuthIdentifierPrompt("  Custom identity\n"))
    }

    @Test
    fun `stripping removes one prepended identity and keeps the real prompt`() {
        val prefix = ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        val prompt = "# SOUL\n\nBe useful."
        val prefixed = "$prefix\n\n$prompt"
        assertEquals(prompt, ClaudeOAuthManager.stripOAuthIdentifierPrompt(prefixed, prefix))
    }

    @Test
    fun `stripping tolerates whitespace differences between prepend sites`() {
        val prefix = ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        val prompt = "Body text"
        // A build-time value with trailing whitespace, or a caller that joined
        // with spaces, must still be recognised.
        assertEquals(prompt, ClaudeOAuthManager.stripOAuthIdentifierPrompt("\n $prefix  \n$prompt", prefix))
    }

    @Test
    fun `stripping removes repeated copies so the identity is sent once`() {
        val prefix = ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        val prompt = "Body text"
        val twice = "$prefix\n\n$prefix\n\n$prompt"
        assertEquals(prompt, ClaudeOAuthManager.stripOAuthIdentifierPrompt(twice, prefix))
    }

    @Test
    fun `stripping leaves unrelated prompts untouched`() {
        val prefix = ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        assertEquals("", ClaudeOAuthManager.stripOAuthIdentifierPrompt("", prefix))
        assertEquals("plain prompt", ClaudeOAuthManager.stripOAuthIdentifierPrompt("plain prompt", prefix))
        // The identity mentioned AFTER the first character is content, not a prefix.
        val embedded = "Note: $prefix"
        assertEquals(embedded, ClaudeOAuthManager.stripOAuthIdentifierPrompt(embedded, prefix))
    }

    @Test
    fun `stripping with an empty prefix is a no-op`() {
        val text = "  anything at all"
        assertEquals(text, ClaudeOAuthManager.stripOAuthIdentifierPrompt(text, ""))
    }
}