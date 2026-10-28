package com.openminis.app.intelligentui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure, deterministic logic for the interactive tool components
 * (converter / budget / compare / directory / quiz / decision / wizard).
 *
 * No Android, Compose, org.json or network types appear here, so every rule can be
 * unit-tested on a plain JVM. UI code is only responsible for parsing JSON and
 * rendering these values. All numbers are bounded: NaN/Infinity never escape.
 */

/** Dimensions accepted by the converter protocol. */
enum class UiDimension(val wire: String) {
    LENGTH("length"),
    MASS("mass"),
    TEMPERATURE("temperature"),
    STORAGE("storage");

    companion object {
        fun fromWire(raw: String?): UiDimension? =
            raw?.trim()?.lowercase(Locale.ROOT)?.let { w -> entries.firstOrNull { it.wire == w } }
    }
}

/** A unit definition; [factor] is the multiplier to the dimension's base unit. */
data class UiUnit(val symbol: String, val dimension: UiDimension, val factor: Double)

object UiUnits {
    const val MAX_INPUT = 1e12

    private val length = listOf(
        UiUnit("mm", UiDimension.LENGTH, 0.001),
        UiUnit("cm", UiDimension.LENGTH, 0.01),
        UiUnit("m", UiDimension.LENGTH, 1.0),
        UiUnit("km", UiDimension.LENGTH, 1000.0),
        UiUnit("in", UiDimension.LENGTH, 0.0254),
        UiUnit("ft", UiDimension.LENGTH, 0.3048),
        UiUnit("yd", UiDimension.LENGTH, 0.9144),
        UiUnit("mi", UiDimension.LENGTH, 1609.344),
    )
    private val mass = listOf(
        UiUnit("mg", UiDimension.MASS, 1e-6),
        UiUnit("g", UiDimension.MASS, 1e-3),
        UiUnit("kg", UiDimension.MASS, 1.0),
        UiUnit("oz", UiDimension.MASS, 0.028349523125),
        UiUnit("lb", UiDimension.MASS, 0.45359237),
        UiUnit("t", UiDimension.MASS, 1000.0),
    )
    private val temperature = listOf(
        UiUnit("C", UiDimension.TEMPERATURE, 1.0),
        UiUnit("F", UiDimension.TEMPERATURE, 1.0),
        UiUnit("K", UiDimension.TEMPERATURE, 1.0),
    )
    private val storage = listOf(
        UiUnit("B", UiDimension.STORAGE, 1.0),
        UiUnit("KB", UiDimension.STORAGE, 1e3),
        UiUnit("MB", UiDimension.STORAGE, 1e6),
        UiUnit("GB", UiDimension.STORAGE, 1e9),
        UiUnit("TB", UiDimension.STORAGE, 1e12),
        UiUnit("KiB", UiDimension.STORAGE, 1024.0),
        UiUnit("MiB", UiDimension.STORAGE, 1048576.0),
        UiUnit("GiB", UiDimension.STORAGE, 1073741824.0),
        UiUnit("TiB", UiDimension.STORAGE, 1099511627776.0),
    )

    private val byDimension: Map<UiDimension, List<UiUnit>> = mapOf(
        UiDimension.LENGTH to length,
        UiDimension.MASS to mass,
        UiDimension.TEMPERATURE to temperature,
        UiDimension.STORAGE to storage,
    )

