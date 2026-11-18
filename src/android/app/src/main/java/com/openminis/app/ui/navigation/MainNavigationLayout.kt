package com.openminis.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.navigation.FloatingWindow
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.openminis.app.R
import com.openminis.app.ui.components.miuix.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class MainDestination(val route: String, val label: Int) {
    Home(Routes.SESSION_LIST, R.string.main_nav_home),
    Agents(Routes.AGENTS, R.string.main_nav_agents),
    Trajectory(Routes.USAGE_STATS, R.string.usage_title),
    Settings(Routes.SETTINGS, R.string.settings_title);

    companion object {
        // Detail routes retain the full workspace and their existing back affordance.
        fun forRoute(route: String?) = entries.firstOrNull { it.route == route }
    }
}

internal fun NavHostController.selectMainDestination(target: MainDestination) {
    val source = MainDestination.forRoute(currentDestination?.route) ?: return
    if (source == target) return
    if (currentBackStackEntry?.lifecycle?.currentState != androidx.lifecycle.Lifecycle.State.RESUMED) return
    if (hasMainEntry(target.route) && popBackStack(target.route, inclusive = false, saveState = true)) return
    safeNavigate(target.route) {
        // Replace only the visible tab. Settings or Activity may have been opened
        // from a conversation, which still needs to be reachable with Back.
        if (source != MainDestination.Home) popUpTo(source.route) { inclusive = true; saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavHostController.hasMainEntry(route: String) =
    try { getBackStackEntry(route); true } catch (_: IllegalArgumentException) { false }

@Composable
internal fun MainNavigationLayout(navController: NavHostController, content: @Composable () -> Unit) {
    val entry by navController.currentBackStackEntryAsState()
    val destination = if (entry?.destination is FloatingWindow) {
        navController.previousBackStackEntry?.destination
    } else {
        entry?.destination
    }
    val focus = LocalFocusManager.current
    MainNavigationLayout(MainDestination.forRoute(destination?.route), onSelect = { target ->
        if (entry?.destination !is FloatingWindow) {
            focus.clearFocus()
            navController.selectMainDestination(target)
        }
    }, content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MainNavigationLayout(
    selected: MainDestination?,
    onSelect: (MainDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        val wide = maxWidth >= 720.dp
        val keyboardVisible = WindowInsets.isImeVisible
        val showNavigation = selected != null && (wide || !keyboardVisible)
        val consumed = if (!showNavigation) WindowInsets(0) else WindowInsets.safeDrawing.only(
            if (wide) WindowInsetsSides.Start else WindowInsetsSides.Bottom,
        )
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                // Keep the same NavHost slot as the dock appears or switches sides.
                Box(Modifier.fillMaxSize().consumeWindowInsets(consumed)) { content() }
                if (showNavigation && selected != null) {
                    MainNavigationDock(selected, wide, onSelect)
                }
            },
        ) { measurables, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val dock = measurables.getOrNull(1)?.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val dockWidth = if (wide) dock?.width ?: 0 else 0
            val dockHeight = if (wide) 0 else dock?.height ?: 0
            val body = measurables[0].measure(androidx.compose.ui.unit.Constraints.fixed(w - dockWidth, h - dockHeight))
            layout(w, h) {
                body.placeRelative(dockWidth, 0)
                dock?.placeRelative(if (wide) 0 else (w - dock.width) / 2, if (wide) 0 else h - dockHeight)
            }
        }
    }
}

@Composable
private fun MainNavigationDock(selected: MainDestination, wide: Boolean, onSelect: (MainDestination) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(25.dp)
    val card = Modifier.shadow(6.dp, shape).clip(shape)
        .background(colors.surfaceContainer)
        .border(0.5.dp, colors.dividerLine, shape)
        .selectableGroup().padding(4.dp)
    if (wide) {
        Box(
            Modifier.fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(card.width(76.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MainDestination.entries.forEach { destination ->
                    MainNavigationItem(destination, selected == destination, Modifier.fillMaxWidth(), onSelect)
                }
            }
        }
    } else {
        Box(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 14.dp, end = 14.dp, top = 5.dp, bottom = 7.dp),
        ) {
            Row(card.widthIn(max = 480.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                MainDestination.entries.forEach { destination ->
                    MainNavigationItem(destination, selected == destination, Modifier.weight(1f), onSelect)
                }
            }
        }
    }
}

@Composable
private fun MainNavigationItem(
    destination: MainDestination,
    selected: Boolean,
    modifier: Modifier,
    onSelect: (MainDestination) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurfaceVariantSummary
    val icon = when (destination) {
        MainDestination.Home -> Icons.Outlined.Home
        MainDestination.Agents -> Icons.Outlined.SmartToy
        MainDestination.Trajectory -> Icons.Outlined.BarChart
        MainDestination.Settings -> Icons.Outlined.Settings
    }
    Column(
        modifier = modifier.clip(RoundedCornerShape(19.dp))
            .background(if (selected) colors.tertiaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination) })
            .heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        Text(stringResource(destination.label), color = tint, fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
