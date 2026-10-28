package com.openminis.app.intelligentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure JVM tests for the pixel mapping shared by the math plot's draw pass and its
 * touch pass. No Compose runtime involved: failures here are real coordinate bugs.
 */
class UiPlotGeometryTest {

    private val insetsAt2x = PlotInsetsPx.forDensity(2f)
    private val rect = UiPlotGeometry.rectFor(400f, 300f, insetsAt2x)

    @Test fun insetsScaleWithDensity() {
        val at1x = PlotInsetsPx.forDensity(1f)
        assertEquals(38f, at1x.left, 0f)
        assertEquals(12f, at1x.top, 0f)
        assertEquals(10f, at1x.right, 0f)
        assertEquals(24f, at1x.bottom, 0f)
        assertEquals(76f, insetsAt2x.left, 0f)
        assertEquals(24f, insetsAt2x.top, 0f)
        assertEquals(20f, insetsAt2x.right, 0f)
        assertEquals(48f, insetsAt2x.bottom, 0f)
    }

    @Test fun drawingRectExcludesAxisGuttersExactlyOnce() {
        assertEquals(76f, rect.left, 0f)
        assertEquals(24f, rect.top, 0f)
        assertEquals(380f, rect.right, 0f)
        assertEquals(252f, rect.bottom, 0f)
        assertEquals(304f, rect.width, 0f)
        assertEquals(228f, rect.height, 0f)
        assertEquals(228f, rect.centerX, 0f)
        assertEquals(138f, rect.centerY, 0f)
    }

    @Test fun aPixelMapsToTheDataValueThatTheDrawPassPaintsThere() {
        val xMin = -10.0
        val xMax = 10.0
        val yMin = -5.0
        val yMax = 15.0
        // Screen position of the data point (2.5, 7.5) as painted by the curve path.
        val sx = UiPlotGeometry.screenX(2.5, xMin, xMax, rect)
        val sy = UiPlotGeometry.screenY(7.5, yMin, yMax, rect)
        val (fx, fy) = UiPlotGeometry.fractionAt(sx, sy, rect)
        assertEquals(2.5, UiPlotGeometry.dataX(fx, xMin, xMax), 1e-5)
        assertEquals(7.5, UiPlotGeometry.dataY(fy, yMin, yMax), 1e-5)
        // And the other direction stays stable too.
        assertEquals(sx, UiPlotGeometry.screenX(UiPlotGeometry.dataX(fx, xMin, xMax), xMin, xMax, rect), 1e-3f)
        assertEquals(sy, UiPlotGeometry.screenY(UiPlotGeometry.dataY(fy, yMin, yMax), yMin, yMax, rect), 1e-3f)
    }

    @Test fun tapsInTheGutterClampToThePlotEdgeInsteadOfRunningPastTheDomain() {
        val leftGutter = UiPlotGeometry.fractionAt(3f, 150f, rect)
        assertEquals(0f, leftGutter.first, 0f)
        val rightGutter = UiPlotGeometry.fractionAt(399f, 150f, rect)
        assertEquals(1f, rightGutter.first, 0f)
        val abovePlot = UiPlotGeometry.fractionAt(200f, 2f, rect)
        assertEquals(0f, abovePlot.second, 0f)
        val belowPlot = UiPlotGeometry.fractionAt(200f, 299f, rect)
        assertEquals(1f, belowPlot.second, 0f)
        assertEquals(-10.0, UiPlotGeometry.dataX(leftGutter.first, -10.0, 10.0), 0.0)
        assertEquals(10.0, UiPlotGeometry.dataX(rightGutter.first, -10.0, 10.0), 0.0)
        // Screen y grows down: below the plot is the minimum, not the maximum.
        assertEquals(-5.0, UiPlotGeometry.dataY(belowPlot.second, -5.0, 15.0), 1e-9)
        assertEquals(15.0, UiPlotGeometry.dataY(abovePlot.second, -5.0, 15.0), 1e-9)
    }

    @Test fun degenerateLayoutsNeverDivideByZero() {
        val tiny = UiPlotGeometry.rectFor(40f, 20f, PlotInsetsPx.forDensity(1f))
        assertTrue(tiny.isEmpty)
        assertEquals(0.5f to 0.5f, UiPlotGeometry.fractionAt(0f, 0f, tiny))
        val (fx, _) = UiPlotGeometry.fractionAt(20f, 10f, tiny)
        assertEquals(0.5f, fx, 0f)
        val zeroSpan = UiPlotGeometry.screenX(5.0, 2.0, 2.0, rect)
        assertEquals(rect.centerX, zeroSpan, 0f)
    }

