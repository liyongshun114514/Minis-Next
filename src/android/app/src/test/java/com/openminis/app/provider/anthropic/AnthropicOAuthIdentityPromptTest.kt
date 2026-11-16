package com.openminis.app.provider.anthropic

import com.openminis.app.auth.ClaudeOAuthManager
import com.openminis.app.data.model.LLMModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `system` array Anthropic receives for Claude Code OAuth credentials.
 *
 * Anthropic's OAuth gate inspects the FIRST system block, so the identity line
 * must lead, must carry no `cache_control`, and must appear exactly once even
 * when the caller already prepended it (ChatViewModel does). The API-key path
 * must never receive the identity — it is that login's marker, not ours.
 */
class AnthropicOAuthIdentityPromptTest {

    private val identity = ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT

    private fun provider(oauth: Boolean) = AnthropicProvider(
        apiKey = if (oauth) "oauth-token" else "api-key",
        model = LLMModel.claudeHaiku45,
        isOAuth = oauth,
    )

    @Test
    fun `oauth system array leads with the identity block`() {
        val arr = provider(oauth = true).resolveSystemPrompt("tail body", "app prompt")!!
        assertEquals(3, arr.length())

        val block = arr.getJSONObject(0)
        assertEquals("text", block.getString("type"))
        assertEquals(identity, block.getString("text"))
        // Uncached: a cache_control here would move the gate's match point.
        assertFalse(block.has("cache_control"))

        assertEquals("app prompt", arr.getJSONObject(1).getString("text"))
        assertTrue(arr.getJSONObject(1).has("cache_control"))
        assertEquals("tail body", arr.getJSONObject(2).getString("text"))
        assertTrue(arr.getJSONObject(2).has("cache_control"))
    }

    @Test
    fun `oauth still sends the identity when there is no other prompt`() {
        val arr = provider(oauth = true).resolveSystemPrompt(null, null)!!
        assertEquals(1, arr.length())
        assertEquals(identity, arr.getJSONObject(0).getString("text"))
    }

    @Test
    fun `a caller-prepended identity is not duplicated`() {
        val prefixed = "$identity\n\n# SOUL\n\nBe accurate."
        val arr = provider(oauth = true).resolveSystemPrompt(prefixed, null)!!
        assertEquals(2, arr.length())
        assertEquals(identity, arr.getJSONObject(0).getString("text"))
        assertEquals("# SOUL\n\nBe accurate.", arr.getJSONObject(1).getString("text"))
    }

    @Test
    fun `a twice-prepended identity yields one identity block`() {
        val doubled = "$identity\n\n$identity\n\nBody"
        val arr = provider(oauth = true).resolveSystemPrompt(doubled, null)!!
        assertEquals(2, arr.length())
        var identities = 0
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("text") == identity) identities++
        }
        assertEquals(1, identities)
        assertEquals("Body", arr.getJSONObject(1).getString("text"))
    }

    @Test
    fun `api key requests never carry the OAuth identity`() {
        val arr = provider(oauth = false).resolveSystemPrompt("tail body", "app prompt")!!
        assertEquals(1, arr.length())
        assertEquals("app prompt\n\ntail body", arr.getJSONObject(0).getString("text"))
        assertFalse(arr.getJSONObject(0).getString("text").contains(identity))
        assertTrue(arr.getJSONObject(0).has("cache_control"))
    }

    @Test
    fun `api key requests with no prompt send no system field`() {
        assertNull(provider(oauth = false).resolveSystemPrompt(null, null))
        assertNull(provider(oauth = false).resolveSystemPrompt("", ""))
    }

    @Test
    fun `the identity is a prompt line and never a credential-shaped value`() {
        // Guards the contract that made a built-in default acceptable: the
        // text is Claude Code's identity sentence, not a key/token.
        assertFalse(identity.contains("sk-ant"))
        assertFalse(identity.contains("Bearer"))
        assertTrue(identity.startsWith("You are Claude Code"))
    }
}