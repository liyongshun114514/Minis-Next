package com.openminis.app.intelligentui

import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict, bounded schema for the native `math` component.
 *
 * The renderer is a local calculator, not a CAS and not an HTML/JS surface.
 * Validation rejects unknown keys, malformed numbers, out-of-range domains and
 * over-budget arrays before anything is drawn. Domain errors that depend on the
 * chosen point (for example division by zero) are reported by the renderer.
 */
object UiMathSchema {
    val modes: Set<String> = linkedSetOf(
        "formula", "function_plot", "parametric", "polar", "implicit", "surface3d",
        "calculus", "equation", "matrix", "statistics", "probability", "geometry", "sequence",
    )

    /** Hard caps shared with the renderer. Kept public so tests can assert the budget. */
    const val MAX_EXPRESSION = 512
    const val MAX_SLIDERS = 6
    const val MAX_FORMULA_STEPS = 12
    const val MAX_MATRIX = 6
    const val MAX_STAT_VALUES = 64
    const val MAX_SEQUENCE_TERMS = 40
    const val MAX_PROBABILITY_N = 1000

    private val common = setOf("id", "type", "title", "text", "mode", "sliders")
    private val reservedVariables = setOf("x", "y", "z", "pi", "e", "tau")

    private val fields: Map<String, Set<String>> = mapOf(
        "formula" to setOf("expression", "steps", "result"),
        "function_plot" to setOf("expression", "xMin", "xMax", "yMin", "yMax"),
        "parametric" to setOf("expression", "expressionY", "tMin", "tMax", "xMin", "xMax", "yMin", "yMax"),
        "polar" to setOf("expression", "tMin", "tMax", "xMin", "xMax", "yMin", "yMax"),
        "implicit" to setOf("expression", "comparator", "xMin", "xMax", "yMin", "yMax"),
        "surface3d" to setOf("expression", "xMin", "xMax", "yMin", "yMax", "zMin", "zMax", "azimuth", "elevation"),
        "calculus" to setOf("expression", "a", "b", "x0"),
        "equation" to setOf("expression", "a", "b"),
        "matrix" to setOf("matrix", "rhs"),
        "statistics" to setOf("values"),
        "probability" to setOf("n", "k", "p"),
        "geometry" to setOf("shape", "a", "b", "c", "r"),
        "sequence" to setOf("kind", "first", "second", "difference", "ratio", "expression", "terms"),
    )

