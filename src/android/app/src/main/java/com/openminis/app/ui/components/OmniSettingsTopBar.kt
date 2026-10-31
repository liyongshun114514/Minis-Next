package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.OmniColors
import com.openminis.app.ui.theme.omniTextStyle

/**
 * [T-omnibot-ui-port] Measured Omni settings header: 72dp tall, 28sp / w600
 * leading title aligned to the page's 22dp gutter.
 *
 * This is the reference settings header — the same bar on the settings root and on
 * every sub-page (one large-title header, not a 17sp centred Material bar). The
 * 28sp size goes through [omniTextStyle], so Settings → Appearance → text size and
 * the system font scale keep working exactly as they did with the 17sp title.
 *
 * ## Drop-in for `TopAppBar`
 *
 * The parameter list mirrors Material3's small `TopAppBar` — `title`,
 * `navigationIcon`, `actions`, `expandedHeight`, `windowInsets`, `colors` — so a
 * screen needs one import change and no other edit: its `actions`, `navigation`
 * slot and `TopAppBarDefaults.topAppBarColors(...)` keep working, and callbacks
 * are untouched.
 *
 * Material3 renders the 17sp centred/leading title at a fixed 64dp; that is the
 * exact thing this port replaces, so the bar is composed directly instead of
 * parameterising `TopAppBar`. Nothing else about Scaffold behaviour changes: it is
 * still a `topBar` slot with the same insets and the same measured height
 * contract.
 */
@Composable
fun OmniSettingsTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = OmniSettingsHeaderHeight,
    windowInsets: WindowInsets = WindowInsets.systemBars.only(
        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
    ),
    colors: OmniSettingsTopBarColors = omniSettingsTopBarColors(),
) {
    val titleColor = colors.titleContentColor
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.containerColor,
        contentColor = colors.titleContentColor,
    ) {
        Column(modifier = Modifier.windowInsetsPadding(windowInsets)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(expandedHeight)
                    .padding(horizontal = OmniSettingsHeaderEdgeInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides colors.navigationIconContentColor,
                ) {
                    navigationIcon()
                }
                ProvideTextStyle(
                    value = omniTextStyle(
                        size = OmniSettingsHeaderTitleSize,
                        color = titleColor,
                        weight = FontWeight.SemiBold,
                        lineHeight = OmniSettingsHeaderTitleLineHeight,
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = OmniSettingsHeaderTitleInset)
                            .semantics { heading() },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides titleColor) {
                            title()
                        }
                    }
                }
                CompositionLocalProvider(
                    LocalContentColor provides colors.actionIconContentColor,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
        }
    }
}

/**
 * [T-omnibot-ui-port] Bar colours, independent of Material3's experimental
 * `TopAppBarColors`: the settings header only needs these four roles, and keeping
 * the type local means calling the bar never forces an opt-in annotation on a
 * screen that has nothing to do with experimental Material APIs.
 */
@Immutable
class OmniSettingsTopBarColors(
    val containerColor: Color,
    val titleContentColor: Color,
    val navigationIconContentColor: Color,
    val actionIconContentColor: Color,
)

/** Omni defaults: page background, primary text, primary icons. */
@Composable
fun omniSettingsTopBarColors(
    containerColor: Color = OmniColors.pageBackground,
    titleContentColor: Color = OmniColors.textPrimary,
    navigationIconContentColor: Color = OmniColors.textPrimary,
    actionIconContentColor: Color = OmniColors.textPrimary,
): OmniSettingsTopBarColors = OmniSettingsTopBarColors(
    containerColor = containerColor,
    titleContentColor = titleContentColor,
    navigationIconContentColor = navigationIconContentColor,
    actionIconContentColor = actionIconContentColor,
)

/**
 * Measured Omni settings header metrics — thin delegations to [SectionDesign], the
 * settings token table, so the bar and the cards below it cannot drift apart.
 */
val OmniSettingsHeaderHeight: Dp = SectionDesign.HeaderHeight

/** Leading inset for the bar's controls; the title lands on the 22dp page gutter. */
val OmniSettingsHeaderEdgeInset: Dp = SectionDesign.HeaderEdgeInset

/** Extra inset between the back control and the title. */
val OmniSettingsHeaderTitleInset: Dp = SectionDesign.HeaderTitleInset

/** 28sp — the large settings title (scaled by the user's font size). */
val OmniSettingsHeaderTitleSize = SectionDesign.HeaderTitleSize

/** 1.2 line-height, as measured on the reference header. */
val OmniSettingsHeaderTitleLineHeight = SectionDesign.HeaderTitleLineHeight
