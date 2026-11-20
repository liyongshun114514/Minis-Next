package com.openminis.app.ui.settings

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.consumeWindowInsets
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Warning
import com.openminis.app.ui.components.miuix.DropdownMenu
import com.openminis.app.ui.components.miuix.DropdownMenuItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import com.openminis.app.ui.components.MinisPageScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import com.openminis.app.ui.components.minisPageContentPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.R
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.openminis.app.ui.components.SectionTextField
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import com.openminis.app.ui.components.preferenceTabMinWidth
import com.openminis.app.ui.components.preferenceTabHeight
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.foundation.layout.Arrangement
import top.yukonga.miuix.kmp.basic.BasicComponent

/**
 * Detail / edit screen for one ModelGroup. Adopted T79 sectioned style:
 * Name, Routing Strategy (+ optional Fallback Trigger sub-section),
 * Members (reorderable), and Actions. Each section gets a header and an
 * explanatory footer caption, matching the iOS-side detail screens.
 *
 * Uses a manually-rolled Scaffold instead of SettingsScaffold because
 * the members list needs LazyColumn for reordering — SettingsScaffold's
 * verticalScroll wrapper would conflict with the lazy list's infinite-
 * height measure.
 */
@Composable
fun ModelGroupDetailScreen(
    groupId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddModels: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val group = config.modelGroups.find { it.id == groupId }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (group == null) {
        LaunchedEffect(groupId) { onBack() }
        return
    }

    var name by remember { mutableStateOf(group.name) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var strategy by remember { mutableStateOf(group.strategy) }
    var fallbackStrategy by remember { mutableStateOf(group.fallbackStrategy) }
    // T312: Session Defaults — keyed on group.id so navigating to another
    // group via NavBackStack rebuilds these from the new group's persisted
    // values. Using `group` as key would also re-fire on every config
    // refresh (since `group` is recomputed from the StateFlow each
    // recomposition); id-keyed remember is the stable alternative.
    var defaultThinkingLevel by remember(group.id) {
        mutableStateOf(group.defaultThinkingLevel)
    }
    var contextLimitTokens by remember(group.id) {
        mutableStateOf(group.contextLimitTokens)
    }

    // Local copy of member IDs for live reorder
    var memberIds by remember(group.memberEntryIds) { mutableStateOf(group.memberEntryIds.toList()) }
    var entryToRemove by remember { mutableStateOf<String?>(null) }
    // [T-quota-group-member-pill] Provider whose quota sheet is open.
    var quotaSheetInstanceId by remember { mutableStateOf<String?>(null) }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // from.index / to.index are LazyColumn global indices (include header items).
        // Use the item key (entryId string) to find the correct position in memberIds.
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        val fromIdx = memberIds.indexOf(fromKey)
        val toIdx = memberIds.indexOf(toKey)
        if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
        memberIds = memberIds.toMutableList().apply {
            add(toIdx, removeAt(fromIdx))
        }
        providerRepository.updateGroup(group.copy(memberEntryIds = memberIds.toMutableList()))
    }

    Scaffold(
        overlapTopBar = true,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            SettingsGlassTopBar(
                title = group.name,
                backdrop = null,
                overlap = { 1f },
                centerTitle = true,
                onBack = onBack,
                navigation = null,
                actions = null,
            )
        },
        containerColor = MiuixTheme.colorScheme.surface,
    ) { padding ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding)
                .testTag("model-group-detail-list"),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction),
                end = padding.calculateEndPadding(direction),
                top = padding.calculateTopPadding() + com.openminis.app.ui.components.LocalMinisPageTopInset.current,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            // ── Name section ──────────────────────────────────────────
            item {
                SettingsSection(
                    header = stringResource(R.string.model_group_detail_name),
                    footer = stringResource(R.string.model_group_detail_the_label_shown_in_the_model_picker_save),
                ) {
                    SettingsCardBlock {
                        SectionTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            fieldModifier = Modifier.onFocusChanged { focusState ->
                                if (!focusState.isFocused && name.isNotBlank() && name != group.name) {
                                    providerRepository.updateGroup(group.copy(name = name))
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Name saved")
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // ── Routing Strategy ──────────────────────────────────────
            item {
                SettingsSection(
                    header = stringResource(R.string.model_group_detail_routing_strategy),
                    footer = when (strategy) {
                        RoutingStrategy.fallback -> "Try models in order. If one fails, advance to the next."
                        RoutingStrategy.loadBalance -> "Distribute sessions across models in the group."
                    },
                ) {
                    SettingsChoiceRow(
                        title = stringResource(R.string.model_group_detail_fallback),
                        selected = strategy == RoutingStrategy.fallback,
                        onSelect = {
                            strategy = RoutingStrategy.fallback
                            providerRepository.updateGroup(group.copy(strategy = RoutingStrategy.fallback))
                        },
                    )
                    SettingsChoiceRow(
                        title = stringResource(R.string.model_group_detail_load_balance),
                        selected = strategy == RoutingStrategy.loadBalance,
                        onSelect = {
                            strategy = RoutingStrategy.loadBalance
                            providerRepository.updateGroup(group.copy(strategy = RoutingStrategy.loadBalance))
                        },
                        showDivider = false,
                    )
                }
            }

            // ── Fallback Trigger (only when strategy = fallback) ──────
            if (strategy == RoutingStrategy.fallback) {
                item {
                    SettingsSection(
                        header = stringResource(R.string.model_group_detail_fallback_trigger),
                        footer = when (fallbackStrategy) {
                            FallbackStrategy.default -> "Fall back on rate limits (429) and server errors (5xx) only."
                            FallbackStrategy.always -> "Fall back on any error, including network and auth failures."
                        },
                    ) {
                        SettingsChoiceRow(
                            title = stringResource(R.string.common_default),
                            selected = fallbackStrategy == FallbackStrategy.default,
                            onSelect = {
                                fallbackStrategy = FallbackStrategy.default
                                providerRepository.updateGroup(group.copy(fallbackStrategy = FallbackStrategy.default))
                            },
                        )
                        SettingsChoiceRow(
                            title = stringResource(R.string.model_group_detail_always),
                            selected = fallbackStrategy == FallbackStrategy.always,
                            onSelect = {
                                fallbackStrategy = FallbackStrategy.always
                                providerRepository.updateGroup(group.copy(fallbackStrategy = FallbackStrategy.always))
                            },
                            showDivider = false,
                        )
                    }
                }
            }

            // ── Members section header ───────────────────────────────
            item {
                SmallTitle(text = stringResource(R.string.model_group_detail_models_header_count, memberIds.size))
            }

            if (memberIds.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(
                            "No models in this group.",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            } else {
                itemsIndexed(memberIds, key = { _, id -> id }) { _, entryId ->
                    // [T-android-provider-voice] System voice sentinels aren't
                    // persisted entries — resolve them to virtual entries so the
                    // default Voice Input/Output members render normally instead
                    // of as stale rows (mirrors iOS system virtual entries).
                    val entry = config.modelEntries.find { it.id == entryId }
                        ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId)

                    if (entry == null) {
                        // Stale member: UUID no longer resolves to any ModelEntry.
                        ReorderableItem(reorderState, key = entryId) { _ ->
                            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                                BasicComponent(
                                    title = stringResource(R.string.model_group_detail_stale_member),
                                    summary = entryId,
                                    startAction = {
                                        Icon(Icons.Default.Warning,
                                            contentDescription = stringResource(R.string.model_group_detail_unavailable),
                                            tint = MiuixTheme.colorScheme.error)
                                    },
                                    endActions = {
                                        IconButton(onClick = {
                                            val newIds = memberIds.toMutableList().apply { remove(entryId) }
                                            memberIds = newIds
                                            providerRepository.updateGroup(group.copy(memberEntryIds = newIds.toMutableList()))
                                        }) {
                                            Icon(Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.common_remove),
                                                tint = MiuixTheme.colorScheme.error)
                                        }
                                    },
                                )
                            }
                        }
                    } else {
                        // Normal member row
                        val instance = config.instances.find { it.id == entry.providerInstanceId }
                        val instanceLabel = instance?.label
                        val providerDisabled = instance?.isEnabled == false
                        // [T-android-model-absence-grace] The provider stopped
                        // listing this model. The entry (and its overrides) is
                        // retained through the grace window, so say why the row
                        // is dimmed instead of letting it look normal — or, as
                        // before, letting it silently vanish from the group.
                        val notListed = entry.isUnavailableFromProvider
                        var showMenu by remember { mutableStateOf(false) }

                        ReorderableItem(reorderState, key = entryId) { _ ->
                            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                                BasicComponent(
                                    modifier = Modifier.alpha(if (providerDisabled || notListed) 0.4f else 1f),
                                    startAction = {
                                        IconButton(modifier = Modifier.draggableHandle(), onClick = {}) {
                                            Icon(Icons.Default.DragHandle,
                                                contentDescription = stringResource(R.string.model_group_detail_drag_to_reorder))
                                        }
                                    },
                                    endActions = {
                                        Box {
                                            val memberAnchor = rememberGlassPopupAnchor()
                                            com.openminis.app.ui.components.MinisToolbarIconButton(
                                                menuMotion = true,
                                                onClick = { showMenu = true },
                                                modifier = Modifier.glassPopupAnchor(memberAnchor, cornerRadius = 24.dp, floating = true),
                                            ) {
                                                Icon(Icons.Default.MoreVert,
                                                    contentDescription = stringResource(R.string.model_group_detail_more_options),
                                                    modifier = Modifier.glassPopupAnchorContent(memberAnchor))
                                            }
                                            com.openminis.app.ui.components.MinisMenu(
                                                glassAnchor = memberAnchor,
                                                anchorContent = { Icon(Icons.Default.MoreVert, contentDescription = null) },
                                                expanded = showMenu,
                                                onDismissRequest = { showMenu = false },
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.model_group_detail_remove_from_group), color = MiuixTheme.colorScheme.error) },
                                                    onClick = { showMenu = false; entryToRemove = entryId },
                                                )
                                            }
                                        }
                                    },
                                ) {
                                    // Preserve the full model name; the quota pill wraps below when needed.
                                    com.openminis.app.ui.components.FitOrWrapLine(
                                        lead = { Text(entry.model.displayName, style = MiuixTheme.textStyles.main) },
                                        tags = {
                                            if (instance != null && !providerDisabled) {
                                                com.openminis.app.ui.quota.ModelQuotaMemberPill(
                                                    instanceId = entry.providerInstanceId,
                                                    modelId = entry.model.id,
                                                ) { quotaSheetInstanceId = entry.providerInstanceId }
                                            }
                                        },
                                    )
                                    Text(instanceLabel.orEmpty(), style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
                                    if (providerDisabled || notListed) {
                                        Text(
                                            if (providerDisabled) "Provider disabled"
                                            else stringResource(R.string.model_not_listed_by_provider),
                                            color = MiuixTheme.colorScheme.error,
                                            style = MiuixTheme.textStyles.body2,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Add models: directly under the member list it acts on ────────
            // Previously this sat at the very bottom, below Session Defaults and
            // immediately above the destructive "Delete group" button. That put
            // an additive action two sections away from the list it appends to,
            // and pair-adjacent to a destructive one — easy to mis-tap and hard
            // to find. Placing it right after the list makes the relationship
            // obvious and leaves "Delete group" alone at the bottom.
            item {
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onAddModels,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    text = stringResource(R.string.model_group_detail_add_models),
                )
            }

            // ── Session Defaults (T312, mirrors iOS ModelGroupDetailView) ────
            item {
                SettingsSection(
                    header = stringResource(R.string.model_group_detail_session_defaults),
                    footer = stringResource(R.string.model_group_detail_session_defaults_footer),
                ) {
                    val reasoningEnabled = defaultThinkingLevel != null
                    SettingsSwitchRow(
                        title = stringResource(R.string.model_group_detail_enable_reasoning),
                        checked = reasoningEnabled,
                        onCheckedChange = { on ->
                            // iOS parity: ON defaults to MEDIUM, OFF clears to null.
                            val newLevel = if (on) ThinkingLevel.MEDIUM else null
                            defaultThinkingLevel = newLevel
                            providerRepository.updateGroup(
                                group.copy(defaultThinkingLevel = newLevel)
                            )
                        },
                        icon = Icons.Default.Psychology,
                        iconColor = Color(0xFFAF52DE),
                        showDivider = reasoningEnabled,
                    )
                    if (reasoningEnabled) {
                        // [T-android-thinking-level-arch] The group's thinking
                        // CEILING is the HIGHEST level any reasoning-capable
                        // member supports — max(), not min() (mirrors iOS
                        // e51fef5d/53388728). min() was wrong twice:
                        //   1. A non-reasoning member (e.g. an image model with
                        //      effectiveMaxThinkingLevel == OFF) dragged the
                        //      ceiling to OFF and emptied the Intensity picker,
                        //      leaving "Enable Reasoning" on with no selector.
                        //   2. Even among reasoning members it capped the group at
                        //      the WEAKEST model — a group of [Opus MAX, a HIGH
                        //      model] would only offer up to High, hiding the tiers
                        //      Opus supports.
                        // The per-request clamp (provider layer) still caps to the
                        // ACTUAL model at send time, so offering the true ceiling
                        // here is safe. Exclude OFF; fall back to XHIGH when no
                        // member reasons (only reached with reasoning enabled).
                        val groupMaxThinkingLevel = remember(group.memberEntryIds, config.modelEntries) {
                            group.memberEntryIds
                                .mapNotNull { entryId ->
                                    config.modelEntries.find { it.id == entryId }
                                        ?.effectiveMaxThinkingLevel
                                }
                                .filter { it != ThinkingLevel.OFF }
                                .maxByOrNull { it.rank }
                                ?: ThinkingLevel.XHIGH
                        }
                        val labelFor: @Composable (ThinkingLevel) -> String = { level ->
                            when (level) {
                                ThinkingLevel.LOW -> stringResource(R.string.model_group_detail_thinking_low)
                                ThinkingLevel.MEDIUM -> stringResource(R.string.model_group_detail_thinking_medium)
                                ThinkingLevel.HIGH -> stringResource(R.string.model_group_detail_thinking_high)
                                ThinkingLevel.XHIGH -> stringResource(R.string.model_group_detail_thinking_xhigh)
                                ThinkingLevel.MAX -> stringResource(R.string.model_group_detail_thinking_max)
                                ThinkingLevel.ULTRA -> stringResource(R.string.model_group_detail_thinking_ultra)
                                ThinkingLevel.OFF -> stringResource(R.string.model_group_detail_thinking_low)
                            }
                        }
                        // Steps exclude OFF (the toggle above carries OFF
                        // semantics) and everything above the group ceiling.
                        val cases = ThinkingLevel.entries
                            .filter { it != ThinkingLevel.OFF && it.rank <= groupMaxThinkingLevel.rank }
                            .map { it to labelFor(it) }
                        SettingsCardBlock {
                            Text(
                                text = stringResource(R.string.model_group_detail_intensity),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            val tabLabels = cases.map { (_, label) -> label }
                            TabRowWithContour(
                                tabs = tabLabels,
                                selectedTabIndex = cases.indexOfFirst { (level, _) -> defaultThinkingLevel == level }.coerceAtLeast(0),
                                onTabSelected = { index ->
                                    val level = cases[index].first
                                    defaultThinkingLevel = level
                                    providerRepository.updateGroup(group.copy(defaultThinkingLevel = level))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minWidth = preferenceTabMinWidth(tabLabels),
                                height = preferenceTabHeight(),
                                colors = TabRowDefaults.tabRowColors(backgroundColor = MiuixTheme.colorScheme.secondaryContainer),
                            )
                        }
                    }
                    val contextEnabled = contextLimitTokens != null
                    // T-android-ctx-slider-cap (port iOS fa77f493): the slider
                    // is now a fixed 7-stop ladder decoupled from member-model
                    // metadata. We no longer derive an `unlimitedTokens` cap
                    // from `max(member.contextWindow)`, because that field is
                    // unreliable (image-output / router models report no
                    // window, falling through to the 128K heuristic default)
                    // and silently shrunk the picker. Per-model enforcement
                    // still happens at runtime via the consumer of
                    // `contextLimitTokens` (min(groupLimit, modelLimit)).
                    SettingsSwitchRow(
                        title = stringResource(R.string.model_group_detail_limit_context_window),
                        checked = contextEnabled,
                        onCheckedChange = { on ->
                            if (on) {
                                // Restore last user-selected value verbatim,
                                // defaulting to 128K for first-time activation.
                                val restored = group.lastContextLimitTokens ?: 100_000
                                contextLimitTokens = restored
                                providerRepository.updateGroup(
                                    group.copy(
                                        contextLimitTokens = restored,
                                        lastContextLimitTokens = restored,
                                    )
                                )
                            } else {
                                // Stash the current value into lastContextLimitTokens
                                // before nulling contextLimitTokens, so toggle ON
                                // can restore it (even after navigating away).
                                val stash = contextLimitTokens
                                contextLimitTokens = null
                                providerRepository.updateGroup(
                                    group.copy(
                                        contextLimitTokens = null,
                                        lastContextLimitTokens = stash
                                            ?: group.lastContextLimitTokens,
                                    )
                                )
                            }
                        },
                        icon = Icons.Default.Memory,
                        iconColor = Color(0xFF5856D6),
                        showDivider = contextEnabled,
                    )
                    if (contextEnabled) {
                        ContextLimitSlider(
                            value = contextLimitTokens,
                            unlimitedLabel = stringResource(R.string.model_group_detail_unlimited),
                            maxContextLabel = stringResource(R.string.model_group_detail_max_context),
                            onValueChange = { newTokens ->
                                contextLimitTokens = newTokens
                                providerRepository.updateGroup(
                                    group.copy(
                                        contextLimitTokens = newTokens,
                                        lastContextLimitTokens = newTokens,
                                    )
                                )
                            },
                        )
                    }
                }
            }

            // ── Destructive action: delete group ──────────────────────
            // "Add models" used to share this block; it now sits directly under
            // the member list, so the only thing left at the bottom is the
            // destructive action, with nothing adjacent to mis-tap.
            item {
                Spacer(Modifier.height(20.dp))
                TextButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(
                        color = MiuixTheme.colorScheme.error,
                    ),
                    text = stringResource(R.string.model_group_detail_delete_group),
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    quotaSheetInstanceId?.let { id ->
        com.openminis.app.ui.quota.QuotaDetailSheet(instanceId = id, onDismiss = { quotaSheetInstanceId = null }, halfHeight = false)
    }

    // Confirm remove model from group
    if (entryToRemove != null) {
        val removingId = entryToRemove!!
        val removingEntry = config.modelEntries.find { it.id == removingId }
        WindowDialog(
            show = true,
            onDismissRequest = { entryToRemove = null },
            title = stringResource(R.string.model_group_detail_remove_model),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val name = removingEntry?.model?.displayName
                                    ?: stringResource(R.string.model_group_detail_remove_this_model_fallback)
                                Text(stringResource(R.string.model_group_detail_remove_named_model_confirm, name))
                com.openminis.app.ui.components.MinisDialogActions(
                    dismissButton = {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { entryToRemove = null },
                            text = stringResource(R.string.common_cancel),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val newIds = memberIds.toMutableList().apply { remove(removingId) }
                                memberIds = newIds
                                providerRepository.updateGroup(group.copy(memberEntryIds = newIds.toMutableList()))
                                entryToRemove = null
                            },
                            text = stringResource(R.string.common_remove),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.error),
                        )
                    },
                )
            }
        }
    }

    // Confirm delete group
    if (showDeleteDialog) {
        WindowDialog(
            show = true,
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(R.string.model_group_detail_delete_group),
            summary = stringResource(R.string.model_group_detail_delete_named_group_confirm, group.name),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                com.openminis.app.ui.components.MinisDialogActions(
                    dismissButton = {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showDeleteDialog = false },
                            text = stringResource(R.string.common_cancel),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                providerRepository.removeGroup(groupId)
                                showDeleteDialog = false
                                onBack()
                            },
                            text = stringResource(R.string.common_delete),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.error),
                        )
                    },
                )
            }
        }
    }
}

