package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the character-card settings page that replaced the old SOUL
 * editor surface.
 *
 * The class name is intentionally kept: the Android workflow and review
 * scripts run this test by name. The assertions are the NEW behaviour — there
 * is no "still edits SOUL.md", "no tabs" or old prompt-body contract left.
 *
 * Storage, parsing and prompt assembly are owned by the agent layer
 * (`CharacterCardStore` + the other agent's backend); this test pins the UI to
 * that fixed API and pins the safety properties that a refactor could silently
 * lose (draft-only import, no auto-enable, read-modify-write merges, unsaved
 * guard, required binding).
 */
class SoulSettingsPageContractTest {

    private val wrapper = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")
    private val screen = ProductionSources.read("ui/settings/CharacterCardsScreen.kt")
    private val editor = ProductionSources.read("ui/settings/CharacterCardEditorScreen.kt")
    private val importSheet = ProductionSources.read("ui/settings/CharacterCardImportSheet.kt")
    private val picker = ProductionSources.read("ui/settings/CharacterCardTargetPicker.kt")

    @Test
    fun `the old soul route is a thin wrapper over the card page`() {
        assertTrue(wrapper.contains("fun SoulSettingsScreen(onBack: () -> Unit)"))
        assertTrue(wrapper.contains("CharacterCardsScreen(onBack = onBack)"))
        // The old page is gone from the wrapper; no SOUL.md body editing here.
        assertFalse(wrapper.contains("SoulStore.save"))
        assertFalse(wrapper.contains("DocumentEditorField"))
        assertFalse(wrapper.contains("SoulPromptRuleStore"))
    }

    @Test
    fun `the shared glyph keeps its exact call signature and fill behaviour`() {
        // ChatExportHeader, ChatAssistantMessageUI and the overlay controller
        // call this symbol by name; the signature is load-bearing.
        assertTrue(
            wrapper.contains(
                "internal fun SoulIconGlyph(\n" +
                    "    icon: String,\n" +
                    "    sizeDp: Dp,\n" +
                    "    emojiSp: TextUnit,\n" +
                    "    sparkleTint: Brush? = null,\n" +
                    ")",
            ),
        )
        assertTrue(wrapper.contains("contentScale = ContentScale.Crop"))
        assertTrue(wrapper.contains("RoundedCornerShape(sizeDp * SoulIcon.CORNER_RADIUS_FRACTION)"))
        assertTrue(wrapper.contains("SoulIcon.decode(icon)"))
    }

    @Test
    fun `the page is a two segment global custom switch and defaults to global`() {
        assertTrue(screen.contains("SingleChoiceSegmentedButtonRow("))
        assertTrue(screen.contains("R.string.character_cards_tab_global"))
        assertTrue(screen.contains("R.string.character_cards_tab_custom"))
        assertTrue(screen.contains("mutableStateOf(CharacterCardTab.GLOBAL)"))
        assertFalse("no TabRow page selector", screen.contains("TabRow("))
        // The custom tab owns GROUP and MODEL cards; global only owns GLOBAL.
        assertTrue(screen.contains("it.scope == CharacterCardScope.GLOBAL"))
        assertTrue(screen.contains("it.scope != CharacterCardScope.GLOBAL"))
    }

    @Test
    fun `cards can be created edited toggled deleted imported and exported`() {
        assertTrue(screen.contains("CharacterCard(scope = CharacterCardScope.GLOBAL)"))
        assertTrue(screen.contains("CharacterCard(scope = CharacterCardScope.MODEL)"))
        assertTrue(screen.contains("CharacterCardEditorScreen("))
        assertTrue(screen.contains("setEnabled("))
        assertTrue(screen.contains("pendingDelete = card"))
        assertTrue(screen.contains("R.string.character_cards_delete_title"))
        assertTrue(screen.contains("R.string.character_cards_delete_body"))
        assertTrue(screen.contains("CharacterCardStore.loadReporting(context)"))
        assertTrue(screen.contains("loadError = report.issue"))
        assertTrue(screen.contains("CharacterCardStore.save(context, latest)"))
        assertTrue(screen.contains("CharacterCardStore.importCard(read.bytes)"))
        assertTrue(screen.contains("CharacterCardStore.exportCard(card)"))
        assertTrue(screen.contains("ActivityResultContracts.OpenDocument()"))
        assertTrue(screen.contains("ActivityResultContracts.CreateDocument(\"application/json\")"))
    }

    @Test
    fun `writes merge with the latest file under the store monitor`() {
        // The config CLI and backup restore can edit cards while this screen is
        // open. An in-memory list save would silently drop those edits.
        assertTrue(screen.contains("synchronized(CharacterCardStore)"))
        assertTrue(screen.contains("val latest = CharacterCardStore.load(context).toMutableList()"))
        assertTrue(screen.contains("transform(latest)"))
        assertTrue(screen.contains("CharacterCardStore.save(context, latest)"))
        assertTrue("writes happen off the main thread", screen.contains("withContext(Dispatchers.IO)"))
        // No card operation may go back to saving a stale snapshot.
        assertFalse(screen.contains("persist(next)"))
    }

    @Test
    fun `import reads a bounded file and only creates a disabled draft`() {
        assertTrue(screen.contains("MAX_IMPORT_BYTES = CharacterCardImport.MAX_INPUT_BYTES"))
        assertTrue(screen.contains("if (total > MAX_IMPORT_BYTES) return CardFileRead.TooLarge"))
        assertTrue(screen.contains("CardFileRead.Ok(output.toByteArray())"))
        assertTrue(screen.contains("importDraft = it"))
        assertTrue(screen.contains("enabled = false"))
        assertTrue(importSheet.contains("R.string.character_cards_import_preview_body"))
        assertTrue(importSheet.contains("R.string.character_cards_import_add"))
        // A colliding id from a concurrent CLI import is replaced, not merged
        // over the existing card.
        assertTrue(screen.contains("java.util.UUID.randomUUID().toString()"))
        assertTrue(screen.contains("latest.any { it.id == candidate.id }"))
    }

    @Test
    fun `the editor groups every card field`() {
        for (fieldRes in listOf(
            "character_card_editor_description",
            "character_card_editor_personality",
            "character_card_editor_scenario",
            "character_card_editor_first_message",
            "character_card_editor_message_examples",
            "character_card_editor_system_prompt",
            "character_card_editor_post_history",
            "character_card_editor_creator_notes",
            "character_card_editor_tags",
        )) {
            assertTrue("missing editor field $fieldRes", editor.contains(fieldRes))
        }
        assertTrue(editor.contains("R.string.character_card_editor_section_basic"))
        assertTrue(editor.contains("R.string.character_card_editor_section_character"))
        assertTrue(editor.contains("R.string.character_card_editor_section_dialogue"))
        assertTrue(editor.contains("R.string.character_card_editor_section_prompt"))
        assertTrue(editor.contains("R.string.character_card_editor_section_notes"))
        // Avatar is editable through the same glyph used everywhere.
        assertTrue(editor.contains("SoulIconGlyph(icon = icon"))
        assertTrue(editor.contains("SoulIcon.encode(bitmap)"))
    }

    @Test
    fun `custom cards must name a real binding and names cannot be blank`() {
        assertTrue(editor.contains("val nameError = draft.name.isBlank()"))
        assertTrue(editor.contains("groupMissing"))
        assertTrue(editor.contains("modelMissing"))
        assertTrue(editor.contains("CHARACTER_CARD_MAX_FIELD_CHARS = 100_000"))
        assertTrue(editor.contains("character_card_editor_field_too_long"))
        assertTrue(editor.contains("R.string.character_card_editor_name_required"))
        assertTrue(editor.contains("R.string.character_card_editor_bind_required_group"))
        assertTrue(editor.contains("R.string.character_card_editor_bind_required_model"))
        assertTrue(editor.contains("val targetBlocksEnable = targetMissing && enabled"))
        assertTrue(editor.contains("targetBlocksEnable || tooLong"))
        // The pair is the identity; the builder clears the irrelevant target
        // fields so a GROUP card cannot keep a stale model binding.
        assertTrue(editor.contains("providerInstanceId = if (cardScope == CharacterCardScope.MODEL)"))
        assertTrue(editor.contains("groupId = if (cardScope == CharacterCardScope.GROUP)"))
        assertTrue(picker.contains("it.providerInstanceId + \"\\u0000\" + it.modelId"))
    }

    @Test
    fun `leaving an edited card asks before discarding`() {
        assertTrue(editor.contains("BackHandler { attemptCancel() }"))
        assertTrue(editor.contains("if (saving) return"))
        assertTrue(editor.contains("if (isDirty) showDiscard = true else onCancel()"))
        assertTrue(editor.contains("R.string.character_card_editor_unsaved_title"))
        assertTrue(editor.contains("R.string.character_card_editor_discard"))
        assertTrue(editor.contains("R.string.character_card_editor_keep_editing"))
    }

    @Test
    fun `a failed write keeps the editor open and prevents double submit`() {
        assertTrue(editor.contains("var saving by remember { mutableStateOf(false) }"))
        assertTrue(editor.contains("if (nameError || groupMissing || modelMissing || targetBlocksEnable || tooLong || saving) return"))
        assertTrue(editor.contains("onSave: suspend (CharacterCard) -> Result<Unit>"))
        assertTrue(editor.contains(".onFailure"))
        assertTrue(editor.contains("R.string.character_card_editor_save_error_title"))
        assertTrue(screen.contains("busyCardIds"))
        assertTrue(screen.contains("enabled = !busy"))
        assertTrue(importSheet.contains("enabled = !saving && !missingBinding"))
    }

    @Test
    fun `legacy group rules are migrated cards and stay visible and editable`() {
        // CharacterCardStore.importLegacy projects SOUL_PROMPT_RULES.json into
        // GROUP/MODEL cards carrying legacyRuleDerived. The UI must show them
        // in the normal custom list, keep the marker when saving, and must not
        // write the retired rules file.
        assertTrue(screen.contains("legacyRuleDerived"))
        assertTrue(screen.contains("R.string.character_cards_badge_legacy"))
        assertTrue(screen.contains("R.string.character_cards_legacy_migrated_note"))
        assertTrue(editor.contains("initial.copy("))
        assertFalse(screen.contains("SoulPromptRuleStore"))
        assertFalse(screen.contains("SoulPromptRuleEditorDialog"))
    }

    @Test
    fun `card content is treated as data and safety priority is stated`() {
        assertTrue(editor.contains("R.string.character_card_editor_notes_footer"))
        // No new "system" bypass: the UI never writes prompt priority or the
        // safety layer, it only stores text fields.
        assertFalse(editor.contains("SystemPromptBuilder"))
        assertFalse(editor.contains("SafetyPolicy"))
    }

    @Test
    fun `runtime freezes character cards per turn from the card store`() {
        // VM correction: one turn snapshots CharacterCardStore.load(context).
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("val cardsForTurn = if (helperConfig == null) {"))
        assertTrue(vm.contains("CharacterCardStore.load(context).toList()"))
        assertTrue(vm.contains("val applicationPromptForTurn = applicationPromptForSelection("))
        assertTrue(vm.contains("cardsForTurn,"))
        assertFalse("legacy rule cache is no longer the application point", vm.contains("SoulPromptRuleStore.cachedOrLoad"))
    }

    @Test
    fun `resources are paired and every referenced key exists`() {
        val en = readResource("values/character_cards.xml")
        val zh = readResource("values-zh/character_cards.xml")
        val enKeys = stringKeys(en)
        val zhKeys = stringKeys(zh)
        assertEquals("zh must mirror en exactly", enKeys, zhKeys)
        assertTrue(enKeys.contains("character_cards_title"))
        assertTrue(enKeys.contains("character_cards_subtitle"))
        assertTrue(zhKeys.contains("character_cards_subtitle"))
        val referenced = Regex("""R\.string\.(character_card_[A-Za-z0-9_]+)""")
            .findAll(wrapper + screen + editor + importSheet + picker)
            .map { it.groupValues[1] }
            .toSet()
        for (key in referenced) {
            assertTrue("missing EN string $key", enKeys.contains(key))
            assertTrue("missing ZH string $key", zhKeys.contains(key))
        }
    }

    private fun readResource(rel: String): String {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(
                java.io.File(dir, "src/main/res/$rel"),
                java.io.File(dir, "src/android/app/src/main/res/$rel"),
                java.io.File(dir, "app/src/main/res/$rel"),
            )) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        error("resource not found: $rel from ${java.io.File("").absolutePath}")
    }

    private fun stringKeys(xml: String): Set<String> =
        Regex("""<string name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()
}
