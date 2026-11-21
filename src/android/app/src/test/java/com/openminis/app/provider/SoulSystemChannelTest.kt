package com.openminis.app.provider

import com.openminis.app.agent.SoulFile
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SystemPromptBuilder
import com.openminis.app.agent.SoulInstructionRender
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/** Wire-level regression: sole SOUL authority block, once, before runtime in every native API. */
class SoulSystemChannelTest {
    private val applicationBlock = requireNotNull(
        SystemPromptBuilder.applicationPrompt(SoulFile(SoulMetadata.DEFAULT, SOUL_BODY)),
    )

    private val userTurn = LLMMessage(LLMMessage.Role.USER, USER_TEXT)

    @Test fun `sole SOUL block preserves the instruction format without transcript framing`() {
        assertTrue(applicationBlock.startsWith(SoulInstructionRender.MANDATORY_INTRO))
        assertTrue(applicationBlock.endsWith(SOUL_BODY))
        assertTrue(applicationBlock.contains("Instructions from: /var/minis/memory/SOUL.md"))
        assertFalse(applicationBlock.contains("AGENTS.md"))
        assertFalse(applicationBlock.contains("CLAUDE.md"))
        assertFalse(applicationBlock.contains("<system-reminder>"))
        assertFalse(applicationBlock.contains("Application system prompt (configured"))
    }

    @Test fun `empty SOUL produces no placeholder or alternate instruction source`() {
        assertNull(SystemPromptBuilder.applicationPrompt(null))
        assertNull(SystemPromptBuilder.applicationPrompt(SoulFile(SoulMetadata.DEFAULT, "   \n")))
    }

    // ── OpenAI Chat Completions: two role=system messages, user after ───────

    @Test
    fun `chat completions sends SOUL as the first system message and runtime as the last`() {
        val body = OpenAIProvider(apiKey = "test-key", model = LLMModel.gpt4oMini).buildRequestBody(
            messages = listOf(userTurn),
            systemPrompt = RUNTIME_TEXT,
            maxTokens = 1024,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
            applicationSystemPrompt = applicationBlock,
        )

        val messages = body.getJSONArray("messages")
        assertEquals("system + system + user", 3, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("system", messages.getJSONObject(1).getString("role"))
        assertEquals("user", messages.getJSONObject(2).getString("role"))

        // The composed block is passed to the wire verbatim — it is its own
        // message so a wrapper cannot reorder it into the runtime text.
        assertEquals(applicationBlock, messages.getJSONObject(0).getString("content"))
        assertEquals(RUNTIME_TEXT, messages.getJSONObject(1).getString("content"))
        assertEquals(USER_TEXT, messages.getJSONObject(2).getString("content"))

        val systemText = (0 until 2).joinToString("\n\n") {
            messages.getJSONObject(it).getString("content")
        }
        assertSoulThenRuntime(systemText, "Chat Completions")
        assertUserTurnClean(messages.getJSONObject(2).optString("content"), "Chat Completions")
    }

    // ── OpenAI Responses: one `instructions` string ─────────────────────────

    @Test
    fun `responses api puts SOUL at the head of instructions and runtime at the tail`() {
        val body = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            useResponsesAPI = true,
        ).buildResponsesAPIBody(
            messages = listOf(userTurn),
            systemPrompt = RUNTIME_TEXT,
            maxTokens = 1024,
            stream = false,
            imageParts = emptyList(),
            applicationSystemPrompt = applicationBlock,
        )

        val instructions = body.getString("instructions")
        assertTrue(
            "instructions must open with the workspace block",
            instructions.startsWith(SoulInstructionRender.MANDATORY_INTRO),
        )
        assertSoulThenRuntime(instructions, "Responses")

        // `input` is the conversation only; nothing from the system channel
        // may be re-sent there.
        assertUserTurnClean(body.getJSONArray("input").toString(), "Responses")
    }

    // ── Anthropic: top-level `system` array (API key and OAuth) ─────────────

    @Test
    fun `anthropic api key path leads its system text with SOUL, runtime last`() {
        val provider = AnthropicProvider(apiKey = "test-key", model = LLMModel.claudeHaiku45)

        val system = provider.resolveSystemPrompt(
            userPrompt = RUNTIME_TEXT,
            applicationPrompt = applicationBlock,
        )
        assertNotNull("API-key path must emit a system array", system)
        system!!
        assertEquals("API-key path keeps a single text block", 1, system.length())
        val text = system.getJSONObject(0).getString("text")
        assertTrue(
            "the API-key block must open with the workspace section",
            text.startsWith(SoulInstructionRender.MANDATORY_INTRO),
        )
        assertSoulThenRuntime(text, "Anthropic API key")
        assertFalse(
            "the API-key system block must not absorb the user turn",
            text.contains(USER_TEXT),
        )
    }

    @Test
    fun `anthropic oauth path keeps its identity block first and puts SOUL next`() {
        assumeAnthropicSystemArrayBuildable()
        val provider = AnthropicProvider(
            apiKey = "test-oauth-token",
            model = LLMModel.claudeSonnet5,
            isOAuth = true,
        )

        val system = provider.resolveSystemPrompt(
            userPrompt = RUNTIME_TEXT,
            applicationPrompt = applicationBlock,
        )
        assertNotNull("OAuth path must emit a system array", system)
        system!!
        assertEquals("identity + application block + runtime tail", 3, system.length())

        // Block 0 is the Claude Code identity line the OAuth gate requires — it
        // must stay first; the SOUL block is block 1, ahead of the app's
        // runtime text in block 2.
        val identity = system.getJSONObject(0).getString("text")
        assertFalse(
            "the identity block is not the workspace block",
            identity.contains(SoulInstructionRender.MANDATORY_INTRO),
        )
        assertEquals(applicationBlock, system.getJSONObject(1).getString("text"))
        assertEquals(RUNTIME_TEXT, system.getJSONObject(2).getString("text"))

        val joined = (0 until system.length()).joinToString("\n\n") {
            system.getJSONObject(it).getString("text")
        }
        assertSoulThenRuntime(joined, "Anthropic OAuth")
    }

