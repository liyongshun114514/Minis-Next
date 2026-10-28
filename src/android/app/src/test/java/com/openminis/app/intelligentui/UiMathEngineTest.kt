package com.openminis.app.intelligentui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.E
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

class UiMathEngineTest {

    // ---------------------------------------------------------------- derivative (approximate)

    @Test fun derivativeOfKnownFunctions() {
        assertEquals(1.0, UiMathEngine.derivative("sin(x)", 0.0), 1e-6)
        assertEquals(0.0, UiMathEngine.derivative("sin(x)", PI / 2), 1e-6)
        assertEquals(6.0, UiMathEngine.derivative("x^2", 3.0), 1e-5)
        assertEquals(3.0, UiMathEngine.derivative("x^3", 1.0), 1e-5)
        assertEquals(E, UiMathEngine.derivative("exp(x)", 1.0), 1e-5)
        assertEquals(0.5, UiMathEngine.derivative("ln(x)", 2.0), 1e-6)
        assertEquals(-0.25, UiMathEngine.derivative("1/x", 2.0), 1e-7)
    }

    @Test fun derivativeWithVariables() {
        assertEquals(12.0, UiMathEngine.derivative("a*x^2", 2.0, mapOf("a" to 3.0)), 1e-5)
        assertEquals(1.0, UiMathEngine.derivative("x-a", 0.5, mapOf("a" to 0.25)), 1e-6)
    }

    @Test fun derivativeOfProductAndConstant() {
        assertEquals(0.0, UiMathEngine.derivative("5", 2.0), 1e-12)
        assertEquals(0.0770037537313969, UiMathEngine.derivative("x*sin(x)", 2.0), 1e-6)
        assertEquals(-1.0 / 9.0, UiMathEngine.derivative("1/x", 3.0), 1e-7)
    }

    @Test fun derivativeRejectsDomainAndPoles() {
        rejectedDerivative("sqrt(x)", 0.0)
        rejectedDerivative("ln(x)", 0.0)
        rejectedDerivative("tan(x)", PI / 2)
        rejectedDerivative("1/x", 0.0)
        rejectedDerivative("sqrt(x)", -1.0)
    }

    @Test fun derivativeRejectsCuspsAndChecksBothSides() {
        // |x| is not differentiable at 0 although the symmetric central difference is 0.
        rejectedDerivative("abs(x)", 0.0)
        assertEquals(1.0, UiMathEngine.derivative("abs(x)", 1.0), 1e-5)
        assertEquals(-1.0, UiMathEngine.derivative("abs(x)", -1.0), 1e-5)
        // x*|x| is differentiable at 0 (both one sided slopes are 0).
        assertEquals(0.0, UiMathEngine.derivative("x*abs(x)", 0.0), 1e-6)
        // smooth one sided agreement is not rejected
        assertEquals(0.75, UiMathEngine.derivative("x^3", 0.5), 1e-6)
        assertEquals(0.0, UiMathEngine.derivative("x^2", 0.0), 1e-6)
    }

    @Test fun derivativeAtDomainEdgesIsOneSidedOnly() {
        rejectedDerivative("sqrt(x)", 0.0)
        assertEquals(0.5, UiMathEngine.derivative("sqrt(x)", 1.0), 1e-6)
        assertEquals(1.0, UiMathEngine.derivative("sqrt(x)", 0.25), 1e-6)
        assertEquals(1.0 / (2 * sqrt(4.0)), UiMathEngine.derivative("sqrt(x)", 4.0), 1e-7)
    }

    // ---------------------------------------------------------------- integration (approximate)

    @Test fun integralOfKnownFunctions() {
        assertEquals(1.0 / 3, UiMathEngine.integrate("x^2", 0.0, 1.0), 1e-9)
        assertEquals(2.0, UiMathEngine.integrate("sin(x)", 0.0, PI), 1e-9)
        assertEquals(ln(2.0), UiMathEngine.integrate("1/x", 1.0, 2.0), 1e-10)
        assertEquals(0.0, UiMathEngine.integrate("x^3", -1.0, 1.0), 1e-9)
        assertEquals(0.7468241328124271, UiMathEngine.integrate("exp(-x^2)", 0.0, 1.0), 1e-8)
        assertEquals(0.0, UiMathEngine.integrate("x^2", 2.0, 2.0), 0.0)
    }

    @Test fun integralRespectsReversedBoundsAndVariables() {
        assertEquals(-0.5, UiMathEngine.integrate("x", 1.0, 0.0), 1e-12)
        assertEquals(0.5, UiMathEngine.integrate("k*x", 0.0, 1.0, mapOf("k" to 1.0)), 1e-12)
    }

