package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import com.openminis.app.ui.components.LocalOmniMorphRuntime
import com.openminis.app.ui.components.OmniMorphContainerBox
import com.openminis.app.ui.components.OmniMorphMotion
import com.openminis.app.ui.components.OmniMorphReturnOnDispose
import com.openminis.app.ui.components.OmniMorphSource
import com.openminis.app.ui.components.OmniMorphTarget
import com.openminis.app.ui.components.OmniSettingsTopBar
import com.openminis.app.ui.components.omniSettingsTopBarColors
import com.openminis.app.ui.components.omniMorphHeaderAlpha
import com.openminis.app.ui.components.omniMorphNavigate
import com.openminis.app.ui.components.omniMorphSource
import com.openminis.app.ui.components.rememberOmniMorphContainer
import com.openminis.app.ui.components.rememberOmniMorphRowSource
import com.openminis.app.ui.theme.OmniColors
import com.openminis.app.ui.theme.OmniDimens
import com.openminis.app.ui.theme.omniTextStyle

/**
 * Shared primitives for settings pages. Grouped-card layout (iOS inset-grouped style).
 *
 * Structure:
 *   SettingsScaffold(title, actions?) {
 *     SettingsSection(header?, footer?) { SettingsRow/SwitchRow/ValueRow(...) ... }
 *     SettingsSection(...) { ... }
 *   }
 */

// ─── Scaffold ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    // [T-android-settings-ui-md3] #11 onBack is nullable so an EDIT screen can
    // suppress the back arrow and use an explicit Cancel/Save action pair instead
    // (a back arrow + a "Cancel" action that both pop the screen is redundant and
    // semantically muddy). Non-edit screens keep passing a non-null onBack and get
    // the usual back arrow — unchanged.
    onBack: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    // [T-android-modeldetail-savecancel-ios-parity] Optional custom
    // navigation slot — e.g. a leading Cancel text action on modal-style
    // edit screens. When null, the slot falls back to the back arrow iff
    // onBack is set, so every existing caller renders unchanged.
    navigation: @Composable (() -> Unit)? = null,
    // [T-omnibot-ui-port] Kept for source compatibility with callers that pass
    // it: the Omni header is a LEADING 28sp large title on every settings page
    // (root and sub-page alike), so a centred 17sp bar is no longer reachable.
    @Suppress("UNUSED_PARAMETER") centerTitle: Boolean = true,
    floatingActionButton: @Composable (() -> Unit)? = null,
    scrollable: Boolean = true,
    // [T-android-storage-usage-cache] Optional bottom action bar (e.g. a
    // selection mode's actions). Null keeps every existing caller unchanged.
    bottomBar: @Composable (() -> Unit)? = null,
    // [T-omnibot-ui-port] Opt out of the row→page container transform (a page
    // that is not a navigation destination, e.g. a preview). Default on.
    enableContainerMorph: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    // [T-omnibot-ui-port] Container-transform wiring, all inside this component so
    // no caller and no navigation code has to change:
    //   • a tapped SettingsRow recorded its measured rect in OmniMorphBridge;
    //   • this page consumes it once and expands out of it over 300ms
    //     fastOutSlowIn (bar settles first, then the page body);
    //   • on dismissal (back, cancel, save, system back) the page records the
    //     return hand-off, so the page underneath grows back out of the same row.
    // With an app-level OmniMorphHost installed the host paints the container
    // above the NavHost and this page only registers its real bounds; without it
    // the page paints and clips the identical container itself.
    val morph = rememberOmniMorphContainer(enabled = enableContainerMorph)
    val runtime = LocalOmniMorphRuntime.current
    OmniMorphReturnOnDispose(morph)
    val pageColor = OmniColors.pageBackground
    val pageBounds = remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(pageBounds.value) {
        if (pageBounds.value != Rect.Zero) {
            runtime?.registerTarget(
                OmniMorphTarget(bounds = pageBounds.value, color = pageColor),
            )
        }
    }
    DisposableEffect(runtime) {
        onDispose { runtime?.clearTarget() }
    }

    val titleSlot: @Composable () -> Unit = {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val navigationSlot: @Composable () -> Unit = {
        when {
            navigation != null -> navigation()
            onBack != null -> IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = androidx.compose.ui.res.stringResource(
                        com.openminis.app.R.string.settings_back,
                    ),
                )
            }
        }
    }

    OmniMorphContainerBox(
        state = morph,
        drawContainer = runtime == null,
        modifier = Modifier.onGloballyPositioned { pageBounds.value = it.boundsInWindow() },
    ) {
        Scaffold(
            topBar = {
                OmniSettingsTopBar(
                    title = titleSlot,
                    navigationIcon = navigationSlot,
                    actions = { actions?.invoke() },
                    colors = omniSettingsTopBarColors(
                        containerColor = OmniColors.pageBackground,
                        titleContentColor = OmniColors.textPrimary,
                        navigationIconContentColor = OmniColors.textPrimary,
                        actionIconContentColor = OmniColors.textPrimary,
                    ),
                    modifier = Modifier.omniMorphHeaderAlpha(morph),
                )
            },
            floatingActionButton = { floatingActionButton?.invoke() },
            bottomBar = { bottomBar?.invoke() },
            // Transparent: the page background is painted by the container layer
            // underneath, so the expanding row is visible through the page while
            // it fades in.
            containerColor = Color.Transparent,
        ) { padding ->
            // T183: imePadding() shrinks the scroll container by the IME's
            // height while the keyboard is up, giving Modifier.bringIntoView()
            // (used by `bringIntoViewOnFocus`) a meaningful "above the
            // keyboard" rect to scroll a focused TextField into. Without it,
            // adjustResize + edge-to-edge leaves the scrollable column at
            // full height behind the IME and bringIntoView is a no-op.
            val baseMod = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
            Column(
                modifier = if (scrollable) baseMod.verticalScroll(rememberScrollState()) else baseMod,
                content = content,
            )
        }
    }
}

