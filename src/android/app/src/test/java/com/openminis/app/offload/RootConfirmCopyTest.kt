package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Copy contract for the compact Root confirmation dialog.
 *
 * The dialog shows `rootConfirmRisk(...)` under the exact command. These
 * assertions pin the shape the UI relies on: exactly one risk line (the
 * first line, explicitly labelled) plus one context line, the real working
 * directory/timeout, and no wording that implies a session or always-allow
 * grant for Root.
 */
class RootConfirmCopyTest {
    @Test
    fun riskLineIsLabelledAndContextKept() {
        val text = rootConfirmRisk("/data/local/tmp", 5)
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("关键风险："))
        assertTrue(lines[0].contains("可能修改系统或删除数据"))
        assertTrue(lines[0].contains("部分操作无法撤销"))
        assertTrue(lines[1].contains("/data/local/tmp"))
        assertTrue(lines[1].contains("5s"))
    }

    @Test
    fun blankOrMissingCwdIsStatedNotOmitted() {
        val text = rootConfirmRisk("", 30)
        assertTrue(text.contains("Android 默认目录"))
        assertTrue(text.contains("30s"))
    }

    @Test
    fun noSessionOrAlwaysAllowWording() {
        val text = rootConfirmRisk("/", 5)
        assertFalse(text.contains("会话"))
        assertFalse(text.contains("始终"))
        assertFalse(text.contains("总是"))
    }
}
