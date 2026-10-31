package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.OmniColors
import com.openminis.app.ui.theme.OmniDimens
import com.openminis.app.ui.theme.omniTextStyle

/**
 * Single source of truth for Settings section-card visual rhythm.
 *
 * [T-omnibot-ui-port] Re-parameterised from the iOS SwiftUI Form measurements to
 * the OmniBot inset-grouped language (visual params only — the OmniBot source is
 * AGPL+commercial and was not copied; see OmniPalette.kt):
 *
 *   • 22dp page inset, 22dp flat card (no shadow, no tonal elevation)
 *   • rows separated by 0.65dp hairlines inset to the title column (54dp)
 *   • 26dp between groups, 16dp from a group label to its card
 *   • 14sp regular tertiary label instead of a semi-bold onSurface title
 *
 * All Settings screens (ProviderDetail / MountedFolders / ModelGroups /
 * EnvironmentVariables / …) MUST consume from this object rather than
 * hard-coding values inline; when a value changes here every screen updates
 * atomically.
 */
object SectionDesign {
    /** Outer column padding from screen edge. Flutter settings page inset = 22dp. */
    val ScreenHorizontalPadding = OmniDimens.PageHorizontal

    /** Card corner radius. Omni groups/cards use a flat 22dp surface. */
    val CardShape = RoundedCornerShape(22.dp)

    /** [T-omnibot-ui-port] Settings header height — the reference's 72dp toolbar
     *  (not Material3's fixed 64dp, and not the old 17sp centred bar). Every
     *  settings page, root or sub-page, uses this through `OmniSettingsTopBar`. */
    val HeaderHeight = 72.dp

    /** Large settings title: 28sp, w600, 1.2 line-height. Scaled by the user's
     *  font size through `omniTextStyle`, exactly like the 15/12sp row text. */
    val HeaderTitleSize = 28.sp
    val HeaderTitleLineHeight = 33.6.sp

    /** Bar controls keep a 16dp edge inset; the title then lands on the 22dp
     *  page gutter used by every section card below it. */
    val HeaderEdgeInset = 16.dp
    val HeaderTitleInset = 6.dp

    /** Row container transform: a row starts from the grouped card's 22dp radius
     *  (same measured value as [OmniMorphMotion.RowSourceRadius], which the
     *  transform itself consumes). */
    val RowSourceRadius = 22.dp

    /** Distance from screen top → first section header. */
    val FirstSectionTopGap = OmniDimens.PageVerticalTop

    /** Distance from previous section's footer → next section header. */
    val SectionTopGap = OmniDimens.SectionGap

    /** Distance from a section header → its card (Omni label padding-bottom). */
    val HeaderToCardGap = 16.dp

    /** Distance from card → its footer caption. */
    val CardToFooterGap = 8.dp

    /** Distance between adjacent rows / cards inside the same section.
     *  Omni groups are divider-separated, so there is no gap between rows. */
    val InterRowGap = 0.dp

    /** Standard row height inside a card (one- and two-line rows match). */
    val RowMinHeight = OmniDimens.RowMinHeight

    /** [T-android-settings-ui-md3] #3 Single-line text-field min height.
     *  Kept equal to [RowMinHeight] so an input lines up with the toggle/value
     *  rows around it. */
    val TextFieldMinHeight = 56.dp

    /** Horizontal padding inside the card (from card-inner-edge to row content). */
    val RowHorizontalPadding = OmniDimens.RowHorizontal

    /** Vertical padding inside a row (top + bottom each). */
    val RowVerticalPadding = OmniDimens.RowVertical

    /** Vertical padding wrapping the inner Column of a [SectionCard], so the
     *  first/last row don't hug the card's top/bottom edge. Omni rows already
     *  carry their own 16dp, so the card adds none. */
    val CardInnerVerticalPadding = 0.dp

    /** Inner-card divider thickness (OmniBot hairline). */
    val DividerThickness = OmniDimens.DividerThickness

