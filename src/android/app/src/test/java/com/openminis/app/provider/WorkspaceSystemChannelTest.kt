package com.openminis.app.provider

import com.openminis.app.agent.SoulFile
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SystemPromptBuilder
import com.openminis.app.agent.workspace.WorkspaceInstructionRender
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

/**
 * [T-workspace-system-channel] Wire-level serialization of the three-part
 * application block the app now sends through each provider's NATIVE system
 * channel:
 *
 *     AGENTS.md (workspace instructions)  →  SOUL.md (Settings → System Prompt)  →  runtime
 *
 * The layering is decided in `ChatViewModel`, which renders the workspace block
 * with `WorkspaceInstructionStore.mandatoryBlock(...)` and joins it AHEAD of
 * `SystemPromptBuilder.applicationPrompt(soulFile)` with a blank line
 * (`listOfNotNull(workspace, soul).joinToString("\n\n")`), handing the result
 * to `provider.applicationSystemPrompt`. Everything the app generates itself —
 * tool list, skills, memory, environment / runtime facts — stays in the
 * provider's ordinary `systemPrompt` and must therefore land LAST.
 *
 * What this file pins, per wire format:
 *  1. the ordering above, in the native field only (leading `role:system`
 *     message on Chat Completions, `instructions` on Responses, the `system`
 *     text blocks on Anthropic, `systemInstruction.parts[0].text` on Gemini);
 *  2. each block appears EXACTLY ONCE (the workspace text used to ride a
 *     durable user-role message; a half-migrated build would send it twice);
 *  3. the user turn carries none of it (no accidental re-injection);
 *  4. the runtime tail is the final section of the system channel, so the
 *     binding workspace wording is read first and the app's own operating
 *     instructions cannot be sandwiched between AGENTS and SOUL.
 *
 * Data used here is ordinary, non-privileged prose ("Use concise review notes",
 * "Prefer short paragraphs…"): the point is byte placement, not content, and
 * nothing in this test asks a model to ignore service-side rules, safety
 * requirements or device permissions.
 *
 * Seams (production visibility unchanged): the two OpenAI builders and
 * `AnthropicProvider.resolveSystemPrompt` are already `internal` and documented
 * as unit-test seams. `GeminiProvider.buildRequestBody` is private, so it is
 * reached by reflection — the same shape the existing Gemini body tests use —
 * rather than widening it. No network is touched: every builder is a pure
 * JSON-construction function and `GeminiProvider`'s base path is never dialed.
 */
class WorkspaceSystemChannelTest {

    // ── Fixtures: real renderers, ordinary text ─────────────────────────────

    private val workspaceFile = WorkspaceInstructionRender.InstructionFile(
        absolutePath = "/var/minis/workspace/demo/AGENTS.md",
        displayPath = "AGENTS.md",
        linuxPath = "/var/minis/workspace/demo/AGENTS.md",
        content = WORKSPACE_BODY,
        isUserGlobal = false,
    )

    /** Produced by the real renderer, not a hand-written stand-in. */
    private val workspaceBlock: String = requireNotNull(
        WorkspaceInstructionRender.renderMandatoryBlock(listOf(workspaceFile), MAX_BYTES),
    ) { "renderMandatoryBlock returned null for a non-empty chain" }

    /** Produced by the real application-prompt builder, not a hand-written one. */
    private val soulBlock: String = requireNotNull(
        SystemPromptBuilder.applicationPrompt(SoulFile(SoulMetadata.DEFAULT, SOUL_BODY)),
    ) { "applicationPrompt returned null for a non-empty SOUL body" }

    /**
     * Mirrors `ChatViewModel`: workspace first, SOUL second, blank-line joined.
     * Assertions below are order/marker based, so a different separator would
     * not make the ordering contract look broken.
     */
    private val applicationBlock: String =
        listOfNotNull(workspaceBlock, soulBlock).joinToString("\n\n")

    private val userTurn = LLMMessage(LLMMessage.Role.USER, USER_TEXT)

    // ── The composition itself ──────────────────────────────────────────────

    @Test
    fun `workspace block precedes the SOUL block and carries no runtime framing`() {
        assertTrue(
            "workspace must lead the composed block",
            applicationBlock.startsWith(WorkspaceInstructionRender.MANDATORY_INTRO),
        )
        assertTrue(
            "SOUL must close the composed block",
            applicationBlock.endsWith(SOUL_BODY),
        )
        assertTrue(
            "SOUL must still open with the application-prompt preamble",
            soulBlock.startsWith(SystemPromptBuilder.SUPREME_PREAMBLE),
        )
        val workspaceIdx = applicationBlock.indexOf(WorkspaceInstructionRender.MANDATORY_INTRO)
        val soulIdx = applicationBlock.indexOf(SOUL_BODY)
        assertTrue("AGENTS must be before SOUL", workspaceIdx in 0 until soulIdx)
        // renderMandatoryBlock delivers prompt text, not a transcript nudge.
        assertFalse(
            "the system channel must not carry the <system-reminder> frame",
            applicationBlock.contains(WorkspaceInstructionRender.SYSTEM_REMINDER_OPEN),
        )
        assertFalse(
            "the system channel must not carry the <system-reminder> close tag",
            applicationBlock.contains(WorkspaceInstructionRender.SYSTEM_REMINDER_CLOSE),
        )
    }

