package com.openminis.app.agent

import com.openminis.app.ProductionSources
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
 * [K-global-prompt-only] `SoulPromptRules.kt` is now a LEGACY DATA module: it
 * decodes/round-trips an old `SOUL_PROMPT_RULES.json` so a user's file is not
 * lost, but it exposes no resolver and no runtime accessor. This test keeps
 * the file-format safety guarantees (atomic write, corrupt file preserved)
 * while asserting that no code path can turn a stored rule into a prompt.
 */
class SoulPromptRulesTest {

    private lateinit var dir: File
    private lateinit var file: File

    private val modelRule = SoulPromptRule(
        id = "model-rule",
        name = "A",
        enabled = true,
        target = SoulPromptRuleTarget.MODEL,
        providerInstanceId = "provider-1",
        modelId = "model-x",
        content = "PROMPT-A",
    )

    private val groupRule = SoulPromptRule(
        id = "group-rule",
        target = SoulPromptRuleTarget.GROUP,
        groupId = "group-a",
        content = "LEGACY",
    )

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("soul-prompt-legacy").toFile()
        file = File(dir, "SOUL_PROMPT_RULES.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `an old document with both scopes still decodes`() {
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(modelRule, groupRule))
        val document = SoulPromptRuleStore.decodeDocument(file.readText())
        assertEquals(SoulPromptMode.CUSTOM, document.mode)
        assertEquals(listOf("model-rule", "group-rule"), document.rules.map { it.id })
        assertEquals(SoulPromptRuleTarget.GROUP, document.rules[1].target)
    }

    @Test
    fun `legacy array-only files still decode`() {
        file.writeText("""[{"id":"old","target":"MODEL","providerInstanceId":"p","modelId":"m","content":"OLD"}]""")
        assertEquals(1, SoulPromptRuleStore.load(file).size)
        assertEquals("OLD", SoulPromptRuleStore.load(file)[0].content)
    }

    @Test
    fun `the store round-trips without rewriting unrelated bytes`() {
        SoulPromptRuleStore.save(file, listOf(modelRule))
        val before = file.readText()
        SoulPromptRuleStore.save(file, listOf(modelRule))
        assertEquals(before, file.readText())
        assertFalse(File(dir, "SOUL_PROMPT_RULES.json.tmp").exists())
        assertFalse(File(dir, "SOUL_PROMPT_RULES.json.bak").exists())
    }

    @Test
    fun `a corrupt document is never rewritten by a read or a save`() {
        val broken = """{"version":2,"mode":"CUSTOM","rules":[{"id":"""
        file.writeText(broken)
        assertEquals(emptyList<SoulPromptRule>(), SoulPromptRuleStore.load(file))
        assertEquals(broken, file.readText())
        try {
            SoulPromptRuleStore.save(file, listOf(modelRule))
            fail("save over a corrupt document must be refused")
        } catch (_: java.io.IOException) {
        }
        assertEquals(broken, file.readText())
    }

    @Test
    fun `no resolver, selection, or runtime accessor exists`() {
        val rules = ProductionSources.read("agent/SoulPromptRules.kt")
        for (token in listOf(
            "SoulPromptRuleResolver", "SoulPromptSelection", "SoulPromptRuleTier",
            "cachedOrLoad(", "cachedModeOrLoad", "cachedDocumentOrLoad",
        )) {
            assertFalse("$token must be gone", rules.contains(token))
        }
        assertTrue(rules.contains("LEGACY"))
        assertNull(
            "nothing resolves a stored rule to a prompt",
            ProductionSources.allKotlinFiles()
                .map { it.readText() }
                .firstOrNull { it.contains("SoulPromptRuleResolver") },
        )
    }

    @Test
    fun `the global prompt never consults a rule file`() {
        val soul = ProductionSources.read("agent/SoulStore.kt")
        assertFalse(soul.contains("SoulPromptRule"))
        assertFalse(soul.contains("SoulPromptMode"))
        val block = SystemPromptBuilder.applicationPrompt(SoulFile(SoulMetadata.DEFAULT, "GLOBAL BODY"))
        assertTrue(block!!.contains("GLOBAL BODY"))
    }
}
