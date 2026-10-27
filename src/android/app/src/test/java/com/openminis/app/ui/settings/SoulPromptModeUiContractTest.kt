package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-kairos-prompt-mode] Independent contract for the persisted mode control.
 *
 * Kept separate from the page contract so a regression in the mode state
 * machine (read → optimistic select → write → restore on failure) fails on its
 * own, without being masked by the many other assertions about the page.
 */
class SoulPromptModeUiContractTest {

    private val src = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")

    private fun res(path: String): String {
        val root = ProductionSources.mainRoot() ?: error("production source root not found")
        val f = File(root, "../../../../res/$path")
        assertTrue("missing resource file: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private fun slice(start: String, end: String): String {
        val at = src.indexOf(start)
        assertTrue("missing marker: $start", at >= 0)
        val until = src.indexOf(end, at + start.length)
        assertTrue("missing end marker: $end", until > at)
        return src.substring(at, until)
    }

    @Test
    fun `the top control is the persisted mode, not a local page index`() {
        assertTrue(src.contains("import com.openminis.app.agent.SoulPromptMode"))
        assertTrue(src.contains("var promptMode by rememberSaveable"))
        assertTrue(src.contains("SoulPromptRuleStore.loadMode(context)"))
        assertTrue(src.contains("SoulPromptRuleStore.setMode(context, requested)"))
        assertTrue(src.contains("when (promptMode)"))
        assertTrue(src.contains("selected = promptMode"))
        assertFalse("no presentation-only enum", src.contains("SoulPromptScope"))
        assertFalse("no page branch on a private enum", src.contains("when (promptScope)"))
    }

    @Test
    fun `mode and rules are read from one snapshot before the control enables`() {
        val load = slice("val promptSnapshot = withContext", "\n        name = parsed.metadata.name")
        assertTrue(load.contains("SoulPromptRuleStore.loadMode(context)"))
        assertTrue(load.contains("SoulPromptRuleStore.load(context)"))
        assertTrue(load.contains("promptMode = promptSnapshot.first"))
        assertTrue(load.contains("SoulPromptRuleUi.stabilize(promptSnapshot.second)"))
        assertTrue("the control waits for the read", load.contains("modeLoaded = true"))
        assertTrue(src.contains("enabled = modeLoaded && !modeSaving"))
    }

    @Test
    fun `a tap writes through and a failure restores the previous selection`() {
        val select = slice("val selectMode: (SoulPromptMode)", "// [T-android-soul-save-in-appbar]")
        val guard = select.indexOf("SoulPromptRuleUi.shouldSwitchMode(")
        val previous = select.indexOf("val previous = promptMode")
        val saving = select.indexOf("modeSaving = true")
        val optimistic = select.indexOf("promptMode = requested")
        val launch = select.indexOf("scope.launch")
        val write = select.indexOf("SoulPromptRuleStore.setMode(context, requested)")
        val failure = select.indexOf("if (failure != null)")
        val restore = select.indexOf("promptMode = previous")
        val error = select.indexOf("modeError = failure.message")

        assertTrue("guard first", guard in 0 until previous)
        assertTrue("previous captured before changing", previous in 0 until optimistic)
        assertTrue("in-flight set synchronously before launch", saving in 0 until launch)
        assertTrue("optimistic selection before the coroutine", optimistic in 0 until launch)
        assertTrue("write happens inside the coroutine", write > launch)
        assertTrue("failure check before restore", failure in 0 until restore)
        assertTrue("restore only after a failure", restore > failure)
        assertTrue("explicit error", error > restore)
        assertEquals(
            "the old mode is restored exactly once, on failure",
            1,
            Regex("promptMode = previous").findAll(select).count(),
        )
        assertTrue("the selection is written, not merely displayed", write > 0)
    }

    @Test
    fun `the control is disabled while a write is in flight`() {
        assertTrue(src.contains("enabled = modeLoaded && !modeSaving"))
        assertTrue(src.contains("saving = modeSaving"))
        val switch = slice("private fun SoulPromptModeSwitch(", "internal fun SoulIconGlyph(")
        assertTrue(switch.contains("enabled = enabled && !saving"))
        assertTrue(switch.contains("if (saving)"))
        assertTrue(switch.contains("R.string.kairos_prompt_mode_saving"))
    }

    @Test
    fun `a mode write failure names the selection that was kept`() {
        assertTrue(src.contains("modeError?.let { err ->"))
        assertTrue(src.contains("R.string.kairos_prompt_mode_error_title"))
        assertTrue(src.contains("R.string.kairos_prompt_mode_error_body, err"))
        val en = res("values/kairos_prompt_ui.xml")
        val zh = res("values-zh/kairos_prompt_ui.xml")
        assertTrue(en.contains("previous selection is still active"))
        assertTrue(zh.contains("仍保持原来的选择"))
    }

    @Test
    fun `switching mode does not touch the global draft`() {
        val select = slice("val selectMode: (SoulPromptMode)", "// [T-android-soul-save-in-appbar]")
        assertFalse(select.contains("body ="))
        assertFalse(select.contains("name ="))
        assertFalse(select.contains("icon ="))
        assertFalse(select.contains("baseline ="))
        // The draft and its dirty comparison live outside the mode path.
        assertTrue(src.contains("var body by remember"))
        assertTrue(src.contains("val isDirty = loaded && baseline != null && currentFile != baseline"))
    }

    @Test
    fun `the runtime reads the same persisted mode from one snapshot`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("SoulPromptRuleStore.cachedDocumentOrLoad(context)"))
        assertTrue(vm.contains("val promptDocumentForTurn"))
        assertTrue(vm.contains("promptDocumentForTurn.mode"))
        assertTrue(vm.contains("promptDocumentForTurn.rules"))
    }

    @Test
    fun `both mode labels exist in both locales`() {
        val en = res("values/kairos_prompt_ui.xml")
        val zh = res("values-zh/kairos_prompt_ui.xml")
        for (key in listOf(
            "kairos_prompt_mode_global",
            "kairos_prompt_mode_custom",
            "kairos_prompt_mode_saving",
        )) {
            assertTrue("$key in values", en.contains("name=\"$key\""))
            assertTrue("$key in values-zh", zh.contains("name=\"$key\""))
        }
        assertTrue(en.contains(">Global<") && en.contains(">Custom<"))
        assertTrue(zh.contains(">全局<") && zh.contains(">自定义<"))
    }
}
