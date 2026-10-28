package com.openminis.app.intelligentui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IntelligentUiParserTest {
    private fun raw(node: String) = """{"version":1,"title":"Native answer","nodes":[$node]}"""
    private fun parse(node: String) = IntelligentUiParser.parse(raw(node))
    private fun rejected(node: String) { try { parse(node); fail("Accepted invalid component") } catch (_: Exception) { } }
    @Test fun plainTextRoundTrips() { val doc = parse("""{"id":"a","type":"text","text":"Hello <world>"}"""); assertEquals("Hello <world>", doc.nodes.single().getString("text")); assertEquals(1,IntelligentUiParser.parse(doc.json.toString()).nodes.size) }
    @Test fun stat() { parse("""{"id":"a","type":"stat","value":"42","unit":"items"}""") }
    @Test fun table() { parse("""{"id":"a","type":"table","columns":["Name","Price"],"rows":[["A","10"],["B","20"]]}""") }
    @Test fun chartNegativeValuesAndLine() { parse("""{"id":"a","type":"chart","labels":["A","B"],"values":[-10,20],"style":"line"}""") }
    @Test fun choicesAndFollowupAreQuoted() { val d=parse("""{"id":"a","type":"choices","options":["A","B"]}"""); val text=IntelligentUiParser.followUp(d.title,d.nodes[0],mapOf("choice" to "line\n\"quoted\"")); assertTrue(text.contains("\\n\\\"quoted\\\"")); assertTrue(text.contains("User-selected values:")) }
    @Test fun formAllFieldKinds() { parse("""{"id":"a","type":"form","fields":[{"id":"name","label":"Name","kind":"text","required":true},{"id":"amount","label":"Amount","kind":"number","value":3},{"id":"yes","label":"Yes","kind":"toggle","value":true},{"id":"pick","label":"Pick","kind":"select","value":"A","options":["A","B"]}]}""") }
    @Test fun calculatorWithDefaults() { parse("""{"id":"a","type":"calculator","fields":[{"id":"total","label":"Total","kind":"number","value":100},{"id":"people","label":"People","kind":"slider","value":2,"min":1,"max":10,"step":1}],"expression":"total/people"}""") }
    @Test fun stepsAndDiagram() { for(type in listOf("steps","diagram")) parse("""{"id":"a","type":"$type","labels":["One","Two"]}""") }
    @Test fun mapAndRotation() { parse("""{"id":"a","type":"map","points":[{"label":"Tokyo","lat":35.6,"lon":139.7}]}"""); parse("""{"id":"a","type":"rotation3d"}""") }
    @Test fun unknownTypesAndFieldsAreErrors() { rejected("""{"id":"a","type":"html","text":"<div>"}"""); rejected("""{"id":"a","type":"text","text":"ok","js":"anything"}""") }
    @Test fun duplicateIdsAndBadIdentifiers() { rejected("""{"id":"a","type":"text","text":"one"},{"id":"a","type":"text","text":"two"}"""); rejected("""{"id":"two words","type":"rotation3d"}""") }
    @Test fun chartAndTableShapeMustMatch() { rejected("""{"id":"a","type":"chart","labels":["A"],"values":[1,2]}"""); rejected("""{"id":"a","type":"table","columns":["A","B"],"rows":[["one"]]}""") }
    @Test fun sliderRangeAndCalculatorAreValidated() { rejected("""{"id":"a","type":"form","fields":[{"id":"n","label":"n","kind":"slider","value":1,"min":2,"max":2}]}"""); rejected("""{"id":"a","type":"calculator","fields":[{"id":"n","label":"n","kind":"number","value":0}],"expression":"1/n"}""") }
    @Test fun mapCoordinatesMustBeInRange() { rejected("""{"id":"a","type":"map","points":[{"label":"bad","lat":99,"lon":0}]}""") }
    @Test fun documentVersionAndSizeBound() { try { IntelligentUiParser.parse("""{"version":2,"title":"bad","nodes":[]}"""); fail() } catch (_: Exception) {} ; rejected("""{"id":"a","type":"text","text":"${"x".repeat(70000)}"}""") }
    @Test fun actualToolReturnsNativeDocumentAndError() { val args=JSONObject().put("document",JSONObject(raw("""{"id":"a","type":"text","text":"Hello"}"""))).toString(); val ok=IntelligentUiTool.execute(args); assertTrue(ok.success); assertTrue(JSONObject(ok.output).has("native_ui")); assertFalse(IntelligentUiTool.execute("{}").success) }
    @Test fun schemaAvailableForEveryProvider() { val def=IntelligentUiTool.definition(); for(schema in listOf(def.toOpenAIJson(),def.toAnthropicJson(),def.toGeminiJson())) assertTrue(schema.toString().contains("document")); assertTrue(def.description.contains("no WebView")) }
}
