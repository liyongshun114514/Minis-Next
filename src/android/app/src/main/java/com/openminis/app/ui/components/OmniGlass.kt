/*
 * Minis Next — Omni liquid glass: scope, surface and renderers.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Provenance: the optics, the capture-then-refract idea and the panel parameters
 * (refraction depth, bevel, frost, saturation, rim, dispersion) are a Compose
 * re-expression of the FlClash liquid-glass surface reached through
 * omnimind-ai/OmniBot commit c4728736964245509404670ee992d3b658efaecd
 * (ui/lib/widgets/flclash/liquid_glass.dart and ui/shaders/liquid_glass.frag),
 * whose FlClash-derived parts are GPLv3 — upstream chen08209/FlClash
 * 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7, pinned in OmniBot's
 * docs/third-party/flclash-NOTICE.md. No OmniBot AGPL-only code, Dart widget
 * tree, font, brand asset or business runtime is copied; the Compose capture,
 * the drawing backends and the API isolation are this project's own.
 *
 * ## What this actually renders
 *
 * * API 33+ — a real AGSL runtime shader (`OmniGlassShaderSource`) samples a
 *   downscaled capture of the content behind the pane and displaces it along the
 *   rounded-superellipse normal (`profile = 1 - sqrt(1 - rise²)`), with frost,
 *   chromatic parting, saturation/brightness, rim highlight and hairline.
 * * API 26–32 — a real geometric refraction: the same capture, cropped to the
 *   pane, is drawn through a `Canvas.drawBitmapMesh` grid whose vertices are
 *   displaced by the identical displacement field, then the same saturation
 *   filter, tint and rim are composited. It is a mesh approximation of the lens,
 *   not a flat highlight.
 * * No capture at all (first frame, capture disabled, or a device that cannot
 *   capture) — a steady tinted surface with hairline and rim. Explicit,
 *   documented, and never an exception.
 */
package com.openminis.app.ui.components

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader as AndroidShader
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Neutral glass tints. Deliberately not the OmniBot palette: OmniBot's own
 * theme file is not reusable here (see the provenance note), so these are plain
 * neutral values a caller may override with any project colour.
 */
object OmniGlassTones {
    /** Light-glass fill: a wash of white over the refracted backdrop. */
    val Light: Color = Color(0xB8F7F7F9)

    /** Dark-glass fill. */
    val Dark: Color = Color(0xB0141416)

    fun of(isDark: Boolean): Color = if (isDark) Dark else Light
}

/**
 * The look of one glass pane, in density-independent units.
 *
 * The defaults follow the reference's regular glass: a shallow bevel that bends
 * the backdrop's edge, a wide frost, a saturated and slightly brightened
 * picture, a 1 dp hairline and a rim highlight that fades ~12 dp inward.
 */
@Immutable
data class OmniGlassStyle(
    val tint: Color = OmniGlassTones.Light,
    /** How far the rim pulls the backdrop inward. Negative = raised lens. */
    val refraction: Dp = 6.dp,
    /** Width of the refracting band in from the rim. */
    val bevel: Dp = 14.dp,
    /** Frost radius applied to the sampled backdrop. */
    val blur: Dp = 10.dp,
    val saturation: Float = 1.35f,
    val brightness: Float = 1.02f,
    /** Rim highlight strength, 0..1. */
    val rim: Float = 0.42f,
    /** How far the rim highlight reaches in from the edge. */
    val rimWidth: Dp = 12.dp,
    /** Dark hairline inside the edge, 0..1. */
    val hairline: Float = 0.45f,
    /** Chromatic parting between colour channels, in dp. */
    val chroma: Dp = 0.4.dp,
    /** Overall opacity of the pane, 0..1 (useful for entering/fading sheets). */
    val opacity: Float = 1f,
) {
    companion object {
        val Default = OmniGlassStyle()

        /** Thinner, clearer glass for controls that sit on content. */
        val Clear = OmniGlassStyle(
            tint = Color(0x74FFFFFF),
            refraction = 4.dp,
            bevel = 9.dp,
            blur = 6.dp,
            saturation = 1.2f,
            rim = 0.5f,
            hairline = 0.3f,
        )

        /** A raised lens (no frost, reads outward) as the reference's lens mode. */
        val Lens = OmniGlassStyle(
            tint = Color(0x1AFFFFFF),
            refraction = (-4.3f).dp,
            bevel = 6.dp,
            blur = 0.dp,
            saturation = 1f,
            rim = 0.6f,
            rimWidth = 10.dp,
            hairline = 0.42f,
            chroma = 1.2.dp,
        )
    }
}