    @Test fun integralRejectsPolesAndDomainErrors() {
        rejectedIntegral("1/x", -1.0, 1.0)
        rejectedIntegral("ln(x)", 0.0, 1.0)
        rejectedIntegral("sqrt(x)", -1.0, 1.0)
        rejectedIntegral("1/x", 0.0, 1.0)
    }

    @Test fun integralBudgetExhaustionIsAnError() {
        // A highly oscillatory integrand cannot reach 1e-9 within 512 leaves; it must throw
        // instead of silently returning the budget truncated value.
        rejectedIntegral("sin(2000*x)", 0.0, 1.0)
    }

    @Test fun integralStaysHonestForOscillatoryIntegrands() {
        // Ten periods per unit interval still converges within budget ...
        assertEquals((1 - cos(10.0)) / 10, UiMathEngine.integrate("sin(10*x)", 0.0, 1.0), 1e-9)
        // ... 50 periods do not: the old code aliased to -0.0326 (true value 0.00070), now it throws.
        rejectedIntegral("sin(50*x)", 0.0, 1.0)
    }

    // ---------------------------------------------------------------- roots (approximate)

    @Test fun integralOfEndpointSingularityAndRational() {
        assertEquals(2.0 / 3.0, UiMathEngine.integrate("sqrt(x)", 0.0, 1.0), 1e-8)
        assertEquals(PI / 2, UiMathEngine.integrate("1/(1+x^2)", -1.0, 1.0), 1e-9)
    }

    @Test fun rootsOfKnownFunctions() {
        val one = UiMathEngine.roots("x^2-2", 0.0, 2.0)
        assertEquals(1, one.size)
        assertEquals(sqrt(2.0), one[0], 1e-9)

        val two = UiMathEngine.roots("x^2-2", -2.0, 2.0)
        assertEquals(2, two.size)
        assertEquals(-sqrt(2.0), two[0], 1e-9)
        assertEquals(sqrt(2.0), two[1], 1e-9)

        val trig = UiMathEngine.roots("sin(x)", 0.0, 4.0)
        assertEquals(2, trig.size)
        assertEquals(0.0, trig[0], 1e-9)
        assertEquals(PI, trig[1], 1e-9)

        assertEquals(0.5, UiMathEngine.roots("x-0.5", 0.0, 1.0).single(), 1e-9)

        val cubic = UiMathEngine.roots("x^3-x", -2.0, 2.0)
        assertEquals(3, cubic.size)
        assertEquals(-1.0, cubic[0], 1e-9)
        assertEquals(0.0, cubic[1], 1e-9)
        assertEquals(1.0, cubic[2], 1e-9)
        assertEquals(0.25, UiMathEngine.roots("x-a", 0.0, 1.0, mapOf("a" to 0.25)).single(), 1e-9)
    }

    @Test fun rootsIgnorePolesAndRootlessFunctions() {
        assertTrue(UiMathEngine.roots("1/x", -1.0, 1.0).isEmpty())
        assertTrue(UiMathEngine.roots("1/x", -1.0, 2.0).isEmpty())
        assertTrue(UiMathEngine.roots("x^2+1", -5.0, 5.0).isEmpty())
        assertTrue(UiMathEngine.roots("tan(x)", 1.0, 2.0).isEmpty())
        assertTrue(UiMathEngine.roots("sqrt(x)", -1.0, -0.5).isEmpty())
    }

    @Test fun rootsRequireOrderedRange() {
        try { UiMathEngine.roots("x", 1.0, 1.0); fail("accepted empty range") } catch (_: IllegalArgumentException) { }
        try { UiMathEngine.roots("x", 2.0, 1.0); fail("accepted reversed range") } catch (_: IllegalArgumentException) { }
    }

    // ---------------------------------------------------------------- matrices

    @Test fun determinants() {
        assertEquals(5.0, UiMathEngine.determinant(listOf(listOf(5.0))), 1e-12)
        assertEquals(-2.0, UiMathEngine.determinant(listOf(listOf(1.0, 2.0), listOf(3.0, 4.0))), 1e-9)
        assertEquals(1.0, UiMathEngine.determinant(listOf(listOf(1.0, 0.0), listOf(0.0, 1.0))), 1e-12)
        assertEquals(0.0, UiMathEngine.determinant(listOf(listOf(1.0, 2.0), listOf(2.0, 4.0))), 1e-9)
        assertEquals(-306.0, UiMathEngine.determinant(listOf(
            listOf(6.0, 1.0, 1.0), listOf(4.0, -2.0, 5.0), listOf(2.0, 8.0, 7.0)
        )), 1e-9)
    }

