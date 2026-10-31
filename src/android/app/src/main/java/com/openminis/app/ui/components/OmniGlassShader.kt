/*
 * Minis Next — Omni liquid glass shader (AGSL) and its uniform contract.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Provenance: this is a re-expression of the FlClash liquid-glass fragment
 * shader reached through omnimind-ai/OmniBot commit
 * c4728736964245509404670ee992d3b658efaecd — OmniBot's
 * ui/shaders/liquid_glass.frag and ui/lib/widgets/flclash/liquid_glass.dart,
 * whose FlClash-derived parts are GPLv3 — upstream chen08209/FlClash
 * 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7, pinned in OmniBot's
 * docs/third-party/flclash-NOTICE.md. Ported from Flutter/Impeller GLSL
 * (`FlutterFragCoord`, superellipse octant inset, circular bevel profile,
 * chromatic parting, saturation/brightness, rim highlight and hairline) to
 * Android AGSL with a Compose capture atlas; the atlas/level machinery, the
 * goo/menu/pill paths and the Flutter adapters are NOT ported. No OmniBot
 * AGPL-only source, brand asset or business runtime is copied.
 *
 * The uniform list below is the single source of truth: it is checked against
 * the declared `uniform`s of [SOURCE] by OmniGlassShaderContractTest, and the
 * API 33 backend only ever sets those names.
 */
package com.openminis.app.ui.components

/** One float uniform and how many components it carries. */
internal class OmniGlassUniform(val name: String, val size: Int) {
    override fun toString(): String = "$name[$size]"
}

internal object OmniGlassShaderSource {
    /** The `uniform shader` input: the downscaled backdrop capture. */
    const val BACKDROP = "uBackdrop"

