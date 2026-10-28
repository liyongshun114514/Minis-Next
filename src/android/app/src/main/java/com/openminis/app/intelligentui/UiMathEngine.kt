package com.openminis.app.intelligentui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Bounded numeric helpers for the native math panel.
 *
 * Everything here is pure Kotlin arithmetic on top of [UiExpression]: no eval, no reflection,
 * no shell, no network. Calculus results ([derivative], [integrate], [roots]) are **approximations**
 * produced by finite differences, adaptive Simpson quadrature and bracketed bisection; they are not
 * symbolic answers.
 *
 * Hard limits: expression length 1..512 (delegated to [UiExpression], which also bounds nesting
 * depth and operation count), matrices at most 6x6, at most 512 statistics values, at most 512
 * integration leaves and 4096 integration samples, at most 256 root scan intervals, and
 * combinatorial arguments at most 1000. Functions must be finite at every sampled
 * point; domain errors, division by zero and poles raise [IllegalArgumentException] instead of
 * silently returning NaN or Infinity.
 */
object UiMathEngine {
    const val MAX_MATRIX_SIZE = 6
    const val MAX_STATISTICS_VALUES = 512
    const val MAX_INTEGRATION_INTERVALS = 512
    const val MAX_ROOT_INTERVALS = 256

    private const val MAX_EXPRESSION_LENGTH = 512
    private const val MAX_INTEGRATION_SAMPLES = 4096
    private const val MAX_COMBINATORICS_N = 1000
    private const val MAX_BISECTION_STEPS = 200
    private const val PIVOT_EPSILON = 1e-12

    // ---------------------------------------------------------------- calculus

    /**
     * Approximate f'(x) with a five point central difference.
     *
     * Both one sided derivative estimates are computed first (Richardson extrapolated). They must
     * exist and agree, otherwise the function is not differentiable at x (cusp such as abs(x) at 0)
     * or the point sits on a domain edge / pole, and this throws. The central difference is only
     * returned after the left and right slopes agree, so a symmetric cusp can never be reported as
     * a zero derivative.
     */
    fun derivative(expression: String, x: Double, variables: Map<String, Double> = emptyMap()): Double {
        require(x.isFinite()) { "x must be finite" }
        val f = evaluator(expression, variables)
        // The function must be defined at the point itself; this rejects poles such as tan(pi/2)
        // and domain edges such as ln(0) before any sampling happens.
        require(f(x).isFinite()) { "Function is not finite at x=$x" }
        val h = 1e-6 * max(1.0, abs(x))

        fun fivePoint(): Double {
            val fPlus1 = f(x + h)
            val fMinus1 = f(x - h)
            val fPlus2 = f(x + 2 * h)
            val fMinus2 = f(x - 2 * h)
            return (fMinus2 - 8 * fMinus1 + 8 * fPlus1 - fPlus2) / (12 * h)
        }

        /** One sided difference quotient; the step carries the sign, so the divisor must too. */
        fun oneSided(direction: Double): Double {
            var step = h
            var previous = (f(x + direction * step) - f(x)) / (direction * step)
            var refined = previous
            repeat(3) {
                step /= 2
                val current = (f(x + direction * step) - f(x)) / (direction * step)
                refined = 2 * current - previous
                previous = current
            }
            return refined
        }

        val forward = runCatching { oneSided(1.0) }.getOrNull()?.takeIf { it.isFinite() }
        val backward = runCatching { oneSided(-1.0) }.getOrNull()?.takeIf { it.isFinite() }
        require(forward != null && backward != null) {
            "Derivative is outside the function domain at x=$x"
        }
        val right = forward!!
        val left = backward!!
        require(abs(right - left) <= 1e-3 * max(1.0, max(abs(right), abs(left)))) {
            "Derivative is not defined at x=$x (left $left vs right $right)"
        }
        val central = runCatching { fivePoint() }.getOrNull()
        if (central != null && central.isFinite()) return central
        return (right + left) / 2
    }

