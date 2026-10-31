package com.openminis.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.OmniGlassStyle
import com.openminis.app.ui.components.OmniGlassTones
import com.openminis.app.ui.components.OmniShapes
import com.openminis.app.ui.components.omniGlassSurface
import com.openminis.app.ui.sessions.OmniHomeSpec
import com.openminis.app.ui.sessions.omniSelectedSurface
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The four kept-alive surfaces of the root shell.
 *
 * These are NOT new features. Each entry is one of the app's existing screens —
 * the conversation home (the split-scaffold list/detail pair), the sub-agent
 * roster, token usage and Settings — rendered in place instead of being pushed
 * onto the NavHost. Every route (`Routes.SESSION_LIST`, `Routes.AGENTS`,
 * `Routes.USAGE_STATS`, `Routes.SETTINGS`) is still registered and still
 * reachable; the shell adds an in-place entry point, it never replaces one.
 *
 * That is the reference's `MainNavigationTab` (`home`, `agents`, `trajectory`,
 * `settings`) mapped onto the destinations Minis Next actually has, rather than
 * inventing OmniBot's business surfaces.
 */
internal enum class OmniRootTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    /** `ConversationHomePage` ⇔ the session list + chat split scaffold. */
    Home(R.string.app_name, Icons.Outlined.Forum),

    /** `AgentModeSettingPage` ⇔ Settings › Agents (sub-agent roster). */
    Agents(R.string.agent_tools_agents, Icons.Outlined.SmartToy),

    /** `UsageStatisticsPage` ⇔ Settings › Token Usage. */
    Activity(R.string.settings_token_usage, Icons.Outlined.QueryStats),

    /** `SettingsPage` ⇔ Settings. */
    Settings(R.string.sessionlist_settings, Icons.Outlined.Settings),
}

internal object OmniRootSpec {
    /**
     * Room the shell reserves under the page when the dock is on screen:
     * the 62dp bar, `_shadowRoom` above and below it (8+8), and `_edgeMargin`
     * below that — the reference's `FlClashNavigationDock.insetOf`, which is
     * `heightOf + max(edgeMargin, safeAreaBottom)`.
     */
    val DockInset: Dp = OmniHomeSpec.DockHeight + OmniHomeSpec.DockShadowRoom * 2 + OmniHomeSpec.DockEdge

    /** Rail padding on the leading edge (`omni_navigation_rail.dart`). */
    val RailHorizontal = 12.dp
    val RailVertical = 16.dp

    // ── Lens optics (`navigation_dock.dart`) ────────────────────────────────
    /** `_lensGrowth`: how much the lens grows as it lifts off the bar. */
    val LensGrowth = 8.dp
    /** `_lensMagnify`: the lens swells past its cell while lifted. */
    const val LensMagnify = 0.16f
    /** `_jellyStretch` / `_jellySquash`: a fast drag stretches, then squashes. */
    const val JellyStretch = 0.2f
    const val JellySquash = 0.15f
    /** `_pressGrowth`: the pressed glyph grows by an eighth. */
    const val PressGrowth = 0.125f
}

/**
 * Chrome the root shell reserves around the selected page.
 *
 * A page that positions its own floating controls against the bottom edge (the
 * home list's action dock) reads this so it can stack above the shell instead
 * of underneath it. Pages that simply fill their box need do nothing — the
 * shell already insets them.
 */
internal val LocalOmniChromeInsets = staticCompositionLocalOf { PaddingValues(0.dp) }

