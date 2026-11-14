package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PartialJsonString] — the decoder that turns a still-streaming JSON string
 * body (already cut at the first unescaped quote) into displayable text.
 *
 * Every `decode` call is a full re-decode of the prefix, so the important
 * property is monotonicity: whatever is rendered now must be a strict prefix
 * of what will be rendered once the value completes, never a half escape and
 * never a lone surrogate.
 */
class PartialJsonStringTest {

    // ---- helpers -----------------------------------------------------------

    private val bs = "\\"

    /** A single JSON `\uXXXX` escape as source characters. */
    private fun u(hex: String) = bs + "u" + hex

    private val replacement = '\uFFFD'

    private fun decode(s: String) = PartialJsonString.decode(s)

    /** No unpaired UTF-16 surrogate may ever reach the UI. */
    private fun assertNoLoneSurrogate(out: String, label: String) {
        var i = 0
        while (i < out.length) {
            val c = out[i]
            if (c.isHighSurrogate()) {
                assertTrue(
                    "$label: unpaired high surrogate at $i in ${dump(out)}",
                    i + 1 < out.length && out[i + 1].isLowSurrogate(),
                )
                i += 2
            } else {
                assertTrue("$label: unpaired low surrogate at $i in ${dump(out)}", !c.isLowSurrogate())
                i++
            }
        }
    }

    private fun dump(s: String): String =
        s.map { c ->
            when {
                c == '\\' -> "\\\\"
                c == '\n' -> "\\n"
                c == '\r' -> "\\r"
                c == '\t' -> "\\t"
                c < ' ' || c == replacement -> "\\u%04x".format(c.code)
                else -> c.toString()
            }
        }.joinToString("")

    /** Runs [check] for every prefix length of [source]. */
    private fun everyPrefix(source: String, label: String, check: (prefix: String, decoded: String, len: Int) -> Unit) {
        for (len in 0..source.length) {
            check(source.substring(0, len), decode(source.substring(0, len)), len)
        }
    }

    // ---- tests -------------------------------------------------------------

    @Test
    fun emptyAndPlainTextPassThrough() {
        assertEquals("", decode(""))
        assertEquals("中文标题", decode("中文标题"))
        assertEquals("plain ASCII title", decode("plain ASCII title"))
        assertEquals("emoji 😀 标题", decode("emoji 😀 标题"))   // literal UTF-8, no escapes
    }

    @Test
    fun allStandardEscapesDecode() {
        val source = """\" \\ \/ \b \f \n \r \t"""
        val expected = "\"" + " " + "\\" + " " + "/" + " " + "\b" + " " +
            "\u000C" + " " + "\n" + " " + "\r" + " " + "\t"
        assertEquals(expected.length, decode(source).length)
        assertEquals(expected, decode(source))
        assertNoLoneSurrogate(decode(source), "standard escapes")

        // Same escapes buried in text, and every prefix stays renderable.
        val wrapped = "a" + source + "z"
        val full = decode(wrapped)
        everyPrefix(wrapped, "standard escapes prefix") { prefix, d, len ->
            assertNoLoneSurrogate(d, "standard prefix len=$len ($prefix)")
            assertTrue("len=$len decoded ${dump(d)} is not a prefix of ${dump(full)}", full.startsWith(d))
        }
    }

    @Test
    fun chineseUnicodeEscapesDecode() {
        val source = u("4e2d") + u("6587") + u("6807") + u("9898")
        assertEquals("中文标题", decode(source))
        assertEquals("标题", decode(u("6807") + u("9898")))
        // Uppercase hex digits are valid too.
        assertEquals("中文", decode(u("4E2D") + u("6587")))
        // A BMP escape may sit next to plain characters.
        assertEquals("文件：标题", decode("文件" + bs + "uff1a" + u("6807") + u("9898")))
    }

    @Test
    fun unicodeEscapesOfAsciiAreNormalCharacters() {
        assertEquals("\"", decode(u("0022")))
        assertEquals(bs, decode(u("005c")))
        assertEquals("/", decode(u("002f")))
        assertEquals("A", decode(u("0041")))
        // An escaped backslash followed by literal "u4e2d" must stay literal.
        assertEquals(bs + "u4e2d", decode(u("005c") + "u4e2d"))
        assertNotEquals("中", decode(u("005c") + "u4e2d"))
    }

