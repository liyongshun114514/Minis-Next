package com.openminis.app.agent.prompts

import com.openminis.app.ProductionSources
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.VisionGroupResolver
import com.openminis.app.ui.chat.TITLE_GEN_SYSTEM_PROMPT
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises real producers, not a duplicate implementation of their strings. */
class PromptRuntimeIntegrationTest {
    private lateinit var root: File
    private lateinit var catalog: JSONArray

    @Before fun setUp() {
        root = Files.createTempDirectory("prompt-runtime-").toFile()
        val main = ProductionSources.mainRoot()!!.parentFile!!.parentFile!!.parentFile!!.parentFile!!
        catalog = JSONArray(File(main, "assets/prompt-catalog.json").readText())
        PromptOverrides.initializeAt(root, catalog.toString())
    }

    @After fun tearDown() {
        PromptOverrides.resetForTesting()
        root.deleteRecursively()
    }

    private fun entry(prefix: String): JSONObject = (0 until catalog.length())
        .map { catalog.getJSONObject(it) }
        .first { it.getString("defaultTemplate").startsWith(prefix) }

    @Test fun `file tool description reads an edit and reset without recreating the process`() {
        val row = entry("Write content to a file on the Linux filesystem.")
        val before = FileWriteTool.definition().description
        PromptOverrides.save(row.getString("id"), "Changed file writer guidance")
        assertEquals("Changed file writer guidance", FileWriteTool.definition().description)
        PromptOverrides.reset(row.getString("id"))
        assertEquals(before, FileWriteTool.definition().description)
    }

    @Test fun `title system getter reflects edits without a new ViewModel`() {
        val row = entry("You generate concise titles for conversations.")
        PromptOverrides.save(row.getString("id"), "Return a concise JSON title.")
        assertEquals("Return a concise JSON title.", TITLE_GEN_SYSTEM_PROMPT)
    }

    @Test fun `vision default instruction actually uses the configured text`() {
        val row = entry("Describe this image in detail and transcribe")
        PromptOverrides.save(row.getString("id"), "Describe the visible layout.")
        assertEquals("Describe the visible layout.", VisionGroupResolver.DESCRIBE_PROMPT)
    }

    @Test fun `helper budget template substitutes runtime values after editing`() {
        val row = entry("[Budget check:")
        PromptOverrides.save(row.getString("id"), "Remaining {{slot0}} {{slot1}}.")
        assertEquals("Remaining 2 rounds.", HelperRunner.turnBudgetWarning(2))
        assertEquals("Remaining 1 round.", HelperRunner.turnBudgetWarning(1))
    }

    @Test fun `every shipped default can be saved and rendered with all its dynamic slots`() {
        for (i in 0 until catalog.length()) {
            val row = catalog.getJSONObject(i)
            val id = row.getString("id")
            val default = row.getString("defaultTemplate")
            PromptOverrides.save(id, default)
            assertEquals(id, default, PromptOverrides.overrideFor(id))
            val args = PromptTemplate.slotCounts(default).keys.associateWith { "VALUE-$it" }
            val expected = PromptTemplate.render(default, args)
            assertEquals(id, expected, PromptOverrides.render(id, default, args))
            PromptOverrides.reset(id)
        }
    }
}