    /**
     * Approximate the definite integral with adaptive Simpson quadrature (absolute tolerance
     * 1e-9 * max(1, |I|)).
     *
     * Budgets are hard: at most [MAX_INTEGRATION_INTERVALS] accepted leaves and
     * [MAX_INTEGRATION_SAMPLES] function samples. If the tolerance cannot be reached inside those
     * budgets the call throws instead of returning a silently truncated value. The tolerance test
     * is only trusted after at least 8 base panels, because very coarse Simpson estimates can agree
     * by aliasing on oscillatory integrands while both are wrong.
     */
    fun integrate(expression: String, a: Double, b: Double, variables: Map<String, Double> = emptyMap()): Double {
        require(a.isFinite() && b.isFinite()) { "Integration bounds must be finite" }
        if (a == b) return 0.0
        val f = evaluator(expression, variables)
        val lo = min(a, b)
        val hi = max(a, b)
        val sign = if (b < a) -1.0 else 1.0

        var leaves = 0
        var evaluations = 0

        fun sample(x: Double): Double {
            require(evaluations < MAX_INTEGRATION_SAMPLES) {
                "Integral exceeded $MAX_INTEGRATION_SAMPLES function samples"
            }
            evaluations++
            val value = f(x)
            require(value.isFinite()) { "Function is not finite at x=$x" }
            return value
        }

        val fa = sample(lo)
        val fb = sample(hi)
        val mid = 0.5 * (lo + hi)
        val fm = sample(mid)
        val whole = (hi - lo) / 6 * (fa + 4 * fm + fb)
        val epsilon = 1e-9 * max(1.0, abs(whole))

        fun adaptive(left: Double, right: Double, fLeft: Double, fMid: Double, fRight: Double,
                     estimate: Double, tolerance: Double, depth: Int): Double {
            if (leaves >= MAX_INTEGRATION_INTERVALS) {
                throw IllegalArgumentException(
                    "Integral did not converge within $MAX_INTEGRATION_INTERVALS subintervals"
                )
            }
            val middle = 0.5 * (left + right)
            val lMid = 0.5 * (left + middle)
            val rMid = 0.5 * (middle + right)
            val fLMid = sample(lMid)
            val fRMid = sample(rMid)
            val leftEstimate = (middle - left) / 6 * (fLeft + 4 * fLMid + fMid)
            val rightEstimate = (right - middle) / 6 * (fMid + 4 * fRMid + fRight)
            val delta = leftEstimate + rightEstimate - estimate
            // Never accept the very coarse top panels: on oscillatory integrands two adjacent
            // Simpson estimates can agree by aliasing while both are wrong, so require at least
            // 8 base panels before the tolerance test is trusted.
            if (depth >= 20 || (depth >= 3 && abs(delta) <= 15 * tolerance)) {
                leaves++
                return leftEstimate + rightEstimate + delta / 15
            }
            return adaptive(left, middle, fLeft, fLMid, fMid, leftEstimate, tolerance / 2, depth + 1) +
                adaptive(middle, right, fMid, fRMid, fRight, rightEstimate, tolerance / 2, depth + 1)
        }

        val value = adaptive(lo, hi, fa, fm, fb, whole, epsilon, 0)
        require(value.isFinite()) { "Integral is not finite" }
        return sign * value
    }

    /**
     * Approximate the real roots of f on [a, b] by scanning 256 subintervals and bisecting every
     * sign change. Roots without a sign change (even multiplicity) may be missed. Sign changes
     * produced by poles are rejected because the residual |f| stays large at the candidate.
     */
    fun roots(expression: String, a: Double, b: Double, variables: Map<String, Double> = emptyMap()): List<Double> {
        require(a.isFinite() && b.isFinite() && a < b) { "Root interval must satisfy a < b" }
        val f = evaluator(expression, variables)

        fun safe(x: Double): Double = runCatching { f(x) }.getOrNull()?.takeIf { it.isFinite() } ?: Double.NaN

        val n = MAX_ROOT_INTERVALS
        val step = (b - a) / n
        val xs = DoubleArray(n + 1) { a + it * step }
        xs[n] = b
        val fs = DoubleArray(n + 1) { safe(xs[it]) }
        val found = ArrayList<Double>()

        fun addRoot(value: Double) {
            if (!value.isFinite()) return
            val near = found.any { abs(it - value) <= 1e-9 * max(1.0, abs(value)) }
            if (!near) found.add(value)
        }

        fun refine(left: Double, right: Double, fLeft: Double, fRight: Double): Double? {
            var l = left
            var r = right
            var fl = fLeft
            repeat(MAX_BISECTION_STEPS) {
                val mid = 0.5 * (l + r)
                val fm = safe(mid)
                if (!fm.isFinite()) return null
                if (fm == 0.0) return mid
                if (fl.sign * fm.sign < 0) { r = mid } else { l = mid; fl = fm }
                if (r - l <= 1e-12 * max(1.0, abs(mid))) return 0.5 * (l + r)
            }
            return 0.5 * (l + r)
        }

        for (index in 0..n) {
            val value = fs[index]
            // An absolute epsilon fabricates roots for tiny positive functions.
            // Exact sampled zeros or a verified sign-change bracket are required.
            if (value == 0.0) {
                addRoot(xs[index])
                continue
            }
            if (index == n) break
            val nextValue = fs[index + 1]
            if (!value.isFinite() || !nextValue.isFinite()) continue
            if (value.sign * nextValue.sign >= 0) continue
            val candidate = refine(xs[index], xs[index + 1], value, nextValue) ?: continue
            val residual = safe(candidate)
            if (!residual.isFinite()) continue
            val tolerance = 1e-6 * max(abs(value), abs(nextValue))
            if (abs(residual) > tolerance) continue // pole or jump, not a root
            addRoot(candidate)
        }
        found.sort()
        return found
    }

