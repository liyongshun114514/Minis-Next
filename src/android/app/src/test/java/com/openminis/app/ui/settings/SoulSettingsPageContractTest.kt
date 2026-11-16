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
        // [T-multi-soul] Reads and writes go through the profile store; the
        // legacy default is still loaded/saved through SoulStore by that
        // store, not directly from this screen.
        assertTrue(src.contains("SoulProfiles.load(context, targetId)"))
        assertTrue(src.contains("SoulProfiles.save(context, editingId, snapshot)"))
        assertTrue(src.contains("SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)"))
        assertTrue(src.contains("DocumentEditorField("))
        assertTrue(src.contains("name = name.ifBlank { SoulMetadata.DEFAULT.name }"))
        // style/lang/emoji round-trip without a field of their own.
        assertTrue(src.contains("preservedEmoji = parsed.metadata.emoji"))
        assertTrue(src.contains("style = parsed.metadata.style"))
        assertTrue(src.contains("lang = parsed.metadata.lang"))
    }

    /**
     * [T-multi-soul] The page is a library: it can create, edit, delete and
     * pick the SOUL for one chat. Those four verbs are what the feature is,
     * so they are pinned at the source level here; the behaviour itself is
     * covered by `SoulProfilesStorageTest`.
     */
    @Test
    fun `the page can create edit delete and select a profile`() {
        assertTrue(src.contains("SoulProfiles.create("))
        assertTrue(src.contains("SoulProfiles.delete(context, profile.id)"))
        assertTrue(src.contains("SoulProfiles.select(context, session, id)"))
        assertTrue("the default profile cannot be deleted", src.contains("profile.id != SoulProfiles.DEFAULT_ID"))
        // One edited profile at a time, reloaded when the id changes.
        assertTrue(src.contains("var editingId by remember"))
        assertTrue(src.contains("LaunchedEffect(editingId, reloadKey)"))
        // Unsaved edits are still guarded before switching profile or leaving.
        assertTrue(src.contains("PendingSoulAction.SwitchProfile"))
    }

    @Test
    fun `no new shared string resource is required by the page`() {
        // The new copy lives in this file's private SoulUiText object; the
        // screen only reads keys that already existed.
        for (line in src.lines().filter { it.contains("stringResource(R.string.") }) {
            assertFalse("new resource key: $line", line.contains("R.string.chat_menu_soul"))
            assertFalse("new resource key: $line", line.contains("R.string.soul_profile"))
        }
        assertTrue(src.contains("private object SoulUiText"))
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
    fun `load readiness and save error state remain declared before use`() {
        val loadDeclaration = "var loaded by remember { mutableStateOf(false) }"
        val errorDeclaration = "var saveError by remember { mutableStateOf<String?>(null) }"
        assertTrue("load state must exist", src.contains(loadDeclaration))
        assertTrue("save errors must have remembered state", src.contains(errorDeclaration))
        assertTrue(src.indexOf(loadDeclaration) < src.indexOf("loaded = true"))
        assertTrue(src.indexOf(errorDeclaration) < src.indexOf("saveError = t.message"))
        assertTrue("failed writes must leave the draft on screen", src.contains("saveError = t.message ?: \"save failed\""))
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
