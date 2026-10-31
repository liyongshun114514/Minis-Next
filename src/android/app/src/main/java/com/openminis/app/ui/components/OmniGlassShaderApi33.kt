/*
 * Minis Next — Omni liquid glass, API 33+ RuntimeShader backend.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Provenance: the AGSL program it drives is a port of the FlClash liquid-glass
 * fragment shader reached through omnimind-ai/OmniBot commit
 * c4728736964245509404670ee992d3b658efaecd (ui/shaders/liquid_glass.frag),
 * GPLv3 for its FlClash-derived parts — upstream chen08209/FlClash
 * 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7. See OmniGlassShader.kt and
 * OmniBot's docs/third-party/flclash-NOTICE.md.
 *
 * This file is the only place that touches android.graphics.RuntimeShader (API
 * 33). It must stay isolated: on API 26–32 the class is never loaded and
 * nothing here is verified or resolved, which is what keeps the ART class
 * verifier from rejecting the glass component on older devices.
 */
package com.openminis.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath

/**
 * A compiled glass program. Declared outside the API 33 file so the renderer can
 * hold one without naming an API 33 type in its own signatures.
 */
internal interface OmniGlassProgramBackend {
    /** Draws [path] filled with the refracted capture [bitmap]. */
    fun draw(canvas: Canvas, path: Path, bitmap: Bitmap, uniforms: Map<String, FloatArray>)

    fun release()
}

/**
 * Compiled shader cache for one pane.
 *
 * Compiling AGSL costs tens of milliseconds, so the program is compiled once and
 * only its uniforms are rewritten per frame. The capture bitmap is swapped
 * underneath it whenever the scope publishes a new one.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class OmniGlassRuntimeShaderProgram : OmniGlassProgramBackend {
    private val shader: RuntimeShader = RuntimeShader(OmniGlassShaderSource.SOURCE)
    private val paint = Paint().apply {
        isAntiAlias = true
        isDither = true
    }
    private var bitmapRef: Bitmap? = null
    private var bitmapShader: BitmapShader? = null
    private var released = false

    override fun draw(
        canvas: Canvas,
        path: Path,
        bitmap: Bitmap,
        uniforms: Map<String, FloatArray>,
    ) {
        if (released || bitmap.isRecycled) return
        if (bitmapRef !== bitmap || bitmapShader == null) {
            bitmapRef = bitmap
            val built = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            bitmapShader = built
            shader.setInputShader(OmniGlassShaderSource.BACKDROP, built)
        }
        for ((name, values) in uniforms) {
            shader.setFloatUniform(name, values)
        }
        paint.shader = shader
        canvas.drawPath(path.asAndroidPath(), paint)
    }

    override fun release() {
        released = true
        paint.shader = null
        bitmapShader = null
        bitmapRef = null
    }
}

/** API 33+ entry point; callers must gate on [Build.VERSION.SDK_INT]. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object OmniGlassShaderApi33 {
    /**
     * Compiles the AGSL program once per process. A vendor compiler that rejects
     * it (or a device where runtime effects are unsupported) yields `null`, and
     * the caller degrades to the API 26 mesh renderer instead of throwing.
     */
    internal fun createProgram(): OmniGlassProgramBackend? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (Unsupported.supported == false) return null
        return try {
            OmniGlassRuntimeShaderProgram().also { Unsupported.supported = true }
        } catch (error: Throwable) {
            // AGSL compile errors surface as IllegalArgumentException; some
            // vendor stacks throw LinkageError. Either way: degrade, never crash.
            Unsupported.supported = false
            Unsupported.failure = error.javaClass.simpleName
            null
        }
    }

    /** Sticky capability flag so a failing device pays the compile attempt once. */
    internal object Unsupported {
        internal var supported: Boolean? = null
        internal var failure: String? = null
    }
}
