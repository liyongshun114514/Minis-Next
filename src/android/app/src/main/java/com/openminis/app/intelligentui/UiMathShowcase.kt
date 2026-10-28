package com.openminis.app.intelligentui

import org.json.JSONArray
import org.json.JSONObject

/**
 * First-party math examples. Every node is local sample data and every value is
 * recomputed on device; nothing here is a live grade, quote, exam answer or CAS
 * result. The list covers each supported mode (formula has a second linear
 * algebra example), and all nodes pass UiMathSchema.validate.
 */
object UiMathShowcase {
    fun nodes(): List<JSONObject> = listOf(
        formula(),
        formulaMatrix(),
        functionPlot(),
        parametric(),
        polar(),
        implicit(),
        surface(),
        calculus(),
        equation(),
        matrix(),
        statistics(),
        probability(),
        geometry(),
        sequence(),
    )

    private fun base(id: String, mode: String, title: String, text: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "math")
            .put("mode", mode)
            .put("title", title)
            .put("text", "$text · 示例数据，本地计算")

    private fun slider(name: String, label: String, min: Double, max: Double, value: Double): JSONObject =
        JSONObject().put("name", name).put("label", label).put("min", min).put("max", max).put("value", value)

    private fun sliders(vararg items: JSONObject): JSONArray = JSONArray(items.toList())

    private fun rows(vararg row: DoubleArray): JSONArray =
        JSONArray(row.map { values -> JSONArray(values.toList()) })

    private fun step(formula: String, note: String): JSONObject =
        JSONObject().put("formula", formula).put("note", note)

    private fun formula(): JSONObject = base(
        "math_formula_quadratic",
        "formula",
        "公式排版：分数 / 根式 / 上下标 / 求和积分 / 矩阵",
        "二次公式与推导步骤；有限原生排版子集，不是完整 TeX 或 CAS",
    ).put("expression", "x = \\frac{-b \\pm \\sqrt{b^{2}-4ac}}{2a}")
        .put("result", "\\Delta = b^{2}-4ac")
        .put(
            "steps",
            JSONArray(
                listOf(
                    step("\\Delta = b^{2}-4ac", "判别式决定实根个数"),
                    step("x_{1,2} = \\frac{-b \\pm \\sqrt{\\Delta}}{2a}", "求根公式"),
                    step("\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}", "求和符号与上下限"),
                    step("\\int_{0}^{1} x^{2}\\,dx = \\frac{1}{3}", "定积分示意"),
                    step("\\begin{matrix} a & b \\\\ c & d \\end{matrix}", "矩阵排版"),
                ),
            ),
        )

    private fun formulaMatrix(): JSONObject = base(
        "math_formula_linear_algebra",
        "formula",
        "线性代数排版：矩阵与求和",
        "矩阵乘法分量式，展示矩阵、下标、求和与上标",
    ).put("expression", "C_{ij} = \\sum_{k=1}^{n} A_{ik}B_{kj}")
        .put("result", "\\begin{matrix} 1 & 2 \\\\ 3 & 4 \\end{matrix}")
        .put(
            "steps",
            JSONArray(
                listOf(
                    step("A = \\begin{matrix} a_{11} & a_{12} \\\\ a_{21} & a_{22} \\end{matrix}", "2×2 矩阵"),
                    step("C_{ij} = \\sum_{k=1}^{2} A_{ik}B_{kj}", "逐项相乘后求和"),
                ),
            ),
        )

    private fun functionPlot(): JSONObject = base(
        "math_function_plot",
        "function_plot",
        "函数图像：三角 + 对数组合",
        "拖动滑块实时改变振幅、频率与对数权重，点按图像探测函数值",
    ).put("expression", "a*sin(b*x)+c*ln(x^2+1)/10")
        .put("xMin", -6.2832).put("xMax", 6.2832)
        .put("yMin", -4.0).put("yMax", 4.0)
        .put(
            "sliders",
            sliders(
                slider("a", "振幅 a", 0.2, 3.0, 1.5),
                slider("b", "频率 b", 0.5, 3.0, 1.0),
                slider("c", "对数权重 c", -2.0, 2.0, 1.0),
            ),
        )

    private fun parametric(): JSONObject = base(
        "math_parametric",
        "parametric",
        "参数曲线：利萨如图形",
        "x(t) 与 y(t) 同时采样，点按曲线探测参数点",
    ).put("expression", "A*sin(3*t+pi/2)")
        .put("expressionY", "A*sin(2*t)")
        .put("tMin", 0.0).put("tMax", 6.2832)
        .put("xMin", -4.0).put("xMax", 4.0)
        .put("yMin", -4.0).put("yMax", 4.0)
        .put("sliders", sliders(slider("A", "幅度 A", 1.0, 4.0, 3.0)))

