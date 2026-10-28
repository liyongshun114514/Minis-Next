package com.openminis.app.intelligentui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Native interactive tools rendered by the Intelligent UI card.
 *
 * The framework owns title/text for the node; this entry point renders only the
 * tool body. Every control is local: conversions, arithmetic, filtering, ranking,
 * practice grading and wizard paging never call the model, the network, a WebView,
 * or any external action. All data comes from the tool argument and is treated as
 * local example data.
 */
@Composable
internal fun UiInteractiveTool(n: JSONObject) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (n.optString("type")) {
            "converter" -> UiConverter(n)
            "budget" -> UiBudgetTool(n)
            "compare" -> UiCompareTool(n)
            "directory" -> UiDirectoryTool(n)
            "quiz" -> UiQuizTool(n)
            "decision" -> UiDecisionTool(n)
            "wizard" -> UiWizardTool(n)
            else -> UiToolError(l("不支持的交互工具类型", "Unsupported interactive tool type"))
        }
    }
}

// ---------------------------------------------------------------- shared helpers

private fun l(zh: String, en: String): String =
    if (Locale.getDefault().language.lowercase(Locale.ROOT).startsWith("zh")) zh else en

private fun JSONArray.objectAt(index: Int): JSONObject? = opt(index) as? JSONObject

private fun JSONObject.stringList(key: String, limit: Int): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).take(limit).mapNotNull { array.opt(it) as? String }
}

private fun JSONObject.finiteNumber(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

@Composable
private fun UiToolError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("ui-tool-error"),
    )
}

@Composable
private fun UiNote(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun UiFieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

// ---------------------------------------------------------------- converter

@Composable
private fun UiConverter(n: JSONObject) {
    val declared = UiDimension.fromWire(n.optString("dimension"))
    val rawFrom = n.optString("from").trim()
    val rawTo = n.optString("to").trim()
    val fromUnit = declared?.let { UiUnits.resolve(rawFrom, it) } ?: UiUnits.detect(rawFrom)
    val toUnit = declared?.let { UiUnits.resolve(rawTo, it) } ?: UiUnits.detect(rawTo)

    val dimension = declared ?: fromUnit?.dimension ?: toUnit?.dimension ?: UiDimension.LENGTH
    val protocolMismatch = when {
        declared != null && rawFrom.isNotEmpty() && fromUnit == null -> true
        declared != null && rawTo.isNotEmpty() && toUnit == null -> true
        fromUnit != null && toUnit != null && fromUnit.dimension != toUnit.dimension -> true
        else -> false
    }
    val initialFrom = fromUnit?.takeIf { it.dimension == dimension }?.symbol ?: UiUnits.defaultFrom(dimension)
    val initialTo = toUnit?.takeIf { it.dimension == dimension }?.symbol ?: UiUnits.defaultTo(dimension)
    val initialValue = n.finiteNumber("value")?.coerceIn(-UiUnits.MAX_INPUT, UiUnits.MAX_INPUT) ?: 0.0
    val options = remember(dimension) { UiUnits.symbols(dimension) }

    var valueText by rememberSaveable { mutableStateOf(UiFormat.number(initialValue)) }
    var from by rememberSaveable { mutableStateOf(initialFrom) }
    var to by rememberSaveable { mutableStateOf(initialTo) }

    val parsed = UiFormat.parse(valueText)?.takeIf { abs(it) <= UiUnits.MAX_INPUT }
    val result = parsed?.let { UiUnits.convertOrNull(it, from, to, dimension) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = valueText,
            onValueChange = { if (it.length <= 32) valueText = it },
            label = { Text(l("数值", "Value")) },
            singleLine = true,
            isError = parsed == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f).testTag("ui-converter-value"),
        )
        UnitPicker("ui-converter-from", from, options) { from = it }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        UnitPicker("ui-converter-to", to, options) { to = it }
        TextButton(onClick = { val old = from; from = to; to = old }) { Text("\u21C5 " + l("互换", "Swap")) }
    }
    when {
        protocolMismatch -> UiToolError(l("单位与声明维度不匹配，请重新选择单位。", "Units do not match the declared dimension; pick units again."))
        parsed == null -> UiToolError(l("请输入有效数值。", "Enter a valid number."))
        result == null -> UiToolError(l("无法转换该单位组合。", "This unit combination cannot be converted."))
        else -> {
            Text(
                "${UiFormat.number(result)} $to",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("ui-converter-result"),
            )
            UiNote("${UiFormat.number(parsed)} $from = ${UiFormat.number(result)} $to")
        }
    }
}