    /** Spoken and abbreviated aliases accepted for each canonical symbol. */
    private val aliases: Map<UiDimension, Map<String, String>> = mapOf(
        UiDimension.LENGTH to mapOf(
            "mm" to "mm", "millimeter" to "mm", "millimetre" to "mm", "millimeters" to "mm",
            "cm" to "cm", "centimeter" to "cm", "centimetre" to "cm", "centimeters" to "cm",
            "m" to "m", "meter" to "m", "metre" to "m", "meters" to "m", "metres" to "m",
            "km" to "km", "kilometer" to "km", "kilometre" to "km", "kilometers" to "km",
            "in" to "in", "inch" to "in", "inches" to "in",
            "ft" to "ft", "foot" to "ft", "feet" to "ft",
            "yd" to "yd", "yard" to "yd", "yards" to "yd",
            "mi" to "mi", "mile" to "mi", "miles" to "mi",
        ),
        UiDimension.MASS to mapOf(
            "mg" to "mg", "milligram" to "mg", "milligrams" to "mg",
            "g" to "g", "gram" to "g", "grams" to "g",
            "kg" to "kg", "kilogram" to "kg", "kilograms" to "kg", "kilo" to "kg",
            "t" to "t", "ton" to "t", "tons" to "t", "tonne" to "t", "tonnes" to "t",
            "oz" to "oz", "ounce" to "oz", "ounces" to "oz",
            "lb" to "lb", "lbs" to "lb", "pound" to "lb", "pounds" to "lb",
        ),
        UiDimension.TEMPERATURE to mapOf(
            "c" to "C", "celsius" to "C", "centigrade" to "C", "度c" to "C",
            "f" to "F", "fahrenheit" to "F", "度f" to "F",
            "k" to "K", "kelvin" to "K",
        ),
        UiDimension.STORAGE to mapOf(
            "b" to "B", "byte" to "B", "bytes" to "B",
            "kb" to "KB", "kilobyte" to "KB", "kilobytes" to "KB",
            "mb" to "MB", "megabyte" to "MB", "megabytes" to "MB",
            "gb" to "GB", "gigabyte" to "GB", "gigabytes" to "GB",
            "tb" to "TB", "terabyte" to "TB", "terabytes" to "TB",
            "kib" to "KiB", "mib" to "MiB", "gib" to "GiB", "tib" to "TiB",
        ),
    )

    fun units(dimension: UiDimension): List<UiUnit> = byDimension.getValue(dimension)

    fun symbols(dimension: UiDimension): List<String> = units(dimension).map { it.symbol }

    /** Resolves an alias to the canonical unit of [dimension], or returns null. */
    fun resolve(symbol: String?, dimension: UiDimension): UiUnit? {
        val raw = symbol?.trim() ?: return null
        if (raw.isEmpty() || raw.length > 24) return null
        val key = normalize(raw)
        val canonical = aliases.getValue(dimension)[key] ?: units(dimension).firstOrNull { normalize(it.symbol) == key }?.symbol
            ?: return null
        return units(dimension).firstOrNull { it.symbol == canonical }
    }

    /** Resolves a unit without knowing the dimension first (used when protocol dimension is absent). */
    fun detect(symbol: String?): UiUnit? =
        UiDimension.entries.firstNotNullOfOrNull { resolve(symbol, it) }

    fun defaultFrom(dimension: UiDimension): String = when (dimension) {
        UiDimension.LENGTH -> "m"
        UiDimension.MASS -> "kg"
        UiDimension.TEMPERATURE -> "C"
        UiDimension.STORAGE -> "MB"
    }

    fun defaultTo(dimension: UiDimension): String = when (dimension) {
        UiDimension.LENGTH -> "cm"
        UiDimension.MASS -> "lb"
        UiDimension.TEMPERATURE -> "F"
        UiDimension.STORAGE -> "GB"
    }

    /**
     * Converts [value] between two units. Throws IllegalArgumentException with a
     * machine-readable prefix ("unknown unit", "dimension mismatch", "invalid value")
     * instead of ever returning NaN or Infinity.
     */
    fun convert(value: Double, from: String, to: String, dimension: UiDimension? = null): Double {
        require(value.isFinite()) { "invalid value: not finite" }
        require(abs(value) <= MAX_INPUT) { "invalid value: outside supported range" }
        val source = (if (dimension != null) resolve(from, dimension) else detect(from))
            ?: throw IllegalArgumentException("unknown unit: $from")
        val target = (if (dimension != null) resolve(to, dimension) else detect(to))
            ?: throw IllegalArgumentException("unknown unit: $to")
        require(source.dimension == target.dimension) {
            "dimension mismatch: ${source.symbol} (${source.dimension.wire}) to ${target.symbol} (${target.dimension.wire})"
        }
        if (dimension != null) require(source.dimension == dimension && target.dimension == dimension) {
            "dimension mismatch: requested ${dimension.wire}"
        }
        val result = when (source.dimension) {
            UiDimension.TEMPERATURE -> fromKelvin(toKelvin(value, source.symbol), target.symbol)
            else -> value * source.factor / target.factor
        }
        require(result.isFinite()) { "invalid value: result is not finite" }
        return result
    }

