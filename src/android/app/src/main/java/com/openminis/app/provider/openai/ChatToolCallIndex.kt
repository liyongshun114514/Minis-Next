package com.openminis.app.provider.openai

import com.openminis.app.provider.safeOptString
import org.json.JSONObject

/** Never defaults every index-less parallel tool call to slot zero. */
internal class ChatToolCallIndex {
    private val ids = linkedMapOf<Int, String>()
    var ambiguous = false
        private set

    fun resolve(call: JSONObject, position: Int, count: Int): Int? {
        val id = call.safeOptString("id")
        val explicit = if (call.has("index") && !call.isNull("index")) call.optInt("index", -1).takeIf { it >= 0 } else null
        val known = ids.entries.firstOrNull { id.isNotBlank() && it.value == id }?.key
        val slot = when {
            explicit != null -> explicit
            known != null -> known
            id.isNotBlank() -> if (position !in ids || ids[position].isNullOrBlank()) position else (ids.keys.maxOrNull() ?: -1) + 1
            count > 1 -> position // an index-less full array is position-keyed
            ids.size <= 1 -> ids.keys.singleOrNull() ?: 0
            else -> { ambiguous = true; return null }
        }
        if (id.isNotBlank()) ids[slot] = id else ids.putIfAbsent(slot, "")
        return slot
    }
}
