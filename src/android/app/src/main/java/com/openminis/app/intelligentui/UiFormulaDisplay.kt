package com.openminis.app.intelligentui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A deliberately small, native math typesetting subset. It is not TeX and it is
 * not a computer-algebra system: only the structures below are parsed, with
 * fixed depth/size budgets, and everything is rendered by Compose layout.
 *
 * Supported: plain symbols, groups, x^{...}, x_{...}, \frac{}{}, \sqrt{}/\sqrt[n]{},
 * \sum/\prod/\int with limits, 2..6 row matrices, a small Greek/operator table,
 * and \text{...}. Unsupported commands are rejected rather than shown raw.
 */
@Composable
internal fun UiFormulaView(
    source: String,
    modifier: Modifier = Modifier,
    tag: String? = null,
    fontSize: TextUnit = 20.sp,
) {
    val parsed = androidx.compose.runtime.remember(source) {
        runCatching { UiFormula.parse(source) }.fold(
            onSuccess = { it to null },
            onFailure = { null to (it.message ?: "Invalid formula") },
        )
    }
    val color = MaterialTheme.colorScheme.onSurface
    val tagged = if (tag == null) modifier else modifier.testTag(tag)
    if (parsed.second != null) {
        Text(
            parsed.second!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = if (tag == null) modifier.testTag("ui-math-formula-error") else modifier.testTag("$tag-error"),
        )
        return
    }
    Box(tagged.horizontalScroll(rememberScrollState())) {
        FormulaNodeView(parsed.first!!, fontSize, color)
    }
}

@Composable
private fun FormulaNodeView(node: FNode, size: TextUnit, color: Color) {
    when (node) {
        is FNode.FText -> Text(node.text, fontSize = size, color = color, fontFamily = FontFamily.Serif)
        is FNode.FRow -> Row(verticalAlignment = Alignment.CenterVertically) {
            node.items.forEach { FormulaNodeView(it, size, color) }
        }
        is FNode.FFrac -> Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
            FormulaNodeView(node.numerator, size * 0.86f, color)
            Box(Modifier.fillMaxWidth().height(1.dp).background(color))
            FormulaNodeView(node.denominator, size * 0.86f, color)
        }
        is FNode.FSqrt -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (node.index != null) {
                Column(horizontalAlignment = Alignment.End) {
                    FormulaNodeView(node.index, size * 0.55f, color)
                    Text("√", fontSize = size * 1.25f, color = color, fontFamily = FontFamily.Serif)
                }
            } else {
                Text("√", fontSize = size * 1.25f, color = color, fontFamily = FontFamily.Serif)
            }
            Column(Modifier.width(IntrinsicSize.Max)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(color))
                FormulaNodeView(node.body, size, color)
            }
        }
        is FNode.FSup -> Row(verticalAlignment = Alignment.Top) {
            FormulaNodeView(node.base, size, color)
            Box(Modifier.offset(y = (-0.30f * size.value).dp)) {
                FormulaNodeView(node.sup, size * 0.62f, color)
            }
        }
        is FNode.FSub -> Row(verticalAlignment = Alignment.Top) {
            FormulaNodeView(node.base, size, color)
            Box(Modifier.offset(y = (0.32f * size.value).dp)) {
                FormulaNodeView(node.sub, size * 0.62f, color)
            }
        }
        is FNode.FBig -> Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (node.sup != null) FormulaNodeView(node.sup, size * 0.58f, color)
                Text(
                    node.symbol,
                    fontSize = if (node.symbol == "lim") size * 0.95f else size * 1.65f,
                    color = color,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Serif,
                )
                if (node.sub != null) FormulaNodeView(node.sub, size * 0.58f, color)
            }
            if (node.body != null) {
                Box(Modifier.padding(start = 3.dp)) { FormulaNodeView(node.body, size, color) }
            }
        }
        is FNode.FMatrix -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("[", fontSize = size * 1.7f, color = color, fontFamily = FontFamily.Serif)
            Column(
                Modifier.padding(horizontal = 3.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                node.rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        row.forEach { cell -> FormulaNodeView(cell, size * 0.9f, color) }
                    }
                }
            }
            Text("]", fontSize = size * 1.7f, color = color, fontFamily = FontFamily.Serif)
        }
    }
}