    @Test fun matrixConstraints() {
        val seven = List(7) { r -> List(7) { c -> if (r == c) 1.0 else 0.0 } }
        rejectedMatrix { UiMathEngine.determinant(seven) }
        rejectedMatrix { UiMathEngine.determinant(listOf(listOf(1.0, 2.0), listOf(3.0))) }
        rejectedMatrix { UiMathEngine.determinant(emptyList()) }
        rejectedMatrix { UiMathEngine.determinant(listOf(listOf(Double.NaN))) }
    }

    @Test fun inverses() {
        val inv = UiMathEngine.inverse(listOf(listOf(4.0, 7.0), listOf(2.0, 6.0)))
        assertEquals(0.6, inv[0][0], 1e-12)
        assertEquals(-0.7, inv[0][1], 1e-12)
        assertEquals(-0.2, inv[1][0], 1e-12)
        assertEquals(0.4, inv[1][1], 1e-12)
        assertEquals(0.25, UiMathEngine.inverse(listOf(listOf(4.0)))[0][0], 1e-12)

        val a = listOf(listOf(2.0, -1.0, 0.0), listOf(-1.0, 2.0, -1.0), listOf(0.0, -1.0, 2.0))
        val product = multiply(a, UiMathEngine.inverse(a))
        for (r in 0..2) for (c in 0..2) assertEquals(if (r == c) 1.0 else 0.0, product[r][c], 1e-12)
    }

    @Test fun singularMatrixIsRejected() {
        rejectedMatrix { UiMathEngine.inverse(listOf(listOf(1.0, 2.0), listOf(2.0, 4.0))) }
        rejectedMatrix { UiMathEngine.inverse(listOf(listOf(1.0, 2.0, 3.0), listOf(4.0, 5.0, 6.0), listOf(7.0, 8.0, 9.0))) }
        rejectedMatrix { UiMathEngine.solve(listOf(listOf(1.0, 2.0), listOf(2.0, 4.0)), listOf(1.0, 2.0)) }
    }

    @Test fun solvesLinearSystems() {
        val two = UiMathEngine.solve(listOf(listOf(2.0, 1.0), listOf(1.0, 3.0)), listOf(5.0, 10.0))
        assertEquals(1.0, two[0], 1e-12)
        assertEquals(3.0, two[1], 1e-12)

        val three = UiMathEngine.solve(
            listOf(listOf(1.0, 1.0, 1.0), listOf(0.0, 2.0, 5.0), listOf(2.0, 5.0, -1.0)),
            listOf(6.0, -4.0, 27.0)
        )
        assertEquals(5.0, three[0], 1e-12)
        assertEquals(3.0, three[1], 1e-12)
        assertEquals(-2.0, three[2], 1e-12)

        // pivoting is required here: first pivot is zero
        val pivoted = UiMathEngine.solve(listOf(listOf(0.0, 1.0), listOf(1.0, 0.0)), listOf(2.0, 3.0))
        assertEquals(3.0, pivoted[0], 1e-12)
        assertEquals(2.0, pivoted[1], 1e-12)

        assertEquals(1.5, UiMathEngine.solve(listOf(listOf(2.0)), listOf(3.0)).single(), 1e-12)

        // A large right hand side must not be mistaken for singularity.
        val scaled = UiMathEngine.solve(
            listOf(listOf(1.0, 0.0), listOf(0.0, 1.0)),
            listOf(1e12, -1e12)
        )
        assertEquals(1e12, scaled[0], 1e-3)
        assertEquals(-1e12, scaled[1], 1e-3)
    }

    @Test fun solveRejectsBadShapes() {
        rejectedMatrix { UiMathEngine.solve(listOf(listOf(1.0, 0.0), listOf(0.0, 1.0)), listOf(1.0)) }
        rejectedMatrix { UiMathEngine.solve(listOf(listOf(1.0, 2.0)), listOf(1.0)) }
        rejectedMatrix { UiMathEngine.solve(listOf(listOf(1.0)), listOf(Double.POSITIVE_INFINITY)) }
    }

    // ---------------------------------------------------------------- statistics

