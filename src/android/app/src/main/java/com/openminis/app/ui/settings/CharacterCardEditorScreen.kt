package com.openminis.app.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.CharacterCard
import com.openminis.app.agent.CharacterCardScope
import com.openminis.app.agent.SoulIcon
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.safeLaunchPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Per-field cap. Character cards are text, but an accidental 10 MB paste is
 *  still a storage and prompt-assembly problem; validation refuses rather than
 *  silently truncating what the user wrote. */
internal const val CHARACTER_CARD_MAX_FIELD_CHARS = 100_000

/**
 * Full-screen card editor. Field groups follow the Tavern card vocabulary:
 * identity → character → dialogue → prompt → notes. All content is treated as
 * data; this screen never interprets or executes it.
 *
 * The save callback is suspending and returns a Result so a failed IO write
 * keeps the editor open with the user's text intact, instead of closing and
 * losing an edit that never reached disk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterCardEditorScreen(
    initial: CharacterCard,
    groups: List<CharacterCardGroupOption>,
    models: List<CharacterCardModelOption>,
    onCancel: () -> Unit,
    onSave: suspend (CharacterCard) -> Result<Unit>,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var icon by remember(initial.id) { mutableStateOf(initial.icon) }
    var enabled by remember(initial.id) { mutableStateOf(initial.enabled) }
    var cardScope by remember(initial.id) { mutableStateOf(initial.scope) }
    var groupId by remember(initial.id) { mutableStateOf(initial.groupId) }
    var providerInstanceId by remember(initial.id) { mutableStateOf(initial.providerInstanceId) }
    var modelId by remember(initial.id) { mutableStateOf(initial.modelId) }
    var description by remember(initial.id) { mutableStateOf(initial.description) }
    var personality by remember(initial.id) { mutableStateOf(initial.personality) }
    var scenario by remember(initial.id) { mutableStateOf(initial.scenario) }
    var firstMessage by remember(initial.id) { mutableStateOf(initial.firstMessage) }
    var messageExamples by remember(initial.id) { mutableStateOf(initial.messageExamples) }
    var systemPrompt by remember(initial.id) { mutableStateOf(initial.systemPrompt) }
    var postHistoryInstructions by remember(initial.id) {
        mutableStateOf(initial.postHistoryInstructions)
    }
    var creatorNotes by remember(initial.id) { mutableStateOf(initial.creatorNotes) }
    var tagsText by remember(initial.id) { mutableStateOf(initial.tags.joinToString(", ")) }
    var style by remember(initial.id) { mutableStateOf(initial.style) }
    var lang by remember(initial.id) { mutableStateOf(initial.lang) }

    var showErrors by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showDiscard by remember { mutableStateOf(false) }
    var showAvatarMenu by remember { mutableStateOf(false) }
    var showEmojiDialog by remember { mutableStateOf(false) }
    var iconError by remember { mutableStateOf<String?>(null) }
    var showTargetPicker by remember { mutableStateOf(false) }

    fun buildCard(): CharacterCard = initial.copy(
        name = name.trim(),
        icon = icon,
        enabled = enabled,
        scope = cardScope,
        groupId = if (cardScope == CharacterCardScope.GROUP) groupId?.trim()?.ifBlank { null } else null,
        providerInstanceId = if (cardScope == CharacterCardScope.MODEL) {
            providerInstanceId?.trim()?.ifBlank { null }
        } else {
            null
        },
        modelId = if (cardScope == CharacterCardScope.MODEL) {
            modelId?.trim()?.ifBlank { null }
        } else {
            null
        },
        description = description,
        personality = personality,
        scenario = scenario,
        firstMessage = firstMessage,
        messageExamples = messageExamples,
        systemPrompt = systemPrompt,
        postHistoryInstructions = postHistoryInstructions,
        creatorNotes = creatorNotes,
        tags = parseCardTags(tagsText),
        style = style,
        lang = lang.ifBlank { "auto" },
    )

    val draft = buildCard()
    val nameError = draft.name.isBlank()
    val groupMissing = cardScope == CharacterCardScope.GROUP && draft.groupId == null
    val modelMissing = cardScope == CharacterCardScope.MODEL &&
        (draft.providerInstanceId == null || draft.modelId == null)
    // A target can be well-formed but deleted from the provider config. That
    // card must never be ENABLED (it would silently never match), but it may
    // still be saved disabled so the user keeps the text and can rebind later.
    val targetMissing = when (cardScope) {
        CharacterCardScope.GROUP -> draft.groupId?.let { id ->
            groups.none { it.id == id }
        } == true
        CharacterCardScope.MODEL -> draft.providerInstanceId != null &&
            draft.modelId != null &&
            models.none {
                it.providerInstanceId == draft.providerInstanceId && it.modelId == draft.modelId
            }
        CharacterCardScope.GLOBAL -> false
    }
    val targetBlocksEnable = targetMissing && enabled
    val tooLong = listOf(
        name,
        description,
        personality,
        scenario,
        firstMessage,
        messageExamples,
        systemPrompt,
        postHistoryInstructions,
        creatorNotes,
        tagsText,
        style,
        lang,
    ).any { it.length > CHARACTER_CARD_MAX_FIELD_CHARS }

    // Dirty is a field-by-field comparison against what was loaded, not a
    // normalization comparison: opening a card with tags written as
    // "a,b" must not look modified before the user types anything.
    val isDirty = name != initial.name ||
        icon != initial.icon ||
        enabled != initial.enabled ||
        cardScope != initial.scope ||
        groupId != initial.groupId ||
        providerInstanceId != initial.providerInstanceId ||
        modelId != initial.modelId ||
        description != initial.description ||
        personality != initial.personality ||
        scenario != initial.scenario ||
        firstMessage != initial.firstMessage ||
        messageExamples != initial.messageExamples ||
        systemPrompt != initial.systemPrompt ||
        postHistoryInstructions != initial.postHistoryInstructions ||
        creatorNotes != initial.creatorNotes ||
        tagsText != initial.tags.joinToString(", ") ||
        style != initial.style ||
        lang != initial.lang

    fun attemptCancel() {
        if (saving) return
        if (isDirty) showDiscard = true else onCancel()
    }

    fun attemptSave() {
        showErrors = true
        if (nameError || groupMissing || modelMissing || targetBlocksEnable || tooLong || saving) return
        saving = true
        saveError = null
        coroutineScope.launch {
            val result = onSave(buildCard())
            saving = false
            result.onSuccess { onCancel() }
                .onFailure { saveError = it.message ?: it.javaClass.simpleName }
        }
    }

    BackHandler { attemptCancel() }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                val bitmap = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
                if (bitmap == null) null else SoulIcon.encode(bitmap)
            }
            when (result) {
                null -> iconError = context.getString(R.string.character_card_editor_avatar_error)
                is SoulIcon.EncodeResult.Success -> icon = result.dataUri
                is SoulIcon.EncodeResult.Failure -> {
                    iconError = context.getString(
                        if (result.reason == SoulIcon.Rejection.TOO_LARGE) {
                            R.string.character_card_editor_avatar_too_large
                        } else {
                            R.string.character_card_editor_avatar_error
                        },
                    )
                }
            }
        }
    }

    val targetLabel: String = when (cardScope) {
        CharacterCardScope.GLOBAL -> ""
        CharacterCardScope.GROUP -> {
            val id = buildCard().groupId
            if (id == null) {
                stringResource(R.string.character_card_editor_bind_none)
            } else {
                groups.firstOrNull { it.id == id }?.label
                    ?: stringResource(R.string.character_cards_target_missing_id, id)
            }
        }
        CharacterCardScope.MODEL -> {
            val card = buildCard()
            val instanceId = card.providerInstanceId
            val model = card.modelId
            if (instanceId == null || model == null) {
                stringResource(R.string.character_card_editor_bind_none)
            } else {
                models.firstOrNull {
                    it.providerInstanceId == instanceId && it.modelId == model
                }?.label ?: "$instanceId / $model"
            }
        }
    }

    SettingsScaffold(
        title = stringResource(
            if (initial.name.isBlank()) {
                R.string.character_card_editor_new
            } else {
                R.string.character_card_editor_edit
            },
        ),
        navigation = {
            MinisTextButton(onClick = { attemptCancel() }, enabled = !saving) {
                Text(stringResource(R.string.character_cards_cancel))
            }
        },
        actions = {
            MinisTextButton(onClick = { attemptSave() }, enabled = !saving) {
                Text(
                    stringResource(
                        if (saving) R.string.character_card_editor_saving
                        else R.string.character_cards_save,
                    ),
                )
            }
        },
        centerTitle = true,
    ) {
        SettingsSection(header = stringResource(R.string.character_card_editor_section_basic)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        )
                        .clickable { showAvatarMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    SoulIconGlyph(icon = icon, sizeDp = 34.dp, emojiSp = 26.sp)
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(3.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.character_card_editor_avatar),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.character_card_editor_enabled_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { showAvatarMenu = true }) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringResource(
                                R.string.character_card_editor_avatar,
                            ),
                        )
                    }
                    DropdownMenu(
                        expanded = showAvatarMenu,
                        onDismissRequest = { showAvatarMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.character_card_editor_avatar_image)) },
                            onClick = {
                                showAvatarMenu = false
                                avatarPicker.safeLaunchPicker(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                    context,
                                    "CharacterCardEditor",
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.character_card_editor_avatar_emoji)) },
                            onClick = {
                                showAvatarMenu = false
                                showEmojiDialog = true
                            },
                        )
                        if (icon.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.character_card_editor_avatar_default)) },
                                onClick = {
                                    showAvatarMenu = false
                                    icon = ""
                                },
                            )
                        }
                    }
                }
            }
            iconError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
            CardTextField(
                labelRes = R.string.character_card_editor_name,
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                isError = showErrors && nameError,
                errorText = stringResource(R.string.character_card_editor_name_required),
            )
            SettingsSwitchRow(
                title = stringResource(R.string.character_card_editor_enabled),
                subtitle = stringResource(R.string.character_card_editor_enabled_hint),
                checked = enabled,
                onCheckedChange = { enabled = it },
                showDivider = false,
            )
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.character_card_editor_scope),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                CharacterCardScopeSelector(
                    scope = cardScope,
                    onScopeChange = { cardScope = it },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        if (cardScope == CharacterCardScope.GLOBAL) {
                            R.string.character_card_editor_scope_global_hint
                        } else {
                            R.string.character_cards_tab_custom_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (cardScope != CharacterCardScope.GLOBAL) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showTargetPicker = true }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    if (cardScope == CharacterCardScope.GROUP) {
                                        R.string.character_card_editor_bind_group
                                    } else {
                                        R.string.character_card_editor_bind_model
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = targetLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (targetMissing) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (targetMissing) {
                                Text(
                                    text = stringResource(R.string.character_card_editor_bind_missing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (showErrors && targetBlocksEnable) {
                                Text(
                                    text = stringResource(
                                        R.string.character_card_editor_enable_requires_target,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (showErrors && groupMissing) {
                    Text(
                        text = stringResource(R.string.character_card_editor_bind_required_group),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (showErrors && modelMissing) {
                    Text(
                        text = stringResource(R.string.character_card_editor_bind_required_model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.character_card_editor_section_character),
            footer = stringResource(R.string.character_card_editor_character_footer),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                CardTextField(
                    labelRes = R.string.character_card_editor_description,
                    value = description,
                    onValueChange = { description = it },
                )
                Spacer(Modifier.height(10.dp))
                CardTextField(
                    labelRes = R.string.character_card_editor_personality,
                    value = personality,
                    onValueChange = { personality = it },
                )
                Spacer(Modifier.height(10.dp))
                CardTextField(
                    labelRes = R.string.character_card_editor_scenario,
                    value = scenario,
                    onValueChange = { scenario = it },
                )
            }
        }

        SettingsSection(header = stringResource(R.string.character_card_editor_section_dialogue)) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                CardTextField(
                    labelRes = R.string.character_card_editor_first_message,
                    value = firstMessage,
                    onValueChange = { firstMessage = it },
                )
                Spacer(Modifier.height(10.dp))
                CardTextField(
                    labelRes = R.string.character_card_editor_message_examples,
                    value = messageExamples,
                    onValueChange = { messageExamples = it },
                    minLines = 4,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.character_card_editor_section_prompt),
            footer = stringResource(R.string.character_card_editor_notes_footer),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                CardTextField(
                    labelRes = R.string.character_card_editor_system_prompt,
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    minLines = 4,
                    monospace = true,
                )
                Spacer(Modifier.height(10.dp))
                CardTextField(
                    labelRes = R.string.character_card_editor_post_history,
                    value = postHistoryInstructions,
                    onValueChange = { postHistoryInstructions = it },
                    minLines = 3,
                    monospace = true,
                )
            }
        }

        SettingsSection(header = stringResource(R.string.character_card_editor_section_notes)) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                CardTextField(
                    labelRes = R.string.character_card_editor_creator_notes,
                    value = creatorNotes,
                    onValueChange = { creatorNotes = it },
                )
                Spacer(Modifier.height(10.dp))
                CardTextField(
                    labelRes = R.string.character_card_editor_tags,
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    singleLine = true,
                    hint = stringResource(R.string.character_card_editor_tags_hint),
                )
                Spacer(Modifier.height(10.dp))
                CardTextField(
                    labelRes = R.string.character_card_editor_style,
                    value = style,
                    onValueChange = { style = it },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                CardTextField(
                    labelRes = R.string.character_card_editor_lang,
                    value = lang,
                    onValueChange = { lang = it },
                    singleLine = true,
                    hint = stringResource(R.string.character_card_editor_lang_hint),
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    if (showTargetPicker) {
        CharacterCardTargetPickerSheet(
            scope = cardScope,
            groups = groups,
            models = models,
            selectedGroupId = groupId,
            selectedProviderInstanceId = providerInstanceId,
            selectedModelId = modelId,
            onPickGroup = {
                groupId = it
                providerInstanceId = null
                modelId = null
                showTargetPicker = false
            },
            onPickModel = {
                providerInstanceId = it.providerInstanceId
                modelId = it.modelId
                groupId = null
                showTargetPicker = false
            },
            onDismiss = { showTargetPicker = false },
        )
    }

    if (showEmojiDialog) {
        CharacterCardEmojiDialog(
            current = icon,
            onDismiss = { showEmojiDialog = false },
            onPick = {
                icon = it
                showEmojiDialog = false
            },
        )
    }

    if (showDiscard) {
        MinisAlertDialog(
            onDismissRequest = { showDiscard = false },
            title = stringResource(R.string.character_card_editor_unsaved_title),
            text = stringResource(R.string.character_card_editor_unsaved_body),
            confirmText = stringResource(R.string.character_card_editor_discard),
            onConfirm = {
                showDiscard = false
                onCancel()
            },
            dismissText = stringResource(R.string.character_card_editor_keep_editing),
            onDismiss = { showDiscard = false },
            isDestructive = true,
        )
    }

    saveError?.let { message ->
        MinisAlertDialog(
            onDismissRequest = { saveError = null },
            title = stringResource(R.string.character_card_editor_save_error_title),
            text = message,
            confirmText = stringResource(R.string.character_cards_ok),
            onConfirm = { saveError = null },
        )
    }
}

@Composable
private fun CardTextField(
    labelRes: Int,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = false,
    minLines: Int = 3,
    monospace: Boolean = false,
    hint: String? = null,
    isError: Boolean = false,
    errorText: String? = null,
) {
    val tooLong = value.length > CHARACTER_CARD_MAX_FIELD_CHARS
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        maxLines = if (singleLine) 1 else 10,
        isError = isError || tooLong,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyMedium
        },
        supportingText = {
            when {
                tooLong -> Text(
                    stringResource(
                        R.string.character_card_editor_field_too_long,
                        CHARACTER_CARD_MAX_FIELD_CHARS,
                    ),
                )
                isError && errorText != null -> Text(errorText)
                hint != null -> Text(hint)
                else -> Text(
                    stringResource(
                        R.string.character_card_editor_chars,
                        value.length,
                        CHARACTER_CARD_MAX_FIELD_CHARS,
                    ),
                )
            }
        },
    )
}

/** Comma or newline separated, trimmed, de-duplicated in first-seen order. */
internal fun parseCardTags(raw: String): List<String> = raw
    .split(',', '\n')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()

@Composable
private fun CharacterCardEmojiDialog(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(current.takeIf { it.length < 16 } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.character_card_editor_emoji_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SoulIcon.SUGGESTED_EMOJI.chunked(8).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        row.forEach { emoji ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (emoji == draft) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerLow
                                        },
                                    )
                                    .clickable { draft = emoji },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = emoji, fontSize = 20.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = SoulIcon.normalizeEmojiInput(it) },
                    label = { Text(stringResource(R.string.character_card_editor_emoji_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            MinisTextButton(
                onClick = { onPick(draft) },
                enabled = draft.isNotEmpty(),
            ) { Text(stringResource(R.string.character_card_editor_emoji_set)) }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.character_cards_cancel))
            }
        },
    )
}
