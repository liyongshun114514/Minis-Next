package com.openminis.app.intelligentui

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject

object IntelligentUiTool {
    const val NAME = "intelligent_ui"
    const val PROMPT = """- intelligent_ui: Prefer native interactive answers over walls of text or HTML when a comparison, explanation, options, form, plan, chart, or calculator would help. Call intelligent_ui with a small declarative document; the app renders real Android controls directly inside this conversation. Use it proactively for suitable answers, not for every greeting or simple fact. Keep short explanatory text alongside the card when useful. Inputs calculate locally; explicit Send taps create a normal new user turn. Do not claim an action, purchase, saved record, or permission was applied by merely displaying a card. Use user_ask for approvals and important decisions, never substitute an Intelligent UI submit for a required approval. Do not generate HTML, JavaScript, an iframe, or a browser page for this feature. The component catalog is intentionally finite: unsupported arbitrary web apps must be described honestly, not claimed to have been rendered. Existing tools remain available for real operations. The catalog includes nested layouts, native forms and dates, interactive charts, budget/conversion/comparison/decision tools, search directories, local practice quizzes, media, and copy/source/action panels. For an explicit full demonstration use preset all; for real tasks build a tailored document, not an unrelated demo."""
    fun definition() = AgentToolDefinition(
        name = NAME,
        description = """Display persistent native Android interactive UI (Compose; no WebView/server/extra API key). Returns immediately; does not wait for input or execute external operations. Use preset:"all" for a complete real interactive showcase, or one of cards/layouts/forms/tools/charts/media/actions/dashboard/comparison/budget/quiz/directory/panel. Otherwise provide document JSON object or encoded string: {version:1,title,nodes:[...]}. 1..32 top-level nodes, <=128 total nodes, <=5 nesting levels, <=192 KiB; unique identifier id per node. All nodes: id,type,title?,text?. Supported contract:
text: text. stat: value:string,unit?. table: columns:[string](1..8),rows:[[string]](1..100).
card: image?:HTTPS,icon?:text,variant?:info/success/warning/error/product/person/place/summary/recommendation,horizontal?:bool,expandable?:bool,details?:text,value?:string,unit?. Card supports real expansion.
Layouts section/grid/masonry/row/carousel/accordion/timeline/popover/tooltip/details: children:[nodes],columns?:1..4,label?,expanded?:bool. grid/masonry make columns; row horizontally scrolls; carousel has paging; accordion expands; popover/tooltip/details are touch-operated native dialogs. tabs: items:[{label,nodes:[nodes]}](1..16). Nest these to build dashboards/compound tools.
chart: labels:[string](1..64),values:[number],style?:bar/line/pie/scatter/histogram/ranking,unit?,xLabel?,yLabel?,xValues?:[number],series?:[{name,values:[number]}](<=6). Lengths must match; pie values nonnegative with positive sum. histogram values are pre-binned counts, labels identify bins; don't pretend automatic raw-data binning. trend: labels,values,unit?. before_after: labels,before:[number],after:[number],unit?. progress: value:0..1,label?,unit?. Always state actual source or label sample data, no fictional live data.
choices: options:[string](2..12),followUp? creates a normal user message only after tap.
form: fields:[field](1..24),followUp?,submitLabel?,cancelLabel?,resetLabel?. field={id,label,kind,value?,min?,max?,step?,required?:bool,disabled?:bool,error?:text,placeholder?:text,options?:[string]}. kind=text/textarea/search/number/slider/toggle/select/radio/checkbox/multiselect/segmented/date. number/slider values JSON numbers; slider needs min,max with max>min; positive step optional. toggle/checkbox booleans; multiselect array of options strings; select/radio/segmented need options; date ISO yyyy-MM-dd; other values strings. Required/error/disabled validation is real. Explicit form submit sends quoted user values, reset restores defaults, cancel is local.
calculator: numeric/slider fields,expression?,unit?,outputs?:[{label,expression,unit?}](<=12). At least expression or outputs required. Local bounded arithmetic + - * / ^ (), min,max,round,abs,sqrt with variables by field id, no code; supply valid defaults. Live results update without a model call.
converter: dimension:length/mass/temperature/storage,value:number,from,to. Units length mm/cm/m/km/in/ft/yd/mi; mass mg/g/kg/oz/lb/t; temperature C/F/K; storage B/KB/MB/GB/TB/KiB/MiB/GiB/TiB. budget: income:number>=0,currency?,items:[{label,amount:number>=0}]. compare: currency?,items:[{name,price:number>=0,quantity:number>0,unit,text?,image?:HTTPS}]; compare unit prices only for identical units. directory: currency?,items:[{name,text?,category?,price?:number>=0,image?:HTTPS}]; local search/filter/sort/details. decision: criteria:[{label,weight:0..100}](<=8),items:[{name,scores:[0..100]}](<=20); scores match criteria; live weighted ranking. quiz: questions:[{question,options:[string],answer:zero-based integer,explanation?}](<=20), only clearly labeled local practice with feedback, never a real exam service or real score record. wizard: pages:[{title,text}],finishLabel?; local progress only.
image: url:HTTPS,caption?,description?. gallery: images:[{url:HTTPS,caption?,description?}](<=24),style?:grid/carousel. video: url:HTTPS,poster?:HTTPS,caption?; explicit tap preview/play, device/network dependent, no autoplay. Remote media is fetched only when rendered; do not put secrets in URLs; no private/local files. map: points:[{label,lat,lon}](<=24), a schematic coordinate map NOT live tiles or verified route. rotation3d: native pitch/roll/yaw airplane sliders.
alert: status:info/success/warning/error,text. sources: items:[{label,url:HTTPS,text?}]. actions: items:[{label,kind:copy/followup/details/link,value:text,confirm?:text}]; copy writes clipboard, details opens native dialog, link opens HTTPS externally, followup sends a normal user message only on tap. Confirmation here is a local UI preview, NOT user_ask permission for purchases or changes to external systems.
No arbitrary HTML/JS/code/commands. Reject unsupported keys instead of silently ignoring. Use user's language. Prefer actual useful interactive answers; use presets only for showcasing or requested demos, never as fake task results.""",
        parameters = linkedMapOf(
            "tool_title" to AgentToolParam("string", "Short summary of the interactive answer in the user's language."),
            "document" to AgentToolParam("string", "JSON document with version:1, title, nodes; required unless preset is supplied."),
            "preset" to AgentToolParam("string", "Optional built-in native showcase: all, cards, layouts, forms, tools, charts, media, actions, dashboard, comparison, budget, quiz, directory, panel."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "document", "preset"),
    )
    fun execute(args: String): ToolExecutionResult = try {
        require(args.toByteArray(Charsets.UTF_8).size <= IntelligentUiParser.MAX_BYTES + 8192)
        val input = JSONObject(args)
        require(!(input.has("preset") && input.has("document"))) { "Use document OR preset" }
        val raw = if(input.has("preset")) UiShowcase.document(input.getString("preset")).toString() else input.get("document").let { if (it is JSONObject) it.toString() else it as String }
        val document = IntelligentUiParser.parse(raw)
        ToolExecutionResult(JSONObject().put("native_ui", document.json).put("status", "displayed")
            .put("message", "Native card displayed. No action executed; only explicit user taps send a new user turn.").toString(), true)
    } catch (e: Exception) {
        ToolExecutionResult("Invalid Intelligent UI document: ${e.message?.take(300) ?: "validation failed"}", false)
    }
}
