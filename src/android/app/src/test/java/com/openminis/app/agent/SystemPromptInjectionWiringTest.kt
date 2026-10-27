package com.openminis.app.agent

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [K-mode-isolation] Wiring guards for the two-mode prompt backend.
 *
 * The rendering itself is unit-tested in [SoulPromptRulesTest] and
 * [KairosPromptModeTest]. What is checked HERE is that `ChatViewModel` — which
 * cannot be instantiated in a unit test — actually calls that backend in the
 * only way that satisfies the product contract:
 *  - the mode is read ONCE per turn and every request path reuses it;
 *  - a fallback / mid-turn switch recomputes the block from that frozen mode
 *    instead of letting a global prompt survive;
 *  - a helper sub agent still gets nothing.
 *
 * It is a source contract on purpose: the alternative is a mocked Context +
 * repository + provider stack, which would test the mock, not the wiring.
 */
class SystemPromptInjectionWiringTest {

    private val chatViewModel: String by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }

    /** Arguments of every `applicationPromptForSelection(` call, via paren depth. */
    private fun promptSelectionCalls(source: String): List<String> {
        val calls = mutableListOf<String>()
        var index = source.indexOf("applicationPromptForSelection(")
        while (index >= 0) {
            var depth = 0
            var i = index + "applicationPromptForSelection".length
            val start = i + 1
            var end = -1
            while (i < source.length) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            end = i
                            i = source.length
                        }
                    }
                }
                i++
            }
            if (end > start) calls.add(source.substring(start, end))
            index = source.indexOf("applicationPromptForSelection(", index + 1)
        }
        return calls
    }

    @Test
    fun `custom mode renders only the exact pair rule`() {
        val file = SoulFile(SoulMetadata.DEFAULT, "GLOBAL_UNIQUE_TEXT")
        val rules = listOf(
            SoulPromptRule(
                id = "a",
                target = SoulPromptRuleTarget.MODEL,
                providerInstanceId = "p",
                modelId = "m",
                content = "MODEL_UNIQUE_TEXT",
            ),
        )
        val block = SystemPromptBuilder.applicationPrompt(
            file,
            SoulPromptMode.CUSTOM,
            rules,
            SoulPromptSelection(providerInstanceId = "p", modelId = "m"),
        )!!
        assertEquals(1, Regex("MODEL_UNIQUE_TEXT").findAll(block).count())
        assertFalse("the global body must not ride along", block.contains("GLOBAL_UNIQUE_TEXT"))
        assertTrue(block.contains(SystemPromptBuilder.CUSTOM_RULES_HEADER))

        assertNull(
            SystemPromptBuilder.applicationPrompt(
                file,
                SoulPromptMode.CUSTOM,
                rules,
                SoulPromptSelection(providerInstanceId = "other", modelId = "m"),
            ),
        )
    }

    @Test
    fun `generated identity omits the body sent by the provider system channel`() {
        val source = ProductionSources.read("agent/SoulStore.kt")
        val body = source.substringAfter("fun identitySection(context: Context): String {")
            .substringBefore("\n    }")
        assertTrue(body.contains("file?.copy(body = \"\")"))
        assertFalse(body.contains("CharacterCard"))
    }

    @Test
    fun `normal prompt settings retain original stores and no character backend`() {
        val source = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")
        assertTrue(source.contains("SoulStore.save(context, currentFile)"))
        assertTrue(source.contains("SoulPromptRuleStore.save(context, next)"))
        assertFalse(source.contains("CharacterCard"))
        val main = ProductionSources.mainRoot()!!
        assertFalse(java.io.File(main, "agent/CharacterCardStore.kt").exists())
        assertFalse(java.io.File(main, "ui/settings/CharacterCardsScreen.kt").exists())
    }

    // ── ChatViewModel wiring ────────────────────────────────────────────

    @Test
    fun `the mode and rules are frozen together once per turn`() {
        // ONE document read, ONE synchronized section: a Settings save between
        // two accessors must not be able to pair a new mode with old rules.
        val declaration = "val promptDocumentForTurn"
        assertEquals(
            "exactly one per-turn prompt snapshot",
            1,
            Regex(Regex.escape(declaration)).findAll(chatViewModel).count(),
        )
        val declarationAt = chatViewModel.indexOf(declaration)
        val loopAt = chatViewModel.indexOf("while (!collectDone)")
        assertTrue("snapshot must precede the request loop", loopAt < 0 || declarationAt < loopAt)
        val block = chatViewModel.substring(
            declarationAt,
            if (loopAt > declarationAt) loopAt else chatViewModel.length,
        )
        assertTrue(block.contains("SoulPromptRuleStore.cachedDocumentOrLoad(context)"))
        assertTrue(block.contains("val promptRulesForTurn = promptDocumentForTurn.rules"))
        assertTrue(block.contains("val promptModeForTurn = promptDocumentForTurn.mode"))
        assertFalse(
            "the turn must not take a second, independently-locked read",
            block.contains("SoulPromptRuleStore.cachedOrLoad(context)"),
        )
        assertFalse(
            "the turn must not read the mode separately from the rules",
            block.contains("SoulPromptRuleStore.cachedModeOrLoad(context)"),
        )
    }

    @Test
    fun `every selection resolution reuses the frozen mode`() {
        val calls = promptSelectionCalls(chatViewModel).filterNot { it.contains("entryId: String?") }
        assertTrue(
            "expected the turn path, the mid-turn switch, the per-request path and the empty-turn fallback",
            calls.size >= 4,
        )
        for ((index, args) in calls.withIndex()) {
            val flat = args.replace('\n', ' ').trim()
            assertTrue(
                "call #$index must pass the frozen mode, was: $flat",
                args.contains("promptModeForTurn") || args.contains("entryId, mode"),
            )
        }
        // The `mode` form exists only inside the fallback-candidate seeder,
        // whose own value comes from the store (asserted separately).
        val seed = chatViewModel.substringAfter("private fun seedApplicationSystemPrompt(")
            .substringBefore("\n    }")
        val seedCalls = promptSelectionCalls(seed)
        assertEquals(1, seedCalls.size)
        assertTrue(seedCalls[0].contains("entryId, mode"))
    }

    @Test
    fun `the per-request path reapplies after every fallback`() {
        // The request loop is the last word on the prompt: a provider adopted
        // mid-turn is corrected before its first request goes out.
        val loop = chatViewModel.substring(chatViewModel.indexOf("while (!collectDone)"))
        val perRequest = loop.substringBefore("val liveProblem")
        assertTrue(perRequest.contains("applyApplicationSystemPrompt("))
        assertTrue(perRequest.contains("applicationPromptForSelection("))
        assertTrue(perRequest.contains("promptModeForTurn"))
    }

    @Test
    fun `a fallback candidate is seeded from the mode and its own entry`() {
        assertTrue(
            chatViewModel.contains("seedApplicationSystemPrompt(p, entry.id)"),
        )
        assertFalse(
            "a CUSTOM turn must not pre-seed candidates with the global body",
            Regex("applyApplicationSystemPromptGlobal\\(p\\)").containsMatchIn(chatViewModel),
        )
    }

    @Test
    fun `the legacy global seeder refuses to leave text behind in custom mode`() {
        val body = chatViewModel
            .substringAfter("private fun applyApplicationSystemPromptGlobal(provider: LLMProvider) {")
            .substringBefore("\n    }")
        assertTrue(body.contains("cachedModeOrLoad"))
        assertTrue(body.contains("SoulPromptMode.GLOBAL"))
        assertTrue("CUSTOM must seed null, not the global body", body.contains("null"))
    }

    @Test
    fun `custom seeding resolves the candidate's own model rule`() {
        val body = chatViewModel
            .substringAfter("private fun seedApplicationSystemPrompt(provider: LLMProvider, entryId: String?) {")
            .substringBefore("\n    }")
        assertTrue("the seed must take one document snapshot", body.contains("cachedDocumentOrLoad"))
        assertTrue(body.contains("applicationPromptForSelection(soul, document.rules, entryId, mode)"))
        assertTrue(body.contains("applyApplicationSystemPrompt("))
    }

    @Test
    fun `a helper sub agent never inherits either mode`() {
        val body = chatViewModel
            .substringAfter("private fun applicationPromptForSelection(")
            .substringBefore("\n    private fun seedApplicationSystemPrompt")
        assertTrue(
            "the helper guard must stay the first statement of the resolver",
            body.substringAfter("): String? {").trimStart().startsWith("if (helperConfig != null) return null"),
        )
        assertTrue("the guard must precede every mode branch", body.indexOf("helperConfig != null") < body.indexOf("SoulPromptMode.GLOBAL"))
    }

    @Test
    fun `the runtime never concatenates both modes by hand`() {
        // Exactly one place chooses the text; a second composer in the loop
        // would be the leak this contract forbids.
        assertFalse(chatViewModel.contains("APPLICATION_PROMPT_HEADER +"))
        assertFalse(chatViewModel.contains("CUSTOM_RULES_HEADER +"))
    }
}