/**
 * The capture scope. Everything inside it is the backdrop the glass panes
 * refract; panes themselves are excluded from the capture (see
 * [OmniGlassCaptureGuard]).
 *
 * Wiring: wrap the *background* of a screen (the content the dock/sheet floats
 * over) and place the panes inside it with [omniGlassSurface]. One scope per
 * window is enough; nested scopes each keep their own capture.
 */
@Composable
fun OmniGlassScope(
    modifier: Modifier = Modifier,
    quality: OmniGlassQuality = OmniGlassQuality.Balanced,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val controller = remember { OmniGlassCaptureController(quality, enabled) }
    controller.quality = quality
    controller.enabled = enabled
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    // The only step that rasterises a whole bitmap runs here, off the draw pass.
    // `requestToken` is written by the draw phase, so this coroutine wakes up
    // once per accepted capture and never spins.
    LaunchedEffect(controller, layer) {
        snapshotFlow { controller.requestToken }.collect { token ->
            if (token > 0L) {
                controller.completeCapture(layer, SystemClock.uptimeMillis())
            }
        }
    }
    CompositionLocalProvider(LocalOmniGlass provides controller) {
        Box(modifier.omniGlassCapture(controller, layer)) {
            content()
        }
    }
}

/**
 * The capture controller of the nearest [OmniGlassScope].
 *
 * Public so a screen can ask for a refresh ([OmniGlassCaptureController.invalidate])
 * after swapping a large background image, and so a pane placed outside the
 * scope's subtree can be wired explicitly.
 */
val LocalOmniGlass = staticCompositionLocalOf<OmniGlassCaptureController?> { null }

/**
 * Makes the pane a glass surface: the backdrop behind it is refracted through
 * the pane's [shape], with frost, saturation and a rim.
 *
 * Must be inside an [OmniGlassScope]; outside one it draws only its steady tint
 * (no exception, no capture).
 */
@Composable
fun Modifier.omniGlassSurface(
    shape: Shape = OmniShapes.Superellipse,
    style: OmniGlassStyle = OmniGlassStyle.Default,
    enabled: Boolean = true,
): Modifier {
    val controller = LocalOmniGlass.current
    return this then OmniGlassSurfaceElement(
        controller = controller,
        shape = shape,
        style = style,
        enabled = enabled,
    )
}

/** Box wrapper for callers who prefer a composable over a modifier factory. */
@Composable
fun OmniGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = OmniShapes.Superellipse,
    style: OmniGlassStyle = OmniGlassStyle.Default,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.omniGlassSurface(shape, style, enabled)) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Capture node
// ---------------------------------------------------------------------------

private data class OmniGlassCaptureElement(
    val controller: OmniGlassCaptureController,
    val layer: GraphicsLayer,
) : ModifierNodeElement<OmniGlassCaptureNode>() {
    override fun create(): OmniGlassCaptureNode = OmniGlassCaptureNode(controller, layer)

    override fun update(node: OmniGlassCaptureNode) {
        node.controller = controller
        node.layer = layer
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "omniGlassCapture"
        properties["quality"] = controller.quality.name
        properties["enabled"] = controller.enabled
    }
}

