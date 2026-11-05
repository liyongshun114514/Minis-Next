package com.openminis.app.data.model

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ThinkingConfigTokenTest {
    @Test fun `all seven CLI tokens round trip without changing persisted names`() {
        val tokens = listOf("off", "low", "medium", "high", "xhigh", "max", "ultra")
        assertEquals(tokens, ThinkingLevel.entries.map { it.configToken })
        for (level in ThinkingLevel.entries) {
            assertEquals(level, ThinkingLevel.fromConfigToken(level.configToken))
            assertEquals(level, ThinkingLevel.parseOrNull(level.name))
        }
    }

    @Test fun `config parser remains strict while persisted parser remains tolerant`() {
        for (token in listOf("HIGH", " high", "high ", "", "future")) {
            assertNull(token, ThinkingLevel.fromConfigToken(token))
        }
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.parseOrNull(" HIGH "))
    }

    @Test fun `wire spelling does not depend on device locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("high", ThinkingLevel.HIGH.configToken)
            assertEquals("xhigh", ThinkingLevel.XHIGH.configToken)
        } finally {
            Locale.setDefault(before)
        }
    }
}
