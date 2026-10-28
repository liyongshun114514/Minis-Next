package com.openminis.app.intelligentui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real Compose rendering on Robolectric. These tests drive actual slider
 * semantics, taps, and the reset button; they do not call private helpers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UiMathRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun node(mode: String): JSONObject = JSONObject()
        .put("id", "test_$mode")
        .put("type", "math")
        .put("mode", mode)
        .put("title", "Test $mode")
        .put("text", "示例")

    private fun slider(name: String, min: Double, max: Double, value: Double) = JSONObject()
        .put("name", name).put("label", name).put("min", min).put("max", max).put("value", value)

    private fun show(n: JSONObject) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                    UiMathDisplay(n)
                }
            }
        }
    }

    private fun drag(tag: String, value: Float) {
        compose.onNodeWithTag(tag).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }

    @Test fun functionPlotSliderRecalculatesProbeAndResetRestoresIt() {
        val n = node("function_plot")
            .put("expression", "a*x^2")
            .put("xMin", 0.0).put("xMax", 2.0)
            .put("yMin", 0.0).put("yMax", 12.0)
            .put("sliders", JSONArray(listOf(slider("a", 1.0, 3.0, 1.0))))
        show(n)
        compose.onNodeWithText("y = a*x^2").assertIsDisplayed()
        compose.onNodeWithTag("ui-math-probe-value").assertTextEquals("f(1) = 1")
        compose.onNodeWithTag("ui-math-slider-value-a").assertTextEquals("a = 1")
        drag("ui-math-slider-a", 3f)
        compose.onNodeWithTag("ui-math-slider-value-a").assertTextEquals("a = 3")
        compose.onNodeWithTag("ui-math-probe-value").assertTextEquals("f(1) = 3")
        compose.onNodeWithTag("ui-math-reset").performScrollTo().performClick()
        compose.onNodeWithTag("ui-math-probe-value").assertTextEquals("f(1) = 1")
        compose.onNodeWithTag("ui-math-slider-value-a").assertTextEquals("a = 1")
    }

    @Test fun functionPlotShowsExplicitDomainErrorInsteadOfFakeCurve() {
        val n = node("function_plot")
            .put("expression", "ln(x)")
            .put("xMin", -2.0).put("xMax", -1.0)
            .put("yMin", -1.0).put("yMax", 1.0)
        show(n)
        compose.onNodeWithTag("ui-math-error").assertExists()
        compose.onNodeWithTag("ui-math-plot").assertDoesNotExist()
    }

    @Test fun invalidSchemaRendersLocalError() {
        show(node("formula").put("expression", "x").put("bogus", 1))
        compose.onNodeWithTag("ui-math-error").assertTextContains("Unknown fields", substring = true)
    }

    @Test fun calculusDerivativeIntegralAndTangentUseTheSharedEngine() {
        val n = node("calculus")
            .put("expression", "x^2")
            .put("a", 0.0).put("b", 2.0).put("x0", 1.0)
        show(n)
        compose.onNodeWithTag("ui-math-derivative").assertTextEquals("2")
        compose.onNodeWithTag("ui-math-integral").assertTextEquals("2.6667")
        compose.onNodeWithTag("ui-math-tangent").assertTextContains("y = 2(x − 1) + 1", substring = true)
    }

    @Test fun equationFindsBoundedRoots() {
        val n = node("equation")
            .put("expression", "x^2-4=0")
            .put("a", -3.0).put("b", 3.0)
        show(n)
        compose.onNodeWithTag("ui-math-roots").assertTextContains("x1 ≈ -2", substring = true)
        compose.onNodeWithTag("ui-math-roots").assertTextContains("x2 ≈ 2", substring = true)
    }

    @Test fun matrixComputesDeterminantInverseAndSolution() {
        val n = node("matrix")
            .put("matrix", JSONArray(listOf(
                JSONArray(listOf(4.0, 7.0)),
                JSONArray(listOf(2.0, 6.0)),
            )))
            .put("rhs", JSONArray(listOf(10.0, 8.0)))
        show(n)
        compose.onNodeWithTag("ui-math-det").assertTextEquals("10")
        compose.onNodeWithTag("ui-math-inverse").assertExists()
        compose.onNodeWithTag("ui-math-solve").assertTextContains("x1=0.4", substring = true)
        compose.onNodeWithTag("ui-math-solve").assertTextContains("x2=1.2", substring = true)
    }

    @Test fun statisticsUsesSampleVarianceLabels() {
        val n = node("statistics").put("values", JSONArray(listOf(10.0, 12.0, 14.0, 16.0, 18.0)))
        show(n)
        compose.onNodeWithTag("ui-math-count").assertTextEquals("5")
        compose.onNodeWithTag("ui-math-mean").assertTextEquals("14")
        compose.onNodeWithTag("ui-math-variance").assertTextEquals("10")
        compose.onNodeWithTag("ui-math-stddev").assertTextEquals("3.1623")
        compose.onNodeWithText("样本方差 variance (n−1)").assertExists()
    }

    @Test fun probabilityCombinesAndBinomialPmf() {
        show(node("probability").put("n", 10).put("k", 3).put("p", 0.5))
        compose.onNodeWithTag("ui-math-combinations").assertTextEquals("120")
        compose.onNodeWithTag("ui-math-permutations").assertTextEquals("720")
        compose.onNodeWithTag("ui-math-binomial").assertTextEquals("0.1172")
    }

    @Test fun geometryRecalculatesAndRejectsSliderDegenerateSides() {
        val n = node("geometry").put("shape", "triangle")
            .put("a", 3.0).put("b", 4.0).put("c", 5.0)
            .put("sliders", JSONArray(listOf(
                slider("a", -4.0, 8.0, 3.0),
                slider("b", 1.0, 8.0, 4.0),
                slider("c", 1.0, 8.0, 5.0),
            )))
        show(n)
        compose.onNodeWithTag("ui-math-geometry-area").assertTextEquals("6")
        compose.onNodeWithTag("ui-math-geometry-perimeter").assertTextEquals("12")
        drag("ui-math-slider-a", -1f)
        compose.onNodeWithTag("ui-math-error").assertTextContains("正数", substring = true)
    }

    @Test fun fibonacciSequenceShowsTermsAndPartialSum() {
        val n = node("sequence").put("kind", "fibonacci")
            .put("first", 1.0).put("second", 1.0).put("terms", 6)
        show(n)
        compose.onNodeWithText("a1=1").assertExists()
        compose.onNodeWithText("a6=8").assertExists()
        compose.onNodeWithTag("ui-math-sequence-sum").assertTextEquals("20")
    }

    @Test fun formulaSubsetRendersFractionsRootsSumsMatricesAndSteps() {
        val steps = JSONArray(listOf(
            JSONObject().put("formula", "\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}").put("note", "求和"),
            JSONObject().put("formula", "\\int_{0}^{1} x^{2}\\,dx = \\frac{1}{3}").put("note", "积分"),
            JSONObject().put("formula", "\\begin{matrix} a & b \\\\ c & d \\end{matrix}").put("note", "矩阵"),
        ))
        val n = node("formula")
            .put("expression", "x = \\frac{-b \\pm \\sqrt{b^{2}-4ac}}{2a}")
            .put("result", "\\Delta = b^{2}-4ac")
            .put("steps", steps)
        show(n)
        compose.onNodeWithTag("ui-math-formula").assertExists()
        compose.onNodeWithTag("ui-math-formula-result").assertExists()
        compose.onNodeWithTag("ui-math-step-0").assertExists()
        compose.onNodeWithTag("ui-math-step-1").assertExists()
        compose.onNodeWithTag("ui-math-step-2").assertExists()
        val errors = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("ui-math-formula-error"))
        errors.assertCountEquals(0)
    }

    @Test fun parametricModeRendersCurveAndProbe() {
        show(node("parametric")
            .put("expression", "cos(t)").put("expressionY", "sin(t)")
            .put("tMin", 0.0).put("tMax", 6.2832)
            .put("xMin", -1.5).put("xMax", 1.5).put("yMin", -1.5).put("yMax", 1.5))
        compose.onNodeWithTag("ui-math-plot").assertExists()
        compose.onNodeWithTag("ui-math-probe-value").assertTextContains("t=3.1416", substring = true)
    }

    @Test fun polarModeRendersCurveAndProbe() {
        show(node("polar")
            .put("expression", "1+0.5*cos(t)")
            .put("tMin", 0.0).put("tMax", 6.2832)
            .put("xMin", -2.0).put("xMax", 2.0).put("yMin", -2.0).put("yMax", 2.0))
        compose.onNodeWithTag("ui-math-probe-value").assertTextContains("r=0.5", substring = true)
    }

    @Test fun implicitModeRendersContourAndProbe() {
        show(node("implicit")
            .put("expression", "x^2+y^2-1").put("comparator", "=")
            .put("xMin", -2.0).put("xMax", 2.0).put("yMin", -2.0).put("yMax", 2.0))
        compose.onNodeWithTag("ui-math-plot").assertExists()
        compose.onNodeWithTag("ui-math-probe-value").assertTextEquals("f(0, 0) = -1")
    }

    @Test fun surfaceModeRendersWireframeAndViewAngles() {
        show(node("surface3d")
            .put("expression", "sin(x)*cos(y)")
            .put("xMin", -3.14).put("xMax", 3.14)
            .put("yMin", -3.14).put("yMax", 3.14)
            .put("zMin", -1.5).put("zMax", 1.5))
        compose.onNodeWithTag("ui-math-surface").assertExists()
        compose.onNodeWithTag("ui-math-view").assertTextContains("方位", substring = true)
    }
}
