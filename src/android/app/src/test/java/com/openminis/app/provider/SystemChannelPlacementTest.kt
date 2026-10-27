package com.openminis.app.provider

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-soul-prompt-supreme] Static placement contract for the application system
 * prompt (Settings → System Prompt).
 *
 * Why source assertions instead of request bodies: each provider's body
 * builder is `internal`/`private` behind an Android `Context`-heavy class, and
 * the one thing that can regress silently is ORDERING — an editor moving the
 * leading block below the app-generated one, or a wrapper re-introducing a
 * "defer to the user" clause. A body-level check would still catch wiring but
 * not placement, so both are pinned: the field exists on the interface, every
 * provider carries it, and each builder puts it FIRST in its system channel.
 */
class SystemChannelPlacementTest {

    private val iface = ProductionSources.read("provider/LLMProvider.kt")
    private val openai = ProductionSources.read("provider/openai/OpenAIProvider.kt")
    private val anthropic = ProductionSources.read("provider/anthropic/AnthropicProvider.kt")
    private val gemini = ProductionSources.read("provider/gemini/GeminiProvider.kt")
    private val chatVm = ProductionSources.read("ui/chat/ChatViewModel.kt")
    private val soul = ProductionSources.read("agent/SoulStore.kt")

    @Test fun `the interface exposes an application prompt separate from systemPrompt`() {
        // Declared with a default getter/setter (an interface property may not
        // have a backing field, and a one-line body is not valid here).
        assertTrue(iface.contains("var applicationSystemPrompt: String?"))
        assertTrue(iface.contains("get() = null"))
        assertTrue(iface.contains("set(_) {}"))
    }

    @Test fun `every provider carries the field`() {
        for ((name, src) in listOf("openai" to openai, "anthropic" to anthropic, "gemini" to gemini)) {
            assertTrue("$name must override the field",
                src.contains("override var applicationSystemPrompt: String? = null"))
        }
    }

    // ── OpenAI Chat Completions: leading role=system message ────────────────

    @Test fun `chat completions puts the application prompt in the first system message`() {
        val appPut = openai.indexOf("put(\"content\", appPrompt)")
        val sysPut = openai.indexOf("put(\"content\", systemPrompt)")
        assertTrue("application prompt is put into messagesArray", appPut > 0)
        assertTrue("system prompt still follows it", sysPut > appPut)
        // Both are role=system, and the app one is the branch that comes first.
        val appBlock = openai.indexOf("if (appPrompt != null) {")
        assertTrue(appBlock in 0 until appPut)
    }

    @Test fun `chat completions receives the field from its own dispatcher`() {
        assertTrue(openai.contains("applicationSystemPrompt = applicationSystemPrompt)"))
    }

    // ── OpenAI Responses: `instructions` leads ───────────────────────────────

    @Test fun `responses api joins the application prompt in front of instructions`() {
        val idx = openai.indexOf("else -> appPrompt + \"\\n\\n\" + systemPrompt")
        assertTrue("app prompt is joined BEFORE systemPrompt", idx > 0)
        val body = openai.indexOf("put(\"instructions\", instructions)")
        assertTrue(body > 0)
        assertFalse("instructions is no longer set straight from systemPrompt",
            openai.contains("put(\"instructions\", systemPrompt)"))
    }

    // ── Anthropic: first block of the top-level system array ────────────────

    @Test fun `anthropic emits the application prompt as a system block after the oauth identity block`() {
        val fn = anthropic.indexOf("internal fun resolveSystemPrompt(")
        assertTrue(fn > 0)
        val body = anthropic.substring(fn, anthropic.indexOf("\n    }\n", fn))
        val identity = body.indexOf("put(\"text\", claudeCodePrefix)")
        val app = body.indexOf("put(\"text\", appPrompt)")
        val tail = body.indexOf("put(\"text\", tail)")
        assertTrue("identity block must stay first for the OAuth gate", identity in 0 until app)
        assertTrue("application prompt comes before the app-generated tail", app in 0 until tail)
    }