internal fun Modifier.omniGlassCapture(
    controller: OmniGlassCaptureController,
    layer: GraphicsLayer,
): Modifier = this then OmniGlassCaptureElement(controller, layer)

private class OmniGlassCaptureNode(
    var controller: OmniGlassCaptureController,
    var layer: GraphicsLayer,
) : Modifier.Node(), DrawModifierNode {

    override fun ContentDrawScope.draw() {
        val c = controller
        c.noteScopeSize(size)
        c.noteScopeBounds(boundsInWindowOrZero())
        if (c.shouldCapture(SystemClock.uptimeMillis())) {
            captureNow(c)
        }
        drawContent()
    }

    private fun boundsInWindowOrZero(): Rect = try {
        val coordinates = requireLayoutCoordinates()
        // Popup and Dialog own different windows: only screen coordinates
        // can be subtracted from a pane in another window.
        val origin = coordinates.localToScreen(Offset.Zero)
        if (origin.x.isFinite() && origin.y.isFinite()) {
            Rect(origin, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        } else Rect.Zero
    } catch (error: Throwable) {
        Rect.Zero
    }

    private fun ContentDrawScope.captureNow(c: OmniGlassCaptureController) {
        val prepared = c.nextCaptureSize() ?: return
        val target = prepared.first
        val scale = prepared.second
        if (target.width <= 0 || target.height <= 0 || scale <= 0f) return
        try {
            // `GraphicsLayer.record` here is the DrawScope member extension, not
            // the platform function: it retargets this scope's canvas onto the
            // layer, so `drawContent()` inside the block really does land in the
            // layer. The layer is `scale` × the scope, so the content is scaled
            // *by* `scale` (not by its reciprocal) — otherwise 1/scale of the
            // content would be blown up and only the top-left corner recorded.
            val contentScale = OmniGlassCaptureMath.contentScaleFor(scale)
            layer.record(target) {
                OmniGlassCaptureGuard.suppressing {
                    scale(contentScale, contentScale, pivot = Offset.Zero) {
                        this@captureNow.drawContent()
                    }
                }
            }
            c.requestCapture(scale)
        } catch (error: Throwable) {
            // A layer that cannot be recorded (vendor renderer, OOM) must not take
            // the UI down: drop the capture and keep the steady surface.
            c.noteCaptureFailure()
        }
    }
}

// ---------------------------------------------------------------------------
// Surface node
// ---------------------------------------------------------------------------

private data class OmniGlassSurfaceElement(
    val controller: OmniGlassCaptureController?,
    val shape: Shape,
    val style: OmniGlassStyle,
    val enabled: Boolean,
) : ModifierNodeElement<OmniGlassSurfaceNode>() {
    override fun create(): OmniGlassSurfaceNode =
        OmniGlassSurfaceNode(controller, shape, style, enabled)

    override fun update(node: OmniGlassSurfaceNode) {
        node.bind(controller, shape, style, enabled)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "omniGlassSurface"
        properties["shape"] = shape
        properties["style"] = style
        properties["enabled"] = enabled
        properties["hasScope"] = controller != null
    }
}

private class OmniGlassSurfaceNode(
    controller: OmniGlassCaptureController?,
    shape: Shape,
    style: OmniGlassStyle,
    enabled: Boolean,
) : Modifier.Node(), DrawModifierNode {

    private var controller: OmniGlassCaptureController? = controller
    private var shape: Shape = shape
    private var style: OmniGlassStyle = style
    private var enabled: Boolean = enabled
    private var attached: Boolean = false
    private val renderer = OmniGlassRenderer()

    internal fun bind(
        nextController: OmniGlassCaptureController?,
        nextShape: Shape,
        nextStyle: OmniGlassStyle,
        nextEnabled: Boolean,
    ) {
        if (controller !== nextController) {
            if (attached) controller?.detachPanel()
            controller = nextController
            if (attached) nextController?.attachPanel()
        }
        shape = nextShape
        style = nextStyle
        enabled = nextEnabled
    }

    override fun onAttach() {
        attached = true
        controller?.attachPanel()
    }

    override fun onDetach() {
        if (attached) controller?.detachPanel()
        attached = false
        renderer.release()
    }

    override fun ContentDrawScope.draw() {
        // Inside a capture pass this pane and its children contribute nothing:
        // that is what stops a pane from sampling itself.
        if (OmniGlassCaptureGuard.capturing) return
        if (!enabled || size.width <= 0f || size.height <= 0f) {
            drawContent()
            return
        }
        val c = controller
        renderer.drawPane(
            scope = this,
            shape = shape,
            style = style,
            backdrop = if (c != null && c.enabled) c.backdrop else null,
            originInScope = paneOriginInScope(c),
            size = size,
            density = Density(density),
            layoutDirection = layoutDirection,
        )
        drawContent()
    }

    private fun paneOriginInScope(c: OmniGlassCaptureController?): Offset {
        if (c == null) return Offset.Zero
        return try {
            val mine = requireLayoutCoordinates().localToScreen(Offset.Zero)
            val scope = c.scopeBoundsInWindow
            if (mine.x.isFinite() && mine.y.isFinite()) {
                Offset(mine.x - scope.left, mine.y - scope.top)
            } else Offset.Zero
        } catch (error: Throwable) {
            Offset.Zero
        }
    }
}

// ---------------------------------------------------------------------------
// Renderer
// ---------------------------------------------------------------------------

/**
 * Draws one pane. Holds the per-pane caches (path, AGSL program, mesh buffers,
 * cropped/blurred bitmaps) because allocating any of them per frame would cost
 * more than the refraction itself.
 */
internal class OmniGlassRenderer {
    private var path: Path? = null
    private var pathShape: Shape? = null
    private var pathOutline: Outline? = null
    private var pathWidth: Float = -1f
    private var pathHeight: Float = -1f
    private var pathDensity: Float = -1f
    private var pathDirection: LayoutDirection? = null

    private var program: OmniGlassProgramBackend? = null
    private var programRequested = false

    private var meshPaint: Paint? = null
    private var rimPaint: Paint? = null
    private var tintPaint: Paint? = null
    private var saturationFilter: ColorMatrixColorFilter? = null
    private var saturationKey: Float = Float.NaN

    private var cropSource: Bitmap? = null
    private var cropGeneration: Long = -1L
    private var cropped: Bitmap? = null
    private var blurred: Bitmap? = null

    internal fun release() {
        program?.release()
        program = null
        programRequested = false
        path = null
        pathShape = null
        cropped = null
        blurred = null
        cropSource = null
        cropGeneration = -1L
        meshPaint = null
        rimPaint = null
        tintPaint = null
        saturationFilter = null
        saturationKey = Float.NaN
    }

    internal fun drawPane(
        scope: DrawScope,
        shape: Shape,
        style: OmniGlassStyle,
        backdrop: OmniGlassBackdrop?,
        originInScope: Offset,
        size: Size,
        density: Density,
        layoutDirection: LayoutDirection,
    ) {
        if (!(size.width > 0f) || !(size.height > 0f)) return
        val panePath = pathFor(shape, size, density, layoutDirection) ?: return
        val geometry = OmniGlassShapeResolver.resolve(shape, size, density, size.width, size.height)
        val optics = resolveOptics(style, density)
        if (optics.alpha <= 0.004f) return

        if (backdrop == null || backdrop.scale <= 0f || backdrop.width < 2 ||
            backdrop.height < 2 || backdrop.bitmap.isRecycled
        ) {
            drawSteady(scope, panePath, optics, size)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val compiled = ensureProgram()
            if (compiled != null) {
                val uniforms = OmniGlassShaderInputs.pack(
                    width = size.width,
                    height = size.height,
                    corners = geometry.corners,
                    exponent = geometry.exponent,
                    captureScale = backdrop.scale,
                    captureOffsetX = originInScope.x * backdrop.scale,
                    captureOffsetY = originInScope.y * backdrop.scale,
                    optics = optics,
                )
                val bitmap = backdrop.bitmap
                var drawn = false
                scope.drawIntoCanvas { canvas ->
                    try {
                        compiled.draw(canvas.nativeCanvas, panePath, bitmap, uniforms)
                        drawn = true
                    } catch (error: Throwable) {
                        // A shader that fails mid-frame (recycled bitmap, vendor
                        // driver) falls through to the mesh backend below.
                    }
                }
                if (drawn) return
            }
        }
        drawMesh(scope, panePath, optics, geometry, backdrop, originInScope, size)
    }

    private fun ensureProgram(): OmniGlassProgramBackend? {
        if (program != null) return program
        if (programRequested) return null
        programRequested = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            program = OmniGlassShaderApi33.createProgram()
        }
        return program
    }

    // --- steady surface (no capture) -------------------------------------

    private fun drawSteady(scope: DrawScope, panePath: Path, optics: OmniGlassOptics, size: Size) {
        scope.drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawPath(panePath.asAndroidPath(), tintPaint(optics))
            canvas.nativeCanvas.drawPath(panePath.asAndroidPath(), rimPaint(optics, size))
        }
    }

    // --- API 26..32 geometric refraction ---------------------------------

    private fun drawMesh(
        scope: DrawScope,
        panePath: Path,
        optics: OmniGlassOptics,
        geometry: OmniGlassShapeGeometry,
        backdrop: OmniGlassBackdrop,
        originInScope: Offset,
        size: Size,
    ) {
        val crop = prepareCrop(backdrop, originInScope, size, optics)
        val region = MeshBuffers.fill(
            vertices = MeshBuffers.vertices,
            left = crop.left,
            top = crop.top,
            width = crop.width,
            height = crop.height,
            paneWidth = size.width,
            paneHeight = size.height,
            corners = geometry.corners,
            exponent = geometry.exponent,
            optics = optics,
        )
        if (!region) return
        val bitmap = crop.bitmap
        scope.drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val save = native.save()
            try {
                native.clipPath(panePath.asAndroidPath())
                native.drawBitmapMesh(
                    bitmap,
                    MeshBuffers.CELLS,
                    MeshBuffers.CELLS,
                    MeshBuffers.vertices,
                    0,
                    null,
                    0,
                    meshPaint(optics),
                )
                native.drawPath(panePath.asAndroidPath(), tintPaint(optics))
                native.drawPath(panePath.asAndroidPath(), rimPaint(optics, size))
            } finally {
                native.restoreToCount(save)
            }
        }
    }

    private class Crop(
        val bitmap: Bitmap,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    /**
     * Crops the shared capture to this pane (plus the bevel margin) and frosts it
     * by downscaling. Both are cached until the capture changes.
     */
    private fun prepareCrop(
        backdrop: OmniGlassBackdrop,
        originInScope: Offset,
        size: Size,
        optics: OmniGlassOptics,
    ): Crop {
        val source = backdrop.bitmap
        val scale = backdrop.scale
        if (cropSource !== source || cropGeneration != backdrop.generation) {
            cropSource = source
            cropGeneration = backdrop.generation
            cropped = null
            blurred = null
        }
        val margin = max(optics.bevelPx, 1f) + 2f
        val x = ((originInScope.x - margin) * scale).toInt().coerceIn(0, max(source.width - 2, 0))
        val y = ((originInScope.y - margin) * scale).toInt().coerceIn(0, max(source.height - 2, 0))
        val w = ((size.width + margin * 2f) * scale).toInt()
            .coerceIn(2, max(source.width - x, 2))
        val h = ((size.height + margin * 2f) * scale).toInt()
            .coerceIn(2, max(source.height - y, 2))
        var tile = cropped
        if (tile == null || tile.width != w || tile.height != h) {
            tile = Bitmap.createBitmap(source, x, y, w, h)
            cropped = tile
            blurred = null
        }
        // Frost: the crop is downscaled and read back with bilinear filtering,
        // which is what a Gaussian blur becomes at this size.
        val targetSigma = max(optics.blurPx * scale, 0f)
        val divisor = if (targetSigma < 0.5f) 1 else (targetSigma / 1.5f).toInt().coerceIn(1, 8)
        val smooth: Bitmap
        if (divisor > 1) {
            val bw = max(2, w / divisor)
            val bh = max(2, h / divisor)
            var cached = blurred
            if (cached == null || cached.width != bw || cached.height != bh) {
                cached = Bitmap.createScaledBitmap(tile, bw, bh, true)
                blurred = cached
            }
            smooth = cached
        } else {
            smooth = tile
        }
        // Destination geometry of the crop, in pane-local px.
        return Crop(
            bitmap = smooth,
            left = x / scale - originInScope.x,
            top = y / scale - originInScope.y,
            width = w / scale,
            height = h / scale,
        )
    }

    // --- paints -----------------------------------------------------------

    private fun meshPaint(optics: OmniGlassOptics): Paint {
        val paint = meshPaint ?: Paint().also {
            it.isAntiAlias = true
            it.isFilterBitmap = true
            it.isDither = true
            meshPaint = it
        }
        paint.colorFilter = saturationColorFilter(optics.saturation, optics.brightness)
        paint.alpha = (optics.alpha * 255f).toInt().coerceIn(0, 255)
        return paint
    }

    private fun tintPaint(optics: OmniGlassOptics): Paint {
        val paint = tintPaint ?: Paint().also {
            it.isAntiAlias = true
            tintPaint = it
        }
        paint.colorFilter = null
        paint.color = argb(
            optics.tintAlpha * optics.alpha,
            optics.tintRed,
            optics.tintGreen,
            optics.tintBlue,
        )
        return paint
    }

    private fun rimPaint(optics: OmniGlassOptics, size: Size): Paint {
        val paint = rimPaint ?: Paint().also {
            it.isAntiAlias = true
            it.style = Paint.Style.STROKE
            rimPaint = it
        }
        val strength = (optics.highlight * optics.alpha * 255f).toInt().coerceIn(0, 255)
        val hairline = (optics.lineAlpha * optics.alpha * 110f).toInt().coerceIn(0, 255)
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            max(size.height, 1f),
            intArrayOf(
                (strength shl 24) or 0xFFFFFF,
                (hairline shl 24) or 0xFFFFFF,
                ((strength * 3 / 4) shl 24) or 0xFFFFFF,
            ),
            floatArrayOf(0f, 0.5f, 1f),
            AndroidShader.TileMode.CLAMP,
        )
        paint.strokeWidth = max(1f, optics.highlightBandPx * 0.09f)
        return paint
    }

    private fun saturationColorFilter(saturation: Float, brightness: Float): ColorMatrixColorFilter {
        val key = saturation * 1000f + brightness
        val cached = saturationFilter
        if (cached != null && saturationKey == key) return cached
        val s = saturation.coerceIn(0f, 3f)
        val b = brightness.coerceIn(0f, 3f)
        val lr = 0.2126f
        val lg = 0.7152f
        val lb = 0.0722f
        val inv = 1f - s
        val m = floatArrayOf(
            (lr * inv + s) * b, lg * inv * b, lb * inv * b, 0f, 0f,
            lr * inv * b, (lg * inv + s) * b, lb * inv * b, 0f, 0f,
            lr * inv * b, lg * inv * b, (lb * inv + s) * b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        val filter = ColorMatrixColorFilter(m)
        saturationFilter = filter
        saturationKey = key
        return filter
    }

    private fun argb(alpha: Float, red: Float, green: Float, blue: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        val r = (red.coerceIn(0f, 1f) * 255f).toInt()
        val g = (green.coerceIn(0f, 1f) * 255f).toInt()
        val bl = (blue.coerceIn(0f, 1f) * 255f).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or bl
    }

    // --- path -------------------------------------------------------------

    private fun pathFor(
        shape: Shape,
        size: Size,
        density: Density,
        layoutDirection: LayoutDirection,
    ): Path? {
        val densityValue = density.density
        // A CornerSize may read an Animatable while keeping the same Shape
        // instance. Resolve it in draw so snapshot observation invalidates this
        // pane and compare the actual outline, not just object identity.
        val outline = shape.createOutline(size, layoutDirection, density)
        if (path == null || pathOutline != outline || pathShape != shape || pathWidth != size.width ||
            pathHeight != size.height || pathDensity != densityValue ||
            pathDirection != layoutDirection
        ) {
            pathOutline = outline
            val built = when (outline) {
                is Outline.Generic -> outline.path
                is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                else -> Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) }
            }
            path = built
            pathShape = shape
            pathWidth = size.width
            pathHeight = size.height
            pathDensity = densityValue
            pathDirection = layoutDirection
        }
        return path
    }
}

