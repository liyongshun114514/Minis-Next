package com.openminis.app.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused JVM tests for [ToolArgumentBuffer] — no Gradle, no Android runtime.
 *
 * Contract under test:
 *  - streaming fragments are kept verbatim and only parsed in [ToolArgumentBuffer.finish];
 *  - a non-blank completion snapshot is authoritative and replaces prior fragments;
 *  - null / empty / whitespace-only snapshots never overwrite accumulated text;
 *  - a double-encoded arguments string is decoded exactly ONE extra layer;
 *  - malformed, truncated or trailing-garbage JSON is rejected with the raw wire text kept;
 *  - a real `{}` and an empty/blank buffer are legal zero-argument calls;
 *  - the buffer never auto-closes and never invents missing JSON.
 */
class ToolArgumentBufferTest {

    private fun bufferOf(vararg fragments: String): ToolArgumentBuffer =
        ToolArgumentBuffer().apply { fragments.forEach { append(it) } }

    // ---- streaming / fragments -------------------------------------------------

    @Test
    fun `fragments accumulate verbatim and parse only at finish`() {
        val buffer = bufferOf("{\"path\":\"", "a b")
        assertEquals("{\"path\":\"a b", buffer.toString())
        assertTrue(buffer.isNotEmpty())
        assertEquals("{\"path\":\"a b".length, buffer.length)

        // Still streaming: nothing is parsed yet, so finish() sees a truncated doc.
        assertNotNull(buffer.finish().error)

        buffer.append("\",\"timeout\":30}")
        val result = buffer.finish()
        assertNull(result.error)
        assertEquals("a b", result.args.getString("path"))
        assertEquals(30, result.args.getInt("timeout"))
        assertEquals("{\"path\":\"a b\",\"timeout\":30}", result.raw)
    }

    @Test
    fun `fragments combine into one valid object`() {
        val buffer = bufferOf("{\"path\":\"", ".", "\"}")
        val result = buffer.finish()

        assertNull(result.error)
        assertEquals(".", result.args.getString("path"))
        assertEquals(1, result.args.length())
        assertEquals("{\"path\":\".\"}", result.raw)
        assertEquals("{\"path\":\".\"}", buffer.toString())
    }

    @Test
    fun `append after finish keeps the previous raw text`() {
        val buffer = bufferOf("{\"a\":1}")
        assertNull(buffer.finish().error)
        buffer.append("")
        assertEquals("{\"a\":1}", buffer.toString())
        val again = buffer.finish()
        assertNull(again.error)
        assertEquals(1, again.args.getInt("a"))
    }

    // ---- snapshot authority ----------------------------------------------------

    @Test
    fun `non blank snapshot replaces accumulated fragments`() {
        val buffer = bufferOf("{\"partial\":true,")
        buffer.snapshot("{\"choice\":\"snapshot\"}")

        assertEquals("{\"choice\":\"snapshot\"}", buffer.toString())
        assertEquals(21, buffer.length)

        val result = buffer.finish()
        assertNull(result.error)
        assertEquals("snapshot", result.args.getString("choice"))
        assertFalse(result.args.has("partial"))
        assertEquals("{\"choice\":\"snapshot\"}", result.raw)
    }

    @Test
    fun `snapshot can follow a completed fragment stream and wins`() {
        val buffer = bufferOf("{\"a\":1}")
        buffer.snapshot("{}")
        assertEquals("{}", buffer.toString())
        val result = buffer.finish()
        assertNull(result.error)
        assertEquals(0, result.args.length())
        assertEquals("{}", result.raw)
    }

    @Test
    fun `null empty and whitespace snapshots never overwrite`() {
        val buffer = bufferOf("{\"a\":1}")

        buffer.snapshot(null)
        assertEquals("{\"a\":1}", buffer.toString())
        buffer.snapshot("")
        assertEquals("{\"a\":1}", buffer.toString())
        buffer.snapshot("   ")
        assertEquals("{\"a\":1}", buffer.toString())
        buffer.snapshot("\n\t \r")
        assertEquals("{\"a\":1}", buffer.toString())
        assertEquals(7, buffer.length)

        val result = buffer.finish()
        assertNull(result.error)
        assertEquals(1, result.args.getInt("a"))
        assertEquals("{\"a\":1}", result.raw)
    }

    @Test
    fun `blank snapshots alone leave a legal no argument call`() {
        val buffer = ToolArgumentBuffer()
        buffer.snapshot(null)
        buffer.snapshot("")
        buffer.snapshot("  \n")

        assertEquals("", buffer.toString())
        assertEquals(0, buffer.length)
        assertFalse(buffer.isNotEmpty())

        val result = buffer.finish()
        assertNull(result.error)
        assertEquals(0, result.args.length())
        assertEquals("", result.raw)
    }

    // ---- double encoding -------------------------------------------------------