    private fun polar(): JSONObject = base(
        "math_polar",
        "polar",
        "极坐标：三叶玫瑰线",
        "r(θ) 按角度采样并转换为直角坐标，可调半径",
    ).put("expression", "A*cos(3*t)")
        .put("tMin", 0.0).put("tMax", 6.2832)
        .put("xMin", -4.0).put("xMax", 4.0)
        .put("yMin", -4.0).put("yMax", 4.0)
        .put("sliders", sliders(slider("A", "半径 A", 1.0, 4.0, 3.0)))

    private fun implicit(): JSONObject = base(
        "math_implicit_circle",
        "implicit",
        "隐式方程：圆",
        "零等值线绘制；把等号改为不等式可查看填充区域",
    ).put("expression", "x^2+y^2-r^2")
        .put("comparator", "=")
        .put("xMin", -3.0).put("xMax", 3.0)
        .put("yMin", -3.0).put("yMax", 3.0)
        .put("sliders", sliders(slider("r", "半径 r", 1.0, 3.0, 2.0)))

    private fun surface(): JSONObject = base(
        "math_surface",
        "surface3d",
        "三维曲面线框：sin(x)·cos(y)",
        "拖动旋转视角，滑块改变高度；线框为本地采样示意",
    ).put("expression", "k*sin(x)*cos(y)")
        .put("xMin", -3.1416).put("xMax", 3.1416)
        .put("yMin", -3.1416).put("yMax", 3.1416)
        .put("zMin", -2.0).put("zMax", 2.0)
        .put("azimuth", -35.0).put("elevation", 28.0)
        .put("sliders", sliders(slider("k", "高度 k", 0.5, 2.0, 1.0)))

    private fun calculus(): JSONObject = base(
        "math_calculus",
        "calculus",
        "微积分：数值导数与定积分",
        "在切点计算数值导数、在区间计算定积分并给出切线",
    ).put("expression", "A*sin(x)+0.2*x^2")
        .put("a", 0.0).put("b", 3.1416).put("x0", 1.0)
        .put("sliders", sliders(slider("A", "振幅 A", 0.5, 2.0, 1.0)))

    private fun equation(): JSONObject = base(
        "math_equation",
        "equation",
        "方程求根：三次方程",
        "在给定区间内数值搜索实根，不保证找到全部根",
    ).put("expression", "x^3-2*x-5=0")
        .put("a", 0.0).put("b", 3.0)

    private fun matrix(): JSONObject = base(
        "math_matrix",
        "matrix",
        "矩阵：行列式 / 逆 / 线性方程组",
        "3×3 数值矩阵，附带右端向量求唯一解",
    ).put("matrix", rows(doubleArrayOf(2.0, 1.0, -1.0), doubleArrayOf(-3.0, -1.0, 2.0), doubleArrayOf(-2.0, 1.0, 2.0)))
        .put("rhs", JSONArray(listOf(8.0, -11.0, -3.0)))

    private fun statistics(): JSONObject = base(
        "math_statistics",
        "statistics",
        "统计：均值 / 中位数 / 方差",
        "10 个本地样本，展示集中趋势与离散程度",
    ).put("values", JSONArray(listOf(12.0, 15.0, 11.0, 18.0, 20.0, 15.0, 14.0, 19.0, 16.0, 13.0)))

    private fun probability(): JSONObject = base(
        "math_probability",
        "probability",
        "概率：排列组合与二项分布",
        "C(n,k)、P(n,k) 及 P(X=k) 的本地数值结果",
    ).put("n", 10).put("k", 3).put("p", 0.5)

    private fun geometry(): JSONObject = base(
        "math_geometry",
        "geometry",
        "几何：可调三角形",
        "三条边由滑块控制，实时检查三角形不等式并计算面积与角度",
    ).put("shape", "triangle")
        .put("a", 3.0).put("b", 4.0).put("c", 5.0)
        .put(
            "sliders",
            sliders(
                slider("a", "边 a", 1.0, 8.0, 3.0),
                slider("b", "边 b", 1.0, 8.0, 4.0),
                slider("c", "边 c", 1.0, 8.0, 5.0),
            ),
        )

    private fun sequence(): JSONObject = base(
        "math_sequence",
        "sequence",
        "数列：斐波那契递推与前 N 项和",
        "逐项递推并累计部分和，附项折线示意",
    ).put("kind", "fibonacci")
        .put("first", 1.0).put("second", 1.0)
        .put("terms", 12)
}
