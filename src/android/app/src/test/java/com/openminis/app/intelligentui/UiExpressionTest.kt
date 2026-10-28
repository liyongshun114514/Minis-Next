package com.openminis.app.intelligentui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.E

class UiExpressionTest {
    @Test fun precedenceAndParentheses() { assertEquals(14.0, UiExpression.evaluate("2+3*4", emptyMap()), .0001); assertEquals(20.0, UiExpression.evaluate("(2+3)*4", emptyMap()), .0001) }
    @Test fun billSplit() { assertEquals(44.0, UiExpression.evaluate("total*(1+tip/100)/people", mapOf("total" to 120.0,"tip" to 10.0,"people" to 3.0)), .0001) }
    @Test fun powersAndUnary() { assertEquals(512.0, UiExpression.evaluate("2^3^2", emptyMap()), .0001); assertEquals(-4.0, UiExpression.evaluate("-(2+2)", emptyMap()), .0001) }
    @Test fun functions() { assertEquals(5.0, UiExpression.evaluate("max(2, sqrt(25))", emptyMap()), .0001); assertEquals(4.0, UiExpression.evaluate("abs(-3)+min(1,2)", emptyMap()), .0001) }
    @Test fun divisionByZero() { rejected("1/0") }
    @Test fun unknownVariable() { rejected("secret") }
    @Test fun arbitraryCodeIsNotAccepted() { listOf("Runtime.exec('id')", "fetch(1)", "1;2", "a.b", "1e9999", "sqrt(-1)", "2^10000").forEach { rejected(it) } }
    @Test fun boundedComplexity() { rejected("(".repeat(40)+"1"+")".repeat(40)); rejected("1+".repeat(300)+"1") }
    @Test fun trailingInputAndMalformedNumbers() { listOf("1 2", "1..2", "", ".", "min(1)", "abs(1,2)", "(1+2").forEach { rejected(it) } }
    @Test fun variablesAreData() { assertEquals(7.0, UiExpression.evaluate("a_b + value2", mapOf("a_b" to 2.0,"value2" to 5.0)), .0001) }

    // ---------------------------------------------------------------- enhanced catalog

    @Test fun trigonometry() {
        assertEquals(1.0, UiExpression.evaluate("sin(pi/2)", emptyMap()), 1e-12)
        assertEquals(1.0, UiExpression.evaluate("cos(0)", emptyMap()), 1e-12)
        assertEquals(0.0, UiExpression.evaluate("sin(0)", emptyMap()), 1e-12)
        assertEquals(PI / 2, UiExpression.evaluate("asin(1)", emptyMap()), 1e-12)
        assertEquals(0.0, UiExpression.evaluate("acos(1)", emptyMap()), 1e-12)
        assertEquals(PI / 4, UiExpression.evaluate("atan(1)", emptyMap()), 1e-12)
        assertEquals(PI / 4, UiExpression.evaluate("atan2(1,1)", emptyMap()), 1e-12)
        assertEquals(0.0, UiExpression.evaluate("sinh(0)", emptyMap()), 1e-12)
        assertEquals(1.0, UiExpression.evaluate("cosh(0)", emptyMap()), 1e-12)
        assertEquals(0.0, UiExpression.evaluate("tanh(0)", emptyMap()), 1e-12)
        assertEquals(0.5, UiExpression.evaluate("tan(0.4636476090008061)", emptyMap()), 1e-9)
    }

    @Test fun logarithmsExponentialsAndRoots() {
        assertEquals(1.0, UiExpression.evaluate("ln(e)", emptyMap()), 1e-12)
        assertEquals(2.0, UiExpression.evaluate("log(100)", emptyMap()), 1e-12)
        assertEquals(3.0, UiExpression.evaluate("log(8, 2)", emptyMap()), 1e-12)
        assertEquals(3.0, UiExpression.evaluate("log10(1000)", emptyMap()), 1e-12)
        assertEquals(10.0, UiExpression.evaluate("log2(1024)", emptyMap()), 1e-12)
        assertEquals(E, UiExpression.evaluate("exp(1)", emptyMap()), 1e-12)
        assertEquals(5.0, UiExpression.evaluate("hypot(3,4)", emptyMap()), 1e-12)
        assertEquals(1024.0, UiExpression.evaluate("pow(2,10)", emptyMap()), 1e-9)
        assertEquals(2.0, UiExpression.evaluate("floor(2.9)", emptyMap()), 0.0)
        assertEquals(3.0, UiExpression.evaluate("ceil(2.1)", emptyMap()), 0.0)
        assertEquals(-1.0, UiExpression.evaluate("sign(-3)", emptyMap()), 0.0)
        assertEquals(0.0, UiExpression.evaluate("sign(0)", emptyMap()), 0.0)
    }

    @Test fun constants() {
        assertEquals(PI, UiExpression.evaluate("pi", emptyMap()), 1e-12)
        assertEquals(E, UiExpression.evaluate("e", emptyMap()), 1e-12)
        assertEquals(2 * PI, UiExpression.evaluate("tau", emptyMap()), 1e-12)
        assertEquals(2 * PI, UiExpression.evaluate("2*pi", emptyMap()), 1e-12)
        assertEquals(3.0, UiExpression.evaluate("pi", mapOf("pi" to 3.0)), 0.0)
    }

