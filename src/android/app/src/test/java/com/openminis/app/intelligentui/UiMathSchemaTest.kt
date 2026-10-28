package com.openminis.app.intelligentui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM schema tests: no Compose, no Android, no network. */
class UiMathSchemaTest {
    private fun node(mode: String): JSONObject = JSONObject()
        .put("id", "n_$mode")
        .put("type", "math")
        .put("mode", mode)
        .put("title", "t")
        .put("text", "x")

    private fun plot(): JSONObject = node("function_plot")
        .put("expression", "a*x")
        .put("xMin", -1.0).put("xMax", 1.0)
        .put("yMin", -1.0).put("yMax", 1.0)

    private fun slider(name: String, min: Double, max: Double, value: Double, label: String = "p"): JSONObject =
        JSONObject().put("name", name).put("label", label).put("min", min).put("max", max).put("value", value)

    private fun withSliders(vararg sliders: JSONObject): JSONObject =
        plot().put("sliders", JSONArray(sliders.toList()))

    private fun rejected(n: JSONObject) {
        assertThrows(IllegalArgumentException::class.java) { UiMathSchema.validate(n) }
    }

    @Test fun showcaseHasFourteenExamplesCoveringEveryDeclaredMode() {
        val nodes = UiMathShowcase.nodes()
        assertEquals(14, nodes.size)
        assertEquals(UiMathSchema.modes, nodes.map { it.getString("mode") }.toSet())
        assertEquals(13, UiMathSchema.modes.size)
        nodes.forEach { UiMathSchema.validate(it) }
        val ids = nodes.map { it.getString("id") }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(nodes.all { it.getString("type") == "math" })
        assertTrue(nodes.all { it.getString("text").contains("示例") })
    }

    @Test fun negativeSliderDomainIsAccepted() {
        UiMathSchema.validate(withSliders(slider("a", -10.0, -1.0, -5.0)))
        UiMathSchema.validate(withSliders(slider("a", -10.0, 2.0, -5.0)))
        UiMathSchema.validate(withSliders(slider("a", -2.0, -0.5, -1.0)))
    }

    @Test fun sliderValueOutsideItsOwnRangeIsRejected() {
        rejected(withSliders(slider("a", -10.0, -1.0, -0.5)))
        rejected(withSliders(slider("a", -10.0, -1.0, 0.0)))
    }

    @Test fun sliderBudgetAndNamesAreStrict() {
        rejected(withSliders(*(1..7).map { slider("p$it", 0.0, 1.0, 0.5) }.toTypedArray()))
        rejected(withSliders(slider("x", 0.0, 1.0, 0.5)))
        rejected(withSliders(slider("pi", 0.0, 1.0, 0.5)))
        rejected(withSliders(slider("tau", 0.0, 1.0, 0.5)))
        rejected(withSliders(slider("1bad", 0.0, 1.0, 0.5)))
        rejected(withSliders(slider("a", 0.0, 1.0, 0.5), slider("a", 0.0, 2.0, 1.0)))
        rejected(withSliders(slider("a", 1.0, 1.0, 1.0)))
        rejected(withSliders(slider("a", 0.0, 2.0, 0.5, label = "x".repeat(41))))
    }

    @Test fun commonTextLimitsMatchTheGeneralCatalog() {
        val tooLongTitle = node("formula").put("expression", "x").put("title", "t".repeat(201))
        rejected(tooLongTitle)
        val tooLongText = node("formula").put("expression", "x").put("text", "t".repeat(4001))
        rejected(tooLongText)
        UiMathSchema.validate(node("formula").put("expression", "x").put("title", "t".repeat(200)).put("text", "t".repeat(4000)))
    }

    @Test fun unknownAndWrongTypedFieldsAreRejected() {
        rejected(node("formula").put("expression", "x").put("mystery", 1))
        rejected(plot().put("expression", "x").put("xMin", "0"))
        rejected(plot().put("expression", "x").put("sliders", JSONObject()))
        rejected(node("formula").put("expression", 12))
        rejected(node("formula").put("type", "chart").put("expression", "x"))
        rejected(node("nope").put("expression", "x"))
    }

    @Test fun expressionLengthAndCharacterBudget() {
        rejected(plot().put("expression", "x".repeat(513)))
        rejected(plot().put("expression", "x; Runtime.exec(1)"))
        rejected(plot().put("expression", "((x)"))
        rejected(plot().put("expression", "x)("))
        UiMathSchema.validate(plot().put("expression", "x"))
    }

    @Test fun domainBoundsMustBeOrderedAndFinite() {
        rejected(plot().put("xMin", 1.0).put("xMax", 1.0))
        rejected(plot().put("xMin", 2.0).put("xMax", 1.0))
        rejected(plot().put("xMin", -1e7))
        val surface = node("surface3d").put("expression", "x+y")
            .put("xMin", 0.0).put("xMax", 1.0).put("yMin", 0.0).put("yMax", 1.0)
            .put("zMin", 2.0).put("zMax", 1.0)
        rejected(surface)
    }