    fun convertOrNull(value: Double, from: String, to: String, dimension: UiDimension? = null): Double? =
        runCatching { convert(value, from, to, dimension) }.getOrNull()

    private fun toKelvin(value: Double, symbol: String): Double = when (symbol) {
        "C" -> value + 273.15
        "F" -> (value - 32.0) * 5.0 / 9.0 + 273.15
        else -> value
    }

    private fun fromKelvin(kelvin: Double, symbol: String): Double = when (symbol) {
        "C" -> kelvin - 273.15
        "F" -> (kelvin - 273.15) * 9.0 / 5.0 + 32.0
        else -> kelvin
    }

    private fun normalize(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace("\u00B0", "").replace(" ", "").replace("\u00A0", "")
}

/** Locale-independent number formatting and bounded parsing shared by all tools. */
object UiFormat {
    // Ordered longest-suffix first so "百万" wins over "万".
    private val multipliers = linkedMapOf(
        "百万" to 1e6, "千" to 1e3, "万" to 1e4, "亿" to 1e8,
        "k" to 1e3, "m" to 1e6, "b" to 1e9, "t" to 1e12,
    )

    fun finiteOrZero(value: Double, bound: Double = 1e12): Double =
        if (value.isFinite()) value.coerceIn(-bound, bound) else 0.0

    /** Accepts "1,200", "¥ 12.5", "1.2k", "3万"; rejects blanks, NaN, Infinity and values over 1e12. */
    fun parse(text: String): Double? {
        var s = text.trim().replace(",", "").replace("_", "").replace(" ", "").replace("\u00A0", "")
        if (s.isEmpty() || s.length > 40) return null
        s = s.removePrefix("+")
        s = s.trimStart('\u2212', '\u00A5', '$', '\u20AC', '\u00A3', '\uFFE5')
        if (s.isEmpty()) return null
        s = s.removeSuffix("%")
        val suffix = multipliers.keys.firstOrNull { s.endsWith(it, ignoreCase = true) }
        if (suffix != null) {
            val multiplier = multipliers.getValue(suffix)
            val base = s.dropLast(suffix.length).toDoubleOrNull() ?: return null
            if (!base.isFinite() || abs(base * multiplier) > 1e12) return null
            return base * multiplier
        }
        val value = s.toDoubleOrNull() ?: return null
        if (!value.isFinite() || abs(value) > 1e12) return null
        return value
    }

    /** Trims trailing zeros; large and tiny magnitudes fall back to scientific notation. */
    fun number(value: Double, decimals: Int = 4): String {
        if (!value.isFinite()) return "\u2014"
        val d = decimals.coerceIn(0, 6)
        if (abs(value) >= 1e12 || (value != 0.0 && abs(value) < 1e-4)) {
            return String.format(Locale.ROOT, "%.4e", value)
        }
        var s = String.format(Locale.ROOT, "%.${d}f", value)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    fun money(value: Double): String =
        if (!value.isFinite()) "\u2014" else String.format(Locale.ROOT, "%.2f", finiteOrZero(value))

    fun percent(value: Double, decimals: Int = 1): String {
        if (!value.isFinite()) return "\u2014"
        val d = decimals.coerceIn(0, 2)
        var s = String.format(Locale.ROOT, "%.${d}f", value)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return "$s%"
    }

    fun currencySymbol(code: String?): String = when (code?.trim()?.uppercase(Locale.ROOT)) {
        "CNY", "RMB" -> "\u00A5"
        "USD" -> "$"
        "EUR" -> "\u20AC"
        "GBP" -> "\u00A3"
        "JPY" -> "\u00A5"
        null, "" -> ""
        else -> code.trim().uppercase(Locale.ROOT) + " "
    }
}

// ---------------------------------------------------------------- budget

data class UiBudgetItem(val label: String, val amount: Double)

data class UiBudgetSummary(
    val income: Double,
    val totalExpense: Double,
    val balance: Double,
    /** balance/income as a percentage; 0 when income is not positive. */
    val balancePercent: Double,
    /** Each item's share of total expenses (percent), parallel to the items. */
    val itemPercents: List<Double>,
    val overBudget: Boolean,
    val count: Int,
)

object UiBudget {
    const val MAX_ITEMS = 50

