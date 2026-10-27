package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-kairos-prompt-mode] Contract for the reduced Soul page and its persisted
 * Global / Custom switch.
 *
 * This is deliberately a source/contract test, not a rendering test: it pins
 * the wiring that the JVM cannot execute without Android — the mode is read
 * from and written to the store, a failed mode write restores the previous
 * selection, the global draft lives above the switch, only MODEL prompts are
 * listed, and a failed per-model save keeps the editor (and its draft) open.
 *
 * Storage, matching and save paths are unchanged and are pinned below.
 */
class SoulSettingsPageContractTest {

    private val src = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")
    private val section = ProductionSources.read("ui/settings/SoulPromptRulesSection.kt")
    private val editor = ProductionSources.read("ui/settings/SoulPromptRuleEditor.kt")
    private val order = ProductionSources.read("ui/settings/SoulPromptRuleUi.kt")

    /** A file under `src/main/res`, resolved from the production source root. */
    private fun res(path: String): String {
        val root = ProductionSources.mainRoot() ?: error("production source root not found")
        // mainRoot() is src/main/java/com/openminis/app; res is src/main/res,
        // four levels up (app → com/openminis → com → java → main).
        val f = File(root, "../../../../res/$path")
        assertTrue("missing resource file: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** The body of one lambda/function, from [start] up to the next marker. */
    private fun slice(start: String, end: String): String {
        val at = src.indexOf(start)
        assertTrue("missing marker: $start", at >= 0)
        val until = src.indexOf(end, at + start.length)
        assertTrue("missing end marker: $end", until > at)
        return src.substring(at, until)
    }

    @Test
    fun `the page edits exactly the avatar, the name and the global prompt`() {
        assertEquals("one prompt editor", 1, Regex("""DocumentEditorField\(""").findAll(src).count())
        assertTrue(src.contains("R.string.soul_section_personality"))
        assertTrue("name field", src.contains("R.string.soul_field_name"))
        assertTrue(src.contains("showIconMenu = true"))
        assertTrue(src.contains("imagePicker.safeLaunchPicker("))
        // The global Save still goes through the SOUL.md store, unchanged.
        assertTrue(src.contains("SoulStore.save(context, currentFile)"))
    }

    @Test
    fun `the removed controls stay removed`() {
        for (gone in listOf(
            "LangPicker",
            "LANG_BUTTON_SIDE_PADDING",
            "SoulBodyLimitControls",
            "R.string.soul_field_style",
            "R.string.soul_field_lang",
            "R.string.soul_lang_",
            "R.string.soul_restore_default",
        )) {
            assertFalse("$gone must not come back", src.contains(gone))
        }
    }

    @Test
    fun `metadata the page does not show is still written back`() {
        assertTrue("emoji preserved", src.contains("preservedEmoji"))
        assertTrue("style round-trips", src.contains("style = style,"))
        assertTrue("lang round-trips", src.contains("lang = lang.ifBlank"))
    }

    @Test
    fun `the length gate still guards save`() {
        assertTrue(src.contains("SoulStore.isOverLimit(body, limitSettings)"))
        assertTrue(src.contains("!bodyLimitCheck.isOverLimit"))
    }

    @Test
    fun `the top buttons read and write the persisted mode`() {
        // Read on load, written on tap — not a local page index.
        assertTrue(src.contains("SoulPromptRuleStore.loadMode(context)"))
        assertTrue(src.contains("var promptMode by rememberSaveable"))
        assertTrue(src.contains("SoulPromptRuleStore.setMode(context, requested)"))
        assertTrue(src.contains("SoulPromptModeSwitch("))
        assertTrue(src.contains("onSelect = selectMode"))
        assertTrue(src.contains("when (promptMode)"))
        assertTrue(src.contains("SoulPromptMode.GLOBAL to R.string.kairos_prompt_mode_global"))
        assertTrue(src.contains("SoulPromptMode.CUSTOM to R.string.kairos_prompt_mode_custom"))
        assertTrue("segmented control", src.contains("SingleChoiceSegmentedButtonRow("))
        assertTrue(src.contains("SegmentedButton("))
        assertTrue(src.contains("SegmentedButtonDefaults.itemShape("))
        assertTrue("disabled until the persisted mode is read", src.contains("enabled = modeLoaded && !modeSaving"))
        assertTrue("one write at a time", src.contains("!rulesBusy && !ruleSaveInFlight"))
        assertEquals("two segments", 2, Regex("""SoulPromptMode\.(GLOBAL|CUSTOM) to R\.string""").findAll(src).count())
        assertEquals("one switch call site", 2, Regex("""SoulPromptModeSwitch\(""").findAll(src).count())
        assertEquals("one switch definition", 1, Regex("""private fun SoulPromptModeSwitch\(""").findAll(src).count())

        // The old presentation-only enum is gone: there is no state that can
        // show one half while the runtime still uses the other.
        assertFalse("no page-only enum", src.contains("SoulPromptScope"))
        assertFalse("no tab state", src.contains("promptTab"))
        assertFalse("no TabRow", src.contains("TabRow("))
    }

    @Test
    fun `a failed mode write restores the previous selection and explains itself`() {
        val select = slice("val selectMode: (SoulPromptMode)", "// [T-android-soul-save-in-appbar]")
        assertTrue("previous selection captured", select.contains("val previous = promptMode"))
        assertTrue("optimistic selection", select.contains("promptMode = requested"))
        assertTrue("restored on failure", select.contains("promptMode = previous"))
        assertTrue("failure surfaced", select.contains("modeError = failure.message ?: \"save failed\""))
        assertTrue("only real changes write", select.contains("SoulPromptRuleUi.shouldSwitchMode("))
        assertTrue(src.contains("R.string.kairos_prompt_mode_error_body"))
        assertTrue(src.contains("R.string.kairos_prompt_mode_error_title"))
        // The draft is not touched by the mode path at all.
        assertFalse("selectMode must not reset the draft", select.contains("body ="))
        assertFalse("selectMode must not reset the name", select.contains("name ="))
    }

    @Test
    fun `switching modes keeps the global draft and leaving asks first`() {
        assertTrue("body draft is hoisted above the switch", src.contains("var body by remember"))
        assertTrue(src.contains("onValueChange = { body = it }"))
        assertTrue(src.contains("var promptMode by rememberSaveable"))
        assertTrue(src.contains("onSelect = selectMode"))
        assertTrue(src.contains("val attemptBack"))
        assertTrue(src.contains("if (isDirty) showDiscardDialog = true else onBack()"))
        assertTrue(src.contains("BackHandler(enabled = isDirty)"))
        assertTrue(src.contains("R.string.soul_discard_confirm_title"))
        assertTrue(src.contains("val isDirty = loaded && baseline != null && currentFile != baseline"))
    }

    @Test
    fun `global save is one-shot and a failure keeps the draft`() {
        assertTrue(src.contains("val save: () -> Unit = {\n        if (!saving) {"))
        assertTrue(src.contains("saving = true"))
        assertTrue(src.contains("enabled = loaded && isDirty && !saving &&"))
        assertTrue(src.contains("saveError = t.message ?: \"save failed\""))
        assertFalse("failure must not clear the draft", src.contains("body = \"\""))
        // The global Save commits SOUL.md only; per-model prompts persist on
        // their own path.
        assertTrue(src.contains("MinisButton(\n                                onClick = save,"))
        assertTrue(src.contains("R.string.kairos_prompt_global_unsaved"))
        assertTrue(src.contains("R.string.kairos_prompt_global_saved"))
        assertTrue(src.contains("persistRules("))
    }

    @Test
    fun `custom page lists only model rows with source preview and actions`() {
        assertTrue(src.contains("SoulCustomRulesSection("))
        assertTrue(src.contains("rules = rules,"))
        assertTrue(src.contains("models = modelOptions,"))
        assertTrue(src.contains("canAdd = modelOptions.isNotEmpty()"))
        assertTrue(src.contains("busy = rulesBusy || ruleSaveInFlight || modeSaving"))
        assertTrue(section.contains("SoulPromptRuleUi.modelEntries(rules)"))
        assertTrue(section.contains("SoulPromptRuleUi.preview(rule.content)"))
        assertTrue(section.contains("SettingsSwitch("))
        assertTrue(section.contains("R.string.kairos_prompt_custom_source"))
        assertTrue(section.contains("R.string.kairos_prompt_custom_edit"))
        assertTrue(section.contains("R.string.kairos_prompt_custom_delete"))
        assertTrue(section.contains("R.string.kairos_prompt_custom_summary"))
        assertTrue(section.contains("R.string.kairos_prompt_custom_add"))

        // No group rows, rule names, scope switch or ordering arrows.
        assertFalse("no group handling in the section", section.contains("SoulPromptRuleTarget.GROUP"))
        assertFalse("no group parameter", section.contains("groups:"))
        assertFalse("no reordering callback", section.contains("onMove"))
        assertFalse("no move icons", section.contains("KeyboardArrow"))
        assertFalse("no icon buttons", section.contains("IconButton("))
        assertFalse("no rule name field", section.contains("rule.name"))
        // Legacy GROUP records are only mentioned as retained, never applied.
        assertTrue(section.contains("R.string.kairos_prompt_custom_legacy"))
        assertTrue("uses the API that keeps GROUP records", section.contains("legacyGroupCount"))
        assertTrue("screen never deletes group rows", !src.contains("SoulPromptRuleTarget.GROUP"))
    }

    @Test
    fun `one effective prompt per provider and model pair`() {
        assertTrue(src.contains("SoulPromptRuleUi.setEnabled(rules, rule.id, checked)"))
        assertTrue(src.contains("SoulPromptRuleUi.upsertModelRule(rules, incoming)"))
        assertTrue(src.contains("SoulPromptRuleUi.stabilize("))
        assertTrue(src.contains("SoulPromptRuleUi.removeRule(rules, pending.id)"))
        assertFalse("no hand-rolled append that can duplicate a pair", src.contains("rules + saved"))
        assertTrue(order.contains("fun upsertModelRule("))
        assertTrue(order.contains("fun setEnabled("))
        assertTrue(order.contains("fun stabilize("))
        assertTrue(order.contains("fun disableOtherPairs("))
        assertTrue(order.contains("rule.copy(enabled = false)"))
        assertTrue("old duplicates are retained, not deleted", order.contains("rules.filterNot { it.id == ruleId }"))
    }

    @Test
    fun `the editor is only model picker body save and cancel`() {
        assertTrue(editor.contains("fun SoulPromptRuleEditorDialog("))
        assertTrue(editor.contains("models: List<SoulPromptModelOption>"))
        assertTrue(editor.contains("configuredPairs: Set<String>"))
        assertTrue(editor.contains("SoulPromptModelPickerDialog("))
        assertTrue(editor.contains("SoulPromptRuleUi.filterModels(models, query)"))
        assertTrue(editor.contains("R.string.kairos_prompt_editor_content"))
        assertTrue(editor.contains("R.string.soul_save"))
        assertTrue(editor.contains("R.string.soul_cancel"))

        // No scope switch, no group target, no rule name, no enabled toggle,
        // no nested dropdown in the form.
        assertFalse("no group scope", editor.contains("SoulPromptRuleTarget.GROUP"))
        assertFalse("no global target", editor.contains("SoulPromptRuleTarget.GLOBAL"))
        assertFalse("no scope segmented buttons", editor.contains("SingleChoiceSegmentedButtonRow("))
        assertFalse("no rule name field", editor.contains("soul_custom_rule_name"))
        assertFalse("no enabled toggle in the editor", editor.contains("SettingsSwitch("))
        assertFalse("no dropdown inside the form", editor.contains("DropdownMenu("))
        assertFalse("no removed applies-to label", editor.contains("prompt_ui_editor_applies_to"))
    }

    @Test
    fun `a new prompt starts unbound and a deleted model is never auto-selected`() {
        assertTrue(
            "existing pair is resolved, everything else is null",
            editor.contains("existing?.let { SoulPromptRuleUi.modelRef(it, models) }"),
        )
        assertFalse("new prompt does not default to the first model", editor.contains("models.firstOrNull()"))
        assertTrue(editor.contains("val hadSelection = selectedModel != null || existing != null"))
        assertTrue(editor.contains("val targetMissing = hadSelection && liveSelected == null"))
        assertTrue(editor.contains("val canSave = liveSelected != null && content.isNotBlank()"))
        assertTrue(editor.contains("R.string.kairos_prompt_editor_missing_target"))
        assertTrue(editor.contains("R.string.kairos_prompt_editor_target_required"))
        // A missing target can only be repaired by an explicit pick.
        assertTrue(editor.contains("selectedModel = picked"))
        assertTrue(editor.contains("if (!canSave || model == null)"))
    }

    @Test
    fun `an open editor revalidates the selected model against the live list`() {
        // A provider edit can remove the model while the dialog is composed.
        // The remembered choice must not remain saveable: resolve it against
        // the current list every recomposition, with an exact pair match.
        assertTrue(editor.contains("val liveSelected = selectedModel?.let { picked ->"))
        assertTrue(editor.contains("models.firstOrNull { option ->"))
        assertTrue(editor.contains("option.providerInstanceId == picked.providerInstanceId &&"))
        assertTrue(editor.contains("option.modelId == picked.modelId"))
        assertTrue("save uses the live option", editor.contains("val model = liveSelected"))
        assertTrue("picker highlights the live option", editor.contains("selected = liveSelected,"))
        // No automatic rebinding: a missing pair becomes null, never another
        // model's option.
        assertFalse(editor.contains("models.firstOrNull() ?:"))
        assertFalse(editor.contains(".takeIf { it.providerInstanceId }"))
    }

    @Test
    fun `a failed per-model save keeps the editor and its draft open`() {
        val save = slice("val saveRule: (SoulPromptRule)", "// [T-kairos-prompt-mode] A tap on the top switch")
        assertTrue("in-flight guard", save.contains("if (!ruleSaveInFlight)"))
        assertTrue("write attempted", save.contains("SoulPromptRuleStore.save(context, next)"))
        assertTrue("failure reported", save.contains("ruleSaveError = failure.message ?: \"save failed\""))
        assertTrue("success closes", save.contains("ruleEditorOpen = false"))
        // The close must be inside the success branch, after the null check.
        val successAt = save.indexOf("if (failure == null) {")
        val closeAt = save.indexOf("ruleEditorOpen = false")
        val failureAt = save.indexOf("} else {", successAt)
        assertTrue("success branch exists", successAt >= 0)
        assertTrue("close inside success branch", closeAt > successAt && closeAt < failureAt)
        assertTrue("failure branch keeps the dialog open", failureAt > successAt)

        assertTrue(src.contains("saving = ruleSaveInFlight"))
        assertTrue(src.contains("saveError = ruleSaveError"))
        assertTrue(src.contains("onSave = saveRule"))
        assertTrue(editor.contains("saveError: String? = null"))
        assertTrue(editor.contains("R.string.kairos_prompt_editor_save_error"))
        assertTrue(editor.contains("var submitLocked by remember"))
        assertTrue(editor.contains("if (!submitLocked && !saving)"))
        assertTrue(editor.contains("LaunchedEffect(saving)"))
        assertTrue("cancel disabled while writing", editor.contains("MinisTextButton(onClick = dismiss, enabled = !saving)"))
        assertTrue("dismiss is ignored while writing", editor.contains("if (!saving) onDismiss()"))
    }

    @Test
    fun `the mode and prompt strings are localized in new resource files`() {
        val en = res("values/kairos_prompt_ui.xml")
        val zh = res("values-zh/kairos_prompt_ui.xml")
        val keys = listOf(
            "kairos_prompt_mode_global",
            "kairos_prompt_mode_custom",
            "kairos_prompt_mode_saving",
            "kairos_prompt_mode_error_title",
            "kairos_prompt_mode_error_body",
            "kairos_prompt_global_badge",
            "kairos_prompt_global_scope",
            "kairos_prompt_global_footer",
            "kairos_prompt_global_saved",
            "kairos_prompt_global_unsaved",
            "kairos_prompt_custom_header",
            "kairos_prompt_custom_intro",
            "kairos_prompt_custom_empty",
            "kairos_prompt_custom_empty_no_models",
            "kairos_prompt_custom_add",
            "kairos_prompt_custom_summary",
            "kairos_prompt_custom_order_hint",
            "kairos_prompt_custom_source",
            "kairos_prompt_custom_missing_model",
            "kairos_prompt_custom_disabled",
            "kairos_prompt_custom_legacy",
            "kairos_prompt_custom_switch_desc",
            "kairos_prompt_custom_edit",
            "kairos_prompt_custom_edit_desc",
            "kairos_prompt_custom_delete",
            "kairos_prompt_custom_delete_desc",
            "kairos_prompt_custom_delete_title",
            "kairos_prompt_custom_delete_body",
            "kairos_prompt_custom_save_error",
            "kairos_prompt_custom_no_content",
            "kairos_prompt_custom_unknown_model",
            "kairos_prompt_editor_add_title",
            "kairos_prompt_editor_edit_title",
            "kairos_prompt_editor_model",
            "kairos_prompt_editor_choose_model",
            "kairos_prompt_editor_change_model",
            "kairos_prompt_editor_model_desc",
            "kairos_prompt_editor_missing_target",
            "kairos_prompt_editor_target_required",
            "kairos_prompt_editor_content",
            "kairos_prompt_editor_content_required",
            "kairos_prompt_editor_save_error",
            "kairos_prompt_editor_saving",
            "kairos_prompt_picker_title",
            "kairos_prompt_picker_search",
            "kairos_prompt_picker_empty",
            "kairos_prompt_picker_no_models",
            "kairos_prompt_picker_configured",
        )
        for (name in keys) {
            assertTrue("$name missing in values", en.contains("name=\"$name\""))
            assertTrue("$name missing in values-zh", zh.contains("name=\"$name\""))
        }
        assertTrue("English labels", en.contains(">Global<") && en.contains(">Custom<"))
        assertTrue("Chinese labels", zh.contains(">全局<") && zh.contains(">自定义<"))
        // The two runtime promises are user-visible, not just code comments.
        assertTrue(en.contains("One prompt for every model"))
        assertTrue(en.contains("sends no user system prompt"))
        assertTrue(zh.contains("对所有模型使用同一段提示词"))
        assertTrue(zh.contains("不会收到用户系统提示词"))
        assertTrue("global mode never appends custom prompts", en.contains("Per-model prompts are not added"))
        assertTrue("delete is explicit", en.contains("Delete this model prompt?"))
        assertTrue("mode failure names the kept selection", en.contains("previous selection is still active"))
    }

    @Test
    fun `the runtime still snapshots mode and rules per turn`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        // One document snapshot at turn start: mode and rules must not come
        // from two reads a Settings save can split.
        assertTrue(vm.contains("SoulPromptRuleStore.cachedDocumentOrLoad(context)"))
        assertTrue(vm.contains("val promptDocumentForTurn"))
        assertTrue(vm.contains("val promptModeForTurn = promptDocumentForTurn.mode"))
        assertTrue(vm.contains("val promptRulesForTurn = promptDocumentForTurn.rules"))
        assertTrue(vm.contains("applicationPromptForSelection("))
        assertTrue(vm.contains("entry?.baseModel?.id"))
        assertTrue(vm.contains("SystemPromptBuilder.applicationPrompt("))
        assertFalse("no group fallback re-introduced in the VM", vm.contains("SoulPromptRuleTier.GROUP"))
    }
}