/** Reusable mesh scratch space; only ever touched on the main thread. */
private object MeshBuffers {
    const val CELLS = 12

    val vertices: FloatArray = FloatArray((CELLS + 1) * (CELLS + 1) * 2)

    /**
     * Fills [vertices] with the destination position of every source vertex:
     * the content that sits at `s` is shown at `s - shift(s)`, so a rim vertex
     * pulls what lies behind it inward — the same displacement field the AGSL
     * backend evaluates per pixel.
     *
     * @return false when the region is degenerate and nothing should be drawn.
     */
    fun fill(
        vertices: FloatArray,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        paneWidth: Float,
        paneHeight: Float,
        corners: FloatArray,
        exponent: Float,
        optics: OmniGlassOptics,
    ): Boolean {
        if (!(width > 1f) || !(height > 1f)) return false
        val holder = OmniGlassCorners(
            corners.getOrElse(0) { 0f },
            corners.getOrElse(1) { 0f },
            corners.getOrElse(2) { 0f },
            corners.getOrElse(3) { 0f },
        )
        var index = 0
        for (row in 0..CELLS) {
            val sourceY = top + height * (row.toFloat() / CELLS)
            for (column in 0..CELLS) {
                val sourceX = left + width * (column.toFloat() / CELLS)
                val sample = OmniGlassGeometryMath.sample(
                    sourceX, sourceY, paneWidth, paneHeight, holder, exponent,
                )
                val shift = OmniGlassGeometryMath.refractionShift(
                    inset = sample.inset,
                    normalX = sample.normalX,
                    normalY = sample.normalY,
                    bevelPx = optics.bevelPx,
                    depthPx = optics.refractionPx,
                )
                vertices[index++] = sourceX - shift.x
                vertices[index++] = sourceY - shift.y
            }
        }
        return true
    }
}

/** Resolves a style against a density. */
internal fun resolveOptics(style: OmniGlassStyle, density: Density): OmniGlassOptics = with(density) {
    val opacity = style.opacity.coerceIn(0f, 1f)
    OmniGlassOptics(
        refractionPx = style.refraction.toPx(),
        bevelPx = max(style.bevel.toPx(), 0.5f),
        blurPx = max(style.blur.toPx(), 0f),
        saturation = style.saturation,
        brightness = style.brightness,
        tintRed = style.tint.red,
        tintGreen = style.tint.green,
        tintBlue = style.tint.blue,
        tintAlpha = style.tint.alpha.coerceIn(0f, 1f),
        highlight = style.rim.coerceIn(0f, 1f),
        highlightBandPx = max(style.rimWidth.toPx(), 0.5f),
        lineAlpha = style.hairline.coerceIn(0f, 1f),
        chromaPx = max(style.chroma.toPx(), 0f),
        alpha = opacity,
    )
}
