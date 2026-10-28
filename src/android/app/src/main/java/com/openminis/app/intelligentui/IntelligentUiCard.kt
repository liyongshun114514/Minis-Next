package com.openminis.app.intelligentui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ToolBlockStatus
import org.json.JSONObject
import java.util.Locale
import kotlin.math.*

@Composable
internal fun IntelligentUiCard(block: AssistantBlock, interactive: Boolean, onFollowUp: (String) -> Unit, fallback: @Composable () -> Unit) {
    val document = remember(block.content, block.toolStatus) {
        if (block.toolStatus != ToolBlockStatus.SUCCESS) null else runCatching {
            IntelligentUiParser.parse(JSONObject(block.content).getJSONObject("native_ui").toString())
        }.getOrNull()
    }
    if (document == null) { fallback(); return }
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Intelligent UI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(document.title, style = MaterialTheme.typography.titleLarge)
            for (node in document.nodes) key(block.id, node.getString("id")) {
                UiNode(document.title, node, interactive, onFollowUp)
            }
        }
    }
}

@Composable
private fun UiNode(title: String, node: JSONObject, interactive: Boolean, onFollowUp: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        node.optString("title").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        node.optString("text").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        when (node.getString("type")) {
            "stat" -> Text(node.getString("value") + " " + node.optString("unit"), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            "table" -> UiTable(node)
            "chart" -> UiChart(node)
            "choices" -> {
                val options = node.getJSONArray("options")
                repeat(options.length()) { i ->
                    OutlinedButton(enabled = interactive, onClick = { onFollowUp(IntelligentUiParser.followUp(title, node, mapOf(node.getString("id") to options.getString(i)))) }, modifier = Modifier.fillMaxWidth()) { Text(options.getString(i)) }
                }
            }
            "form", "calculator" -> UiFields(title, node, interactive, onFollowUp)
            "steps" -> {
                val labels = node.getJSONArray("labels")
                var checked by rememberSaveable { mutableStateOf(ArrayList<String>()) }
                repeat(labels.length()) { i ->
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(checked = i.toString() in checked, onCheckedChange = { yes -> checked = ArrayList(checked.toMutableSet().apply { if (yes) add(i.toString()) else remove(i.toString()) }) })
                        Text(labels.getString(i), Modifier.weight(1f).padding(top = 12.dp))
                    }
                }
            }
            "diagram" -> {
                val labels = node.getJSONArray("labels")
                var selected by rememberSaveable { mutableIntStateOf(0) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(labels.length()) { i ->
                        FilterChip(selected = selected == i, onClick = { selected = i }, label = { Text("${i + 1}") })
                        if (i + 1 < labels.length()) Text("→", Modifier.padding(top = 12.dp))
                    }
                }
                Text(labels.getString(selected.coerceIn(0, labels.length() - 1)))
            }
            "map" -> UiMap(node)
            "rotation3d" -> UiRotation()
        }
    }
}

