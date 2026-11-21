package com.openminis.app.agent

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [K-global-prompt-only] Wiring contract: the global SOUL.md body is read once
 * per turn, frozen as one block, and copied onto whichever provider may send a
 * request. No mode, no rule list, no per-entry resolution, and no duplicate
 * concatenation into `buildSystemPrompt`.
 */
class SystemPromptInjectionWiringTest {

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
    private val builtins = ProductionSources.read("config/ConfigBuiltins.kt")

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
        assertTrue(vm.contains("val soulFileForTurn = if (helperConfig == null)"))
        assertTrue(vm.contains("val applicationPromptForTurn = if (helperConfig == null)"))
        assertTrue(vm.contains("SystemPromptBuilder.applicationPrompt(soulFileForTurn)"))
        // One read of SOUL.md for the turn; retries/fallbacks reuse the value.
        val loop = vm.substring(vm.indexOf("val soulFileForTurn"))
        assertTrue(loop.indexOf("SoulStore.load(context)") < loop.indexOf("applicationPromptForTurn"))
    }

    @Test
    fun `soul is the only automatic user instruction reader`() {
        assertFalse(vm.contains("WorkspaceInstructionStore"))
        assertFalse(vm.contains("WorkspaceInstructionAuthority"))
        assertFalse(vm.contains("workspaceBlockForTurn"))
        val fallback = body("private fun applyApplicationSystemPromptGlobal(provider: LLMProvider)")
        assertTrue(fallback.contains("SystemPromptBuilder.applicationPrompt(context)"))
        assertFalse(fallback.contains("listOfNotNull("))
        val nav = ProductionSources.read("ui/navigation/AppNavigation.kt")
        assertFalse(nav.contains("WorkspaceInstructionsScreen("))
        assertFalse(nav.contains("WorkspaceInstructionEditorScreen("))
        for (type in listOf("WorkspaceInstructionStore", "WorkspaceInstructionDiscovery", "WorkspaceInstructionAuthority")) {
            assertFalse("retired runtime implementation $type", ProductionSources.allKotlinFiles().any { it.name == "$type.kt" })
        }
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
        assertFalse("no re-resolution per request", vm.contains("applicationPromptForSelection("))
    }

    @Test
    fun `a helper never inherits the parent prompt`() {
        val snapshot = vm.substring(
            vm.indexOf("val applicationPromptForTurn = if (helperConfig == null)"),
            vm.indexOf("applyApplicationSystemPrompt(currentProvider, applicationPromptForTurn)"),
        )
        assertTrue(snapshot.contains("} else {\n            null\n        }"))
    }

    @Test
    fun `the block is sent only through the native application field`() {
        val build = body("private fun buildSystemPrompt()")
        assertFalse("do not concatenate the global body into the system message",
            build.contains("applicationPrompt("))
        assertTrue("identity is still built here", build.contains("SystemPromptBuilder.identitySection(context)"))
        // Field copy lives in its own helper.
        val apply = body("private fun applyApplicationSystemPrompt(provider: LLMProvider?, prompt: String?)")
        assertTrue(apply.contains("provider.applicationSystemPrompt = prompt"))
    }

    @Test
    fun `the body uses one raw instruction renderer and sensitive writes still require approval`() {
        assertFalse(builtins.contains("containsInjectionPattern(raw)"))
        val writer = builtins.substringAfter("path = \"soul.body\"")
        assertTrue(writer.contains("risk = ConfigRisk.SENSITIVE"))
        assertTrue(writer.contains("saveCurrent(cur.copy(body = raw))"))
        val soul = ProductionSources.read("agent/SoulStore.kt")
        assertFalse(soul.contains("scrubInjections"))
        assertTrue(soul.contains("SoulInstructionRender.render(file?.body)"))
        assertTrue(soul.contains("if (!file?.body.isNullOrBlank()) \"\" else render(file)"))
        val renderer = ProductionSources.read("agent/SoulInstructionRender.kt")
        assertEquals(1, Regex("val MANDATORY_INTRO: String").findAll(renderer).count())
        assertFalse(renderer.contains(".trim()"))
        assertFalse(renderer.contains("replace("))
    }
}
