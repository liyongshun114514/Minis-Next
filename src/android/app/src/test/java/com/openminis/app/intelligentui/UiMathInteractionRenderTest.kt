package com.openminis.app.intelligentui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs

/**
 * Real Compose touch input on the math plot: the same pixels that are painted must be the
 * ones that are hit-tested, dragging and tapping both move the probe, and recomposing with
 * new plot bounds must not keep the pointerInput coroutine's stale mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UiMathInteractionRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun plot(xMin: Double, xMax: Double): JSONObject = JSONObject()
        .put("id", "plot")
        .put("type", "math")
        .put("mode", "function_plot")
        .put("title", "Drag")
        .put("expression", "x")
        .put("xMin", xMin).put("xMax", xMax)
        .put("yMin", -20.0).put("yMax", 20.0)

    private fun show(n: JSONObject) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                    UiMathDisplay(n)
                }
            }
        }
    }

    // --------------------------------------------------------------------- helpers

    /** The same pixel geometry the draw pass and the gesture pass use, at the real density. */
    private fun plotRect(): PlotRectPx {
        val node = compose.onNodeWithTag("ui-math-plot").fetchSemanticsNode()
        return UiPlotGeometry.rectFor(
            node.size.width.toFloat(),
            node.size.height.toFloat(),
            node.layoutInfo.density.density,
        )
    }

    private fun offsetOf(x: Double, y: Double, xMin: Double, xMax: Double, yMin: Double, yMax: Double): Offset {
        val rect = plotRect()
        return Offset(
            UiPlotGeometry.screenX(x, xMin, xMax, rect),
            UiPlotGeometry.screenY(y, yMin, yMax, rect),
        )
    }

    private fun probeText(): String =
        compose.onNodeWithTag("ui-math-probe-value").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("") { it.text }

    /** Reads the number that follows [prefix] in a readout such as "t=1.5708 → (0, 1)". */
    private fun numberAfter(prefix: String): Double {
        val text = probeText()
        val start = text.indexOf(prefix)
        assertTrue("readout '$text' does not start with '$prefix'", start >= 0)
        val from = start + prefix.length
        val to = text.indexOf(' ', from).let { if (it < 0) text.length else it }
        return text.substring(from, to).toDouble()
    }

    private fun circleNode(mode: String, expression: String, expressionY: String? = null): JSONObject {
        val n = JSONObject()
            .put("id", "curve")
            .put("type", "math")
            .put("mode", mode)
            .put("title", "Circle")
            .put("expression", expression)
            .put("tMin", 0.0)
            .put("tMax", 2 * PI)
            .put("xMin", -1.5).put("xMax", 1.5)
            .put("yMin", -1.5).put("yMax", 1.5)
        if (expressionY != null) n.put("expressionY", expressionY)
        return n
    }

    @Test fun parameterCircleTappedAtItsTopPicksTheTopNotTheLeftEnd() {
        show(circleNode("parametric", "cos(t)", "sin(t)"))
        compose.onNodeWithTag("ui-math-plot").performScrollTo()
            .performTouchInput { click(offsetOf(0.0, 1.0, -1.5, 1.5, -1.5, 1.5)) }
        val t = numberAfter("t=")
        assertEquals("a tap on the top of the circle must resolve to t = π/2", PI / 2, t, 0.02)
        assertTrue("t must not be the left end of the loop", abs(t - PI) > 1.0)
        assertTrue("the probe must sit at the top, not on the left", probeText().contains("(0, 1)"))
    }

    @Test fun parameterCircleTappedAtItsRightPicksAnEndpointOfTheParameterRange() {
        show(circleNode("parametric", "cos(t)", "sin(t)"))
        compose.onNodeWithTag("ui-math-plot").performScrollTo()
            .performTouchInput { click(offsetOf(1.0, 0.0, -1.5, 1.5, -1.5, 1.5)) }
        val t = numberAfter("t=")
        assertTrue("t=$t is not the right-hand point", minOf(abs(t), abs(2 * PI - t)) < 0.02)
    }

    @Test fun polarCircleTappedAtItsTopPicksHalfPiInsteadOfTheLeftTheta() {
        show(circleNode("polar", "1"))
        compose.onNodeWithTag("ui-math-plot").performScrollTo()
            .performTouchInput { click(offsetOf(0.0, 1.0, -1.5, 1.5, -1.5, 1.5)) }
        val theta = numberAfter("θ=")
        assertEquals("a tap on the top of a polar circle must resolve to θ = π/2", PI / 2, theta, 0.02)
        assertTrue("θ must not be the left end (π)", abs(theta - PI) > 1.0)
        assertEquals(1.0, numberAfter("r="), 0.02)
    }

    @Test fun verticalDragInsideThePlotKeepsMovingTheProbeAndNeverScrollsTheParent() {
        val scroll = androidx.compose.foundation.ScrollState(0)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Spacer(Modifier.height(40.dp))
                    UiMathDisplay(plot(-10.0, 10.0))
                    Spacer(Modifier.height(2000.dp))
                }
            }
        }
        compose.onNodeWithTag("ui-math-plot").performScrollTo()
        assertTrue("the parent must really be scrollable for this regression", scroll.maxValue > 0)
        val before = scroll.value

        // Vertical-dominant swipe: exactly the gesture a parent scroller used to steal.
        compose.onNodeWithTag("ui-math-plot").performTouchInput {
            swipe(
                start = center,
                end = Offset(center.x + width * 0.18f, center.y - height * 0.35f),
                durationMillis = 300L,
            )
        }
        val firstDraw = probeText()
        assertNotEquals("the drag must move the probe", "f(0) = 0", firstDraw)
        assertEquals("the parent list must not scroll while dragging the point", before, scroll.value)

        // A second drag on the same node still reaches the probe: the gesture was not
        // cancelled by the enclosing scroll container.
        compose.onNodeWithTag("ui-math-plot").performTouchInput {
            swipe(
                start = Offset(center.x - width * 0.2f, center.y),
                end = Offset(center.x + width * 0.3f, center.y - height * 0.2f),
                durationMillis = 300L,
            )
        }
        assertNotEquals("the second drag must move the probe again", firstDraw, probeText())
        assertEquals("the parent list must still not scroll", before, scroll.value)
    }

    @Test fun aTapInTheMiddleMapsToTheMiddleOfTheDeclaredBounds() {
        show(plot(-10.0, 10.0))
        val rect = plotRect()
        compose.onNodeWithTag("ui-math-plot").performScrollTo().performTouchInput { click(Offset(rect.centerX, rect.centerY)) }
        compose.onNodeWithTag("ui-math-probe-value").assertTextEquals("f(0) = 0")
    }

    @Test fun tappingInsideTheAxisGutterClampsToTheDeclaredBoundary() {
        show(plot(0.0, 100.0))
        compose.onNodeWithTag("ui-math-plot").performScrollTo().performTouchInput { click(androidx.compose.ui.geometry.Offset(1f, center.y)) }
        compose.onNodeWithTag("ui-math-probe-value").assertTextEquals("f(0) = 0")
        compose.onNodeWithTag("ui-math-plot").performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 1f, center.y)) }
        compose.onNodeWithTag("ui-math-probe-value").assertTextEquals("f(100) = 100")
    }

    @Test fun draggingMovesTheProbeContinuouslyInsteadOfOnlyOnRelease() {
        show(plot(-10.0, 10.0))
        compose.onNodeWithTag("ui-math-plot").performScrollTo()
        // Down lands on the midpoint (f(0)); only the move events can reach/pass the right
        // edge, where the probe clamps to the declared xMax instead of leaving the domain.
        compose.onNodeWithTag("ui-math-plot").performTouchInput {
            swipe(
                start = center,
                end = androidx.compose.ui.geometry.Offset(width * 2f, center.y),
                durationMillis = 240L,
            )
        }
        compose.onNodeWithTag("ui-math-probe-value").assertTextEquals("f(10) = 10")
    }

    @Test fun newBoundsAreHitTestedAfterRecompositionWithoutRestartingInput() {
        var bounds by mutableStateOf(-10.0 to 10.0)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                    UiMathDisplay(plot(bounds.first, bounds.second))
                }
            }
        }
        val rect = plotRect()
        compose.onNodeWithTag("ui-math-plot").performScrollTo().performTouchInput { click(Offset(rect.centerX, rect.centerY)) }
        compose.onNodeWithTag("ui-math-probe-value").assertTextEquals("f(0) = 0")
        compose.runOnIdle { bounds = 0.0 to 20.0 }
        compose.onNodeWithTag("ui-math-plot").performTouchInput { click(Offset(rect.centerX, rect.centerY)) }
        compose.onNodeWithTag("ui-math-probe-value").assertTextEquals("f(10) = 10")
    }

    @Test fun mathSliderAcceptsNonIntegerProgressWithoutSnapping() {
        val n = plot(-2.0, 2.0).put(
            "sliders",
            org.json.JSONArray().put(
                JSONObject().put("name", "a").put("label", "a")
                    .put("min", 1.0).put("max", 3.0).put("value", 1.0),
            ),
        )
        show(n)
        compose.onNodeWithTag("ui-math-slider-a").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2.37f) }
        compose.onNodeWithTag("ui-math-slider-value-a").assertTextEquals("a = 2.37")
    }

    @Test fun formSliderWithAnIntegerStepRemainsContinuousAfterRelease() {
        formSliderKeepsFraction(1.0)
    }

    @Test fun formSliderKeepsARealFractionInsteadOfItsDeclaredDetents() {
        formSliderKeepsFraction(0.5)
    }

    private fun formSliderKeepsFraction(step: Double) {
        val n = JSONObject(
            """{"id":"c","type":"calculator","fields":[{"id":"q","label":"Q","kind":"slider","value":2,"min":1,"max":10,"step":$step}],"expression":"q"}""",
        )
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                    UiAdvancedFields("Calc", n, true, {})
                }
            }
        }
        compose.onNodeWithTag("field_q").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2.37f) }
        compose.onNodeWithTag("ui_result").assertTextContains("2.37", substring = true)
    }
}