@Composable
private fun UiTable(n: JSONObject) {
    val columns = n.getJSONArray("columns"); val rows = n.getJSONArray("rows")
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row { repeat(columns.length()) { Text(columns.getString(it), Modifier.width(160.dp).padding(8.dp), fontWeight = FontWeight.Bold) } }
        HorizontalDivider()
        repeat(rows.length()) { r ->
            Row { repeat(columns.length()) { c -> Text(rows.getJSONArray(r).getString(c), Modifier.width(160.dp).padding(8.dp)) } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun UiChart(n: JSONObject) {
    val labels = n.getJSONArray("labels"); val values = n.getJSONArray("values")
    val data = (0 until values.length()).map { values.getDouble(it) }
    val primary = MaterialTheme.colorScheme.primary; val line = MaterialTheme.colorScheme.outlineVariant
    val low = min(0.0, data.min()); val high = max(0.0, data.max()); val span = (high - low).coerceAtLeast(1.0)
    val description = data.indices.joinToString { "${labels.getString(it)}: ${data[it]} ${n.optString("unit")}" }
    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
        fun y(v: Double) = (size.height * (1 - (v - low) / span)).toFloat()
        val zero = y(0.0)
        drawLine(line, Offset(0f, zero), Offset(size.width, zero))
        val slot = size.width / data.size
        if (n.optString("style", "bar") == "line") {
            val path = Path()
            data.forEachIndexed { i, v -> val p = Offset(slot * (i + .5f), y(v)); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y); drawCircle(primary, 4.dp.toPx(), p) }
            drawPath(path, primary, style = Stroke(2.dp.toPx()))
        } else data.forEachIndexed { i, v ->
            val top = min(zero, y(v)); val height = abs(zero - y(v)).coerceAtLeast(1f)
            drawRect(primary, Offset(slot * i + slot * .15f, top), androidx.compose.ui.geometry.Size(slot * .7f, height))
        }
    }
    // Exact values remain accessible even on a narrow screen and with a screen reader.
    repeat(data.size) { i -> Text("${labels.getString(i)}  ·  ${format(data[i])} ${n.optString("unit")}", style = MaterialTheme.typography.bodySmall) }
}

private fun format(v: Double): String = String.format(Locale.getDefault(), "%.4f", v).trimEnd('0').trimEnd('.', ',')

@Composable
private fun UiFields(title: String, n: JSONObject, interactive: Boolean, onFollowUp: (String) -> Unit) {
    val fields = n.getJSONArray("fields")
    var values by rememberSaveable { mutableStateOf(ArrayList((0 until fields.length()).map { i -> fields.getJSONObject(i).opt("value")?.toString() ?: "" })) }
    fun update(i: Int, value: String) { if (value.length <= 2000) values = ArrayList(values).apply { set(i, value) } }
    val errorText = stringResource(R.string.intelligent_ui_invalid_input)
    val invalid = (0 until fields.length()).any { i ->
        val f = fields.getJSONObject(i); val value = values[i]
        if (f.optBoolean("required") && value.isBlank()) true
        else if (f.getString("kind") in listOf("number", "slider")) {
            val v = value.toDoubleOrNull()
            v == null || !v.isFinite() || abs(v) > 1e12 || (f.has("min") && v < f.getDouble("min")) || (f.has("max") && v > f.getDouble("max"))
        } else false
    }
    repeat(fields.length()) { i ->
        val f = fields.getJSONObject(i); val label = f.getString("label"); val value = values[i]
        when (f.getString("kind")) {
            "toggle" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, Modifier.weight(1f)); Switch(checked = value == "true", onCheckedChange = { update(i, it.toString()) })
            }
            "slider" -> {
                val min = f.getDouble("min"); val max = f.getDouble("max")
                Text("$label: ${format(value.toDoubleOrNull() ?: min)}")
                Slider(value = (value.toDoubleOrNull() ?: min).toFloat().coerceIn(min.toFloat(), max.toFloat()), onValueChange = {
                    val step = f.optDouble("step", 0.0)
                    val v = if (step > 0) min + round((it.toDouble() - min) / step) * step else it.toDouble()
                    update(i, v.coerceIn(min, max).toString())
                }, valueRange = min.toFloat()..max.toFloat())
            }
            "select" -> {
                Text(label)
                val options = f.getJSONArray("options")
                Column { repeat(options.length()) { j ->
                    val option = options.getString(j)
                    FilterChip(selected = value == option, onClick = { update(i, option) }, label = { Text(option) })
                } }
            }
            else -> OutlinedTextField(value = value, onValueChange = { update(i, it) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
    }
    if (invalid) Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (n.getString("type") == "calculator") {
        val computed = if (invalid) null else runCatching { UiExpression.evaluate(n.getString("expression"), (0 until fields.length()).associate { fields.getJSONObject(it).getString("id") to values[it].toDouble() }) }.getOrNull()
        Text(if (computed == null) errorText else "${format(computed)} ${n.optString("unit")}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
    } else Button(enabled = interactive && !invalid, onClick = {
        val selected = (0 until fields.length()).associate { fields.getJSONObject(it).getString("id") to values[it] }
        onFollowUp(IntelligentUiParser.followUp(title, n, selected))
    }) { Text(stringResource(R.string.intelligent_ui_send)) }
}

@Composable
private fun UiMap(n: JSONObject) {
    val points = n.getJSONArray("points")
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val primary = MaterialTheme.colorScheme.primary
    val coordinates = (0 until points.length()).map { points.getJSONObject(it) }
    val minX = coordinates.minOf { it.getDouble("lon") }; val maxX = coordinates.maxOf { it.getDouble("lon") }
    val minY = coordinates.minOf { it.getDouble("lat") }; val maxY = coordinates.maxOf { it.getDouble("lat") }
    val description = coordinates.joinToString { "${it.getString("label")}: ${it.getDouble("lat")}, ${it.getDouble("lon")}" }
    Canvas(Modifier.fillMaxWidth().height(190.dp).semantics { contentDescription = description }) {
        fun point(p: JSONObject) = Offset((.1 * size.width + .8 * size.width * (p.getDouble("lon") - minX) / (maxX - minX).coerceAtLeast(.01)).toFloat(), (.9 * size.height - .8 * size.height * (p.getDouble("lat") - minY) / (maxY - minY).coerceAtLeast(.01)).toFloat())
        coordinates.zipWithNext().forEach { (a, b) -> drawLine(primary.copy(alpha = .4f), point(a), point(b), 2.dp.toPx()) }
        coordinates.forEachIndexed { i, p -> drawCircle(primary, if (i == selected) 9.dp.toPx() else 5.dp.toPx(), point(p)) }
    }
    Text(stringResource(R.string.intelligent_ui_schematic), style = MaterialTheme.typography.labelSmall)
    repeat(points.length()) { i -> val p = points.getJSONObject(i)
        FilterChip(selected = selected == i, onClick = { selected = i }, label = { Text("${i + 1}. ${p.getString("label")} (${p.getDouble("lat")}, ${p.getDouble("lon")})") })
    }
}

@Composable
private fun UiRotation() {
    var pitch by rememberSaveable { mutableFloatStateOf(0f) }; var roll by rememberSaveable { mutableFloatStateOf(0f) }; var yaw by rememberSaveable { mutableFloatStateOf(0f) }
    val primary = MaterialTheme.colorScheme.primary; val secondary = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = "Pitch $pitch, roll $roll, yaw $yaw" }) {
        fun project(x: Double, y: Double, z: Double): Offset {
            val p = pitch * PI / 180; val r = roll * PI / 180; val w = yaw * PI / 180
            val x1 = x * cos(w) - y * sin(w); val y1 = x * sin(w) + y * cos(w)
            val y2 = y1 * cos(p) - z * sin(p); val z2 = y1 * sin(p) + z * cos(p)
            val x3 = x1 * cos(r) + z2 * sin(r); val z3 = -x1 * sin(r) + z2 * cos(r)
            val scale = min(size.width, size.height) * .35
            return Offset((size.width / 2 + x3 * scale).toFloat(), (size.height / 2 + (y2 * .5 - z3 * .7) * scale).toFloat())
        }
        val vertices = listOf(Triple(0.0, -1.2, 0.0), Triple(.2, -.2, 0.0), Triple(1.2, .4, 0.0), Triple(.2, .3, 0.0), Triple(.15, 1.0, 0.0), Triple(.5, 1.1, 0.0), Triple(0.0, .85, 0.0), Triple(-.5, 1.1, 0.0), Triple(-.15, 1.0, 0.0), Triple(-.2, .3, 0.0), Triple(-1.2, .4, 0.0), Triple(-.2, -.2, 0.0))
        val path = Path(); vertices.forEachIndexed { i, (x,y,z) -> val point = project(x,y,z); if (i == 0) path.moveTo(point.x,point.y) else path.lineTo(point.x,point.y) }; path.close()
        drawPath(path, primary.copy(alpha = .3f)); drawPath(path, primary, style = Stroke(2.dp.toPx()))
        drawLine(secondary, project(0.0,0.0,0.0), project(0.0,0.0,1.0), 2.dp.toPx())
    }
    Text("Pitch ${pitch.toInt()}°"); Slider(value = pitch, onValueChange = { pitch = it }, valueRange = -180f..180f)
    Text("Roll ${roll.toInt()}°"); Slider(value = roll, onValueChange = { roll = it }, valueRange = -180f..180f)
    Text("Yaw ${yaw.toInt()}°"); Slider(value = yaw, onValueChange = { yaw = it }, valueRange = -180f..180f)
    OutlinedButton(onClick = { pitch = 0f; roll = 0f; yaw = 0f }) { Text(stringResource(R.string.intelligent_ui_reset)) }
}
