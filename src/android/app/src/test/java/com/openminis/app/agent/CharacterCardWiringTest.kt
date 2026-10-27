package com.openminis.app.agent

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-character-cards-double-injection] Regression for the wiring, not the text:
 * every card body must travel exactly ONCE, through
 * `applicationPrompt(cards, selection)`, and the app-generated identity section
 * must carry identity/style/hint only (no persona body).
 */
class CharacterCardWiringTest {

    private fun characterCard(body: String) = CharacterCard(
        id = "c", name = "Nova", scope = CharacterCardScope.GLOBAL, personality = body,
    )

    private val selection = SoulPromptSelection(
        groupId = "group-a", providerInstanceId = "provider-1", modelId = "model-x",
    )

    private val cards = listOf(
        characterCard("Global body."),
        CharacterCard(
            id = "c2", name = "Second global", scope = CharacterCardScope.GLOBAL,
            personality = "Second global body.",
        ),
        CharacterCard(
            id = "g1", name = "Group rule", scope = CharacterCardScope.GROUP,
            groupId = "group-a", systemPrompt = "Group rule body.",
        ),
        CharacterCard(
            id = "m1", name = "Model rule", scope = CharacterCardScope.MODEL,
            providerInstanceId = "provider-1", modelId = "model-x",
            systemPrompt = "Model rule body.",
        ),
    )

    @Test fun cardBodiesEnterTheApplicationPromptOnceAndInLayerOrder() {
        val block = SystemPromptBuilder.applicationPrompt(cards, selection)!!
        val global = block.indexOf("Global body.")
        val group = block.indexOf("Group rule body.")
        val model = block.indexOf("Model rule body.")
        assertTrue("global body present", global >= 0)
        assertTrue("group layer after global", group > global)
        assertTrue("model layer after group", model > group)
        assertEquals("global body injected once", 1, Regex("Global body\\.").findAll(block).count())
        assertEquals("second global body injected once", 1, Regex("Second global body\\.").findAll(block).count())
        assertTrue("second global after the first", block.indexOf("Second global body.") > global)
        assertEquals("group body injected once", 1, Regex("Group rule body\\.").findAll(block).count())
        assertEquals("model body injected once", 1, Regex("Model rule body\\.").findAll(block).count())
    }

    @Test fun theIdentitySectionSourceCarriesNoPersonaBody() {
        val source = ProductionSources.read("agent/SoulStore.kt")
        val fn = source.substringAfter("fun identitySection(context: Context): String {")
            .substringBefore("\n    }")
        assertTrue("identity section must pass body = null", fn.contains("body = null"))
        assertFalse("identity section must not render cards bodies", fn.contains("CharacterCardPrompt.globalBody"))
        assertFalse("identity section must not call renderCards", fn.contains("renderCards"))
        // The remaining card call is for identity fields only.
        assertTrue(fn.contains("rawName = primary?.name"))
        assertTrue(fn.contains("rawStyle = primary?.style"))
    }

    @Test fun chatViewModelInjectsThePerTurnCardSnapshotThroughTheSelection() {
        val source = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(source.contains("val cardsForTurn"))
        assertTrue(source.contains("CharacterCardStore.load(context).toList()"))
        val resolver = source.substringAfter("private fun applicationPromptForSelection(")
            .substringBefore("\n    private fun isBoundToAbandonedModel")
        assertTrue(resolver.contains("SystemPromptBuilder.applicationPrompt("))
        assertTrue(resolver.contains("cards,"))
        assertTrue(resolver.contains("selection,"))
        assertTrue(source.contains("applyApplicationSystemPrompt(currentProvider, applicationPromptForTurn)"))
    }

    @Test fun theStorePublishesMetadataOnEveryLoadSaveAndUpdate() {
        val source = ProductionSources.read("agent/CharacterCardStore.kt")
        val publish = Regex("SoulStore\\.updateMetadataFromCards\\(").findAll(source).count()
        assertTrue("load/save/update each publish the active metadata", publish >= 4)
        val load = source.substringAfter("fun loadReporting(context: Context): CharacterCardLoad {")
            .substringBefore("\n    /**\n     * Runtime snapshot")
        assertTrue(load.contains("SoulStore.updateMetadataFromCards(visible)"))
        val save = source.substringAfter("fun save(context: Context, cards: List<CharacterCard>) {")
            .substringBefore("\n    /**\n     * [T-character-cards-corrupt]")
        assertTrue(save.contains("SoulStore.updateMetadataFromCards"))
        val update = source.substringAfter("fun update(context: Context, transform:")
            .substringBefore("\n    @Synchronized\n    fun invalidate()")
        assertTrue(update.contains("SoulStore.updateMetadataFromCards(next)"))

        // And the publish itself stays I/O free (it is called while the store lock is held).
        val publisher = ProductionSources.read("agent/SoulStore.kt")
            .substringAfter("internal fun updateMetadataFromCards(cards: List<CharacterCard>) {")
            .substringBefore("\n    }")
        assertFalse(publisher.contains("readText"))
        assertFalse(publisher.contains("CharacterCardStore."))
        assertFalse(publisher.contains("load("))
    }
}
