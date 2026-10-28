package com.openminis.app.intelligentui

import org.junit.Assert.*
import org.junit.Test

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
    private fun rejected(s: String) { try { UiExpression.evaluate(s, emptyMap()); fail("Accepted: $s") } catch (_: Exception) { } }
}
