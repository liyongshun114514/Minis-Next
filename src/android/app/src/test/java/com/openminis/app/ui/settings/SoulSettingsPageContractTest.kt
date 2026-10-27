package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-soul-system-prompt-only] Contract for the reduced Soul page,
 * extended for the restructured prompt area and the two-scope switch.
 *
 * The page is the system-prompt setting and nothing else: an editable avatar,
 * a name, the global prompt, and the scoped custom rules. Two segmented
 * buttons at the top choose which half is on screen — the global page (avatar,
 * name, body, its own Save) or the custom rules page. Storage, matching and
 * save paths are unchanged and are pinned below.
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

    @Test
    fun `the page edits exactly the avatar, the name and the prompt`() {
        // One multiline editor, labelled as the system prompt.
        assertEquals("one prompt editor", 1, Regex("""DocumentEditorField\(""").findAll(src).count())
        assertTrue(src.contains("R.string.soul_section_personality"))
        assertTrue("name field", src.contains("R.string.soul_field_name"))
        // The avatar still opens the emoji / image picker menu.
        assertTrue(src.contains("showIconMenu = true"))
        assertTrue(src.contains("imagePicker.safeLaunchPicker("))
        // Save still goes through the SOUL.md store.
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
        // style / lang / emoji are preserved verbatim so a save from this page
        // cannot reset values set by an older build or another device.
        assertTrue("emoji preserved", src.contains("preservedEmoji"))
        assertTrue("style round-trips", src.contains("style = style,"))
        assertTrue("lang round-trips", src.contains("lang = lang.ifBlank"))
    }

    @Test
    fun `the length gate still guards save`() {
        // [T-soul-unlimited-default] The gate stays wired to the same
        // classifier SoulStore.save() enforces — it is simply Ok by default
        // now (mode UNLIMITED), so it never blocks a save.
        assertTrue(src.contains("SoulStore.isOverLimit(body, limitSettings)"))
        assertTrue(src.contains("!bodyLimitCheck.isOverLimit"))
    }

    @Test
    fun `the over-limit banner cannot fire under the default policy`() {
        // It is gated on the MODE as well as the result: only CUSTOM (which this
        // page cannot reach) can report OverLimit*.
        assertTrue(src.contains("!limitSettings.mode.isUnlimited"))
        assertFalse("no unconditional red banner", src.contains("val overLimit = bodyLimitCheck\n                if (overLimit is"))
    }

    @Test
    fun `the avatar fills its frame instead of letterboxing`() {
        // [T-soul-avatar-fill] Default ContentScale.Fit left blank bands on the
        // two short sides of a non-square image.
        assertTrue("avatar image must Crop", src.contains("contentScale = ContentScale.Crop"))
        assertTrue(src.contains("RoundedCornerShape(sizeDp * SoulIcon.CORNER_RADIUS_FRACTION)"))
    }

    @Test
    fun `the square crop hint is gone from the menu`() {
        assertFalse("hint line removed", src.contains("R.string.soul_icon_image_hint"))
        assertTrue("the menu item still has a title", src.contains("R.string.soul_icon_choose_image"))
    }

    // ── Two scope buttons: global page / custom rules page ────────────────

    @Test
    fun `two scope buttons choose the global page and the custom rules`() {
        // The switch is the same Material3 segmented control the rest of
        // Settings uses — NOT a TabRow, which used to hide one half of the
        // page and made the app-bar Save look like it saved the rules too.
        assertTrue(src.contains("SoulPromptScopeSwitch("))
        assertTrue(src.contains("SingleChoiceSegmentedButtonRow("))
        assertTrue(src.contains("SegmentedButton("))
        assertTrue(src.contains("SegmentedButtonDefaults.itemShape("))
        assertTrue(src.contains("SoulPromptScope.GLOBAL to R.string.system_prompt_scope_global"))
        assertTrue(src.contains("SoulPromptScope.CUSTOM to R.string.system_prompt_scope_custom"))
        assertTrue("the switch is state, not a tab index", src.contains("when (promptScope)"))
        assertFalse("no tabs", src.contains("TabRow("))
        assertFalse("no page selector state", src.contains("promptTab"))
        // Two buttons and two pages, one of each.
        assertEquals("two segments", 2, Regex("""SoulPromptScope\.(GLOBAL|CUSTOM) to R\.string""").findAll(src).count())
        assertTrue(src.contains("private fun SoulPromptScopeSwitch("))
        assertEquals("one switch call site", 2, Regex("""SoulPromptScopeSwitch\(""").findAll(src).count())
    }

    @Test
    fun `the global page owns the identity, the editor and the save`() {
        val switchCall = src.indexOf("SoulPromptScopeSwitch(\n")
        val globalBranch = src.indexOf("SoulPromptScope.GLOBAL -> {")
        val customBranch = src.indexOf("SoulPromptScope.CUSTOM -> {")
        val avatar = src.indexOf("showIconMenu = true")
        val editorField = src.indexOf("DocumentEditorField(")
        val saveButton = src.indexOf("onClick = save,")
        val saveStatus = src.indexOf("R.string.system_prompt_saving")
        val rules = src.indexOf("SoulCustomRulesSection(")

        assertTrue("switch on top", switchCall in 0 until globalBranch)
        assertTrue("two ordered branches", customBranch > globalBranch)
        // Avatar + name, body editor and its own Save/status are on the GLOBAL
        // page; the rules section is on the CUSTOM page (and nowhere else).
        for ((name, at) in listOf(
            "avatar" to avatar,
            "editor" to editorField,
            "save button" to saveButton,
            "save status" to saveStatus,
        )) {
            assertTrue("$name on the global page", at in (globalBranch + 1) until customBranch)
        }
        assertTrue("rules on the custom page", rules > customBranch)
        assertEquals("exactly one rules section", 1, Regex("""SoulCustomRulesSection\(""").findAll(src).count())
    }

    @Test
    fun `the scope labels are localized in their own resource files`() {
        val en = res("values/system_prompt_ui.xml")
        val zh = res("values-zh/system_prompt_ui.xml")
        for (name in listOf(
            "system_prompt_scope_global",
            "system_prompt_scope_custom",
            "system_prompt_saving",
        )) {
            assertTrue("$name missing in values", en.contains("name=\"$name\""))
            assertTrue("$name missing in values-zh", zh.contains("name=\"$name\""))
        }
        assertTrue("English labels", en.contains(">Global<") && en.contains(">Custom<"))
        assertTrue("Chinese labels", zh.contains(">全局<") && zh.contains(">自定义<"))
    }

    @Test
    fun `switching scope keeps the global draft and leaving asks first`() {
        // The draft is screen-level state ABOVE the switch, so hiding the
        // editor cannot drop it; the switch only chooses what is composed.
        assertTrue("body draft is hoisted", src.contains("var body by remember"))
        assertTrue(src.contains("onValueChange = { body = it }"))
        assertTrue(src.contains("var promptScope by rememberSaveable"))
        assertTrue(src.contains("onSelect = { promptScope = it }"))
        // Leaving with unsaved edits still asks, on either page.
        assertTrue(src.contains("val attemptBack"))
        assertTrue(src.contains("if (isDirty) showDiscardDialog = true else onBack()"))
        assertTrue(src.contains("BackHandler(enabled = isDirty)"))
        assertTrue(src.contains("R.string.soul_discard_confirm_title"))
        // The dirty comparison is still against what was loaded.
        assertTrue(src.contains("val isDirty = loaded && baseline != null && currentFile != baseline"))
    }

    @Test
    fun `save is one-shot and a failure keeps the draft`() {
        // The tap is guarded synchronously, so it cannot enqueue a second write
        // before the first lands, and the button is disabled from the same flag.
        assertTrue(src.contains("val save: () -> Unit = {\n        if (!saving) {"))
        assertTrue(src.contains("saving = true"))
        assertTrue(src.contains("enabled = loaded && isDirty && !saving &&"))
        assertTrue(src.contains("R.string.system_prompt_saving"))
        // The global Save commits SOUL.md only; rules persist on change.
        assertTrue(src.contains("MinisButton(\n                                onClick = save,"))
        assertTrue(src.contains("R.string.prompt_ui_global_unsaved"))
        assertTrue(src.contains("R.string.prompt_ui_global_saved"))
        assertTrue("rules persist on change", src.contains("persistRules("))
        // A failed write reports and keeps the draft: nothing resets `body`.
        assertTrue(src.contains("saveError = t.message ?: \"save failed\""))
        assertFalse("failure must not clear the draft", src.contains("body = \"\""))
    }

    @Test
    fun `custom rules can be enabled targeted edited added and deleted`() {
        assertTrue(src.contains("SoulPromptRuleStore.load(context)"))
        assertTrue(src.contains("SoulPromptRuleStore.save(context, next)"))
        assertTrue(src.contains("persistRules("))
        assertTrue(src.contains("SoulPromptRuleEditorDialog("))
        assertTrue(src.contains("rules.filterNot { it.id == pending.id }"))
        // The list itself lives in its own UI-only file.
        assertTrue(section.contains("SettingsSwitch("))
        assertTrue(section.contains("SoulPromptRuleTarget.GROUP"))
        assertTrue(section.contains("SoulPromptRuleTarget.MODEL"))
        assertTrue(section.contains("R.string.soul_custom_rules_add"))
    }

    @Test
    fun `a rule row shows scope target on-off state and a body preview`() {
        assertTrue(section.contains("R.string.soul_custom_rule_target_group"))
        assertTrue(section.contains("R.string.soul_custom_rule_target_model"))
        assertTrue(section.contains("R.string.soul_custom_rule_group_label"))
        assertTrue(section.contains("R.string.soul_custom_rule_model_label"))
        assertTrue(section.contains("R.string.soul_custom_rule_target_missing"))
        assertTrue(section.contains("prompt_ui_rules_summary"))
        assertTrue(section.contains("SoulPromptRuleUi.preview("))
        assertTrue(section.contains("prompt_ui_rule_disabled"))
    }

    @Test
    fun `reordering is offered inside a scope and persisted in list order`() {
        assertTrue(section.contains("onMove(rule, -1)"))
        assertTrue(section.contains("onMove(rule, 1)"))
        assertTrue(src.contains("SoulPromptRuleUi.moveWithinTier(rules, rule.id, delta)"))
        assertTrue(src.contains("if (next !== rules) persistRules(next)"))
        assertTrue(order.contains("fun moveWithinTier("))
        // No sorting by id/name anywhere: the list order IS the injection
        // order, so a display sort would silently reorder the prompt.
        assertFalse(order.contains("sortedBy"))
        assertFalse(order.contains("sortedWith"))
    }

    @Test
    fun `a deleted target is shown as missing and never silently global`() {
        assertTrue(section.contains("SoulPromptRuleUi.targetRef("))
        assertTrue(section.contains("R.string.soul_custom_rule_target_missing"))
        assertTrue(section.contains("R.string.soul_custom_rules_intro"))
        // The rule editor offers exactly Group and Model — no "all" scope.
        assertTrue(editor.contains("SoulPromptRuleTarget.GROUP"))
        assertTrue(editor.contains("SoulPromptRuleTarget.MODEL"))
        assertFalse("no global target option", editor.contains("SoulPromptRuleTarget.GLOBAL"))
        assertFalse(editor.contains("AllModels"))
    }

    @Test
    fun `the editor selects scope and target without nested menus`() {
        assertFalse("no tab row in the form", editor.contains("TabRow("))
        assertFalse("no dropdown inside the scrolling form", editor.contains("DropdownMenu("))
        assertTrue(editor.contains("SingleChoiceSegmentedButtonRow("))
        assertTrue(editor.contains("SoulPromptTargetPickerDialog("))
        assertTrue(editor.contains("prompt_ui_editor_applies_to"))
        assertTrue(editor.contains("prompt_ui_editor_target"))
        assertTrue(editor.contains("prompt_ui_editor_change_target"))
        // Missing targets are preserved verbatim and must be re-picked.
        assertTrue(editor.contains("existing.groupId"))
        assertTrue(editor.contains("it.providerInstanceId == existing.providerInstanceId &&"))
        assertTrue(editor.contains("canSave = hasTarget && content.isNotBlank()"))
    }

    @Test
    fun `the runtime resolves rules per request and snapshots them per turn`() {
        // Grep guard for the wiring that cannot be constructed in a JVM test:
        // turn-level snapshot + per-request selection. If ChatViewModel drops
        // either half, this fails instead of the UI silently doing nothing.
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("SoulPromptRuleStore.cachedOrLoad(context)"))
        assertTrue(vm.contains("applicationPromptForSelection("))
        assertTrue(vm.contains("THE per-request application point"))
        assertTrue(vm.contains("entry?.baseModel?.id"))
        assertTrue(vm.contains("SystemPromptBuilder.applicationPrompt("))
    }
}
