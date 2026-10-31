/*
 * Minis Next — capture frame raster test.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This is the bitmap-level half of the capture contract. `GraphicsLayer` and
 * `Bitmap` need a real device, so the test rasterises into an in-memory pixel
 * buffer *through the production transform* — the layer's size and the content
 * scale both come from `OmniGlassCaptureMath`, the same functions the capture
 * node uses. A scale applied the wrong way round (the reciprocal instead of the
 * scale itself) therefore lands the marker in the wrong pixels and fails here,
 * which pure arithmetic on paper would not have caught.
 *
 * It does not and cannot prove device rendering; that still needs a device.
 */
package com.openminis.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniGlassCaptureFrameTest {

    /** A scope px rectangle, the only thing this file draws. */
    private data class ContentRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val color: Int = 0xFF00FF00.toInt(),
    )

    /**
     * A tiny software rasteriser that stands in for the capture layer: it maps
     * content pixels through the production [OmniGlassCaptureMath.contentScaleFor]
     * and clips to the layer, exactly like the recorded layer does.
     */
    private class Raster(val size: IntSize) {
        val pixels = IntArray(size.width * size.height)

        fun draw(rect: ContentRect, captureScale: Float) {
            val contentScale = OmniGlassCaptureMath.contentScaleFor(captureScale)
            val left = (rect.left * contentScale)
            val top = (rect.top * contentScale)
            val right = (rect.right * contentScale)
            val bottom = (rect.bottom * contentScale)
            val x0 = floorToInt(left).coerceIn(0, size.width)
            val x1 = ceilToInt(right).coerceIn(0, size.width)
            val y0 = floorToInt(top).coerceIn(0, size.height)
            val y1 = ceilToInt(bottom).coerceIn(0, size.height)
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    pixels[y * size.width + x] = rect.color
                }
            }
        }

        fun at(x: Int, y: Int): Int {
            if (x < 0 || y < 0 || x >= size.width || y >= size.height) return 0
            return pixels[y * size.width + x]
        }

        fun paintedColumns(): IntRange? {
            var first = -1
            var last = -1
            for (x in 0 until size.width) {
                val hit = (0 until size.height).any { y -> at(x, y) != 0 }
                if (hit) {
                    if (first < 0) first = x
                    last = x
                }
            }
            return if (first < 0) null else first..last
        }

        private fun floorToInt(value: Float): Int = kotlin.math.floor(value.toDouble()).toInt()

        private fun ceilToInt(value: Float): Int = kotlin.math.ceil(value.toDouble()).toInt()
    }

    private fun layerFor(scope: Size, captureScale: Float): IntSize =
        OmniGlassCaptureMath.captureSize(scope, captureScale)

    @Test
    fun aMarkerFiftyPixelsOutLandsAtTwentyFiveInAHalfCapture() {
        val scope = Size(100f, 100f)
        val raster = Raster(layerFor(scope, 0.5f))
        assertEquals(50, raster.size.width)
        // A 4x2 marker 50 px from the scope origin: 50..54 px -> 25..27.
        raster.draw(ContentRect(50f, 0f, 54f, 2f), 0.5f)
        assertTrue("marker must land at x=25, not x=100", raster.at(25, 0) != 0)
        assertTrue(raster.at(26, 0) != 0)
        assertTrue("marker must not land at x=100 (unscaled)", raster.at(100, 0) == 0)
        assertTrue("marker must not land at x=50 (reciprocal scale)", raster.at(50, 0) == 0)
        assertEquals(25..26, raster.paintedColumns())
    }

    @Test
    fun contentAtTheFarEdgeStaysAtTheFarEdge() {
        // This is what a reciprocal scale breaks: the far edge would be pushed
        // past the layer's bounds and the capture would be a cropped corner.
        val scope = Size(100f, 100f)
        val raster = Raster(layerFor(scope, 0.5f))
        raster.draw(ContentRect(96f, 96f, 100f, 100f), 0.5f)
        assertTrue("far corner must survive", raster.at(48, 48) != 0)
        assertTrue(raster.at(49, 49) != 0)
        assertEquals("nothing may be painted on the near edge", 48..49, raster.paintedColumns())
    }

    @Test
    fun theFullScopeCoversTheFullLayerWithoutOverflow() {
        val scope = Size(120f, 240f)
        val raster = Raster(layerFor(scope, 0.5f))
        raster.draw(ContentRect(0f, 0f, 120f, 240f), 0.5f)
        assertEquals(60, raster.size.width)
        assertEquals(120, raster.size.height)
        assertEquals(0..59, raster.paintedColumns())
        // Every pixel is covered exactly once; no wrap-around, no empty corner.
        assertTrue(raster.pixels.all { it != 0 })
    }

    @Test
    fun theDrawTransformAndTheShaderMappingAgree() {
        val scope = Size(200f, 120f)
        val captureScale = 0.5f
        val raster = Raster(layerFor(scope, captureScale))
        val paneOrigin = Offset(40f, 20f)
        val local = Offset(10f, 10f)
        val contentPoint = Offset(paneOrigin.x + local.x, paneOrigin.y + local.y)
        // 1. where the draw transform puts it in the layer
        raster.draw(
            ContentRect(contentPoint.x, contentPoint.y, contentPoint.x + 1f, contentPoint.y + 1f),
            captureScale,
        )
        // 2. where the shader's uMap says it is
        val sampled = OmniGlassCaptureMath.capturePixelFor(local, paneOrigin, captureScale)
        val column = sampled.x.toInt()
        val row = sampled.y.toInt()
        assertTrue(
            "uMap must read the pixel the transform wrote: sampled $sampled",
            raster.at(column, row) != 0,
        )
        assertEquals(Offset(25f, 15f), sampled)
        assertEquals(25, column)
        assertEquals(15, row)
    }

    @Test
    fun theContentScaleIsTheCaptureScaleNeverItsReciprocal() {
        assertEquals(0.5f, OmniGlassCaptureMath.contentScaleFor(0.5f), 1e-6f)
        assertEquals(0.25f, OmniGlassCaptureMath.contentScaleFor(0.25f), 1e-6f)
        assertEquals(1f, OmniGlassCaptureMath.contentScaleFor(1f), 1e-6f)
        // Hostile values collapse to the documented floor instead of exploding.
        assertEquals(
            OmniGlassCaptureMath.MIN_CAPTURE_SCALE,
            OmniGlassCaptureMath.contentScaleFor(0f),
            1e-6f,
        )
        assertEquals(1f, OmniGlassCaptureMath.contentScaleFor(Float.NaN), 1e-6f)
        assertEquals(1f, OmniGlassCaptureMath.contentScaleFor(4f), 1e-6f)
    }

    @Test
    fun captureSizeAndContentScaleStayConsistentAtPhoneScale() {
        val scope = Size(1080f, 2400f)
        for (quality in OmniGlassQuality.entries) {
            val scale = OmniGlassCaptureMath.captureScale(
                scope, quality.capturePixelBudget, quality.maxCaptureScale,
            )
            val layer = OmniGlassCaptureMath.captureSize(scope, scale)
            val raster = Raster(layer)
            raster.draw(ContentRect(0f, 0f, scope.width, scope.height), scale)
            // A capture that lost the far corner would leave these unpainted.
            assertTrue(
                "${quality.name}: bottom-right corner missing",
                raster.pixels.all { it != 0 },
            )
            // And the layer is the scope scaled down, give or take rounding.
            assertEquals(scope.width * scale, layer.width.toFloat(), 1.5f)
            assertEquals(scope.height * scale, layer.height.toFloat(), 1.5f)
        }
    }
}