@Composable
private fun UnitPicker(tag: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag(tag)) {
            Text(selected, maxLines = 1)
            Text(" \u25BE", style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, modifier = Modifier.testTag("$tag-option-$option")) },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- budget

@Composable
private fun UiBudgetTool(n: JSONObject) {
    val currency = n.optString("currency").trim().take(12)
    val symbol = UiFormat.currencySymbol(currency)
    val labels = mutableListOf<String>()
    val defaults = mutableListOf<Double>()
    n.optJSONArray("items")?.let { array ->
        for (i in 0 until minOf(array.length(), UiBudget.MAX_ITEMS)) {
            val item = array.objectAt(i) ?: continue
            labels += item.optString("label").trim().ifEmpty { l("支出 ${labels.size + 1}", "Expense ${labels.size + 1}") }.take(120)
            defaults += UiBudget.sanitizeAmount(item.finiteNumber("amount") ?: 0.0)
        }
    }

    var incomeText by rememberSaveable { mutableStateOf(UiFormat.number(UiBudget.sanitizeAmount(n.finiteNumber("income") ?: 0.0))) }
    var expenseTexts by rememberSaveable { mutableStateOf(ArrayList(defaults.map { UiFormat.number(it) })) }

    val income = UiBudget.parseAmount(incomeText)
    val expenses = expenseTexts.map { UiBudget.parseAmount(it) }
    val rows = labels.mapIndexed { i, label -> UiBudgetItem(label, expenses.getOrNull(i) ?: 0.0) }
    val summary = UiBudget.summarize(income ?: 0.0, rows)

    OutlinedTextField(
        value = incomeText,
        onValueChange = { if (it.length <= 24) incomeText = it },
        label = { Text(l("收入", "Income") + if (symbol.isNotBlank()) " ($currency)" else "") },
        singleLine = true,
        isError = income == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag("ui-budget-income"),
    )
    if (income == null) UiToolError(l("收入必须是 0 到 1 万亿之间的数值。", "Income must be a number between 0 and 1 trillion."))

    if (labels.isEmpty()) {
        UiNote(l("此预算没有支出项目。", "This budget has no expense items."))
    } else {
        labels.forEachIndexed { i, label ->
            val amount = expenses.getOrNull(i)
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        UiFormat.percent(summary.itemPercents.getOrElse(i) { 0.0 }),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = expenseTexts.getOrElse(i) { "" },
                    onValueChange = { text ->
                        if (text.length <= 24) {
                            expenseTexts = ArrayList(expenseTexts).apply {
                                while (size <= i) add("")
                                set(i, text)
                            }
                        }
                    },
                    label = { Text(l("金额", "Amount")) },
                    singleLine = true,
                    isError = amount == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("ui-budget-expense-$i"),
                )
            }
        }
    }

    HorizontalDivider()
    SummaryLine(l("支出合计", "Total expenses"), symbol + UiFormat.money(summary.totalExpense))
    SummaryLine(
        l("余额", "Balance"),
        symbol + UiFormat.money(summary.balance),
        color = if (summary.balance < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        testTag = "ui-budget-balance",
    )
    SummaryLine(
        l("结余率", "Saved"),
        if (summary.income > 0) UiFormat.percent(summary.balancePercent) else "\u2014",
        testTag = "ui-budget-percent",
    )
    if (summary.overBudget) {
        Text(
            l("支出已超过收入 " + symbol + UiFormat.money(-summary.balance), "Expenses exceed income by " + symbol + UiFormat.money(-summary.balance)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    UiNote(l("仅本地计算，不记录或同步任何账目。", "Calculated locally only; nothing is saved or synced."))
}

@Composable
private fun SummaryLine(label: String, value: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, testTag: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
        )
    }
}

// ---------------------------------------------------------------- compare

@Composable
private fun UiCompareTool(n: JSONObject) {
    val items = remember(n.toString()) {
        val array = n.optJSONArray("items")
        if (array == null) emptyList() else (0 until minOf(array.length(), UiCompare.MAX_ITEMS)).mapNotNull { i ->
            val obj = array.objectAt(i) ?: return@mapNotNull null
            val name = obj.optString("name").trim()
            if (name.isEmpty()) null else UiCompareItem(
                name = name.take(160),
                price = obj.finiteNumber("price") ?: 0.0,
                quantity = obj.finiteNumber("quantity") ?: 1.0,
                unit = obj.optString("unit").trim().take(24),
                text = obj.optString("text").trim().takeIf { it.isNotEmpty() }?.take(2000),
                image = UiDirectory.validImage(obj.optString("image")),
            )
        }
    }
    val rows = remember(items) { UiCompare.rows(items) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableIntStateOf(-1) }

    if (rows.isEmpty()) {
        UiNote(l("此比较没有条目。", "This comparison has no items."))
        return
    }

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = sort == 0, onClick = { sort = 0 }, label = { Text(l("默认", "Default")) }, modifier = Modifier.testTag("ui-compare-sort-0"))
        FilterChip(selected = sort == 1, onClick = { sort = 1 }, label = { Text(l("单价从低到高", "Unit price low to high")) }, modifier = Modifier.testTag("ui-compare-sort-1"))
        FilterChip(selected = sort == 2, onClick = { sort = 2 }, label = { Text(l("总价从低到高", "Total low to high")) }, modifier = Modifier.testTag("ui-compare-sort-2"))
    }

    val ordered = when (sort) {
        1 -> UiCompare.rankByUnitPrice(rows)
        2 -> rows.sortedWith(compareBy<UiCompareRow, Double?>(nullsLast()) { it.total }.thenBy { it.index })
        else -> rows
    }
    ordered.forEach { row ->
        val isBest = row.unitPrice != null && UiCompare.isLowest(rows, row)
        OutlinedCard(
            onClick = { selected = if (selected == row.index) -1 else row.index },
            modifier = Modifier.fillMaxWidth().testTag("ui-compare-item-${row.index}"),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(row.name, row.image)
                    Spacer(Modifier.width(8.dp))
                    Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (isBest) Text(l("最低单价", "Lowest"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    l("单价 ", "Unit price ") + (row.unitPrice?.let(UiFormat::money) ?: "\u2014") +
                        " / " + row.unit.ifBlank { l("件", "unit") } +
                        "  ·  " + l("总价 ", "Total ") + (row.total?.let(UiFormat::money) ?: "\u2014"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                row.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        }
    }

    val chosen = rows.firstOrNull { it.index == selected }
    if (chosen != null) {
        OutlinedCard(
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().testTag("ui-compare-detail"),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(l("已选：", "Selected: ") + chosen.name, style = MaterialTheme.typography.titleSmall)
                Text(l("数量 ", "Quantity ") + UiFormat.number(chosen.quantity) + " " + chosen.unit.ifBlank { l("件", "unit") })
                Text(l("单价 ", "Unit price ") + (chosen.unitPrice?.let(UiFormat::money) ?: "\u2014") + "  ·  " + l("总价 ", "Total ") + (chosen.total?.let(UiFormat::money) ?: "\u2014"))
                if (chosen.unitPrice != null) {
                    val diff = UiCompare.differenceFromCheapest(rows, chosen)
                    if (diff != null) {
                        Text(
                            if (abs(diff) < 0.05) l("同单位中的最低单价。", "Lowest unit price for this unit.")
                            else l("比同单位最低单价高 " + UiFormat.percent(diff), "About " + UiFormat.percent(diff) + " above the cheapest for this unit."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    UiNote(l("价格或数量无效，无法计算单价。", "Price or quantity is invalid, so no unit price is shown."))
                }
                UiNote(l("比较仅使用本地示例数据，不同单位的单价不会直接混排。", "Comparison uses local example data; different units are not ranked against each other."))
            }
        }
    }
}

// ---------------------------------------------------------------- directory

@Composable
private fun UiDirectoryTool(n: JSONObject) {
    val items = remember(n.toString()) {
        val array = n.optJSONArray("items")
        if (array == null) emptyList() else (0 until minOf(array.length(), UiDirectory.MAX_ITEMS)).mapNotNull { i ->
            val obj = array.objectAt(i) ?: return@mapNotNull null
            val name = obj.optString("name").trim()
            if (name.isEmpty()) null else UiDirectoryItem(
                name = name.take(160),
                text = obj.optString("text").trim().takeIf { it.isNotEmpty() }?.take(4000),
                category = obj.optString("category").trim().takeIf { it.isNotEmpty() }?.take(80),
                price = obj.finiteNumber("price")?.takeIf { it.isFinite() && it >= 0 && it <= 1e12 },
                image = UiDirectory.validImage(obj.optString("image")),
            )
        }
    }
    val currency = UiFormat.currencySymbol(n.optString("currency").trim().take(12))
    val categories = remember(items) { UiDirectory.categories(items) }
    val sorts = remember {
        listOf(
            UiDirectorySort.DEFAULT to l("默认", "Default"),
            UiDirectorySort.NAME to l("名称", "Name"),
            UiDirectorySort.PRICE_ASC to l("价格 \u2191", "Price \u2191"),
            UiDirectorySort.PRICE_DESC to l("价格 \u2193", "Price \u2193"),
        )
    }

    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var sortIndex by rememberSaveable { mutableIntStateOf(0) }
    var expandedKey by rememberSaveable { mutableIntStateOf(-1) }

    OutlinedTextField(
        value = query,
        onValueChange = { if (it.length <= 120) query = it },
        label = { Text(l("搜索名称或说明", "Search name or description")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("ui-directory-search"),
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = category.isEmpty(),
            onClick = { category = ""; expandedKey = -1 },
            label = { Text(l("全部", "All")) },
            modifier = Modifier.testTag("ui-directory-cat-all"),
        )
        categories.forEachIndexed { index, name ->
            FilterChip(
                selected = category == name,
                onClick = { category = if (category == name) "" else name; expandedKey = -1 },
                label = { Text(name) },
                modifier = Modifier.testTag("ui-directory-cat-$index"),
            )
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sorts.forEachIndexed { index, (sort, label) ->
            FilterChip(
                selected = sortIndex == index,
                onClick = { sortIndex = index },
                label = { Text(label) },
                modifier = Modifier.testTag("ui-directory-sort-$index"),
            )
        }
    }

    val filtered = remember(items, query, category, sortIndex) {
        UiDirectory.filter(items, query, category.ifBlank { null }, sorts[sortIndex.coerceIn(0, sorts.lastIndex)].first)
    }
    Text(
        if (filtered.isEmpty()) l("没有匹配的条目", "No matching entries") else l("${filtered.size} 项", "${filtered.size} entries"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("ui-directory-count"),
    )

    filtered.forEach { item ->
        val key = items.indexOf(item)
        val expanded = key == expandedKey
        OutlinedCard(
            onClick = { expandedKey = if (expanded) -1 else key },
            modifier = Modifier.fillMaxWidth().testTag("ui-directory-item-$key"),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(item.name, item.image)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val facts = listOfNotNull(
                            item.category,
                            item.price?.let { currency + UiFormat.money(it) },
                        ).joinToString("  ·  ")
                        if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (expanded) "\u25B4" else "\u25BE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (expanded) {
                    HorizontalDivider()
                    Text(
                        item.text ?: l("此条目没有更多说明。", "This entry has no further description."),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("ui-directory-detail-$key"),
                    )
                    Text(
                        listOfNotNull(
                            item.category?.let { l("类别：", "Category: ") + it },
                            item.price?.let { l("价格：", "Price: ") + currency + UiFormat.money(it) },
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    UiNote(l("本地示例数据；未打开外部页面，也未执行任何操作。", "Local example data; no external page was opened and no action was performed."))
                }
            }
        }
    }
}

@Composable
private fun Thumb(label: String, image: String?) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.trim().take(1).ifEmpty { "?" }.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

// ---------------------------------------------------------------- quiz

@Composable
private fun UiQuizTool(n: JSONObject) {
    val questions = remember(n.toString()) {
        runCatching {
            val array = n.optJSONArray("questions") ?: throw IllegalArgumentException("no questions")
            val parsed = (0 until minOf(array.length(), UiQuiz.MAX_QUESTIONS)).map { i ->
                val q = array.getJSONObject(i)
                UiQuizQuestion(
                    question = q.optString("question"),
                    options = q.optJSONArray("options")?.let { options ->
                        (0 until options.length()).map { options.optString(it) }
                    } ?: emptyList(),
                    answer = q.optInt("answer", UiQuiz.UNANSWERED),
                    explanation = q.optString("explanation").takeIf { it.isNotBlank() },
                )
            }
            UiQuiz.validate(parsed)
        }.getOrNull()
    }
    if (questions == null) {
        UiToolError(l("练习数据无效：每题需要 2-8 个不重复选项和有效答案。", "Invalid practice data: each question needs 2-8 unique options and an in-range answer."))
        return
    }

    var answers by rememberSaveable { mutableStateOf(ArrayList(List(questions.size) { UiQuiz.UNANSWERED.toString() })) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val choices = answers.map { it.toIntOrNull() ?: UiQuiz.UNANSWERED }
    val result = remember(questions, answers) { UiQuiz.grade(questions, choices) }

    Text(
        l("本地练习 · 不记录为考试或答卷，也不上传答案", "Local practice · not an exam record or answer sheet; nothing is uploaded"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    questions.forEachIndexed { qi, question ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${qi + 1}. ${question.question}", style = MaterialTheme.typography.titleSmall)
            question.options.forEachIndexed { oi, option ->
                val selected = choices[qi] == oi
                FilterChip(
                    selected = selected,
                    enabled = !submitted,
                    onClick = {
                        if (!submitted) {
                            answers = ArrayList(answers).apply { set(qi, oi.toString()) }
                        }
                    },
                    label = { Text(option, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth().testTag("ui-quiz-$qi-option-$oi"),
                )
            }
            if (submitted) {
                val chosen = choices[qi]
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            chosen == UiQuiz.UNANSWERED -> "\u25CB " + l("未作答", "Unanswered")
                            chosen == question.answer -> "\u2713 " + l("回答正确", "Correct")
                            else -> "\u2717 " + l("回答错误", "Incorrect")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (chosen == question.answer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("ui-quiz-feedback-$qi"),
                    )
                }
                Text(
                    l("正确答案：", "Correct answer: ") + question.options[question.answer],
                    style = MaterialTheme.typography.bodySmall,
                )
                if (chosen in question.options.indices && chosen != question.answer) {
                    Text(l("你的选择：", "Your choice: ") + question.options[chosen], style = MaterialTheme.typography.bodySmall)
                }
                question.explanation?.let { Text(l("解析：", "Explanation: ") + it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }

    if (submitted) {
        Text(
            l("得分 ", "Score ") + "${result.correct} / ${result.total} · ${result.percent}%",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("ui-quiz-score"),
        )
        if (!result.complete) {
            UiNote(l("有 ${result.total - result.answered} 题未作答，按未得分计算。", "${result.total - result.answered} unanswered question(s) count as incorrect."))
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { submitted = true }, modifier = Modifier.testTag("ui-quiz-submit")) {
            Text(l("检查答案", "Check answers"))
        }
        OutlinedButton(
            onClick = {
                answers = ArrayList(List(questions.size) { UiQuiz.UNANSWERED.toString() })
                submitted = false
            },
            modifier = Modifier.testTag("ui-quiz-reset"),
        ) { Text(l("重置", "Reset")) }
    }
}

// ---------------------------------------------------------------- decision

@Composable
private fun UiDecisionTool(n: JSONObject) {
    val criteria: List<UiCriterion> = remember(n.toString()) {
        val array = n.optJSONArray("criteria") ?: return@remember emptyList()
        (0 until minOf(array.length(), UiDecision.MAX_CRITERIA)).mapNotNull { i ->
            val obj = array.objectAt(i) ?: return@mapNotNull null
            val label = obj.optString("label").trim()
            if (label.isEmpty()) null else UiCriterion(label.take(120), UiDecision.sanitizeWeight(obj.finiteNumber("weight") ?: 1.0))
        }
    }
    val items: List<UiDecisionItem> = remember(n.toString()) {
        val array = n.optJSONArray("items") ?: return@remember emptyList()
        (0 until minOf(array.length(), UiDecision.MAX_ITEMS)).mapNotNull { i ->
            val obj = array.objectAt(i) ?: return@mapNotNull null
            val name = obj.optString("name").trim()
            if (name.isEmpty()) null else UiDecisionItem(
                name = name.take(160),
                scores = obj.optJSONArray("scores")?.let { scores ->
                    (0 until minOf(scores.length(), UiDecision.MAX_CRITERIA)).map { scores.optDouble(it, 0.0) }
                } ?: emptyList(),
            )
        }
    }
    if (criteria.isEmpty() || items.isEmpty()) {
        UiToolError(l("决策数据无效：需要至少一个标准和一候选项。", "Invalid decision data: at least one criterion and one option are required."))
        return
    }

    var weightsText by rememberSaveable { mutableStateOf(criteria.joinToString(",") { UiFormat.number(UiDecision.sanitizeWeight(it.weight)) }) }
    val weights = criteria.indices.map { i ->
        weightsText.split(",").getOrNull(i)?.toDoubleOrNull()?.let(UiDecision::sanitizeWeight) ?: criteria[i].weight
    }
    val ranking = remember(criteria, items, weightsText) { UiDecision.rank(criteria, items, weights) }

    Text(l("权重（0-10）：拖动后立即重排", "Weights (0-10): rankings update immediately"), style = MaterialTheme.typography.labelMedium)
    criteria.forEachIndexed { i, criterion ->
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(criterion.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(UiFormat.number(weights[i], 1), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = weights[i].toFloat().coerceIn(0f, UiDecision.MAX_WEIGHT.toFloat()),
                onValueChange = { raw ->
                    val rounded = (round(raw.toDouble() * 10.0) / 10.0).coerceIn(0.0, UiDecision.MAX_WEIGHT)
                    weightsText = criteria.indices.joinToString(",") { j ->
                        UiFormat.number(if (j == i) rounded else weights[j], 1)
                    }
                },
                valueRange = 0f..UiDecision.MAX_WEIGHT.toFloat(),
                modifier = Modifier.fillMaxWidth().testTag("ui-decision-weight-$i"),
            )
        }
    }

    HorizontalDivider()
    Text(l("实时排名", "Live ranking"), style = MaterialTheme.typography.titleSmall)
    ranking.forEachIndexed { position, rank ->
        Column(Modifier.fillMaxWidth().testTag("ui-decision-rank-$position"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${position + 1}. ${rank.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    UiFormat.number(rank.score, 2) + " / " + UiFormat.number(UiDecision.MAX_SCORE, 0),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Box(
                Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier.fillMaxWidth((rank.score / UiDecision.MAX_SCORE).toFloat().coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
    UiNote(l("加权平均为本地计算；示例评分为工具传入数据，不代表真实评价。", "Weighted averages are computed locally from tool-supplied example scores, not real ratings."))
}

// ---------------------------------------------------------------- wizard

@Composable
private fun UiWizardTool(n: JSONObject) {
    val pages = remember(n.toString()) {
        val array = n.optJSONArray("pages")
        if (array == null) emptyList() else (0 until minOf(array.length(), UiWizard.MAX_PAGES)).mapNotNull { i ->
            val page = array.objectAt(i) ?: return@mapNotNull null
            val title = page.optString("title").trim()
            val text = page.optString("text").trim()
            if (title.isEmpty() && text.isEmpty()) null else title to text
        }
    }
    if (pages.isEmpty()) {
        UiToolError(l("向导没有可显示的页面。", "This wizard has no pages to show."))
        return
    }
    val finishLabel = n.optString("finishLabel").trim().takeIf { it.isNotEmpty() } ?: l("完成", "Finish")

    var page by rememberSaveable { mutableIntStateOf(0) }
    var finished by rememberSaveable { mutableStateOf(false) }
    val index = UiWizard.clamp(page, pages.size)

    if (!finished) {
        Text(
            l("第 ", "Step ") + UiWizard.stepLabel(index, pages.size) + l(" 页", ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("ui-wizard-step"),
        )
        pages[index].first.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        pages[index].second.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = index > 0,
                onClick = { page = UiWizard.back(index, pages.size) },
                modifier = Modifier.testTag("ui-wizard-back"),
            ) { Text(l("上一步", "Back")) }
            Button(
                onClick = {
                    if (UiWizard.isLast(index, pages.size)) finished = true
                    else page = UiWizard.next(index, pages.size)
                },
                modifier = Modifier.testTag(if (UiWizard.isLast(index, pages.size)) "ui-wizard-finish" else "ui-wizard-next"),
            ) {
                Text(if (UiWizard.isLast(index, pages.size)) finishLabel else l("下一步", "Next"))
            }
        }
    } else {
        Text(
            l("已完成本地浏览", "Finished locally"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("ui-wizard-done"),
        )
        Text(
            l("已读完 ${pages.size} / ${pages.size} 页。未提交、未保存记录，也未执行任何外部操作。", "Read all ${pages.size} of ${pages.size} pages. Nothing was submitted, saved, or performed externally."),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { page = 0; finished = false },
            modifier = Modifier.testTag("ui-wizard-restart"),
        ) { Text(l("重新开始", "Start over")) }
    }
}
