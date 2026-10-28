package com.openminis.app.intelligentui

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject

object IntelligentUiTool {
    const val NAME = "intelligent_ui"
    const val PROMPT = """- intelligent_ui: Prefer native interactive answers over walls of text or HTML when a comparison, explanation, options, form, plan, chart, or calculator would help. Call intelligent_ui with a small declarative document; the app renders real Android controls directly inside this conversation. Use it proactively for suitable answers, not for every greeting or simple fact. Keep short explanatory text alongside the card when useful. Inputs calculate locally; explicit Send taps create a normal new user turn. Do not claim an action, purchase, saved record, or permission was applied by merely displaying a card. Use user_ask for approvals and important decisions, never substitute an Intelligent UI submit for a required approval. Do not generate HTML, JavaScript, an iframe, or a browser page for this feature. The component catalog is intentionally finite: unsupported arbitrary web apps must be described honestly, not claimed to have been rendered. Existing tools remain available for real operations."""
    fun definition() = AgentToolDefinition(
        name = NAME,
        description = """Display a persistent native Android Intelligent UI card in the chat. Inspired by CopilotKit/OpenIntelligentUI's task-first interactive answers, implemented using Compose (no WebView/server/extra API key). Returns immediately; it does not wait for input or perform any external action. The document is JSON (object or JSON-encoded string), version 1, title and nodes (1..16, total <=64 KiB). Each node needs a unique identifier id, a type, optional title/text. Supported nodes:
text: text.
stat: value (string), unit?.
table: columns (1..8 strings), rows (1..100 arrays of same number of strings).
chart: labels (1..64 strings), values (same-length JSON numbers), style "bar" or "line", unit?.
choices: options (2..12 strings), followUp? (instruction sent ONLY when user taps an option).
form: fields, followUp?. Fields: id,label,kind (text/number/slider/toggle/select), value, required?. For slider provide numeric min,max,value; optional positive step. For select provide options and an optional matching value. Number values must be JSON numbers; toggle values booleans. Fields can be edited and sent explicitly as a normal user message.
calculator: numeric/slider fields, expression, unit?. Local bounded arithmetic supports + - * / ^ (), variables by field id, min(a,b), max(a,b), round(a), abs(a), sqrt(a); no code. Provide valid defaults. The result updates live, no model call.
steps: labels (1..24 strings), locally checkable.
diagram: labels (1..24), tap to inspect sequential explanation.
map: points (1..24 objects label,lat,lon). Native schematic coordinate map, NOT a tile map or verified route; do not claim live tiles or directions.
rotation3d: interactive native pitch/roll/yaw airplane explanation with angle sliders, no arbitrary imported 3D models.
No other fields or component types; no nested component tree, remote code, HTML, scripts or automatic commands. Labels/text are plain text. Return errors to the model instead of silently dropping unsupported content. Use the user's language.
Example: {"version":1,"title":"Split the bill","nodes":[{"id":"split","type":"calculator","title":"Per person","fields":[{"id":"total","label":"Total","kind":"number","value":120,"min":0},{"id":"tip","label":"Tip %","kind":"slider","value":10,"min":0,"max":30},{"id":"people","label":"People","kind":"slider","value":3,"min":1,"max":12,"step":1}],"expression":"total*(1+tip/100)/people","unit":"CNY"}]}""",
        parameters = linkedMapOf(
            "tool_title" to AgentToolParam("string", "Short summary of the interactive answer in the user's language."),
            "document" to AgentToolParam("string", "JSON document with version:1, title, nodes; see component contract above."),
        ),
        required = listOf("tool_title", "document"),
        propertyOrdering = listOf("tool_title", "document"),
    )
    fun execute(args: String): ToolExecutionResult = try {
        require(args.toByteArray(Charsets.UTF_8).size <= IntelligentUiParser.MAX_BYTES + 8192)
        val input = JSONObject(args)
        val raw = input.get("document").let { if (it is JSONObject) it.toString() else it as String }
        val document = IntelligentUiParser.parse(raw)
        ToolExecutionResult(JSONObject().put("native_ui", document.json).put("status", "displayed")
            .put("message", "Native card displayed. No action executed; only explicit user taps send a new user turn.").toString(), true)
    } catch (e: Exception) {
        ToolExecutionResult("Invalid Intelligent UI document: ${e.message?.take(300) ?: "validation failed"}", false)
    }
}
