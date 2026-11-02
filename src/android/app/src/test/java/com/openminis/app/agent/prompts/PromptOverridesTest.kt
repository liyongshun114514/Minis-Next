package com.openminis.app.agent.prompts

import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-prompt-overrides] Storage, validation and restore-to-default, exercised
 * against a real directory instead of a device: [PromptOverrides.initializeAt]
 * takes the app's `filesDir`, so the whole feature runs on a temp folder with
 * the real `PROMPTS.json` write path.
 *
 * The contract this pins, in the words the rest of the app depends on:
 *  - a built-in prompt always has its default; an override is only a
 *    replacement for the TEXT and only while its `{{slotN}}` placeholders still
 *    match the default's,
 *  - a rejected save changes nothing — the previous override is still there,
 *  - "restore default" puts the shipped text back and leaves no override file
 *    behind when it was the last one.
 */
class PromptOverridesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var promptFile: File

    /**
     * `chat.system` has a repeated slot on purpose: it is the case a plain
     * set-comparison would wave through while the prompt silently loses one of
     * its two insertions.
     */
    private val catalog = """
        [
          {
            "id": "chat.system",
            "group": "chat",
            "title": "Chat system prompt",
            "source": "ui/chat/ChatViewModel.kt",
            "defaultTemplate": "You are {{slot1}}. Tools: {{slot2}}. Again: {{slot1}}."
          },
          {
            "id": "chat.title",
            "group": "chat",
            "title": "Conversation title",
            "source": "ui/chat/TitleGenerator.kt",
            "defaultTemplate": "Summarise the conversation in one line."
          },
          {
            "id": "identity",
            "group": "chat",
            "title": "Identity sentence",
            "source": "agent/SoulStore.kt",
            "defaultTemplate": "You are {name}, a capable AI assistant. Tools: {{slot1}}."
          },
          {
            "id": "scheduled.trigger",
            "group": "scheduled",
            "title": "Scheduled trigger",
            "source": "scheduled/ScheduledTaskMarker.kt",
            "defaultTemplate": "This task already ran: {{slot0}}. Result: {{result}}."
          }
        ]
    """.trimIndent()

    private val systemDefault = "You are {{slot1}}. Tools: {{slot2}}. Again: {{slot1}}."

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        promptFile = File(filesDir, "minis-global/memory/PROMPTS.json")
        PromptOverrides.initializeAt(filesDir, catalog)
    }

    @After
    fun tearDown() {
        PromptOverrides.resetForTesting()
    }

    private fun storedJson(): JSONObject = JSONObject(promptFile.readText())

    private fun assertSaveRejected(id: String, text: String, fragment: String) {
        try {
            PromptOverrides.save(id, text)
            fail("save($id) should have been rejected")
        } catch (e: PromptOverrideException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(fragment))
        }
    }

    // MARK: - Catalog

    @Test
    fun `entries expose the catalog in order with every field`() {
        val entries = PromptOverrides.entries()
        assertEquals(
            listOf("chat.system", "chat.title", "identity", "scheduled.trigger"),
            entries.map { it.id },
        )
        assertEquals(
            PromptEntry(
                id = "chat.system",
                group = "chat",
                title = "Chat system prompt",
                source = "ui/chat/ChatViewModel.kt",
                defaultTemplate = systemDefault,
            ),
            entries[0],
        )
    }

    @Test
    fun `no catalog asset means no entries but rendering still works`() {
        PromptOverrides.initializeAt(filesDir, null)
        assertEquals(emptyList<PromptEntry>(), PromptOverrides.entries())
        assertEquals(
            "Hi x",
            PromptOverrides.render("anything", "Hi {{slot1}}", mapOf("slot1" to "x")),
        )
    }

    @Test
    fun `catalog tolerates a wrapper object, blank ids and duplicates`() {
        PromptOverrides.initializeAt(
            filesDir,
            """
            {"prompts": [
              {"id": "a", "defaultTemplate": "one"},
              {"id": "  ", "defaultTemplate": "no id"},
              {"id": "a", "defaultTemplate": "duplicate"},
              {"id": "b"}
            ]}
            """.trimIndent(),
        )
        val entries = PromptOverrides.entries()
        assertEquals(listOf("a"), entries.map { it.id })
        assertEquals("one", entries[0].defaultTemplate)
    }

    // MARK: - Rendering

    @Test
    fun `without an override the default renders with arguments`() {
        assertEquals(
            "You are Minis. Tools: none. Again: Minis.",
            PromptOverrides.render(
                "chat.system",
                systemDefault,
                mapOf("slot1" to "Minis", "slot2" to "none"),
            ),
        )
        assertEquals(systemDefault, PromptOverrides.render("chat.system", systemDefault))
    }

    @Test
    fun `before initialize the passed default is used verbatim`() {
        PromptOverrides.save("chat.system", "Saved {{slot1}} {{slot2}} {{slot1}}")
        PromptOverrides.resetForTesting()

        assertEquals(emptyList<PromptEntry>(), PromptOverrides.entries())
        assertNull(PromptOverrides.overrideFor("chat.system"))
        assertEquals(
            "D x",
            PromptOverrides.render("chat.system", "D {{slot1}}", mapOf("slot1" to "x")),
        )
        try {
            PromptOverrides.save("chat.system", "D")
            fail("save before initialize should be refused")
        } catch (e: PromptOverrideException) {
            assertTrue(e.message!!.contains("not initialised"))
        }
        // Reset is a no-op rather than a crash: a settings screen built before
        // startup finished must not take the app down.
        PromptOverrides.reset("chat.system")
    }

    @Test
    fun `a stored override replaces the text and keeps every slot`() {
        val edited = "Repeat {{slot1}}; you are {{slot1}}; tools {{slot2}}."
        PromptOverrides.save("chat.system", edited)

        assertEquals(edited, PromptOverrides.overrideFor("chat.system"))
        assertEquals(
            "Repeat A; you are A; tools B.",
            PromptOverrides.render(
                "chat.system",
                systemDefault,
                mapOf("slot1" to "A", "slot2" to "B"),
            ),
        )
        // Persisted, versioned, and keyed by id.
        val root = storedJson()
        assertEquals(1, root.getInt("version"))
        assertEquals(edited, root.getJSONObject("overrides").getString("chat.system"))
        // No tmp debris left beside it (the backup copies every file it finds).
        assertFalse(File(promptFile.parentFile, "PROMPTS.json.tmp").exists())
    }

    @Test
    fun `an override survives a re-initialize`() {
        PromptOverrides.save("chat.system", "Edited {{slot1}} {{slot2}} {{slot1}}")
        PromptOverrides.initializeAt(filesDir, catalog)
        assertEquals("Edited {{slot1}} {{slot2}} {{slot1}}", PromptOverrides.overrideFor("chat.system"))
    }

    // MARK: - Validation

    @Test
    fun `a save that drops a placeholder is refused and changes nothing`() {
        PromptOverrides.save("chat.system", "Kept {{slot1}} {{slot2}} {{slot1}}")
        val before = promptFile.readText()

        assertSaveRejected(
            "chat.system",
            "You are {{slot1}}. Tools: {{slot2}}.",
            "missing {{slot1}}",
        )
        assertSaveRejected("chat.system", "You are {{slot1}}.", "missing {{slot1}}")
        // The previous override is still in force, on disk and in memory.
        assertEquals(before, promptFile.readText())
        assertEquals("Kept {{slot1}} {{slot2}} {{slot1}}", PromptOverrides.overrideFor("chat.system"))
    }

    @Test
    fun `a save that adds a placeholder or an unknown name is refused`() {
        assertSaveRejected(
            "chat.system",
            "{{slot1}} {{slot1}} {{slot1}} {{slot2}}",
            "unexpected {{slot1}}",
        )
        assertSaveRejected(
            "chat.system",
            "{{slot1}} {{slot2}} {{slot1}} {{slot3}}",
            "unexpected {{slot3}}",
        )
        assertSaveRejected(
            "chat.system",
            "You are {{slot1}}. Tools: {{slot2}}. Again: {{slot1}} — {{user}}",
            "unknown placeholder(s) {{user}}",
        )
        // The error names both sides, so the editor can show a real diff.
        try {
            PromptOverrides.save("chat.system", "{{slot2}}")
            fail("expected a rejection")
        } catch (e: PromptOverrideException) {
            val message = e.message!!
            assertTrue(message, message.contains("default has {{slot1}}×2, {{slot2}}"))
            assertTrue(message, message.contains("this text has {{slot2}}"))
        }
        assertNull(PromptOverrides.overrideFor("chat.system"))
        assertFalse(promptFile.exists())
    }

    @Test
    fun `reordering the placeholders is an accepted edit`() {
        val reordered = "Tools {{slot2}}. Again {{slot1}}. You {{slot1}}."
        PromptOverrides.save("chat.system", reordered)
        assertEquals(reordered, PromptOverrides.overrideFor("chat.system"))
    }

    @Test
    fun `empty text is refused only when the default has required slots`() {
        assertSaveRejected("chat.system", "", "cannot be empty")
        assertSaveRejected("chat.system", "", "{{slot1}}×2, {{slot2}}")

        // No slots, nothing to lose: an empty override is a legitimate edit.
        PromptOverrides.save("chat.title", "")
        assertEquals("", PromptOverrides.overrideFor("chat.title"))
        assertEquals("", PromptOverrides.render("chat.title", "Summarise the conversation in one line."))
    }

    @Test
    fun `an unknown id cannot be saved`() {
        PromptOverrides.initializeAt(filesDir, null)
        try {
            PromptOverrides.save("nope", "text")
            fail("expected a rejection")
        } catch (e: PromptOverrideException) {
            assertTrue(e.message!!.contains("unknown prompt id 'nope'"))
        }
    }

    @Test
    fun `an id that left the catalog can still be edited from its stored text`() {
        // An override from a build whose catalog had an entry the current one
        // has dropped: the editor may not know the default, but the stored
        // text is still a valid reference, so the user's edit is not lost.
        promptFile.parentFile?.mkdirs()
        promptFile.writeText(
            """{"version":1,"overrides":{"legacy.prompt":"Legacy {{slot1}}"}}""",
        )
        PromptOverrides.initializeAt(filesDir, catalog)

        assertEquals("Legacy {{slot1}}", PromptOverrides.overrideFor("legacy.prompt"))
        PromptOverrides.save("legacy.prompt", "Rewritten {{slot1}}")
        assertEquals("Rewritten {{slot1}}", PromptOverrides.overrideFor("legacy.prompt"))
    }

    // MARK: - The caller-filled {name}

    @Test
    fun `a save that drops the caller-filled name is refused`() {
        // render hands {name} straight back to the caller's `.replace`, so a
        // deleted one would reach the model as literal "{name}".
        assertSaveRejected(
            "identity",
            "You are a capable AI assistant. Tools: {{slot1}}.",
            "missing {name}",
        )
        assertSaveRejected(
            "identity",
            "You are {name} ({name}), an assistant. Tools: {{slot1}}.",
            "unexpected {name}",
        )
        assertFalse(promptFile.exists())
    }

    @Test
    fun `a save that keeps the name is accepted and render leaves it for the caller`() {
        PromptOverrides.save("identity", "You are {name}. Tools: {{slot1}}.")
        assertEquals("You are {name}. Tools: {{slot1}}.", PromptOverrides.overrideFor("identity"))
        // Slot filled here; {name} deliberately untouched so the caller's
        // `.replace("{name}", …)` still works exactly as before.
        assertEquals(
            "You are {name}. Tools: none.",
            PromptOverrides.render(
                "identity",
                "You are {name}, a capable AI assistant. Tools: {{slot1}}.",
                mapOf("slot1" to "none"),
            ),
        )
    }

    @Test
    fun `a render with no arguments keeps every slot standing`() {
        // A UI preview or a call site that has not been wired to its argument
        // map yet must not blank the dynamic values out of the prompt.
        assertEquals(systemDefault, PromptOverrides.render("chat.system", systemDefault))
        PromptOverrides.save("chat.system", "Edited {{slot1}} {{slot2}} {{slot1}}")
        assertEquals(
            "Edited {{slot1}} {{slot2}} {{slot1}}",
            PromptOverrides.render("chat.system", systemDefault),
        )
    }

    @Test
    fun `a token the default owns is kept by count and cannot be invented`() {
        // `{{result}}` is not a slot — the scheduler fills it later — but it is
        // part of this prompt's contract, so it must survive an edit.
        PromptOverrides.save(
            "scheduled.trigger",
            "Already ran: {{slot0}}. Its result: {{result}}.",
        )
        assertEquals(
            "Already ran: x. Its result: {{result}}.",
            PromptOverrides.render(
                "scheduled.trigger",
                "This task already ran: {{slot0}}. Result: {{result}}.",
                mapOf("slot0" to "x"),
            ),
        )
        // Dropping it is a missing placeholder...
        assertSaveRejected(
            "scheduled.trigger",
            "Already ran: {{slot0}}.",
            "missing {{result}}",
        )
        // ...and inventing a new token is still refused as unknown.
        assertSaveRejected(
            "scheduled.trigger",
            "Already ran: {{slot0}}. {{result}} {{extra}}",
            "unknown placeholder(s) {{extra}}",
        )
    }

    // MARK: - Restore default

    @Test
    fun `reset restores the default and removes the last override file`() {
        PromptOverrides.save("chat.system", "Edited {{slot1}} {{slot2}} {{slot1}}")
        assertTrue(promptFile.isFile)

        PromptOverrides.reset("chat.system")
        assertNull(PromptOverrides.overrideFor("chat.system"))
        assertEquals(systemDefault, PromptOverrides.render("chat.system", systemDefault))
        // "No overrides" has one on-disk shape: no file.
        assertFalse(promptFile.exists())

        // Idempotent: resetting again writes nothing and does not throw.
        PromptOverrides.reset("chat.system")
        assertFalse(promptFile.exists())
    }

    @Test
    fun `failed reset preserves the cached override`() {
        val edited = "Edited {{slot1}} {{slot2}} {{slot1}}"
        PromptOverrides.save("chat.system", edited)
        assertTrue(promptFile.delete())
        assertTrue(promptFile.mkdir())
        File(promptFile, "cannot-delete").writeText("occupied")
        try {
            PromptOverrides.reset("chat.system")
            org.junit.Assert.fail("non-empty target directory must reject reset")
        } catch (_: PromptOverrideException) {
            assertEquals(edited, PromptOverrides.overrideFor("chat.system"))
            assertTrue(File(promptFile, "cannot-delete").isFile)
        }
    }

    @Test
    fun `failed atomic replace preserves the cached override`() {
        PromptOverrides.save("chat.title", "Previous")
        assertTrue(promptFile.delete())
        assertTrue(promptFile.mkdir())
        File(promptFile, "cannot-replace").writeText("occupied")
        try {
            PromptOverrides.save("chat.title", "Next")
            org.junit.Assert.fail("cannot replace a non-empty directory")
        } catch (_: PromptOverrideException) {
            assertEquals("Previous", PromptOverrides.overrideFor("chat.title"))
            assertTrue(File(promptFile, "cannot-replace").isFile)
        }
    }

    @Test
    fun `reset keeps the other overrides`() {
        PromptOverrides.save("chat.system", "Edited {{slot1}} {{slot2}} {{slot1}}")
        PromptOverrides.save("chat.title", "Short title")
        PromptOverrides.reset("chat.title")

        assertEquals("Edited {{slot1}} {{slot2}} {{slot1}}", PromptOverrides.overrideFor("chat.system"))
        assertNull(PromptOverrides.overrideFor("chat.title"))
        assertEquals(
            setOf("chat.system"),
            storedJson().getJSONObject("overrides").keys().asSequence().toSet(),
        )
    }

    // MARK: - Compatibility with a changed default

    @Test
    fun `a reworded default keeps the user's edit`() {
        PromptOverrides.save("chat.system", "Mine {{slot1}} {{slot1}} {{slot2}}")
        // The app rewords its prompt but keeps the same slots (and passes its
        // own text to render, which it always does).
        PromptOverrides.initializeAt(
            filesDir,
            """
            [{"id":"chat.system","group":"chat","title":"Chat system prompt","source":"x",
              "defaultTemplate":"You are {{slot1}} (again {{slot1}}). Tools available: {{slot2}}."}]
            """.trimIndent(),
        )
        assertEquals(
            "Mine A A B",
            PromptOverrides.render(
                "chat.system",
                "You are {{slot1}} (again {{slot1}}). Tools available: {{slot2}}.",
                mapOf("slot1" to "A", "slot2" to "B"),
            ),
        )
    }

    @Test
    fun `an override that no longer fits is ignored but not deleted`() {
        val edited = "Mine {{slot1}} {{slot1}} {{slot2}}"
        PromptOverrides.save("chat.system", edited)

        // An update that added a slot: the stored text cannot render it.
        val newDefault = "You are {{slot1}} {{slot1}}, tools {{slot2}}, os {{slot3}}."
        PromptOverrides.initializeAt(
            filesDir,
            """
            [{"id":"chat.system","group":"chat","title":"Chat system prompt","source":"x",
              "defaultTemplate":"$newDefault"}]
            """.trimIndent(),
        )
        assertNull(PromptOverrides.overrideFor("chat.system"))
        assertEquals(
            "You are A A, tools B, os C.",
            PromptOverrides.render(
                "chat.system",
                newDefault,
                mapOf("slot1" to "A", "slot2" to "B", "slot3" to "C"),
            ),
        )
        // Still on disk: an app update must not destroy the user's text.
        assertEquals(edited, storedJson().getJSONObject("overrides").getString("chat.system"))
        // And reset is the way back to a clean state.
        PromptOverrides.reset("chat.system")
        assertNull(PromptOverrides.overrideFor("chat.system"))
        assertFalse(promptFile.exists())
    }

    @Test
    fun `a passed default with different slots ignores the override`() {
        PromptOverrides.save("chat.system", "Mine {{slot1}} {{slot1}} {{slot2}}")
        // The call site still passes its own template, with one slot fewer: the
        // edit is not applied, and the call site's own text renders instead.
        assertEquals(
            "Hi there",
            PromptOverrides.render("chat.system", "Hi {{slot1}}", mapOf("slot1" to "there")),
        )
    }

    // MARK: - File tolerance

    @Test
    fun `a corrupt file is ignored instead of breaking the launch`() {
        promptFile.parentFile?.mkdirs()
        promptFile.writeText("{not json at all")
        PromptOverrides.initializeAt(filesDir, catalog)

        assertEquals(4, PromptOverrides.entries().size)
        assertNull(PromptOverrides.overrideFor("chat.system"))
        assertEquals(systemDefault, PromptOverrides.render("chat.system", systemDefault))
        // A later save rewrites the file from the in-memory state.
        PromptOverrides.save("chat.title", "T")
        assertEquals("T", storedJson().getJSONObject("overrides").getString("chat.title"))
    }

    @Test
    fun `hand-written text with an unknown placeholder is ignored`() {
        promptFile.parentFile?.mkdirs()
        promptFile.writeText(
            """{"version":1,"overrides":{"chat.title":"Title: {{name}}"}}""",
        )
        PromptOverrides.initializeAt(filesDir, catalog)
        assertNull(PromptOverrides.overrideFor("chat.title"))
        assertEquals("Title", PromptOverrides.render("chat.title", "Title"))
    }

    @Test
    fun `a legacy flat map and an object value both load`() {
        promptFile.parentFile?.mkdirs()
        promptFile.writeText(
            """
            {"chat.system":{"text":"From object {{slot1}} {{slot2}} {{slot1}}"},
             "chat.title":"From flat map"}
            """.trimIndent(),
        )
        PromptOverrides.initializeAt(filesDir, catalog)
        assertNotNull(PromptOverrides.overrideFor("chat.system"))
        assertEquals("From object {{slot1}} {{slot2}} {{slot1}}", PromptOverrides.overrideFor("chat.system"))
        assertEquals("From flat map", PromptOverrides.overrideFor("chat.title"))
    }

    @Test
    fun `the file lands next to SOUL and GLOBAL so memory backup carries it`() {
        PromptOverrides.save("chat.title", "T")
        assertEquals(
            File(filesDir, "minis-global/memory/PROMPTS.json").absolutePath,
            promptFile.absolutePath,
        )
        assertTrue(promptFile.isFile)
    }
}