// ─── Switch ────────────────────────────────────────────────────────────────────

/**
 * [T-android-switch-row-height] A [Switch] that does not inflate the row it
 * sits in. Use this instead of Material's `Switch` inside any settings row.
 *
 * ## The problem it solves
 *
 * Compose applies `LocalMinimumInteractiveComponentSize` (48dp) to Switch, so
 * although the control DRAWS at 32dp it MEASURES at 48. Inside a row whose
 * height is `heightIn(min = 56.dp)` + 12dp vertical padding, that produced:
 *
 *     with a switch:  48 + 12 + 12  = 72dp   (heightIn never applies)
 *     without one:    30 + 12 + 12  = 54dp   -> clamped to 56dp
 *
 * Measured on a Pixel 6: switch rows rendered 190px and plain rows 147px, a
 * visible 16dp step between neighbouring rows of the same card. It read as
 * "the Encryption section has odd spacing" when in fact every switch row in
 * the app was taller than every non-switch row.
 *
 * iOS has no equivalent problem — SwiftUI's List sizes all its rows alike and
 * a Toggle does not stretch one — so this was an Android-only artefact rather
 * than an intentional difference.
 *
 * ## Why waiving the minimum is safe here
 *
 * The 48dp floor exists so a small control is still easy to hit. In these rows
 * the WHOLE ROW is clickable and at least 56dp tall, and tapping anywhere on it
 * toggles the same state, so the effective touch target grows rather than
 * shrinks. Material's own guidance allows waiving the minimum exactly when a
 * control is embedded in a larger interactive surface.
 *
 * Do NOT use this for a standalone switch that is the only touch target.
 */
@Composable
fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: androidx.compose.material3.SwitchColors = omniSwitchColors(),
) {
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            colors = colors,
        )
    }
}

/**
 * [T-omnibot-ui-port] Monochrome Omni switch: `accentPrimary` track with a
 * page-coloured thumb when on, `segmentTrack`/`segmentThumb` when off. No M3
 * primary tint, so the toggle reads as chrome rather than an accent.
 */
@Composable
fun omniSwitchColors(): androidx.compose.material3.SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = OmniColors.pageBackground,
    checkedTrackColor = OmniColors.accentPrimary,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = OmniColors.segmentThumb,
    uncheckedTrackColor = OmniColors.segmentTrack,
    uncheckedBorderColor = OmniColors.borderStrong,
    disabledCheckedThumbColor = OmniColors.pageBackground,
    disabledCheckedTrackColor = OmniColors.surfaceElevated,
    disabledUncheckedThumbColor = OmniColors.segmentThumb,
    disabledUncheckedTrackColor = OmniColors.segmentTrack,
)

