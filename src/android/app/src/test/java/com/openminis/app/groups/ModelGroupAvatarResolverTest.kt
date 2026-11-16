package com.openminis.app.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-model-group-avatar] The resolution rules every group presentation point
 * shares: manual override → automatic name match → one fallback.
 *
 * Pure JVM: the resolver has no Android dependency, so these are the real
 * rules, not a port.
 */
class ModelGroupAvatarResolverTest {

    /** 1x1 transparent PNG; only the signature matters to the value parser. */
    private val png = "data:image/png;base64," +
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    private fun catalog() = ModelIconCatalog(
        listOf(
            ModelIconCatalogEntry("openai", "OpenAI", listOf("gpt", "chatgpt"), "openai.png"),
            ModelIconCatalogEntry("claude", "Claude", listOf("sonnet", "opus", "haiku"), "claude.png"),
            ModelIconCatalogEntry("deepseek", "DeepSeek", listOf("deepseek-r1"), "deepseek.png"),
            ModelIconCatalogEntry("gemini", "Gemini", listOf("google-gemini"), "gemini.png"),
            ModelIconCatalogEntry("google", "Google", listOf("google-ai", "gemma"), "google.png"),
            ModelIconCatalogEntry("qwen", "Qwen", listOf("通义千问", "千问"), "qwen.png"),
            ModelIconCatalogEntry("tiny", "Tiny", listOf("ai"), "tiny.png"),
        ),
    )

    private fun idOf(resolved: ResolvedModelGroupAvatar): String? =
        (resolved.spec as? ModelGroupAvatarSpec.Builtin)?.entry?.id

    @Test
    fun `an exact brand name matches automatically`() {
        val r = ModelGroupAvatarResolver.resolve("OpenAI", null, catalog())
        assertEquals("openai", idOf(r))
        assertEquals(ModelGroupAvatarSource.AUTO, r.source)
    }

    @Test
    fun `case, punctuation and separators normalize`() {
        val c = catalog()
        assertEquals("openai", idOf(ModelGroupAvatarResolver.resolve("  openai!! ", null, c)))
        assertEquals("openai", idOf(ModelGroupAvatarResolver.resolve("Open AI", null, c)))
        assertEquals("openai", idOf(ModelGroupAvatarResolver.resolve("chat-GPT", null, c)))
    }

    @Test
    fun `a brand inside a longer group name matches on a whole token`() {
        val r = ModelGroupAvatarResolver.resolve("GPT-4o 主力", null, catalog())
        assertEquals("openai", idOf(r))
        assertEquals("gpt", r.matchedTerm)
    }

    @Test
    fun `renaming an automatic icon follows the new name`() {
        val c = catalog()
        val before = ModelGroupAvatarResolver.resolve("Claude", null, c)
        val after = ModelGroupAvatarResolver.resolve("DeepSeek", "   ", c)
        assertEquals("claude", idOf(before))
        // Nothing was persisted, so the resolution is recomputed from the
        // CURRENT name: the same group "renamed" now matches DeepSeek.
        assertEquals("deepseek", idOf(after))
        assertNotEquals(idOf(before), idOf(after))
    }

    @Test
    fun `a manual builtin survives a rename that would auto-match something else`() {
        val stored = ModelGroupAvatarValue.builtinValue("claude")
        val r = ModelGroupAvatarResolver.resolve("GPT 主力", stored, catalog())
        assertEquals("claude", idOf(r))
        assertEquals(ModelGroupAvatarSource.MANUAL, r.source)
    }

    @Test
    fun `a manual picture beats an automatic match`() {
        val r = ModelGroupAvatarResolver.resolve("Claude", png, catalog())
        assertEquals(ModelGroupAvatarSpec.Image(png), r.spec)
        assertEquals(ModelGroupAvatarSource.MANUAL, r.source)
    }

    @Test
    fun `an unknown builtin id degrades to automatic without losing the stored value`() {
        val stored = ModelGroupAvatarValue.builtinValue("retired-icon")
        val r = ModelGroupAvatarResolver.resolve("Claude", stored, catalog())
        assertEquals("claude", idOf(r))
        assertEquals(ModelGroupAvatarSource.AUTO, r.source)
        assertEquals(ModelGroupAvatarValue.Manual.Builtin("retired-icon"), r.manual)
    }

    @Test
    fun `a malformed picture is ignored and automatic matching still applies`() {
        val notPng = "data:image/png;base64,bm90YXBuZw=="
        val r = ModelGroupAvatarResolver.resolve("Claude", notPng, catalog())
        assertEquals("claude", idOf(r))
        assertEquals(ModelGroupAvatarSource.AUTO, r.source)
    }

    @Test
    fun `an unsafe builtin id is not a manual value`() {
        val r = ModelGroupAvatarResolver.resolve("Claude", "builtin:../../etc", catalog())
        assertEquals("claude", idOf(r))
        assertEquals(ModelGroupAvatarSource.AUTO, r.source)
    }

    @Test
    fun `no name match yields the one fallback`() {
        val r = ModelGroupAvatarResolver.resolve("主力模型", null, catalog())
        assertSame(ModelGroupAvatarSpec.Fallback, r.spec)
        assertEquals(ModelGroupAvatarSource.FALLBACK, r.source)
        assertNull(r.matchedTerm)
    }

    @Test
    fun `a two-letter alias never matches inside an unrelated word`() {
        val r = ModelGroupAvatarResolver.resolve("Main Models", null, catalog())
        assertSame(ModelGroupAvatarSpec.Fallback, r.spec)
        // The same alias is still usable when the name IS the alias.
        assertEquals("tiny", idOf(ModelGroupAvatarResolver.resolve("AI", null, catalog())))
    }

    @Test
    fun `the longest matched brand term wins`() {
        // gemini (6) beats gpt (3).
        assertEquals("gemini", idOf(ModelGroupAvatarResolver.resolve("Gemini GPT", null, catalog())))
    }

    @Test
    fun `a same-length tie is settled by catalog order`() {
        // "Google Gemini": google and gemini are both 6 characters; the
        // catalog lists gemini first, so that is the brand the user meant.
        assertEquals("gemini", idOf(ModelGroupAvatarResolver.resolve("Google Gemini", null, catalog())))
    }

    @Test
    fun `Chinese group names match Chinese aliases, spaced or not`() {
        val c = catalog()
        assertEquals("qwen", idOf(ModelGroupAvatarResolver.resolve("通义千问", null, c)))
        assertEquals("qwen", idOf(ModelGroupAvatarResolver.resolve("通义千问主力", null, c)))
        assertEquals("qwen", idOf(ModelGroupAvatarResolver.resolve("千问 高并发", null, c)))
    }

    @Test
    fun `autoMatch exposes the bare automatic result for the picker`() {
        val match = ModelGroupAvatarResolver.autoMatch("Claude 日常", catalog())
        assertEquals("claude", match?.id)
        assertNull(ModelGroupAvatarResolver.autoMatch("主力模型", catalog()))
    }

    @Test
    fun `an empty catalog never throws and always falls back`() {
        val r = ModelGroupAvatarResolver.resolve("OpenAI", null, ModelIconCatalog.EMPTY)
        assertSame(ModelGroupAvatarSpec.Fallback, r.spec)
        assertTrue(ModelGroupAvatarValue.isUsable(png))
    }
}