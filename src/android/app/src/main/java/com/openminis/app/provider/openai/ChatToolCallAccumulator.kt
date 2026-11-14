package com.openminis.app.provider.openai

/** Per-response tool identity. Explicit IDs survive relays reusing/omitting index.
 * An already published start must never be completed under a different identity.
 * This does not deduplicate independent calls by name or arguments.
 */
internal class ChatToolCallAccumulator {
    internal class Call {
        var id = ""
        var name = ""
        val args = StringBuilder()
        var started = false
    }
    private val byIndex = mutableMapOf<Int, Call>()
    private val byId = mutableMapOf<String, Call>()
    private val ordered = mutableListOf<Call>()
    val calls: List<Call> get() = ordered

    fun append(index: Int, id: String, name: String, arguments: String): Call {
        val indexed = byIndex[index]
        val call = when {
            id.isNotEmpty() && indexed != null && (indexed.id == id || indexed.id.isEmpty()) -> indexed
            id.isNotEmpty() && indexed != null && byId[id] != null -> byId.getValue(id)
            id.isNotEmpty() -> Call().also { ordered.add(it) }
            indexed != null -> indexed
            else -> Call().also { ordered.add(it) }
        }
        // Once an id/name has been published, a conflicting identity is a
        // separate call. Some relays reuse an index while switching calls;
        // merging it would turn file_read into shell_execute and pair the
        // result with the wrong card. The VM's existing id dedupe handles the
        // rare case where the relay also reuses the raw id.
        if (call.name.isNotEmpty() && name.isNotEmpty() && call.name != name) {
            val replacement = Call().also { ordered.add(it) }
            if (id.isNotEmpty()) byId[id] = replacement
            byIndex[index] = replacement
            return appendTo(replacement, id, name, arguments)
        }
        byIndex[index] = call
        return appendTo(call, id, name, arguments)
    }

    private fun appendTo(call: Call, id: String, name: String, arguments: String): Call {
        if (id.isNotEmpty()) {
            if (call.id.isNotEmpty() && call.id != id) return call
            call.id = id
            byId[id] = call
        }
        if (name.isNotEmpty()) call.name = name
        // Some relays send growing JSON snapshots instead of delta fragments.
        // Replace a complete prior snapshot rather than concatenating it.
        val previous = call.args.toString()
        if (id.isNotEmpty() && name.isNotEmpty() && previous.isNotEmpty() &&
            arguments.startsWith("{") && arguments.startsWith(previous) &&
            runCatching { org.json.JSONObject(arguments) }.isSuccess) {
            call.args.setLength(0)
        }
        call.args.append(arguments)
        return call
    }
}