    fun sanitizeAmount(value: Double): Double =
        if (!value.isFinite()) 0.0 else value.coerceIn(0.0, 1e12)

    fun parseAmount(text: String): Double? = UiFormat.parse(text)?.let { if (it < 0 || it > 1e12) null else it }

    fun summarize(income: Double, items: List<UiBudgetItem>): UiBudgetSummary {
        val safeIncome = sanitizeAmount(income)
        val safeItems = items.take(MAX_ITEMS).map { UiBudgetItem(it.label.take(120), sanitizeAmount(it.amount)) }
        val total = safeItems.fold(0.0) { acc, item ->
            (acc + item.amount).coerceAtMost(1e15)
        }
        val balance = (safeIncome - total).coerceIn(-1e15, 1e15)
        val percents = safeItems.map { if (total > 0) it.amount / total * 100.0 else 0.0 }
        return UiBudgetSummary(
            income = safeIncome,
            totalExpense = total,
            balance = balance,
            balancePercent = if (safeIncome > 0) balance / safeIncome * 100.0 else 0.0,
            itemPercents = percents,
            overBudget = total > safeIncome,
            count = safeItems.size,
        )
    }
}

// ---------------------------------------------------------------- compare

data class UiCompareItem(
    val name: String,
    val price: Double,
    val quantity: Double,
    val unit: String,
    val text: String? = null,
    val image: String? = null,
)

data class UiCompareRow(
    val name: String,
    val price: Double,
    val quantity: Double,
    val unit: String,
    val unitPrice: Double?,
    val total: Double?,
    val text: String?,
    val image: String?,
    val index: Int,
)

object UiCompare {
    const val MAX_ITEMS = 40
    const val MAX_AMOUNT = 1e12

    fun validPrice(value: Double): Double? = if (value.isFinite() && value >= 0 && value <= MAX_AMOUNT) value else null

    fun validQuantity(value: Double): Double? = if (value.isFinite() && value > 0 && value <= MAX_AMOUNT) value else null

    fun unitPrice(price: Double, quantity: Double): Double? {
        val p = validPrice(price) ?: return null
        val q = validQuantity(quantity) ?: return null
        val result = p / q
        return if (result.isFinite()) result else null
    }

    fun total(price: Double, quantity: Double): Double? {
        val p = validPrice(price) ?: return null
        val q = validQuantity(quantity) ?: return null
        // price is the whole pack/plan price; quantity is its contained units.
        return p
    }

    fun rows(items: List<UiCompareItem>): List<UiCompareRow> =
        items.take(MAX_ITEMS).mapIndexed { index, item ->
            UiCompareRow(
                name = item.name.take(160),
                price = item.price,
                quantity = item.quantity,
                unit = item.unit.take(24),
                unitPrice = unitPrice(item.price, item.quantity),
                total = total(item.price, item.quantity),
                text = item.text,
                image = item.image,
                index = index,
            )
        }

    /** Unit-price ranking; rows without a valid unit price stay last, ties keep input order. */
    fun rankByUnitPrice(rows: List<UiCompareRow>, descending: Boolean = false): List<UiCompareRow> {
        val comparator = if (descending) {
            compareBy<UiCompareRow> { it.unitPrice == null }
                .thenByDescending { it.unitPrice ?: -1.0 }
                .thenBy { it.index }
        } else {
            compareBy<UiCompareRow> { it.unitPrice == null }
                .thenBy { it.unitPrice ?: Double.MAX_VALUE }
                .thenBy { it.index }
        }
        return rows.groupBy { it.unit }.values.flatMap { it.sortedWith(comparator) }
    }

    /** Cheapest item per unit label, so different units are never compared as if equal. */
    fun cheapestByUnit(rows: List<UiCompareRow>): Map<String, UiCompareRow> =
        rows.filter { it.unitPrice != null }
            .groupBy { it.unit }
            .mapValues { (_, group) -> group.minWith(compareBy({ it.unitPrice }, { it.index })) }

    fun isLowest(rows: List<UiCompareRow>, row: UiCompareRow): Boolean =
        cheapestByUnit(rows)[row.unit]?.index == row.index && row.unitPrice != null