// ─── Section ───────────────────────────────────────────────────────────────────

/**
 * A grouped section — optional 14sp tertiary label + flat 22dp card + optional
 * 12sp footer caption, separated by 26dp of page background.
 *
 * [T-omnibot-ui-port] Was an upper-cased 11sp `onSurfaceVariant` label over a
 * 14dp `surfaceContainerLow` card inside a 24dp top-padded Column. Now: 22dp
 * page inset, 22dp `surfacePrimary` card with no vertical padding (rows carry
 * their own 16dp), 14sp regular `textTertiary` label 16dp above the card, 12sp
 * tertiary footer 8dp below. Children (SettingsRow / SettingsSwitchRow / …)
 * stay unchanged and auto-inset their dividers.
 */
@Composable
fun SettingsSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = OmniDimens.SectionGap),
    ) {
        if (header != null) {
            Text(
                text = header,
                style = omniTextStyle(
                    size = OmniDimens.GroupLabelSize,
                    color = OmniColors.textTertiary,
                    lineHeight = 19.sp,
                ),
                modifier = Modifier.padding(
                    start = OmniDimens.PageHorizontal,
                    end = OmniDimens.PageHorizontal,
                    bottom = 16.dp,
                ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OmniDimens.PageHorizontal)
                .clip(RoundedCornerShape(OmniDimens.SurfaceRadius))
                .background(OmniColors.surfacePrimary),
            content = content,
        )
        if (footer != null) {
            Text(
                text = footer,
                style = omniTextStyle(
                    size = OmniDimens.FooterSize,
                    color = OmniColors.textTertiary,
                    lineHeight = 17.sp,
                ),
                modifier = Modifier.padding(
                    start = OmniDimens.PageHorizontal + 6.dp,
                    end = OmniDimens.PageHorizontal + 6.dp,
                    top = 8.dp,
                    bottom = 4.dp,
                ),
            )
        }
    }
}

// ─── Row primitives ────────────────────────────────────────────────────────────

