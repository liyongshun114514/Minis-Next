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
        assertTrue(chat.contains("ComposerPermissionDialog(onDismiss = { showComposerPermission = false })"))
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
        assertTrue(menu.contains("var selection by remember(current)"))
        assertTrue(menu.contains(".clickable { selection = access }"))
        assertTrue(menu.contains("dismissButton = { TextButton(onClick = onDismiss)"))
        val save = menu.substring(menu.indexOf("confirmButton ="), menu.indexOf("dismissButton ="))
        assertTrue(save.contains("RootManager.setAccessLevel(selection)"))
        assertTrue(save.contains("Text(\"保存\")"))
    }

    @Test fun triStateOrderAndRootRiskAreClear() {
        val options = menu.substring(menu.indexOf("val descriptions ="), menu.indexOf("AlertDialog("))
        assertTrue(options.indexOf("NOT_ALLOWED") < options.indexOf("ASK_ONCE"))
        assertTrue(options.indexOf("ASK_ONCE") < options.indexOf("BYPASS"))
        assertTrue(menu.contains("uid 0"))
        assertTrue(menu.contains("chroot 不是安全隔离边界"))
        assertTrue(menu.contains("su 管理器"))
    }
}
