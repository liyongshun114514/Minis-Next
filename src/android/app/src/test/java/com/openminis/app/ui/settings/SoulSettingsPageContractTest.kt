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
}
