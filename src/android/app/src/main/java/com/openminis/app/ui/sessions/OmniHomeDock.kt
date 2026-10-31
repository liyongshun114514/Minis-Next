package com.openminis.app.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.model.ModelGroup
import kotlin.math.abs

/**
 * One destination of the floating home dock.
 *
 * Pure presentation: the caller owns every callback, so the dock can never
 * invent a route of its own. [contentDescription] is the accessibility label
 * and falls back to [label] (the visible glyph caption).
 */
internal data class OmniDockItem(
    val icon: ImageVector,
    val label: String,
    val contentDescription: String? = null,
    /** Non-null and > 0 renders a count badge on the glyph's top-trailing corner. */
    val badge: Int? = null,
    val onLongClick: (() -> Unit)? = null,
    val onSwap: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/**
 * Independent Compose port of the reference floating dock
 * (`ui/lib/widgets/flclash/navigation_dock.dart`).
 *
 * Kept from upstream: 62dp bar, 4dp inner padding, a fully rounded (there:
 * superellipse) shell that casts a soft two-part shadow, one glyph over a 10sp
 * caption, and a selection "lens" that travels between destinations on a
 * spring (upstream: 400ms / bounce 0.15). Not ported: the liquid-glass lens,
 * hover magnet, jelly stretch and drag-to-select — this app's material theme
 * has no glass layer, and the motion model is re-expressed with Compose
 * springs rather than the upstream per-frame physics.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OmniFloatingDock(
    items: List<OmniDockItem>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    isDark: Boolean = false,
) {
    if (items.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    val shape = omniCapsuleShape()
    val shell = omniFloatingSurface()
    val lensColor = omniSelectedSurface()
    val safeSelected = if (selectedIndex in items.indices) selectedIndex else -1
    // -1 means "no persistent selection" (this dock is an action bar, not a
    // tab bar). The lens then follows the finger and fades out on release.
    var pressedIndex by remember { mutableStateOf(-1) }
    val lensTarget = (if (pressedIndex >= 0) pressedIndex else safeSelected)
        .coerceAtLeast(0)
    val travel by animateFloatAsState(
        targetValue = lensTarget.toFloat(),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "omni-dock-lens",
    )
    val lensAlpha by animateFloatAsState(
        targetValue = if (pressedIndex >= 0 || safeSelected >= 0) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "omni-dock-lens-alpha",
    )

    Surface(
        shape = shape,
        color = shell,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        // Upstream draws a 24dp-blur shadow at 8% plus a 3dp contact shadow at
        // 4%; a single Compose shadow at 12dp lands on the same visual weight.
        modifier = modifier
            .shadow(
                elevation = 12.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (isDark) 0.5f else 0.15f),
                spotColor = Color.Black.copy(alpha = if (isDark) 0.5f else 0.15f),
            )
            .height(OmniHomeSpec.DockHeight),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val count = items.size
            val pitch: Dp = (maxWidth - OmniHomeSpec.DockPadding * 2) / count
            val lensWidth: Dp = pitch + OmniHomeSpec.DockPadding * 2
            Box(
                modifier = Modifier
                    .offset(
                        x = pitch * travel,
                        y = OmniHomeSpec.DockPadding,
                    )
                    .width(lensWidth)
                    .fillMaxHeight()
                    .padding(vertical = OmniHomeSpec.DockPadding)
                    .background(lensColor.copy(alpha = lensColor.alpha * lensAlpha), shape),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = OmniHomeSpec.DockPadding),
            ) {
                items.forEachIndexed { index, item ->
                    val pressedSource = remember { MutableInteractionSource() }
                    val pressed by pressedSource.collectIsPressedAsState()
                    LaunchedEffect(pressed) {
                        if (pressed) {
                            pressedIndex = index
                        } else if (pressedIndex == index) {
                            pressedIndex = -1
                        }
                    }
                    val pressScale by animateFloatAsState(
                        targetValue = if (pressed) 0.92f else 1f,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
                        label = "omni-dock-press",
                    )
                    val selected = index == safeSelected
                    val threshold = with(LocalDensity.current) { 100.dp.toPx() }
                    val swapModifier = if (item.onSwap == null) Modifier else Modifier.pointerInput(item.onSwap) {
                        var drag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { drag = 0f },
                            onHorizontalDrag = { _, amount -> drag += amount },
                            onDragCancel = { drag = 0f },
                            onDragEnd = {
                                if (abs(drag) > threshold) item.onSwap.invoke()
                                drag = 0f
                            },
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(swapModifier)
                            .clip(shape)
                            .combinedClickable(
                                interactionSource = pressedSource,
                                indication = null,
                                onClickLabel = item.label,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    item.onClick()
                                },
                                onLongClick = item.onLongClick?.let {
                                    {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        it()
                                    }
                                },
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .scale(pressScale)
                                .size(OmniHomeSpec.DockIcon + 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.contentDescription ?: item.label,
                                tint = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(OmniHomeSpec.DockIcon),
                            )
                            val badge = item.badge
                            if (badge != null && badge > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = if (badge > 99) "99+" else "$badge",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(OmniHomeSpec.DockLabelGap))
                        Text(
                            text = item.label,
                            fontSize = OmniHomeSpec.DockLabelSize,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) {
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

/**
 * Search field of the reference header (`_SearchField`): 44dp tall, fully
 * rounded, 17sp text, a 24dp leading glyph and a trailing action that becomes
 * a progress ring while a search is in flight.
 */
@Composable
internal fun OmniSearchCapsule(
    value: String,
    onValueChange: (String) -> Unit,
    isSearching: Boolean,
    onDismiss: () -> Unit,
    placeholder: String,
    dismissContentDescription: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val shape = omniCapsuleShape()
    val fieldModifier = remember(focusRequester) {
        if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    }
    Surface(
        shape = shape,
        color = omniFloatingSurface(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .height(OmniHomeSpec.SearchFieldHeight)
            .shadow(elevation = 10.dp, shape = shape, clip = false)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = shape,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp),
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = OmniHomeSpec.SearchTextSize,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .then(fieldModifier),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                fontSize = OmniHomeSpec.SearchTextSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (isSearching) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(18.dp),
                )
            } else {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = dismissContentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Bottom chrome of the home screen: an optional search capsule stacked over the
 * floating dock.
 *
 * Mirrors the reference `fabRow` + dock composition — actions live in a
 * floating shell instead of two round FABs — while keeping every callback of
 * the previous two-FAB row exactly as it was: new chat, new chat in a group
 * (long press), search open/close/query, plus the caller-supplied [extraItems].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OmniHomeDockBar(
    isDark: Boolean,
    isSearchActive: Boolean,
    searchQuery: String,
    isSearching: Boolean,
    hasSessions: Boolean,
    newChatLabel: String,
    searchLabel: String,
    searchPlaceholder: String,
    searchDismissLabel: String,
    onNewChat: () -> Unit,
    onNewChatWithGroup: (String) -> Unit,
    modelGroups: List<ModelGroup>,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchDismiss: () -> Unit,
    extraItems: List<OmniDockItem> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // The old two-FAB row let a drag swap the two primary actions; that
    // preference is still stored under fab_swapped, so it now orders the
    // dock's two primaries instead of being silently dropped.
    val prefs = remember { context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE) }
    var swapped by remember { mutableStateOf(prefs.getBoolean(PREF_FAB_SWAPPED, false)) }
    val swapActions = {
        swapped = !swapped
        prefs.edit().putBoolean(PREF_FAB_SWAPPED, swapped).apply()
    }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showGroupMenu by remember { mutableStateOf(false) }
    val topGroups = remember(modelGroups) { modelGroups.take(10) }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            // The capsule enters through AnimatedVisibility, so yield one frame
            // before requesting focus; the field is not composed until then.
            kotlinx.coroutines.delay(50)
            runCatching { searchFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    val newChatItem = OmniDockItem(
        icon = Icons.Outlined.Forum,
        label = newChatLabel,
        onSwap = swapActions,
        onLongClick = if (topGroups.isNotEmpty()) {
            { showGroupMenu = true }
        } else {
            null
        },
        onClick = onNewChat,
    )
    val searchItem = if (hasSessions) {
        OmniDockItem(
            icon = Icons.Outlined.Search,
            label = searchLabel,
            onSwap = swapActions,
            onClick = onSearchToggle,
        )
    } else {
        null
    }
    val items = (if (swapped) {
        listOfNotNull(searchItem, newChatItem)
    } else {
        listOfNotNull(newChatItem, searchItem)
    }) + extraItems
    // Search is "selected" while its capsule is open; otherwise the dock has no
    // selected destination (the list is the home surface itself).
    val searchIndex = when {
        searchItem == null -> -1
        swapped -> 0
        else -> 1
    }
    val selectedIndex = if (isSearchActive) searchIndex else -1

    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                // Scaffold already hands the content a bottom inset for the
                // navigation bar (there is no bottomBar slot), so only the IME
                // needs lifting here.
                .imePadding(),
        ) {
            AnimatedVisibility(
                visible = isSearchActive,
                enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.9f),
                exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.9f),
            ) {
                OmniSearchCapsule(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    isSearching = isSearching,
                    onDismiss = onSearchDismiss,
                    placeholder = searchPlaceholder,
                    dismissContentDescription = searchDismissLabel,
                    focusRequester = searchFocusRequester,
                    modifier = Modifier
                        .padding(horizontal = OmniHomeSpec.ToolbarEdge)
                        .padding(bottom = OmniHomeSpec.ToolbarGap),
                )
            }
            OmniFloatingDock(
                items = items,
                selectedIndex = selectedIndex,
                isDark = isDark,
                modifier = Modifier
                    .padding(horizontal = OmniHomeSpec.DockEdge)
                    .padding(
                        top = OmniHomeSpec.DockShadowRoom,
                        bottom = OmniHomeSpec.DockShadowRoom,
                    ),
            )
        }
        // Long press on "new chat" — same group shortcut as before, anchored to
        // the dock so the menu opens upwards.
        DropdownMenu(
            expanded = showGroupMenu,
            onDismissRequest = { showGroupMenu = false },
        ) {
            topGroups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.name) },
                    leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                    onClick = {
                        showGroupMenu = false
                        onNewChatWithGroup(group.id)
                    },
                )
            }
        }
    }
}