    // ---------------------------------------------------------------- linear algebra

    fun determinant(matrix: List<List<Double>>): Double {
        val a = prepareSquare(matrix)
        val n = a.size
        val scale = a.maxOf { row -> row.maxOf { abs(it) } }
        var det = 1.0
        for (column in 0 until n) {
            var pivot = column
            for (row in column + 1 until n) if (abs(a[row][column]) > abs(a[pivot][column])) pivot = row
            if (abs(a[pivot][column]) <= PIVOT_EPSILON * max(1.0, scale)) return 0.0
            if (pivot != column) {
                val swap = a[pivot]; a[pivot] = a[column]; a[column] = swap
                det = -det
            }
            det *= a[column][column]
            for (row in column + 1 until n) {
                val factor = a[row][column] / a[column][column]
                if (factor == 0.0) continue
                for (k in column until n) a[row][k] -= factor * a[column][k]
            }
        }
        require(det.isFinite()) { "Determinant is not finite" }
        return det
    }

    fun inverse(matrix: List<List<Double>>): List<List<Double>> {
        val a = prepareSquare(matrix)
        val n = a.size
        val scale = a.maxOf { row -> row.maxOf { abs(it) } }
        val aug = Array(n) { row -> DoubleArray(2 * n) { col ->
            when {
                col < n -> a[row][col]
                col - n == row -> 1.0
                else -> 0.0
            }
        } }
        for (column in 0 until n) {
            var pivot = column
            for (row in column + 1 until n) if (abs(aug[row][column]) > abs(aug[pivot][column])) pivot = row
            require(abs(aug[pivot][column]) > PIVOT_EPSILON * max(1.0, scale)) { "Matrix is singular" }
            if (pivot != column) { val swap = aug[pivot]; aug[pivot] = aug[column]; aug[column] = swap }
            val divisor = aug[column][column]
            for (k in 0 until 2 * n) aug[column][k] /= divisor
            for (row in 0 until n) {
                if (row == column) continue
                val factor = aug[row][column]
                if (factor == 0.0) continue
                for (k in 0 until 2 * n) aug[row][k] -= factor * aug[column][k]
            }
        }
        val result = List(n) { row -> List(n) { col -> aug[row][n + col] } }
        require(result.all { row -> row.all { it.isFinite() } }) { "Inverse is not finite" }
        return result
    }

