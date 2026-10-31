/*
 * Minis Next — Omni liquid glass backdrop capture.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Provenance: the *idea* and the cost rules (one shared capture of everything
 * behind the panes, downscaled until the frost still spans a pixel or two,
 * reused while it is valid) come from the FlClash capture host reached through
 * omnimind-ai/OmniBot commit c4728736964245509404670ee992d3b658efaecd
 * (ui/lib/widgets/flclash/liquid_glass_capture.dart), GPLv3 for its
 * FlClash-derived parts — upstream chen08209/FlClash
 * 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7. Flutter's layer/atlas/level
 * machinery is not ported: Compose needs none of it, and the capture here is one
 * GraphicsLayer replayed into one downscaled bitmap.
 *
 * Privacy: the capture only ever exists as an in-memory bitmap owned by the
 * OmniGlassScope composition. It is never written to disk, never encoded, never
 * logged and never sent anywhere; it carries no identifiers and is dropped as
 * soon as the scope leaves the composition.
 */
package com.openminis.app.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize

/**
 * Set while a scope re-renders its content into the capture.
 *
 * Every glass surface checks this *first* and draws nothing at all (its children
 * included) during that pass, so a pane can never sample a capture that already
 * contains itself — the feedback loop that turns glass into a smear, and with a
 * tint into a runaway brightening. It is a plain counter on the main thread: the
 * Compose draw phase is single threaded and the depth is restored in a `finally`.
 */
internal object OmniGlassCaptureGuard {
    private var depth = 0

    internal val capturing: Boolean get() = depth > 0

    internal inline fun <T> suppressing(block: () -> T): T {
        depth++
        try {
            return block()
        } finally {
            depth--
        }
    }
}

/**
 * One published backdrop: a downscaled render of everything behind the panes,
 * plus the scale that maps scope px onto its pixels.
 */
internal class OmniGlassBackdrop(
    /** Always a software bitmap: hardware captures are copied once here so the
     *  shader, the mesh crop and any test can treat it uniformly. */
    val image: ImageBitmap,
    /** Capture px per scope px; 1 means the capture is full resolution. */
    val scale: Float,
    val generation: Long,
) {
    val bitmap: Bitmap get() = image.asAndroidBitmap()

    val width: Int get() = image.width

    val height: Int get() = image.height
}

/**
 * Capture state shared by one [OmniGlassScope] and its panes.
 *
 * The handshake is deliberately split in two:
 *
 * 1. the draw phase (single threaded, latency critical) records the content into
 *    a [GraphicsLayer] at the downscaled size and bumps [requestToken];
 * 2. a coroutine owns the expensive part — `GraphicsLayer.toImageBitmap()` —
 *    and publishes the result, which invalidates the panes' *draw* only.
 *
 * Splitting it keeps the rasterisation out of the draw pass and lets a slow
 * device drop captures (the interval) instead of dropping frames.
 */
