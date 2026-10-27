package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [K-global-prompt-only] Contract for Settings → System Prompt: avatar, name,
 * one global body, Save. Nothing else is configurable, and nothing on the page
 * can block or truncate the body.
 */
class SoulSettingsPageContractTest {

    private val src = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")
    private val avatar = ProductionSources.read("ui/components/SoulAvatar.kt")

    @Test
    fun `the page edits exactly the original global properties`() {
        assertTrue(src.contains("soul_field_name"))
        assertTrue(src.contains("SoulStore.load(context)"))
        assertTrue(src.contains("SoulStore.save(context, currentFile)"))
        assertTrue(src.contains("SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)"))
        assertTrue(src.contains("DocumentEditorField("))
        assertTrue(src.contains("name = name.ifBlank { SoulMetadata.DEFAULT.name }"))
        // style/lang/emoji round-trip without a field of their own.
        assertTrue(src.contains("preservedEmoji = parsed.metadata.emoji"))
        assertTrue(src.contains("style = parsed.metadata.style"))
        assertTrue(src.contains("lang = parsed.metadata.lang"))
    }

    @Test
    fun `no mode, rule, or length control is reachable from the page`() {
        for (token in listOf(
            "SoulPromptMode", "SoulPromptModeSwitch", "SoulPromptRule", "SoulPromptRuleStore",
            "SoulCustomRulesSection", "SoulPromptRuleEditor", "SoulBodyLimit", "isOverLimit",
            "limitSettings", "bodyLimitCheck", "SoulLimitUi", "SegmentedButton",
            "kairos_prompt_mode_", "soul_over_limit", "soul_counter",
        )) {
            assertFalse("$token must not appear on the page", src.contains(token))
        }
    }

    @Test
    fun `the mode and rule UI helpers are deleted, not hidden`() {
        val root = ProductionSources.mainRoot() ?: error("production source root not found")
        for (name in listOf(
            "ui/settings/SoulPromptRuleUi.kt",
            "ui/settings/SoulPromptRuleEditor.kt",
            "ui/settings/SoulPromptRulesSection.kt",
            "ui/settings/SoulLimitUi.kt",
        )) {
            assertFalse("$name must be gone", File(root, name).exists())
        }
    }

    @Test
    fun `save is gated only by dirty and in-flight state`() {
        assertTrue(src.contains("enabled = loaded && isDirty && !saving,"))
        assertFalse("no length gate on Save", src.contains("!bodyLimitCheck.isOverLimit"))
        assertTrue(src.contains("val isDirty = loaded && baseline != null && currentFile != baseline"))
        assertTrue(src.contains("if (!saving)"))
    }

    @Test
    fun `the body is saved whole with no counter or rejection banner`() {
        assertTrue(src.contains("onValueChange = { body = it }"))
        assertFalse(src.contains("SoulStore.isOverLimit"))
        assertFalse(src.contains("soul_over_limit_chinese"))
        assertFalse(src.contains("soul_over_limit_english"))
        assertFalse(src.contains("body.length"))
    }

    @Test
    fun `the avatar uses the one circular renderer and the sampled importer`() {
        assertTrue(src.contains("SoulAvatarGlyph("))
        assertTrue(avatar.contains("fun SoulAvatarGlyph("))
        assertTrue(avatar.contains("ContentScale.Crop"))
        assertTrue(avatar.contains("CircleShape"))
        assertTrue("popup image import samples while decoding", src.contains("SoulIcon.importFrom"))
        assertFalse("no full-resolution decode", src.contains("BitmapFactory.decodeStream"))
        assertFalse("no TOO_LARGE wording remains", src.contains("iconTooLargeMsg"))
        assertFalse(src.contains("soul_icon_error_too_large"))
        // The glyph fills its frame: 44dp in a 44dp circular button.
        assertTrue(src.contains("SoulIconGlyph(icon = icon, sizeDp = 44.dp, emojiSp = 26.sp)"))
    }

    @Test
    fun `unsaved edits still get the discard guard and save errors are shown`() {
        assertTrue(src.contains("BackHandler(enabled = isDirty)"))
        assertTrue(src.contains("soul_discard_confirm_title"))
        assertTrue(src.contains("saveError?.let"))
        assertTrue(src.contains("soul_save_error_title"))
    }
}