    @Test
    fun emojiSurrogatePairDecodes() {
        assertEquals("😀", decode(u("d83d") + u("de00")))
        assertEquals("😀", decode(u("D83D") + u("DE00")))
        assertEquals("🚀 上线", decode(u("d83d") + u("de80") + " 上线"))
        assertEquals("中文😀标题", decode("中文" + u("d83d") + u("de00") + "标题"))
        assertNoLoneSurrogate(decode("中文" + u("d83d") + u("de00") + "标题"), "pair")
    }

    @Test
    fun emojiNeverRendersHalfPairAtAnyPrefix() {
        val source = "中文" + u("d83d") + u("de00") + "标题"
        val full = "中文😀标题"
        everyPrefix(source, "emoji prefix") { prefix, d, len ->
            assertNoLoneSurrogate(d, "len=$len prefix=${dump(prefix)}")
            assertTrue("len=$len: ${dump(d)} not a prefix of ${dump(full)}", full.startsWith(d))
        }
        // Boundary spot checks: high half alone, low half in progress, done.
        assertEquals("中文", decode(source.substring(0, 2 + 6)))            // \uD83D complete, no low yet
        assertEquals("中文", decode(source.substring(0, 2 + 6 + 3)))        // \uDE0 partial
        assertEquals("中文😀", decode(source.substring(0, 2 + 6 + 6)))      // low half complete
        assertEquals("中文😀标", decode(source.substring(0, 2 + 12 + 1)))
        assertEquals(full, decode(source))
    }

    @Test
    fun everyPrefixOfMixedCorpusIsPrefixOfFinal() {
        val corpus = listOf(
            "中文标题" to "plain CJK",
            "A" + u("d83d") + u("de00") + "标题" to "emoji + CJK",
            """\" \\ \/ \b \f \n \r \t""" to "all standard escapes",
            "https:" + bs + "/" + bs + "/example.com" + bs + "/a" to "escaped slashes",
            "a" + bs + bs + "u4e2d" + "b" to "literal escaped backslash before u",
            "x" + bs + bs + bs + "u4e2d" to "literal backslash + real unicode escape",
            "中" + u("4e2d") + bs + "n" + "文" to "CJK + escape + newline",
            "文件" + bs + "\"" + "标题" + bs + "n" to "escaped quote",
            "tab" + bs + "t" + "分隔" to "tab",
            "控制" + u("0008") + "字符" to "control char escape",
        )
        for ((source, label) in corpus) {
            val full = decode(source)
            assertNoLoneSurrogate(full, "full $label")
            everyPrefix(source, label) { prefix, d, len ->
                assertNoLoneSurrogate(d, "$label len=$len")
                assertTrue(
                    "$label len=$len: decoded ${dump(d)} is not a prefix of ${dump(full)}",
                    full.startsWith(d),
                )
                // Re-decoding the same buffer is deterministic.
                assertEquals("$label len=$len: decode is not deterministic", d, decode(prefix))
            }
        }
    }

    @Test
    fun escapedBackslashBeforeUIsLiteralText() {
        // Source characters: \ \ u 4 e 2 d -> literal \u4e2d, NOT 中.
        val source = bs + bs + "u4e2d"
        assertEquals(bs + "u4e2d", decode(source))
        assertNotEquals("中", decode(source))

        // Source: \ \ \ u 4 e 2 d -> literal backslash + 中.
        assertEquals(bs + "中", decode(bs + bs + bs + "u4e2d"))

        // Literal backslash then the word "university" must not be eaten.
        assertEquals(bs + "university", decode(bs + bs + "university"))

        // A naive chained .replace() would also corrupt these.
        assertEquals(bs + "n", decode(bs + bs + "n"))
        assertEquals(bs + bs + "n", decode(bs + bs + bs + bs + "n"))
        assertEquals("C:" + bs + "path" + bs + "n", decode("C:" + bs + bs + "path" + bs + bs + "n"))
    }

    @Test
    fun escapesAfterLiteralBackslashDecodeInOnePass() {
        // Source: a \ \ \ " b  -> a + literal backslash + quote + b
        val source = "a" + bs + bs + bs + "\"" + "b"
        assertEquals("a" + bs + "\"b", decode(source))
        // Source: a \\ \n b -> a + literal backslash + newline + b
        assertEquals("a" + bs + "\nb", decode("a" + bs + bs + bs + "nb"))
    }