    @Test
    fun `double encoded arguments decode exactly one layer`() {
        // Wire text is a JSON string whose content is the real arguments object.
        val raw = "\"{\\\"command\\\":\\\"printf hi\\\"}\""
        val buffer = bufferOf(raw)

        val result = buffer.finish()
        assertNull(result.error)
        assertEquals("printf hi", result.args.getString("command"))
        assertEquals(1, result.args.length())
        // raw stays exactly what the provider sent, not the unwrapped value.
        assertEquals(raw, result.raw)
        assertEquals(raw, buffer.toString())
    }

    @Test
    fun `double encoded with surrounding whitespace still decodes one layer`() {
        val result = bufferOf("  \"{\\\"a\\\":2}\"  ").finish()
        assertNull(result.error)
        assertEquals(2, result.args.getInt("a"))
        assertEquals("  \"{\\\"a\\\":2}\"  ", result.raw)
    }

    @Test
    fun `triple encoded arguments are rejected instead of decoded twice`() {
        val raw = "\"\\\"{\\\\\\\"a\\\\\\\":1}\\\"\""
        val result = bufferOf(raw).finish()

        assertNotNull(result.error)
        assertEquals(0, result.args.length())
        assertEquals(raw, result.raw)
        assertTrue(result.error!!.contains("non-object"))
    }

    // ---- non-object values -----------------------------------------------------

    @Test
    fun `json array is rejected as a non object value`() {
        val result = bufferOf("[1,2,3]").finish()

        assertNotNull(result.error)
        assertEquals(0, result.args.length())
        assertEquals("[1,2,3]", result.raw)
        assertTrue(result.error!!.contains("non-object"))
    }

    @Test
    fun `json scalar is rejected as a non object value`() {
        val number = bufferOf("42").finish()
        assertNotNull(number.error)
        assertEquals(0, number.args.length())
        assertEquals("42", number.raw)
        assertTrue(number.error!!.contains("non-object"))

        val bool = bufferOf("true").finish()
        assertNotNull(bool.error)
        assertEquals("true", bool.raw)
    }

    // ---- malformed inputs ------------------------------------------------------

    @Test
    fun `trailing garbage after an object is rejected and raw is preserved`() {
        val raw = "{\"a\":1} not json"
        val result = bufferOf(raw).finish()

        assertNotNull(result.error)
        assertEquals(0, result.args.length())
        assertEquals(raw, result.raw)
        assertTrue(result.error!!.contains("malformed"))
    }

    @Test
    fun `two concatenated objects are rejected as trailing data`() {
        val raw = "{\"a\":1}{\"b\":2}"
        val result = bufferOf(raw).finish()

        assertNotNull(result.error)
        assertEquals(0, result.args.length())
        assertEquals(raw, result.raw)
    }

    @Test
    fun `truncated json is an error and is never auto closed`() {
        for (raw in listOf("{\"a\":", "{\"a\":1", "{\"a\":\"unterminated", "{", "{\"a\":[1,2")) {
            val buffer = bufferOf(raw)
            val result = buffer.finish()

            assertNotNull("expected an error for $raw", result.error)
            assertEquals("args must stay empty for $raw", 0, result.args.length())
            assertEquals("raw must be preserved for $raw", raw, result.raw)
            assertEquals("buffer must not be mutated for $raw", raw, buffer.toString())
            assertTrue(
                "error should mention malformed/incomplete for $raw, was ${result.error}",
                result.error!!.contains("malformed") || result.error!!.contains("incomplete"),
            )
            assertFalse("must not auto-close $raw", buffer.toString().endsWith("}"))
        }
    }

    @Test
    fun `unterminated string is rejected`() {
        val raw = "{\"path\":\"/tmp"
        val result = bufferOf(raw).finish()
        assertNotNull(result.error)
        assertEquals(raw, result.raw)
        assertEquals(0, result.args.length())
    }

    // ---- legal zero-argument calls ---------------------------------------------

    @Test
    fun `literal empty object is a valid zero argument call`() {
        val buffer = bufferOf("{}")
        val result = buffer.finish()

        assertNull(result.error)
        assertEquals(0, result.args.length())
        assertTrue(result.args.isEmpty())
        assertEquals("{}", result.raw)
        assertEquals("{}", buffer.toString())
    }

    @Test
    fun `empty buffer is a valid zero argument call`() {
        val result = ToolArgumentBuffer().finish()
        assertNull(result.error)
        assertEquals(0, result.args.length())
        assertEquals("", result.raw)
    }

    @Test
    fun `whitespace only buffer is a valid zero argument call`() {
        val raw = "  \n\t "
        val result = bufferOf(raw).finish()
        assertNull(result.error)
        assertEquals(0, result.args.length())
        assertEquals(raw, result.raw)
    }

    @Test
    fun `finish is repeatable and does not invent missing fields`() {
        val buffer = bufferOf("{\"path\":\".\"}")
        val first = buffer.finish()
        val second = buffer.finish()

        assertNull(first.error)
        assertNull(second.error)
        assertEquals(first.raw, second.raw)
        assertEquals(first.args.toString(), second.args.toString())
        assertEquals(1, first.args.length())
        assertFalse(first.args.has("command"))
        assertFalse(first.args.has("arguments"))
        assertNull(first.args.opt("command"))
    }
}