    /**
     * AGSL (Android Graphics Shading Language) program.
     *
     * Coordinates: `fragCoord` is the pane's local px, so `uHalf` is the pane's
     * centre and `uHalf + p` the pane's own bounds. `uMap` maps those px onto the
     * capture bitmap: `capturePx = localPx * uMap.x + uMap.yz`.
     */
    const val SOURCE = """
uniform shader uBackdrop;
uniform float2 uHalf;
uniform float4 uCorners;
uniform float uExponent;
uniform float4 uMap;
uniform float4 uOptics;
uniform float4 uTint;
uniform float4 uLight;
uniform float uChroma;
uniform float uAlpha;

float2 toCapture(float2 p) {
    return p * uMap.x + float2(uMap.y, uMap.z);
}

float4 sampleTex(float2 p) {
    half4 h = uBackdrop.eval(p);
    return float4(h.r, h.g, h.b, h.a);
}

float4 sampleGlass(float2 p) {
    return sampleTex(toCapture(p));
}

// Nine taps: the centre plus two rings, which is enough because the capture is
// already downscaled by uMap.x and read with bilinear filtering.
float4 sampleFrost(float2 p, float sigma) {
    if (sigma < 0.35) {
        return sampleGlass(p);
    }
    float s = sigma;
    float4 acc = sampleGlass(p) * 0.2500;
    acc += sampleGlass(p + float2(s, 0.0)) * 0.09375;
    acc += sampleGlass(p - float2(s, 0.0)) * 0.09375;
    acc += sampleGlass(p + float2(0.0, s)) * 0.09375;
    acc += sampleGlass(p - float2(0.0, s)) * 0.09375;
    float d = s * 0.70710678;
    acc += sampleGlass(p + float2(d, d)) * 0.09375;
    acc += sampleGlass(p + float2(d, -d)) * 0.09375;
    acc += sampleGlass(p - float2(d, d)) * 0.09375;
    acc += sampleGlass(p - float2(d, -d)) * 0.09375;
    return acc;
}

float cornerRadius(float2 p) {
    if (p.x < 0.0) {
        return p.y < 0.0 ? uCorners.x : uCorners.w;
    }
    return p.y < 0.0 ? uCorners.y : uCorners.z;
}

// Inset from the rim (0 on the rim, positive inside) and the outward normal of
// the rounded superellipse. Along a straight side this is the distance to the
// nearer edge; in a corner octant it is the superellipse |u|^n + |v|^n = 1 in a
// frame of one corner radius, which is what makes the corner a squircle.
void shapeSample(float2 p, out float inset, out float2 normal) {
    float2 aq = abs(p);
    float r = min(cornerRadius(p), min(uHalf.x, uHalf.y));
    float n = max(uExponent, 2.0);
    if (r > 0.001 && aq.x > uHalf.x - r && aq.y > uHalf.y - r) {
        float2 u = (aq - float2(uHalf.x - r, uHalf.y - r)) / r;
        float un = pow(u.x, n);
        float vn = pow(u.y, n);
        float norm = pow(un + vn, 1.0 / n);
        float sn = max(norm, 0.001);
        float gx = pow(sn, 1.0 - n) * pow(u.x, n - 1.0);
        float gy = pow(sn, 1.0 - n) * pow(u.y, n - 1.0);
        float gl = sqrt(gx * gx + gy * gy);
        if (gl > 0.0001) {
            inset = r * (1.0 - norm) / gl;
            normal = float2(p.x < 0.0 ? -gx : gx, p.y < 0.0 ? -gy : gy) / gl;
            return;
        }
        inset = uHalf.x + uHalf.y;
        normal = float2(0.0, -1.0);
        return;
    }
    float dx = uHalf.x - aq.x;
    float dy = uHalf.y - aq.y;
    if (dx < dy) {
        inset = dx;
        normal = float2(p.x < 0.0 ? -1.0 : 1.0, 0.0);
    } else {
        inset = dy;
        normal = float2(0.0, p.y < 0.0 ? -1.0 : 1.0);
    }
}

half4 main(float2 fragCoord) {
    float2 p = fragCoord - uHalf;
    float inset;
    float2 normal;
    shapeSample(p, inset, normal);

    float bevel = max(uOptics.y, 0.5);
    float rise = 1.0 - clamp(inset / bevel, 0.0, 1.0);
    float profile = 1.0 - sqrt(max(1.0 - rise * rise, 0.0));
    float2 shift = -normal * (uOptics.x * profile);
    float blurPx = max(uOptics.z, 0.0);

    float4 base = sampleFrost(fragCoord + shift, blurPx);
    if (uChroma > 0.01 && inset < bevel) {
        float part = uChroma * 0.5 * rise;
        if (part > 0.001) {
            float4 red = sampleGlass(fragCoord + shift + normal * part);
            float4 blue = sampleGlass(fragCoord + shift - normal * part);
            base = float4(red.r, base.g, blue.b, max(base.a, max(red.a, blue.a)));
        }
    }

    float a = clamp(base.a, 0.0, 1.0);
    float3 color = base.rgb / max(a, 0.0001);
    float luma = dot(color, float3(0.2126, 0.7152, 0.0722));
    color = mix(float3(luma, luma, luma), color, uOptics.w) * uLight.x;

    float tintA = clamp(uTint.a, 0.0, 1.0);
    color = mix(color, uTint.rgb, tintA);

    // A dark hairline just inside the rim, as iOS draws it outside the bounds.
    float line = 1.0 - clamp(inset, 0.0, 1.0);
    color *= 1.0 - clamp(uLight.w, 0.0, 1.0) * line * 0.30;

    // Rim highlight: strongest along the top and bottom, fading across a band.
    float band = 1.0 - clamp(inset / max(uLight.z, 0.5), 0.0, 1.0);
    float facing = 0.35 + 0.65 * abs(normal.y);
    float lit = clamp(uLight.y * band * facing, 0.0, 1.0);
    color = mix(color, float3(1.0, 1.0, 1.0), lit);

    float coverage = clamp(a + tintA * (1.0 - a), 0.0, 1.0);
    coverage *= clamp(uAlpha, 0.0, 1.0);
    color = clamp(color, 0.0, 1.0);
    return half4(color.r * coverage, color.g * coverage, color.b * coverage, coverage);
}
"""

