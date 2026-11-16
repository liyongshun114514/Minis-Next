package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [K-unified-prompt-workspace] The System Prompt hub + built-in editor, pinned
 * at the two places a compiler cannot help:
 *
 *  1. the placeholder summary shown to the author must come from
 *     [com.openminis.app.agent.prompts.PromptTemplate.signature] — i.e. the
 *     same parser save() validates with — so `{name}` and a default's own
 *     `{{result}}` are displayed, not dropped by a local double-brace regex;
 *  2. Save / Restore-default must act on a draft snapshot taken at tap time,
 *     so text typed while the write is in flight is never reported as saved
 *     (and never discarded by a concurrent reset);
 *  3. the editor's remembered state is keyed to the prompt id, and the hub
 *     re-reads override state on RESUMED instead of assuming that coming back
 *     from the editor rebuilds the composition.
 *
 * The behavioural half exercises [builtinSlotUsage] directly (pure function,
 * no Android); the rest are source contracts over the production files, in the
 * same style as SoulPromptModeUiContractTest.
 */
class UnifiedPromptSettingsTest {

    private val editor = ProductionSources.read("ui/settings/BuiltinPromptEditorScreen.kt")
    private val hub = ProductionSources.read("ui/settings/PromptSettingsScreen.kt")

    // ─── builtinSlotUsage: every placeholder kind, counted, ordered ──────────

    @Test
    fun `placeholder summary counts slots, names and default-owned tokens`() {
        val usage = builtinSlotUsage("{{slot1}} {name} {{result}} {{slot1}}")
        assertEquals(listOf("{{slot1}}" to 2, "{name}" to 1, "{{result}}" to 1), usage)
    }

    @Test
    fun `slots are listed numerically before the other token kinds`() {
        val usage = builtinSlotUsage("{{slot10}} {name} {{slot2}} {{result}}")
        assertEquals(listOf("{{slot2}}" to 1, "{{slot10}}" to 1), usage.take(2))
        assertEquals(
            setOf("{name}", "{{result}}"),
            usage.drop(2).map { it.first }.toSet(),
        )
    }

    @Test
    fun `text without placeholders has no usage`() {
        assertEquals(emptyList<Pair<String, Int>>(), builtinSlotUsage("plain prose, no braces"))
        // Prose braces are text: only `{{slotN}}`, identifier `{name}` and
        // double-brace tokens are placeholders.
        assertEquals(emptyList<Pair<String, Int>>(), builtinSlotUsage("{\"a\": 1} {1} { }"))
    }

    @Test
    fun `usage is derived from PromptTemplate signature, not a local regex`() {
        // The old helper scanned only `{{slotN}}` and therefore reported
        // "{name}" and "{{result}}" as absent — the exact tokens save() refuses
        // to lose. The production helper must delegate to the shared parser.
        assertTrue(editor.contains("PromptTemplate.signature(template)"))
        assertFalse("a second scanner would drift from the validator", editor.contains("BUILTIN_SLOT"))
        assertFalse(editor.contains("Regex(\"\"\"\\{\\{slot"))
    }

    // ─── Save: immutable snapshot at tap time ───────────────────────────────

    @Test
    fun `save writes a draft snapshot captured at tap time`() {
        assertTrue("save must snapshot the draft", editor.contains("val submitted = draft"))
        assertTrue(
            "the snapshot, not the live field, is persisted",
            editor.contains("PromptOverrides.save(promptId, submitted)"),
        )
        assertFalse(
            "reading draft inside the coroutine would save text typed mid-write",
            editor.contains("PromptOverrides.save(promptId, draft)"),
        )
    }

    @Test
    fun `the saved baseline is the snapshot, never the current draft`() {
        assertTrue(editor.contains("baseline = submitted"))
        assertFalse(
            "baseline = draft would mark text that never reached disk as saved",
            editor.contains("baseline = draft"),
        )
        // Success is only committed after the IO result comes back, so a
        // failure leaves the page dirty and editable.
        val commit = editor.indexOf("baseline = submitted")
        val failure = editor.indexOf(".onFailure { saveFailure")
        assertTrue("baseline commit inside the success branch", commit in 0 until failure)
    }

