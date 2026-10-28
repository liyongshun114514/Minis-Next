package com.openminis.app.intelligentui

import kotlin.math.*

/**
 * Bounded arithmetic only. Never evaluates code, reflection, commands, or network calls.
 *
 * Grammar (recursive descent):
 *   expression := term (('+' | '-') term)*
 *   term       := unary (('*' | '/') unary)*
 *   unary      := ('+' | '-') unary | power
 *   power      := postfix ('^' unary)?          // right associative, exponent may be signed
 *   postfix    := atom '!'*
 *   atom       := number | name | name '(' expression (',' expression)? ')' | '(' expression ')'
 *
 * Unary minus binds looser than '^', so -x^2 = -(x^2) while (-x)^2 = x^2.
 * Limits: source length 1..512, nesting depth <= 64, 512 parser operations,
 * factorial arguments 0..170, every intermediate value must be finite.
 *
 * Constants: pi, e, tau (an explicit variable of the same name wins).
 * One argument: sin cos tan asin acos atan sinh cosh tanh ln log log10 log2 exp floor ceil
 *               sign sqrt abs round factorial
 * Two arguments: log(base) pow hypot atan2 min max
 * Postfix: n! for integer n in 0..170. Numbers accept 1.5e-3 style scientific notation.
 * `log(x)` is base 10; `log(x, b)` is base b.
 */
object UiExpression {
    private const val MAX_LENGTH = 512
    private const val MAX_DEPTH = 64
    private const val MAX_OPERATIONS = 512
    private const val MAX_FACTORIAL = 170

    /** Built-in constants; an explicit variable of the same name takes precedence. */
    private val CONSTANTS: Map<String, Double> = mapOf(
        "pi" to PI,
        "e" to E,
        "tau" to 2.0 * PI
    )

    fun evaluate(expression: String, variables: Map<String, Double>): Double {
        require(expression.length in 1..MAX_LENGTH) { "Expression must be 1..$MAX_LENGTH characters" }
        variables.forEach { (name, value) -> require(value.isFinite()) { "Variable $name is not finite" } }
        val p = Parser(expression, variables)
        val result = p.expression(0)
        p.space()
        require(p.atEnd()) { "Unexpected expression suffix" }
        require(result.isFinite()) { "Result is not finite" }
        return result
    }

    private class Parser(val source: String, val variables: Map<String, Double>) {
        var i = 0
        var operations = 0

        fun space() { while (i < source.length && source[i].isWhitespace()) i++ }
        fun atEnd() = i == source.length
        fun take(c: Char): Boolean {
            space()
            return if (i < source.length && source[i] == c) { i++; true } else false
        }
        fun guard(depth: Int) {
            require(depth <= MAX_DEPTH && ++operations <= MAX_OPERATIONS) { "Expression too complex" }
        }

        /** Every arithmetic level must stay finite, so overflow cannot be hidden by a later step. */
        fun finite(value: Double): Double {
            require(value.isFinite()) { "Result is not finite" }
            return value
        }

        fun expression(depth: Int): Double {
            guard(depth)
            var value = term(depth + 1)
            while (true) {
                value = when {
                    take('+') -> finite(value + term(depth + 1))
                    take('-') -> finite(value - term(depth + 1))
                    else -> return value
                }
            }
        }

        fun term(depth: Int): Double {
            guard(depth)
            var value = unary(depth + 1)
            while (true) {
                value = when {
                    take('*') -> finite(value * unary(depth + 1))
                    take('/') -> {
                        val divisor = unary(depth + 1)
                        require(divisor != 0.0) { "Division by zero" }
                        finite(value / divisor)
                    }
                    else -> return value
                }
            }
        }

        fun unary(depth: Int): Double {
            guard(depth)
            if (take('+')) return unary(depth + 1)
            if (take('-')) return -unary(depth + 1)
            return power(depth + 1)
        }

        fun power(depth: Int): Double {
            guard(depth)
            val base = postfix(depth + 1)
            if (!take('^')) return base
            val exponent = unary(depth + 1)
            val result = base.pow(exponent)
            require(!result.isNaN()) { "Power is outside its domain" }
            require(result.isFinite()) { "Result is not finite" }
            return result
        }

