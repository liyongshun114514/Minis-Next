package com.openminis.app.intelligentui
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class IntelligentUiExtendedParserTest {
    private fun doc(node:JSONObject)=JSONObject().put("version",1).put("title","Test").put("nodes",JSONArray().put(node)).toString()
    private fun reject(node:JSONObject){var failed=false;try{IntelligentUiParser.parse(doc(node))}catch(_:Exception){failed=true};assertTrue("Invalid document accepted",failed)}
    @Test fun allBuiltInPresetsAreValid(){for(p in UiShowcase.presets){val d=UiShowcase.document(p);assertTrue(p,IntelligentUiParser.parse(d.toString()).nodes.isNotEmpty());assertEquals(d.toString(),IntelligentUiParser.parse(d.toString()).json.toString())}}
    @Test fun showcasesCoverEntireCatalog(){val raw=UiShowcase.document("all").toString();for(type in listOf("card","grid","masonry","carousel","tabs","accordion","timeline","popover","tooltip","details","wizard","form","calculator","converter","budget","compare","decision","quiz","directory","chart","trend","before_after","progress","image","gallery","video","map","actions","sources","math"))assertTrue(type,raw.contains("\"type\":\"$type\""))}
    @Test fun duplicateNestedIdsAreRejected(){val child=JSONObject("""{"id":"same","type":"text","text":"hi"}""");reject(JSONObject().put("id","same").put("type","section").put("children",JSONArray().put(child)))}
    @Test fun excessiveDepthIsRejected(){var n=JSONObject("""{"id":"last","type":"text","text":"hi"}""");repeat(7){i->n=JSONObject().put("id","nest$i").put("type","section").put("children",JSONArray().put(n))};reject(n)}
    @Test fun unsupportedActionsAndUrlsRejected(){for(url in listOf("file:///sdcard/a.jpg","http://example.com/a","https://127.0.0.1/a","https://localhost/a","https://user:secret@example.com/a","https://example.com:8080/a")){reject(JSONObject().put("id","img").put("type","image").put("url",url))};reject(JSONObject("""{"id":"a","type":"actions","items":[{"label":"x","kind":"command","value":"whoami"}]}"""))}
    @Test fun invalidQuizAndChartsRejected(){reject(JSONObject("""{"id":"q","type":"quiz","questions":[{"question":"q","options":["a","b"],"answer":2}]}"""));reject(JSONObject("""{"id":"c","type":"chart","labels":["a"],"values":[0],"style":"pie"}"""));reject(JSONObject("""{"id":"c","type":"chart","labels":["a"],"values":[1],"series":[{"name":"B","values":[1,2]}]}"""))}
    @Test fun invalidFieldTypesAndDefaultsRejected(){for(f in listOf("""{"id":"f","label":"f","kind":"date","value":"2026-02-30"}""","""{"id":"f","label":"f","kind":"checkbox","value":"true"}""","""{"id":"f","label":"f","kind":"multiselect","options":["a","b"],"value":["c"]}""")){reject(JSONObject().put("id","form").put("type","form").put("fields",JSONArray().put(JSONObject(f))))}}
    @Test fun presetToolReturnsActualPersistentDocument(){val r=IntelligentUiTool.execute("""{"preset":"all"}""");assertTrue(r.output,r.success);assertTrue(JSONObject(r.output).getJSONObject("native_ui").getJSONArray("nodes").length()>0);assertFalse(IntelligentUiTool.execute("""{"preset":"all","document":"{}"}""").success)}
    @Test fun shapeAndRangesRejected(){reject(JSONObject("""{"id":"a","type":"progress","value":1.1}"""));reject(JSONObject("""{"id":"d","type":"decision","criteria":[{"label":"A","weight":50}],"items":[{"name":"A","scores":[1,2]}]}"""));reject(JSONObject("""{"id":"b","type":"budget","income":-1,"items":[{"label":"A","amount":1}]}"""))}
}
