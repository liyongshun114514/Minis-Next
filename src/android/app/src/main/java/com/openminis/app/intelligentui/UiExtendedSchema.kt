package com.openminis.app.intelligentui

import org.json.JSONObject
import org.json.JSONArray
import java.net.URI
import java.time.LocalDate

/** Bounded, declarative extensions. No commands, script strings, or arbitrary view classes. */
internal object UiExtendedSchema {
    val layouts = setOf("section","grid","masonry","row","carousel","tabs","accordion","timeline","popover","tooltip","details")
    val rich = setOf("card","image","gallery","video","alert","progress","trend","before_after")
    val tools = setOf("converter","budget","compare","directory","quiz","decision","wizard")
    val types = layouts + rich + tools + setOf("actions","sources")
    fun keys(o:JSONObject, allowed:Set<String>) { require(o.keys().asSequence().all { it in allowed }) { "Unknown fields in ${o.optString("type", "item")}" } }
    fun text(o:JSONObject,k:String,max:Int=4000,required:Boolean=false) { if(required || o.has(k)) require(o.get(k) is String && o.getString(k).length <= max && (!required || o.getString(k).isNotBlank())) { "Invalid $k" } }
    fun num(o:JSONObject,k:String, min:Double=-1e12,max:Double=1e12):Double { val v=o.get(k); require(v is Number); return v.toDouble().also { require(it.isFinite() && it in min..max) { "$k out of range" } } }
    fun arr(o:JSONObject,k:String,min:Int=1,max:Int=32):JSONArray = o.getJSONArray(k).also { require(it.length() in min..max) { "$k count out of range" } }
    fun url(o:JSONObject,k:String) { if(o.has(k)) { text(o,k,2048,true); val u=URI(o.getString(k)); require(u.scheme=="https" && !u.host.isNullOrBlank() && u.userInfo==null && u.port in listOf(-1,443)) { "Only public HTTPS media/source URLs" }; val h=u.host.lowercase(); require(h!="localhost" && !h.endsWith(".local") && !h.endsWith(".localhost") && !h.contains(':') && !Regex("[0-9.]+").matches(h)) { "Local/numeric hosts are not supported" } } }
    fun strings(o:JSONObject,k:String,min:Int=1,max:Int=64):List<String> { val a=arr(o,k,min,max);return (0 until a.length()).map { require(a.get(it) is String);a.getString(it).also { s->require(s.isNotBlank()&&s.length<=600) } } }
    fun numbers(o:JSONObject,k:String,count:Int,min:Double=-1e12):List<Double> { val a=arr(o,k,count,count);return (0 until a.length()).map { val v=a.get(it);require(v is Number);v.toDouble().also { n->require(n.isFinite()&&n in min..1e12) } } }
    fun validate(n:JSONObject, child:(JSONObject)->Unit) {
        val type=n.getString("type")
        val common=setOf("id","type","title","text")
        val fields=when(type) {
            in layouts -> setOf("children","items","columns","label","expanded")
            "card"->setOf("image","icon","variant","horizontal","expandable","details","value","unit")
            "image"->setOf("url","caption","description")
            "gallery"->setOf("images","style")
            "video"->setOf("url","poster","caption")
            "alert"->setOf("status")
            "progress"->setOf("value","label","unit")
            "trend"->setOf("labels","values","unit")
            "before_after"->setOf("labels","before","after","unit")
            "converter"->setOf("value","from","to","dimension")
            "budget"->setOf("income","currency","items")
            "compare","directory"->setOf("items","currency")
            "quiz"->setOf("questions")
            "decision"->setOf("criteria","items")
            "wizard"->setOf("pages","finishLabel")
            "sources"->setOf("items")
            "actions"->setOf("items")
            else->error("Unknown component $type")
        }
        keys(n,common+fields)
        for(k in listOf("caption","description","icon","details","label","unit","currency","finishLabel")) text(n,k)
        for(k in listOf("horizontal","expandable","expanded")) if(n.has(k)) require(n.get(k) is Boolean)
        when(type) {
            in layouts -> {
                if(n.has("columns")) require(n.getInt("columns") in 1..4)
                if(type=="tabs") { val items=arr(n,"items",1,16);repeat(items.length()) { val item=items.getJSONObject(it);keys(item,setOf("label","nodes"));text(item,"label",100,true);val nodes=arr(item,"nodes",1,32);repeat(nodes.length()){ j->child(nodes.getJSONObject(j)) } } }
                else { val children=arr(n,"children",1,32);repeat(children.length()) { child(children.getJSONObject(it)) } }
            }
            "card"->{url(n,"image");text(n,"value",200);require(n.optString("variant","info") in listOf("info","success","warning","error","product","person","place","summary","recommendation"))}
            "image","video"->{require(n.has("url"));url(n,"url");url(n,"poster")}
            "gallery"->{require(n.optString("style","grid") in listOf("grid","carousel"));val a=arr(n,"images",1,24);repeat(a.length()){val x=a.getJSONObject(it);keys(x,setOf("url","caption","description"));require(x.has("url"));url(x,"url");text(x,"caption");text(x,"description")}}
            "alert"->{text(n,"text",4000,true);require(n.optString("status","info") in listOf("info","success","warning","error"))}
            "progress"->num(n,"value",0.0,1.0)
            "trend","before_after"->{val labels=strings(n,"labels");if(type=="trend") numbers(n,"values",labels.size) else {numbers(n,"before",labels.size);numbers(n,"after",labels.size)}}
            "converter"->{num(n,"value");require(n.getString("dimension") in listOf("length","mass","temperature","storage"));text(n,"from",30,true);text(n,"to",30,true);val units=when(n.getString("dimension")){"length"->listOf("mm","cm","m","km","in","ft","yd","mi");"mass"->listOf("mg","g","kg","oz","lb","t");"temperature"->listOf("C","F","K");else->listOf("B","KB","MB","GB","TB","KiB","MiB","GiB","TiB")};require(n.getString("from") in units && n.getString("to") in units)}
            "budget","compare","directory"->{if(type=="budget")num(n,"income",0.0);val a=arr(n,"items",1,40);repeat(a.length()){val x=a.getJSONObject(it);keys(x,when(type){"budget"->setOf("label","amount");"compare"->setOf("name","price","quantity","unit","text","image");else->setOf("name","text","category","price","image")});text(x,if(type=="budget")"label" else "name",200,true);if(type=="budget")num(x,"amount",0.0) else {if(type=="compare"||x.has("price"))num(x,"price",0.0);if(type=="compare"){num(x,"quantity",0.000001);text(x,"unit",80,true)};text(x,"text");text(x,"category",200);url(x,"image")}}}
            "quiz"->{val a=arr(n,"questions",1,20);repeat(a.length()){val x=a.getJSONObject(it);keys(x,setOf("question","options","answer","explanation"));text(x,"question",1000,true);val opts=strings(x,"options",2,12);require(x.get("answer") is Number && x.getInt("answer").toDouble()==x.getDouble("answer") && x.getInt("answer") in opts.indices);text(x,"explanation")}}
            "decision"->{val a=arr(n,"criteria",1,8);repeat(a.length()){val x=a.getJSONObject(it);keys(x,setOf("label","weight"));text(x,"label",200,true);num(x,"weight",0.0,100.0)};val items=arr(n,"items",1,20);repeat(items.length()){val x=items.getJSONObject(it);keys(x,setOf("name","scores"));text(x,"name",200,true);val scores=numbers(x,"scores",a.length(),0.0);require(scores.all{it<=100})}}
            "wizard"->{val a=arr(n,"pages",1,16);repeat(a.length()){val x=a.getJSONObject(it);keys(x,setOf("title","text"));text(x,"title",200,true);text(x,"text",4000,true)}}
            "sources"->{val a=arr(n,"items",1,24);repeat(a.length()){val x=a.getJSONObject(it);keys(x,setOf("label","url","text"));text(x,"label",200,true);require(x.has("url"));url(x,"url");text(x,"text")}}
            "actions"->{val a=arr(n,"items",1,12);repeat(a.length()){val x=a.getJSONObject(it);keys(x,setOf("label","kind","value","confirm"));text(x,"label",100,true);text(x,"value",2000,true);require(x.getString("kind") in listOf("copy","followup","details","link"));if(x.getString("kind")=="link")url(JSONObject().put("url",x.getString("value")),"url");if(x.has("confirm"))text(x,"confirm",1000,true)}}
        }
    }
}
