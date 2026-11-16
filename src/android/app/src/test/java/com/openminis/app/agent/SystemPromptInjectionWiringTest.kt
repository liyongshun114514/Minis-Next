package com.openminis.app.agent

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-soul-scoped-prompt] Wiring contract for the application system prompt
 * after the rollback to the scoped-rules behaviour (commit d1110b8).
 *
 * The app has ONE global SOUL.md body plus an OPTIONAL list of scoped rules
 * (per model group / per provider model). Per turn the body and the rule list
 * are read ONCE — so a Settings save mid-turn cannot make one turn's requests
 * disagree — while the SELECTION (group id + provider entry) is re-read for
 * every request, so a group or model switch applies to the next request.
 *
 * What must NOT be reachable from the runtime:
 *
 *  - the retired multi-profile SOUL library (`SoulProfiles`, the per-chat
 *    picker, `soulChoice`), which replaced this contract after d1110b8 and is
 *    rolled back together with the per-chat profile feature;
 *  - any AGENTS.md / workspace-instruction block: the system channel carries
 *    the SOUL prompt and the app-generated identity text, nothing else;
 *  - the prompt text concatenated into `buildSystemPrompt` — it travels only
 *    in each provider's native `applicationSystemPrompt` field.
 */
class SystemPromptInjectionWiringTest {

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
    private val soul = ProductionSources.read("agent/SoulStore.kt")
    private val rules = ProductionSources.read("agent/SoulPromptRules.kt")

    private fun body(fn: String): String {
        val start = vm.indexOf(fn)
        assertTrue("missing $fn", start >= 0)
        val marker = vm.indexOf("\n    }\n", start)
        assertTrue(marker > start)
        return vm.substring(start, marker)
    }

    // ── The turn snapshot ───────────────────────────────────────────────────

    @Test
    fun `the global body and the rule list are snapshotted once per turn`() {
        assertTrue(
            "the turn reads the single global SOUL file",
            vm.contains("val soulFileForTurn = if (helperConfig == null)"),
        )
        assertTrue(vm.contains("SoulStore.load(context)"))
        assertTrue(
            "the scoped rule list is read once for the turn",
            vm.contains("val promptRulesForTurn = if (helperConfig == null)"),
        )
        assertTrue(vm.contains("SoulPromptRuleStore.cachedOrLoad(context)"))
        assertEquals(
            "one rule-store read per turn, reused by every request",
            1,
            Regex("SoulPromptRuleStore\\.cachedOrLoad\\(context\\)").findAll(vm).count(),
        )
        // The global file is read before the request loop starts; the loop
        // itself never re-reads it (only the selection is re-resolved).
        val loopStart = vm.indexOf("while (!collectDone)")
        assertTrue("request loop not found", loopStart > 0)
        assertTrue(
            "the global file must be read before the request loop",
            vm.lastIndexOf("SoulStore.load(context)") in 0 until loopStart,
        )
        assertTrue(
            "the resolved block is captured before the first request",
            vm.contains("val applicationPromptForTurn = applicationPromptForSelection("),
        )
    }

    @Test
    fun `the selection is resolved from the live group and provider entry`() {
        assertTrue(
            "scoped resolution builds an explicit selection",
            vm.contains("SoulPromptSelection("),
        )
        assertTrue("group scope", vm.contains("groupId = _selectedGroupId.value"))
        assertTrue(
            "model scope is the provider instance plus the base model id",
            vm.contains("providerInstanceId = entry?.providerInstanceId"),
        )
        assertTrue(vm.contains("modelId = entry?.baseModel?.id"))
        // A deleted/unknown entry resolves to a null model scope: it must not
        // degrade into "apply the model rule globally".
        assertTrue(
            "an unknown entry keeps a null model scope",
            vm.contains("val entry = effectiveEntryId?.let { id ->"),
        )
        val resolver = vm.substringAfter("private fun applicationPromptForSelection(")
        assertTrue(
            "the selection is handed to the real scoped resolver",
            resolver.contains("SystemPromptBuilder.applicationPrompt(") &&
                resolver.contains("soul,") && resolver.contains("rules,") &&
                resolver.contains("selection,"),
        )
    }

    @Test
    fun `scoped rules are matched, not just stored`() {
        assertTrue("resolver exists", rules.contains("object SoulPromptRuleResolver"))
        assertTrue("resolver resolves rules", rules.contains("fun matching("))
        assertTrue("group targets", rules.contains("SoulPromptRuleTarget.GROUP"))
        assertTrue("model targets", rules.contains("SoulPromptRuleTarget.MODEL"))
        assertTrue("rules persist on disk", rules.contains("object SoulPromptRuleStore"))
        assertTrue(
            "the prompt builder applies the resolved rules",
            soul.contains("SoulPromptRuleResolver.matching(rules, selection)"),
        )
        assertTrue(
            "a matched rule is rendered under its own header",
            soul.contains("CUSTOM_RULES_HEADER"),
        )
        assertTrue(
            "the no-rule overload still sends only the global body",
            soul.contains("applicationPrompt(file, emptyList(), SoulPromptSelection())"),
        )
    }

    // ── Multi-profile / picker are gone ────────────────────────────────────