    // ── Gemini: systemInstruction.parts[0].text ─────────────────────────────

    @Test
    fun `gemini puts SOUL at the head of systemInstruction and runtime at the tail`() {
        val body = geminiBody(runtime = RUNTIME_TEXT)
        val systemText = body
            .getJSONObject("systemInstruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")

        assertTrue(
            "systemInstruction must open with the workspace section",
            systemText.startsWith(SoulInstructionRender.MANDATORY_INTRO),
        )
        assertSoulThenRuntime(systemText, "Gemini")

        // contents[] is the conversation; the mandatory block must not be
        // duplicated there as a fake user turn.
        assertUserTurnClean(body.getJSONArray("contents").toString(), "Gemini")
        assertTrue(
            "sanity: the user turn really is on the wire",
            body.getJSONArray("contents").toString().contains(USER_TEXT),
        )
    }

    /**
     * Gemini's body builder is private (only the two OpenAI builders and
     * Anthropic's system-array resolver are `internal` seams), so it is reached
     * reflectively instead of widening production visibility. The method is
     * looked up by name + arity, so an added parameter fails loudly here rather
     * than silently testing the wrong overload.
     */
    private fun geminiBody(runtime: String): JSONObject {
        val provider = GeminiProvider(apiKey = "test-key", model = LLMModel.gemini25Flash)
        // The provider reads the application block from its own field.
        provider.applicationSystemPrompt = applicationBlock

        val method = GeminiProvider::class.java.declaredMethods.firstOrNull {
            it.name == "buildRequestBody" && it.parameterCount == 8
        } ?: throw AssertionError(
            "GeminiProvider.buildRequestBody(messages, systemPrompt, maxTokens, temperature, " +
                "imageParts, tools, thinkingLevel, thinkingFormat) is gone or changed arity; " +
                "update the reflective seam in SoulSystemChannelTest",
        )
        method.isAccessible = true
        val result = method.invoke(
            provider,
            listOf(userTurn),
            runtime,
            1024,
            null,
            emptyList<LLMMessage.ImagePart>(),
            emptyList<AgentToolDefinition>(),
            ThinkingLevel.OFF,
            null,
        )
        return result as JSONObject
    }

    // ── Shared assertions ──────────────────────────────────────────────────

    private fun assertSoulThenRuntime(systemText: String, channel: String) {
        for ((label, marker) in listOf(
            "SOUL authority" to SoulInstructionRender.MANDATORY_INTRO,
            "SOUL file section" to "Instructions from: /var/minis/memory/SOUL.md",
            "SOUL body" to SOUL_BODY,
            "runtime text" to RUNTIME_TEXT,
        )) {
            assertEquals(
                "$channel: $label must appear exactly once in the system channel",
                1,
                occurrences(systemText, marker),
            )
        }

        val soulIdx = systemText.indexOf(SOUL_BODY)
        val runtimeIdx = systemText.indexOf(RUNTIME_TEXT)
        assertFalse("$channel: retired AGENTS prompt must not return", systemText.contains("Instructions from: AGENTS.md"))
        assertTrue("$channel: authority precedes body", systemText.indexOf(SoulInstructionRender.MANDATORY_INTRO) in 0 until soulIdx)
        assertTrue(
            "$channel: SOUL must precede the runtime text (soul=$soulIdx, runtime=$runtimeIdx)",
            soulIdx in 0 until runtimeIdx,
        )
        assertTrue(
            "$channel: the runtime text must be the last section, but found '$RUNTIME_TEXT' at " +
                "$runtimeIdx of ${systemText.length}",
            systemText.trimEnd().endsWith(RUNTIME_TEXT),
        )
    }

    private fun assertUserTurnClean(userText: String, channel: String) {
        for ((label, marker) in listOf(
            "SOUL authority" to SoulInstructionRender.MANDATORY_INTRO,
            "SOUL file section" to "Instructions from: /var/minis/memory/SOUL.md",
            "SOUL body" to SOUL_BODY,
            "runtime text" to RUNTIME_TEXT,
        )) {
            assertFalse(
                "$channel: the $label leaked into the user turn",
                userText.contains(marker),
            )
        }
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val idx = haystack.indexOf(needle, from)
            if (idx < 0) return count
            count += 1
            from = idx + needle.length
        }
    }

    /**
     * Only the OAuth path requires the build-time Claude Code identity.
     * The public build leaves it unset, so that optional path is explicitly
     * skipped there. The API-key system serializer always runs independently.
     */
    private fun assumeAnthropicSystemArrayBuildable() {
        val configured = runCatching {
            com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        }.isSuccess
        Assume.assumeTrue(
            "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT is not configured in this build (the public mirror " +
                "ships no provider-customization.properties), so resolveSystemPrompt cannot build " +
                "a system array here",
            configured,
        )
    }

    private companion object {
        const val SOUL_BODY = "Use concise review notes."
        const val RUNTIME_TEXT =
            "Runtime guidance: use shell_execute for shell commands and file_read for files."
        const val USER_TEXT = "Please review the notes I attached."
    }
}
