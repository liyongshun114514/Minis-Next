package com.openminis.app.intelligentui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun UiLayout(n:JSONObject, render:@Composable (JSONObject)->Unit) {
    val type=n.getString("type")
    val saved = rememberSaveableStateHolder()
    val children=if(n.has("children"))n.getJSONArray("children") else null
    fun childList():List<JSONObject> = (0 until (children?.length()?:0)).map{children!!.getJSONObject(it)}
    when(type) {
        "grid","masonry" -> {
            val columns=n.optInt("columns",2)
            val items=childList()
            if(type=="masonry") Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                repeat(columns){ col->Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){ items.filterIndexed{index,_->index%columns==col}.forEach { item->key(item.getString("id")){render(item)} } } }
            } else Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {items.chunked(columns).forEach { row-> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach { item->Column(Modifier.weight(1f)){key(item.getString("id")){render(item)}} };repeat(columns-row.size){Spacer(Modifier.weight(1f))}}}}
        }
        "row" -> Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)){childList().forEach{item->Column(Modifier.width(260.dp)){key(item.getString("id")){render(item)}}}}
        "carousel"->{var selected by rememberSaveable { mutableIntStateOf(0) };val items=childList();saved.SaveableStateProvider(items[selected].getString("id")){render(items[selected])};Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){OutlinedButton(onClick={selected--},enabled=selected>0){Text("‹")};Text("${selected+1} / ${items.size}",Modifier.padding(top=12.dp));OutlinedButton(onClick={selected++},enabled=selected<items.lastIndex){Text("›")}}}
        "tabs"->{val tabs=n.getJSONArray("items");var selected by rememberSaveable{mutableIntStateOf(0)}
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){repeat(tabs.length()){i->FilterChip(selected=selected==i,onClick={selected=i},label={Text(tabs.getJSONObject(i).getString("label"))})}}
            saved.SaveableStateProvider("tab_$selected") { val nodes=tabs.getJSONObject(selected).getJSONArray("nodes");repeat(nodes.length()){val item=nodes.getJSONObject(it);key(item.getString("id")){render(item)}} }
        }
        "accordion"->{var expanded by rememberSaveable{mutableStateOf(n.optBoolean("expanded",false))};OutlinedButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()){Text(n.optString("label",if(expanded)"收起 / Collapse" else "展开 / Expand"))};if(expanded)childList().forEach{item->key(item.getString("id")){render(item)}}}
        "popover","tooltip","details"->{var open by rememberSaveable{mutableStateOf(false)};OutlinedButton(onClick={open=true}){Text(n.optString("label","查看详情 / Details"))};if(open)AlertDialog(onDismissRequest={open=false},title={Text(n.optString("title","详情 / Details"))},text={Column(Modifier.heightIn(max=480.dp).verticalScrollCompat(),verticalArrangement=Arrangement.spacedBy(8.dp)){childList().forEach{item->key(item.getString("id")){render(item)}}}},confirmButton={TextButton(onClick={open=false}){Text("关闭 / Close")}})}
        "timeline"->childList().forEachIndexed{i,item->Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Text("${i+1} ●",color=MaterialTheme.colorScheme.primary);Column(Modifier.weight(1f)){key(item.getString("id")){render(item)}}};HorizontalDivider()}
        else->childList().forEach{item->key(item.getString("id")){render(item)}}
    }
}

@Composable
private fun Modifier.verticalScrollCompat():Modifier = this.verticalScroll(rememberScrollState())

@Composable
internal fun UiActions(title:String,n:JSONObject,interactive:Boolean,onFollowUp:(String)->Unit) {
    val items=n.getJSONArray("items");val clipboard=LocalClipboardManager.current;val uri=LocalUriHandler.current
    var message by rememberSaveable{mutableStateOf("")};var pending by rememberSaveable{mutableIntStateOf(-1)};var detail by rememberSaveable{mutableStateOf("")}
    fun execute(i:Int) {
        val action=items.getJSONObject(i);val value=action.getString("value")
        when(action.getString("kind")){
            "copy"->{clipboard.setText(AnnotatedString(value));message="已复制 / Copied"}
            "link"->runCatching{uri.openUri(value)}.onFailure{message="无法打开链接 / Unable to open link"}
            "details"->{detail=value}
            "followup"->{if(interactive)onFollowUp(IntelligentUiParser.followUp(title,JSONObject().put("followUp",value),mapOf("action" to action.getString("label"))))}
        }
    }
    repeat(items.length()){i->val a=items.getJSONObject(i);OutlinedButton(enabled=a.getString("kind")!="followup"||interactive,onClick={if(a.has("confirm"))pending=i else execute(i)},modifier=Modifier.fillMaxWidth()){Text(a.getString("label"))}}
    if(message.isNotBlank())Text(message,style=MaterialTheme.typography.labelSmall)
    if(pending>=0)AlertDialog(onDismissRequest={pending=-1},text={Text(items.getJSONObject(pending).getString("confirm"))},confirmButton={TextButton(onClick={val i=pending;pending=-1;execute(i)}){Text("确认 / Confirm")}},dismissButton={TextButton(onClick={pending=-1}){Text("取消 / Cancel")}})
    if(detail.isNotBlank())AlertDialog(onDismissRequest={detail=""},text={Text(detail)},confirmButton={TextButton(onClick={detail=""}){Text("关闭 / Close")}})
}

@Composable
internal fun UiSources(n:JSONObject) {
    val items=n.getJSONArray("items");val uri=LocalUriHandler.current
    var error by rememberSaveable{mutableStateOf(false)}
    repeat(items.length()){i->val x=items.getJSONObject(i);TextButton(onClick={error=runCatching{uri.openUri(x.getString("url"))}.isFailure}){Text("[${i+1}] ${x.getString("label")}")};Text(x.getString("url"),style=MaterialTheme.typography.labelSmall);if(x.has("text"))Text(x.getString("text"))}
    if(error)Text("无法打开链接 / Unable to open link",color=MaterialTheme.colorScheme.error)
}
