package com.openminis.app.ui.sessions

import com.openminis.app.ProductionSources
import org.junit.Assert.*
import org.junit.Test

/** Home floating buttons share Miuix glass, not duplicate clipped FAB shadows/ripples. */
class SessionListFabShadowTest {
    private val src = ProductionSources.read("ui/sessions/SessionListScreen.kt")
    private val chatFab = src.substringAfter("val chatFab: @Composable () -> Unit = {").substringBefore("val searchFab:")
    private val searchFab = src.substringAfter("val searchFab: @Composable () -> Unit = {").substringBefore("\n    Row(")

    @Test fun `both home actions use one glass material without fake shadows or duplicate clicks`() {
        for (fab in listOf(chatFab, searchFab)) {
            assertTrue(fab.contains("MinisToolbarIconButton("))
            assertTrue(fab.contains("size = 56.dp"))
            assertFalse(fab.contains("FloatingActionButton("))
            assertFalse(fab.contains(".shadow("))
            assertFalse(fab.contains(".combinedClickable("))
            assertFalse("direct actions do not use menu springs", fab.contains("menuMotion = true"))
        }
    }
    @Test fun `new chat retains group long press and both buttons retain drag swap`() {
        assertTrue(chatFab.contains("onLongClick = { if (topGroups.isNotEmpty()) showGroupMenu = true }"))
        assertTrue(chatFab.contains("onNewChatWithGroup(group.id)"))
        for (fab in listOf(chatFab, searchFab)) assertTrue(fab.contains("detectHorizontalDragGestures("))
    }
}