/**
 * Generic row: left icon (optional colored circle) + title/subtitle + trailing slot + optional chevron.
 * Pass `showDivider = false` on the last row of a section.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    // [T-omnibot-ui-port] Omni rows are monochrome: the glyph is tinted
    // textPrimary by default (the pre-port brand chip per row was decoration only).
    // A caller that needs an accent — destructive actions, a product mark — passes
    // it explicitly; nothing else should.
    iconColor: Color = OmniColors.textPrimary,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    showDivider: Boolean = true,
    titleColor: Color = OmniColors.textPrimary,
    trailing: (@Composable () -> Unit)? = null,
    // [T-android-settings-ui-md3] #8 single-line List Item is 56dp; a caller with
    // two-line content (e.g. the model list: name + id) passes 72dp for the MD3
    // double-line height. Default keeps every other row at the single-line 56dp.
    minHeight: Dp = OmniDimens.RowMinHeight,
    // Long-press on a row that is also tappable (e.g. a skill's context menu).
    // Null keeps the plain clickable, so no other row changes.
    onLongClick: (() -> Unit)? = null,
    // [T-omnibot-ui-port] Rows that navigate a page hand the container transform
    // their measured rect; a row whose tap only toggles or opens a sheet passes
    // false so it never leaves a stray hand-off behind.
    animateContainer: Boolean = true,
) {
    // Measured in window coordinates, so the destination page (or the app-level
    // OmniMorphHost) can expand out of exactly this row even across the
    // status-bar/IME insets.
    val morphSource = rememberOmniMorphRowSource()
    val sourceColor = OmniColors.surfacePrimary
    val tap: (() -> Unit)? = onClick?.let { callback ->
        {
            if (animateContainer) {
                // Records the rect, then invokes the caller's callback unchanged.
                omniMorphNavigate(
                    source = morphSource.snapshot(
                        cornerRadius = OmniMorphMotion.RowSourceRadius,
                        color = sourceColor,
                    ),
                    onClick = callback,
                )
            } else {
                callback()
            }
        }
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .omniMorphSource(morphSource)
                // [T-android-settings-ui-md3] #1/#8 fixed List Item height so every
                // toggle/value/nav row in a section is uniform (was content-driven
                // → 24/54/72dp mix). heightIn(min) not height() so an unexpectedly
                // tall row can still grow; the symmetric 12dp vertical padding (was
                // effectively asymmetric once the 0.5dp divider was added/removed)
                // is what made a no-subtitle last row read ~50px shorter.
                .heightIn(min = minHeight)
                .then(
                    when {
                        tap != null && onLongClick != null ->
                            Modifier.combinedClickable(
                                role = Role.Button,
                                onClick = tap,
                                onLongClick = onLongClick,
                            )
                        tap != null -> Modifier.clickable(role = Role.Button, onClick = tap)
                        else -> Modifier
                    },
                )
                // [T-omnibot-ui-port] 18dp row inset / 16dp vertical, matching
                // OmniSettingsTile's EdgeInsets.symmetric(horizontal: 18, vertical: 16).
                .padding(horizontal = OmniDimens.RowHorizontal, vertical = OmniDimens.RowVertical),
            // #10 keep the trailing control (Switch/value) vertically centered
            // against the title — already centered, kept explicit.
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                // Omni rows carry a bare 20dp glyph on a 22dp leading box, not a
                // filled colour chip. The caller's iconColor is kept as the tint
                // so existing per-row colour semantics survive the port.
                Box(
                    modifier = Modifier.size(OmniDimens.LeadingIconBox),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(OmniDimens.LeadingIconSize),
                    )
                }
                Spacer(Modifier.width(OmniDimens.LeadingIconGap))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.5.dp),
            ) {
                Text(
                    text = title,
                    style = omniTextStyle(
                        size = OmniDimens.RowTitleSize,
                        color = titleColor,
                        lineHeight = 20.7.sp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = omniTextStyle(
                            size = OmniDimens.RowSubtitleSize,
                            color = OmniColors.textSecondary,
                            lineHeight = 17.4.sp,
                        ),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }

            if (showChevron) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    // Omni chevron: 18dp hairline in textTertiary.
                    tint = OmniColors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (showDivider) {
            // Hairline starts under the title (54dp = 18 + 22 + 14), or at the
            // row inset when there is no leading icon.
            val insetStart = if (icon != null) OmniDimens.DividerStartInset else OmniDimens.RowHorizontal
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = insetStart, end = OmniDimens.RowHorizontal)
                    .height(OmniDimens.DividerThickness)
                    .background(
                        OmniColors.borderSubtle.copy(
                            alpha = if (OmniColors.isDark) 0.55f else 0.75f,
                        ),
                    ),
            )
        }
    }
}

/** Title + Switch row. */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    iconColor: Color = OmniColors.textPrimary,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconColor = iconColor,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        showChevron = false,
        showDivider = showDivider,
        animateContainer = false,
        trailing = {
            SettingsSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = omniSwitchColors(),
            )
        },
    )
}

/** Title + right-aligned value text (tap opens picker/detail). */
@Composable
fun SettingsValueRow(
    title: String,
    value: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = OmniColors.textPrimary,
    valueColor: Color = OmniColors.textSecondary,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconColor = iconColor,
        onClick = onClick,
        showChevron = onClick != null,
        showDivider = showDivider,
        trailing = {
            Text(
                value,
                style = omniTextStyle(size = OmniDimens.RowValueSize, color = valueColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** Single-choice row (tap to select; shows check on selected). Used for radio-style lists. */
@Composable
fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // [T-android-settings-ui-md3] #1 match SettingsRow's 56dp min so
                // choice/radio rows line up with toggle/value rows in mixed lists.
                .heightIn(min = OmniDimens.RowMinHeight)
                .clickable(onClick = onSelect)
                .padding(horizontal = OmniDimens.RowHorizontal, vertical = OmniDimens.RowVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Text(
                title,
                style = omniTextStyle(size = OmniDimens.RowTitleSize, color = OmniColors.textPrimary),
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = OmniColors.accentPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = OmniDimens.DividerStartInset, end = OmniDimens.RowHorizontal)
                    .height(OmniDimens.DividerThickness)
                    .background(
                        OmniColors.borderSubtle.copy(
                            alpha = if (OmniColors.isDark) 0.55f else 0.75f,
                        ),
                    ),
            )
        }
    }
}

/**
 * Container for non-row content (sliders, segmented pickers, custom composables)
 * that still wants the grouped-card background.
 */
@Composable
fun SettingsCardBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .padding(horizontal = OmniDimens.RowHorizontal, vertical = 12.dp)
            .fillMaxWidth(),
        content = content,
    )
}
