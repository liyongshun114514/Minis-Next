package com.openminis.app.agent

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-multi-soul] Wiring contract for the application system prompt.
 *
 * There is exactly ONE user-configurable prompt: the SOUL profile selected for
 * the conversation. Per turn it is read once, frozen as one block, and copied
 * onto whichever provider may send a request. There is no workspace /
 * AGENTS.md block in the chain any more, no mode, no rule list, no per-entry
 * resolution, and no duplicate concatenation into `buildSystemPrompt`.
 */
class SystemPromptInjectionWiringTest {

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
    private val builtins = ProductionSources.read("config/ConfigBuiltins.kt")
    private val soul = ProductionSources.read("agent/SoulStore.kt")

    private fun body(fn: String): String {
        val start = vm.indexOf(fn)
        assertTrue("missing $fn", start >= 0)
        val marker = vm.indexOf("\n    }\n", start)
        assertTrue(marker > start)
        return vm.substring(start, marker)
    }

    @Test
    fun `per-model and per-group prompt resolution is gone`() {
        for (token in listOf(
            "SoulPromptRuleStore",
            "SoulPromptMode",
            "SoulPromptSelection",
            "applicationPromptForSelection",
            "promptModeForTurn",
            "promptRulesForTurn",
            "cachedDocumentOrLoad",
        )) {
            assertFalse("$token must not reach the runtime", vm.contains(token))
        }
    }

    @Test
    fun `the body is frozen once per turn`() {
        assertTrue(vm.contains("val soulFileForTurn = if (helperConfig == null) selectedSoulForTurn() else null"))
        assertTrue(vm.contains("val applicationPromptForTurn = if (helperConfig == null)"))
        assertTrue(vm.contains("SystemPromptBuilder.applicationPrompt(soulFileForTurn)"))
        // One read of the selected SOUL for the turn; retries/fallbacks reuse
        // the frozen value rather than re-resolving it per request.
        val loop = vm.substring(vm.indexOf("val soulFileForTurn"))
        assertTrue(loop.indexOf("selectedSoulForTurn()") < loop.indexOf("applicationPromptForTurn"))
        assertFalse("no second read inside the request loop", vm.contains("applicationPromptForSelection("))
    }

    @Test
    fun `no workspace instruction block survives in the send path`() {
        for (token in listOf(
            "WorkspaceInstructionStore",
            "WorkspaceInstructionAuthority",
            "workspaceBlockForTurn",
            "mandatoryBlock(",
            "exclusive(context)",
        )) {
            assertFalse("$token must be gone from the chat runtime", vm.contains(token))
        }
        // The frozen block is the SOUL prompt alone: nothing is joined with it.
        val assembly = vm.substring(
            vm.indexOf("val applicationPromptForTurn"),
            vm.indexOf("applyApplicationSystemPrompt(currentProvider, applicationPromptForTurn)"),
        )
        assertTrue(assembly.contains("SystemPromptBuilder.applicationPrompt(soulFileForTurn)"))
        assertFalse(assembly.contains("listOfNotNull("))
        assertFalse(assembly.contains("+ workspace"))
    }

    @Test
    fun `the per-chat selection is the source of both prompt and identity`() {
        // Turn snapshot: the SOUL selected for the active session.
        val selected = body("    private fun selectedSoulForTurn()")
        assertTrue(selected.contains("SoulProfiles.load(context, pending.profileId)"))
        assertTrue(selected.contains("SoulProfiles.selected(context, activeSessionId)"))
        // Identity sentence names the same profile, so "You are <name>" can
        // never disagree with the prompt that turn sends.
        assertTrue(vm.contains("val runtimePromptForTurn = buildSystemPrompt(soulFileForTurn)"))
        val loop = body("    private suspend fun runAgentLoopBody(")
        assertEquals("one SOUL read supplies identity and body", 1,
            Regex("selectedSoulForTurn\\(\\)").findAll(loop).count())
        val build = body("    private fun buildSystemPrompt(soulFileForIdentity:")
        assertFalse("identity uses the supplied snapshot", build.contains("selectedSoulForTurn()"))
        assertTrue(vm.contains("SystemPromptBuilder.identitySection(soulFileForIdentity)"))
        assertFalse(vm.contains("SystemPromptBuilder.identitySection(context)"))
        // The field-copy path (model switch, retry) reads the same source.
        val apply = body("    private fun applyApplicationSystemPromptGlobal(provider: LLMProvider)")
        assertTrue(apply.contains("SystemPromptBuilder.applicationPrompt(selectedSoulForTurn())"))
        assertFalse(apply.contains("SoulStore.load(context)"))
    }