    /** Percent by which [row] is above the cheapest comparable row; negative means cheaper. */
    fun differenceFromCheapest(rows: List<UiCompareRow>, row: UiCompareRow): Double? {
        val cheapest = cheapestByUnit(rows)[row.unit] ?: return null
        val best = cheapest.unitPrice ?: return null
        val own = row.unitPrice ?: return null
        if (best <= 0) return null
        return (own - best) / best * 100.0
    }
}

// ---------------------------------------------------------------- decision

data class UiCriterion(val label: String, val weight: Double)

data class UiDecisionItem(val name: String, val scores: List<Double>)

data class UiDecisionRank(val name: String, val score: Double, val index: Int)

object UiDecision {
    const val MAX_CRITERIA = 8
    const val MAX_ITEMS = 20
    const val MAX_WEIGHT = 100.0
    const val MAX_SCORE = 100.0

    fun sanitizeWeight(value: Double): Double =
        if (value.isFinite()) value.coerceIn(0.0, MAX_WEIGHT) else 0.0

    fun sanitizeScore(value: Double): Double =
        if (value.isFinite()) value.coerceIn(0.0, MAX_SCORE) else 0.0

    fun weights(criteria: List<UiCriterion>): List<Double> = criteria.take(MAX_CRITERIA).map { sanitizeWeight(it.weight) }

    /**
     * Weighted mean of scores. If every weight is zero, equal weights are used so the
     * ranking still reflects the scores instead of collapsing to an all-zero tie.
     */
    fun score(scores: List<Double>, weights: List<Double>): Double {
        val count = minOf(scores.size, weights.size)
        if (count == 0) return 0.0
        var weighted = 0.0
        var totalWeight = 0.0
        for (i in 0 until count) {
            val w = sanitizeWeight(weights[i])
            val s = sanitizeScore(scores[i])
            weighted += w * s
            totalWeight += w
        }
        val result = if (totalWeight > 0) weighted / totalWeight else {
            (0 until count).sumOf { sanitizeScore(scores[it]) } / count
        }
        return if (result.isFinite()) result.coerceIn(0.0, MAX_SCORE) else 0.0
    }

    fun rank(criteria: List<UiCriterion>, items: List<UiDecisionItem>, weights: List<Double> = weights(criteria)): List<UiDecisionRank> {
        val effective = criteria.take(MAX_CRITERIA).mapIndexed { i, c ->
            sanitizeWeight(weights.getOrElse(i) { c.weight })
        }
        return items.take(MAX_ITEMS).mapIndexed { index, item ->
            UiDecisionRank(item.name.take(160), score(item.scores, effective), index)
        }.sortedWith(compareByDescending<UiDecisionRank> { it.score }.thenBy { it.index })
    }
}

// ---------------------------------------------------------------- quiz

data class UiQuizQuestion(
    val question: String,
    val options: List<String>,
    val answer: Int,
    val explanation: String? = null,
)

data class UiQuizResult(
    val correct: Int,
    val answered: Int,
    val total: Int,
    val percent: Int,
    /** true = correct, false = wrong, null = unanswered (after submit). */
    val verdicts: List<Boolean?>,
    val complete: Boolean,
)

object UiQuiz {
    const val MAX_QUESTIONS = 20
    const val MAX_OPTIONS = 12
    const val UNANSWERED = -1