/**
 * Root shell: four kept-alive destinations behind one dock or one rail.
 *
 * Behaviour carried over from `main_navigation_page.dart`:
 *  - one page tree is kept for all four surfaces, so switching a tab does not
 *    rebuild the screen — drafts, search text, scroll offsets and selection
 *    survive the switch (here: a pager plus a `SaveableStateHolder`, which is
 *    the Compose equivalent of the reference's retained `FlClashTabView`);
 *  - at `>= 600dp` the dock becomes a leading rail (`OmniLayoutBreakpoints.medium`);
 *  - the dock is hidden while the IME is up;
 *  - switching tabs unfocuses the keyboard and the back key returns to the home
 *    tab instead of leaving the app.
 *
 * What is deliberately NOT here: any business state. The shell owns only which
 * tab is showing; each page keeps its own ViewModel/repository wiring, and
 * every callback that used to navigate still navigates.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun OmniRootNavigation(
    modifier: Modifier = Modifier,
    initialTab: OmniRootTab = OmniRootTab.Home,
    page: @Composable (tab: OmniRootTab, onNavigateUp: () -> Unit) -> Unit,
) {
    val tabs = remember { OmniRootTab.entries }
    var selectedIndex by rememberSaveable { mutableStateOf(initialTab.ordinal) }
    val selected = tabs[selectedIndex.coerceIn(tabs.indices)]
    val pagerState = rememberPagerState(initialPage = selected.ordinal) { tabs.size }
    val keyboard = LocalSoftwareKeyboardController.current
    val select: (Int) -> Unit = { index ->
        val target = index.coerceIn(tabs.indices)
        if (target != selectedIndex) {
            keyboard?.hide()
            selectedIndex = target
        }
    }
    LaunchedEffect(selectedIndex) {
        if (pagerState.currentPage != selectedIndex) {
            pagerState.animateScrollToPage(
                page = selectedIndex,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = OmniMotion.TabSlideDuration,
                    easing = OmniMotion.TabEasing,
                ),
            )
        }
    }
    // Back from a non-home tab returns to the home tab — root behaviour. It does
    // not touch any data: it only moves the shell's selected index.
    BackHandler(enabled = selected != OmniRootTab.Home) {
        selectedIndex = OmniRootTab.Home.ordinal
    }

    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useRail = maxWidth >= OmniHomeSpec.RailBreakpoint
        val imeVisible = WindowInsets.isImeVisible
        val showDock = !useRail && !imeVisible
        // `FlClashNavigationDock.insetOf` = bar + both shadow rooms +
        // max(edgeMargin, safeAreaBottom). The page's own Scaffold already
        // consumes the system navigation-bar inset, so only the part of that
        // margin the bar does NOT cover is reserved here — reserving the whole
        // margin would count the nav bar twice and float the dock too high.
        val safeBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val edge = maxOf(OmniHomeSpec.DockEdge, safeBottom)
        val dockReserve = OmniHomeSpec.DockHeight + OmniHomeSpec.DockShadowRoom * 2 +
            (edge - safeBottom).coerceAtLeast(0.dp)
        // Animate the reserved room so the page does not jump when the keyboard
        // opens (the dock fades out as its space is given back).
        val bottomInset by animateDpAsState(
            targetValue = if (showDock) dockReserve else 0.dp,
            label = "omni-root-bottom-inset",
        )
        // ONLY the rail's own width. The page's Scaffold consumes the system
        // leading inset itself, and the rail sits inside `safeDrawingPadding`,
        // so adding it here too would push the page right by the inset twice
        // (the same double-count the dock reserve below avoids at the bottom).
        val leadingInset = if (useRail) OmniHomeSpec.RailWidth else 0.dp
        val insets = PaddingValues(start = leadingInset, bottom = bottomInset)
        val stateHolder = rememberSaveableStateHolder()
        CompositionLocalProvider(LocalOmniChromeInsets provides insets) {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // Tab changes are driven by the dock/rail only; the reference
                    // tab view disables direct page dragging for the same reason.
                    userScrollEnabled = false,
                    // KEEP-ALIVE, not save/restore: the other three pages stay in
                    // composition while off-screen, so plain `remember` state
                    // (drafts, in-flight dialogs' backing fields, scroll owners,
                    // one-shot effect baselines) survives a tab switch — the same
                    // guarantee the reference's retained `FlClashTabView` pages
                    // give. A `SaveableStateHolder` alone would only restore
                    // `rememberSaveable` values and would drop the rest.
                    beyondViewportPageCount = tabs.lastIndex,
                    key = { tabs[it].name },
                ) { index ->
                    val tab = tabs[index]
                    Box(Modifier.fillMaxSize().padding(insets)) {
                        // Every page is composed; only the selected one is the
                        // "active" page. Inactive pages must not own transient
                        // windows, must not be reachable by accessibility, and
                        // must not steal back presses — see OmniRootPageHost.
                        OmniRootPageHost(active = index == selected.ordinal) {
                            stateHolder.SaveableStateProvider(tab.name) {
                                page(tab) { select(OmniRootTab.Home.ordinal) }
                            }
                        }
                    }
                }

                if (useRail) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(OmniHomeSpec.RailWidth)
                            .fillMaxHeight()
                            .safeDrawingPadding(),
                    ) {
                        OmniRootDestinationBar(
                            tabs = tabs,
                            selectedIndex = selected.ordinal,
                            onSelect = select,
                            vertical = true,
                            isDark = isDark,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(
                                    horizontal = OmniRootSpec.RailHorizontal,
                                    vertical = OmniRootSpec.RailVertical,
                                ),
                        )
                    }
                } else {
                    AnimatedVisibility(
                        visible = showDock,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        OmniRootDestinationBar(
                            tabs = tabs,
                            selectedIndex = selected.ordinal,
                            onSelect = select,
                            vertical = false,
                            isDark = isDark,
                            modifier = Modifier
                                .fillMaxWidth()
                                // `max(_edgeMargin, safeAreaBottom)`, not
                                // navigationBarsPadding + a second margin: the
                                // reference places the bar at that margin above
                                // the bottom edge, whatever the system inset is.
                                .padding(horizontal = OmniHomeSpec.DockEdge)
                                .padding(
                                    top = OmniHomeSpec.DockShadowRoom,
                                    bottom = edge + OmniHomeSpec.DockShadowRoom,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Marker for "this page is the one on screen".
 *
 * Kept-alive pages are composed while off-screen, so a page that owns transient
 * windows (dialogs, sheets, popups) must dismiss them when it goes inactive
 * rather than leave them floating over another tab. [OmniRootPageHost] publishes
 * it; the home page consumes it, and any other page can in one line.
 */
