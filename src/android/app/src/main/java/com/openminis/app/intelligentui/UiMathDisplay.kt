package com.openminis.app.intelligentui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Native, local math workspace for the Intelligent UI `math` component.
 *
 * Everything is computed on device from the declared expression and slider
 * values: no WebView, no TeX engine, no CAS, no network. Sampling counts are
 * fixed and every derived series is cached with remember(...) so a Canvas
 * redraw never re-evaluates the expression. Domain failures are shown as
 * explicit errors instead of a fabricated finite value.
 */
@Composable
internal fun UiMathDisplay(n: JSONObject) {
    val snapshot = n.toString()
    val invalid = remember(snapshot) {
        runCatching { UiMathSchema.validate(n) }.exceptionOrNull()?.let { it.message ?: "Invalid math node" }
    }
    if (invalid != null) {
        MathError(invalid)
        return
    }
    val sliders = remember(snapshot) { readSliders(n) }
    var values by remember(snapshot) { mutableStateOf(sliders.map { it.initial }) }
    var resetTick by remember(snapshot) { mutableIntStateOf(0) }
    val variables = remember(sliders, values) {
        sliders.mapIndexed { index, slider -> slider.name to values[index] }.toMap()
    }
    val mode = n.getString("mode")

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (sliders.isNotEmpty()) {
            SliderPanel(sliders, values) { index, value ->
                values = values.toMutableList().also { it[index] = value }
            }
        }
        key(resetTick) {
            when (mode) {
                "formula" -> FormulaMode(n)
                "function_plot" -> FunctionPlotMode(n, variables)
                "parametric" -> ParametricMode(n, variables)
                "polar" -> PolarMode(n, variables)
                "implicit" -> ImplicitMode(n, variables)
                "surface3d" -> SurfaceMode(n, variables)
                "calculus" -> CalculusMode(n, variables)
                "equation" -> EquationMode(n, variables)
                "matrix" -> MatrixMode(n)
                "statistics" -> StatisticsMode(n)
                "probability" -> ProbabilityMode(n)
                "geometry" -> GeometryMode(n, variables)
                "sequence" -> SequenceMode(n, variables)
                else -> MathError("不支持的数学模式 / Unsupported math mode")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "本地计算 · 有限排版，非完整 TeX / 非 CAS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { values = sliders.map { it.initial }; resetTick++ },
                modifier = Modifier.testTag("ui-math-reset"),
            ) { Text("重置") }
        }
    }
}

// --------------------------------------------------------------------- shared

private data class MathSlider(val name: String, val label: String, val min: Double, val max: Double, val initial: Double)

private fun readSliders(n: JSONObject): List<MathSlider> {
    val array = n.optJSONArray("sliders") ?: return emptyList()
    return (0 until array.length()).map { i ->
        val s = array.getJSONObject(i)
        MathSlider(s.getString("name"), s.getString("label"), s.getDouble("min"), s.getDouble("max"), s.getDouble("value"))
    }
}

@Composable
private fun SliderPanel(sliders: List<MathSlider>, values: List<Double>, onChange: (Int, Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        sliders.forEachIndexed { index, slider ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(slider.label, style = MaterialTheme.typography.labelLarge)
                Text(
                    "${slider.name} = ${fmt(values[index])}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("ui-math-slider-value-${slider.name}"),
                )
            }
            Slider(
                value = values[index].toFloat().coerceIn(slider.min.toFloat(), slider.max.toFloat()),
                onValueChange = { onChange(index, it.toDouble()) },
                valueRange = slider.min.toFloat()..slider.max.toFloat(),
                modifier = Modifier.fillMaxWidth().testTag("ui-math-slider-${slider.name}"),
                steps = 0,
            )
        }
    }
}

@Composable
private fun MathError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("ui-math-error"),
    )
}

@Composable
private fun ValueRow(label: String, value: String, tag: String? = null, emphasis: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            modifier = if (tag == null) Modifier else Modifier.testTag(tag),
        )
    }
}

@Composable
private fun FormulaBadge(source: String, tag: String? = null, size: Int = 18) {
    Box(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        UiFormulaView(source, tag = tag, fontSize = size.sp)
    }
}

private fun fmt(value: Double): String {
    if (!value.isFinite()) return "未定义"
    if (abs(value) < 1e-12) return "0"
    if (abs(value) >= 1e6 || abs(value) < 1e-4) return String.format(Locale.ROOT, "%.3e", value)
    val factor = 10.0.pow(4)
    val rounded = round(value * factor) / factor
    return if (rounded == floor(rounded) && abs(rounded) < 1e15) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.4f", rounded).trimEnd('0').trimEnd('.')
    }
}

private data class Eval(val value: Double?, val error: String?)

private fun evaluate(expression: String, variables: Map<String, Double>): Eval =
    runCatching { UiExpression.evaluate(expression, variables) }.fold(
        onSuccess = { Eval(it, null) },
        onFailure = { Eval(null, it.message ?: "表达式无法计算") },
    )

@Composable
private fun EmptyNotice(message: String, tag: String = "ui-math-no-result") {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(tag))
}

// --------------------------------------------------------------------- formula

