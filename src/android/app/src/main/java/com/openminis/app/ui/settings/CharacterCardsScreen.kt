package com.openminis.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.agent.CharacterCard
import com.openminis.app.agent.CharacterCardImport
import com.openminis.app.agent.CharacterCardScope
import com.openminis.app.agent.CharacterCardStore
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.safeLaunchPicker
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Page navigation. Two segments only; card scopes have a third (GROUP) value
 *  that belongs to a card's binding, not to the page. */
private enum class CharacterCardTab { GLOBAL, CUSTOM }

private const val MAX_IMPORT_BYTES = CharacterCardImport.MAX_INPUT_BYTES
private val MAX_IMPORT_MB = CharacterCardImport.MAX_INPUT_BYTES / (1024 * 1024)
private const val IMPORT_MIME_TYPES = "application/json,image/png,text/plain,application/octet-stream"

private sealed interface CardFileRead {
    data class Ok(val bytes: ByteArray) : CardFileRead
    data object Empty : CardFileRead
    data object TooLarge : CardFileRead
    data object Unreadable : CardFileRead
}

/**
 * Character-card settings: the Android replacement for the old SOUL page.
 *
 * Top-level navigation is a two-segment Minis control: "Global" lists scope
 * GLOBAL cards (the default when no custom card matches), "Custom" lists cards
 * bound to a model group or to a provider instance + model. Legacy
 * SoulPromptRule group/model rules are migrated by
 * [CharacterCardStore.importLegacy] into cards carrying `legacyRuleDerived`;
 * they appear here with a "Legacy rule" badge and are edited like any other
 * card, so they are never hidden, converted twice or deleted by this screen.
 *
 * Every write goes through [CharacterCardStore]; the UI never edits the JSON
 * file itself. Import only produces a draft preview and the added card is
 * always disabled, so an import cannot silently replace or activate anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterCardsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val app = remember(context) { context.applicationContext as? MinisApp }
    val providerRepository = remember(app) { app?.providerRepositoryOrNull }
    val emptyConfig = remember { MutableStateFlow(ProviderConfig()) }
    val config by (providerRepository?.config ?: emptyConfig).collectAsState()

    val groups = remember(config.modelGroups) {
        config.modelGroups.map { group ->
            CharacterCardGroupOption(group.id, group.name.ifBlank { group.id })
        }
    }
    val models = remember(config.modelEntries, config.instances) {
        config.modelEntries
            .filter { !it.isHidden }
            .mapNotNull { entry ->
                val instance = config.instances.firstOrNull {
                    it.id == entry.providerInstanceId
                } ?: return@mapNotNull null
                CharacterCardModelOption(
                    providerInstanceId = entry.providerInstanceId,
                    modelId = entry.model.id,
                    providerLabel = instance.label.ifBlank { entry.model.provider },
                    label = entry.model.displayName + " · " +
                        instance.label.ifBlank { entry.model.provider },
                )
            }
            .distinctBy { it.providerInstanceId + "\u0000" + it.modelId }
    }

    var cards by remember { mutableStateOf<List<CharacterCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(CharacterCardTab.GLOBAL) }

    var editingCard by remember { mutableStateOf<CharacterCard?>(null) }
    var pendingDelete by remember { mutableStateOf<CharacterCard?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    var importing by remember { mutableStateOf(false) }
    var importDraft by remember { mutableStateOf<CharacterCard?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importSaving by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadToken) {
        loading = true
        loadError = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                CharacterCardStore.invalidate()
                CharacterCardStore.loadReporting(context)
            }
        }
        result.onSuccess { report ->
            cards = report.cards
            // A corrupt document reads as "no cards" from load(); showing the
            // empty state there would invite a new card, and saving would be
            // refused by the store. Surface the issue instead and keep the
            // empty state off screen (the Add/Import actions are disabled).
            loadError = report.issue
            loading = false
        }.onFailure { throwable ->
            loadError = throwable.message ?: throwable.javaClass.simpleName
            loading = false
        }
    }

    var busyCardIds by remember { mutableStateOf(setOf<String>()) }

    suspend fun mutateCards(
        transform: (MutableList<CharacterCard>) -> Unit,
    ): Result<List<CharacterCard>> = withContext(Dispatchers.IO) {
        runCatching {
            // Read-modify-write under the store's own monitor. The config CLI
            // and backup restore can rewrite the card file while this screen
            // is open; saving a stale in-memory list would drop their edits.
            // The transform is therefore applied to a freshly loaded list and
            // only the card the user acted on is changed.
            synchronized(CharacterCardStore) {
                val latest = CharacterCardStore.load(context).toMutableList()
                transform(latest)
                CharacterCardStore.save(context, latest)
                latest.toList()
            }
        }
    }

    fun setEnabled(card: CharacterCard, enabled: Boolean) {
        if (card.id in busyCardIds) return
        busyCardIds = busyCardIds + card.id
        coroutineScope.launch {
            mutateCards { latest ->
                val index = latest.indexOfFirst { it.id == card.id }
                if (index >= 0) latest[index] = latest[index].copy(enabled = enabled)
            }.onSuccess { loaded -> cards = loaded }
                .onFailure { throwable ->
                    actionError = throwable.message ?: throwable.javaClass.simpleName
                }
            busyCardIds = busyCardIds - card.id
        }
    }

    val globalCards = cards.filter { it.scope == CharacterCardScope.GLOBAL }
    val customCards = cards.filter { it.scope != CharacterCardScope.GLOBAL }
    val visibleCards = if (tab == CharacterCardTab.GLOBAL) globalCards else customCards

    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        importError = null
        coroutineScope.launch {
            when (val read = withContext(Dispatchers.IO) { readCardFile(context, uri) }) {
                is CardFileRead.Ok -> {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { CharacterCardStore.importCard(read.bytes) }
                    }
                    importing = false
                    result.onSuccess { importDraft = it }
                        .onFailure {
                            importError = it.message ?: it.javaClass.simpleName
                        }
                }
                CardFileRead.Empty -> {
                    importing = false
                    importError = context.getString(R.string.character_cards_import_empty)
                }
                CardFileRead.TooLarge -> {
                    importing = false
                    importError = context.getString(
                        R.string.character_cards_import_too_large,
                        MAX_IMPORT_MB,
                    )
                }
                CardFileRead.Unreadable -> {
                    importing = false
                    importError = context.getString(R.string.character_cards_import_read_error)
                }
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = exportJson
        exportJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Could not open the selected file")
                }
            }
            result.onSuccess { notice = context.getString(R.string.character_card_export_success) }
                .onFailure {
                    actionError = context.getString(
                        R.string.character_card_export_failed,
                        it.message ?: it.javaClass.simpleName,
                    )
                }
        }
    }

    fun startExport(card: CharacterCard) {
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CharacterCardStore.exportCard(card) }
            }
            result.onSuccess { json ->
                exportJson = json
                val name = sanitizeExportName(card.name.ifBlank { "character-card" })
                exportPicker.safeLaunchPicker(
                    context.getString(R.string.character_card_export_filename, name),
                    context,
                    "CharacterCardExport",
                )
            }.onFailure {
                actionError = context.getString(
                    R.string.character_card_export_failed,
                    it.message ?: it.javaClass.simpleName,
                )
            }
        }
    }

    fun startImport() {
        importPicker.safeLaunchPicker(
            IMPORT_MIME_TYPES.split(',').toTypedArray(),
            context,
            "CharacterCardImport",
        )
    }

    if (editingCard != null) {
        val initial = editingCard!!
        val wasExisting = cards.any { it.id == initial.id }
        CharacterCardEditorScreen(
            initial = initial,
            groups = groups,
            models = models,
            onCancel = { editingCard = null },
            onSave = { updated ->
                mutateCards { latest ->
                    val index = latest.indexOfFirst { it.id == updated.id }
                    when {
                        index >= 0 -> latest[index] = updated
                        // A new card (opened from "New") is appended. An
                        // existing card that vanished was deleted by the CLI
                        // or a restore while the editor was open; resurrecting
                        // it would silently undo that delete.
                        !wasExisting -> latest.add(updated)
                        else -> error(
                            context.getString(R.string.character_card_editor_deleted_elsewhere),
                        )
                    }
                }.map { loaded -> cards = loaded }
            },
        )
        return
    }

    SettingsScaffold(
        title = stringResource(R.string.character_cards_title),
        onBack = onBack,
        scrollable = false,
        actions = {
            IconButton(
                onClick = {
                    editingCard = if (tab == CharacterCardTab.GLOBAL) {
                        CharacterCard(scope = CharacterCardScope.GLOBAL)
                    } else {
                        CharacterCard(scope = CharacterCardScope.MODEL)
                    }
                },
                enabled = loadError == null,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.character_cards_new),
                )
            }
            Box {
                var overflowOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.character_cards_import_json_png),
                    )
                }
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.character_cards_import_json_png)) },
                        enabled = !importing && loadError == null,
                        onClick = {
                            overflowOpen = false
                            startImport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.character_cards_refresh)) },
                        enabled = !loading,
                        onClick = {
                            overflowOpen = false
                            reloadToken += 1
                        },
                    )
                }
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 8.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val tabs = listOf(
                        CharacterCardTab.GLOBAL to R.string.character_cards_tab_global,
                        CharacterCardTab.CUSTOM to R.string.character_cards_tab_custom,
                    )
                    tabs.forEachIndexed { index, (value, labelRes) ->
                        SegmentedButton(
                            selected = tab == value,
                            onClick = { tab = value },
                            shape = SegmentedButtonDefaults.itemShape(index, tabs.size),
                        ) {
                            Text(stringResource(labelRes), fontSize = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        if (tab == CharacterCardTab.GLOBAL) {
                            R.string.character_cards_tab_global_hint
                        } else {
                            R.string.character_cards_tab_custom_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.character_cards_count,
                        globalCards.size,
                        customCards.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    loading -> SettingsSection {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.character_cards_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    loadError != null -> SettingsSection {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.character_cards_load_error,
                                    loadError.orEmpty(),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(10.dp))
                            MinisButton(
                                onClick = {
                                    loadError = null
                                    reloadToken += 1
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.character_cards_retry)) }
                        }
                    }

                    else -> {
                        importingNotice(importing)

                        notice?.let { message ->
                            SettingsSection {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    MinisTextButton(onClick = { notice = null }) {
                                        Text(stringResource(R.string.character_cards_ok))
                                    }
                                }
                            }
                        }

                        if (visibleCards.isEmpty()) {
                            CharacterCardsEmptyState(
                                custom = tab == CharacterCardTab.CUSTOM,
                                onNew = {
                                    editingCard = if (tab == CharacterCardTab.GLOBAL) {
                                        CharacterCard(scope = CharacterCardScope.GLOBAL)
                                    } else {
                                        CharacterCard(scope = CharacterCardScope.MODEL)
                                    }
                                },
                                onImport = { startImport() },
                            )
                        } else {
                            SettingsSection(
                                header = stringResource(
                                    R.string.character_cards_count,
                                    globalCards.size,
                                    customCards.size,
                                ),
                            ) {
                                visibleCards.forEachIndexed { index, card ->
                                    CharacterCardRow(
                                        card = card,
                                        targetLabel = cardTargetLabel(
                                            card = card,
                                            groups = groups,
                                            models = models,
                                        ),
                                        targetMissing = cardTargetMissing(card, groups, models),
                                        busy = card.id in busyCardIds,
                                        onOpen = { editingCard = card },
                                        onToggle = { enabled -> setEnabled(card, enabled) },
                                        onExport = { startExport(card) },
                                        onDelete = { pendingDelete = card },
                                    )
                                    if (index < visibleCards.lastIndex) {
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                        )
                                    }
                                }
                            }
                        }

                        if (tab == CharacterCardTab.CUSTOM) {
                            if (actionError != null) {
                                Text(
                                    text = actionError.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.character_cards_legacy_migrated_note,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                            )
                        }

                        Spacer(Modifier.height(28.dp))
                    }
                }
            }
        }
    }

    if (importDraft != null) {
        CharacterCardImportPreviewSheet(
            draft = importDraft!!,
            groups = groups,
            models = models,
            saving = importSaving,
            error = importError,
            onDismiss = {
                if (!importSaving) {
                    importDraft = null
                    importError = null
                }
            },
            onAdd = add@{ scope, groupId, providerInstanceId, modelId ->
                val base = importDraft ?: return@add
                importSaving = true
                importError = null
                coroutineScope.launch {
                    mutateCards { latest ->
                        var candidate = base.copy(
                            enabled = false,
                            scope = scope,
                            groupId = groupId,
                            providerInstanceId = providerInstanceId,
                            modelId = modelId,
                        )
                        // A config CLI import could have used the same id while
                        // this preview was open; keep both cards rather than
                        // overwriting.
                        if (latest.any { it.id == candidate.id }) {
                            candidate = candidate.copy(
                                id = java.util.UUID.randomUUID().toString(),
                            )
                        }
                        latest.add(candidate)
                    }.onSuccess { loaded ->
                        cards = loaded
                        importSaving = false
                        importDraft = null
                        notice = context.getString(R.string.character_cards_import_success)
                    }.onFailure {
                        importSaving = false
                        importError = it.message ?: it.javaClass.simpleName
                    }
                }
            },
        )
    }

    if (importing && importDraft == null) {
        MinisAlertDialog(
            onDismissRequest = {},
            title = stringResource(R.string.character_cards_import),
            text = stringResource(R.string.character_cards_importing),
            confirmText = stringResource(R.string.character_cards_ok),
            onConfirm = {},
        )
    }

    importError?.takeIf { importDraft == null }?.let { message ->
        MinisAlertDialog(
            onDismissRequest = { importError = null },
            title = stringResource(R.string.character_cards_import),
            text = stringResource(R.string.character_cards_import_error, message),
            confirmText = stringResource(R.string.character_cards_ok),
            onConfirm = { importError = null },
        )
    }

    pendingDelete?.let { card ->
        MinisAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = stringResource(R.string.character_cards_delete_title),
            text = stringResource(
                R.string.character_cards_delete_body,
                card.name.ifBlank { stringResource(R.string.character_cards_import_name_fallback) },
            ),
            confirmText = stringResource(R.string.character_cards_delete_confirm),
            onConfirm = {
                coroutineScope.launch {
                    mutateCards { latest ->
                        latest.removeAll { it.id == card.id }
                    }.onSuccess { loaded ->
                        cards = loaded
                        pendingDelete = null
                    }.onFailure {
                        actionError = context.getString(
                            R.string.character_cards_delete_failed,
                            it.message ?: it.javaClass.simpleName,
                        )
                        pendingDelete = null
                    }
                }
            },
            dismissText = stringResource(R.string.character_cards_cancel),
            onDismiss = { pendingDelete = null },
            isDestructive = true,
        )
    }

    actionError?.let { message ->
        MinisAlertDialog(
            onDismissRequest = { actionError = null },
            title = stringResource(R.string.character_cards_title),
            text = message,
            confirmText = stringResource(R.string.character_cards_ok),
            onConfirm = { actionError = null },
        )
    }

}

@Composable
private fun importingNotice(importing: Boolean) {
    if (!importing) return
    SettingsSection {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.character_cards_importing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CharacterCardsEmptyState(
    custom: Boolean,
    onNew: () -> Unit,
    onImport: () -> Unit,
) {
    SettingsSection {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    if (custom) {
                        R.string.character_cards_empty_custom_title
                    } else {
                        R.string.character_cards_empty_global_title
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (custom) {
                        R.string.character_cards_empty_custom_body
                    } else {
                        R.string.character_cards_empty_global_body
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            MinisButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.character_cards_new))
            }
            Spacer(Modifier.height(8.dp))
            MinisOutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.character_cards_import_json_png))
            }
        }
    }
}

@Composable
private fun CharacterCardRow(
    card: CharacterCard,
    targetLabel: String?,
    targetMissing: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(card.id) { mutableStateOf(false) }
    val preview = remember(card) {
        listOf(
            card.description,
            card.personality,
            card.scenario,
            card.systemPrompt,
            card.firstMessage,
        ).firstOrNull { it.isNotBlank() }?.replace('\n', ' ')?.trim().orEmpty()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                SoulIconGlyph(icon = card.icon, sizeDp = 36.dp, emojiSp = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.name.ifBlank {
                        stringResource(R.string.character_cards_import_name_fallback)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (card.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            when (card.scope) {
                                CharacterCardScope.GLOBAL -> R.string.character_cards_badge_global
                                CharacterCardScope.GROUP -> R.string.character_cards_badge_group
                                CharacterCardScope.MODEL -> R.string.character_cards_badge_model
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    if (card.legacyRuleDerived) {
                        Text(
                            text = stringResource(R.string.character_cards_badge_legacy),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.character_cards_edit),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.character_cards_edit)) },
                        onClick = {
                            menuOpen = false
                            onOpen()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.character_cards_export)) },
                        onClick = {
                            menuOpen = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.character_cards_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }

        if (card.scope != CharacterCardScope.GLOBAL) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = targetLabel ?: stringResource(R.string.character_card_editor_bind_none),
                style = MaterialTheme.typography.bodySmall,
                color = if (targetMissing) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 54.dp),
            )
            if (targetMissing) {
                Text(
                    text = stringResource(R.string.character_cards_binding_missing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 54.dp),
                )
            }
        }
        if (preview.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 54.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (card.enabled) {
                        R.string.character_cards_enabled
                    } else {
                        R.string.character_cards_disabled
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            SettingsSwitch(
                checked = card.enabled,
                onCheckedChange = onToggle,
                enabled = !busy,
            )
        }
    }
}

private fun cardTargetLabel(
    card: CharacterCard,
    groups: List<CharacterCardGroupOption>,
    models: List<CharacterCardModelOption>,
): String? = when (card.scope) {
    CharacterCardScope.GLOBAL -> null
    CharacterCardScope.GROUP -> card.groupId?.let { id ->
        groups.firstOrNull { it.id == id }?.label ?: id
    }
    CharacterCardScope.MODEL -> card.providerInstanceId?.let { instance ->
        card.modelId?.let { modelId ->
            models.firstOrNull {
                it.providerInstanceId == instance && it.modelId == modelId
            }?.label ?: "$instance / $modelId"
        }
    }
}

private fun cardTargetMissing(
    card: CharacterCard,
    groups: List<CharacterCardGroupOption>,
    models: List<CharacterCardModelOption>,
): Boolean = when (card.scope) {
    CharacterCardScope.GLOBAL -> false
    CharacterCardScope.GROUP -> card.groupId?.let { id ->
        groups.none { it.id == id }
    } == true
    CharacterCardScope.MODEL -> card.providerInstanceId != null &&
        card.modelId != null &&
        models.none {
            it.providerInstanceId == card.providerInstanceId && it.modelId == card.modelId
        }
}

private fun readCardFile(context: Context, uri: Uri): CardFileRead {
    return try {
        val input = context.contentResolver.openInputStream(uri)
            ?: return CardFileRead.Unreadable
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) return CardFileRead.TooLarge
                output.write(buffer, 0, read)
            }
            if (total == 0) CardFileRead.Empty else CardFileRead.Ok(output.toByteArray())
        }
    } catch (t: Throwable) {
        CardFileRead.Unreadable
    }
}

private fun sanitizeExportName(raw: String): String {
    val cleaned = raw
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .trim('.')
        .take(48)
    return cleaned.ifBlank { "character-card" }
}
