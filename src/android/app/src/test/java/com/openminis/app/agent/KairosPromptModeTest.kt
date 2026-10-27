package com.openminis.app.agent

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [K-mode-isolation] Persistence-level tests for [SoulPromptMode].
 *
 * Everything here drives the real store against a real temporary file (the
 * Context overloads are one-line delegations to these `File` entry points), so
 * "the mode survives a rules write", "a damaged file is not overwritten" and
 * "a failed write keeps the old bytes" are checked against actual filesystem
 * behaviour and not against a re-implementation.
 */
class KairosPromptModeTest {

    private lateinit var dir: File
    private lateinit var file: File

    private val ruleA = SoulPromptRule(
        id = "rule-a",
        name = "A",
        enabled = true,
        target = SoulPromptRuleTarget.MODEL,
        providerInstanceId = "provider-1",
        modelId = "model-x",
        content = "PROMPT-A",
    )

    private val ruleB = SoulPromptRule(
        id = "rule-b",
        name = "B",
        enabled = true,
        target = SoulPromptRuleTarget.MODEL,
        providerInstanceId = "provider-1",
        modelId = "model-y",
        content = "PROMPT-B",
    )

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("kairos-prompt-mode").toFile()
        file = File(dir, "SOUL_PROMPT_RULES.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun writeRaw(text: String) {
        file.writeText(text)
    }

    // ── Serialization ───────────────────────────────────────────────────

