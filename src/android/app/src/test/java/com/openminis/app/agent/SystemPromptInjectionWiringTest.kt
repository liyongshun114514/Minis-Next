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
    fun `the injection scrubber and the writer's rejection are both intact`() {
        assertTrue(builtins.contains("containsInjectionPattern(raw)"))
        assertTrue(builtins.contains("prompt-injection pattern"))
        val soul = ProductionSources.read("agent/SoulStore.kt")
        assertTrue(soul.contains("fun containsInjectionPattern"))
        assertTrue(soul.contains("private fun scrubInjections"))
        assertTrue(soul.contains("scrubInjections(trimmed)"))
        // Safety/priority text is unchanged and never claims to override them.
        assertTrue(soul.contains("不覆盖模型服务端规则"))
        assertFalse(soul.contains("outranks every instruction"))
        assertEquals(1, Regex("SUPREME_PREAMBLE: String =").findAll(soul).count())
    }
}
