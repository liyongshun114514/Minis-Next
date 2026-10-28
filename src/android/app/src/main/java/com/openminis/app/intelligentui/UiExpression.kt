package com.openminis.app.intelligentui

import kotlin.math.*

/** Bounded arithmetic only. Never evaluates code, reflection, commands, or network calls. */
object UiExpression {
    fun evaluate(expression: String, variables: Map<String, Double>): Double {
        require(expression.length in 1..512) { "Expression must be 1..512 characters" }
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
        fun take(c: Char): Boolean { space(); return if (i < source.length && source[i] == c) { i++; true } else false }
        fun guard(depth: Int) { require(depth <= 24 && ++operations <= 512) { "Expression too complex" } }
        fun expression(depth: Int): Double {
            guard(depth)
            var value = term(depth + 1)
            while (true) value = when { take('+') -> value + term(depth + 1); take('-') -> value - term(depth + 1); else -> return value }
        }
        fun term(depth: Int): Double {
            guard(depth)
            var value = power(depth + 1)
            while (true) value = when {
                take('*') -> value * power(depth + 1)
                take('/') -> { val d = power(depth + 1); require(d != 0.0) { "Division by zero" }; value / d }
                else -> return value
            }
        }
        fun power(depth: Int): Double {
            guard(depth)
            val value = atom(depth + 1)
            return if (take('^')) value.pow(power(depth + 1)) else value
        }
        fun atom(depth: Int): Double {
            guard(depth)
            if (take('+')) return atom(depth + 1)
            if (take('-')) return -atom(depth + 1)
            if (take('(')) { val x = expression(depth + 1); require(take(')')) { "Missing )" }; return x }
            space()
            val start = i
            if (i < source.length && (source[i].isLetter() || source[i] == '_')) {
                while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                val name = source.substring(start, i)
                if (!take('(')) return variables[name] ?: error("Unknown variable: $name")
                val a = expression(depth + 1)
                val b = if (take(',')) expression(depth + 1) else null
                require(take(')')) { "Missing )" }
                return when (name) {
                    "min" -> min(a, requireNotNull(b)); "max" -> max(a, requireNotNull(b))
                    "round" -> { require(b == null); round(a) }; "abs" -> { require(b == null); abs(a) }
                    "sqrt" -> { require(b == null && a >= 0); sqrt(a) }
                    else -> error("Unsupported function: $name")
                }
            }
            while (i < source.length && (source[i].isDigit() || source[i] == '.')) i++
            require(i > start) { "Number or variable expected" }
            return source.substring(start, i).toDouble().also { require(it.isFinite()) }
        }
    }
}
