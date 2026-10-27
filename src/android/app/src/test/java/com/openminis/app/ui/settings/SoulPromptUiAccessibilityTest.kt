package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-kairos-prompt-mode] Accessibility / small-screen contract for the
 * per-model prompt UI.
 *
 * The app cannot run Compose in the JVM test task, so these assertions pin the
 * structural choices that make the screen usable with TalkBack and on a short
 * display: a searchable lazily scrolling picker, bounded dialog content,
 * 48dp minimum rows, explicit switch/edit/delete descriptions, and no tiny
 * icon-only action buttons.
 */
class SoulPromptUiAccessibilityTest {

    private val editor = ProductionSources.read("ui/settings/SoulPromptRuleEditor.kt")
    private val section = ProductionSources.read("ui/settings/SoulPromptRulesSection.kt")
    private val screen = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")

    private fun res(path: String): String {
        val root = ProductionSources.mainRoot() ?: error("production source root not found")
        val f = File(root, "../../../../res/$path")
        assertTrue("missing resource file: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun `the model picker is searchable and scrolls lazily on a long list`() {
        assertTrue(editor.contains("SoulPromptModelPickerDialog("))
        assertTrue(editor.contains("R.string.kairos_prompt_picker_search"))
        assertTrue(editor.contains("singleLine = true"))
        assertTrue(editor.contains("LazyColumn("))
        assertTrue("the list is bounded, not unbounded in a dialog", editor.contains("heightIn(max = 300.dp)"))
        assertTrue(editor.contains("SoulPromptRuleUi.filterModels(models, query)"))
        assertTrue(editor.contains("R.string.kairos_prompt_picker_empty"))
        assertTrue(editor.contains("R.string.kairos_prompt_picker_no_models"))
    }

    @Test
    fun `the editor form scrolls instead of pushing save off a short screen`() {
        assertTrue(editor.contains("verticalScroll(rememberScrollState())"))
        assertTrue(editor.contains("heightIn(max = 380.dp)"))
        assertTrue("the prompt field keeps a usable minimum", editor.contains("heightIn(min = 120.dp)"))
        assertFalse("no fixed height that would clip a small screen", editor.contains("height(300.dp)"))
        assertFalse("no fixed width dialog", editor.contains("width(320.dp)"))
    }

    @Test
    fun `picker rows and the target row meet the 48dp touch target`() {
        assertTrue(editor.contains("heightIn(min = 48.dp)"))
        assertTrue("picker row is selectable", editor.contains("selectable("))
        assertTrue(editor.contains("role = Role.RadioButton"))
        assertTrue("target row is a button", editor.contains("role = Role.Button"))
        assertFalse("no tiny icon-only controls", editor.contains("IconButton("))
        assertFalse(editor.contains("size(36.dp)"))
    }

    @Test
    fun `switch and row actions have explicit descriptions`() {
        assertTrue(section.contains("Modifier.semantics { contentDescription = switchDesc }"))
        assertTrue(section.contains("Modifier.semantics { contentDescription = editDesc }"))
        assertTrue(section.contains("Modifier.semantics { contentDescription = deleteDesc }"))
        assertTrue(section.contains("R.string.kairos_prompt_custom_switch_desc, title"))
        assertTrue(section.contains("R.string.kairos_prompt_custom_edit_desc, title"))
        assertTrue(section.contains("R.string.kairos_prompt_custom_delete_desc, title"))
        // The visible label is not the only name: each description names the
        // model, so a list of identical "Edit" buttons is still distinguishable.
        val en = res("values/kairos_prompt_ui.xml")
        val zh = res("values-zh/kairos_prompt_ui.xml")
        for (key in listOf(
            "kairos_prompt_custom_switch_desc",
            "kairos_prompt_custom_edit_desc",
            "kairos_prompt_custom_delete_desc",
            "kairos_prompt_editor_model_desc",
        )) {
            assertTrue("$key must take a model name", en.contains("name=\"$key\"") && en.contains("%1\$s"))
            assertTrue("$key translated", zh.contains("name=\"$key\"") && zh.contains("%1\$s"))
        }
    }

    @Test
    fun `rows ellipsize long model names and provider labels`() {
        assertTrue(section.contains("maxLines = 1"))
        assertTrue(section.contains("TextOverflow.Ellipsis"))
        assertTrue(section.contains("maxLines = 2"))
        assertTrue(editor.contains("maxLines = 1"))
        assertTrue(editor.contains("maxLines = 2"))
    }

    @Test
    fun `every write control is disabled while a save is in flight`() {
        assertTrue(screen.contains("busy = rulesBusy || ruleSaveInFlight || modeSaving"))
        assertTrue(section.contains("enabled = canAdd && !busy"))
        assertTrue(section.contains("enabled = !busy"))
        assertTrue("switch disabled", section.contains("enabled = !busy,\n                modifier = Modifier.semantics"))
        assertTrue("mode switch disabled", screen.contains("enabled = modeLoaded && !modeSaving"))
        assertTrue(editor.contains("enabled = !saving && !submitLocked"))
        assertTrue(editor.contains("MinisTextButton(onClick = dismiss, enabled = !saving)"))
    }

    @Test
    fun `status is conveyed by text not by color alone`() {
        assertTrue(section.contains("R.string.kairos_prompt_custom_disabled"))
        assertTrue(section.contains("R.string.kairos_prompt_custom_missing_model"))
        assertTrue(section.contains("R.string.kairos_prompt_custom_no_content"))
        assertTrue(screen.contains("R.string.kairos_prompt_mode_saving"))
    }
}
