package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-soul-system-prompt-only] Contract for the reduced Soul page.
 *
 * The page is the system-prompt setting and nothing else: an editable avatar,
 * a name, and one prompt field. This replaces the former
 * `SoulLangButtonWrapTest`, which asserted the language row that has been
 * removed.
 */
class SoulSettingsPageContractTest {

    private val src = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")

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

    // ── [T-soul-scoped-prompt] Global / Custom pages ─────────────────────

    @Test
    fun `the prompt area has global and custom tabs`() {
        assertTrue(src.contains("soul_tab_global"))
        assertTrue(src.contains("soul_tab_custom"))
        assertTrue(src.contains("TabRow(selectedTabIndex = promptTab)"))
        // Global keeps the original SOUL.md editor and its Save path.
        assertTrue(src.contains("if (promptTab == 0)"))
        assertEquals("one global prompt editor", 1, Regex("""DocumentEditorField\(""").findAll(src).count())
    }

    @Test
    fun `custom rules can be enabled targeted edited added and deleted`() {
        assertTrue(src.contains("SoulPromptRuleStore.load(context)"))
        assertTrue(src.contains("SoulPromptRuleStore.save(context, next)"))
        assertTrue(src.contains("SettingsSwitch("))
        assertTrue(src.contains("persistRules("))
        assertTrue(src.contains("SoulPromptRuleEditorDialog("))
        assertTrue(src.contains("SoulPromptRuleTarget.GROUP"))
        assertTrue(src.contains("SoulPromptRuleTarget.MODEL"))
        assertTrue(src.contains("rules.filterNot { it.id == pending.id }"))
        assertTrue(src.contains("soul_custom_rules_add"))
    }

    @Test
    fun `a deleted target is shown as missing and never silently global`() {
        assertTrue(src.contains("soul_custom_rule_target_missing"))
        assertTrue(src.contains("soul_custom_rules_intro"))
        // The rule editor offers exactly Group and Model — no "all" scope.
        val editor = ProductionSources.read("ui/settings/SoulPromptRuleEditor.kt")
        assertTrue(editor.contains("SoulPromptRuleTarget.GROUP"))
        assertTrue(editor.contains("SoulPromptRuleTarget.MODEL"))
        assertFalse("no global target option", editor.contains("SoulPromptRuleTarget.GLOBAL"))
        assertFalse(editor.contains("AllModels"))
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
