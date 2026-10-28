package com.openminis.app.intelligentui

import org.json.JSONObject
import java.time.LocalDate

internal object UiFormSchema {
    val kinds = setOf("text","textarea","search","number","slider","toggle","select","radio","checkbox","multiselect","segmented","date")
    fun validate(n:JSONObject) {
        val fields=UiExtendedSchema.arr(n,"fields",1,24)
        val ids=mutableSetOf<String>();val vars=mutableMapOf<String,Double>()
        repeat(fields.length()) { i ->
            val f=fields.getJSONObject(i)
            UiExtendedSchema.keys(f,setOf("id","label","kind","value","min","max","step","required","options","disabled","error","placeholder"))
            val id=f.getString("id");require(Regex("[A-Za-z][A-Za-z0-9_]{0,47}").matches(id)&&ids.add(id))
            UiExtendedSchema.text(f,"label",200,true)
            val kind=f.getString("kind");require(kind in kinds)
            if(n.getString("type")=="calculator") require(kind in listOf("number","slider"))
            for(k in listOf("required","disabled")) if(f.has(k))require(f.get(k) is Boolean)
            for(k in listOf("error","placeholder"))UiExtendedSchema.text(f,k,1000)
            when(kind) {
                "number","slider"->{val v=UiExtendedSchema.num(f,"value");vars[id]=v
                    if(f.has("min"))require(v>=UiExtendedSchema.num(f,"min"))
                    if(f.has("max"))require(v<=UiExtendedSchema.num(f,"max"))
                    if(f.has("step"))require(UiExtendedSchema.num(f,"step")>0)
                    if(kind=="slider") {val low=UiExtendedSchema.num(f,"min");val high=UiExtendedSchema.num(f,"max");require(high>low && high-low<=1e12 && high.toFloat()>low.toFloat())}
                }
                "toggle","checkbox"->require(f.get("value") is Boolean)
                "multiselect"->{val options=UiExtendedSchema.strings(f,"options",1,24);if(f.has("value")){val a=f.getJSONArray("value");require(a.length()<=options.size);val selected=(0 until a.length()).map{require(a.get(it)is String);a.getString(it)};require(selected.distinct().size==selected.size&&selected.all{it in options})}}
                else->{UiExtendedSchema.text(f,"value",2000)
                    if(kind in listOf("select","radio","segmented")){val options=UiExtendedSchema.strings(f,"options",1,24);if(f.has("value"))require(f.getString("value") in options)}
                    if(kind=="date"&&f.optString("value").isNotBlank())LocalDate.parse(f.getString("value"))
                }
            }
        }
        if(n.getString("type")=="calculator") {
            if(n.has("expression")){UiExtendedSchema.text(n,"expression",512,true);UiExpression.evaluate(n.getString("expression"),vars)}
            require(n.has("expression")||n.has("outputs"))
            if(n.has("outputs")){val outputs=UiExtendedSchema.arr(n,"outputs",1,12);repeat(outputs.length()){val o=outputs.getJSONObject(it);UiExtendedSchema.keys(o,setOf("label","expression","unit"));UiExtendedSchema.text(o,"label",160,true);UiExtendedSchema.text(o,"expression",512,true);UiExtendedSchema.text(o,"unit",80);UiExpression.evaluate(o.getString("expression"),vars)}}
            UiExtendedSchema.text(n,"unit",80)
        } else {for(k in listOf("followUp","submitLabel","cancelLabel","resetLabel"))UiExtendedSchema.text(n,k,500)}
    }
}
