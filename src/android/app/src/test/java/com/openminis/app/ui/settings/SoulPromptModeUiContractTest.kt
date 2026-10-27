package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [K-global-prompt-only] The persisted GLOBAL/CUSTOM switch is gone from both
 * the page and the runtime. Legacy data is still readable by the store, but no
 * UI surfaces it and no turn consults it.
 */
class SoulPromptModeUiContractTest {

    private val screen = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")
    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")

    @Test
    fun `the page has no mode control`() {
        assertFalse(screen.contains("SoulPromptMode"))
        assertFalse(screen.contains("SegmentedButton"))
        assertFalse(screen.contains("promptMode"))
        assertFalse(screen.contains("setMode("))
        assertFalse(screen.contains("loadMode("))
    }

    @Test
    fun `the runtime never reads a stored mode or rule list`() {
        assertFalse(vm.contains("SoulPromptRuleStore"))
        assertFalse(vm.contains("SoulPromptMode"))
        assertFalse(vm.contains("SoulPromptRule"))
        assertTrue(vm.contains("SystemPromptBuilder.applicationPrompt(soulFileForTurn)"))
    }

    @Test
    fun `legacy storage still decodes old documents`() {
        val rules = ProductionSources.read("agent/SoulPromptRules.kt")
        assertTrue(rules.contains("enum class SoulPromptMode"))
        assertTrue(rules.contains("CUSTOM"))
        assertTrue(rules.contains("SOUL_PROMPT_RULES.json"))
        assertTrue("legacy rows stay decodable", rules.contains("enum class SoulPromptRuleTarget"))
        assertFalse("but nothing resolves them", rules.contains("SoulPromptRuleResolver"))
    }

    @Test
    fun `the page still owns the global draft independently`() {
        assertTrue(screen.contains("var body by remember"))
        assertTrue(screen.contains("SoulStore.save(context, currentFile)"))
        assertTrue(screen.contains("baseline"))
    }
}
