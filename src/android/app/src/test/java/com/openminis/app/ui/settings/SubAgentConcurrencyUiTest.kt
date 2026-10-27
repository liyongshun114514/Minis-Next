package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.*
import org.junit.Test

class SubAgentConcurrencyUiTest {
    private val source = ProductionSources.read("ui/settings/SubAgentsScreen.kt")
    @Test fun `concurrency uses Minis scrollable choice rows instead of crowded radio dialog`() {
        val page = source.substringBefore("fun SubAgentDetailScreen")
        assertTrue(page.contains("ModalBottomSheet(onDismissRequest"))
        assertTrue(page.contains("verticalScroll(rememberScrollState())"))
        assertTrue(page.contains("SettingsChoiceRow("))
        assertTrue(page.contains("1..AgentJobRegistry.MAX_USER_CONCURRENT_CHILD_JOBS"))
        assertTrue(page.contains("SubAgentConcurrencyPrefs.set(context, count)"))
        assertFalse(page.contains("RadioButton("))
    }
    @Test fun `reorder controls no longer shrink the roster text column`() {
        assertTrue(source.contains("Column(Modifier.fillMaxWidth())"))
        assertTrue(source.contains("horizontalArrangement = Arrangement.End"))
        assertFalse(source.contains("Box(Modifier.weight(1f))"))
    }
}