@Composable
private fun FormulaMode(n: JSONObject) {
    Text("公式排版", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    FormulaBadge(n.getString("expression"), tag = "ui-math-formula", size = 21)
    if (n.has("result")) {
        Text("结果", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FormulaBadge(n.getString("result"), tag = "ui-math-formula-result", size = 19)
    }
    val steps = n.optJSONArray("steps")
    if (steps != null) {
        Text("推导步骤（示例）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        repeat(steps.length()) { i ->
            val step = steps.getJSONObject(i)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Box(Modifier.width(6.dp))
                    UiFormulaView(step.getString("formula"), tag = "ui-math-step-$i", fontSize = 17.sp)
                }
                if (step.has("note")) {
                    Text(
                        step.getString("note"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 18.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------- plot primitives

private data class PlotBounds(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double)

private fun bounds(n: JSONObject): PlotBounds =
    PlotBounds(n.getDouble("xMin"), n.getDouble("xMax"), n.getDouble("yMin"), n.getDouble("yMax"))

private data class SeriesData(val segments: List<List<Pair<Double, Double>>>, val error: String?)

/** 241 fixed samples; poles and domain holes break the polyline instead of joining across them. */
private fun sampleFunction(expression: String, b: PlotBounds, vars: Map<String, Double>): SeriesData {
    val samples = 241
    val segments = mutableListOf<List<Pair<Double, Double>>>()
    var current = mutableListOf<Pair<Double, Double>>()
    var lastY: Double? = null
    var firstError: String? = null
    var success = 0
    val ySpan = b.yMax - b.yMin
    for (i in 0..samples) {
        val x = b.xMin + (b.xMax - b.xMin) * i / samples
        val result = evaluate(expression, vars + ("x" to x))
        val y = result.value
        if (y == null || !y.isFinite()) {
            if (firstError == null) firstError = result.error
            if (current.isNotEmpty()) { segments += current; current = mutableListOf() }
            lastY = null
            continue
        }
        success++
        val previous = lastY
        if (previous != null) {
            val pole = y * previous < 0 && (abs(y) > ySpan * 3 || abs(previous) > ySpan * 3)
            val jump = abs(y - previous) > ySpan * 12
            if (pole || jump) {
                if (current.isNotEmpty()) segments += current
                current = mutableListOf()
            }
        }
        current.add(x to y)
        lastY = y
    }
    if (current.isNotEmpty()) segments += current
    return if (success == 0) SeriesData(emptyList(), firstError ?: "定义域内没有可绘制的值") else SeriesData(segments, null)
}



private fun sampleParametric(xExpr: String, yExpr: String, tMin: Double, tMax: Double, vars: Map<String, Double>, samples: Int = 321): ParamSeries {
    val segments = mutableListOf<List<ParamSample>>()
    var current = mutableListOf<ParamSample>()
    var firstError: String? = null
    var success = 0
    fun breakHere() {
        if (current.isNotEmpty()) { segments += current; current = mutableListOf() }
    }
    for (i in 0..samples) {
        val t = tMin + (tMax - tMin) * i / samples
        val x = evaluate(xExpr, vars + ("t" to t))
        val y = evaluate(yExpr, vars + ("t" to t))
        if (x.value == null || y.value == null || !x.value.isFinite() || !y.value.isFinite()) {
            if (firstError == null) firstError = x.error ?: y.error
            breakHere()
            continue
        }
        success++
        // t travels with the painted point: the gesture pass resolves a tap to this sample's
        // real parameter instead of deriving t from the horizontal position.
        current.add(ParamSample(t, x.value, y.value))
    }
    breakHere()
    return if (success == 0) ParamSeries(emptyList(), firstError ?: "参数区间内没有可绘制的点") else ParamSeries(segments, null)
}

private fun samplePolar(expression: String, tMin: Double, tMax: Double, vars: Map<String, Double>, samples: Int = 361): ParamSeries {
    val segments = mutableListOf<List<ParamSample>>()
    var current = mutableListOf<ParamSample>()
    var firstError: String? = null
    var success = 0
    fun breakHere() {
        if (current.isNotEmpty()) { segments += current; current = mutableListOf() }
    }
    for (i in 0..samples) {
        val t = tMin + (tMax - tMin) * i / samples
        val r = evaluate(expression, vars + ("t" to t))
        val radius = r.value
        if (radius == null || !radius.isFinite() || abs(radius) > 1e6) {
            if (firstError == null) firstError = r.error ?: "极径超出可绘制范围"
            breakHere()
            continue
        }
        success++
        // θ rides along with the painted point, so a tap at the top of a polar circle
        // resolves to θ ≈ π/2 instead of the left-hand θ = π.
        current.add(ParamSample(t, radius * cos(t), radius * sin(t)))
    }
    breakHere()
    return if (success == 0) ParamSeries(emptyList(), firstError ?: "角度区间内没有可绘制的点") else ParamSeries(segments, null)
}

private fun mathPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG)

private data class MathColors(val axis: Color, val grid: Color, val primary: Color, val surface: Color)

@Composable
private fun mathColors(): MathColors = MathColors(
    MaterialTheme.colorScheme.onSurfaceVariant,
    MaterialTheme.colorScheme.outlineVariant,
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.surfaceVariant,
)

@Composable
private fun PlotCanvas(
    description: String,
    tag: String,
    modifier: Modifier = Modifier,
    onPoint: ((xPx: Float, yPx: Float, rect: PlotRectPx) -> Unit)? = null,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.(MathColors) -> Unit,
) {
    val colors = mathColors()
    // The latest callback is read at gesture time: a recomposition with new bounds
    // must never keep the pointerInput coroutine's first (stale) closure.
    val currentOnPoint by rememberUpdatedState(onPoint)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .testTag(tag)
            .semantics { contentDescription = description }
            .then(
                if (onPoint == null) Modifier else Modifier.pointerInput(tag) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Geometry is derived per gesture from the live node size + density,
                        // never from values captured when the pointerInput block was created.
                        val rect = UiPlotGeometry.rectFor(size.width.toFloat(), size.height.toFloat(), density)
                        fun report(x: Float, y: Float) {
                            currentOnPoint?.invoke(x, y, rect)
                        }
                        // The chart takes the gesture it was started on: consuming the down
                        // and every move keeps a parent vertical scroller from breaking the
                        // loop with an already-consumed change, so a finger inside the plot
                        // keeps dragging the point. Touches outside the plot never reach this
                        // node, so the surrounding list still scrolls normally.
                        down.consume()
                        report(down.position.x, down.position.y)
                        val pointer = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointer } ?: break
                            val stillPressed = change.pressed
                            change.consume()
                            if (!stillPressed) break
                            if (change.position != change.previousPosition) {
                                report(change.position.x, change.position.y)
                            }
                        }
                    }
                },
            ),
    ) {
        draw(colors)
        val paint = mathPaint().apply { textSize = 9.sp.toPx(); color = colors.axis.toArgb() }
        drawContext.canvas.nativeCanvas.drawText("X", size.width - 18.dp.toPx(), size.height - 6.dp.toPx(), paint)
        drawContext.canvas.nativeCanvas.drawText("Y", 4.dp.toPx(), 16.dp.toPx(), paint)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.gridAndAxes(b: PlotBounds, colors: MathColors) {
    val rect = plotRect()
    val left = rect.left; val top = rect.top; val w = rect.width; val h = rect.height
    val grid = colors.grid
    for (i in 0..4) {
        val x = left + w * i / 4f
        val y = top + h * i / 4f
        drawLine(grid, Offset(x, top), Offset(x, top + h), 1f)
        drawLine(grid, Offset(left, y), Offset(left + w, y), 1f)
    }
    val zeroX = left + w * ((0.0 - b.xMin) / (b.xMax - b.xMin)).toFloat()
    val zeroY = top + h * ((b.yMax - 0.0) / (b.yMax - b.yMin)).toFloat()
    val axis = colors.axis
    if (zeroX in left..(left + w)) drawLine(axis, Offset(zeroX, top), Offset(zeroX, top + h), 1.6f)
    if (zeroY in top..(top + h)) drawLine(axis, Offset(left, zeroY), Offset(left + w, zeroY), 1.6f)
}

/** One pixel geometry shared by every draw helper and by [PlotCanvas]'s gesture mapping. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.plotRect(): PlotRectPx =
    UiPlotGeometry.rectFor(
        size.width,
        size.height,
        PlotInsetsPx(38.dp.toPx(), 12.dp.toPx(), 10.dp.toPx(), 24.dp.toPx()),
    )

private fun androidx.compose.ui.graphics.drawscope.DrawScope.mapPoint(b: PlotBounds, x: Double, y: Double): Offset {
    val rect = plotRect()
    return Offset(
        UiPlotGeometry.screenX(x, b.xMin, b.xMax, rect, clamp = false),
        UiPlotGeometry.screenY(y, b.yMin, b.yMax, rect, clamp = false),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.plotClip(block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    val rect = plotRect()
    clipRect(rect.left, rect.top, rect.right, rect.bottom) { block() }
}

@Composable
private fun PlotReadout(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag(tag))
    }
}
// ------------------------------------------------------------- function plot

@Composable
private fun FunctionPlotMode(n: JSONObject, vars: Map<String, Double>) {
    val expression = n.getString("expression")
    val b = bounds(n)
    val data = remember(expression, b, vars) { sampleFunction(expression, b, vars) }
    if (data.error != null && data.segments.isEmpty()) {
        MathError("无法绘制：${data.error}")
        return
    }
    var probeX by remember { mutableStateOf((b.xMin + b.xMax) / 2) }
    val probe = remember(expression, probeX, vars) { evaluate(expression, vars + ("x" to probeX)) }
    Text("y = $expression", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    PlotCanvas(
        description = "函数图像 y=${expression}，x 从 ${fmt(b.xMin)} 到 ${fmt(b.xMax)}",
        tag = "ui-math-plot",
        onPoint = { xPx, _, rect ->
            val (fx, _) = UiPlotGeometry.fractionAt(xPx, rect.centerY, rect)
            probeX = UiPlotGeometry.dataX(fx, b.xMin, b.xMax)
        },
    ) { c ->
        gridAndAxes(b, c)
        plotClip {
            data.segments.forEach { segment ->
                if (segment.size < 2) return@forEach
                val path = Path()
                segment.forEachIndexed { index, point ->
                    val p = mapPoint(b, point.first, point.second)
                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, c.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        val y = probe.value
        if (y != null && y.isFinite() && y in b.yMin..b.yMax) {
            val p = mapPoint(b, probeX, y)
            drawCircle(c.primary, 5.dp.toPx(), p)
            val pr = plotRect()
            drawLine(c.primary.copy(alpha = 0.35f), Offset(p.x, pr.top), Offset(p.x, pr.bottom), 1f)
        }
    }
    if (probe.value == null) {
        EmptyNotice("探测点不可计算：${probe.error}", "ui-math-probe-error")
    } else {
        PlotReadout("探测点", "f(${fmt(probeX)}) = ${fmt(probe.value)}", "ui-math-probe-value")
    }
    Text("点按或拖动图像移动探测点；滑块参数实时重算（固定 241 采样）。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------------------------------------------------------------- parametric

@Composable
private fun ParametricMode(n: JSONObject, vars: Map<String, Double>) {
    val xExpr = n.getString("expression")
    val yExpr = n.getString("expressionY")
    val b = bounds(n)
    val tMin = n.getDouble("tMin")
    val tMax = n.getDouble("tMax")
    val data = remember(xExpr, yExpr, tMin, tMax, vars) { sampleParametric(xExpr, yExpr, tMin, tMax, vars) }
    if (data.segments.isEmpty()) {
        MathError("无法绘制参数曲线：${data.error}")
        return
    }
    var probeT by remember { mutableStateOf((tMin + tMax) / 2) }
    val px = remember(xExpr, probeT, vars) { evaluate(xExpr, vars + ("t" to probeT)) }
    val py = remember(yExpr, probeT, vars) { evaluate(yExpr, vars + ("t" to probeT)) }
    Text("x(t) = $xExpr    y(t) = $yExpr", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    PlotCanvas(
        description = "参数曲线 x=${xExpr}, y=${yExpr}, t 从 ${fmt(tMin)} 到 ${fmt(tMax)}",
        tag = "ui-math-plot",
        onPoint = { xPx, yPx, rect ->
            // Nearest painted sample in pixels, carrying its own t: a vertical tap at the
            // top of a loop resolves to the top's t, not to the t that shares its column.
            UiPlotParamHit.nearestT(
                data.segments, xPx, yPx,
                b.xMin, b.xMax, b.yMin, b.yMax, rect,
            )?.let { probeT = it }
        },
    ) { c ->
        gridAndAxes(b, c)
        plotClip {
            data.segments.forEach { segment ->
                if (segment.size < 2) return@forEach
                val path = Path()
                segment.forEachIndexed { index, point ->
                    val p = mapPoint(b, point.x, point.y)
                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, c.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        val x = px.value
        val y = py.value
        if (x != null && y != null && x.isFinite() && y.isFinite()) {
            drawCircle(c.primary, 5.dp.toPx(), mapPoint(b, x, y))
        }
    }
    if (px.value == null || py.value == null) {
        EmptyNotice("探测点不可计算：${px.error ?: py.error}", "ui-math-probe-error")
    } else {
        PlotReadout("探测点", "t=${fmt(probeT)} → (${fmt(px.value)}, ${fmt(py.value)})", "ui-math-probe-value")
    }
    Text("点按或拖动图像沿曲线移动探测点；固定 321 采样。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// --------------------------------------------------------------------- polar

@Composable
private fun PolarMode(n: JSONObject, vars: Map<String, Double>) {
    val expression = n.getString("expression")
    val b = bounds(n)
    val tMin = n.getDouble("tMin")
    val tMax = n.getDouble("tMax")
    val data = remember(expression, tMin, tMax, vars) { samplePolar(expression, tMin, tMax, vars) }
    if (data.segments.isEmpty()) {
        MathError("无法绘制极坐标曲线：${data.error}")
        return
    }
    var probeT by remember { mutableStateOf((tMin + tMax) / 2) }
    val probe = remember(expression, probeT, vars) { evaluate(expression, vars + ("t" to probeT)) }
    Text("r(θ) = $expression", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    PlotCanvas(
        description = "极坐标 r=${expression}, θ 从 ${fmt(tMin)} 到 ${fmt(tMax)}",
        tag = "ui-math-plot",
        onPoint = { xPx, yPx, rect ->
            // The hit is the painted point under the finger, carrying its own θ. Mapping
            // the column back to θ would put θ = π (the left end) under a tap at the top.
            UiPlotParamHit.nearestT(
                data.segments, xPx, yPx,
                b.xMin, b.xMax, b.yMin, b.yMax, rect,
            )?.let { probeT = it }
        },
    ) { c ->
        gridAndAxes(b, c)
        plotClip {
            data.segments.forEach { segment ->
                if (segment.size < 2) return@forEach
                val path = Path()
                segment.forEachIndexed { index, point ->
                    val p = mapPoint(b, point.x, point.y)
                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, c.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        val r = probe.value
        if (r != null && r.isFinite()) {
            val p = mapPoint(b, r * cos(probeT), r * sin(probeT))
            drawCircle(c.primary, 5.dp.toPx(), p)
            drawLine(c.primary.copy(alpha = 0.4f), mapPoint(b, 0.0, 0.0), p, 1f)
        }
    }
    if (probe.value == null) {
        EmptyNotice("探测点不可计算：${probe.error}", "ui-math-probe-error")
    } else {
        PlotReadout("探测点", "θ=${fmt(probeT)} → r=${fmt(probe.value)}", "ui-math-probe-value")
    }
    Text("角度以弧度表示；点按或拖动图像沿曲线移动探测点。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ----------------------------------------------------------------- implicit

private data class ImplicitData(
    val lines: List<Pair<Pair<Double, Double>, Pair<Double, Double>>>,
    val fills: List<Pair<Double, Double>>,
    val error: String?,
)

private fun sampleImplicit(expression: String, comparator: String, b: PlotBounds, vars: Map<String, Double>): ImplicitData {
    val cells = 32
    val grid = Array(cells + 1) { i ->
        DoubleArray(cells + 1) { j ->
            val x = b.xMin + (b.xMax - b.xMin) * i / cells
            val y = b.yMin + (b.yMax - b.yMin) * j / cells
            evaluate(expression, vars + ("x" to x) + ("y" to y)).value ?: Double.NaN
        }
    }
    val lines = mutableListOf<Pair<Pair<Double, Double>, Pair<Double, Double>>>()
    val fills = mutableListOf<Pair<Double, Double>>()
    var evaluated = 0
    var firstError: String? = null
    for (i in 0 until cells) for (j in 0 until cells) {
        val x0 = b.xMin + (b.xMax - b.xMin) * i / cells
        val x1 = b.xMin + (b.xMax - b.xMin) * (i + 1) / cells
        val y0 = b.yMin + (b.yMax - b.yMin) * j / cells
        val y1 = b.yMin + (b.yMax - b.yMin) * (j + 1) / cells
        val v0 = grid[i][j]; val v1 = grid[i + 1][j]; val v2 = grid[i + 1][j + 1]; val v3 = grid[i][j + 1]
        if (!v0.isFinite() || !v1.isFinite() || !v2.isFinite() || !v3.isFinite()) {
            if (firstError == null) {
                firstError = evaluate(expression, vars + ("x" to (x0 + x1) / 2) + ("y" to (y0 + y1) / 2)).error
            }
            continue
        }
        evaluated++
        if (comparator == "=") {
            val crossings = mutableListOf<Pair<Double, Double>>()
            fun edge(ax: Double, ay: Double, av: Double, bx: Double, by: Double, bv: Double) {
                if (av == 0.0) { crossings += ax to ay; return }
                if (av * bv < 0) {
                    val t = av / (av - bv)
                    crossings += (ax + (bx - ax) * t) to (ay + (by - ay) * t)
                }
            }
            edge(x0, y0, v0, x1, y0, v1); edge(x1, y0, v1, x1, y1, v2)
            edge(x1, y1, v2, x0, y1, v3); edge(x0, y1, v3, x0, y0, v0)
            if (crossings.size >= 2) {
                lines += (crossings[0].first to crossings[0].second) to (crossings[1].first to crossings[1].second)
                if (crossings.size >= 4) lines += (crossings[2].first to crossings[2].second) to (crossings[3].first to crossings[3].second)
            }
        } else {
            val centerValue = evaluate(expression, vars + ("x" to (x0 + x1) / 2) + ("y" to (y0 + y1) / 2)).value ?: continue
            val trueCell = when (comparator) {
                "<" -> centerValue < 0; "<=" -> centerValue <= 0
                ">" -> centerValue > 0; else -> centerValue >= 0
            }
            if (trueCell) fills += ((x0 + x1) / 2) to ((y0 + y1) / 2)
        }
    }
    return if (evaluated == 0) ImplicitData(emptyList(), emptyList(), firstError ?: "定义域内没有可计算的点") else ImplicitData(lines, fills, null)
}

@Composable
private fun ImplicitMode(n: JSONObject, vars: Map<String, Double>) {
    val expression = n.getString("expression")
    val comparator = n.getString("comparator")
    val b = bounds(n)
    val data = remember(expression, comparator, b, vars) { sampleImplicit(expression, comparator, b, vars) }
    if (data.error != null) {
        MathError("无法绘制隐式关系：${data.error}")
        return
    }
    var probe by remember { mutableStateOf(((b.xMin + b.xMax) / 2) to ((b.yMin + b.yMax) / 2)) }
    val probeValue = remember(expression, probe, vars) { evaluate(expression, vars + ("x" to probe.first) + ("y" to probe.second)) }
    Text("$expression $comparator 0", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    PlotCanvas(
        description = "隐式关系 ${expression} ${comparator} 0 的图像",
        tag = "ui-math-plot",
        onPoint = { xPx, yPx, rect ->
            val (fx, fy) = UiPlotGeometry.fractionAt(xPx, yPx, rect)
            probe = UiPlotGeometry.dataX(fx, b.xMin, b.xMax) to UiPlotGeometry.dataY(fy, b.yMin, b.yMax)
        },
    ) { c ->
        gridAndAxes(b, c)
        plotClip {
        data.fills.forEach { cell ->
            val p = mapPoint(b, cell.first, cell.second)
            val pr = plotRect()
            val cellW = pr.width / 32f
            val cellH = pr.height / 32f
            drawRect(c.primary.copy(alpha = 0.12f), Offset(p.x - cellW / 2, p.y - cellH / 2), Size(cellW, cellH))
        }
        data.lines.forEach { line ->
            drawLine(c.primary, mapPoint(b, line.first.first, line.first.second), mapPoint(b, line.second.first, line.second.second), 2.dp.toPx())
        }
        }
        val value = probeValue.value
        if (value != null && value.isFinite()) {
            drawCircle(c.primary, 4.dp.toPx(), mapPoint(b, probe.first, probe.second))
        }
    }
    if (probeValue.value == null) {
        EmptyNotice("探测点不可计算：${probeValue.error}", "ui-math-probe-error")
    } else {
        PlotReadout("探测点", "f(${fmt(probe.first)}, ${fmt(probe.second)}) = ${fmt(probeValue.value)}", "ui-math-probe-value")
    }
    Text("点按或拖动图像选点；等号绘制零等值线，不等式填充满足区域。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------------------------------------------------------------- surface3d

@Composable
private fun SurfaceMode(n: JSONObject, vars: Map<String, Double>) {
    val expression = n.getString("expression")
    val xMin = n.getDouble("xMin"); val xMax = n.getDouble("xMax")
    val yMin = n.getDouble("yMin"); val yMax = n.getDouble("yMax")
    val zMin = n.getDouble("zMin"); val zMax = n.getDouble("zMax")
    var azimuth by remember { mutableStateOf(n.optDouble("azimuth", -35.0)) }
    var elevation by remember { mutableStateOf(n.optDouble("elevation", 28.0)) }
    val grid = remember(expression, xMin, xMax, yMin, yMax, vars) {
        val steps = 16
        var failures = 0
        var message: String? = null
        val values = (0..steps).map { i ->
            (0..steps).map { j ->
                val x = xMin + (xMax - xMin) * i / steps
                val y = yMin + (yMax - yMin) * j / steps
                val r = evaluate(expression, vars + ("x" to x) + ("y" to y))
                if (r.value == null || !r.value.isFinite()) { failures++; if (message == null) message = r.error }
                r.value?.takeIf { it.isFinite() }
            }
        }
        if (failures >= (steps + 1) * (steps + 1)) Triple(emptyList<List<Double?>>(), failures, message) else Triple(values, failures, message)
    }
    if (grid.first.isEmpty()) {
        MathError("无法绘制曲面：${grid.third ?: "定义域内没有可计算的值"}")
        return
    }
    val points = remember(grid.first, azimuth, elevation) { projectSurface(grid.first, zMin, zMax, azimuth, elevation) }
    val colors = mathColors()
    Box(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(240.dp).testTag("ui-math-surface")
                .semantics { contentDescription = "三维曲面线框 z=$expression，拖动旋转视角" }
                .pointerInput("surface3d") {
                    // Rotation stays pixel-independent: one drag step is dp-normalised so
                    // the same physical finger travel rotates the same amount on any density.
                    detectDragGestures { change, drag ->
                        change.consume()
                        val dx = drag.x / density
                        val dy = drag.y / density
                        azimuth = ((azimuth + dx * 0.4) % 360 + 360) % 360
                        elevation = (elevation - dy * 0.35).coerceIn(-89.0, 89.0)
                    }
                },
        ) {
            val cx = size.width / 2f; val cy = size.height / 2f
            val scale = min(size.width, size.height) * 0.38f
            fun screen(p: Triple<Double, Double, Double>) = Offset(cx + (p.first * scale).toFloat(), cy - (p.second * scale).toFloat())
            val rows = points.size; val cols = points.firstOrNull()?.size ?: 0
            for (i in 0 until rows) for (j in 0 until cols) {
                val a = points[i][j] ?: continue
                if (j + 1 < cols) points[i][j + 1]?.let { b -> drawLine(colors.primary.copy(alpha = 0.65f), screen(a), screen(b), 1.2f) }
                if (i + 1 < rows) points[i + 1][j]?.let { b -> drawLine(colors.primary.copy(alpha = 0.45f), screen(a), screen(b), 1.2f) }
            }
        }
    }
    val stats = remember(grid.second) { grid.second }
    if (stats > 0) {
        Text("有 $stats 个采样点超出定义域，已断开。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    PlotReadout("视角", "方位 ${fmt(azimuth)}° · 仰角 ${fmt(elevation)}°（拖动旋转）", "ui-math-view")
    Text("固定 17×17 线框采样；z 范围裁剪显示。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Returns projected (screenX, screenY, depth) per grid point, or null where z is non-finite/out of range. */
private fun projectSurface(
    grid: List<List<Double?>>,
    zMin: Double,
    zMax: Double,
    azimuth: Double,
    elevation: Double,
): List<List<Triple<Double, Double, Double>?>> {
    val a = Math.toRadians(azimuth)
    val e = Math.toRadians(elevation)
    val rows = grid.size
    val cols = grid.firstOrNull()?.size ?: 0
    return (0 until rows).map { i ->
        (0 until cols).map { j ->
            val z = grid[i][j] ?: return@map null
            if (z < zMin || z > zMax) return@map null
            val x = if (cols <= 1) 0.0 else i.toDouble() / (cols - 1) * 2 - 1
            val y = if (rows <= 1) 0.0 else j.toDouble() / (rows - 1) * 2 - 1
            val zz = z / max(abs(zMin), abs(zMax)).coerceAtLeast(1e-9)
            val xr = x * cos(a) - y * sin(a)
            val yr = x * sin(a) + y * cos(a)
            val sx = xr
            val sy = zz * cos(e) - yr * sin(e)
            Triple(sx, sy, yr)
        }
    }
}
// ----------------------------------------------------------------- calculus

@Composable
private fun CalculusMode(n: JSONObject, vars: Map<String, Double>) {
    val expression = n.getString("expression")
    val a = n.getDouble("a"); val b = n.getDouble("b"); val x0 = n.getDouble("x0")
    val f0 = remember(expression, x0, vars) { evaluate(expression, vars + ("x" to x0)) }
    val derivative = remember(expression, x0, vars) {
        runCatching { UiMathEngine.derivative(expression, x0, vars) }
    }
    val integral = remember(expression, a, b, vars) {
        runCatching { UiMathEngine.integrate(expression, a, b, vars) }
    }
    Text("f(x) = $expression", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    Text("区间 [${fmt(a)}, ${fmt(b)}] · 切点 x₀ = ${fmt(x0)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    derivative.exceptionOrNull()?.let { MathError("导数不可用：${it.message ?: "定义域或表达式错误"}") }
    integral.exceptionOrNull()?.let { MathError("积分不可用：${it.message ?: "定义域或表达式错误"}") }
    if (derivative.isSuccess) {
        ValueRow("数值导数 f′(x₀)", fmt(derivative.getOrThrow()), "ui-math-derivative", emphasis = true)
    }
    if (integral.isSuccess) {
        ValueRow("定积分 ∫ₐᵇ f(x) dx", fmt(integral.getOrThrow()), "ui-math-integral", emphasis = true)
    }
    if (derivative.isSuccess && f0.value != null) {
        val m = derivative.getOrThrow()
        ValueRow(
            "切线",
            "y = ${fmt(m)}(x − ${fmt(x0)}) + ${fmt(f0.value)}",
            "ui-math-tangent",
        )
    }
    Text("数值方法（中心差分 / 有界数值积分），非符号 CAS；结果受步长与定义域限制。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ----------------------------------------------------------------- equation

@Composable
private fun EquationMode(n: JSONObject, vars: Map<String, Double>) {
    val expression = n.getString("expression")
    val a = n.getDouble("a"); val b = n.getDouble("b")
    val fExpr = remember(expression) {
        val parts = expression.split("=", limit = 2)
        "(${parts[0]})-(${parts[1]})"
    }
    val roots = remember(fExpr, a, b, vars) { runCatching { UiMathEngine.roots(fExpr, a, b, vars) } }
    Text(expression, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    Text("有界求根区间 [${fmt(a)}, ${fmt(b)}]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val failure = roots.exceptionOrNull()
    if (failure != null) {
        MathError("求根失败：${failure.message ?: "表达式或区间无效"}")
        return
    }
    val list = roots.getOrThrow().filter { it.isFinite() }.sorted()
    if (list.isEmpty()) {
        EmptyNotice("区间内未找到实根；这不代表方程无解。", "ui-math-roots")
    } else {
        Text(
            list.take(20).mapIndexed { i, r -> "x${i + 1} ≈ ${fmt(r)}" }.joinToString("  ·  "),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("ui-math-roots"),
        )
        Text("共 ${list.size} 个（上限 20 个显示）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("仅在给定区间内数值搜索；不保证找到全部根或重根。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ------------------------------------------------------------------- matrix

private fun matrixOf(n: JSONObject): List<List<Double>> {
    val array = n.getJSONArray("matrix")
    return (0 until array.length()).map { r ->
        val row = array.getJSONArray(r)
        (0 until row.length()).map { c -> row.getDouble(c) }
    }
}

@Composable
private fun MatrixGrid(rows: List<List<Double>>, tag: String) {
    Column(
        Modifier.horizontalScroll(rememberScrollState()).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { value ->
                    Text(
                        fmt(value),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(58.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MatrixMode(n: JSONObject) {
    val matrix = remember(n.toString()) { matrixOf(n) }
    MatrixGrid(matrix, "ui-math-matrix")
    val det = remember(matrix) { runCatching { UiMathEngine.determinant(matrix) } }
    if (det.isSuccess) ValueRow("行列式 det(A)", fmt(det.getOrThrow()), "ui-math-det", emphasis = true)
    else MathError("行列式不可用：${det.exceptionOrNull()?.message ?: "矩阵无效"}")
    val inverse = remember(matrix) { runCatching { UiMathEngine.inverse(matrix) } }
    if (inverse.isSuccess) {
        Text("逆矩阵 A⁻¹", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MatrixGrid(inverse.getOrThrow(), "ui-math-inverse")
    } else {
        Text(
            "逆矩阵不存在：${inverse.exceptionOrNull()?.message ?: "矩阵奇异"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("ui-math-inverse-error"),
        )
    }
    val rhs = n.optJSONArray("rhs")
    if (rhs != null && rhs.length() == matrix.size) {
        val vector = (0 until rhs.length()).map { rhs.getDouble(it) }
        val solution = remember(matrix, vector) { runCatching { UiMathEngine.solve(matrix, vector) } }
        if (solution.isSuccess) {
            ValueRow("解 A x = b", solution.getOrThrow().mapIndexed { i, v -> "x${i + 1}=${fmt(v)}" }.joinToString(", "), "ui-math-solve", emphasis = true)
        } else {
            Text(
                "线性方程组无唯一解：${solution.exceptionOrNull()?.message ?: "矩阵奇异"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("ui-math-solve-error"),
            )
        }
    }
    Text("行列式 / 逆 / 线性方程仅支持 2..6 阶方阵；数值结果四舍五入显示。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// --------------------------------------------------------------- statistics

@Composable
private fun StatisticsMode(n: JSONObject) {
    val values = remember(n.toString()) {
        val array = n.getJSONArray("values")
        (0 until array.length()).map { array.getDouble(it) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        values.forEach { Text(fmt(it), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
    val stats = remember(values) { runCatching { UiMathEngine.statistics(values) } }
    val failure = stats.exceptionOrNull()
    if (failure != null) {
        MathError("统计不可用：${failure.message ?: "数据无效"}")
        return
    }
    val s = stats.getOrThrow()
    ValueRow("样本数 count", fmt(s["count"] ?: Double.NaN), "ui-math-count")
    ValueRow("均值 mean", fmt(s["mean"] ?: Double.NaN), "ui-math-mean", emphasis = true)
    ValueRow("中位数 median", fmt(s["median"] ?: Double.NaN), "ui-math-median")
    ValueRow("最小值 min", fmt(s["min"] ?: Double.NaN), "ui-math-min")
    ValueRow("最大值 max", fmt(s["max"] ?: Double.NaN), "ui-math-max")
    ValueRow("样本方差 variance (n−1)", fmt(s["variance"] ?: Double.NaN), "ui-math-variance")
    ValueRow("样本标准差 stddev (n−1)", fmt(s["stddev"] ?: Double.NaN), "ui-math-stddev")
    Text(
        "最多 ${UiMathSchema.MAX_STAT_VALUES} 个数值。方差/标准差为样本口径（除以 n−1）；单个样本时共享引擎约定返回 0，这不是数学定义。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// -------------------------------------------------------------- probability

@Composable
private fun ProbabilityMode(n: JSONObject) {
    val trials = n.getInt("n")
    val successes = n.getInt("k")
    val p = n.getDouble("p")
    val c = remember(trials, successes) { runCatching { UiMathEngine.combinations(trials, successes) } }
    val perm = remember(trials, successes) { runCatching { UiMathEngine.permutations(trials, successes) } }
    val pmf = remember(trials, successes, p) {
        runCatching {
            val coefficient = UiMathEngine.combinations(trials, successes)
            if (p == 0.0 && successes == 0) coefficient * 1.0
            else coefficient * p.pow(successes) * (1 - p).pow(trials - successes)
        }
    }
    Text("n = $trials · k = $successes · p = ${fmt(p)}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    c.exceptionOrNull()?.let { MathError("组合数不可用：${it.message ?: "参数无效"}") }
    perm.exceptionOrNull()?.let { MathError("排列数不可用：${it.message ?: "参数无效"}") }
    if (c.isSuccess) ValueRow("组合 C(n, k)", fmt(c.getOrThrow()), "ui-math-combinations", emphasis = true)
    if (perm.isSuccess) ValueRow("排列 P(n, k)", fmt(perm.getOrThrow()), "ui-math-permutations")
    if (pmf.isSuccess) ValueRow("二项分布 P(X = k)", fmt(pmf.getOrThrow()), "ui-math-binomial", emphasis = true)
    else MathError("二项分布不可用：${pmf.exceptionOrNull()?.message ?: "参数无效"}")
    ValueRow("期望 np", fmt(trials * p), "ui-math-binomial-mean")
    ValueRow("方差 np(1−p)", fmt(trials * p * (1 - p)), "ui-math-binomial-variance")
    Text("n ≤ ${UiMathSchema.MAX_PROBABILITY_N}，0 ≤ k ≤ n，0 ≤ p ≤ 1；结果为数值近似。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ----------------------------------------------------------------- geometry

@Composable
private fun GeometryMode(n: JSONObject, vars: Map<String, Double>) {
    val shape = n.getString("shape")
    val colors = mathColors()
    if (shape == "circle") {
        val r = vars["r"] ?: n.getDouble("r")
        if (!r.isFinite() || r <= 0) {
            MathError("半径必须为正数（当前 r = ${fmt(r)}）")
            return
        }
        val area = Math.PI * r * r
        val perimeter = 2 * Math.PI * r
        Canvas(
            Modifier.fillMaxWidth().height(200.dp).testTag("ui-math-geometry")
                .semantics { contentDescription = "半径 ${fmt(r)} 的圆" },
        ) {
            val cx = size.width / 2f; val cy = size.height / 2f
            val radius = min(size.width, size.height) * 0.42f
            drawCircle(colors.primary.copy(alpha = 0.08f), radius, Offset(cx, cy))
            drawCircle(colors.primary, radius, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            drawLine(colors.axis, Offset(cx, cy), Offset(cx + radius, cy), 1.5.dp.toPx())
            drawCircle(colors.axis, 3.dp.toPx(), Offset(cx, cy))
            val paint = mathPaint().apply { textSize = 11.sp.toPx(); color = colors.axis.toArgb() }
            drawContext.canvas.nativeCanvas.drawText("r = ${fmt(r)}", cx + radius * 0.2f, cy - 8.dp.toPx(), paint)
        }
        ValueRow("半径 r", fmt(r), "ui-math-radius")
        ValueRow("面积 πr²", fmt(area), "ui-math-geometry-area", emphasis = true)
        ValueRow("周长 2πr", fmt(perimeter), "ui-math-geometry-perimeter")
    } else {
        val a = vars["a"] ?: n.getDouble("a")
        val b = vars["b"] ?: n.getDouble("b")
        val c = vars["c"] ?: n.getDouble("c")
        if (!a.isFinite() || !b.isFinite() || !c.isFinite() || a <= 0 || b <= 0 || c <= 0) {
            MathError("三条边必须为有限正数（当前 a=${fmt(a)}, b=${fmt(b)}, c=${fmt(c)}）")
            return
        }
        if (a + b <= c || a + c <= b || b + c <= a) {
            MathError("当前边长不满足三角形不等式，无法构造三角形。")
            return
        }
        val area = runCatching {
            val s = (a + b + c) / 2
            sqrt(s * (s - a) * (s - b) * (s - c))
        }.getOrElse { Double.NaN }
        val perimeter = a + b + c
        val angleA = Math.toDegrees(kotlin.math.acos(((b * b + c * c - a * a) / (2 * b * c)).coerceIn(-1.0, 1.0)))
        val angleB = Math.toDegrees(kotlin.math.acos(((a * a + c * c - b * b) / (2 * a * c)).coerceIn(-1.0, 1.0)))
        val angleC = 180.0 - angleA - angleB
        Canvas(
            Modifier.fillMaxWidth().height(200.dp).testTag("ui-math-geometry")
                .semantics { contentDescription = "边长 ${fmt(a)}, ${fmt(b)}, ${fmt(c)} 的三角形" },
        ) {
            val ax = 0.0; val ay = 0.0
            val bx = c; val by = 0.0
            val cx = (b * b + c * c - a * a) / (2 * c)
            val cy = sqrt(max(0.0, b * b - cx * cx))
            val spanX = max(cx, bx).coerceAtLeast(1e-9)
            val spanY = cy.coerceAtLeast(1e-9)
            val pad = 26.dp.toPx()
            val scale = min((size.width - pad * 2) / spanX, (size.height - pad * 2) / spanY).toFloat()
            val offsetX = (size.width - spanX.toFloat() * scale) / 2
            val offsetY = (size.height + spanY.toFloat() * scale) / 2
            fun pt(x: Double, y: Double) = Offset(offsetX + (x * scale).toFloat(), offsetY - (y * scale).toFloat())
            val pa = pt(ax, ay); val pb = pt(bx, by); val pc = pt(cx, cy)
            val path = Path().apply { moveTo(pa.x, pa.y); lineTo(pb.x, pb.y); lineTo(pc.x, pc.y); close() }
            drawPath(path, colors.primary.copy(alpha = 0.08f))
            drawPath(path, colors.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            val paint = mathPaint().apply { textSize = 11.sp.toPx(); color = colors.axis.toArgb() }
            drawContext.canvas.nativeCanvas.drawText("A", pa.x - 12.dp.toPx(), pa.y + 4.dp.toPx(), paint)
            drawContext.canvas.nativeCanvas.drawText("B", pb.x + 6.dp.toPx(), pb.y + 4.dp.toPx(), paint)
            drawContext.canvas.nativeCanvas.drawText("C", pc.x - 4.dp.toPx(), pc.y - 6.dp.toPx(), paint)
        }
        ValueRow("边 a / b / c", "${fmt(a)} / ${fmt(b)} / ${fmt(c)}", "ui-math-sides")
        ValueRow("面积（海伦公式）", fmt(area), "ui-math-geometry-area", emphasis = true)
        ValueRow("周长", fmt(perimeter), "ui-math-geometry-perimeter")
        ValueRow("角 A / B / C", "${fmt(angleA)}° / ${fmt(angleB)}° / ${fmt(angleC)}°", "ui-math-angles")
    }
    Text("滑块可覆盖几何参数；绘制为本地示意，非精确工程图。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ----------------------------------------------------------------- sequence

@Composable
private fun SequenceMode(n: JSONObject, vars: Map<String, Double>) {
    val kind = n.getString("kind")
    val termsCount = n.getInt("terms")
    val first = n.getDouble("first")
    val expression = n.optString("expression", "")
    val second = n.optDouble("second", first)
    val difference = n.optDouble("difference", 1.0)
    val ratio = n.optDouble("ratio", 1.0)
    val generated = remember(kind, termsCount, first, second, difference, ratio, expression, vars) {
        val terms = mutableListOf<Double>()
        var failure: String? = null
        for (i in 0 until termsCount) {
            val value: Double? = when (kind) {
                "arithmetic" -> first + difference * i
                "geometric" -> first * ratio.pow(i)
                "fibonacci" -> when (i) { 0 -> first; 1 -> second; else -> terms[i - 1] + terms[i - 2] }
                else -> if (i == 0) first else evaluate(expression, vars + ("n" to (i + 1).toDouble()) + ("a" to terms[i - 1])).let { result ->
                    if (result.value == null) { failure = result.error; null } else result.value
                }
            }
            if (value == null || !value.isFinite() || abs(value) > 1e12) {
                if (failure == null) failure = "第 ${i + 1} 项超出可计算范围"
                break
            }
            terms += value
        }
        terms to failure
    }
    val terms = generated.first
    val sums = remember(terms) {
        val result = mutableListOf<Double>()
        var running = 0.0
        terms.forEach { running += it; result += running }
        result
    }
    val recurrence = when (kind) {
        "arithmetic" -> "a₁ = ${fmt(first)}，aₙ₊₁ = aₙ + ${fmt(difference)}"
        "geometric" -> "a₁ = ${fmt(first)}，aₙ₊₁ = aₙ × ${fmt(ratio)}"
        "fibonacci" -> "a₁ = ${fmt(first)}，a₂ = ${fmt(second)}，aₙ = aₙ₋₁ + aₙ₋₂"
        else -> "a₁ = ${fmt(first)}，aₙ₊₁ = f(n, aₙ)"
    }
    Text(recurrence, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    if (generated.second != null) MathError("序列在第 ${terms.size + 1} 项中断：${generated.second}")
    Row(Modifier.horizontalScroll(rememberScrollState()).testTag("ui-math-sequence-terms"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        terms.forEachIndexed { i, value -> Text("a${i + 1}=${fmt(value)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
    ValueRow("前 ${terms.size} 项和 Sₙ", fmt(sums.lastOrNull() ?: 0.0), "ui-math-sequence-sum", emphasis = true)
    if (terms.size >= 2) {
        val lo = terms.min(); val hi = terms.max()
        val colors = mathColors()
        Canvas(Modifier.fillMaxWidth().height(90.dp).testTag("ui-math-sequence-plot")) {
            val span = (hi - lo).takeIf { it > 1e-12 } ?: 1.0
            val step = size.width / (terms.size - 1).coerceAtLeast(1)
            val path = Path()
            terms.forEachIndexed { i, value ->
                val x = i * step
                val y = size.height - 8.dp.toPx() - ((value - lo) / span * (size.height - 16.dp.toPx())).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(colors.primary, 2.5.dp.toPx(), Offset(x, y))
            }
            drawPath(path, colors.primary.copy(alpha = 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        }
    }
    Text("最多 ${UiMathSchema.MAX_SEQUENCE_TERMS} 项；自定义递推仅可使用 n 与上一项 a。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