    @Test fun sliderFractionsAreContinuousAndNeverSnap() {
        assertEquals(2.37, UiPlotGeometry.sliderValue(0.685f, 1.0, 3.0), 1e-6)
        assertEquals(1.0, UiPlotGeometry.sliderValue(-1f, 1.0, 3.0), 0.0)
        assertEquals(3.0, UiPlotGeometry.sliderValue(2f, 1.0, 3.0), 0.0)
        assertEquals(2.37, UiPlotGeometry.sliderValue(0.237f, 0.0, 10.0), 1e-6)
        assertEquals(7.4, UiPlotGeometry.sliderValue(0.74f, 0.0, 10.0), 1e-6)
    }

    // ------------------------------------------------ parametric / polar hit test
    //
    // The regression: deriving t (or θ) from the horizontal tap position puts the probe at
    // a completely different point of the curve — the left end of a circle when the user
    // tapped its top. These are pure pixel/data checks, no Compose runtime involved.

    private val unitRect = rect
    private val lo = -1.5
    private val hi = 1.5

    /** Unit circle x = cos t, y = sin t, sampled exactly like the plot's parameter range. */
    private fun circle(samples: Int = 320): List<List<ParamSample>> =
        listOf(
            (0..samples).map { i ->
                val t = 2 * PI * i / samples
                ParamSample(t, cos(t), sin(t))
            },
        )

    private fun hit(segments: List<List<ParamSample>>, x: Double, y: Double): Double? =
        UiPlotParamHit.nearestT(
            segments,
            UiPlotGeometry.screenX(x, lo, hi, unitRect),
            UiPlotGeometry.screenY(y, lo, hi, unitRect),
            lo, hi, lo, hi,
            unitRect,
        )

    @Test fun tappingTheTopOfANonMonotonicCircleReturnsTheTopsOwnT() {
        assertEquals(PI / 2, hit(circle(), 0.0, 1.0)!!, 0.01)
        // A column-based mapping reports t = π (the left end) for this same tap.
        val columnMapped = lo + 0.5 * (hi - lo)
        assertEquals(0.0, columnMapped, 1e-9)
        assertTrue("t must not be a quarter turn away from the painted point", abs(hit(circle(), 0.0, 1.0)!! - PI) > 1.0)
    }

    @Test fun tappingTheRightOfACircleReturnsAnEndpointOfItsTInterval() {
        val t = hit(circle(), 1.0, 0.0)!!
        // Both t = 0 and t = 2π paint this point; either is the real parameter.
        assertTrue("t=$t is not the right-hand point", min(abs(t), abs(2 * PI - t)) < 0.01)
    }

    @Test fun yPixelsDecideBetweenTwoBranchesOfTheSameColumn() {
        // Top and bottom share the same screen column: only the vertical pixel distance can
        // tell them apart, which is exactly what a column-based mapping cannot do.
        assertEquals(1.5 * PI, hit(circle(), 0.0, -1.0)!!, 0.01)
    }

    @Test fun theHitUsesPixelDistanceNotJustTheHorizontalOffset() {
        // unitRect is 304 x 228 px, so a data offset is not a pixel offset.
        val branches = listOf(
            // Painted far above the tap, only 0.05 data units to the right.
            listOf(ParamSample(0.0, -0.05, 0.9), ParamSample(1.0, 0.05, 0.9)),
            // Painted at the tap's height, further away in data units.
            listOf(ParamSample(2.0, -0.6, 0.0), ParamSample(3.0, -0.5, 0.0)),
        )
        val t = hit(branches, 0.0, 0.0)!!
        assertTrue("must pick the branch whose pixels are closest, got t=$t", t >= 2.0 && t <= 3.0)
    }

    @Test fun domainBreakIsNeverInterpolatedAcross() {
        val segments = listOf(
            listOf(ParamSample(0.0, 0.0, -1.2), ParamSample(1.0, 0.1, -1.2)),
            listOf(ParamSample(5.0, 0.0, 1.2), ParamSample(6.0, 0.1, 1.2)),
        )
        val t = hit(segments, 0.0, 1.2)!!
        assertEquals(5.0, t, 0.2)
    }

    @Test fun aBranchThatIsEntirelyOffScreenCannotAttractTheTap() {
        val offScreen = listOf(
            listOf(ParamSample(0.0, 0.0, 40.0), ParamSample(1.0, 0.1, 41.0)),
        )
        assertNull(hit(offScreen, 0.0, 0.0))
    }

    @Test fun denserOrReversedSamplingResolvesTheSameVisiblePoint() {
        assertEquals(PI / 2, hit(circle(samples = 720), 0.0, 1.0)!!, 0.005)
        assertEquals(PI / 2, hit(listOf(circle().first().reversed()), 0.0, 1.0)!!, 0.01)
    }
}
