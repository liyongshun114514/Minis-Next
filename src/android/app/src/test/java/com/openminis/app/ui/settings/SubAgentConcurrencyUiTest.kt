package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.*
import org.junit.Test

class SubAgentConcurrencyUiTest {
    private val source = ProductionSources.read("ui/settings/SubAgentsScreen.kt")
    @Test fun `concurrency and wait duration share native touch anchored dropdowns`() {
        val page = source.substringBefore("fun SubAgentDetailScreen")
        val tools = ProductionSources.read("ui/settings/AgentToolsSettingsScreen.kt")
        for (content in listOf(page, tools)) {
            assertTrue(content.contains("MinisDropdownPreference("))
            assertTrue(content.contains("anchorAtTap = true"))
            assertFalse(content.contains("OverlayDialog("))
        }
        assertTrue(page.contains("1..AgentJobRegistry.MAX_USER_CONCURRENT_CHILD_JOBS"))
        assertTrue(page.contains("SubAgentConcurrencyPrefs.set(context, concurrency)"))
    }
    @Test fun `reorder controls no longer shrink the roster text column`() {
        assertTrue(source.contains("Column(Modifier.fillMaxWidth())"))
        assertTrue(source.contains("horizontalArrangement = Arrangement.End"))
        assertFalse(source.contains("Box(Modifier.weight(1f))"))
    }
}