    // ─── Restore default: must not swallow input typed meanwhile ────────────

    @Test
    fun `restore default keeps text typed while the reset is in flight`() {
        assertTrue(editor.contains("val submitted = draft"))
        assertTrue(
            "the draft is only replaced when it has not moved since the tap",
            editor.contains("if (draft == submitted)"),
        )
        val guard = editor.indexOf("if (draft == submitted)")
        val assign = editor.indexOf("draft = default")
        assertTrue("draft = default sits behind the guard", guard in 0 until assign)
        assertTrue(
            "the restored default becomes the baseline either way",
            editor.indexOf("baseline = default") in 0 until guard,
        )
    }

    // ─── Route/promptId scoping ─────────────────────────────────────────────

    @Test
    fun `editor state is keyed to the prompt id and reset on load`() {
        assertTrue("editor body is keyed by promptId", editor.contains("key(promptId)"))
        val load = editor.indexOf("LaunchedEffect(promptId) {")
        assertTrue("a load effect per prompt id", load >= 0)
        val reset = editor.indexOf("draft = \"\"", load)
        assertTrue("the draft is cleared before the catalog read", reset > load)
        assertTrue(editor.contains("entry = null"))
        assertTrue(editor.contains("baseline = \"\""))
    }

    @Test
    fun `hub reloads override state on resume, not only on first composition`() {
        assertTrue(hub.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(hub.contains("overrideRevision++"))
        assertTrue(hub.contains("remember(entries, overrideRevision)"))
        assertTrue(hub.contains("entry.id in overriddenIds"))
        assertFalse(
            "a badge read only during the first composition goes stale after an edit",
            hub.contains("PromptOverrides.overrideFor(entry.id) != null"),
        )
    }

    // ─── Routing / resources ────────────────────────────────────────────────

    @Test
    fun `the built-in editor route carries an encoded prompt id`() {
        val nav = ProductionSources.read("ui/navigation/AppNavigation.kt")
        assertTrue(nav.contains("const val SOUL_BUILTIN_EDITOR = \"soul_builtin/{promptId}\""))
        assertTrue(nav.contains("android.net.Uri.encode(promptId)"))
        assertTrue(nav.contains("navArgument(\"promptId\")"))
        assertTrue(nav.contains("BuiltinPromptEditorScreen("))
        assertTrue(nav.contains("PromptSettingsScreen("))
    }

    @Test
    fun `every string the two pages reference exists in both locales`() {
        val en = res("values/prompt_catalog_ui.xml")
        val zh = res("values-zh/prompt_catalog_ui.xml")
        val enNames = stringNames(en)
        assertEquals("prompt_catalog strings must be translated", enNames, stringNames(zh))

        val referenced = Regex("""R\.string\.([a-z0-9_]+)""")
            .findAll(editor + hub)
            .map { it.groupValues[1] }
            .toSet()
        val allValues = File(ProductionSources.mainRoot()!!.parentFile!!.parentFile!!.parentFile!!.parentFile!!, "res/values")
            .listFiles { file -> file.name.endsWith(".xml") }
            .orEmpty()
            .flatMap { file -> stringNames(file.readText()) }
            .toSet()
        val missing = referenced - allValues
        assertTrue("unresolvable string ids: $missing", missing.isEmpty())
    }

    private fun stringNames(xml: String): Set<String> =
        Regex("<string name=\"([a-z0-9_]+)").findAll(xml).map { it.groupValues[1] }.toSet()

    private fun res(relative: String): String =
        File(ProductionSources.mainRoot()!!.parentFile!!.parentFile!!.parentFile!!.parentFile!!, "res/$relative").readText()
}