    /** Every float uniform of [SOURCE], in declaration order. */
    val floatUniforms: List<OmniGlassUniform> = listOf(
        OmniGlassUniform("uHalf", 2),
        OmniGlassUniform("uCorners", 4),
        OmniGlassUniform("uExponent", 1),
        OmniGlassUniform("uMap", 4),
        OmniGlassUniform("uOptics", 4),
        OmniGlassUniform("uTint", 4),
        OmniGlassUniform("uLight", 4),
        OmniGlassUniform("uChroma", 1),
        OmniGlassUniform("uAlpha", 1),
    )
}

/**
 * Packs the shader's uniforms. Pure: no Android type is touched, so the
 * contract test can compare the packed values with the declared uniforms.
 */
internal object OmniGlassShaderInputs {
    internal fun pack(
        width: Float,
        height: Float,
        corners: FloatArray,
        exponent: Float,
        captureScale: Float,
        captureOffsetX: Float,
        captureOffsetY: Float,
        optics: OmniGlassOptics,
    ): Map<String, FloatArray> {
        val w = if (width.isFinite() && width > 0f) width else 1f
        val h = if (height.isFinite() && height > 0f) height else 1f
        val scale = if (captureScale.isFinite() && captureScale > 0f) captureScale else 1f
        val offsetX = if (captureOffsetX.isFinite()) captureOffsetX.coerceIn(-1e6f, 1e6f) else 0f
        val offsetY = if (captureOffsetY.isFinite()) captureOffsetY.coerceIn(-1e6f, 1e6f) else 0f
        val map = LinkedHashMap<String, FloatArray>(8)
        map["uHalf"] = floatArrayOf(w * 0.5f, h * 0.5f)
        map["uCorners"] = floatArrayOf(
            corners.getOrElse(0) { 0f },
            corners.getOrElse(1) { 0f },
            corners.getOrElse(2) { 0f },
            corners.getOrElse(3) { 0f },
        )
        map["uExponent"] = floatArrayOf(OmniGlassGeometryMath.clampExponent(exponent))
        map["uMap"] = floatArrayOf(scale, offsetX, offsetY, 0f)
        map["uOptics"] = floatArrayOf(
            if (optics.refractionPx.isFinite()) optics.refractionPx.coerceIn(-64f, 64f) else 0f,
            if (optics.bevelPx.isFinite()) optics.bevelPx.coerceIn(0.5f, 256f) else 0.5f,
            if (optics.blurPx.isFinite()) optics.blurPx.coerceIn(0f, 64f) else 0f,
            if (optics.saturation.isFinite()) optics.saturation.coerceIn(0f, 4f) else 1f,
        )
        map["uTint"] = floatArrayOf(
            optics.tintRed.coerceIn(0f, 1f),
            optics.tintGreen.coerceIn(0f, 1f),
            optics.tintBlue.coerceIn(0f, 1f),
            optics.tintAlpha.coerceIn(0f, 1f),
        )
        map["uLight"] = floatArrayOf(
            if (optics.brightness.isFinite()) optics.brightness.coerceIn(0f, 4f) else 1f,
            if (optics.highlight.isFinite()) optics.highlight.coerceIn(0f, 1f) else 0f,
            if (optics.highlightBandPx.isFinite()) optics.highlightBandPx.coerceIn(0.5f, 512f) else 0.5f,
            if (optics.lineAlpha.isFinite()) optics.lineAlpha.coerceIn(0f, 1f) else 0f,
        )
        map["uChroma"] = floatArrayOf(
            if (optics.chromaPx.isFinite()) optics.chromaPx.coerceIn(0f, 32f) else 0f,
        )
        map["uAlpha"] = floatArrayOf(if (optics.alpha.isFinite()) optics.alpha.coerceIn(0f, 1f) else 1f)
        return map
    }
}
