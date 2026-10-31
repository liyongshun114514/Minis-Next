package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.OmniColors
import com.openminis.app.ui.theme.OmniDimens
import com.openminis.app.ui.theme.omniTextStyle

/**
 * Inset-grouped section: a quiet 14sp label above a flat 22dp surface card
 * containing the caller's [content] rows, with 26dp of air after the group.
 * Pair with [SettingsRowDivider] between rows so dividers land under the row
 * title rather than touching the card's rounded edge.
 *
 * [T-omnibot-ui-port] Previously an upper-cased 11sp `primary` label over a
 * 16dp card tinted with `colorScheme.surface`. Omni's group label is 14sp
 * regular `textTertiary` in sentence case, and the card is the shadowless
 * `surfacePrimary` at 22dp; both now come from the shared [OmniDimens] /
 * [OmniColors] table so this component and [SectionCard] can't drift.
 * Signature unchanged (45 call sites).
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OmniDimens.PageHorizontal),
    ) {
        Text(
            text = title,
            style = omniTextStyle(
                size = OmniDimens.GroupLabelSize,
                color = OmniColors.textTertiary,
                weight = FontWeight.Normal,
                lineHeight = 19.sp,
            ),
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Surface(
            shape = RoundedCornerShape(OmniDimens.SurfaceRadius),
            color = OmniColors.surfacePrimary,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
        Spacer(modifier = Modifier.height(OmniDimens.SectionGap))
    }
}

/**
 * Hairline for rows inside a [SettingsSection] / [SectionCard]. Inset to the
 * row title column (18 + 22 + 14 = 54dp) so the line starts under the text and
 * stops before the rounded edge.
 */
@Composable
fun SettingsRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = OmniDimens.DividerStartInset, end = OmniDimens.RowHorizontal),
        thickness = OmniDimens.DividerThickness,
        color = OmniColors.borderSubtle.copy(alpha = if (OmniColors.isDark) 0.55f else 0.75f),
    )
}
