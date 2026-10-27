package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [K-global-prompt-only] The per-model prompt editor and its helpers were
 * deleted. This file used to drive their ordering logic; it now guards that no
 * rule-editing surface or resolver can reappear.
 */
class SoulPromptRuleUiTest {

    private val root = ProductionSources.mainRoot()!!

    @Test
    fun `rule and limit UI sources are removed`() {
        for (name in listOf(
            "SoulPromptRuleUi.kt",
            "SoulPromptRuleEditor.kt",
            "SoulPromptRulesSection.kt",
            "SoulLimitUi.kt",
        )) {
            assertFalse("$name must be deleted", File(root, "ui/settings/$name").exists())
        }
    }

    @Test
    fun `no production source references the deleted helpers`() {
        val refs = ProductionSources.allKotlinFiles().filter { f ->
            val t = f.readText()
            t.contains("SoulPromptRuleUi") ||
                t.contains("SoulPromptRuleEditorDialog") ||
                t.contains("SoulCustomRulesSection") ||
                t.contains("SoulLimitUi")
        }.map { it.name }
        assertTrue("unexpected references: $refs", refs.isEmpty())
    }

    @Test
    fun `legacy rule rows remain data only`() {
        val rules = ProductionSources.read("agent/SoulPromptRules.kt")
        assertTrue(rules.contains("data class SoulPromptRule("))
        assertTrue(rules.contains("data class SoulPromptRulesDocument("))
        assertFalse(rules.contains("SoulPromptRuleResolver"))
        assertFalse(rules.contains("SoulPromptSelection"))
        assertFalse(rules.contains("cachedOrLoad("))
    }
}