internal val LocalOmniPageActive = staticCompositionLocalOf { true }

/**
 * A lifecycle for ONE kept-alive page.
 *
 * The four pages stay composed, but they must not be foreground at the same
 * time. Without this they all inherit the root nav entry's RESUMED lifecycle,
 * which means:
 *  - a page's `BackHandler` (the home page hosts `ChatScreen`, which registers
 *    one for an active search and another for its overlay) stays ENABLED while
 *    another tab is on screen, so back would be swallowed by an invisible page;
 *  - `collectAsStateWithLifecycle`, `repeatOnLifecycle` and any other
 *    lifecycle-aware subscription keeps running in the background for every tab.
 *
 * The active page is RESUMED; an off-screen page drops to CREATED — composed and
 * state-holding, but stopped, so its back callbacks are inactive and its
 * lifecycle-aware work pauses. CREATED (not DESTROYED) is deliberate: nothing is
 * torn down, nothing is disposed, and no ViewModel is cleared. The
 * ViewModelStoreOwner is left untouched (the root entry's), so pages keep using
 * the ViewModels they always used instead of getting new ones.
 */
internal object OmniPageLifecycleTarget {
    /**
     * `min(parent, active ? RESUMED : CREATED)`.
     *
     * The page can never be more alive than the Activity hosting it: while the
     * app is in the background the parent is STOPPED/CREATED and every page
     * parks there too, so a kept-alive page does not keep running as if it were
     * foreground. An inactive page is CREATED (composed, state-holding, back
     * callbacks inactive) — never DESTROYED, so nothing is torn down.
     */
    fun of(active: Boolean, parentState: Lifecycle.State): Lifecycle.State = when {
        !active -> Lifecycle.State.CREATED
        parentState == Lifecycle.State.RESUMED -> Lifecycle.State.RESUMED
        parentState == Lifecycle.State.STARTED -> Lifecycle.State.STARTED
        else -> Lifecycle.State.CREATED
    }
}

internal class OmniPageLifecycleOwner : LifecycleOwner {
    // `createUnsafe` skips the main-looper check so the state machine can be
    // unit-tested on a plain JVM; every write below still happens on the main
    // thread (SideEffect), so production behaviour is unchanged.
    private val registry = LifecycleRegistry.createUnsafe(this)

    override val lifecycle: Lifecycle get() = registry

    init {
        registry.currentState = Lifecycle.State.CREATED
    }

    /** Applies [targetState] for the current [active] flag and parent state. */
    fun setActive(active: Boolean, parentState: Lifecycle.State) {
        registry.currentState = OmniPageLifecycleTarget.of(active, parentState)
    }

    fun dispose() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

@Composable
private fun OmniRootPageHost(active: Boolean, content: @Composable () -> Unit) {
    val pageLifecycle = remember { OmniPageLifecycleOwner() }
    // The Activity's own state is observed, not assumed: a page must drop to
    // CREATED when the app goes to the background even if it is the selected
    // tab. `min(parent, active ? RESUMED : CREATED)` is the whole policy.
    val parentState by androidx.lifecycle.compose.LocalLifecycleOwner.current
        .lifecycle.currentStateAsState()
    // Main-thread transition; keeps the registry legal on every recomposition.
    SideEffect { pageLifecycle.setActive(active, parentState) }
    DisposableEffect(pageLifecycle) {
        onDispose { pageLifecycle.dispose() }
    }
    CompositionLocalProvider(
        LocalOmniPageActive provides active,
        androidx.lifecycle.compose.LocalLifecycleOwner provides pageLifecycle,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (active) {
                        Modifier
                    } else {
                        // Off-screen and kept alive: still laid out (that is how
                        // its state survives) but removed from the semantic tree
                        // so accessibility focus can never land on a hidden tab.
                        Modifier.semantics { invisibleToUser() }
                    },
                ),
        ) {
            content()
        }
    }
}