    @Test fun `anthropic api key path leads with the application prompt`() {
        val idx = anthropic.indexOf("val parts = listOfNotNull(appPrompt, userPrompt?.takeIf { it.isNotEmpty() })")
        assertTrue("application prompt is element 0 of the joined text", idx > 0)
        assertTrue(anthropic.contains("put(\"text\", parts.joinToString(\"\\n\\n\"))"))
    }

    // ── Gemini: start of systemInstruction ──────────────────────────────────

    @Test fun `gemini leads systemInstruction with the application prompt`() {
        val idx = gemini.indexOf("else -> appPrompt + \"\\n\\n\" + systemPrompt")
        assertTrue(idx > 0)
        val field = gemini.indexOf("body.put(\"systemInstruction\"")
        assertTrue("the joined text is what goes into systemInstruction", field > idx)
    }

    // ── The wiring: ChatViewModel hands it over ─────────────────────────────

    @Test fun `the chat view model copies the prompt onto every provider it may send with`() {
        assertTrue(chatVm.contains("private fun applyApplicationSystemPrompt(provider: LLMProvider?, prompt: String?)"))
        // Applied to the loop's starting provider and to both mid-turn switch
        // points, so a fallback / switch cannot strand the prompt.
        assertTrue(chatVm.contains("applyApplicationSystemPrompt(currentProvider, applicationPromptForTurn)"))
        for (provider in listOf("chosen", "next")) {
            assertTrue("switch/fallback resolves scoped prompt for $provider", Regex(
                "applyApplicationSystemPrompt\\(\\s*$provider,\\s*applicationPromptForSelection\\(",
            ).containsMatchIn(chatVm))
        }
        // Text is snapshotted per turn; group/model identity is resolved for
        // every request and after a switch/fallback. Helpers keep isolation.
        assertTrue(chatVm.contains("val soulFileForTurn = if (helperConfig == null)"))
        // Mode and rules are captured atomically from one document so a
        // concurrent settings save cannot mix GLOBAL and CUSTOM state.
        assertTrue(chatVm.contains("SoulPromptRuleStore.cachedDocumentOrLoad(context)"))
        assertTrue(chatVm.contains("val promptRulesForTurn = promptDocumentForTurn.rules"))
        assertTrue(chatVm.contains("val promptModeForTurn = promptDocumentForTurn.mode"))
        assertTrue(chatVm.contains("if (helperConfig != null) return null"))
        val requestLoop = chatVm.indexOf("while (!collectDone)")
        val inject = chatVm.indexOf("applicationPromptForSelection(", requestLoop)
        assertTrue("scoped prompt refreshes in request loop", requestLoop >= 0 && inject > requestLoop)
        assertTrue(chatVm.contains("modelId = entry?.baseModel?.id"))
        assertTrue(chatVm.contains("SystemPromptBuilder.applicationPrompt(context)"))
    }

    @Test fun `the prompt text is never logged`() {
        assertFalse("prompt text must not be logged",
            chatVm.contains("AppLogger.info(TAG_STREAM, \$prompt"))
        // applyApplicationSystemPrompt passes the value straight to the field.
        val fn = chatVm.indexOf("private fun applyApplicationSystemPrompt(provider: LLMProvider?, prompt: String?)")
        val body = chatVm.substring(fn, chatVm.indexOf("\n    }\n", fn))
        assertFalse(body.contains("AppLogger"))
        assertFalse(body.contains("Log."))
    }

    @Test fun `no wording lets a later message override the application prompt`() {
        // Scoped to the prompt-building pair. MemoryRepository's "defer to the
        // user's latest message" line is about BACKGROUND CONTEXT vs the newest
        // turn, a different contract, and is out of scope here.
        for ((name, src) in listOf("SoulStore" to soul, "ChatViewModel" to chatVm)) {
            assertFalse("$name still yields to later messages",
                src.contains("defer to the user's latest") || src.contains("may be overridden"))
        }
        assertTrue(soul.contains("不覆盖模型服务端规则"))
        assertFalse(soul.contains("outranks every instruction"))
        assertEquals(1, Regex("SUPREME_PREAMBLE: String =").findAll(soul).count())
    }
}