    fun validate(n: JSONObject) {
        val type = n.opt("type")
        require(type is String && type == "math") { "type must be math" }
        val mode = n.opt("mode")
        require(mode is String && mode in modes) { "Unsupported math mode: $mode" }
        val allowed = common + fields.getValue(mode)
        keys(n, allowed)
        text(n, "id", 1, 48, required = true)
        text(n, "title", 1, 200, required = false)
        text(n, "text", 1, 4000, required = false)
        sliders(n)
        when (mode) {
            "formula" -> {
                formula(n, "expression", required = true)
                formula(n, "result", required = false)
                if (n.has("steps")) {
                    val steps = array(n, "steps", 1, MAX_FORMULA_STEPS)
                    repeat(steps.length()) { i ->
                        val step = steps.get(i)
                        require(step is JSONObject) { "steps[$i] must be an object" }
                        keys(step, setOf("formula", "note"))
                        formula(step, "formula", required = true)
                        text(step, "note", 1, 200, required = false)
                    }
                }
            }
            "function_plot" -> {
                expression(n, "expression", required = true)
                domain(n, "x")
                domain(n, "y")
            }
            "parametric" -> {
                expression(n, "expression", required = true)
                expression(n, "expressionY", required = true)
                domain(n, "x")
                domain(n, "y")
                parameterDomain(n)
            }
            "polar" -> {
                expression(n, "expression", required = true)
                domain(n, "x")
                domain(n, "y")
                parameterDomain(n)
            }
            "implicit" -> {
                expression(n, "expression", required = true)
                val comparator = n.opt("comparator")
                require(comparator is String && comparator in setOf("=", "<", "<=", ">", ">=")) { "implicit comparator must be =, <, <=, > or >=" }
                domain(n, "x")
                domain(n, "y")
            }
            "surface3d" -> {
                expression(n, "expression", required = true)
                domain(n, "x")
                domain(n, "y")
                domain(n, "z")
                if (n.has("azimuth")) number(n, "azimuth", -180.0, 180.0)
                if (n.has("elevation")) number(n, "elevation", -89.0, 89.0)
            }
            "calculus" -> {
                expression(n, "expression", required = true)
                val a = number(n, "a", -1e5, 1e5)
                val b = number(n, "b", -1e5, 1e5)
                require(a < b) { "calculus requires a < b" }
                require(b - a <= 1e5) { "calculus interval too wide" }
                number(n, "x0", a, b)
            }
            "equation" -> {
                expression(n, "expression", required = true, allowEquals = true)
                val a = number(n, "a", -1e5, 1e5)
                val b = number(n, "b", -1e5, 1e5)
                require(a < b) { "equation requires a < b" }
                require(b - a <= 1e5) { "equation interval too wide" }
            }
            "matrix" -> {
                val matrix = squareMatrix(n, "matrix")
                if (n.has("rhs")) {
                    val rhs = array(n, "rhs", matrix, matrix)
                    repeat(matrix) { i ->
                        require(rhs.get(i) is Number) { "rhs[$i] must be a number" }
                        finite(rhs.getDouble(i), 1e6, "rhs[$i]")
                    }
                }
            }
            "statistics" -> {
                val values = array(n, "values", 1, MAX_STAT_VALUES)
                repeat(values.length()) { i ->
                    require(values.get(i) is Number) { "values[$i] must be a number" }
                    finite(values.getDouble(i), 1e9, "values[$i]")
                }
            }
            "probability" -> {
                val nv = integer(n, "n", 0, MAX_PROBABILITY_N)
                integer(n, "k", 0, nv)
                number(n, "p", 0.0, 1.0)
            }
            "geometry" -> {
                val shape = n.opt("shape")
                require(shape is String && shape in setOf("triangle", "circle")) { "geometry shape must be triangle or circle" }
                if (shape == "triangle") {
                    require(!n.has("r")) { "triangle does not accept r" }
                    val a = number(n, "a", 0.001, 1e6)
                    val b = number(n, "b", 0.001, 1e6)
                    val c = number(n, "c", 0.001, 1e6)
                    require(a + b > c && a + c > b && b + c > a) { "triangle sides violate the triangle inequality" }
                } else {
                    require(!n.has("a") && !n.has("b") && !n.has("c")) { "circle accepts only r" }
                    number(n, "r", 0.001, 1e6)
                }
            }
            "sequence" -> {
                val kind = n.opt("kind")
                require(kind is String && kind in setOf("arithmetic", "geometric", "fibonacci", "custom")) { "sequence kind must be arithmetic, geometric, fibonacci or custom" }
                number(n, "first", -1e9, 1e9)
                integer(n, "terms", 1, MAX_SEQUENCE_TERMS)
                when (kind) {
                    "arithmetic" -> {
                        number(n, "difference", -1e6, 1e6)
                        require(!n.has("expression") && !n.has("ratio") && !n.has("second")) { "arithmetic rejects ratio/second/expression" }
                    }
                    "geometric" -> {
                        number(n, "ratio", -100.0, 100.0)
                        require(!n.has("expression") && !n.has("difference") && !n.has("second")) { "geometric rejects difference/second/expression" }
                    }
                    "fibonacci" -> {
                        number(n, "second", -1e9, 1e9)
                        require(!n.has("expression") && !n.has("difference") && !n.has("ratio")) { "fibonacci rejects difference/ratio/expression" }
                    }
                    else -> {
                        expression(n, "expression", required = true)
                        require(!n.has("difference") && !n.has("ratio") && !n.has("second")) { "custom rejects difference/ratio/second" }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun keys(o: JSONObject, allowed: Set<String>) {
        val names = o.keys().asSequence().toSet()
        val unknown = names - allowed
        require(unknown.isEmpty()) { "Unknown fields: ${unknown.sorted()}" }
    }

    private fun text(o: JSONObject, key: String, min: Int, max: Int, required: Boolean) {
        if (!required && !o.has(key)) return
        val value = o.opt(key)
        require(value is String) { "$key must be text" }
        require(value.length in min..max) { "$key length out of range" }
        require(value.isNotBlank()) { "$key must not be blank" }
    }

    private fun number(o: JSONObject, key: String, min: Double, max: Double): Double {
        require(o.has(key)) { "$key is required" }
        val value = o.opt(key)
        require(value is Number) { "$key must be a number" }
        val absMax = kotlin.math.max(kotlin.math.abs(min), kotlin.math.abs(max))
        return finite(value.toDouble(), absMax, key).also { require(it in min..max) { "$key out of range" } }
    }

    private fun finite(value: Double, absMax: Double, key: String): Double {
        require(value.isFinite() && kotlin.math.abs(value) <= absMax) { "$key out of range" }
        return value
    }

    private fun integer(o: JSONObject, key: String, min: Int, max: Int): Int {
        require(o.has(key)) { "$key is required" }
        val value = o.opt(key)
        require(value is Number) { "$key must be a number" }
        val d = value.toDouble()
        require(d.isFinite() && d == kotlin.math.floor(d)) { "$key must be an integer" }
        require(d in min.toDouble()..max.toDouble()) { "$key out of range" }
        return d.toInt()
    }

    private fun array(o: JSONObject, key: String, min: Int, max: Int): JSONArray {
        require(o.has(key)) { "$key is required" }
        val value = o.opt(key)
        require(value is JSONArray) { "$key must be an array" }
        require(value.length() in min..max) { "$key count out of range" }
        return value
    }

    private fun expression(o: JSONObject, key: String, required: Boolean, allowEquals: Boolean = false) {
        if (!required && !o.has(key)) return
        val raw = o.opt(key)
        require(raw is String) { "$key must be text" }
        val value = raw.trim()
        require(value.isNotEmpty() && value.length <= MAX_EXPRESSION) { "$key must be 1..$MAX_EXPRESSION characters" }
        val allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_+-*/^(),. <>="
        require(value.all { it in allowed }) { "$key contains unsupported characters" }
        require(value.any { it.isLetterOrDigit() }) { "$key must contain a variable or number" }
        var depth = 0
        for (c in value) {
            if (c == '(') depth++
            if (c == ')') { depth--; require(depth >= 0) { "$key has unbalanced parentheses" } }
            require(depth <= 24) { "$key is nested too deeply" }
        }
        require(depth == 0) { "$key has unbalanced parentheses" }
        val equals = value.count { it == '=' }
        if (allowEquals) {
            require(equals == 1) { "$key must contain exactly one =" }
            require(value.substringBefore("=").isNotBlank() && value.substringAfter("=").isNotBlank()) { "$key has an empty side" }
        } else {
            require(equals == 0) { "$key must not contain =" }
        }
    }

    private fun domain(o: JSONObject, axis: String) {
        val lo = number(o, "${axis}Min", -1e6, 1e6)
        val hi = number(o, "${axis}Max", -1e6, 1e6)
        require(lo < hi) { "${axis}Min must be less than ${axis}Max" }
        require(hi - lo <= 2e6) { "$axis range too wide" }
    }

    private fun parameterDomain(o: JSONObject) {
        val lo = number(o, "tMin", -1000.0, 1000.0)
        val hi = number(o, "tMax", -1000.0, 1000.0)
        require(lo < hi) { "tMin must be less than tMax" }
        require(hi - lo <= 1000.0) { "t range too wide" }
    }

    private fun formula(o: JSONObject, key: String, required: Boolean) {
        if (!required && !o.has(key)) return
        val raw = o.opt(key)
        require(raw is String) { "$key must be text" }
        require(raw.length <= MAX_EXPRESSION) { "$key exceeds $MAX_EXPRESSION characters" }
        UiFormula.validate(raw)
    }

    private fun squareMatrix(o: JSONObject, key: String): Int {
        val rows = array(o, key, 2, MAX_MATRIX)
        val size = rows.length()
        repeat(size) { r ->
            val row = rows.get(r)
            require(row is JSONArray) { "$key[$r] must be an array" }
            require(row.length() == size) { "$key must be square (2..$MAX_MATRIX)" }
            repeat(size) { c ->
                require(row.get(c) is Number) { "$key[$r][$c] must be a number" }
                finite(row.getDouble(c), 1e6, "$key[$r][$c]")
            }
        }
        return size
    }

    private fun sliders(n: JSONObject) {
        if (!n.has("sliders")) return
        val list = array(n, "sliders", 1, MAX_SLIDERS)
        val names = mutableSetOf<String>()
        repeat(list.length()) { i ->
            val s = list.get(i)
            require(s is JSONObject) { "sliders[$i] must be an object" }
            keys(s, setOf("name", "label", "min", "max", "value"))
            val name = s.opt("name")
            require(name is String && Regex("[A-Za-z][A-Za-z0-9_]{0,15}").matches(name)) { "sliders[$i].name is invalid" }
            require(name !in reservedVariables) { "slider name $name is reserved (x/y/z/pi/e/tau)" }
            require(names.add(name)) { "duplicate slider name $name" }
            text(s, "label", 1, 40, required = true)
            val min = number(s, "min", -1e6, 1e6)
            val max = number(s, "max", -1e6, 1e6)
            require(min < max) { "slider $name requires min < max" }
            number(s, "value", min, max)
        }
    }
}

internal sealed interface FNode {
    data class FText(val text: String) : FNode
    data class FRow(val items: List<FNode>) : FNode
    data class FFrac(val numerator: FNode, val denominator: FNode) : FNode
    data class FSqrt(val body: FNode, val index: FNode?) : FNode
    data class FSup(val base: FNode, val sup: FNode) : FNode
    data class FSub(val base: FNode, val sub: FNode) : FNode
    data class FBig(val symbol: String, val sub: FNode?, val sup: FNode?, val body: FNode?) : FNode
    data class FMatrix(val rows: List<List<FNode>>) : FNode
}

internal object UiFormula {
    const val MAX_LENGTH = 512
    private const val MAX_DEPTH = 16
    private const val MAX_NODES = 400
    private const val MAX_MATRIX = 6

    fun validate(source: String) {
        parse(source)
    }

    fun parse(source: String): FNode {
        require(source.isNotBlank() && source.length <= MAX_LENGTH) { "Formula must be 1..$MAX_LENGTH characters" }
        require(source.none { it.isISOControl() }) { "Formula contains control characters" }
        val parser = Parser(source)
        val node = parser.parse()
        require(parser.count in 1..MAX_NODES) { "Formula is empty" }
        return node
    }

    private val symbols = mapOf(
        "pi" to "π", "theta" to "θ", "alpha" to "α", "beta" to "β", "gamma" to "γ",
        "delta" to "δ", "Delta" to "Δ", "lambda" to "λ", "mu" to "μ", "sigma" to "σ",
        "Sigma" to "Σ", "phi" to "φ", "omega" to "ω", "Omega" to "Ω", "infty" to "∞",
        "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠",
        "approx" to "≈", "equiv" to "≡", "to" to "→", "rightarrow" to "→", "Rightarrow" to "⇒",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "cup" to "∪", "cap" to "∩",
        "forall" to "∀", "exists" to "∃", "partial" to "∂", "nabla" to "∇",
        "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "degree" to "°",
        "sin" to "sin", "cos" to "cos", "tan" to "tan", "cot" to "cot", "sec" to "sec",
        "ln" to "ln", "log" to "log", "exp" to "exp", "max" to "max", "min" to "min",
        "quad" to " ", "qquad" to "  ", "ldots" to "…", "cdots" to "⋯",
    )
    private val big = mapOf(
        "sum" to "∑", "prod" to "∏", "int" to "∫", "iint" to "∬", "oint" to "∮",
        "lim" to "lim", "bigcup" to "⋃", "bigcap" to "⋂",
    )

    private class Parser(private val s: String) {
        var i = 0
        var count = 0
        private var depth = 0

        fun parse(): FNode {
            val node = sequence(emptyList(), null)
            skipSpace()
            require(i == s.length) { "Unexpected '${s[i]}' at ${i + 1}" }
            return node
        }

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun sequence(stopTokens: List<String>, stopChar: Char?): FNode {
            val items = mutableListOf<FNode>()
            while (true) {
                skipSpace()
                if (i >= s.length) break
                if (stopChar != null && s[i] == stopChar) break
                if (stopTokens.any { s.startsWith(it, i) }) break
                items += element()
            }
            require(items.isNotEmpty()) { "Empty formula group" }
            return if (items.size == 1) items.first() else FNode.FRow(items)
        }

        private fun element(): FNode {
            require(++count <= MAX_NODES) { "Formula is too complex" }
            var base = atom()
            while (true) {
                skipSpace()
                when {
                    i < s.length && s[i] == '^' -> { i++; base = FNode.FSup(base, script()) }
                    i < s.length && s[i] == '_' -> { i++; base = FNode.FSub(base, script()) }
                    else -> return base
                }
            }
        }

        private fun script(): FNode {
            skipSpace()
            require(i < s.length) { "Missing script after ^ or _" }
            return if (s[i] == '{') group() else atom()
        }

        private fun group(): FNode {
            expect('{')
            depth++
            require(depth <= MAX_DEPTH) { "Formula is nested too deeply" }
            val node = sequence(emptyList(), '}')
            require(i < s.length && s[i] == '}') { "Missing }" }
            i++
            depth--
            return node
        }

        private fun atom(): FNode {
            skipSpace()
            require(i < s.length) { "Unexpected end of formula" }
            val c = s[i]
            return when {
                c == '{' -> group()
                c == '^' || c == '_' -> throw IllegalArgumentException("Script without a base at ${i + 1}")
                c == '}' || c == '&' -> throw IllegalArgumentException("Unexpected '$c' at ${i + 1}")
                c == '\\' -> command()
                c == '[' || c == ']' -> { i++; FNode.FText(c.toString()) }
                c.isWhitespace() -> { skipSpace(); FNode.FText(" ") }
                else -> {
                    val start = i
                    while (i < s.length) {
                        val ch = s[i]
                        if (ch.isWhitespace() || ch in "^_{}\\&[]") break
                        require(isAllowed(ch)) { "Unsupported character '$ch' in formula" }
                        i++
                    }
                    require(i > start) { "Unexpected '$c' at ${start + 1}" }
                    FNode.FText(s.substring(start, i))
                }
            }
        }

        private fun command(): FNode {
            i++ // backslash
            require(i < s.length) { "Dangling backslash" }
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            if (i == start) {
                val escaped = s[i]
                i++
                return when (escaped) {
                    ',', ';', ':', '!', ' ' -> FNode.FText(" ")
                    '{', '}', '_', '&', '%', '#', '$' -> FNode.FText(escaped.toString())
                    else -> throw IllegalArgumentException("Unsupported escape \\$escaped")
                }
            }
            val name = s.substring(start, i)
            return when {
                name == "frac" -> {
                    val numerator = group()
                    val denominator = group()
                    FNode.FFrac(numerator, denominator)
                }
                name == "sqrt" -> {
                    skipSpace()
                    val index = if (i < s.length && s[i] == '[') {
                        i++
                        val n = sequence(listOf("]"), null)
                        expect(']')
                        n
                    } else null
                    FNode.FSqrt(group(), index)
                }
                name == "text" -> {
                    expect('{')
                    val startText = i
                    while (i < s.length && s[i] != '}') i++
                    require(i < s.length) { "Missing } after text" }
                    val value = s.substring(startText, i)
                    i++
                    require(value.isNotEmpty() && value.length <= 120) { "Formula text is too long" }
                    FNode.FText(value)
                }
                name == "begin" -> matrix()
                name in big -> {
                    var sub: FNode? = null
                    var sup: FNode? = null
                    while (true) {
                        skipSpace()
                        if (i < s.length && s[i] == '_') { i++; sub = script() }
                        else if (i < s.length && s[i] == '^') { i++; sup = script() }
                        else break
                    }
                    skipSpace()
                    val body = if (i >= s.length || s[i] == '}' || s[i] == '&' || s.startsWith("\\\\", i)) null else element()
                    FNode.FBig(big.getValue(name), sub, sup, body)
                }
                name in symbols -> FNode.FText(symbols.getValue(name))
                else -> throw IllegalArgumentException("Unsupported formula command \\$name")
            }
        }

        private fun matrix(): FNode {
            expect('{')
            val envStart = i
            while (i < s.length && s[i] != '}') i++
            require(i < s.length) { "Missing } after matrix environment" }
            val env = s.substring(envStart, i).trim()
            require(env in setOf("matrix", "pmatrix", "bmatrix", "vmatrix")) { "Unsupported environment $env" }
            i++
            val rows = mutableListOf<List<FNode>>()
            var row = mutableListOf<FNode>()
            while (true) {
                skipSpace()
                when {
                    i >= s.length -> throw IllegalArgumentException("Missing \\end{$env}")
                    s.startsWith("\\end", i) -> {
                        if (row.isNotEmpty()) rows += row
                        i += 4
                        expect('{')
                        val close = s.indexOf('}', i)
                        require(close > i) { "Malformed \\end{$env}" }
                        val end = s.substring(i, close)
                        require(end == env) { "Mismatched \\end{$end}" }
                        i = close + 1
                        break
                    }
                    s.startsWith("\\\\", i) -> {
                        require(row.isNotEmpty()) { "Empty matrix row" }
                        rows += row
                        row = mutableListOf()
                        i += 2
                    }
                    s[i] == '&' -> {
                        require(row.isNotEmpty()) { "Empty matrix cell" }
                        i++
                    }
                    else -> row += sequence(listOf("&", "\\\\", "\\end"), null)
                }
                require(rows.size <= MAX_MATRIX) { "Matrix has more than $MAX_MATRIX rows" }
                require(row.size <= MAX_MATRIX) { "Matrix has more than $MAX_MATRIX columns" }
            }
            require(rows.size in 2..MAX_MATRIX) { "Matrix must have 2..$MAX_MATRIX rows" }
            require(rows.all { it.size == rows.first().size }) { "Matrix rows must have equal length" }
            return FNode.FMatrix(rows)
        }

        private fun expect(c: Char) {
            skipSpace()
            require(i < s.length && s[i] == c) { "Expected '$c' at ${i + 1}" }
            i++
        }

        private fun isAllowed(c: Char): Boolean =
            c.isLetterOrDigit() || c in " +-*/^_=<>()[]{}.,:;!|'"
    }
}
