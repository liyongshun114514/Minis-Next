package com.openminis.app.provider.openai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponsesToolCallAssemblerTest {
    private fun item(id: String = "fc1", call: String = "c1", args: String? = null) =
        JSONObject().put("type", "function_call").put("id", id).put("call_id", call).put("name", "shell_execute")
            .apply { if (args != null) put("arguments", args) }
    private fun event(item: JSONObject, index: Int = 0) = JSONObject().put("item", item).put("output_index", index)
    private fun arg(id: String = "fc1", text: String, snapshot: Boolean = false) = JSONObject()
        .put("item_id", id).put(if (snapshot) "arguments" else "delta", text)

    @Test fun `deltas followed by missing empty null or blank snapshot keep arguments`() {
        for (empty in listOf(null, "", " ", JSONObject.NULL)) {
            val a = ResponsesToolCallAssembler()
            a.added(event(item()))
            a.arguments(arg(text = "{\"command\":"), false)
            a.arguments(arg(text = "\"pwd\"}"), false)
            val done = item().apply { if (empty != null) put("arguments", empty) }
            val result = a.done(event(done))!!.args.finish()
            assertEquals("pwd", result.args.getString("command"))
            assertNull(result.error)
        }
    }
    @Test fun `arguments done only is retained`() {
        val a = ResponsesToolCallAssembler(); a.added(event(item()))
        a.arguments(arg(text = "{\"command\":\"pwd\"}", snapshot = true), true)
        assertEquals("pwd", a.done(event(item()))!!.args.finish().args.getString("command"))
    }
    @Test fun `item done authoritative snapshot wins`() {
        val a = ResponsesToolCallAssembler(); a.added(event(item()))
        a.arguments(arg(text = "{\"command\":\"old\"}"), false)
        assertEquals("new", a.done(event(item(args = "{\"command\":\"new\"}")))!!.args.finish().args.getString("command"))
    }
    @Test fun `parallel calls route using output index without matching item id`() {
        val a = ResponsesToolCallAssembler(); a.added(event(item(), 2)); a.added(event(item("fc2", "c2"), 5))
        a.arguments(arg("wrong1", "{\"n\":1}").put("output_index", 2), false)
        a.arguments(arg("wrong2", "{\"n\":2}").put("output_index", 5), false)
        assertEquals(1, a.done(event(item(), 2))!!.args.finish().args.getInt("n"))
        assertEquals(2, a.done(event(item("fc2", "c2"), 5))!!.args.finish().args.getInt("n"))
    }
    @Test fun `call id alias routes arguments`() {
        val a = ResponsesToolCallAssembler(); a.added(event(item()))
        a.arguments(arg("c1", "{\"n\":1}"), false)
        assertEquals(1, a.done(event(item()))!!.args.finish().args.getInt("n"))
    }
    @Test fun `missing identity resolves only a sole candidate`() {
        val a = ResponsesToolCallAssembler(); a.added(event(item()))
        assertNotNull(a.arguments(JSONObject().put("delta", "{}"), false))
        a.added(event(item("fc2", "c2"), 1))
        assertNull(a.arguments(JSONObject().put("delta", "{}"), false))
        assertNotNull(a.associationError)
    }
    @Test fun `metadata arriving only at done does not discard deltas`() {
        val a = ResponsesToolCallAssembler(); a.added(event(item().apply { remove("call_id"); remove("name") }))
        a.arguments(arg(text = "{\"n\":1}"), false)
        assertEquals(1, a.done(event(item()))!!.args.finish().args.getInt("n"))
    }
    @Test fun `done without added emits a call`() {
        val a = ResponsesToolCallAssembler()
        assertEquals(1, a.done(event(item(args = "{\"n\":1}")))!!.args.finish().args.getInt("n"))
    }
    @Test fun `completed output only emits a call`() {
        val a = ResponsesToolCallAssembler()
        val done = a.flush(JSONObject().put("output", JSONArray().put(item(args = "{\"n\":1}"))))
        assertEquals(1, done.single().args.finish().args.getInt("n"))
        assertTrue(a.sawCalls)
        assertTrue(a.flush(null).isEmpty())
    }
    @Test fun `completed snapshot fills pending call`() {
        val a = ResponsesToolCallAssembler(); a.added(event(item()))
        a.arguments(arg(text = "{\"n\":"), false)
        assertEquals(1, a.flush(JSONObject().put("output", JSONArray().put(item(args = "{\"n\":1}"))))
            .single().args.finish().args.getInt("n"))
    }
    @Test fun `repeated done added and completed do not duplicate call`() {
        val a = ResponsesToolCallAssembler(); a.added(event(item()))
        assertNotNull(a.done(event(item(args = "{}"))))
        assertNull(a.done(event(item(args = "{}"))))
        assertNull(a.added(event(item())))
        assertTrue(a.flush(JSONObject().put("output", JSONArray().put(item(args = "{}")))).isEmpty())
    }
    @Test fun `stream end preserves malformed raw data as explicit error`() {
        val a = ResponsesToolCallAssembler(); a.added(event(item()))
        a.arguments(arg(text = "{\"command\":\"cut"), false)
        val result = a.flush(null).single().args.finish()
        assertEquals("{\"command\":\"cut", result.raw)
        assertNotNull(result.error)
        assertEquals(0, result.args.length())
    }
}