    @Test
    fun `an absent workspace or an empty SOUL leaves no placeholder section`() {
        // No instruction file → no workspace block at all.
        assertNull(
            "an unconfigured workspace must send nothing",
            WorkspaceInstructionRender.renderMandatoryBlock(emptyList(), MAX_BYTES),
        )
        // Empty SOUL body → no application block (Settings → System Prompt unset).
        assertNull(
            "an empty SOUL body must send nothing",
            SystemPromptBuilder.applicationPrompt(SoulFile(SoulMetadata.DEFAULT, "   \n")),
        )
        // The joiner therefore degrades to the section that exists, not to "  \n\n  ".
        val workspaceOnly = listOfNotNull(workspaceBlock, null).joinToString("\n\n")
        assertEquals(workspaceBlock, workspaceOnly)
        val soulOnly = listOfNotNull(null, soulBlock).joinToString("\n\n")
        assertEquals(soulBlock, soulOnly)
    }

    // ── OpenAI Chat Completions: two role=system messages, user after ───────

    @Test
    fun `chat completions sends AGENTS then SOUL as the first system message and runtime as the last`() {
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
        assertWorkspaceThenSoulThenRuntime(systemText, "Chat Completions")
        assertUserTurnClean(messages.getJSONObject(2).optString("content"), "Chat Completions")
    }

    // ── OpenAI Responses: one `instructions` string ─────────────────────────

    @Test
    fun `responses api puts AGENTS then SOUL at the head of instructions and runtime at the tail`() {
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
            instructions.startsWith(WorkspaceInstructionRender.MANDATORY_INTRO),
        )
        assertWorkspaceThenSoulThenRuntime(instructions, "Responses")

        // `input` is the conversation only; nothing from the system channel
        // may be re-sent there.
        assertUserTurnClean(body.getJSONArray("input").toString(), "Responses")
    }

    // ── Anthropic: top-level `system` array (API key and OAuth) ─────────────

    @Test
    fun `anthropic api key path leads its system text with AGENTS then SOUL, runtime last`() {
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
            text.startsWith(WorkspaceInstructionRender.MANDATORY_INTRO),
        )
        assertWorkspaceThenSoulThenRuntime(text, "Anthropic API key")
        assertFalse(
            "the API-key system block must not absorb the user turn",
            text.contains(USER_TEXT),
        )
    }

    @Test
    fun `anthropic oauth path keeps its identity block first and puts AGENTS then SOUL next`() {
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
        // must stay first; the AGENTS→SOUL block is block 1, ahead of the app's
        // runtime text in block 2.
        val identity = system.getJSONObject(0).getString("text")
        assertFalse(
            "the identity block is not the workspace block",
            identity.contains(WorkspaceInstructionRender.MANDATORY_INTRO),
        )
        assertEquals(applicationBlock, system.getJSONObject(1).getString("text"))
        assertEquals(RUNTIME_TEXT, system.getJSONObject(2).getString("text"))

        val joined = (0 until system.length()).joinToString("\n\n") {
            system.getJSONObject(it).getString("text")
        }
        assertWorkspaceThenSoulThenRuntime(joined, "Anthropic OAuth")
    }

    // ── Gemini: systemInstruction.parts[0].text ─────────────────────────────

    @Test
    fun `gemini puts AGENTS then SOUL at the head of systemInstruction and runtime at the tail`() {
        val body = geminiBody(runtime = RUNTIME_TEXT)
        val systemText = body
            .getJSONObject("systemInstruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")

        assertTrue(
            "systemInstruction must open with the workspace section",
            systemText.startsWith(WorkspaceInstructionRender.MANDATORY_INTRO),
        )
        assertWorkspaceThenSoulThenRuntime(systemText, "Gemini")

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
                "update the reflective seam in WorkspaceSystemChannelTest",
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

    private fun assertWorkspaceThenSoulThenRuntime(systemText: String, channel: String) {
        for ((label, marker) in listOf(
            "workspace intro" to WorkspaceInstructionRender.MANDATORY_INTRO,
            "workspace body" to WORKSPACE_BODY,
            "SOUL preamble" to SystemPromptBuilder.SUPREME_PREAMBLE,
            "SOUL body" to SOUL_BODY,
            "runtime text" to RUNTIME_TEXT,
        )) {
            assertEquals(
                "$channel: $label must appear exactly once in the system channel",
                1,
                occurrences(systemText, marker),
            )
        }

        val workspaceIdx = systemText.indexOf(WorkspaceInstructionRender.MANDATORY_INTRO)
        val soulIdx = systemText.indexOf(SOUL_BODY)
        val runtimeIdx = systemText.indexOf(RUNTIME_TEXT)
        assertTrue(
            "$channel: the workspace block must precede SOUL (workspace=$workspaceIdx, soul=$soulIdx)",
            workspaceIdx in 0 until soulIdx,
        )
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
            "workspace intro" to WorkspaceInstructionRender.MANDATORY_INTRO,
            "workspace body" to WORKSPACE_BODY,
            "SOUL preamble" to SystemPromptBuilder.SUPREME_PREAMBLE,
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
        /** Generous budget: this file tests ordering, not the budget degradation path. */
        const val MAX_BYTES = 64 * 1024

        /** Ordinary review-style guidance — no privileged or policy-bending text. */
        const val WORKSPACE_BODY = "Prefer short paragraphs in review notes."
        const val SOUL_BODY = "Use concise review notes."
        const val RUNTIME_TEXT =
            "Runtime guidance: use shell_execute for shell commands and file_read for files."
        const val USER_TEXT = "Please review the notes I attached."
    }
}
