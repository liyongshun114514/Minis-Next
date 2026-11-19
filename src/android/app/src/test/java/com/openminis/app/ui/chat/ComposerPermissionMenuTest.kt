package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring checks, not device visual/interaction tests. */
class ComposerPermissionMenuTest {
    private val menu = ProductionSources.read("ui/chat/ComposerPermissionMenu.kt")
    private val chat = ProductionSources.read("ui/chat/ChatScreen.kt")

    @Test fun permissionIsImmediatelyAfterAddFileBeforeCommands() {
        val start = chat.indexOf("// Button row below text field")
        val file = chat.indexOf("R.string.chat_attach_add_file", start)
        val permission = chat.indexOf("ComposerPermissionMenuItem(", file)
        val commands = chat.indexOf("ComposerCommandMenuRows(", file)
        assertTrue(start >= 0 && file > start && permission > file && commands > permission)
        assertFalse(chat.substring(file, permission).contains("MinisMenuDivider()"))
        assertTrue(chat.contains("ComposerPermissionDialog("))
        assertTrue(chat.contains("show = showComposerPermission"))
        assertFalse(chat.contains("if (showComposerPermission)"))
    }

    @Test fun permissionUsesOneSharedPersistentRootSetting() {
        assertTrue(menu.contains("OffloadPermissionManager.rootLevel()"))
        assertEquals(1, Regex("RootManager\\.setAccessLevel\\(").findAll(menu).count())
        assertTrue(menu.contains("registerOnSharedPreferenceChangeListener(listener)"))
        assertTrue(menu.contains("unregisterOnSharedPreferenceChangeListener(listener)"))
        assertFalse(menu.contains("RootManager.runProcess"))
        assertFalse(menu.contains("ensureServer("))
    }

    @Test fun changesAreOnlyAppliedBySaveNotDismissOrRadioTap() {
        assertTrue(menu.contains("var selection by remember { mutableStateOf(current) }"))
        assertTrue(menu.contains("LaunchedEffect(show) { if (show) selection = current }"))
        assertTrue(menu.contains("ComposerRootAccessChoices(selection) { selection = it }"))
        val dialog = menu.substringAfter("WindowDialog(").substringBefore("internal fun ComposerRootAccessChoices")
        assertTrue(dialog.contains("onDismissRequest = onDismiss"))
        val cancel = dialog.substringAfter("TextButton(").substringBefore("TextButton(")
        val save = dialog.substringAfterLast("TextButton(")
        assertTrue(save.contains("RootManager.setAccessLevel(selection)"))
        assertTrue(save.contains("R.string.common_save"))
        assertTrue(save.contains("ButtonDefaults.textButtonColorsPrimary()"))
        assertTrue(cancel.contains("onClick = onDismiss"))
        assertFalse(cancel.contains("setAccessLevel"))
        assertTrue(dialog.contains("Arrangement.spacedBy(20.dp)"))
    }

    @Test fun triStateOrderAndRootRiskAreClear() {
        val options = menu.substringAfter("val options =")
        assertTrue(options.indexOf("NOT_ALLOWED") < options.indexOf("ASK_ONCE"))
        assertTrue(options.indexOf("ASK_ONCE") < options.indexOf("BYPASS"))
        assertTrue(menu.contains("RadioButtonPreference("))
        assertTrue(menu.contains("radioButtonLocation = top.yukonga.miuix.kmp.preference.RadioButtonLocation.End"))
        assertTrue(menu.contains("R.string.composer_permission_explanation"))
        assertTrue(menu.contains("R.string.composer_root_allowed_summary"))
    }
}
