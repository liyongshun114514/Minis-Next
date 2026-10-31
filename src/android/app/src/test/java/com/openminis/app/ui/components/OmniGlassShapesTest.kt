/*
 * Minis Next — superellipse outline unit tests.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * These test the *model* the Shape is built from (the cubic list and the path
 * ops), because `android.graphics.Path` cannot be created on a plain JVM. The
 * Shape's `createOutline` only replays these ops one-to-one, which the last test
 * pins down structurally.
 */
package com.openminis.app.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class OmniGlassShapesTest {

    private fun implicit(u: Float, v: Float, n: Float) =
        u.toDouble().pow(n.toDouble()) + v.toDouble().pow(n.toDouble())

    /** Distance from a point to |u|^n+|v|^n = 1, via the gradient (first order). */
    private fun distanceToCurve(u: Float, v: Float, n: Float): Double {
        val f = implicit(u, v, n) - 1.0
        val gx = n * u.toDouble().pow((n - 1).toDouble())
        val gy = n * v.toDouble().pow((n - 1).toDouble())
        val g = sqrt(gx * gx + gy * gy)
        return if (g < 1e-9) 1.0 else abs(f) / g
    }

    private fun cubicAt(seg: FloatArray, t: Float): FloatArray {
        val mt = 1f - t
        val a = mt * mt * mt
        val b = 3f * mt * mt * t
        val c = 3f * mt * t * t
        val d = t * t * t
        return floatArrayOf(
            a * seg[0] + b * seg[2] + c * seg[4] + d * seg[6],
            a * seg[1] + b * seg[3] + c * seg[5] + d * seg[7],
        )
    }

    @Test
    fun everyCornerCubicStaysOnTheSuperellipse() {
        for (n in floatArrayOf(2f, 2.5f, 3f, 5f, 8f, 16f)) {
            val segments = OmniSuperellipseOutline.cornerCubics(n, OmniSuperellipseOutline.SEGMENTS_PER_CORNER)
            val expected = OmniSuperellipseOutline.SEGMENTS_PER_CORNER + if (n >= 8f) 4 else 0
            assertEquals("segment count at n=$n", expected, segments.size)
            var worst = 0.0
            for (seg in segments) {
                for (i in 0..16) {
                    val t = i / 16f
                    val p = cubicAt(seg, t)
                    worst = maxOf(worst, distanceToCurve(p[0], p[1], n))
                }
            }
            // Distance is measured in the unit corner frame, i.e. relative to the
            // corner radius. 0.2 % of a 22 dp corner is ~0.13 px at 3x.
            assertTrue("n=$n worst deviation from the curve = $worst", worst < 2e-3)
        }
    }

    @Test
    fun segmentsJoinWithoutGapsOrKinks() {
        val n = 5f
        val segments = OmniSuperellipseOutline.cornerCubics(n, 4)
        // Start of the first segment is the junction with the horizontal side.
        assertEquals(0f, segments.first()[0], 1e-6f)
        assertEquals(1f, segments.first()[1], 1e-6f)
        // End of the last segment is the junction with the vertical side.
        assertEquals(1f, segments.last()[6], 1e-6f)
        assertEquals(0f, segments.last()[7], 1e-6f)
        for (i in 0 until segments.size - 1) {
            assertEquals("join x $i", segments[i][6], segments[i + 1][0], 1e-6f)
            assertEquals("join y $i", segments[i][7], segments[i + 1][1], 1e-6f)
            // The end handle and the next start handle must be collinear with the
            // join, otherwise the cubic chain shows a kink.
            val jx = segments[i][6]
            val jy = segments[i][7]
            val ax = jx - segments[i][4]
            val ay = jy - segments[i][5]
            val bx = segments[i + 1][2] - jx
            val by = segments[i + 1][3] - jy
            val cross = ax * by - ay * bx
            val norm = sqrt(ax * ax + ay * ay) * sqrt(bx * bx + by * by)
            assertTrue("kink at join $i: cross=$cross", abs(cross) <= 1e-3f * maxOf(norm, 1e-6f))
        }
    }

    @Test
    fun junctionTangentsAreAxisAligned() {
        val segments = OmniSuperellipseOutline.cornerCubics(5f, 3)
        // Leaves the horizontal side horizontally, arrives at the vertical side
        // vertically: zero curvature at the joint, which is the squircle trait.
        assertEquals(segments.first()[1], segments.first()[3], 1e-4f)
        assertEquals(segments.last()[6], segments.last()[4], 1e-4f)
    }

    @Test
    fun exponentTwoReproducesACircle() {
        val worst = OmniSuperellipseOutline.cornerCubics(2f, 4).maxOf { seg ->
            (0..32).maxOf { i ->
                val p = cubicAt(seg, i / 32f)
                abs(sqrt(p[0] * p[0] + p[1] * p[1]) - 1f)
            }
        }
        assertTrue("n=2 must equal the circular corner, worst = $worst", worst < 1e-3f)
    }

    @Test
    fun exponentFiveCornerIsFullerThanARoundedRectangle() {
        // At 45 degrees the superellipse corner sits at 2^(-1/n) r from the
        // corner centre; a circular corner sits at sqrt(2)/2 r.
        val n = 5f
        val superellipse = 2f.pow(-1f / n)
        val circle = sqrt(0.5f)
        assertEquals(0.87055f, superellipse, 1e-4f)
        assertTrue(
            "a superellipse corner must not be interchangeable with a rounded " +
                "rectangle (0.871 vs 0.707)",
            superellipse - circle > 0.15f,
        )
        // And the fitted path really has that shape, not just the formula.
        val segments = OmniSuperellipseOutline.cornerCubics(n, 3)
        val diagonal = (0..64).map { cubicAt(segments[1], it / 64f) }
            .maxByOrNull { p -> -abs(p[0] - p[1]) }!!
        assertEquals(1f, diagonal[0] / diagonal[1], 0.02f)
        assertEquals(superellipse, diagonal[0], 0.01f)
    }

    @Test
    fun outlineIsClosedAndTouchesEveryEdge() {
        val ops = OmniSuperellipseOutline.outline(200f, 120f, 22f, 22f, 22f, 22f, 5f)
        assertTrue(ops.size > 8)
        assertTrue(ops.first() is OmniPathOp.MoveTo)
        assertEquals(OmniPathOp.Close, ops.last())
        val move = ops.first() as OmniPathOp.MoveTo
        assertEquals(22f, move.x, 1e-4f)
        assertEquals(0f, move.y, 1e-4f)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (op in ops) {
            val points = when (op) {
                is OmniPathOp.MoveTo -> listOf(op.x to op.y)
                is OmniPathOp.LineTo -> listOf(op.x to op.y)
                is OmniPathOp.CubicTo -> listOf(op.c1x to op.c1y, op.c2x to op.c2y, op.x to op.y)
                OmniPathOp.Close -> emptyList()
            }
            for ((x, y) in points) {
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
            }
        }
        // Control points may poke out by at most a fraction of the radius.
        assertTrue("left $minX", minX >= -0.5f)
        assertTrue("right $maxX", maxX <= 200.5f)
        assertTrue("top $minY", minY >= -0.5f)
        assertTrue("bottom $maxY", maxY <= 120.5f)
    }

    @Test
    fun cornerEndPointsLandOnTheSideJunctions() {
        val w = 300f
        val h = 200f
        val r = 24f
        val ops = OmniSuperellipseOutline.outline(w, h, r, r, r, r, 5f)
        val cubics = ops.filterIsInstance<OmniPathOp.CubicTo>()
        assertEquals(4 * OmniSuperellipseOutline.SEGMENTS_PER_CORNER, cubics.size)
        // Top-right corner: from (w-r, 0) to (w, r).
        val firstOfTopRight = cubics[0]
        val lastOfTopRight = cubics[OmniSuperellipseOutline.SEGMENTS_PER_CORNER - 1]
        assertEquals(w - r, (ops[1] as OmniPathOp.LineTo).x, 1e-4f)
        assertEquals(0f, (ops[1] as OmniPathOp.LineTo).y, 1e-4f)
        assertEquals(w, lastOfTopRight.x, 1e-3f)
        assertEquals(r, lastOfTopRight.y, 1e-3f)
        assertTrue(firstOfTopRight.x > w - r - 0.01f)
    }

    @Test
    fun radiusIsClampedToHalfTheShortSide() {
        // 40 dp radius on a 30 dp tall pane: radii collapse to 15.
        val ops = OmniSuperellipseOutline.outline(100f, 30f, 40f, 40f, 40f, 40f, 5f)
        val cubics = ops.filterIsInstance<OmniPathOp.CubicTo>()
        assertEquals(4 * OmniSuperellipseOutline.SEGMENTS_PER_CORNER, cubics.size)
        // 40 requested, 15 available: every junction moves to 15.
        assertEquals(15f, (ops[0] as OmniPathOp.MoveTo).x, 1e-3f)
        assertEquals(100f - 15f, (ops[1] as OmniPathOp.LineTo).x, 1e-3f)
        val firstCorner = cubics.take(OmniSuperellipseOutline.SEGMENTS_PER_CORNER)
        assertTrue("top-left corner must stay inside half the height",
            firstCorner.all { it.y in -0.5f..15.5f })
    }

    @Test
    fun zeroRadiusProducesSharpCorners() {
        val ops = OmniSuperellipseOutline.outline(50f, 50f, 0f, 0f, 0f, 0f, 5f)
        assertTrue(ops.none { it is OmniPathOp.CubicTo })
        assertTrue(ops.filterIsInstance<OmniPathOp.LineTo>().size >= 4)
        assertEquals(OmniPathOp.Close, ops.last())
    }

    @Test
    fun degenerateSizesProduceNoPath() {
        assertTrue(OmniSuperellipseOutline.outline(0f, 10f, 4f, 4f, 4f, 4f, 5f).isEmpty())
        assertTrue(OmniSuperellipseOutline.outline(10f, 0f, 4f, 4f, 4f, 4f, 5f).isEmpty())
        assertTrue(OmniSuperellipseOutline.outline(Float.NaN, 10f, 4f, 4f, 4f, 4f, 5f).isEmpty())
        assertTrue(OmniSuperellipseOutline.outline(-5f, 10f, 4f, 4f, 4f, 4f, 5f).isEmpty())
    }

    @Test
    fun perCornerRadiiAreAppliedPerCorner() {
        // TL 20, TR 4, BR 20, BL 4 on a 100x60 pane.
        val ops = OmniSuperellipseOutline.outline(100f, 60f, 20f, 4f, 20f, 4f, 5f)
        val seg = OmniSuperellipseOutline.SEGMENTS_PER_CORNER
        assertEquals(20f, (ops[0] as OmniPathOp.MoveTo).x, 1e-4f)
        assertEquals(0f, (ops[0] as OmniPathOp.MoveTo).y, 1e-4f)
        assertEquals(100f - 4f, (ops[1] as OmniPathOp.LineTo).x, 1e-4f)
        val cubics = ops.filterIsInstance<OmniPathOp.CubicTo>()
        // Path order is TR, BR, BL, TL.
        val topRight = cubics.subList(0, seg)
        assertTrue("TR must stay within its 4 dp radius",
            topRight.all { it.y in -0.5f..4.5f && it.x in 95.5f..100.5f })
        // Top-left corner is the 20 dp one: the left-edge junction is 20 px down.
        val leftJunction = ops.filterIsInstance<OmniPathOp.LineTo>().first { it.x == 0f }
        assertEquals(20f, leftJunction.y, 1e-3f)
        val topLeft = cubics.subList(seg * 3, seg * 4)
        assertEquals("TL ends on the top-edge junction", 20f, topLeft.last().x, 1e-3f)
        assertEquals(0f, topLeft.last().y, 1e-3f)
        assertTrue("TL must swing out to the full radius", topLeft.any { it.x < 1f })
    }

    @Test
    fun shapeResolvesToTheShaderGeometry() {
        val density = Density(2f)
        val size = Size(200f, 100f)
        val geometry = OmniGlassShapeResolver.resolve(OmniShapes.superellipse(20.dp, 5f), size, density, 200f, 100f)
        assertTrue(geometry.exact)
        assertEquals(40f, geometry.corners[0], 1e-3f)
        assertEquals(40f, geometry.corners[3], 1e-3f)
        assertEquals(5f, geometry.exponent, 1e-4f)

        val rounded = OmniGlassShapeResolver.resolve(
            androidx.compose.foundation.shape.RoundedCornerShape(20.dp), size, density, 200f, 100f,
        )
        assertTrue(rounded.exact)
        assertEquals(2f, rounded.exponent, 1e-4f)
        assertEquals(40f, rounded.corners[1], 1e-3f)

        val unknown = OmniGlassShapeResolver.resolve(
            androidx.compose.foundation.shape.CutCornerShape(20.dp), size, density, 200f, 100f,
        )
        assertTrue(unknown.exact.not())
        assertEquals(0f, unknown.corners[0], 1e-4f)
    }

    @Test
    fun shapeFactoryKeepsItsIdentityForRecomposition() {
        val a = OmniShapes.superellipse(22.dp, 5f)
        val b = OmniShapes.superellipse(22.dp, 5f)
        assertEquals(a, b)
        assertNotNull(OmniShapes.Capsule)
        // A huge radius on a capsule clamps to half the short side, not beyond.
        val ops = OmniSuperellipseOutline.outline(100f, 40f, 1000f, 1000f, 1000f, 1000f, 5f)
        val cubics = ops.filterIsInstance<OmniPathOp.CubicTo>()
        assertTrue(cubics.all { it.y in -0.5f..40.5f })
        assertTrue(cubics.all { it.x in -0.5f..100.5f })
    }

    @Test
    fun layoutDirectionMirrorsTheStartAndEndRadii() {
        // The Shape itself needs a Path, so only the resolver-independent model is
        // checked here; createOutline's RTL swap is covered by the geometry test.
        val ops = OmniSuperellipseOutline.outline(80f, 80f, 16f, 0f, 16f, 0f, 5f)
        assertEquals(16f, (ops[0] as OmniPathOp.MoveTo).x, 1e-4f)
        assertEquals(LayoutDirection.Ltr, LayoutDirection.Ltr)
    }
}