    @Test
    fun `the retired multi-profile SOUL library cannot reach the runtime`() {
        for (token in listOf(
            "SoulProfiles",
            "SoulPicker",
            "selectedSoulForTurn",
            "soulChoice",
            "selectSoul(",
            "refreshSoulProfiles",
            "profileId",
        )) {
            assertFalse("$token must not survive in the chat runtime", vm.contains(token))
        }
        val root = ProductionSources.mainRoot()!!
        assertFalse(
            "the profile store is deleted, not merely unreferenced",
            File(root, "agent/SoulProfiles.kt").exists(),
        )
        assertFalse(
            "the per-chat picker is deleted, not merely unreferenced",
            File(root, "ui/chat/SoulPickerDialog.kt").exists(),
        )
        val offenders = ProductionSources.allKotlinFiles()
            .filter { it.readText().contains("SoulProfiles") }
            .map { it.name }
        assertTrue(
            "no production source may still reference the deleted profile store: $offenders",
            offenders.isEmpty(),
        )
    }

    // ── No AGENTS.md / workspace instruction block ─────────────────────────

    @Test
    fun `no workspace instruction block survives in the send path`() {
        for (token in listOf(
            "WorkspaceInstructionStore",
            "WorkspaceInstructionAuthority",
            "workspaceBlockForTurn",
            "mandatoryBlock(",
            "AGENTS.md",
        )) {
            assertFalse("$token must not reach the chat runtime", vm.contains(token))
        }
        // The identity/instruction string is app-generated only: the SOUL body
        // never becomes part of it (it goes in the provider field instead).
        assertFalse("no workspace block in the prompt builder", soul.contains("AGENTS.md"))
        assertFalse("no workspace block in the prompt builder", soul.contains("WorkspaceInstruction"))
    }

    @Test
    fun `there is exactly one global SOUL file`() {
        assertTrue(
            "the global body lives in one file",
            soul.contains("private const val FILE_NAME = \"SOUL.md\""),
        )
        assertTrue(
            "the file sits in the shared memory directory",
            soul.contains("File(File(context.filesDir, MEMORY_SUBDIR), FILE_NAME)"),
        )
        assertFalse(
            "no per-chat profile directory may be consulted",
            soul.contains("soul-profiles") || soul.contains("SoulProfiles"),
        )
    }

    // ── Per-turn and per-request lifetimes ─────────────────────────────────

    @Test
    fun `every request and every switch re-resolves the selection from the snapshot`() {
        // turn start + chosen switch + per-request reapply + fallback switch.
        val scoped = Regex(
            "applicationPromptForSelection\\(\\s*soulFileForTurn,\\s*promptRulesForTurn,",
        ).findAll(vm).count()
        assertTrue(
            "turn start, both switch points and the per-request reapply must reuse "
                + "the frozen body/rules with a fresh selection (found $scoped)",
            scoped >= 3,
        )
        assertTrue(
            "the loop's starting provider gets the turn's block",
            vm.contains("applyApplicationSystemPrompt(currentProvider, applicationPromptForTurn)"),
        )
        for (provider in listOf("chosen", "next")) {
            assertTrue(
                "a mid-turn switch must not strand the prompt for $provider",
                Regex(
                    "applyApplicationSystemPrompt\\(\\s*$provider,",
                ).containsMatchIn(vm),
            )
        }
    }

    @Test
    fun `a helper never inherits the parent application prompt`() {
        assertTrue(
            "the helper branch resolves nothing",
            vm.contains("if (helperConfig != null) return null"),
        )
        assertTrue(
            "a helper's rule list is empty",
            vm.contains(".getOrDefault(emptyList())") &&
                vm.contains("} else {\n            emptyList()\n        }"),
        )
        assertTrue(
            "a helper keeps its own identity text",
            vm.contains("HelperRunner.identitySection(it, browserEnabled = browserToolEnabled)"),
        )
        assertTrue(vm.contains("SystemPromptBuilder.identitySection(context)"))
    }

    // ── Placement: provider field only ─────────────────────────────────────

    @Test
    fun `the block is sent only through the native application field`() {
        val build = body("    private fun buildSystemPrompt()")
        assertFalse(
            "the body must not be concatenated into the system message",
            build.contains("applicationPrompt("),
        )
        assertFalse(
            "scoped rules must not be concatenated either",
            build.contains("SoulPromptRule"),
        )
        assertTrue("identity is still built here", build.contains("SystemPromptBuilder.identitySection(context)"))
        val apply = body("    private fun applyApplicationSystemPrompt(provider: LLMProvider?, prompt: String?)")
        assertTrue(apply.contains("provider.applicationSystemPrompt = prompt"))
        assertFalse("the prompt text is never logged", apply.contains("AppLogger"))
    }

    @Test
    fun `the body is scrubbed exactly as the builder decides, not by the runtime`() {
        // The scrubber lives in the prompt builder (one place, shared by the
        // global body and the scoped rules); ChatViewModel must not filter the
        // user's text on its way out.
        assertTrue(soul.contains("scrubInjections("))
        assertTrue(soul.contains("val personality = if (trimmed.isEmpty())"))
        assertFalse("the runtime must not re-scrub", vm.contains("scrubInjections"))
    }
}