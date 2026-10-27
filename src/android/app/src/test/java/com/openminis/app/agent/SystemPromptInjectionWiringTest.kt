package com.openminis.app.agent

import com.openminis.app.ProductionSources
import org.junit.Assert.*
import org.junit.Test

/** Ordinary user configuration uses native system channels, never forged messages. */
class SystemPromptInjectionWiringTest {
    @Test fun `global and matching scoped text each enter the application prompt once`() {
        val file = SoulFile(SoulMetadata.DEFAULT, "GLOBAL_UNIQUE_TEXT")
        val rules = listOf(SoulPromptRule(id = "a", target = SoulPromptRuleTarget.MODEL,
            providerInstanceId = "p", modelId = "m", content = "MODEL_UNIQUE_TEXT"))
        val block = SystemPromptBuilder.applicationPrompt(file, rules,
            SoulPromptSelection(providerInstanceId = "p", modelId = "m"))!!
        assertEquals(1, Regex("GLOBAL_UNIQUE_TEXT").findAll(block).count())
        assertEquals(1, Regex("MODEL_UNIQUE_TEXT").findAll(block).count())
        assertTrue(block.indexOf("GLOBAL_UNIQUE_TEXT") < block.indexOf("MODEL_UNIQUE_TEXT"))
        val other = SystemPromptBuilder.applicationPrompt(file, rules,
            SoulPromptSelection(providerInstanceId = "other", modelId = "m"))!!
        assertFalse(other.contains("MODEL_UNIQUE_TEXT"))
    }
    @Test fun `generated identity omits the body sent by the provider system channel`() {
        val source = ProductionSources.read("agent/SoulStore.kt")
        val body = source.substringAfter("fun identitySection(context: Context): String {")
            .substringBefore("\n    }")
        assertTrue(body.contains("file?.copy(body = \"\")"))
        assertFalse(body.contains("CharacterCard"))
    }
    @Test fun `normal prompt settings retain original stores and no character backend`() {
        val source = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")
        assertTrue(source.contains("SoulStore.save(context, currentFile)"))
        assertTrue(source.contains("SoulPromptRuleStore.save(context, next)"))
        assertFalse(source.contains("CharacterCard"))
        val main = ProductionSources.mainRoot()!!
        assertFalse(java.io.File(main, "agent/CharacterCardStore.kt").exists())
        assertFalse(java.io.File(main, "ui/settings/CharacterCardsScreen.kt").exists())
    }
}