/**
 * Discrete-step max-context slider that mirrors iOS `ContextLimitSlider`
 * (ModelGroupDetailView.swift). Step labels: every 100K from 100K to 1M,
 * then Unlimited.
 *
 * Fixed ladder, always shown verbatim regardless of which models (if
 * any) are in the group. The previous behaviour clipped the ladder to the
 * largest member-declared context window, but model metadata is unreliable
 * (e.g. image-output / router models report no window and fall through to
 * the 128K heuristic default) and the per-model field is already enforced
 * at runtime when assembling requests via `min(groupLimit, modelLimit)`.
 * Keeping the ladder fixed makes the UI a pure user-intent slider —
 * "I want this group to cap context at N" — and removes the entire family
 * of bugs where adding/removing a model would silently shrink the picker.
 *
 * The "Unlimited" stop maps to `Int.MAX_VALUE` in the binding; the runtime
 * consumer treats anything `>= Int.MAX_VALUE` as "no override, use the
 * model's native window".
 */
private const val CONTEXT_LIMIT_UNLIMITED_SENTINEL: Int = Int.MAX_VALUE

private val CONTEXT_LIMIT_PRESETS = (100_000..1_000_000 step 100_000).toList()

private fun formatPresetLabel(tokens: Int): String =
    if (tokens >= 1_000_000) "${tokens / 1_000_000}M" else "${tokens / 1_000}K"