    @Test
    fun `every request and every switch reuses the frozen block`() {
        assertTrue(vm.contains("applyApplicationSystemPrompt(currentProvider, applicationPromptForTurn)"))
        val requestLoop = vm.indexOf("while (!collectDone)")
        assertTrue("per-request reapply", vm.indexOf("applicationPromptForTurn", requestLoop) > requestLoop)
        assertEquals(
            "both mid-turn switch points use the frozen string",
            2,
            Regex("applyApplicationSystemPrompt\\(\\s*(chosen|next),\\s*applicationPromptForTurn,")
                .findAll(vm).count(),
        )
    }

    @Test
    fun `a helper never inherits the parent prompt`() {
        val snapshot = vm.substring(
            vm.indexOf("val applicationPromptForTurn = if (helperConfig == null)"),
            vm.indexOf("applyApplicationSystemPrompt(currentProvider, applicationPromptForTurn)"),
        )
        assertTrue(snapshot.contains("} else {\n            null\n        }"))
        // A helper keeps its own identity text, which is built from its config
        // rather than from any SOUL profile.
        assertTrue(vm.contains("HelperRunner.identitySection(it, browserEnabled = browserToolEnabled)"))
    }

    @Test
    fun `the block is sent only through the native application field`() {
        val build = body("    private fun buildSystemPrompt(soulFileForIdentity:")
        assertFalse("do not concatenate the body into the system message",
            build.contains("applicationPrompt("))
        assertTrue("identity is still built here", build.contains("identitySection(soulFileForIdentity)"))
        // Field copy lives in its own helper.
        val apply = body("    private fun applyApplicationSystemPrompt(provider: LLMProvider?, prompt: String?)")
        assertTrue(apply.contains("provider.applicationSystemPrompt = prompt"))
    }

    /**
     * [T-multi-soul] "Pick a SOUL, then send" and "tap A, then B" must both end
     * on the LAST tap, with no window where the request uses a profile the
     * store never saved.
     *
     * The contract is three-part: the tap is published synchronously, every
     * durable write runs through ONE job chain, and a reader prefers that
     * value only while it belongs to its own session.
     */
    @Test
    fun `the last tap wins over the asynchronous write`() {
        val select = body("    fun selectSoul(profileId: String)")
        assertTrue("the choice is published before the write starts",
            select.indexOf("_soulChoice.value = choice") in 0 until select.indexOf("viewModelScope.launch"))
        assertTrue("writes are serialised in one chain", select.contains("previous?.join()"))
        assertTrue("a failed write drops only its own choice",
            select.contains("_soulChoice.compareAndSet(choice, null)"))
        // A newer tap survives an older write's completion or failure.
        assertEquals(2, Regex("_soulChoice\\.compareAndSet\\(choice, null\\)").findAll(select).count())
        // The turn reader consults the pending choice for ITS session only.
        val turn = body("    private fun selectedSoulForTurn()")
        assertTrue(turn.contains("pending.sessionId == activeSessionId"))
        assertTrue(turn.contains("SoulProfiles.load(context, pending.profileId)"))
        // Promotion enters the same FIFO before exposing the real session id.
        val ensure = body("    private suspend fun ensureSession()")
        assertTrue(select.contains("synchronized(soulChoiceLock)"))
        assertTrue(ensure.contains("synchronized(soulChoiceLock)"))
        assertTrue(ensure.contains("previous?.join()"))
        assertTrue(ensure.contains(".also { soulWriteJob = it }"))
        assertTrue(ensure.contains("migration.join()"))
        assertFalse("migration cannot overwrite a newer tap", ensure.contains("_soulChoice.value = SoulChoice("))
    }

    /**
     * [T-multi-soul] The SOUL body is user-authored and reaches the model as
     * written. The phrase matcher survives as a validation helper only, which
     * is why it must not appear in either prompt-building path.
     */
    @Test
    fun `the body is never filtered on the way out`() {
        assertFalse("no phrase-based line dropping", soul.contains("scrubInjections"))
        assertTrue("the validator itself stays available", soul.contains("fun containsInjectionPattern"))
        assertEquals(1, Regex("val SUPREME_PREAMBLE: String\\s+get\\(\\) =").findAll(soul).count())
        assertTrue(soul.contains("val personality = trimmed"))
        // A caller that WANTS to warn still can.
        assertTrue(builtins.contains("SoulProfiles.save("))
    }
}