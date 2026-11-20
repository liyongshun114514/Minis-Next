package com.openminis.app.ui.onboarding

import com.openminis.app.ui.components.minisJellyPress

import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.openminis.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import com.openminis.app.ui.components.MinisPageScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import com.openminis.app.ui.components.minisPageScrollPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.openminis.app.ui.components.miuix.DropdownMenu
import com.openminis.app.ui.components.miuix.DropdownMenuItem
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.repository.ProviderRepository
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.TextField
import com.openminis.app.ui.components.MinisTopAppBar as TopAppBar

/**
 * Onboarding step 2: pick 1-3 models from configured providers
 * and create a "Default Models" group. Mirrors iOS OnboardingModelSelectionView.
 */
@Composable
fun OnboardingModelSelectionScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    /** [T-onboarding-model-fetch-fallback] Open Add Custom Model for this provider. */
    onAddCustomModel: (instanceId: String) -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()
    val selected = remember { mutableStateListOf<String>() }
    var searchText by remember { mutableStateOf("") }

    val enabledInstances = config.instances.filter { it.isEnabled }
    val enabledInstanceIds = enabledInstances.map { it.id }.toSet()
    val allEntries = config.modelEntries.filter {
        it.providerInstanceId in enabledInstanceIds && !it.isHidden
    }

    // [T-onboarding-model-fetch-fallback] State of the page's own bounded
    // fetch, which runs only while the list is empty. Saveable so a trip to
    // Add Custom Model and back keeps the failure section instead of
    // restarting a 20 s spinner.
    var fetching by remember { mutableStateOf(false) }
    var fetchAttempted by rememberSaveable { mutableStateOf(false) }
    // Non-null = the fetch finished and the page is still empty; the text is
    // the provider's own error, or "" when there is nothing beyond the headline.
    var failureDetail by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val timeoutText = stringResource(
        R.string.onboarding_models_fetch_timeout,
        (OnboardingModelFetch.TIMEOUT_MS / 1000).toInt(),
    )

    fun visibleEntryCount(instanceIds: Set<String>): Int =
        providerRepository.config.value.modelEntries.count { it.providerInstanceId in instanceIds && !it.isHidden }

    fun runBoundedFetch() {
        if (fetching) return // Retry while in flight is a no-op.
        val current = providerRepository.config.value
        val targets = current.instances.filter { inst ->
            inst.isEnabled && current.modelEntries.none { it.providerInstanceId == inst.id && !it.isHidden }
        }
        fetchAttempted = true
        failureDetail = null
        if (targets.isEmpty()) {
            // No enabled provider at all: nothing to wait for.
            if (visibleEntryCount(enabledInstanceIds) == 0) failureDetail = ""
            return
        }
        fetching = true
        scope.launch {
            try {
                val result = OnboardingModelFetch.run(targets) { inst ->
                    var vendorError: String? = null
                    withContext(Dispatchers.IO) {
                        providerRepository.refreshModels(inst, onVendorError = { vendorError = it })
                    }
                    vendorError
                }
                // Failure is a property of the EMPTY page, not of a provider: a
                // partial result simply lists what arrived.
                val ids = providerRepository.config.value.instances.filter { it.isEnabled }.map { it.id }.toSet()
                if (visibleEntryCount(ids) == 0) {
                    failureDetail = if (result.timedOut) timeoutText else result.errors.distinct().joinToString("\n")
                }
            } finally {
                fetching = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (fetchAttempted) return@LaunchedEffect
        if (allEntries.isEmpty()) {
            runBoundedFetch()
            return@LaunchedEffect
        }
        // Normal path: refresh model lists for every enabled instance when the
        // screen appears, so the user sees the live provider catalog (not just
        // the built-in placeholder list seeded by addInstance). Mirrors iOS
        // fetchModelsWithFallback chain.
        for (instance in enabledInstances) {
            // [T-provider-refresh-outlives-screen] (GH#265) Repository scope, so
            // tapping Continue quickly does not cancel the fetch half-way.
            providerRepository.triggerAsyncModelReconcile(instance.id)
        }
    }
    val filteredEntries = if (searchText.isBlank()) allEntries else {
        val q = searchText.lowercase()
        allEntries.filter {
            it.model.displayName.lowercase().contains(q) || it.model.id.lowercase().contains(q)
        }
    }
    val grouped = filteredEntries.groupBy { it.providerInstanceId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.onboarding_select_models_title),
                navigationIcon = {
                    com.openminis.app.ui.components.MinisToolbarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = onBack,
                        text = stringResource(R.string.common_skip),
                        colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                    )
                },
                expandable = false,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_select_models_subtitle),
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
            Spacer(Modifier.height(12.dp))

            TextField(
                value = searchText,
                onValueChange = { searchText = it },
                singleLine = true,
                label = stringResource(R.string.onboarding_filter_models),
                useLabelAsPlaceholder = false,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            if (allEntries.isEmpty() && !fetching && failureDetail != null) {
                ModelFetchFailure(
                    detail = failureDetail.orEmpty(),
                    instances = enabledInstances.map { it.id to it.label },
                    onRetry = { runBoundedFetch() },
                    onAddCustomModel = onAddCustomModel,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (allEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.onboarding_loading_models),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for ((instanceId, entries) in grouped) {
                        val instance = config.instances.find { it.id == instanceId }
                        item(key = "header_$instanceId") {
                            Text(
                                instance?.label ?: instanceId,
                                style = MiuixTheme.textStyles.button,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(entries, key = { it.id }) { entry ->
                            val isSelected = entry.id in selected
                            val selectionIndex = selected.indexOf(entry.id)
                            val canSelect = selected.size < 3 || isSelected

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canSelect) {
                                        if (isSelected) selected.remove(entry.id)
                                        else if (selected.size < 3) selected.add(entry.id)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) MiuixTheme.colorScheme.primary
                                            else MiuixTheme.colorScheme.surfaceVariant
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Text(
                                            "${selectionIndex + 1}",
                                            color = MiuixTheme.colorScheme.onPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.model.displayName,
                                        style = MiuixTheme.textStyles.body1,
                                    )
                                    Text(
                                        entry.model.id,
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = {
                    if (selected.isNotEmpty()) {
                        val group = ModelGroup(name = "Default Models")
                        group.memberEntryIds.addAll(selected)
                        providerRepository.addGroup(group)
                        if (config.defaultPrimaryGroupId == null) {
                            providerRepository.defaultPrimaryGroupId = group.id
                        }
                    }
                    onBack()
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                text = stringResource(R.string.onboarding_done_count, selected.size),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * [T-onboarding-model-fetch-fallback] Shown when the bounded fetch finished
 * and the page is still empty: a headline, the provider's own error under it
 * (omitted when it would only repeat the headline), Retry, and Add Model
 * Manually. A custom model belongs to exactly one provider, so with several
 * enabled providers the button opens a menu to pick one; with none (every
 * provider disabled) it hides and Skip remains.
 */
@Composable
private fun ModelFetchFailure(
    detail: String,
    instances: List<Pair<String, String>>,
    onRetry: () -> Unit,
    onAddCustomModel: (instanceId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            val headline = stringResource(R.string.onboarding_models_fetch_failed)
            Text(
                headline,
                style = MiuixTheme.textStyles.subtitle,
                textAlign = TextAlign.Center,
            )
            if (detail.isNotBlank() && detail != headline) {
                Spacer(Modifier.height(6.dp))
                Text(
                    detail,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 6,
                )
            }
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onRetry,
                text = stringResource(R.string.onboarding_models_retry),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            if (instances.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Box {
                    TextButton(
                        modifier = Modifier.minisJellyPress(instances.size > 1),
                        onClick = {
                        if (instances.size == 1) onAddCustomModel(instances.first().first) else pickerOpen = true
                    },
                        text = stringResource(R.string.onboarding_models_add_manually),
                        colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                    )
                    DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                        for ((id, label) in instances) {
                            DropdownMenuItem(
                                text = { Text(label.ifBlank { id }) },
                                onClick = {
                                    pickerOpen = false
                                    onAddCustomModel(id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