@Composable
private fun ContextLimitSlider(
    value: Int?,
    unlimitedLabel: String,
    maxContextLabel: String,
    onValueChange: (Int) -> Unit,
) {
    data class Step(val label: String, val tokens: Int)
    val steps = remember(unlimitedLabel) {
        CONTEXT_LIMIT_PRESETS.map { Step(formatPresetLabel(it), it) } +
            Step(unlimitedLabel, CONTEXT_LIMIT_UNLIMITED_SENTINEL)
    }
    // Track the selected stop by index. Snap to nearest stop <= value
    // so legacy saved values (e.g. 128K) land on the next stop down (100K).
    var selectedIndex by remember(steps, value) {
        mutableStateOf(
            when {
                value == null -> steps.lastIndex
                value >= CONTEXT_LIMIT_UNLIMITED_SENTINEL -> steps.lastIndex
                else -> steps.indexOfLast { it.tokens <= value }.takeIf { it >= 0 } ?: 0
            }
        )
    }
    val currentLabel: String = run {
        val v = value
        when {
            v == null -> steps.last().label
            v >= CONTEXT_LIMIT_UNLIMITED_SENTINEL -> unlimitedLabel
            else -> steps.firstOrNull { it.tokens == v }?.label ?: "${v / 1000}K"
        }
    }
    SettingsCardBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = maxContextLabel,
                style = MiuixTheme.textStyles.body1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = currentLabel,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = selectedIndex.toFloat(),
            onValueChange = { newIndex ->
                val idx = newIndex.roundToInt().coerceIn(0, steps.lastIndex)
                if (idx != selectedIndex) {
                    selectedIndex = idx
                    onValueChange(steps[idx].tokens)
                }
            },
            valueRange = 0f..steps.lastIndex.toFloat().coerceAtLeast(1f),
            steps = (steps.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            Text(
                steps.first().label,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                unlimitedLabel,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }
    }
}
