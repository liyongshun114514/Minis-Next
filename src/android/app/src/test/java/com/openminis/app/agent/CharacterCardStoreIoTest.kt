package com.openminis.app.agent

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-character-cards] Storage + migration primitives, exercised on plain files
 * (no Context): atomic verified round-trip, corrupt-document refusal, and the
 * pure SOUL -> card merge.
 */
class CharacterCardStoreIoTest {

    private fun tempDir(): java.io.File = Files.createTempDirectory("cards-test").toFile()

    private val fullCard = CharacterCard(
        id = "11111111-1111-1111-1111-111111111111",
        name = "Nova",
        icon = "data:image/png;base64,AAAA",
        enabled = true,
        scope = CharacterCardScope.MODEL,
        groupId = "group-a",
        providerInstanceId = "provider-1",
        modelId = "model-x",
        description = "desc",
        personality = "persona",
        scenario = "scene",
        firstMessage = "hello",
        messageExamples = "ex",
        systemPrompt = "sys",
        postHistoryInstructions = "post",
        creatorNotes = "notes",
        tags = listOf("a", "b"),
        style = "terse",
        lang = "zh",
        legacyEmoji = "🌙",
        sourceJson = """{"spec":"chara_card_v3","data":{"name":"Nova"}}""",
        legacyRuleDerived = true,
    )

    @Test fun everyFieldSurvivesAnAtomicVerifiedRoundTrip() {
        val dir = tempDir()
        val file = java.io.File(dir, "CHARACTER_CARDS.json")
        CharacterCardStore.writeVerified(file, listOf(fullCard))
        val read = CharacterCardStore.readStrict(file)
        assertEquals(listOf(fullCard), read)
        assertTrue(file.exists())
        assertTrue(!java.io.File(dir, "CHARACTER_CARDS.json.tmp").exists())
        dir.deleteRecursively()
    }

    @Test fun aCorruptDocumentIsNeverOverwrittenAndKeepsItsBytes() {
        val dir = tempDir()
        val file = java.io.File(dir, "CHARACTER_CARDS.json")
        val junk = "{ this is not json".toByteArray()
        file.writeBytes(junk)
        assertThrows(IllegalStateException::class.java) {
            CharacterCardStore.writeChecked(file, listOf(fullCard))
        }
        assertTrue("corrupt bytes must be untouched", junk.contentEquals(file.readBytes()))
        // Strict reading is what makes the guard possible.
        assertThrows(CharacterCardImportException::class.java) { CharacterCardStore.readStrict(file) }
        dir.deleteRecursively()
    }

    @Test fun aBareArrayDocumentIsAccepted() {
        val dir = tempDir()
        val file = java.io.File(dir, "cards.json")
        file.writeText("""[{"id":"x","name":"Plain"}]""")
        assertEquals("Plain", CharacterCardStore.readStrict(file).single().name)
        dir.deleteRecursively()
    }

    @Test fun legacySoulBecomesOneGlobalCardWithAvatarAndMetadata() {
        val soul = """
            ---
            name: "Nova"
            emoji: "🌙"
            icon: "data:image/png;base64,QUJD"
            style: "terse"
            lang: "zh"
            ---

            Persona body.
        """.trimIndent()
        val card = CharacterCardLegacyMerge.soulCard(soul, null)!!
        assertEquals("Nova", card.name)
        assertEquals("Persona body.", card.personality)
        assertEquals("terse", card.style)
        assertEquals("zh", card.lang)
        assertEquals("🌙", card.legacyEmoji)
        assertEquals("data:image/png;base64,QUJD", card.icon)
        assertEquals(CharacterCardScope.GLOBAL, card.scope)
        assertTrue(card.enabled)
    }

    @Test fun theSidecarAvatarIsFoldedIntoTheCard() {
        val soul = "---\nname: \"Nova\"\nicon: \"SOUL.icon.png\"\n---\n\nBody.\n"
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val card = CharacterCardLegacyMerge.soulCard(soul, png)!!
        assertTrue(card.icon.startsWith(SoulIcon.DATA_URI_PREFIX))
        assertNotEquals("SOUL.icon.png", card.icon)
    }

    @Test fun migrationIsIdempotentAndNeverOverwritesAnExistingCard() {
        val soulCard = CharacterCardLegacyMerge.soulCard(
            "---\nname: \"Nova\"\n---\n\nBody.\n", null,
        )!!
        val once = CharacterCardLegacyMerge.merge(emptyList(), listOf(soulCard))
        val twice = CharacterCardLegacyMerge.merge(once, listOf(soulCard))
        assertEquals(1, twice.size)

        val edited = soulCard.copy(personality = "User edited this.")
        val merged = CharacterCardLegacyMerge.merge(listOf(edited), listOf(soulCard))
        assertEquals("User edited this.", merged.single().personality)
    }

    @Test fun theSameSoulTextAlwaysProducesTheSameCardId() {
        val soul = "---\nname: \"Nova\"\n---\n\nBody.\n"
        assertEquals(
            CharacterCardLegacyMerge.soulCard(soul, null)!!.id,
            CharacterCardLegacyMerge.soulCard(soul, null)!!.id,
        )
        val different = CharacterCardLegacyMerge.soulCard("---\nname: \"Nova\"\n---\n\nOther.\n", null)!!
        assertNotEquals(CharacterCardLegacyMerge.soulCard(soul, null)!!.id, different.id)
    }

    @Test fun legacyRulesMapToScopedCardsWithoutLosingDisabledOrOrphanedOnes() {
        val json = SoulPromptRuleStore.encode(
            listOf(
                SoulPromptRule(id = "r1", name = "Keep", target = SoulPromptRuleTarget.GROUP, groupId = "g1", content = "Group text."),
                SoulPromptRule(id = "r2", enabled = false, target = SoulPromptRuleTarget.MODEL, providerInstanceId = "p1", modelId = "m1", content = "Off."),
                SoulPromptRule(id = "r3", target = SoulPromptRuleTarget.GROUP, groupId = "deleted-group", content = "Orphan."),
            ),
        )
        val cards = CharacterCardLegacyMerge.ruleCards(json)
        assertEquals(3, cards.size)
        assertEquals(CharacterCardScope.GROUP, cards[0].scope)
        assertEquals("Group text.", cards[0].systemPrompt)
        assertEquals(false, cards[1].enabled)
        assertEquals("p1", cards[1].providerInstanceId)
        assertEquals("m1", cards[1].modelId)
        assertTrue(cards[2].hasValidTarget) // points at a group that may be gone, but is not global
        assertEquals("r1", cards[0].id) // id reuse: a re-import cannot duplicate
    }

    @Test fun malformedRulesAreReportedNotReadAsEmpty() {
        assertThrows(CharacterCardImportException::class.java) {
            CharacterCardLegacyMerge.ruleCardsStrict("{\"rules\":")
        }
    }

    @Test fun multipleGlobalBodiesMergeAndTheEditorCannotDuplicateThem() {
        val a = CharacterCard(id = "a", scope = CharacterCardScope.GLOBAL, personality = "First.")
        val b = CharacterCard(id = "b", scope = CharacterCardScope.GLOBAL, personality = "Second.")
        val merged = SoulStore.mergedGlobalBody(listOf(a, b))
        assertEquals("First.\n\nSecond.", merged)

        val editedBack = SoulStore.stripOtherGlobalBodies(merged, listOf(b.personality))
        assertEquals("First.", editedBack)
        // Unrelated edits are preserved untouched.
        assertEquals("Brand new.", SoulStore.stripOtherGlobalBodies("Brand new.", listOf("Second.")))
    }
}
