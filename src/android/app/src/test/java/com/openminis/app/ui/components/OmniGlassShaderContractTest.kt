/*
 * Minis Next — AGSL uniform contract test.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * A uniform whose name or arity disagrees with `RuntimeShader` does not fail
 * loudly on a device: `setFloatUniform` throws for a wrong arity, and an unset
 * uniform silently reads zero, which would turn the refraction off while still
 * costing a full-screen shader. These assertions run on the JVM and compare the
 * declared program text with what the renderer actually sets.
 */
package com.openminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniGlassShaderContractTest {

    private val declared: List<Pair<String, Int>> by lazy {
        val pattern = Regex("""^uniform\s+(float|float2|float3|float4|half|half2|half3|half4)\s+([A-Za-z_][A-Za-z0-9_]*)\s*;""", RegexOption.MULTILINE)
        pattern.findAll(OmniGlassShaderSource.SOURCE).map { match ->
            val size = when (match.groupValues[1]) {
                "float", "half" -> 1
                "float2", "half2" -> 2
                "float3", "half3" -> 3
                else -> 4
            }
            match.groupValues[2] to size
        }.toList()
    }

    @Test
    fun theShaderDeclaresExactlyTheProgramsUniforms() {
        val expected = OmniGlassShaderSource.floatUniforms.map { it.name to it.size }
        assertEquals("declared $declared", expected, declared)
    }

    @Test
    fun theShaderTakesTheBackdropAsAnInputShader() {
        assertTrue(
            "the backdrop must be a sampler input, not a float uniform",
            OmniGlassShaderSource.SOURCE.contains(
                "uniform shader ${OmniGlassShaderSource.BACKDROP};",
            ),
        )
        assertTrue(OmniGlassShaderSource.SOURCE.contains("${OmniGlassShaderSource.BACKDROP}.eval("))
    }

    @Test
    fun theEntryPointIsAValidAgsFragmentMain() {
        assertTrue(
            "RuntimeShader needs half4 main(float2)",
            Regex("""half4\s+main\s*\(\s*float2\s+[A-Za-z_][A-Za-z0-9_]*\s*\)""")
                .containsMatchIn(OmniGlassShaderSource.SOURCE),
        )
    }

    @Test
    fun theProgramTextIsStructurallySound() {
        val text = OmniGlassShaderSource.SOURCE
        assertEquals("unbalanced braces", text.count { it == '{' }, text.count { it == '}' })
        assertEquals("unbalanced parentheses", text.count { it == '(' }, text.count { it == ')' })
        assertFalse("AGSL has no preprocessor includes", text.contains("#include"))
        assertFalse("no dynamic loops: they must be unrolled", text.contains("for ("))
        assertFalse("no while loops", text.contains("while ("))
        // Trailing semicolons on every declaration line keeps the parser happy.
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("uniform ") || trimmed.startsWith("return ")) {
                assertTrue("missing ';': $trimmed", trimmed.endsWith(";"))
            }
        }
    }

    @Test
    fun everyDeclaredUniformIsActuallyUsed() {
        // Everything except the uniform declarations themselves.
        val body = OmniGlassShaderSource.SOURCE.lines()
            .filterNot { it.trim().startsWith("uniform ") }
            .joinToString("\n")
        for ((name, _) in declared) {
            val uses = Regex("""\b$name\b""").findAll(body).count()
            assertTrue("uniform $name is declared but never used", uses >= 1)
        }
    }

    @Test
    fun packedUniformsMatchTheDeclaredArity() {
        val optics = OmniGlassOptics(
            refractionPx = 6f,
            bevelPx = 14f,
            blurPx = 10f,
            saturation = 1.35f,
            brightness = 1.02f,
            tintRed = 0.97f,
            tintGreen = 0.97f,
            tintBlue = 0.98f,
            tintAlpha = 0.72f,
            highlight = 0.42f,
            highlightBandPx = 12f,
            lineAlpha = 0.45f,
            chromaPx = 0.4f,
            alpha = 1f,
        )
        val packed = OmniGlassShaderInputs.pack(
            width = 320f,
            height = 62f,
            corners = floatArrayOf(31f, 31f, 31f, 31f),
            exponent = 5f,
            captureScale = 0.5f,
            captureOffsetX = 30f,
            captureOffsetY = 120f,
            optics = optics,
        )
        assertEquals(declared.map { it.first }, packed.keys.toList())
        for ((name, size) in declared) {
            val values = packed.getValue(name)
            assertEquals("arity of $name", size, values.size)
            for (value in values) {
                assertTrue("$name holds a non-finite value: ${values.toList()}", value.isFinite())
            }
        }
    }

    @Test
    fun packedValuesDescribeThePaneAndTheCapture() {
        val optics = OmniGlassOptics(
            refractionPx = 6f,
            bevelPx = 14f,
            blurPx = 10f,
            saturation = 1.35f,
            brightness = 1.02f,
            tintRed = 0.1f,
            tintGreen = 0.2f,
            tintBlue = 0.3f,
            tintAlpha = 0.5f,
            highlight = 0.42f,
            highlightBandPx = 12f,
            lineAlpha = 0.45f,
            chromaPx = 0.4f,
            alpha = 0.75f,
        )
        val packed = OmniGlassShaderInputs.pack(
            width = 320f,
            height = 62f,
            corners = floatArrayOf(31f, 31f, 31f, 31f),
            exponent = 5f,
            captureScale = 0.5f,
            captureOffsetX = 15f,
            captureOffsetY = -8f,
            optics = optics,
        )
        assertArrayEquals(floatArrayOf(160f, 31f), packed.getValue("uHalf"))
        assertArrayEquals(floatArrayOf(31f, 31f, 31f, 31f), packed.getValue("uCorners"))
        assertArrayEquals(floatArrayOf(5f), packed.getValue("uExponent"))
        // capturePx = localPx * scale + offset, exactly as the Kotlin mapping does.
        assertArrayEquals(floatArrayOf(0.5f, 15f, -8f, 0f), packed.getValue("uMap"))
        assertArrayEquals(floatArrayOf(6f, 14f, 10f, 1.35f), packed.getValue("uOptics"))
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.5f), packed.getValue("uTint"))
        assertArrayEquals(floatArrayOf(1.02f, 0.42f, 12f, 0.45f), packed.getValue("uLight"))
        assertArrayEquals(floatArrayOf(0.4f), packed.getValue("uChroma"))
        assertArrayEquals(floatArrayOf(0.75f), packed.getValue("uAlpha"))
    }

    @Test
    fun packingClampsHostileInput() {
        val optics = OmniGlassOptics(
            refractionPx = Float.NaN,
            bevelPx = -3f,
            blurPx = -1f,
            saturation = 99f,
            brightness = -5f,
            tintRed = 2f,
            tintGreen = -1f,
            tintBlue = 0.5f,
            tintAlpha = 4f,
            highlight = 3f,
            highlightBandPx = 0f,
            lineAlpha = -1f,
            chromaPx = -2f,
            alpha = 7f,
        )
        val packed = OmniGlassShaderInputs.pack(
            width = 0f,
            height = Float.NaN,
            corners = floatArrayOf(),
            exponent = Float.NaN,
            captureScale = 0f,
            captureOffsetX = Float.NaN,
            captureOffsetY = 0f,
            optics = optics,
        )
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), packed.getValue("uHalf"))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), packed.getValue("uCorners"))
        // NaN depth is zeroed, a negative bevel becomes the thinnest real one,
        // a negative frost becomes none and saturation is clamped to 4.
        assertArrayEquals(floatArrayOf(0f, 0.5f, 0f, 4f), packed.getValue("uOptics"))
        assertEquals(4f, packed.getValue("uOptics")[3], 1e-6f)
        assertEquals(0f, packed.getValue("uLight")[0], 1e-6f)
        assertEquals(0.5f, packed.getValue("uLight")[2], 1e-6f)
        assertEquals(1f, packed.getValue("uTint")[0], 1e-6f)
        assertEquals(0f, packed.getValue("uTint")[1], 1e-6f)
        assertEquals(1f, packed.getValue("uTint")[3], 1e-6f)
        assertEquals(1f, packed.getValue("uAlpha")[0], 1e-6f)
        for ((name, values) in packed) {
            for (value in values) assertTrue("$name is not finite", value.isFinite())
        }
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals("expected ${expected.toList()}, got ${actual.toList()}", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("index $i", expected[i], actual[i], 1e-5f)
        }
    }
}