        fun postfix(depth: Int): Double {
            guard(depth)
            var value = atom(depth + 1)
            while (take('!')) {
                require(value >= 0.0 && value == floor(value) && value <= MAX_FACTORIAL) {
                    "Factorial requires an integer in 0..$MAX_FACTORIAL"
                }
                value = factorial(value.toInt())
            }
            return value
        }

        fun atom(depth: Int): Double {
            guard(depth)
            if (take('(')) {
                val inner = expression(depth + 1)
                require(take(')')) { "Missing )" }
                return inner
            }
            space()
            val start = i
            if (i < source.length && (source[i].isLetter() || source[i] == '_')) {
                while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                val name = source.substring(start, i)
                if (!take('(')) {
                    return variables[name] ?: CONSTANTS[name] ?: error("Unknown variable: $name")
                }
                val a = expression(depth + 1)
                val b = if (take(',')) expression(depth + 1) else null
                // Only ')' may follow the optional second argument; a trailing comma such as
                // min(1,2,) must not be accepted.
                require(take(')')) { "Missing )" }
                return function(name, a, b)
            }
            while (i < source.length && (source[i].isDigit() || source[i] == '.')) i++
            if (i > start && i < source.length && (source[i] == 'e' || source[i] == 'E')) {
                var j = i + 1
                if (j < source.length && (source[j] == '+' || source[j] == '-')) j++
                if (j < source.length && source[j].isDigit()) {
                    i = j
                    while (i < source.length && source[i].isDigit()) i++
                }
            }
            require(i > start) { "Number or variable expected" }
            val text = source.substring(start, i)
            val value = text.toDoubleOrNull() ?: error("Malformed number: $text")
            require(value.isFinite()) { "Number is not finite" }
            return value
        }

        fun function(name: String, a: Double, b: Double?): Double {
            fun one(): Double {
                require(b == null) { "$name takes exactly one argument" }
                return a
            }
            fun two(): Double {
                require(b != null) { "$name takes exactly two arguments" }
                return b
            }
            val result: Double = when (name) {
                "sin" -> sin(one())
                "cos" -> cos(one())
                "tan" -> {
                    val v = one()
                    require(abs(cos(v)) > 1e-12) { "tan is undefined at this point" }
                    tan(v)
                }
                "asin" -> { val v = one(); require(abs(v) <= 1.0) { "asin domain is -1..1" }; asin(v) }
                "acos" -> { val v = one(); require(abs(v) <= 1.0) { "acos domain is -1..1" }; acos(v) }
                "atan" -> atan(one())
                "sinh" -> sinh(one())
                "cosh" -> cosh(one())
                "tanh" -> tanh(one())
                "ln" -> { val v = one(); require(v > 0.0) { "ln domain is positive" }; ln(v) }
                "log" -> {
                    if (b == null) {
                        require(a > 0.0) { "log domain is positive" }
                        log10(a)
                    } else {
                        require(a > 0.0 && b > 0.0 && b != 1.0) { "log needs a positive value and a base other than 1" }
                        ln(a) / ln(b)
                    }
                }
                "log10" -> { val v = one(); require(v > 0.0) { "log10 domain is positive" }; log10(v) }
                "log2" -> { val v = one(); require(v > 0.0) { "log2 domain is positive" }; log2(v) }
                "exp" -> { val v = one(); require(v <= 709.0) { "exp overflows" }; exp(v) }
                "floor" -> floor(one())
                "ceil" -> ceil(one())
                "sign" -> sign(one())
                "pow" -> a.pow(two())
                "hypot" -> hypot(a, two())
                "atan2" -> atan2(a, two())
                "sqrt" -> { val v = one(); require(v >= 0.0) { "sqrt domain is non-negative" }; sqrt(v) }
                "abs" -> abs(one())
                "round" -> round(one())
                "factorial" -> {
                    val v = one()
                    require(v >= 0.0 && v == floor(v) && v <= MAX_FACTORIAL) {
                        "factorial requires an integer in 0..$MAX_FACTORIAL"
                    }
                    factorial(v.toInt())
                }
                "min" -> min(a, two())
                "max" -> max(a, two())
                else -> error("Unsupported function: $name")
            }
            require(!result.isNaN()) { "$name is outside its domain" }
            require(result.isFinite()) { "$name result is not finite" }
            return result
        }

        fun factorial(n: Int): Double {
            var acc = 1.0
            for (k in 2..n) acc *= k
            return acc
        }
    }
}
