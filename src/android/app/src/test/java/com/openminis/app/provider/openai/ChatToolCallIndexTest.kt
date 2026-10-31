package com.openminis.app.provider.openai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatToolCallIndexTest {
    @Test fun `explicit index wins over array position`() {
        val a = ChatToolCallIndex()
        assertEquals(4, a.resolve(JSONObject().put("index", 4).put("id", "a"), 0, 2))
        assertEquals(1, a.resolve(JSONObject().put("index", 1).put("id", "b"), 1, 2))
    }
    @Test fun `indexless full parallel array uses distinct positions`() {
        val a = ChatToolCallIndex()
        assertEquals(0, a.resolve(JSONObject().put("id", "a"), 0, 2))
        assertEquals(1, a.resolve(JSONObject().put("id", "b"), 1, 2))
    }
    @Test fun `indexless distinct ids in separate events never merge`() {
        val a = ChatToolCallIndex()
        assertEquals(0, a.resolve(JSONObject().put("id", "a"), 0, 1))
        assertEquals(1, a.resolve(JSONObject().put("id", "b"), 0, 1))
        assertEquals(0, a.resolve(JSONObject().put("id", "a"), 0, 1))
        assertEquals(1, a.resolve(JSONObject().put("id", "b"), 0, 1))
    }
    @Test fun `empty ids with explicit index preserve parallel routing`() {
        val a = ChatToolCallIndex()
        assertEquals(2, a.resolve(JSONObject().put("index", 2).put("id", "a"), 0, 1))
        assertEquals(2, a.resolve(JSONObject().put("index", 2).put("id", ""), 0, 1))
    }
    @Test fun `single call can omit all routing fields on continuation`() {
        val a = ChatToolCallIndex()
        assertEquals(0, a.resolve(JSONObject().put("id", "a"), 0, 1))
        assertEquals(0, a.resolve(JSONObject(), 0, 1))
        assertFalse(a.ambiguous)
    }
    @Test fun `unkeyed singleton delta with parallel candidates is rejected`() {
        val a = ChatToolCallIndex()
        a.resolve(JSONObject().put("index", 0).put("id", "a"), 0, 1)
        a.resolve(JSONObject().put("index", 1).put("id", "b"), 0, 1)
        assertNull(a.resolve(JSONObject(), 0, 1))
        assertTrue(a.ambiguous)
    }
    @Test fun `late id inherits an anonymous slot`() {
        val a = ChatToolCallIndex()
        assertEquals(0, a.resolve(JSONObject(), 0, 1))
        assertEquals(0, a.resolve(JSONObject().put("id", "late"), 0, 1))
        assertEquals(0, a.resolve(JSONObject().put("id", "late"), 0, 1))
    }
    @Test fun `null index is absent rather than zero`() {
        val a = ChatToolCallIndex()
        a.resolve(JSONObject().put("index", 0).put("id", "a"), 0, 1)
        assertEquals(1, a.resolve(JSONObject().put("index", JSONObject.NULL).put("id", "b"), 0, 1))
    }
}
