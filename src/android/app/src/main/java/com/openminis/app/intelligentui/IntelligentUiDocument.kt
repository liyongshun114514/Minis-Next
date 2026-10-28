package com.openminis.app.intelligentui

import org.json.JSONArray
import org.json.JSONObject

data class IntelligentUiDocument(val title: String, val nodes: List<JSONObject>, val json: JSONObject)

/** Deliberately a finite native component catalog, not an HTML/JS interpreter. */
object IntelligentUiParser {
    const val MAX_BYTES = 65536
    val types = listOf("text", "stat", "table", "chart", "choices", "form", "calculator", "steps", "diagram", "map", "rotation3d")
    private val idPattern = Regex("[A-Za-z][A-Za-z0-9_]{0,47}")
    private val common = setOf("id", "type", "title", "text")
    private val specific = mapOf(
        "text" to emptySet(), "stat" to setOf("value", "unit"),
        "table" to setOf("columns", "rows"), "chart" to setOf("labels", "values", "style", "unit"),
        "choices" to setOf("options", "followUp"), "form" to setOf("fields", "followUp"),
        "calculator" to setOf("fields", "expression", "unit"),
        "steps" to setOf("labels"), "diagram" to setOf("labels"),
        "map" to setOf("points"), "rotation3d" to emptySet(),
    )
    fun parse(raw: String): IntelligentUiDocument {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "UI exceeds 64 KiB" }
        val root = JSONObject(raw)
        keys(root, setOf("version", "title", "nodes"))
        require(root.optInt("version", -1) == 1) { "UI version must be 1" }
        val title = str(root, "title", 160)
        val nodes = root.getJSONArray("nodes")
        require(nodes.length() in 1..16) { "Use 1..16 components" }
        val ids = mutableSetOf<String>()
        val parsed = (0 until nodes.length()).map { i ->
            val n = nodes.getJSONObject(i)
            val type = str(n, "type", 30)
            require(type in types) { "Unsupported component: $type" }
            keys(n, common + specific.getValue(type))
            val id = str(n, "id", 48)
            require(idPattern.matches(id) && ids.add(id)) { "Component IDs must be unique identifiers" }
            optionalText(n, "title", 200); optionalText(n, "text", 4000)
            when (type) {
                "text" -> str(n, "text", 4000)
                "stat" -> { str(n, "value", 200); optionalText(n, "unit", 80) }
                "table" -> {
                    val cols = strings(n, "columns", 1, 8, 200)
                    val rows = n.getJSONArray("rows"); require(rows.length() in 1..100)
                    repeat(rows.length()) { r ->
                        val row = rows.getJSONArray(r); require(row.length() == cols.size)
                        repeat(row.length()) { c -> require(row.get(c) is String && row.getString(c).length <= 1000) }
                    }
                }
                "chart" -> {
                    val labels = strings(n, "labels", 1, 64, 160)
                    val values = n.getJSONArray("values"); require(values.length() == labels.size)
                    repeat(values.length()) { numeric(values.get(it)) }
                    require(n.optString("style", "bar") in listOf("bar", "line")); optionalText(n, "unit", 80)
                }
                "choices" -> { strings(n, "options", 2, 12, 240); optionalText(n, "followUp", 500) }
                "form", "calculator" -> {
                    val fields = n.getJSONArray("fields"); require(fields.length() in 1..12)
                    val fieldIds = mutableSetOf<String>(); val vars = mutableMapOf<String, Double>()
                    repeat(fields.length()) { f ->
                        val field = fields.getJSONObject(f)
                        keys(field, setOf("id", "label", "kind", "value", "min", "max", "step", "required", "options"))
                        val fid = str(field, "id", 48)
                        require(idPattern.matches(fid) && fieldIds.add(fid))
                        str(field, "label", 200)
                        val kind = str(field, "kind", 20)
                        require(kind in listOf("text", "number", "slider", "toggle", "select"))
                        if (type == "calculator") require(kind in listOf("number", "slider"))
                        if (kind in listOf("number", "slider")) {
                            val v = numeric(field.get("value")); vars[fid] = v
                            if (field.has("min")) require(v >= numeric(field.get("min")))
                            if (field.has("max")) require(v <= numeric(field.get("max")))
                            if (kind == "slider") {
                                val min = numeric(field.get("min")); val max = numeric(field.get("max"))
                                require(max > min && max - min <= 1e12 && max.toFloat() > min.toFloat()) { "Slider range must be representable by native controls" }
                            }
                            if (field.has("step")) require(numeric(field.get("step")) > 0)
                        } else if (kind == "toggle") require(field.get("value") is Boolean)
                        else {
                            optionalText(field, "value", 2000)
                            if (kind == "select") {
                                val options = strings(field, "options", 1, 24, 200)
                                if (field.has("value")) require(field.getString("value") in options)
                            }
                        }
                        if (field.has("required")) require(field.get("required") is Boolean)
                    }
                    if (type == "calculator") {
                        val expr = str(n, "expression", 512)
                        UiExpression.evaluate(expr, vars)
                        optionalText(n, "unit", 80)
                    } else optionalText(n, "followUp", 500)
                }
                "steps", "diagram" -> strings(n, "labels", 1, 24, 600)
                "map" -> {
                    val pts = n.getJSONArray("points"); require(pts.length() in 1..24)
                    repeat(pts.length()) { p ->
                        val point = pts.getJSONObject(p); keys(point, setOf("label", "lat", "lon"))
                        str(point, "label", 160)
                        require(numeric(point.get("lat")) in -85.0..85.0)
                        require(numeric(point.get("lon")) in -180.0..180.0)
                    }
                }
            }
            n
        }
        return IntelligentUiDocument(title, parsed, root)
    }
    private fun numeric(value: Any): Double {
        require(value is Number) { "Expected JSON number" }
        return value.toDouble().also { require(it.isFinite() && kotlin.math.abs(it) <= 1e12) { "Number outside supported range" } }
    }
    private fun keys(o: JSONObject, allowed: Set<String>) {
        val names = o.keys().asSequence().toSet()
        require(names.all { it in allowed }) { "Unknown fields: ${names - allowed}" }
    }
    private fun optionalText(o: JSONObject, key: String, max: Int) { if (o.has(key)) { require(o.get(key) is String); require(o.getString(key).length <= max) } }
    private fun str(o: JSONObject, key: String, max: Int): String {
        require(o.get(key) is String) { "$key must be text" }
        return o.getString(key).also { require(it.isNotBlank() && it.length <= max) { "$key is empty or too long" } }
    }
    private fun strings(o: JSONObject, key: String, min: Int, max: Int, length: Int): List<String> {
        val a = o.getJSONArray(key); require(a.length() in min..max) { "$key count out of range" }
        return (0 until a.length()).map { require(a.get(it) is String); a.getString(it).also { s -> require(s.isNotBlank() && s.length <= length) } }
    }

    /** Only a real tap calls this. JSON quoting keeps user values separate from instructions. */
    fun followUp(title: String, component: JSONObject, values: Map<String, String>): String {
        require(values.size <= 12 && values.all { it.key.length <= 48 && it.value.length <= 2000 })
        return "Intelligent UI · $title\n${component.optString("followUp", component.optString("title", "Continue with my selections"))}\nUser-selected values: ${JSONObject(values).toString()}"
    }
}
