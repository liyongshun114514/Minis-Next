package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-character-cards] Prompt layers: GLOBAL -> GROUP -> MODEL, ALL enabled
 * matches per tier, exact provider/model identity, and no fake messages.
 */
class CharacterCardPromptTest {

    private fun global(id: String, body: String, name: String = "Nova") = CharacterCard(
        id = id, name = name, scope = CharacterCardScope.GLOBAL, personality = body,
    )

    private fun group(id: String, groupId: String?, text: String, enabled: Boolean = true) = CharacterCard(
        id = id, scope = CharacterCardScope.GROUP, groupId = groupId,
        systemPrompt = text, enabled = enabled,
    )

    private fun model(
        id: String, instance: String?, modelId: String?, text: String, enabled: Boolean = true,
    ) = CharacterCard(
        id = id, scope = CharacterCardScope.MODEL, providerInstanceId = instance,
        modelId = modelId, systemPrompt = text, enabled = enabled,
    )

    private val selection = SoulPromptSelection(
        groupId = "group-a", providerInstanceId = "provider-1", modelId = "model-x",
    )

    @Test fun everyGlobalCardContributesToTheGlobalBodyInOrder() {
        val cards = listOf(global("g1", "First."), global("g2", "Second."), global("g3", "Third."))
        assertEquals("First.\n\nSecond.\n\nThird.", CharacterCardPrompt.globalBody(cards))
        // Identity still comes from the first enabled GLOBAL card.
        assertEquals("Nova", CharacterCardPrompt.resolve(cards, selection).global?.name)
    }

    @Test fun aDisabledGlobalCardIsSkippedButDoesNotHideTheNextOne() {
        val cards = listOf(
            global("g1", "Hidden.").copy(enabled = false),
            global("g2", "Visible."),
        )
        val chain = CharacterCardPrompt.resolve(cards, selection)
        assertEquals(listOf("g2"), chain.globals.map { it.id })
        assertEquals("Visible.", CharacterCardPrompt.globalBody(chain.globals))
    }

    @Test fun allGroupCardsForTheSelectedGroupAreInjectedInDocumentOrder() {
        val cards = listOf(
            global("g", "Global."),
            group("r1", "group-a", "First group rule."),
            group("r2", "group-b", "Other group."),
            group("r3", "group-a", "Second group rule."),
        )
        val chain = CharacterCardPrompt.resolve(cards, selection)
        assertEquals(listOf("r1", "r3"), chain.groups.map { it.id })
        assertEquals(listOf("r1", "r3"), chain.scoped.map { it.id })
    }

    @Test fun allModelCardsForTheExactPairAreInjectedInOrder() {
        val cards = listOf(
            group("r1", "group-a", "Group."),
            model("m1", "provider-1", "model-x", "First model rule."),
            model("m2", "provider-1", "model-x", "Second model rule."),
            model("m3", "provider-2", "model-x", "Other provider."),
            model("m4", "provider-1", "model-y", "Other model."),
        )
        val chain = CharacterCardPrompt.resolve(cards, selection)
        assertEquals(listOf("m1", "m2"), chain.models.map { it.id })
        // Group tier before model tier, each in document order.
        assertEquals(listOf("r1", "m1", "m2"), chain.scoped.map { it.id })
    }

    @Test fun modelMatchingRequiresBothIdsAndNeverFallsBackToAName() {
        val cards = listOf(model("m1", "provider-1", null, "Incomplete."))
        assertTrue(CharacterCardPrompt.resolve(cards, selection).models.isEmpty())

        val noSelection = SoulPromptSelection(groupId = null, providerInstanceId = null, modelId = null)
        val onlyModel = listOf(model("m2", "provider-1", "model-x", "Model."))
        assertTrue(CharacterCardPrompt.resolve(onlyModel, noSelection).models.isEmpty())
    }

    @Test fun disabledScopedCardsAreSkipped() {
        val cards = listOf(
            group("r1", "group-a", "Off.", enabled = false),
            group("r2", "group-a", "On."),
        )
        assertEquals(listOf("r2"), CharacterCardPrompt.resolve(cards, selection).groups.map { it.id })
    }

    @Test fun groupWithBlankIdIsInertNotGlobal() {
        val cards = listOf(group("r1", null, "Should not apply."))
        assertTrue(CharacterCardPrompt.resolve(cards, selection).groups.isEmpty())
        val blankSelection = SoulPromptSelection(groupId = "", providerInstanceId = "p", modelId = "m")
        assertTrue(CharacterCardPrompt.resolve(cards, blankSelection).groups.isEmpty())
    }

    @Test fun examplesAreReferenceOnlyAndFirstMessageIsNeverAMessage() {
        val card = CharacterCard(
            id = "c", name = "Nova",
            firstMessage = "Hello there, traveller.",
            messageExamples = "<START>\n{{char}}: Hi.",
            scope = CharacterCardScope.GLOBAL,
        )
        val body = CharacterCardPrompt.globalBody(card).orEmpty()
        assertTrue(body.contains(CharacterCardPrompt.EXAMPLES_HEADER))
        assertTrue(body.contains("NOT messages in this conversation"))
        assertFalse("first message must not be injected", body.contains("Hello there, traveller."))
    }

    @Test fun aScopedCardPersonaActuallyReachesTheModel() {
        val card = model("m1", "provider-1", "model-x", "").copy(
            name = "Iris",
            personality = "Dry, precise.",
            description = "A navigator.",
            scenario = "Bridge at night.",
            style = "Terse.",
            lang = "zh",
        )
        val body = CharacterCardPrompt.scopedBody(card).orEmpty()
        assertTrue(body.contains("Iris"))
        assertTrue(body.contains("Dry, precise."))
        assertTrue(body.contains("A navigator."))
        assertTrue(body.contains("Bridge at night."))
        assertTrue(body.contains("Terse."))
        assertTrue(body.contains("zh"))
        assertTrue(body.contains(CharacterCardPrompt.SCOPE_CHARACTER_HEADER))
    }

    @Test fun aMigratedLegacyRuleStillRendersOnlyItsContent() {
        val card = group("r1", "group-a", "Only this.").copy(
            name = "Settings-only label",
            legacyRuleDerived = true,
        )
        assertEquals("Only this.", CharacterCardPrompt.scopedBody(card))
        assertFalse(CharacterCardPrompt.scopedBody(card)!!.contains("Settings-only label"))
    }

    @Test fun creatorNotesAndAvatarNeverReachThePrompt() {
        val card = CharacterCard(
            id = "c", name = "Nova",
            icon = "data:image/png;base64,AAAA",
            creatorNotes = "Author-only note.",
            scope = CharacterCardScope.GLOBAL,
        )
        val body = CharacterCardPrompt.globalBody(card)
        assertNull(body)
    }

    @Test fun promptContentMapsToExactlyOneFieldAndPreservesTheRest() {
        val full = CharacterCard(
            id = "c", name = "Nova", scope = CharacterCardScope.GLOBAL,
            personality = "old body", description = "keep desc", scenario = "keep scene",
            systemPrompt = "keep sys", messageExamples = "keep ex", tags = listOf("t"),
            sourceJson = "{}", style = "keep style", lang = "zh",
        )
        val edited = full.withPromptContent("new body")
        assertEquals("new body", edited.personality)
        assertEquals(full.copy(personality = "new body"), edited)

        val scoped = CharacterCard(id = "s", scope = CharacterCardScope.MODEL, systemPrompt = "rule")
        assertEquals("rule", scoped.promptContent())
        assertEquals(scoped.copy(systemPrompt = "x"), scoped.withPromptContent("x"))
    }
}