    @Test fun implicitComparatorIsFiniteAndBounded() {
        val base = node("implicit").put("expression", "x^2+y^2-1")
            .put("xMin", -2.0).put("xMax", 2.0).put("yMin", -2.0).put("yMax", 2.0)
        UiMathSchema.validate(base.put("comparator", "="))
        UiMathSchema.validate(base.put("comparator", "<="))
        rejected(base.put("comparator", "!="))
        rejected(base.put("comparator", "=="))
    }

    @Test fun matrixMustBeSquareAndAtMostSix() {
        fun matrix(rows: List<List<*>>) = node("matrix").put(
            "matrix",
            JSONArray(rows.map { row -> JSONArray(row) }),
        )
        UiMathSchema.validate(matrix(listOf(listOf(1.0, 2.0), listOf(3.0, 4.0))).put("rhs", JSONArray(listOf(1.0, 2.0))))
        rejected(matrix(listOf(listOf(1.0, 2.0), listOf(3.0))))
        rejected(matrix(List(7) { List(7) { 1.0 } }))
        rejected(matrix(listOf(listOf(1.0, 2.0), listOf(1.0, 2.0))).put("rhs", JSONArray(listOf(1.0))))
        rejected(matrix(listOf(listOf(1.0, 2.0), listOf(3.0, "4"))))
    }

    @Test fun probabilityStatisticsAndSequenceCaps() {
        UiMathSchema.validate(node("probability").put("n", 10).put("k", 3).put("p", 0.5))
        rejected(node("probability").put("n", 10).put("k", 11).put("p", 0.5))
        rejected(node("probability").put("n", 1001).put("k", 1).put("p", 0.5))
        rejected(node("probability").put("n", 10).put("k", 2).put("p", 1.5))
        UiMathSchema.validate(node("statistics").put("values", JSONArray(List(64) { it.toDouble() })))
        rejected(node("statistics").put("values", JSONArray(List(65) { it.toDouble() })))
        UiMathSchema.validate(node("sequence").put("kind", "fibonacci").put("first", 1.0).put("second", 1.0).put("terms", 40))
        rejected(node("sequence").put("kind", "fibonacci").put("first", 1.0).put("second", 1.0).put("terms", 41))
        UiMathSchema.validate(node("sequence").put("kind", "custom").put("first", 1.0).put("terms", 5).put("expression", "a+n"))
        rejected(node("sequence").put("kind", "custom").put("first", 1.0).put("terms", 5).put("expression", "a+n").put("difference", 2.0))
    }

    @Test fun calculusAndEquationNeedARealInterval() {
        UiMathSchema.validate(node("calculus").put("expression", "sin(x)").put("a", 0.0).put("b", 1.0).put("x0", 0.5))
        rejected(node("calculus").put("expression", "sin(x)").put("a", 1.0).put("b", 1.0).put("x0", 1.0))
        rejected(node("calculus").put("expression", "sin(x)").put("a", 0.0).put("b", 1.0).put("x0", 2.0))
        UiMathSchema.validate(node("equation").put("expression", "x^2-4=0").put("a", -3.0).put("b", 3.0))
        rejected(node("equation").put("expression", "x^2-4").put("a", -3.0).put("b", 3.0))
        rejected(node("equation").put("expression", "x^2-4=0=1").put("a", -3.0).put("b", 3.0))
    }

    @Test fun geometryRejectsDegenerateShapes() {
        UiMathSchema.validate(node("geometry").put("shape", "triangle").put("a", 3.0).put("b", 4.0).put("c", 5.0))
        rejected(node("geometry").put("shape", "triangle").put("a", 1.0).put("b", 2.0).put("c", 4.0))
        rejected(node("geometry").put("shape", "triangle").put("a", 3.0).put("b", 4.0).put("c", 5.0).put("r", 2.0))
        UiMathSchema.validate(node("geometry").put("shape", "circle").put("r", 2.0))
        rejected(node("geometry").put("shape", "circle").put("r", 0.0))
        rejected(node("geometry").put("shape", "circle").put("r", 2.0).put("a", 1.0))
    }

    @Test fun formulaSubsetParsesStructuresWithoutCompose() {
        listOf(
            "\\frac{a}{b}",
            "\\sqrt{x}",
            "\\sqrt[3]{x}",
            "x^{2}+a_{n}",
            "\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}",
            "\\int_{0}^{1} x^{2}\\,dx = \\frac{1}{3}",
            "\\begin{matrix} a & b \\\\ c & d \\end{matrix}",
            "\\text{速度} = \\frac{s}{t}",
            "\\Delta = b^{2}-4ac",
        ).forEach { UiFormula.validate(it) }
    }

    @Test fun malformedFormulasAreRejected() {
        listOf(
            "\\frac{a}",
            "\\frac{}{b}",
            "\\sqrt{}",
            "x^{}",
            "\\unknown{x}",
            "\\begin{matrix} a & b \\end{matrix}",
            "\\begin{matrix} a & b \\\\ c \\end{matrix}",
            "\\begin{matrix} " + " & ".repeat(7) + " \\end{matrix}",
            "{x",
            "x}",
        ).forEach {
            assertThrows(it, IllegalArgumentException::class.java) { UiFormula.validate(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { UiFormula.validate("x".repeat(513)) }
    }
}
