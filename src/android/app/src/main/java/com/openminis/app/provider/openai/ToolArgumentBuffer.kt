package com.openminis.app.provider.openai

import org.json.JSONObject
import org.json.JSONTokener

/** Keeps wire arguments separate from parsed arguments. Never invents missing JSON. */
internal class ToolArgumentBuffer {
    private val text = StringBuilder()

    fun append(fragment: String) { text.append(fragment) }

    /** Completion snapshots are authoritative; a missing/blank snapshot is not. */
    fun snapshot(value: String?) {
        if (!value.isNullOrBlank()) {
            text.setLength(0)
            text.append(value)
        }
    }

    override fun toString(): String = text.toString()
    val length: Int get() = text.length
    fun isNotEmpty(): Boolean = text.isNotEmpty()

    data class Result(val args: JSONObject, val raw: String, val error: String?)

    fun finish(): Result {
        val raw = text.toString()
        // Some zero-argument tools use an empty string. Required-field preflight
        // still rejects this for tools such as shell_execute.
        if (raw.isBlank()) return Result(JSONObject(), raw, null)
        return try {
            var value = parseValue(raw)
            // Compatible relays sometimes JSON-encode the arguments string twice.
            // Decode ONE extra layer, but never strip fences or auto-close JSON.
            if (value is String) value = parseValue(value)
            if (value !is JSONObject) {
                Result(JSONObject(), raw, "Tool arguments must be a JSON object (received a non-object value).")
            } else {
                Result(value, raw, null)
            }
        } catch (_: Exception) {
            Result(JSONObject(), raw, "Tool argument JSON was malformed or incomplete in the provider response.")
        }
    }

    private fun parseValue(raw: String): Any? {
        val parser = JSONTokener(raw)
        val value = parser.nextValue()
        require(parser.nextClean() == '\u0000') { "Trailing data after tool arguments" }
        return value
    }
}
