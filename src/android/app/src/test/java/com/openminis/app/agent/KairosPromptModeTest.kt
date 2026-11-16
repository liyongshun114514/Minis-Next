package com.openminis.app.agent

import com.openminis.app.ProductionSources
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [K-global-prompt-only] The stored GLOBAL/CUSTOM mode survives only as LEGACY
 * DATA. This file used to prove that the two modes were mutually exclusive and
 * that the mode survived rule edits; it now proves the stronger product rule:
 * an old document still decodes and is never rewritten by a read, while no
 * runtime path can consult the mode or the rules.
 */
class KairosPromptModeTest {

    private lateinit var dir: File
    private lateinit var file: File

    private val rule = SoulPromptRule(
        id = "rule-a",
        name = "A",
        enabled = true,
        target = SoulPromptRuleTarget.MODEL,
        providerInstanceId = "provider-1",
        modelId = "model-x",
        content = "PROMPT-A",
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

    @Test
    fun `a legacy custom document still decodes`() {
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(rule))
        val document = SoulPromptRuleStore.decodeDocument(file.readText())
        assertEquals(SoulPromptMode.CUSTOM, document.mode)
        assertEquals("PROMPT-A", document.rules.single().content)
        // Reading does not migrate the file: the old mode stays on disk so a
        // user can still inspect or export it.
        assertTrue(file.readText().contains("\"mode\": \"CUSTOM\""))
    }

    @Test
    fun `both legacy mode constants round-trip`() {
        for (mode in SoulPromptMode.entries) {
            val text = SoulPromptRuleStore.encode(mode, listOf(rule))
            assertEquals(mode, SoulPromptRuleStore.decodeDocument(text).mode)
        }
    }

    @Test
    fun `an unknown mode fails closed to the default document`() {
        // A hand-edited/future value must not crash and must not invent rules.
        file.writeText("""{"version":2,"mode":"SOMETHING_NEW","rules":[]}""")
        assertEquals(SoulPromptMode.GLOBAL, SoulPromptRuleStore.loadMode(file))
        assertTrue(SoulPromptRuleStore.load(file).isEmpty())
    }

    @Test
    fun `the mode is inert at runtime`() {
        val rules = ProductionSources.read("agent/SoulPromptRules.kt")
        assertFalse(rules.contains("SoulPromptRuleResolver"))
        assertFalse(rules.contains("SoulPromptSelection"))

        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertFalse(vm.contains("SoulPromptMode"))
        assertFalse(vm.contains("SoulPromptRule"))

        val screen = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")
        assertFalse(screen.contains("SoulPromptMode"))
        assertFalse(screen.contains("SoulPromptRule"))

        val soul = ProductionSources.read("agent/SoulStore.kt")
        assertFalse(soul.contains("SoulPromptMode"))
        assertNull(
            "no production source may resolve a stored rule",
            ProductionSources.allKotlinFiles()
                .map { it.readText() }
                .firstOrNull { it.contains("SoulPromptRuleResolver") },
        )
    }

    @Test
    fun `a custom-mode file cannot replace or suppress the global body`() {
        SoulPromptRuleStore.save(file, SoulPromptMode.CUSTOM, listOf(rule))
        // The prompt builder takes a SOUL file and nothing else; a rule file
        // on disk has no parameter through which it could enter.
        val block = SystemPromptBuilder.applicationPrompt(SoulFile(SoulMetadata.DEFAULT, "GLOBAL"))
        assertTrue(block!!.contains("GLOBAL"))
        assertFalse(block.contains("PROMPT-A"))
        // Two overloads (Context / frozen SoulFile), both global-only.
        assertEquals(2, Regex("fun applicationPrompt\\(").findAll(
            ProductionSources.read("agent/SoulStore.kt"),
        ).count())
    }

    @Test
    fun `the legacy file is left untouched by a read`() {
        file.writeText("""{"version":1,"rules":[{"id":"old","target":"MODEL","providerInstanceId":"p","modelId":"m","content":"OLD","enabled":true}]}""")
        val before = file.readBytes()
        assertEquals(SoulPromptMode.GLOBAL, SoulPromptRuleStore.loadMode(file))
        assertEquals("OLD", SoulPromptRuleStore.load(file).single().content)
        assertTrue("reading must never rewrite the user's file", before.contentEquals(file.readBytes()))
    }
}