/**
 * The destinations themselves, as one lens that springs between them.
 *
 * Ported from `flclash/navigation_dock.dart`: a `_barHeight` 62dp superellipse
 * shell, `_barPadding` 4dp, one 28dp glyph over a 10sp caption with a 2dp gap,
 * a selection lens on `_travelSpring`, and drag-to-select — pressing a
 * destination and sliding moves the lens under the finger, releasing selects
 * what it landed on (`_followSpring` while the finger is down).
 *
 * The gesture is owned by the bar rather than by each destination: a tab bar
 * needs the drag to survive crossing item boundaries, and one owner keeps a
 * drag from also firing the destination it started on.
 */
@Composable
private fun OmniRootDestinationBar(
    tabs: List<OmniRootTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    isDark: Boolean = false,
) {
    if (tabs.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    val shape = OmniShapes.Capsule
    val glassStyle = OmniGlassStyle.Default.copy(tint = OmniGlassTones.of(isDark))
    val lensColor = omniSelectedSurface()
    val density = LocalDensity.current
    var dragTravel by remember { mutableFloatStateOf(Float.NaN) }
    var pressedIndex by remember { mutableIntStateOf(-1) }
    // `_jellyStretch`: per-drag-frame displacement, normalised to one cell and
    // damped; it decays back to 0 on its own spring when the finger lifts.
    var jellyTarget by remember { mutableFloatStateOf(0f) }
    val lensTarget = if (!dragTravel.isNaN()) dragTravel else selectedIndex.toFloat()
    val travel by animateFloatAsState(
        targetValue = lensTarget,
        animationSpec = if (!dragTravel.isNaN()) OmniMotion.DockLensFollow else OmniMotion.DockLensTravel,
        label = "omni-root-lens",
    )
    // `_liftSpring` (250ms): the lens lifts while a destination is held or
    // dragged, and settles back when it is released.
    val lifted by animateFloatAsState(
        targetValue = if (pressedIndex >= 0 || !dragTravel.isNaN()) 1f else 0f,
        animationSpec = OmniMotion.DockLift,
        label = "omni-root-lift",
    )
    val jelly by animateFloatAsState(
        targetValue = jellyTarget,
        animationSpec = OmniMotion.DockJelly,
        label = "omni-root-jelly",
    )
    val barModifier = if (vertical) {
        // One 62dp cell per destination plus the bar's inner padding, the
        // vertical analogue of the dock's single-row height.
        Modifier
            .fillMaxWidth()
            .height(OmniHomeSpec.DockHeight * tabs.size + OmniHomeSpec.DockPadding * 2)
    } else {
        Modifier
            .fillMaxWidth()
            .height(OmniHomeSpec.DockHeight)
    }
    Box(
        // The shell is a GLASS pane, not an opaque Surface: the page behind the
        // dock/rail is refracted through it (OmniGlass.kt `omniGlassSurface`).
        // No fill is painted here, so nothing covers the glass.
        modifier = modifier
            .then(barModifier)
            .shadow(
                elevation = 12.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (isDark) 0.5f else 0.15f),
                spotColor = Color.Black.copy(alpha = if (isDark) 0.5f else 0.15f),
            )
            .omniGlassSurface(shape = shape, style = glassStyle)
            .clip(shape),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pad = OmniHomeSpec.DockPadding
            val extent: Dp = if (vertical) maxHeight else maxWidth
            val pitch: Dp = (extent - pad * 2) / tabs.size
            val pitchPx = with(density) { pitch.toPx() }.coerceAtLeast(1f)
            val indexAt: (Float) -> Int = { position ->
                (position / pitchPx).toInt().coerceIn(0, tabs.size - 1)
            }
            val travelAt: (Float) -> Float = { position ->
                (position / pitchPx - 0.5f).coerceIn(0f, (tabs.size - 1).toFloat())
            }
            // Lens: one cell wide plus the bar's inner padding, inset by that
            // padding on the cross axis (navigation_dock.dart `_lensRect`), then
            // grown by `_lensGrowth` as it lifts, swelled by `_lensMagnify`, and
            // stretched/squashed by the jelly while a drag is under way. The
            // last three are DRAWN (graphicsLayer), so they cost no re-layout.
            val lensOffset = (pitchPx * travel).roundToInt()
            val growth = OmniRootSpec.LensGrowth * lifted
            val swell = 1f + OmniRootSpec.LensMagnify * lifted
            val squash = 1f - abs(jelly) * OmniRootSpec.JellySquash
            val lensModifier = if (vertical) {
                Modifier
                    .offset { IntOffset(0, lensOffset) }
                    .height(pitch + pad * 2 + growth)
                    .fillMaxWidth()
                    .padding(horizontal = pad)
            } else {
                Modifier
                    .offset { IntOffset(lensOffset, 0) }
                    .width(pitch + pad * 2 + growth)
                    .fillMaxHeight()
                    .padding(vertical = pad)
            }
            Box(
                modifier = lensModifier
                    .graphicsLayer {
                        if (vertical) {
                            scaleX = squash
                            scaleY = swell + jelly
                        } else {
                            scaleX = swell + jelly
                            scaleY = squash
                        }
                    }
                    .background(lensColor, shape),
            )
            val gestureModifier = if (vertical) {
                Modifier.pointerInput(tabs.size, pitchPx) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragTravel = travelAt(offset.y)
                            pressedIndex = indexAt(offset.y)
                        },
                        onDragCancel = {
                            dragTravel = Float.NaN
                            pressedIndex = -1
                            jellyTarget = 0f
                        },
                        onDragEnd = {
                            val landed = dragTravel
                            dragTravel = Float.NaN
                            pressedIndex = -1
                            jellyTarget = 0f
                            if (!landed.isNaN()) onSelect(landed.roundToInt())
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragTravel = travelAt(change.position.y)
                            pressedIndex = indexAt(change.position.y)
                            jellyTarget = (dragAmount / pitchPx).coerceIn(-1f, 1f) *
                                OmniRootSpec.JellyStretch
                        },
                    )
                }
            } else {
                Modifier.pointerInput(tabs.size, pitchPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragTravel = travelAt(offset.x)
                            pressedIndex = indexAt(offset.x)
                        },
                        onDragCancel = {
                            dragTravel = Float.NaN
                            pressedIndex = -1
                            jellyTarget = 0f
                        },
                        onDragEnd = {
                            val landed = dragTravel
                            dragTravel = Float.NaN
                            pressedIndex = -1
                            jellyTarget = 0f
                            if (!landed.isNaN()) onSelect(landed.roundToInt())
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragTravel = travelAt(change.position.x)
                            pressedIndex = indexAt(change.position.x)
                            // `_jellyStretch` of one cell per frame, damped.
                            jellyTarget = (dragAmount / pitchPx).coerceIn(-1f, 1f) *
                                OmniRootSpec.JellyStretch
                        },
                    )
                }
            }
            val tapModifier = if (vertical) {
                Modifier.pointerInput(tabs.size, pitchPx) {
                    detectTapGestures(
                        onPress = { offset ->
                            pressedIndex = indexAt(offset.y)
                            tryAwaitRelease()
                            pressedIndex = -1
                        },
                        onTap = { offset ->
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(indexAt(offset.y))
                        },
                    )
                }
            } else {
                Modifier.pointerInput(tabs.size, pitchPx) {
                    detectTapGestures(
                        onPress = { offset ->
                            pressedIndex = indexAt(offset.x)
                            tryAwaitRelease()
                            pressedIndex = -1
                        },
                        onTap = { offset ->
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(indexAt(offset.x))
                        },
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .then(gestureModifier)
                    .then(tapModifier),
            )
            tabs.forEachIndexed { index, tab ->
                val isSelected = index == selectedIndex
                val label = stringResource(tab.labelRes)
                val itemModifier = if (vertical) {
                    Modifier
                        .offset { IntOffset(0, (pitchPx * index).roundToInt()) }
                        .height(pitch)
                        .fillMaxWidth()
                } else {
                    Modifier
                        .offset { IntOffset((pitchPx * index).roundToInt(), 0) }
                        .width(pitch)
                        .fillMaxHeight()
                }
                val pressed by animateFloatAsState(
                    targetValue = if (pressedIndex == index) {
                        1f + OmniRootSpec.PressGrowth
                    } else {
                        1f
                    },
                    animationSpec = OmniMotion.DockPress,
                    label = "omni-root-press",
                )
                Box(
                    modifier = itemModifier.semantics(mergeDescendants = true) {
                        this.selected = isSelected
                        role = Role.Tab
                        contentDescription = label
                        onClick(label = label) { onSelect(index); true }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .scale(pressed)
                                .size(OmniHomeSpec.DockIcon + 6.dp)
                                .clip(shape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(OmniHomeSpec.DockIcon),
                            )
                        }
                        Spacer(Modifier.height(OmniHomeSpec.DockLabelGap))
                        Text(
                            text = label,
                            fontSize = OmniHomeSpec.DockLabelSize,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
