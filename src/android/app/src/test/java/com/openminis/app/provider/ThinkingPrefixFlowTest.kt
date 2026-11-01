package com.openminis.app.provider

import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThinkingPrefixFlowTest {
    private fun split(vararg events: LLMStreamChunk) = runBlocking {
        events.asFlow().splitThinkingPrefix().toList()
    }
    private fun body(events: List<LLMStreamChunk>) = events.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }
    private fun thought(events: List<LLMStreamChunk>) = events.filterIsInstance<LLMStreamChunk.ThinkingDelta>().joinToString("") { it.text }

    @Test fun `reported thinking tag is reasoning across every single split`() {
        val text = "<thinking>Reversing snapshot fadeAdding source hold enterEnsuring closed final zero before coroutine finish</thinking>Answer"
        for (cut in 0..text.length) {
            val out = split(LLMStreamChunk.Text(text.take(cut)), LLMStreamChunk.Text(text.drop(cut)), LLMStreamChunk.Finished("stop"))
            assertEquals("split=$cut", "Answer", body(out))
            assertEquals("Reversing snapshot fadeAdding source hold enterEnsuring closed final zero before coroutine finish", thought(out))
            assertTrue(out.last() is LLMStreamChunk.Finished)
        }
    }
    @Test fun `one character deltas and native thinking can coexist`() {
        val text = "  <thinking>relay thought</thinking>Body"
        val events = listOf(LLMStreamChunk.ThinkingDelta("native ")) + text.map { LLMStreamChunk.Text(it.toString()) }
        val out = split(*events.toTypedArray())
        assertEquals("native relay thought", thought(out))
        assertEquals("Body", body(out))
    }
    @Test fun `ordinary tag examples in prose and code are not swallowed`() {
        for (text in listOf("Use <thinking>x</thinking> here", "```xml\n<thinking>example</thinking>\n```")) {
            val out = split(LLMStreamChunk.Text(text))
            assertEquals(text, body(out))
            assertEquals("", thought(out))
        }
    }
    @Test fun `incomplete thinking remains thinking at EOF and finished`() {
        for (end in listOf(emptyList(), listOf(LLMStreamChunk.Finished("length")))) {
            val out = split(*(listOf(LLMStreamChunk.Text("<thinking>partial</thi")) + end).toTypedArray())
            assertEquals("", body(out))
            assertEquals("partial</thi", thought(out))
        }
    }
    @Test fun `tool events and native reasoning retain ordering`() {
        val start = LLMStreamChunk.ToolUseStart("t", "shell_execute")
        val out = split(LLMStreamChunk.Started, LLMStreamChunk.Text("<thinking>plan</thinking>"), start, LLMStreamChunk.ReasoningContent("opaque"), LLMStreamChunk.Finished("tool_calls"))
        assertEquals(listOf(LLMStreamChunk.Started, LLMStreamChunk.ThinkingDelta("plan"), start, LLMStreamChunk.ReasoningContent("opaque"), LLMStreamChunk.Finished("tool_calls")), out)
    }
    @Test fun `plain markdown whitespace remains byte identical`() {
        val text = "  indented  \nline two  \n"
        assertEquals(text, body(split(*text.map { LLMStreamChunk.Text(it.toString()) }.toTypedArray())))
    }
    @Test fun `delimiter whitespace may arrive after the closing tag chunk`() {
        assertEquals("Answer", body(split(LLMStreamChunk.Text("<thinking>plan</thinking>"), LLMStreamChunk.Text("\n\n"), LLMStreamChunk.Text("Answer"))))
    }
    @Test fun `parser lifetime is one request not the long session`() {
        repeat(200) {
            assertEquals("normal", body(split(LLMStreamChunk.Text("normal"))))
            assertEquals("plan", thought(split(LLMStreamChunk.Text("<thinking>plan</thinking>"))))
        }
    }
}