    fun solve(matrix: List<List<Double>>, rhs: List<Double>): List<Double> {
        val a = prepareSquare(matrix)
        val n = a.size
        require(rhs.size == n) { "Right hand side must have $n entries" }
        require(rhs.all { it.isFinite() }) { "Right hand side must be finite" }
        // Pivot guard uses the matrix scale only: a large right hand side must not make a
        // perfectly conditioned matrix look singular.
        val scale = a.maxOf { row -> row.maxOf { abs(it) } }
        val aug = Array(n) { row -> DoubleArray(n + 1) { col -> if (col < n) a[row][col] else rhs[row] } }
        for (column in 0 until n) {
            var pivot = column
            for (row in column + 1 until n) if (abs(aug[row][column]) > abs(aug[pivot][column])) pivot = row
            require(abs(aug[pivot][column]) > PIVOT_EPSILON * max(1.0, scale)) { "Matrix is singular" }
            if (pivot != column) { val swap = aug[pivot]; aug[pivot] = aug[column]; aug[column] = swap }
            for (row in column + 1 until n) {
                val factor = aug[row][column] / aug[column][column]
                if (factor == 0.0) continue
                for (k in column..n) aug[row][k] -= factor * aug[column][k]
            }
        }
        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var sum = aug[row][n]
            for (k in row + 1 until n) sum -= aug[row][k] * x[k]
            x[row] = sum / aug[row][row]
        }
        val result = x.toList()
        require(result.all { it.isFinite() }) { "Solution is not finite" }
        return result
    }

    private fun prepareSquare(matrix: List<List<Double>>): Array<DoubleArray> {
        val n = matrix.size
        require(n in 1..MAX_MATRIX_SIZE) { "Matrix must be 1..$MAX_MATRIX_SIZE rows" }
        require(matrix.all { it.size == n }) { "Matrix must be square" }
        require(matrix.all { row -> row.all { it.isFinite() } }) { "Matrix entries must be finite" }
        return Array(n) { row -> matrix[row].toDoubleArray() }
    }

    // ---------------------------------------------------------------- statistics

    /**
     * Summary statistics. `variance` and `stddev` are the sample (n-1) versions and are 0.0 for a
     * single value. Keys are exactly: count, mean, median, min, max, variance, stddev.
     */
    fun statistics(values: List<Double>): Map<String, Double> {
        require(values.size in 1..MAX_STATISTICS_VALUES) { "Statistics need 1..$MAX_STATISTICS_VALUES values" }
        require(values.all { it.isFinite() }) { "Statistics values must be finite" }
        val n = values.size
        val sorted = values.sorted()
        val mean = values.sum() / n
        require(mean.isFinite()) { "Mean is not finite" }
        val median = if (n % 2 == 1) sorted[n / 2] else 0.5 * (sorted[n / 2 - 1] + sorted[n / 2])
        var sumSquares = 0.0
        for (value in values) {
            val delta = value - mean
            sumSquares += delta * delta
        }
        val variance = if (n < 2) 0.0 else sumSquares / (n - 1)
        require(variance.isFinite()) { "Variance is not finite" }
        return linkedMapOf(
            "count" to n.toDouble(),
            "mean" to mean,
            "median" to median,
            "min" to sorted.first(),
            "max" to sorted.last(),
            "variance" to variance,
            "stddev" to sqrt(max(0.0, variance))
        )
    }

    // ---------------------------------------------------------------- combinatorics

    fun combinations(n: Int, k: Int): Double {
        require(n in 0..MAX_COMBINATORICS_N && k in 0..n) {
            "combinations requires 0 <= k <= n <= $MAX_COMBINATORICS_N"
        }
        val limit = min(k, n - k)
        var result = 1.0
        for (i in 1..limit) {
            result = result * (n - limit + i) / i
            require(result.isFinite()) { "combinations result is not finite" }
        }
        return roundToInteger(result)
    }

    fun permutations(n: Int, k: Int): Double {
        require(n in 0..MAX_COMBINATORICS_N && k in 0..n) {
            "permutations requires 0 <= k <= n <= $MAX_COMBINATORICS_N"
        }
        var result = 1.0
        for (i in 0 until k) {
            result *= (n - i)
            require(result.isFinite()) { "permutations result is not finite" }
        }
        return roundToInteger(result)
    }

    /** Keeps combinatorial results integral despite floating point accumulation. */
    private fun roundToInteger(value: Double): Double =
        if (abs(value) < 9.007199254740992E15) round(value) else value

    // ---------------------------------------------------------------- plumbing

    /** Builds a single-argument evaluator with `x` bound to the sample point. */
    private fun evaluator(expression: String, variables: Map<String, Double>): (Double) -> Double {
        require(expression.length in 1..MAX_EXPRESSION_LENGTH) {
            "Expression must be 1..$MAX_EXPRESSION_LENGTH characters"
        }
        variables.forEach { (name, value) -> require(value.isFinite()) { "Variable $name is not finite" } }
        val scope = HashMap<String, Double>(variables)
        scope["x"] = 0.0
        return { x ->
            scope["x"] = x
            UiExpression.evaluate(expression, scope)
        }
    }
}