    /** Inner-card divider left inset: 18 (row inset) + 22 (icon box) + 14 (gap),
     *  so the rule starts exactly under the row title. */
    val DividerStartInset = OmniDimens.DividerStartInset

    /** Card background — the flat Omni `surfacePrimary` (white / #202020).
     *  Deliberately NOT `surfaceContainerLow`: Omni cards are shadowless and
     *  untinted, and the Material scheme is aligned to the same table anyway. */
    @Composable
    @ReadOnlyComposable
    fun cardColor(): Color = OmniColors.surfacePrimary

    /** Section background (the grey behind the cards) — `pageBackground`
     *  (#F6F6F6 light / #151515 dark). */
    @Composable
    @ReadOnlyComposable
    fun screenBackgroundColor(): Color = OmniColors.pageBackground

    /** Inner-card divider colour — Omni fades the hairline on dark surfaces. */
    @Composable
    @ReadOnlyComposable
    fun dividerColor(): Color = OmniColors.borderSubtle.copy(
        alpha = if (OmniColors.isDark) 0.55f else 0.75f,
    )

    /** Footer caption colour (tertiary text below cards). */
    @Composable
    @ReadOnlyComposable
    fun footerColor(): Color = OmniColors.textTertiary
}

/**
 * Group label above a card. Omni uses 14sp regular tertiary text — quieter than
 * Material's labelMedium and deliberately not upper-cased.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = omniTextStyle(
            size = OmniDimens.GroupLabelSize,
            color = OmniColors.textTertiary,
            weight = FontWeight.Normal,
            lineHeight = 19.sp,
        ),
        modifier = modifier.padding(
            start = SectionDesign.ScreenHorizontalPadding,
            end = SectionDesign.ScreenHorizontalPadding,
            bottom = SectionDesign.HeaderToCardGap,
        ),
    )
}

/**
 * Footer caption below a card. Omni uses 12sp tertiary text, 8dp under the card.
 */
@Composable
fun SectionFooter(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = omniTextStyle(
            size = OmniDimens.FooterSize,
            color = SectionDesign.footerColor(),
            lineHeight = 17.5.sp,
        ),
        modifier = modifier.padding(
            start = SectionDesign.ScreenHorizontalPadding + 6.dp,
            end = SectionDesign.ScreenHorizontalPadding + 6.dp,
            top = SectionDesign.CardToFooterGap,
        ),
    )
}

/**
 * Container for one or more rows in a settings section: the flat Omni card
 * (shadowless, 22dp, surfacePrimary). Pass child rows; they divide themselves
 * with [SectionDivider] when stacked.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = SectionDesign.cardColor(),
        shape = SectionDesign.CardShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SectionDesign.ScreenHorizontalPadding),
    ) {
        Column(
            modifier = Modifier.padding(vertical = SectionDesign.CardInnerVerticalPadding),
            content = content,
        )
    }
}

/** Hairline between rows inside a [SectionCard], inset to the title column. */
@Composable
fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = SectionDesign.DividerStartInset),
        thickness = SectionDesign.DividerThickness,
        color = SectionDesign.dividerColor(),
    )
}

/**
 * Inline label placed directly above a [SectionTextField] (or
 * [SectionDropdown]) when several labelled fields share a section but each row
 * needs its own short caption — too small a scope for a full [SectionHeader].
 *
 * Horizontal padding is 0: callsites place this inside [SettingsCardBlock] (or a
 * manually padded Column) which already adds the 18dp inset. Adding our own
 * inset on top would double-indent the label relative to sibling
 * SectionHeader / description text / radio rows — see T353.
 */
@Composable
fun RowLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = omniTextStyle(
            size = OmniDimens.RowSubtitleSize,
            color = OmniColors.textSecondary,
        ),
        modifier = modifier.padding(bottom = 6.dp),
    )
}
