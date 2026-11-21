package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ReplySpeedTest {
    @Test fun speedAndFirstOutputSurviveJsonReload() {
        val usage = requireNotNull(ChatTokenUsage.parse("""{"outputTokens":185,"streamMs":10000,"firstTokenMs":2400}"""))
        assertEquals(18.5, requireNotNull(usage.outputTokensPerSecond), 0.0001)
        assertEquals(2400L, usage.firstTokenMs)
    }
    @Test fun oldMessagesDoNotInventTiming() {
        val usage = requireNotNull(ChatTokenUsage.parse("""{"inputTokens":123,"outputTokens":18}"""))
        assertEquals(0L, usage.firstTokenMs)
        assertNull(usage.outputTokensPerSecond)
    }
    @Test fun zeroAndNegativeDurationsCannotProduceInvalidSpeed() {
        for (duration in listOf(0, -1, -1000)) {
            val usage = requireNotNull(ChatTokenUsage.parse("""{"outputTokens":18,"streamMs":$duration,"firstTokenMs":-20}"""))
            assertNull(usage.outputTokensPerSecond)
            assertEquals(0L, usage.firstTokenMs)
        }
    }
    @Test fun malformedRowsRemainSafe() {
        assertNull(ChatTokenUsage.parse("not json"))
        assertNull(ChatTokenUsage.parse("{}"))
    }
}