    @Test
    fun incompleteTrailingEscapesEmitNothing() {
        assertEquals("", decode(bs))
        assertEquals("", decode(bs + "u"))
        assertEquals("", decode(bs + "u4"))
        assertEquals("", decode(bs + "u4e"))
        assertEquals("", decode(bs + "u4e2"))
        assertEquals("", decode(bs + "uD83D"))
        assertEquals("", decode(bs + "uD83D" + bs))
        assertEquals("", decode(bs + "uD83D" + bs + "u"))
        assertEquals("", decode(bs + "uD83D" + bs + "uDE0"))

        // The already-complete part is kept; only the tail is held back.
        assertEquals("中文", decode("中文" + bs))
        assertEquals("中文", decode("中文" + bs + "u4e2"))
        assertEquals("abc", decode("abc" + bs))
        assertEquals("中文😀", decode(u("4e2d") + u("6587") + u("d83d") + u("de00") + bs + "u4e2"))
    }

    @Test
    fun malformedHexEscapeBecomesReplacementChar() {
        assertEquals("a" + replacement + "b", decode("a" + u("12G4") + "b"))
        assertEquals(replacement.toString(), decode(u("ZZZZ")))
        // A half-streamed escape is pending, not malformed: still nothing emitted.
        assertEquals("a", decode("a" + u("12G")))
        // Malformed low half after a high half collapses to one replacement char.
        assertEquals(replacement.toString(), decode(u("d83d") + u("12G4")))
        // Four hex-digit characters that are only partly hex never leak as text.
        val out = decode(u("4e2d") + u("4e2"))
        assertEquals("中", out)
        assertNoLoneSurrogate(out, "malformed tail")
    }

    @Test
    fun unknownEscapesKeepTheCharacter() {
        assertEquals("aqb", decode("a" + bs + "q" + "b"))
        assertEquals("aU4e2d", decode("a" + bs + "U4e2d"))
        assertEquals("a" + bs + "x", decode("a" + bs + bs + "x"))
        // Unknown escape at the very end of the buffer is complete, so it shows.
        assertEquals("q", decode(bs + "q"))
    }

    @Test
    fun loneSurrogatesBecomeReplacementChar() {
        assertEquals(replacement.toString() + "X", decode(u("d83d") + "X"))
        assertEquals(replacement.toString(), decode(u("de00")))
        assertEquals(replacement.toString() + "\n", decode(u("d83d") + bs + "n"))
        assertEquals(replacement.toString() + "A", decode(u("d83d") + u("0041")))
        assertEquals(replacement.toString() + "😀", decode(u("d83d") + u("d83d") + u("de00")))
        assertEquals(replacement.toString() + replacement, decode(u("de00") + u("de00")))
        assertNoLoneSurrogate(decode(u("d83d") + "X"), "lone high")
        assertNoLoneSurrogate(decode(u("de00")), "lone low")
    }

    @Test
    fun crossChunkReDecodeMatchesWholeBuffer() {
        // A realistic streamed tool argument body: CJK title, escaped quote,
        // newline, emoji pair, literal escaped backslash before "u", tab.
        val source = "中文标题" + bs + "\"" + "内容" + bs + "n" +
            "emoji:" + u("d83d") + u("de00") + bs + "t" +
            bs + bs + "u4e2d" + "完成"
        val full = decode(source)
        assertEquals("中文标题\"内容\nemoji:😀\t" + bs + "u4e2d完成", full)

        for (chunk in 1..9) {
            val buffer = StringBuilder()
            var at = 0
            while (at < source.length) {
                buffer.append(source, at, minOf(at + chunk, source.length))
                at += chunk
                val d = decode(buffer.toString())
                assertNoLoneSurrogate(d, "chunk=$chunk at=$at")
                assertTrue(
                    "chunk=$chunk at=$at: ${dump(d)} not a prefix of ${dump(full)}",
                    full.startsWith(d),
                )
            }
            assertEquals("chunk=$chunk final buffer", full, decode(buffer.toString()))
        }
    }

    @Test
    fun largeStreamedTitleDecodesCorrectly() {
        val n = 20_000
        val source = StringBuilder(n * 7)
        val expected = StringBuilder(n * 2)
        repeat(n) {
            source.append(u("6807"))   // 标
            source.append('a')
            expected.append('标').append('a')
        }
        val started = System.nanoTime()
        val out = decode(source.toString())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(expected.toString(), out)
        assertTrue("decode of ${source.length} chars took ${elapsedMs}ms", elapsedMs < 5_000)
    }
}
