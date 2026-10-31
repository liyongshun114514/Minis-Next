package com.openminis.app.provider.openai

import com.openminis.app.provider.safeOptString
import org.json.JSONObject

/** Joins all Responses argument channels without executing or repairing JSON. */
internal class ResponsesToolCallAssembler {
    data class Call(
        val itemId: String,
        var callId: String = "",
        var name: String = "",
        val args: ToolArgumentBuffer = ToolArgumentBuffer(),
        var started: Boolean = false,
    )
    private val pending = linkedMapOf<String, Call>()
    private val indices = mutableMapOf<Int, String>()
    private val completed = mutableSetOf<String>()
    var sawCalls = false
        private set
    val size: Int get() = pending.size
    var associationError: String? = null
        private set

    private fun index(event: JSONObject): Int? =
        if (event.has("output_index") && !event.isNull("output_index")) event.optInt("output_index", -1).takeIf { it >= 0 } else null

    private fun find(itemId: String, outputIndex: Int?): Call? {
        pending[itemId]?.let { return it }
        pending.values.firstOrNull { itemId.isNotEmpty() && it.callId == itemId }?.let { return it }
        outputIndex?.let { indices[it] }?.let { pending[it] }?.let { return it }
        // An unkeyed event is safe to associate ONLY when there is one candidate.
        return if (itemId.isBlank() && outputIndex == null) pending.values.singleOrNull() else null
    }

    fun added(event: JSONObject): Call? {
        val item = event.optJSONObject("item") ?: return null
        if (item.safeOptString("type") != "function_call") return null
        sawCalls = true
        val itemId = item.safeOptString("id").ifBlank { item.safeOptString("call_id") }
        if (itemId.isBlank() || itemId in completed) return null
        val call = pending.getOrPut(itemId) { Call(itemId) }
        item.safeOptString("call_id").takeIf { it.isNotBlank() }?.let { call.callId = it }
        item.safeOptString("name").takeIf { it.isNotBlank() }?.let { call.name = it }
        index(event)?.let { indices[it] = itemId }
        call.args.snapshot(item.safeOptString("arguments"))
        return call
    }

    fun arguments(event: JSONObject, snapshot: Boolean): Call? {
        val call = find(event.safeOptString("item_id"), index(event)) ?: run {
            associationError = "Responses tool argument event could not be associated with a call."
            return null
        }
        if (snapshot) call.args.snapshot(event.safeOptString("arguments"))
        else call.args.append(event.safeOptString("delta"))
        return call
    }

    fun done(event: JSONObject): Call? {
        val item = event.optJSONObject("item") ?: return null
        return complete(item, index(event))
    }

    private fun complete(item: JSONObject, outputIndex: Int?): Call? {
        if (item.safeOptString("type") != "function_call") return null
        sawCalls = true
        val itemId = item.safeOptString("id").ifBlank { item.safeOptString("call_id") }
        if (itemId in completed) return null
        val existing = find(itemId, outputIndex)
        if (existing?.itemId in completed) return null
        val call = existing ?: itemId.takeIf { it.isNotBlank() }?.let { Call(it) } ?: return null
        item.safeOptString("call_id").takeIf { it.isNotBlank() }?.let { call.callId = it }
        item.safeOptString("name").takeIf { it.isNotBlank() }?.let { call.name = it }
        // Metadata sometimes appears only at completion. Keep unresolved calls
        // pending rather than losing their arguments when an early done is thin.
        if (call.callId.isBlank() || call.name.isBlank()) return null
        call.args.snapshot(item.safeOptString("arguments"))
        pending.remove(call.itemId)
        completed.add(call.itemId)
        if (itemId.isNotBlank()) completed.add(itemId)
        return call
    }

    /** Completed output can be the only source of a call, or fill missing done events. */
    fun flush(response: JSONObject?): List<Call> {
        val result = mutableListOf<Call>()
        val output = response?.optJSONArray("output")
        if (output != null) for (i in 0 until output.length()) {
            output.optJSONObject(i)?.let { complete(it, i) }?.let { result.add(it) }
        }
        val remaining = pending.values.toList()
        pending.clear()
        for (call in remaining) {
            if (call.itemId !in completed && call.callId.isNotBlank() && call.name.isNotBlank()) {
                completed.add(call.itemId)
                result.add(call)
            }
        }
        return result
    }
}