    /** Validates and trims questions; throws IllegalArgumentException for unusable data. */
    fun validate(questions: List<UiQuizQuestion>): List<UiQuizQuestion> {
        require(questions.isNotEmpty()) { "quiz needs at least one question" }
        require(questions.size <= MAX_QUESTIONS) { "too many quiz questions" }
        return questions.mapIndexed { index, q ->
            val prompt = q.question.trim()
            require(prompt.isNotEmpty()) { "question ${index + 1} is blank" }
            require(prompt.length <= 1000) { "question ${index + 1} is too long" }
            require(q.options.size in 2..MAX_OPTIONS) { "question ${index + 1} needs 2..$MAX_OPTIONS options" }
            val options = q.options.map { it.trim() }
            require(options.all { it.isNotEmpty() && it.length <= 400 }) { "question ${index + 1} has invalid options" }
            require(options.distinct().size == options.size) { "question ${index + 1} repeats an option" }
            require(q.answer in options.indices) { "question ${index + 1} answer is out of range" }
            require((q.explanation?.length ?: 0) <= 2000) { "question ${index + 1} explanation is too long" }
            UiQuizQuestion(prompt, options, q.answer, q.explanation?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    fun isCorrect(q: UiQuizQuestion, choice: Int): Boolean = choice == q.answer

    fun grade(questions: List<UiQuizQuestion>, answers: List<Int>): UiQuizResult {
        val verdicts = questions.mapIndexed { i, q ->
            val choice = answers.getOrElse(i) { UNANSWERED }
            when {
                choice !in q.options.indices -> null
                else -> choice == q.answer
            }
        }
        val correct = verdicts.count { it == true }
        val answered = verdicts.count { it != null }
        val total = questions.size
        return UiQuizResult(
            correct = correct,
            answered = answered,
            total = total,
            percent = percent(correct, total),
            verdicts = verdicts,
            complete = answered == total && total > 0,
        )
    }

    fun percent(correct: Int, total: Int): Int =
        if (total <= 0) 0 else ((correct.toDouble() / total) * 100.0).roundToInt().coerceIn(0, 100)

    fun next(current: Int, total: Int): Int = (current + 1).coerceAtMost((total - 1).coerceAtLeast(0))
}

// ---------------------------------------------------------------- directory

data class UiDirectoryItem(
    val name: String,
    val text: String? = null,
    val category: String? = null,
    val price: Double? = null,
    val image: String? = null,
)

enum class UiDirectorySort(val wire: String) {
    DEFAULT("default"),
    NAME("name"),
    PRICE_ASC("price_asc"),
    PRICE_DESC("price_desc");

    companion object {
        fun fromWire(raw: String?): UiDirectorySort =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase(Locale.ROOT) } ?: DEFAULT
    }
}

object UiDirectory {
    const val MAX_ITEMS = 50

    fun validImage(url: String?): String? =
        url?.trim()?.takeIf { it.length <= 2000 && (it.startsWith("https://") || it.startsWith("http://")) }

    /** Distinct, non-blank categories in first-seen order (local data only). */
    fun categories(items: List<UiDirectoryItem>): List<String> =
        items.mapNotNull { it.category?.trim()?.takeIf(String::isNotEmpty) }.distinct()

    fun filter(
        items: List<UiDirectoryItem>,
        query: String = "",
        category: String? = null,
        sort: UiDirectorySort = UiDirectorySort.DEFAULT,
    ): List<UiDirectoryItem> {
        val needle = query.trim().lowercase(Locale.ROOT)
        val filtered = items.take(MAX_ITEMS).filter { item ->
            val categoryOk = category.isNullOrBlank() || item.category?.trim() == category
            val textOk = needle.isEmpty() || listOfNotNull(item.name, item.text, item.category)
                .any { it.lowercase(Locale.ROOT).contains(needle) }
            categoryOk && textOk
        }
        return when (sort) {
            UiDirectorySort.DEFAULT -> filtered
            UiDirectorySort.NAME -> filtered.sortedWith(compareBy { it.name.lowercase(Locale.ROOT) })
            UiDirectorySort.PRICE_ASC -> filtered.sortedWith(compareBy(nullsLast()) { it.price })
            UiDirectorySort.PRICE_DESC -> filtered.sortedWith(compareBy<UiDirectoryItem> { it.price == null }.thenByDescending { it.price })
        }
    }

    fun matches(item: UiDirectoryItem, query: String, category: String? = null): Boolean =
        filter(listOf(item), query, category).size == 1
}

// ---------------------------------------------------------------- wizard

object UiWizard {
    const val MAX_PAGES = 16

    fun clamp(index: Int, count: Int): Int {
        if (count <= 0) return 0
        return index.coerceIn(0, count - 1)
    }

    fun next(index: Int, count: Int): Int = clamp(index + 1, count)

    fun back(index: Int, count: Int): Int = clamp(index - 1, count)

    fun isFirst(index: Int, count: Int): Boolean = clamp(index, count) == 0

    fun isLast(index: Int, count: Int): Boolean = count <= 0 || clamp(index, count) == count - 1

    fun stepLabel(index: Int, count: Int): String = "${clamp(index, count) + 1} / ${count.coerceAtLeast(1)}"
}