    @Test fun scientificNotation() {
        assertEquals(1500.0, UiExpression.evaluate("1.5e3", emptyMap()), 0.0)
        assertEquals(0.0025, UiExpression.evaluate("2.5E-3", emptyMap()), 1e-15)
        assertEquals(100.0, UiExpression.evaluate("1e+2", emptyMap()), 0.0)
        assertEquals(0.5, UiExpression.evaluate(".5", emptyMap()), 0.0)
        assertEquals(6.022e23, UiExpression.evaluate("6.022e23", emptyMap()), 1e8)
        rejected("1e")
        rejected("e5")
        rejected("1e+")
    }

    @Test fun powerPrecedenceOverUnaryMinus() {
        assertEquals(-9.0, UiExpression.evaluate("-3^2", emptyMap()), 1e-12)
        assertEquals(9.0, UiExpression.evaluate("(-3)^2", emptyMap()), 1e-12)
        assertEquals(-9.0, UiExpression.evaluate("-x^2", mapOf("x" to 3.0)), 1e-12)
        assertEquals(9.0, UiExpression.evaluate("(-x)^2", mapOf("x" to 3.0)), 1e-12)
        assertEquals(0.25, UiExpression.evaluate("2^-2", emptyMap()), 1e-12)
        assertEquals(-8.0, UiExpression.evaluate("-2^3", emptyMap()), 1e-12)
        assertEquals(0.125, UiExpression.evaluate("2^-3", emptyMap()), 1e-12)
        assertEquals(1.0, UiExpression.evaluate("2^0", emptyMap()), 0.0)
        assertEquals(512.0, UiExpression.evaluate("2^3^2", emptyMap()), 0.0)
    }

    @Test fun factorialIsBounded() {
        assertEquals(120.0, UiExpression.evaluate("5!", emptyMap()), 0.0)
        assertEquals(1.0, UiExpression.evaluate("0!", emptyMap()), 0.0)
        assertEquals(3628800.0, UiExpression.evaluate("(3+7)!", emptyMap()), 0.0)
        assertEquals(720.0, UiExpression.evaluate("3!!", emptyMap()), 0.0)
        assertTrue(UiExpression.evaluate("170!", emptyMap()).isFinite())
        rejected("171!")
        rejected("(-1)!")
        rejected("3.5!")
    }

    @Test fun domainErrorsAreRejected() {
        listOf(
            "ln(0)", "ln(-1)", "log(0)", "log(8,1)", "log(-8,2)", "log10(0)", "log2(-2)",
            "sqrt(-1)", "asin(2)", "acos(-1.5)", "tan(pi/2)", "tan(1.5707963267948966)",
            "exp(1000)", "10^400", "pow(-2,0.5)"
        ).forEach { rejected(it) }
    }

    @Test fun factorialFunctionMatchesPostfixOperator() {
        assertEquals(120.0, UiExpression.evaluate("factorial(5)", emptyMap()), 0.0)
        assertEquals(1.0, UiExpression.evaluate("factorial(0)", emptyMap()), 0.0)
        assertEquals(UiExpression.evaluate("10!", emptyMap()), UiExpression.evaluate("factorial(10)", emptyMap()), 0.0)
        assertEquals(3628800.0, UiExpression.evaluate("factorial(3+7)", emptyMap()), 0.0)
        assertTrue(UiExpression.evaluate("factorial(170)", emptyMap()).isFinite())
        listOf("factorial(-1)", "factorial(2.5)", "factorial(171)", "factorial(5,1)", "factorial()").forEach { rejected(it) }
    }

    @Test fun functionTrailingCommasAreRejected() {
        listOf(
            "min(1,2,)", "max(1,2,)", "pow(2,3,)", "log(8,2,)", "hypot(3,4,)", "atan2(1,1,)",
            "min(,1)", "min(1,,2)", "min(1,)"
        ).forEach { rejected(it) }
    }

    @Test fun intermediateOverflowIsNotMasked() {
        listOf(
            "(1e308*1e308)^0", "1e308*1e308", "1e308+1e308", "1e308/1e-10", "(1e308-(-1e308))^0",
            "1e308*10", "(1e308*1e308)-1e308^2"
        ).forEach { rejected(it) }
        assertEquals(1.0, UiExpression.evaluate("1e308*1e-308", emptyMap()), 1e-12)
        assertEquals(1.0, UiExpression.evaluate("(1e300*1e-300)", emptyMap()), 1e-12)
    }

    @Test fun boundsOnLengthAndComplexity() {
        rejected("1".repeat(513))
        rejected("-".repeat(60) + "1")
        rejected("9".repeat(400))
        assertEquals(1.0, UiExpression.evaluate("((((((((1))))))))", emptyMap()), 0.0)
    }

    private fun rejected(s: String) { try { UiExpression.evaluate(s, emptyMap()); fail("Accepted: $s") } catch (_: Exception) { } }
}