    @Test
    fun `mode is part of the same document as the rules`() {
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(ruleA))
        val text = file.readText()
        assertTrue("mode must be in the envelope", text.contains("\"mode\": \"CUSTOM\""))
        assertTrue(text.contains("PROMPT-A"))
        val document = SoulPromptRuleStore.decodeDocument(text)
        assertEquals(SoulPromptMode.CUSTOM, document.mode)
        assertEquals(listOf("rule-a"), document.rules.map { it.id })
    }

    @Test
    fun `both enum constants round-trip`() {
        for (mode in SoulPromptMode.entries) {
            val text = SoulPromptRuleStore.encode(mode, listOf(ruleA))
            assertEquals(mode, SoulPromptRuleStore.decodeDocument(text).mode)
        }
    }

    @Test
    fun `legacy document without mode decodes as global`() {
        // Version 1 file written before the mode existed.
        writeRaw("""{"version":1,"rules":[{"id":"old","target":"MODEL","providerInstanceId":"p","modelId":"m","content":"OLD","enabled":true}]}""")
        val document = SoulPromptRuleStore.decodeDocument(file.readText())
        assertEquals(SoulPromptMode.GLOBAL, document.mode)
        assertEquals(1, document.rules.size)
        assertEquals("OLD", document.rules[0].content)
        assertEquals(SoulPromptMode.GLOBAL, SoulPromptRuleStore.loadMode(file))
        assertEquals(listOf("old"), SoulPromptRuleStore.load(file).map { it.id })
    }

    @Test
    fun `legacy array-only file decodes as global with its rules`() {
        writeRaw("""[{"id":"old","target":"MODEL","providerInstanceId":"p","modelId":"m","content":"OLD"}]""")
        assertEquals(SoulPromptMode.GLOBAL, SoulPromptRuleStore.loadMode(file))
        assertEquals(1, SoulPromptRuleStore.load(file).size)
    }

    @Test
    fun `unknown mode value fails closed instead of throwing`() {
        writeRaw("""{"version":2,"mode":"SOMETHING_NEW","rules":[]}""")
        // A hand-edited/future value must not crash the prompt path, and must
        // not silently re-enable the global body either.
        assertEquals(SoulPromptMode.CUSTOM, SoulPromptRuleStore.loadMode(file))
        assertTrue(SoulPromptRuleStore.load(file).isEmpty())
    }

    // ── save() preserves the mode ───────────────────────────────────────

    @Test
    fun `save rules preserves the stored mode`() {
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(ruleA))
        // Settings edits the list through the one-argument save.
        SoulPromptRuleStore.save(file, listOf(ruleB))
        val document = SoulPromptRuleStore.decodeDocument(file.readText())
        assertEquals("editing a rule must not flip the mode", SoulPromptMode.CUSTOM, document.mode)
        assertEquals(listOf("rule-b"), document.rules.map { it.id })
        assertEquals(SoulPromptMode.CUSTOM, SoulPromptRuleStore.loadMode(file))
    }

    @Test
    fun `save rules on a missing file keeps the default global mode`() {
        assertFalse(file.exists())
        SoulPromptRuleStore.save(file, listOf(ruleA))
        assertEquals(SoulPromptMode.GLOBAL, SoulPromptRuleStore.loadMode(file))
        assertEquals(listOf("rule-a"), SoulPromptRuleStore.load(file).map { it.id })
    }

    @Test
    fun `saving an empty list still persists the custom mode`() {
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(ruleA))
        SoulPromptRuleStore.save(file, emptyList())
        assertEquals(SoulPromptMode.CUSTOM, SoulPromptRuleStore.loadMode(file))
        assertTrue(SoulPromptRuleStore.load(file).isEmpty())
    }

    @Test
    fun `setMode preserves the rules byte for byte`() {
        SoulPromptRuleStore.save(file, SoulPromptMode.GLOBAL, listOf(ruleA, ruleB))
        val before = SoulPromptRuleStore.load(file)
        SoulPromptRuleStore.setMode(file, SoulPromptMode.CUSTOM)
        assertEquals(SoulPromptMode.CUSTOM, SoulPromptRuleStore.loadMode(file))
        assertEquals(before.map { it.id }, SoulPromptRuleStore.load(file).map { it.id })
        assertEquals(before.map { it.content }, SoulPromptRuleStore.load(file).map { it.content })
    }

    @Test
    fun `switching modes is reversible and keeps legacy group rows`() {
        val legacy = SoulPromptRule(
            id = "legacy-group",
            target = SoulPromptRuleTarget.GROUP,
            groupId = "group-a",
            content = "LEGACY",
        )
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(ruleA, legacy))
        SoulPromptRuleStore.setMode(file, SoulPromptMode.GLOBAL)
        SoulPromptRuleStore.setMode(file, SoulPromptMode.CUSTOM)
        val document = SoulPromptRuleStore.decodeDocument(file.readText())
        assertEquals(SoulPromptMode.CUSTOM, document.mode)
        assertEquals(listOf("rule-a", "legacy-group"), document.rules.map { it.id })
        // The legacy row is still inert at runtime.
        assertNull(
            SoulPromptRuleResolver.matchTier(
                document.rules[1],
                SoulPromptSelection(providerInstanceId = "provider-1", modelId = "model-x"),
            ),
        )
    }

    // ── Damaged file ────────────────────────────────────────────────────

    @Test
    fun `a corrupt file fails closed and is left untouched`() {
        val broken = "{\"version\":2,\"mode\":\"CUSTOM\",\"rules\":[{\"id\":"
        writeRaw(broken)
        val before = file.readBytes()

        // Degrading to GLOBAL here would inject the SOUL.md body into a turn
        // the owner wanted isolated, so a damaged file means "no prompt".
        assertEquals(SoulPromptMode.CUSTOM, SoulPromptRuleStore.loadMode(file))
        assertTrue(SoulPromptRuleStore.load(file).isEmpty())
        assertTrue(SoulPromptRuleStore.decodeDocument(file.readText()).rules.isEmpty())

        assertTrue(
            "merely reading must never rewrite or truncate the damaged file",
            before.contentEquals(file.readBytes()),
        )
        assertEquals(broken, file.readText())
        assertFalse(File(dir, "SOUL_PROMPT_RULES.json.tmp").exists())
        assertFalse(File(dir, "SOUL_PROMPT_RULES.json.bak").exists())
    }

    @Test
    fun `an empty file reads as defaults and stays empty`() {
        writeRaw("")
        assertEquals(SoulPromptMode.GLOBAL, SoulPromptRuleStore.loadMode(file))
        assertTrue(SoulPromptRuleStore.load(file).isEmpty())
        assertEquals("", file.readText())
    }

    @Test
    fun `a missing file reads as defaults and is not created`() {
        assertEquals(SoulPromptMode.GLOBAL, SoulPromptRuleStore.loadMode(file))
        assertTrue(SoulPromptRuleStore.load(file).isEmpty())
        assertFalse("reads must not create the file", file.exists())
    }

    // ── Atomic write ────────────────────────────────────────────────────

    @Test
    fun `a failed write keeps the previous document intact`() {
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(ruleA))
        val before = file.readBytes()

        // Block the sibling temp path with a non-empty directory: the write
        // fails before the rename can touch the real file.
        val tmp = File(dir, "SOUL_PROMPT_RULES.json.tmp")
        tmp.mkdirs()
        File(tmp, "block").writeText("x")
        try {
            SoulPromptRuleStore.setMode(file, SoulPromptMode.GLOBAL)
            fail("expected the write to fail")
        } catch (_: Throwable) {
            // expected
        }

        assertTrue("old bytes must survive a failed write", before.contentEquals(file.readBytes()))
        assertEquals(SoulPromptMode.CUSTOM, SoulPromptRuleStore.loadMode(file))
        assertEquals(listOf("rule-a"), SoulPromptRuleStore.load(file).map { it.id })
        assertFalse("no stray temp file may be left", tmp.isFile)
    }

    @Test
    fun `a failed write keeps the cached snapshot`() {
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(ruleA))
        assertEquals(SoulPromptMode.CUSTOM, SoulPromptRuleStore.cachedModeOrLoad(file))
        assertEquals(1, SoulPromptRuleStore.cachedOrLoad(file).size)

        // Break the write without touching the target: the cache must keep
        // serving the last good document, not the attempted (empty) one.
        val tmp = File(dir, "SOUL_PROMPT_RULES.json.tmp")
        tmp.mkdirs()
        File(tmp, "block").writeText("x")
        try {
            SoulPromptRuleStore.save(file, emptyList())
            fail("expected the write to fail")
        } catch (_: Throwable) {
            // expected
        }
        assertEquals(SoulPromptMode.CUSTOM, SoulPromptRuleStore.cachedModeOrLoad(file))
        assertEquals(listOf("rule-a"), SoulPromptRuleStore.cachedOrLoad(file).map { it.id })
        assertEquals(SoulPromptMode.CUSTOM, SoulPromptRuleStore.loadMode(file))
        assertEquals(1, SoulPromptRuleStore.load(file).size)
    }

    @Test
    fun `concurrent mode and rules writes never lose a half`() {
        // Two Settings surfaces (mode switch + rule editor) writing at once:
        // the store serializes them, so the file always ends up with a
        // decodable document holding BOTH the last mode and the last rules.
        SoulPromptRuleStore.save(file, SoulPromptMode.GLOBAL, listOf(ruleA))
        val rules = listOf(ruleA, ruleB)
        val threads = (0 until 4).map { index ->
            Thread {
                repeat(40) { iteration ->
                    if (index % 2 == 0) {
                        SoulPromptRuleStore.setMode(
                            file,
                            if (iteration % 2 == 0) SoulPromptMode.CUSTOM else SoulPromptMode.GLOBAL,
                        )
                    } else {
                        SoulPromptRuleStore.save(file, rules)
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val document = SoulPromptRuleStore.decodeDocument(file.readText())
        assertEquals(listOf("rule-a", "rule-b"), document.rules.map { it.id })
        assertTrue(document.mode == SoulPromptMode.CUSTOM || document.mode == SoulPromptMode.GLOBAL)
        assertFalse(File(dir, "SOUL_PROMPT_RULES.json.tmp").exists())
    }

    @Test
    fun `a successful write leaves no temp or backup behind`() {
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(ruleA))
        SoulPromptRuleStore.setMode(file, SoulPromptMode.GLOBAL)
        assertFalse(File(dir, "SOUL_PROMPT_RULES.json.tmp").exists())
        assertFalse(File(dir, "SOUL_PROMPT_RULES.json.bak").exists())
        assertEquals(1, dir.listFiles()!!.count { it.name == "SOUL_PROMPT_RULES.json" })
    }

    // ── A corrupt document is never overwritten by a save ──────────────

    private val corrupt = """{"version":2,"mode":"CUSTOM","rules":[{"id":"a","content":"USER TEXT""""

    private fun assertRefused(before: ByteArray, block: () -> Unit) {
        try {
            block()
            fail("a save over a corrupt document must be refused")
        } catch (expected: java.io.IOException) {
            // expected
        }
        assertTrue("the corrupt bytes are the user's only copy", before.contentEquals(file.readBytes()))
        assertFalse("no temp file may be left", File(dir, "SOUL_PROMPT_RULES.json.tmp").exists())
        assertFalse("no backup file may be created", File(dir, "SOUL_PROMPT_RULES.json.bak").exists())
    }

    @Test
    fun `saving rules refuses to overwrite a corrupt document`() {
        writeRaw(corrupt)
        val before = file.readBytes()
        assertRefused(before) { SoulPromptRuleStore.save(file, listOf(ruleA)) }
        // The tolerant read path still works, and it fails closed.
        assertTrue(SoulPromptRuleStore.load(file).isEmpty())
        assertEquals(SoulPromptMode.CUSTOM, SoulPromptRuleStore.loadMode(file))
    }

    @Test
    fun `switching the mode refuses to overwrite a corrupt document`() {
        writeRaw(corrupt)
        val before = file.readBytes()
        assertRefused(before) { SoulPromptRuleStore.setMode(file, SoulPromptMode.GLOBAL) }
    }

    @Test
    fun `a two-part save refuses to overwrite a corrupt document`() {
        writeRaw(corrupt)
        val before = file.readBytes()
        assertRefused(before) { SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(ruleA)) }
    }

    @Test
    fun `an unknown mode value is corrupt for the write path`() {
        // A hand-edited/future enum: readable (fail closed to CUSTOM) but not
        // something this build may overwrite — it would silently downgrade.
        writeRaw("""{"version":2,"mode":"SOMETHING_NEW","rules":[]}""")
        val before = file.readBytes()
        assertRefused(before) { SoulPromptRuleStore.save(file, listOf(ruleA)) }
        assertEquals(SoulPromptMode.CUSTOM, SoulPromptRuleStore.loadMode(file))
    }

    @Test
    fun `a zero-byte file may be replaced`() {
        writeRaw("")
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(ruleA))
        assertEquals(SoulPromptMode.CUSTOM, SoulPromptRuleStore.loadMode(file))
        assertEquals(listOf("rule-a"), SoulPromptRuleStore.load(file).map { it.id })
    }

    @Test
    fun `an existing backup file is never used or deleted`() {
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(ruleA))
        val backup = File(dir, "SOUL_PROMPT_RULES.json.bak")
        backup.writeText("USER-BACKUP-SENTINEL")
        SoulPromptRuleStore.setMode(file, SoulPromptMode.GLOBAL)
        assertEquals(
            "a save must not delete a backup it did not create",
            "USER-BACKUP-SENTINEL",
            backup.readText(),
        )
        assertEquals(SoulPromptMode.GLOBAL, SoulPromptRuleStore.loadMode(file))
    }

    @Test
    fun `writes create the memory directory`() {
        val nested = File(File(dir, "minis-global/memory"), "SOUL_PROMPT_RULES.json")
        SoulPromptRuleStore.save(nested, SoulPromptMode.CUSTOM, listOf(ruleA))
        assertTrue(nested.isFile)
        assertEquals(SoulPromptMode.CUSTOM, SoulPromptRuleStore.loadMode(nested))
    }

    @Test
    fun `a missing id is filled in before it reaches the file`() {
        val anonymous = ruleA.copy(id = "")
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(anonymous))
        val stored = SoulPromptRuleStore.load(file).single()
        assertTrue(stored.id.isNotBlank())
        assertEquals("PROMPT-A", stored.content)
    }

    // ── Missing vs damaged: the isolation difference ───────────────────

    @Test
    fun `a corrupt document injects no global prompt`() {
        writeRaw(corrupt)
        val soul = SoulFile(SoulMetadata.DEFAULT, "GLOBAL-BODY")
        val document = SoulPromptRuleStore.cachedDocumentOrLoad(file)
        assertEquals(SoulPromptMode.CUSTOM, document.mode)
        assertTrue(document.rules.isEmpty())
        assertNull(
            "a damaged file must not degrade into the global body",
            SystemPromptBuilder.applicationPrompt(
                soul,
                document.mode,
                document.rules,
                SoulPromptSelection(providerInstanceId = "provider-1", modelId = "model-x"),
            ),
        )
    }

    @Test
    fun `a missing document keeps the shipped global behaviour`() {
        assertFalse(file.exists())
        val soul = SoulFile(SoulMetadata.DEFAULT, "GLOBAL-BODY")
        val document = SoulPromptRuleStore.cachedDocumentOrLoad(file)
        assertEquals(SoulPromptMode.GLOBAL, document.mode)
        assertTrue(document.rules.isEmpty())
        assertTrue(
            SystemPromptBuilder.applicationPrompt(soul, document.mode, document.rules, SoulPromptSelection())!!
                .contains("GLOBAL-BODY"),
        )
    }

    @Test
    fun `the snapshot accessor returns mode and rules from one read`() {
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(ruleA))
        val document = SoulPromptRuleStore.cachedDocumentOrLoad(file)
        assertEquals(SoulPromptMode.CUSTOM, document.mode)
        assertEquals(listOf("rule-a"), document.rules.map { it.id })
        // The warm-cache path returns the same, already-consistent document.
        assertEquals(document, SoulPromptRuleStore.cachedDocumentOrLoad(file))
    }

    // ── End-to-end mutual exclusion through the store ──────────────────
    @Test
    fun `stored mode decides which text the runtime renders`() {
        val soul = SoulFile(SoulMetadata.DEFAULT, "GLOBAL-BODY")
        SoulPromptRuleStore.save(file, SoulPromptMode.GLOBAL, listOf(ruleA))
        val selection = SoulPromptSelection(providerInstanceId = "provider-1", modelId = "model-x")

        val globalBlock = SystemPromptBuilder.applicationPrompt(
            soul,
            SoulPromptRuleStore.loadMode(file),
            SoulPromptRuleStore.load(file),
            selection,
        )!!
        assertTrue(globalBlock.contains("GLOBAL-BODY"))
        assertFalse(globalBlock.contains("PROMPT-A"))

        SoulPromptRuleStore.setMode(file, SoulPromptMode.CUSTOM)
        val customBlock = SystemPromptBuilder.applicationPrompt(
            soul,
            SoulPromptRuleStore.loadMode(file),
            SoulPromptRuleStore.load(file),
            selection,
        )!!
        assertTrue(customBlock.contains("PROMPT-A"))
        assertFalse(customBlock.contains("GLOBAL-BODY"))

        // A model with no custom prompt gets nothing in CUSTOM mode — not the
        // global body that the file still contains.
        assertNull(
            SystemPromptBuilder.applicationPrompt(
                soul,
                SoulPromptRuleStore.loadMode(file),
                SoulPromptRuleStore.load(file),
                SoulPromptSelection(providerInstanceId = "provider-1", modelId = "unconfigured"),
            ),
        )
    }
}