class OmniGlassCaptureController internal constructor(
    quality: OmniGlassQuality,
    enabled: Boolean,
) {
    internal var quality: OmniGlassQuality = quality
        set(value) {
            if (field == value) return
            field = value
            geometryDirty = true
        }

    internal var enabled: Boolean = enabled
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                geometryDirty = true
            } else {
                backdrop = null
            }
        }

    /** Panes currently attached; the scope renders no capture when this is 0. */
    internal var attachedPanels: Int = 0
        private set

    internal var geometryDirty: Boolean = true
        private set

    internal var lastCaptureMillis: Long = 0L
        private set

    internal var captures: Long = 0L
        private set

    /** Rasterisation failures; after a few the scope stops trying this session. */
    internal var captureFailures: Int = 0
        private set

    /** Published capture, read by panes inside their draw phase. */
    internal var backdrop: OmniGlassBackdrop? by mutableStateOf(null)
        private set

    /** Bumped by the draw phase; observed by the capture coroutine. */
    internal var requestToken: Long by mutableStateOf(0L)
        private set

    internal var requestedScale: Float = 1f
        private set

    /** The scope's live size in px; panes use it to clip their capture reads. */
    internal var scopeSizePx: Size = Size.Zero
        private set

    /**
     * The scope's bounds in SCREEN px, refreshed on every draw. The historical
     * field name is retained internally, but both capture and panes now use
     * localToScreen so a Popup/Dialog's separate window origin cannot corrupt
     * sampling. Panes subtract this origin to get position in the capture.
     */
    internal var scopeBoundsInWindow: Rect = Rect.Zero
        private set

    private var requestInFlight: Boolean = false

    internal fun noteScopeBounds(bounds: Rect) {
        if (bounds != scopeBoundsInWindow) scopeBoundsInWindow = bounds
    }

    internal fun attachPanel() {
        attachedPanels++
        geometryDirty = true
    }

    internal fun detachPanel() {
        if (attachedPanels > 0) attachedPanels--
        if (attachedPanels == 0) {
            // Nothing is looking at the backdrop any more: drop the pixels
            // instead of holding a screen-sized bitmap for an invisible pane.
            backdrop = null
        }
    }

    /** Ask for a fresh capture on the next frame (content or theme changed). */
    fun invalidate() {
        geometryDirty = true
    }

    internal fun noteScopeSize(sizePx: Size) {
        if (sizePx != scopeSizePx) {
            scopeSizePx = sizePx
            geometryDirty = true
        }
    }

    /** Pure decision; [nowMillis] comes from the frame so tests can drive it. */
    internal fun shouldCapture(nowMillis: Long): Boolean = !requestInFlight &&
        OmniGlassCaptureMath.shouldCapture(
            enabled = enabled,
            capturing = OmniGlassCaptureGuard.capturing,
            attachedSurfaces = attachedPanels,
            sizePx = scopeSizePx,
            nowMillis = nowMillis,
            lastCaptureMillis = lastCaptureMillis,
            intervalMs = quality.refreshIntervalMs,
            geometryChanged = geometryDirty,
        ) && captureFailures < MAX_FAILURES

    /**
     * Layer size and scale for the next capture, or null when the pane is too
     * small or the profile cannot afford one.
     */
    internal fun nextCaptureSize(): Pair<IntSize, Float>? {
        val size = scopeSizePx
        if (!OmniGlassCaptureMath.isDrawable(size)) return null
        val scale = OmniGlassCaptureMath.captureScale(
            size, quality.capturePixelBudget, quality.maxCaptureScale,
        )
        val target = OmniGlassCaptureMath.captureSize(size, scale)
        if (target.width <= 0 || target.height <= 0) return null
        return target to scale
    }

    /** Called by the capture node after it recorded the content into the layer. */
    internal fun requestCapture(scale: Float) {
        requestedScale = scale
        requestInFlight = true
        requestToken++
    }

    /**
     * Converts the recorded layer into a backdrop. Runs in a coroutine, never in
     * the draw pass: this is the only step that allocates and rasterises a full
     * bitmap, and the reason the scope has a refresh interval at all.
     */
    internal suspend fun completeCapture(layer: GraphicsLayer, nowMillis: Long) {
        val scale = requestedScale
        val image = try {
            layer.toImageBitmap()
        } catch (error: Throwable) {
            requestInFlight = false
            noteCaptureFailure()
            return
        }
        requestInFlight = false
        val software = toSoftware(image)
        if (software == null) {
            noteCaptureFailure()
            return
        }
        publish(software, scale, nowMillis)
    }

    /**
     * A capture from `GraphicsLayer.toImageBitmap()` is a hardware bitmap on
     * API 28+. Copying it once, here, keeps every downstream consumer (shader
     * input, mesh crop, tests) on a plain software bitmap and bounds the readback
     * to one per capture instead of one per pane.
     */
    private fun toSoftware(image: ImageBitmap): ImageBitmap? {
        val bitmap = image.asAndroidBitmap()
        if (bitmap.config != Bitmap.Config.HARDWARE) return image
        val copy = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (error: Throwable) {
            null
        } ?: return null
        return copy.asImageBitmap()
    }

    internal fun publish(image: ImageBitmap, scale: Float, nowMillis: Long) {
        generation++
        captures++
        lastCaptureMillis = nowMillis
        geometryDirty = false
        backdrop = OmniGlassBackdrop(image, scale, generation)
    }

    /**
     * Test hook: advances the capture clock and clears the dirty flag without
     * allocating or rasterising a bitmap (which needs a real Android device).
     */
    internal fun publishCaptureForTest(nowMillis: Long) {
        generation++
        captures++
        lastCaptureMillis = nowMillis
        geometryDirty = false
    }

    internal fun noteCaptureFailure() {
        requestInFlight = false
        captureFailures++
        geometryDirty = false
        lastCaptureMillis = 0L
    }

    internal fun release() {
        attachedPanels = 0
        backdrop = null
    }

    private var generation: Long = 0L

    private companion object {
        /** A device that fails three times will not start working on the fourth. */
        const val MAX_FAILURES = 3
    }
}
