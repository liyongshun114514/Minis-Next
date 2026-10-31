package com.openminis.app.ui.chat

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

/** Regression: identifiers in model replies must not become distant italic pairs. */
class MarkdownUnderscoreTest {
    private fun plain(raw: String) = parseInlineForTest(raw).text.replace("\u2006", "")
    private fun noItalic(raw: String) {
        val result = parseInlineForTest(raw)
        assertEquals(raw, raw, result.text.replace("\u2006", ""))
        assertFalse(raw, result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test fun `screenshot shell execute and tool title retain underscores and upright prose`() {
        val raw = "调用 shell_execute 执行 `pwd`，提供完整的 command 和 tool_title。"
        assertFalse(parseInlineForTest(raw).spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertEquals("调用 shell_execute 执行 pwd，提供完整的 command 和 tool_title。", plain(raw))
    }
    @Test fun `snake case identifiers paths and environment names stay literal`() {
        for (raw in listOf("shell_execute", "tool_title", "max_output_tokens", "temperature / top_p",
            "ANDROID_SDK_ROOT", "foo_bar_baz", "v1_2_3", "/tmp/my_file_name.txt",
            "foo__bar__baz", "中文_变量_名称", "a_b 和 c_d", "a__b 与 c__d")) noItalic(raw)
    }
    @Test fun `every streaming prefix of identifiers remains literal`() {
        val full = "调用 shell_execute 执行 pwd，提供 command 和 tool_title。下一项 max_output_tokens。"
        for (n in 1..full.length) noItalic(full.take(n))
    }
    @Test fun `standalone underscore italic still works for latin and Chinese`() {
        for (word in listOf("italic", "斜体", "two words")) {
            val result = parseInlineForTest("_${word}_")
            assertEquals(word, result.text)
            assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic && it.start == 0 && it.end == word.length })
        }
    }
    @Test fun `underscore emphasis can be surrounded by Unicode punctuation`() {
        assertEquals("（斜体），正常", plain("（_斜体_），正常"))
        assertEquals("“italic”", plain("“_italic_”"))
    }
    @Test fun `closing underscore inside identifier does not prematurely close emphasis`() {
        val result = parseInlineForTest("_use tool_title here_ normal")
        assertEquals("use tool_title here normal", result.text)
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic && it.end == "use tool_title here".length })
    }
    @Test fun `escaped and code underscores do not close outer emphasis`() {
        assertEquals("a _ b", plain("_a \\_ b_"))
        assertEquals("a _ b", plain("_a `_` b_"))
    }
    @Test fun `double underscore bold and triple underscore combined emphasis work`() {
        val bold = parseInlineForTest("__加粗__")
        assertEquals("加粗", bold.text)
        assertTrue(bold.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertFalse(bold.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        val both = parseInlineForTest("___强调___")
        assertEquals("强调", both.text)
        assertTrue(both.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == FontStyle.Italic })
    }
    @Test fun `invalid opening and closing whitespace stays literal`() {
        for (raw in listOf("_ text_", "_text _", "_\ttext_", "_text\t_", "a_b_", "_a_b", "a__b__")) noItalic(raw)
    }
    @Test fun `unmatched and long underscore runs stay literal`() {
        for (raw in listOf("_pending", "__pending", "___pending", "____text____", "shell_execute then foo__init__bar")) noItalic(raw)
    }
    @Test fun `star and HTML italic remain supported`() {
        for (raw in listOf("*斜体*", "<i>斜体</i>", "<em>斜体</em>")) {
            val result = parseInlineForTest(raw)
            assertEquals("斜体", result.text)
            assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        }
    }
    @Test fun `bold wrapper preserves identifiers`() {
        val result = parseInlineForTest("**shell_execute 和 tool_title**")
        assertEquals("shell_execute 和 tool_title", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertFalse(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }
    @Test fun `multiline underscore emphasis cannot be split before closing marker`() {
        val raw = "_开头\n" + "正文内容".repeat(100) + "\n结束_\n" + "z".repeat(300)
        val offset = safeInlineSplitOffset(raw)
        assertEquals(raw.indexOf("结束_") + "结束_\n".length, offset)
        val whole = parseInlineForTest(raw)
        val split = parseInlineForTest(raw.take(offset)) + parseInlineForTest(raw.drop(offset))
        assertEquals(whole, split)
    }
    @Test fun `unclosed multiline underscore emphasis keeps an early safe boundary`() {
        val raw = "前文\n_开头\n" + "正文内容\n".repeat(100) + "z".repeat(300)
        assertEquals("前文\n".length, safeInlineSplitOffset(raw))
    }
    @Test fun `literal double underscores do not pin streaming split offset`() {
        val raw = "__init__ foo__bar shell_execute\n" + "正文内容\n".repeat(100) + "z".repeat(300)
        assertTrue(safeInlineSplitOffset(raw) > raw.indexOf('\n'))
    }
}
