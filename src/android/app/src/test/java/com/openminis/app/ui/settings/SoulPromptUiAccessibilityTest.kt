package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [K-global-prompt-only] The page kept its accessible primitives (labelled
 * fields, menu items, buttons) after the rule/limit controls were removed.
 */
class SoulPromptUiAccessibilityTest {

    private val src = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")

    @Test
    fun `avatar actions remain labelled menu items`() {
        assertTrue(src.contains("SoulIconMenu("))
        assertTrue(src.contains("R.string.soul_icon_choose_emoji"))
        assertTrue(src.contains("R.string.soul_icon_choose_image"))
        assertTrue(src.contains("R.string.soul_icon_use_default"))
        assertTrue(src.contains("DropdownMenuItem("))
    }

    @Test
    fun `name field and save button keep their labels`() {
        assertTrue(src.contains("label = { Text(stringResource(R.string.soul_field_name)) }"))
        assertTrue(src.contains("R.string.soul_save"))
        assertTrue(src.contains("MinisButton("))
    }

    @Test
    fun `dialogs are still dismissible and the page is still scrollable`() {
        assertTrue(src.contains("SettingsScaffold("))
        assertTrue(src.contains("AlertDialog("))
        assertTrue(src.contains("onDismissRequest = { saveError = null }"))
        assertTrue(src.contains("onDismissRequest = { iconError = null }"))
    }

    @Test
    fun `no removed control leaves a dead accessibility node`() {
        assertFalse(src.contains("SoulCustomRulesSection"))
        assertFalse(src.contains("SoulPromptModeSwitch"))
        assertFalse(src.contains("SoulBodyLimitControls"))
    }
}
