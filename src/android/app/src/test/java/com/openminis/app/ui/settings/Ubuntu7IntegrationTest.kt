package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.*
import org.junit.Test

/** UI/source wiring checks complement pure storage tests and the full Android cloud compile. */
class Ubuntu7IntegrationTest {
    @Test fun `memory panel no longer edits persona files`() {
        val source = ProductionSources.read("ui/chat/SessionMemorySheet.kt")
        assertFalse(source.contains("memoryRepository.readFile(\"SOUL.md\")"))
        assertFalse(source.contains("name = \"SOUL.md\""))
        assertTrue(source.contains("memoryRepository.loadGlobalMd()"))
    }
    @Test fun `settings entry opens character cards`() {
        val source = ProductionSources.read("ui/settings/SettingsScreen.kt")
        assertTrue(source.contains("R.string.character_cards_title"))
        assertTrue(source.contains("R.string.character_cards_subtitle"))
    }
    @Test fun `runtime reminder points to active cards not stale file`() {
        val source = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(source.contains("\"active character cards and GLOBAL.md\""))
        assertFalse(source.contains("val files = if (hasGlobal) \"SOUL.md"))
    }
    @Test fun `concurrency uses standard scrollable Minis choice rows`() {
        val source = ProductionSources.read("ui/settings/SubAgentsScreen.kt")
        val page = source.substringBefore("fun SubAgentDetailScreen")
        assertTrue(page.contains("ModalBottomSheet(onDismissRequest"))
        assertTrue(page.contains("verticalScroll(rememberScrollState())"))
        assertTrue(page.contains("SettingsChoiceRow("))
        assertTrue(page.contains("1..AgentJobRegistry.MAX_USER_CONCURRENT_CHILD_JOBS"))
        assertTrue(page.contains("SubAgentConcurrencyPrefs.set(context, count)"))
        assertFalse(page.contains("RadioButton("))
    }
    @Test fun `character CLI uses same store and approved config registry`() {
        val registry = ProductionSources.read("config/ConfigBuiltins.kt")
        val source = ProductionSources.read("config/collections/CharacterCardsCollection.kt")
        assertTrue(registry.contains("CharacterCardsCollection(context, providerRepo)"))
        assertTrue(source.contains("ConfigRisk.SENSITIVE"))
        assertTrue(source.contains("synchronized(CharacterCardStore)"))
        assertTrue(source.contains("CharacterCardStore.save(context,"))
        assertTrue(source.contains("it.providerInstanceId == provider && it.model.id == model"))
        assertTrue(source.contains("c.copy(scope = scope,"))
    }
}
