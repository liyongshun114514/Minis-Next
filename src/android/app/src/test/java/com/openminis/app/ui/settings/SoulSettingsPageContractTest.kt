package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-soul-system-prompt-only] Contract for the reduced Soul page,
 * extended for the restructured prompt area.
 *
 * The page is the system-prompt setting and nothing else: an editable avatar,
 * a name, the global prompt, and the scoped custom rules. The layout moved
 * from two tabs to two stacked sections; the storage, matching and save paths
 * are unchanged and are pinned below.
 */
class SoulSettingsPageContractTest {

    private val src = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")
    private val section = ProductionSources.read("ui/settings/SoulPromptRulesSection.kt")
    private val editor = ProductionSources.read("ui/settings/SoulPromptRuleEditor.kt")
    private val order = ProductionSources.read("ui/settings/SoulPromptRuleUi.kt")

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

    // ── Global prompt and custom rules are separate sections ─────────────

    @Test
    fun `the prompt area is two stacked sections instead of hidden tabs`() {
        // A tab row hid one half of the feature and put the global Save in the
        // app bar, where it looked like it saved the rules too.
        assertFalse("no tabs", src.contains("TabRow("))
        assertFalse("no page selector state", src.contains("promptTab"))
        // Global first, custom rules directly after it.
        val global = src.indexOf("R.string.soul_section_personality")
        val custom = src.indexOf("SoulCustomRulesSection(")
        assertTrue("global section before custom rules", global >= 0 && custom > global)
        assertTrue(src.contains("prompt_ui_global_badge"))
        assertTrue(src.contains("prompt_ui_global_footer"))
    }

    @Test
    fun `the global prompt keeps its own save, scoped to the prompt`() {
        // Save moved from the app bar into the global card: it commits
        // SOUL.md only, while rules persist immediately and have no button.
        assertTrue(src.contains("MinisButton(\n                        onClick = save,"))
        assertTrue(src.contains("prompt_ui_global_unsaved"))
        assertTrue(src.contains("prompt_ui_global_saved"))
        assertTrue("rules persist on change", src.contains("persistRules("))
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
