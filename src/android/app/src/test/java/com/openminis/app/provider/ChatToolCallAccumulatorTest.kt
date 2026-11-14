package com.openminis.app.provider

import com.openminis.app.provider.openai.ChatToolCallAccumulator
import org.junit.Assert.*
import org.junit.Test

class ChatToolCallAccumulatorTest {
    @Test fun `reused index never replaces a published identity or mixes arguments`() {
        val a = ChatToolCallAccumulator()
        val read = a.append(0, "A", "file_read", "{\"path\":\"/a\"}")
        read.started = true
        val shell = a.append(0, "B", "shell_execute", "{\"command\":\"pwd\"}")
        assertNotSame(read, shell)
        assertEquals("A", read.id)
        assertEquals("file_read", read.name)
        assertEquals("{\"path\":\"/a\"}", read.args.toString())
        assertEquals(listOf("A", "B"), a.calls.map { it.id })
        assertEquals("{\"command\":\"pwd\"}", shell.args.toString())
        assertFalse(shell.started)
    }
    @Test fun `id routes fragments when relay omits or reuses index`() {
        val a = ChatToolCallAccumulator()
        a.append(0, "A", "file_read", "{\"path\":")
        a.append(0, "B", "shell_execute", "{\"command\":")
        a.append(0, "A", "", "\"/a\"}")
        a.append(0, "B", "", "\"pwd\"}")
        assertEquals("{\"path\":\"/a\"}", a.calls[0].args.toString())
        assertEquals("{\"command\":\"pwd\"}", a.calls[1].args.toString())
    }
    @Test fun `normal indexed parallel fragments and repeated raw ids remain separate`() {
        val a = ChatToolCallAccumulator()
        a.append(1, "same", "file_read", "{\"path\":")
        a.append(0, "same", "file_read", "{\"path\":")
        a.append(1, "", "", "\"/a\"}")
        a.append(0, "", "", "\"/b\"}")
        assertEquals(2, a.calls.size)
        assertEquals("{\"path\":\"/a\"}", a.calls[0].args.toString())
        assertEquals("{\"path\":\"/b\"}", a.calls[1].args.toString())
    }
    @Test fun `id arriving after arguments preserves the provisional call`() {
        val a = ChatToolCallAccumulator()
        val provisional = a.append(2, "", "shell_execute", "{\"command\":")
        val identified = a.append(2, "late", "", "\"pwd\"}")
        assertSame(provisional, identified)
        assertEquals(1, a.calls.size)
        assertEquals("late", identified.id)
        assertEquals("{\"command\":\"pwd\"}", identified.args.toString())
    }
    @Test fun `growing object snapshots are not concatenated or double executed`() {
        val a = ChatToolCallAccumulator()
        val x = a.append(0, "A", "file_read", "{\"path\":")
        x.started = true
        assertSame(x, a.append(0, "A", "file_read", "{\"path\":\"/a\"}"))
        a.append(0, "A", "file_read", "{\"path\":\"/a\"}")
        assertEquals(1, a.calls.size)
        assertTrue(x.started)
        assertEquals("{\"path\":\"/a\"}", x.args.toString())
    }
    @Test fun `literal repeated fragments are never overlap trimmed`() {
        val a = ChatToolCallAccumulator()
        a.append(0, "A", "file_write", "{\"content\":\"")
        repeat(3) { a.append(0, "", "", "aa") }
        a.append(0, "", "", "\"}")
        assertEquals("{\"content\":\"aaaaaa\"}", a.calls.single().args.toString())
    }
    @Test fun `conflicting name starts separate call without mutating previous start`() {
        val a = ChatToolCallAccumulator()
        val first = a.append(0, "A", "file_read", "{}")
        first.started = true
        val second = a.append(0, "A", "shell_execute", "{}")
        assertNotSame(first, second)
        assertEquals("file_read", first.name)
        assertFalse(second.started)
    }
}
