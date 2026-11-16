package com.openminis.app.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-model-group-avatar] Contract for the persisted `avatar` string: exactly
 * two manual forms, both self-contained, with everything else reading as
 * automatic.
 */
class ModelGroupAvatarValueTest {

    private val png = "data:image/png;base64," +
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    @Test
    fun `empty means automatic`() {
        assertNull(ModelGroupAvatarValue.parse(null))
        assertNull(ModelGroupAvatarValue.parse(""))
        assertNull(ModelGroupAvatarValue.parse("   "))
        assertFalse(ModelGroupAvatarValue.isUsable(null))
    }

    @Test
    fun `a builtin id is lower cased and validated`() {
        assertEquals(
            ModelGroupAvatarValue.Manual.Builtin("openai"),
            ModelGroupAvatarValue.parse("builtin:OpenAI"),
        )
        assertEquals("builtin:openai", ModelGroupAvatarValue.builtinValue(" OpenAI "))
        assertEquals(
            ModelGroupAvatarValue.Manual.Builtin("qwen-2.5_max"),
            ModelGroupAvatarValue.parse("builtin:qwen-2.5_max"),
        )
    }

    @Test
    fun `unsafe or malformed builtin ids are automatic`() {
        val bad = listOf(
            "builtin:",
            "builtin:../etc",
            "builtin:/openai",
            "builtin:open ai",
            "builtin:-openai",
            "builtin:openai?x=1",
            "builtin:" + "a".repeat(65),
            "builtins:openai",
        )
        bad.forEach { assertNull(it, ModelGroupAvatarValue.parse(it)) }
    }

    @Test
    fun `a bounded PNG data URI is the only accepted picture form`() {
        assertEquals(ModelGroupAvatarValue.Manual.Image(png), ModelGroupAvatarValue.parse(png))
        assertTrue(ModelGroupAvatarValue.isUsable(png))
        // A non-PNG payload behind the PNG prefix.
        assertNull(ModelGroupAvatarValue.parse("data:image/png;base64,bm90YXBuZw=="))
        // The wrong MIME prefix is not a form this build stores.
        assertNull(ModelGroupAvatarValue.parse("data:image/jpeg;base64,/9j/4AAQ"))
        // Non-base64 characters.
        assertNull(ModelGroupAvatarValue.parse("data:image/png;base64,!!!!"))
        // Over the stored cap.
        assertNull(ModelGroupAvatarValue.parse(ModelGroupAvatarValue.DATA_URI_PREFIX + "A".repeat(70_000)))
    }

    @Test
    fun `pngBytes returns the decoded picture and rejects junk`() {
        val bytes = ModelGroupAvatarValue.pngBytes(png)
        assertTrue(bytes != null && bytes.isNotEmpty())
        assertTrue(bytes!!.size >= 8)
        assertNull(ModelGroupAvatarValue.pngBytes("data:image/jpeg;base64,/9j/4AAQ"))
        assertNull(ModelGroupAvatarValue.pngBytes("data:image/png;base64,bm90YXBuZw=="))
    }

    @Test
    fun `catalog asset files must stay relative and image-only`() {
        assertEquals("openai.png", ModelGroupAvatarValue.sanitizeAssetFile("openai.png"))
        assertEquals("brand/openai.png", ModelGroupAvatarValue.sanitizeAssetFile("brand/openai.png"))
        assertEquals(
            "file:///android_asset/model-icons/openai.png",
            ModelGroupAvatarValue.assetModel("openai.png"),
        )
        val bad = listOf(
            "",
            "/openai.png",
            "../openai.png",
            "brand/../../openai.png",
            "brand//openai.png",
            "openai.svg",
            "openai.exe",
            "open ai.png",
            ".hidden.png",
            "C:\\icons\\openai.png",
            "openai.png?raw=1",
            "openai.png#frag",
            "https://example.com/openai.png",
        )
        bad.forEach {
            assertNull("must reject: $it", ModelGroupAvatarValue.sanitizeAssetFile(it))
            assertNull(ModelGroupAvatarValue.assetModel(it))
        }
    }

    @Test
    fun `a value from a newer build is automatic, not an error`() {
        assertNull(ModelGroupAvatarValue.parse("svg:openai"))
        assertNull(ModelGroupAvatarValue.parse("file:/data/user/0/x.png"))
    }
}