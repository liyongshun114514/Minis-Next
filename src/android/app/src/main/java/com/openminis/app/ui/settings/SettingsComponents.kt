package com.openminis.app.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    // [T-android-modeldetail-savecancel-ios-parity] Center the title
    // (CenterAlignedTopAppBar) for iOS-modal-style edit screens. Default
    // keeps the start-aligned TopAppBar.
    centerTitle: Boolean = false,
    floatingActionButton: @Composable (() -> Unit)? = null,
    scrollable: Boolean = true,
    // [T-android-storage-usage-cache] Optional bottom action bar (e.g. a
    // selection mode's actions). Null keeps every existing caller unchanged.
    bottomBar: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = if (scrollable) rememberScrollState() else null
    val backdrop = if (scrollable && isRuntimeShaderSupported()) rememberLayerBackdrop() else null
    val pageColor = MiuixTheme.colorScheme.surface
    val fadeInDistance = with(LocalDensity.current) { 24.dp.toPx() }
    Scaffold(
        topBar = {
            SettingsGlassTopBar(
                title = title,
                backdrop = backdrop,
                overlap = { ((scrollState?.value ?: 0) / fadeInDistance).coerceIn(0f, 1f) },
                centerTitle = centerTitle,
                onBack = onBack,
                navigation = navigation,
                actions = actions,
            )
        },
        floatingActionButton = { floatingActionButton?.invoke() },
        bottomBar = { bottomBar?.invoke() },
        containerColor = pageColor,
    ) { padding ->
        val direction = LocalLayoutDirection.current
        val viewportPadding = PaddingValues(
            start = padding.calculateStartPadding(direction),
            end = padding.calculateEndPadding(direction),
            bottom = padding.calculateBottomPadding(),
        )
        val capture = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier
        Column(
            modifier = Modifier.fillMaxSize()
                .then(capture)
                .background(pageColor)
                .padding(viewportPadding)
                .consumeWindowInsets(padding)
                // Keep the focused TextField's bring-into-view viewport above the keyboard.
                .imePadding()
                .then(if (scrollState != null) Modifier.verticalScroll(scrollState) else Modifier)
                // Inside the scroll viewport so rows actually travel behind the glass.
                .padding(top = padding.calculateTopPadding())
                .then(if (scrollable) Modifier.padding(bottom = 16.dp) else Modifier),
            content = content,
        )
    }
}

/** Page grouping only. The Miuix scaffold uses surface and Card uses surfaceContainer. */
@Composable
fun SettingsSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = 16.dp)) {
        if (header != null) SmallTitle(text = header)
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), content = content)
        if (footer != null) {
            Text(
                text = footer,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 4.dp),
            )
        }
    }
}

/** Bind app navigation, custom trailing content and long press to native preference rows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = MiuixTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    showDivider: Boolean = true,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
    minHeight: Dp = 56.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val leading = if (icon != null) settingsRowIcon(icon, iconColor) else null
    val colors = BasicComponentDefaults.titleColor(color = titleColor)
    val modifier = Modifier.heightIn(min = minHeight)
    if (showChevron && onLongClick == null) {
        ArrowPreference(
            title = title,
            summary = subtitle,
            modifier = modifier,
            titleColor = colors,
            startAction = leading,
            endActions = { trailing?.invoke() },
            onClick = onClick,
        )
    } else {
        val withLongPress = onClick != null && onLongClick != null
        val endActions: (@Composable RowScope.() -> Unit)? = if (trailing != null || showChevron) {
            {
                trailing?.invoke()
                if (showChevron) {
                    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.size(10.dp, 16.dp).graphicsLayer { scaleX = if (rtl) -1f else 1f },
                    )
                }
            }
        } else null
        BasicComponent(
            title = title,
            titleColor = colors,
            summary = subtitle,
            startAction = leading,
            endActions = endActions,
            modifier = if (withLongPress) modifier.combinedClickable(
                onClick = requireNotNull(onClick), onLongClick = onLongClick,
            ) else modifier,
            onClick = if (withLongPress) null else onClick,
        )
    }
    if (showDivider) PreferenceDivider(hasIcon = icon != null)
}

@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    iconColor: Color = MiuixTheme.colorScheme.primary,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    SwitchPreference(
        title = title,
        summary = subtitle,
        checked = checked,
        onCheckedChange = onCheckedChange,
        startAction = icon?.let { settingsRowIcon(it, iconColor) },
        enabled = enabled,
    )
    if (showDivider) PreferenceDivider(hasIcon = icon != null)
}

@Composable
fun SettingsValueRow(
    title: String,
    value: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = MiuixTheme.colorScheme.primary,
    valueColor: Color = MiuixTheme.colorScheme.onSurfaceSecondary,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconColor = iconColor,
        onClick = onClick,
        showDivider = showDivider,
        trailing = {
            Text(value, style = MiuixTheme.textStyles.body2, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}

@Composable
fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    showDivider: Boolean = true,
) {
    RadioButtonPreference(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp)),
        title = title,
        selected = selected,
        onClick = onSelect,
        startAction = leading,
        radioButtonLocation = RadioButtonLocation.End,
    )
    if (showDivider) PreferenceDivider(hasIcon = leading != null)
}

private fun settingsRowIcon(icon: ImageVector, color: Color): @Composable () -> Unit = {
    Box(Modifier.size(32.dp).background(color, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun PreferenceDivider(hasIcon: Boolean) {
    HorizontalDivider(Modifier.padding(start = if (hasIcon) 56.dp else 16.dp, end = 16.dp))
}

@Composable
fun SettingsCardBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(16.dp), content = content)
}