    @Test fun statisticsOfKnownSample() {
        val stats = UiMathEngine.statistics(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0))
        assertEquals(setOf("count", "mean", "median", "min", "max", "variance", "stddev"), stats.keys)
        assertEquals(8.0, stats.getValue("count"), 0.0)
        assertEquals(5.0, stats.getValue("mean"), 1e-12)
        assertEquals(4.5, stats.getValue("median"), 1e-12)
        assertEquals(2.0, stats.getValue("min"), 0.0)
        assertEquals(9.0, stats.getValue("max"), 0.0)
        assertEquals(32.0 / 7.0, stats.getValue("variance"), 1e-12)
        assertEquals(sqrt(32.0 / 7.0), stats.getValue("stddev"), 1e-12)
    }

    @Test fun statisticsEdgeCases() {
        val single = UiMathEngine.statistics(listOf(5.0))
        assertEquals(5.0, single.getValue("median"), 0.0)
        assertEquals(0.0, single.getValue("variance"), 0.0)
        assertEquals(0.0, single.getValue("stddev"), 0.0)
        assertEquals(2.0, UiMathEngine.statistics(listOf(1.0, 2.0, 3.0)).getValue("median"), 0.0)
        assertEquals(512.0, UiMathEngine.statistics(List(512) { it.toDouble() }).getValue("count"), 0.0)
        rejectedStatistics { UiMathEngine.statistics(emptyList()) }
        rejectedStatistics { UiMathEngine.statistics(List(513) { 1.0 }) }
        rejectedStatistics { UiMathEngine.statistics(listOf(1.0, Double.NaN)) }
        rejectedStatistics { UiMathEngine.statistics(listOf(1.0, Double.NEGATIVE_INFINITY)) }
    }

    // ---------------------------------------------------------------- combinatorics

    @Test fun combinatoricsValues() {
        assertEquals(10.0, UiMathEngine.combinations(5, 2), 0.0)
        assertEquals(10.0, UiMathEngine.combinations(5, 3), 0.0)
        assertEquals(1.0, UiMathEngine.combinations(0, 0), 0.0)
        assertEquals(1.0, UiMathEngine.combinations(9, 0), 0.0)
        assertEquals(1.0, UiMathEngine.combinations(9, 9), 0.0)
        assertEquals(2598960.0, UiMathEngine.combinations(52, 5), 0.0)
        assertEquals(20.0, UiMathEngine.permutations(5, 2), 0.0)
        assertEquals(3628800.0, UiMathEngine.permutations(10, 10), 0.0)
        assertEquals(1.0, UiMathEngine.permutations(10, 0), 0.0)
    }

    @Test fun combinatoricsRejectsInvalidInputs() {
        rejectedCombinatorics { UiMathEngine.combinations(2, 3) }
        rejectedCombinatorics { UiMathEngine.combinations(-1, 0) }
        rejectedCombinatorics { UiMathEngine.combinations(5, -1) }
        rejectedCombinatorics { UiMathEngine.permutations(3, 4) }
        rejectedCombinatorics { UiMathEngine.combinations(1001, 1) }
        rejectedCombinatorics { UiMathEngine.combinations(Int.MAX_VALUE, 1) }
        rejectedCombinatorics { UiMathEngine.permutations(Int.MAX_VALUE, 1) }
        rejectedCombinatorics { UiMathEngine.permutations(1000, 200) } // overflow, early stop
    }

    @Test fun combinatoricsStaysInsideBounds() {
        assertTrue(UiMathEngine.combinations(1000, 500).isFinite())
        assertTrue(UiMathEngine.combinations(1000, 0) == 1.0)
        assertTrue(UiMathEngine.permutations(1000, 0) == 1.0)
        assertTrue(UiMathEngine.permutations(170, 170).isFinite())
    }

    @Test fun smallPositiveFunctionsAreNotRootsAndScalingPreservesSolutions() {
        assertTrue(UiMathEngine.roots("exp(x)", -40.0, -35.0).isEmpty())
        assertTrue(UiMathEngine.roots("1e-20*(x^2+1)", -2.0, 2.0).isEmpty())
        assertTrue(UiMathEngine.roots("1e-20/(x-0.123456)", -1.0, 1.0).isEmpty())
        val roots = UiMathEngine.roots("1e-20*(x-1)", 0.0, 3.0)
        assertEquals(1, roots.size)
        assertEquals(1.0, roots.single(), 1e-8)
    }

    // ---------------------------------------------------------------- helpers

    private fun multiply(a: List<List<Double>>, b: List<List<Double>>): List<List<Double>> {
        val n = a.size
        return List(n) { r -> List(n) { c -> (0 until n).sumOf { k -> a[r][k] * b[k][c] } } }
    }

    private fun rejectedDerivative(expression: String, x: Double) {
        try { UiMathEngine.derivative(expression, x); fail("accepted derivative of $expression at $x") } catch (_: IllegalArgumentException) { }
    }

    private fun rejectedIntegral(expression: String, a: Double, b: Double) {
        try { UiMathEngine.integrate(expression, a, b); fail("accepted integral of $expression on [$a,$b]") } catch (_: IllegalArgumentException) { }
    }

    private fun rejectedMatrix(block: () -> Unit) {
        try { block(); fail("accepted invalid matrix input") } catch (_: IllegalArgumentException) { }
    }

    private fun rejectedStatistics(block: () -> Unit) {
        try { block(); fail("accepted invalid statistics input") } catch (_: IllegalArgumentException) { }
    }

    private fun rejectedCombinatorics(block: () -> Unit) {
        try { block(); fail("accepted invalid combinatorics input") } catch (_: IllegalArgumentException) { }
    }
}
