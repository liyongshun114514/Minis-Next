package com.openminis.app.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.safeLaunchPicker
import com.openminis.app.agent.SoulBodyLimitCheck
import com.openminis.app.agent.SoulBodyLimitPolicy
import com.openminis.app.agent.SoulBodyLimitSettings
import com.openminis.app.agent.SoulIcon
import com.openminis.app.agent.SoulFile
import com.openminis.app.agent.SoulMDParser
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulPromptRule
import com.openminis.app.agent.SoulPromptRuleStore
import com.openminis.app.agent.SoulStore
import com.openminis.app.data.model.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext

/**
 * [T-soul-md] Settings page for SOUL.md: an editable avatar, a name, the
 * global system prompt, and the scoped custom rules that append to it.
 *
 * [T-android-soul-system-prompt-only] Style, language and the body-length
 * mode/cap controls were removed from the page. They are NOT removed from
 * the data model: whatever SOUL.md already carries for `style`, `lang` and
 * `emoji` is read into state at load and written back unchanged on save, so
 * the file still round-trips and older/other-device values are not silently
 * dropped. The length gate itself is untouched — it is still evaluated from
 * the persisted [SoulBodyLimitPolicy], it is only no longer configurable
 * here; a body over the stored cap still cannot be saved and now says so
 * inline.
 *
 * The global prompt is saved as the Markdown body of SOUL.md via
 * [SoulStore.save] from the button inside its own card; custom rules persist
 * independently through [SoulPromptRuleStore] on every change. Prompt
 * assembly, layering and instruction priority live in the agent layer and
 * are not changed by this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoulSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(SoulMetadata.DEFAULT.name) }
    // Preserved, not editable. Kept in state so Save re-emits the values the
    // loaded file had; dropping them would rewrite metadata the user set
    // elsewhere just because this page no longer shows a field for it.
    var style by remember { mutableStateOf(SoulMetadata.DEFAULT.style) }
    var lang by remember { mutableStateOf(SoulMetadata.DEFAULT.lang) }
    var body by remember { mutableStateOf("") }
    // [T-native-composer-soul-limit] The persisted length policy is still
    // read (never written from here) so the Save button keeps enforcing the
    // same gate the agent layer uses.
    var limitSettings by remember { mutableStateOf(SoulBodyLimitSettings()) }
    var loaded by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // Preserve the raw `emoji` field exactly as it appears in SOUL.md so
    // Save round-trips without clobbering a value the user may have set
    // on another device (or an older build). Not surfaced in the UI —
    // the identity emoji shown to the user is locked to ✨ everywhere
    // (see [SoulMetadata.displayEmoji]).
    var preservedEmoji by remember { mutableStateOf(SoulMetadata.DEFAULT.emoji) }
    // [T-android-soul-custom-icon] The user-settable identity icon: an emoji
    // literal, a data:image/png;base64 URI, or empty for the default sparkle.
    var icon by remember { mutableStateOf(SoulMetadata.DEFAULT.icon) }
    var showIconMenu by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }
    var iconError by remember { mutableStateOf<String?>(null) }

    // [T-soul-scoped-prompt] Custom rules are persisted independently of the
    // global save button: toggling a switch or deleting a row is immediate,
    // while editing the global body is a SOUL.md draft committed from its own
    // card.
    var rules by remember { mutableStateOf<List<SoulPromptRule>>(emptyList()) }
    var rulesError by remember { mutableStateOf<String?>(null) }
    var ruleEditorOpen by remember { mutableStateOf(false) }
    var ruleBeingEdited by remember { mutableStateOf<SoulPromptRule?>(null) }
    var rulePendingDelete by remember { mutableStateOf<SoulPromptRule?>(null) }

    // The group / model lists shown by the rule editor. Read from the same
    // ProviderRepository the chat uses, so the ids stored by a rule are the
    // ids the runtime will compare against. null repository (safe mode) simply
    // yields empty pickers instead of crashing.
    val app = remember(context) { context.applicationContext as? com.openminis.app.MinisApp }
    val providerRepository = remember(app) { app?.providerRepositoryOrNull }
    val emptyConfig = remember { MutableStateFlow(ProviderConfig()) }
    val providerConfig by (providerRepository?.config ?: emptyConfig).collectAsState()
    val groupOptions = providerConfig.modelGroups.map { group ->
        SoulPromptGroupOption(group.id, group.name.ifBlank { group.id })
    }
    val modelOptions = providerConfig.modelEntries
        .filter { !it.isHidden }
        .mapNotNull { entry ->
            val instance = providerConfig.instances.firstOrNull { it.id == entry.providerInstanceId }
                ?: return@mapNotNull null
            SoulPromptModelOption(
                providerInstanceId = entry.providerInstanceId,
                modelId = entry.model.id,
                label = entry.model.displayName + " · " +
                    instance.label.ifBlank { entry.model.provider },
            )
        }
        .distinctBy { it.providerInstanceId + "\u0000" + it.modelId }

    // [T-android-soul-save-in-appbar] What was loaded from disk, kept so
    // "has the user changed anything" is a comparison rather than a flag.
    //
    // A boolean set by every onValueChange would report dirty after a round
    // trip that lands back on the original text (type a character, delete it),
    // and would need a reset at each of the several places state is
    // reassigned — the load and the save itself. A snapshot compared by value
    // cannot drift out of sync with the fields.
    var baseline by remember { mutableStateOf<SoulFile?>(null) }

    // [T-android-soul-custom-icon] Image import: decode → keep the aspect
    // ratio → bound the longest edge ([SoulIcon.STORED_PIXELS]) → PNG → base64
    // data URI. [T-soul-avatar-fill] No square crop: the avatar renderer fills
    // its frame (ContentScale.Crop), so what the user sees is decided at draw
    // time and nothing is destroyed on save. Runs off the main thread because a
    // full-resolution camera photo is expensive to decode and scale.
    val iconUnreadableMsg = stringResource(R.string.soul_icon_error_unreadable)
    val iconTooLargeMsg = stringResource(R.string.soul_icon_error_too_large)
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val bmp = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
                if (bmp == null) {
                    SoulIcon.EncodeResult.Failure(SoulIcon.Rejection.UNREADABLE)
                } else {
                    SoulIcon.encode(bmp)
                }
            }
            when (result) {
                is SoulIcon.EncodeResult.Success -> icon = result.dataUri
                is SoulIcon.EncodeResult.Failure -> iconError = when (result.reason) {
                    SoulIcon.Rejection.TOO_LARGE -> iconTooLargeMsg
                    SoulIcon.Rejection.UNREADABLE -> iconUnreadableMsg
                }
            }
        }
    }

    // Initial load + (defensive) ensureExists. The Application-level call
    // already seeded on first run, but loading from a freshly cleared app
    // shouldn't crash.
    LaunchedEffect(Unit) {
        limitSettings = withContext(Dispatchers.IO) { SoulBodyLimitPolicy.load(context) }
        val parsed = withContext(Dispatchers.IO) {
            SoulStore.ensureExists(context)
            SoulStore.load(context) ?: SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
        }
        rules = withContext(Dispatchers.IO) {
            runCatching { SoulPromptRuleStore.load(context) }.getOrDefault(emptyList())
        }
        name = parsed.metadata.name
        preservedEmoji = parsed.metadata.emoji
        icon = parsed.metadata.icon
        style = parsed.metadata.style
        lang = parsed.metadata.lang
        body = parsed.body
        // [T-android-soul-save-in-appbar] Snapshot what disk holds, so the
        // dirty check starts from the loaded state rather than from the
        // pre-load defaults (which would read as "changed" immediately).
        //
        // Rebuilt through the same normalisation Save applies (ifBlank
        // fallbacks) rather than stored raw: a file whose `lang` is empty on
        // disk would otherwise compare unequal to the field state the instant
        // it loaded, and the screen would open already dirty.
        baseline = SoulFile(
            metadata = SoulMetadata(
                name = parsed.metadata.name.ifBlank { SoulMetadata.DEFAULT.name },
                emoji = parsed.metadata.emoji.ifBlank { SoulMetadata.DEFAULT.emoji },
                icon = parsed.metadata.icon,
                style = parsed.metadata.style,
                lang = parsed.metadata.lang.ifBlank { SoulMetadata.DEFAULT.lang },
            ),
            body = parsed.body,
        )
        loaded = true
    }

    // [T-soul-unlimited-default] The length policy defaults to UNLIMITED, and
    // SoulStore.isOverLimit() folds any stale NATIVE into it, so [bodyLimitCheck]
    // is Ok for every mode this page can reach: there is no length control on
    // this page any more, and CUSTOM is only reachable from the minis-config
    // writer. It is kept (rather than deleted) because it is still the ONE
    // classifier that Save and SoulStore.save() share — Save must stay wired to
    // the same gate the store enforces, or a body the store accepts could still
    // be blocked in the UI. The over-limit warning below is unreachable for the
    // same reason; see its note.
    val bodyLimitCheck by remember(body, limitSettings) {
        derivedStateOf { SoulStore.isOverLimit(body, limitSettings) }
    }

    val currentFile = SoulFile(
        metadata = SoulMetadata(
            name = name.ifBlank { SoulMetadata.DEFAULT.name },
            emoji = preservedEmoji.ifBlank { SoulMetadata.DEFAULT.emoji },
            icon = icon,
            style = style,
            lang = lang.ifBlank { SoulMetadata.DEFAULT.lang },
        ),
        body = body,
    )
    val isDirty = loaded && baseline != null && currentFile != baseline
    var showDiscardDialog by remember { mutableStateOf(false) }

    // [T-soul-scoped-prompt] Rule writes are serialized: rapid switch flips
    // launch several saves, and without a mutex an older snapshot could land
    // after a newer one. A failure reports itself but never rolls the UI back
    // — that would discard a newer change the user already made.
    val persistMutex = remember { kotlinx.coroutines.sync.Mutex() }
    val persistRules: (List<SoulPromptRule>) -> Unit = { next ->
        rules = next
        scope.launch {
            persistMutex.withLock {
                runCatching {
                    withContext(Dispatchers.IO) { SoulPromptRuleStore.save(context, next) }
                }.onFailure { t ->
                    rulesError = t.message ?: "save failed"
                }
            }
        }
        Unit
    }

    val save: () -> Unit = {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SoulStore.save(context, currentFile) }
                onBack()
            } catch (t: Throwable) {
                saveError = t.message ?: "save failed"
            }
        }
        Unit
    }

    // [T-android-soul-save-in-appbar] Leaving with unsaved edits asks first.
    // Routed through one lambda so the app bar's back arrow and the system
    // back gesture cannot disagree about whether the guard applies.
    val attemptBack: () -> Unit = {
        if (isDirty) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = isDirty) { showDiscardDialog = true }

    SettingsScaffold(
        title = stringResource(R.string.soul_settings_title),
        onBack = attemptBack,
    ) {
        // Identity: the avatar and the name, and nothing else. Tapping the
        // avatar opens the icon menu (emoji / image / default).
        SettingsSection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // [T-android-soul-custom-icon] A bare glyph does not read as
                // tappable — reported on iOS review — so the icon sits on a
                // filled circle with a hairline border and a pencil badge.
                // The circle doubles as the backdrop for transparent PNGs,
                // which by definition have no background of their own.
                Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            )
                            .clickable { showIconMenu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        SoulIconGlyph(icon = icon, sizeDp = 30.dp, emojiSp = 24.sp)
                        // Badge kept INSIDE the circle's bounds: on iOS an
                        // overhanging badge was clipped to a sliver by the
                        // enclosing button label.
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(2.dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(2.5.dp),
                        )
                    }
                    SoulIconMenu(
                        expanded = showIconMenu,
                        hasIcon = icon.isNotEmpty(),
                        onDismiss = { showIconMenu = false },
                        onChooseEmoji = { showIconMenu = false; showEmojiSheet = true },
                        onChooseImage = {
                            showIconMenu = false
                            imagePicker.safeLaunchPicker(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                                context,
                                "SoulSettings",
                            )
                        },
                        onUseDefault = { showIconMenu = false; icon = "" },
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.soul_field_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
            }
        }

        // [T-soul-scoped-prompt] The global prompt and the scoped rules are two
        // stacked sections on one scroll surface, not two hidden tabs. They
        // have different lifecycles — the global prompt is a SOUL.md draft
        // committed by its own button, while a rule persists on every toggle,
        // move, edit and delete — so each section states its save model next
        // to the control that uses it.
        //
        // The global editor below is unchanged in meaning and save path: it
        // still edits the SOUL.md body through SoulStore.save.
        SettingsSection(
            header = stringResource(R.string.soul_section_personality),
            footer = stringResource(R.string.prompt_ui_global_footer),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.prompt_ui_global_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.prompt_ui_global_scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                // [T-android-editor-keyboard-overlap] The page scrolls and
                // pads for the keyboard (SettingsScaffold); this field asks
                // it to keep the caret's line in view, not just the field.
                com.openminis.app.ui.components.DocumentEditorField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    placeholder = stringResource(R.string.soul_body_placeholder),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    outlined = true,
                    ownScroll = false,
                    minHeight = 240.dp,
                )
                // [T-soul-unlimited-default] Dead by construction, kept as a
                // safety net: the default policy is UNLIMITED and this page has
                // no control that can pick CUSTOM, so `bodyLimitCheck` never
                // reports OverLimit* here and no "too long" red line appears.
                // Deleting the branch would not change behaviour; leaving it
                // means a body that SoulStore.save() WOULD still refuse (a
                // CUSTOM policy left on disk by an older build or written via
                // minis-config) explains itself instead of showing a disabled
                // Save button with no reason.
                // [T-soul-unlimited-default] Gated on the MODE as well as the
                // result: CUSTOM is the only mode that can report OverLimit*
                // now, and this page has no control that reaches it, so the
                // banner is unreachable rather than merely unlikely.
                val overLimit = bodyLimitCheck
                if (!limitSettings.mode.isUnlimited &&
                    (overLimit is SoulBodyLimitCheck.OverLimitChinese ||
                        overLimit is SoulBodyLimitCheck.OverLimitEnglish)
                ) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when (overLimit) {
                            is SoulBodyLimitCheck.OverLimitChinese -> stringResource(
                                R.string.soul_over_limit_chinese,
                                overLimit.chars,
                                overLimit.cap,
                                limitSettings.englishWordLimit,
                            )
                            is SoulBodyLimitCheck.OverLimitEnglish -> stringResource(
                                R.string.soul_over_limit_english,
                                overLimit.words,
                                overLimit.cap,
                                limitSettings.chineseCharLimit,
                            )
                            else -> ""
                        },
                        fontSize = 12.sp,
                        color = Color(0xFFFF3B30),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            !loaded -> ""
                            isDirty -> stringResource(R.string.prompt_ui_global_unsaved)
                            else -> stringResource(R.string.prompt_ui_global_saved)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDirty) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                    MinisButton(
                        onClick = save,
                        enabled = loaded && isDirty && !bodyLimitCheck.isOverLimit,
                    ) { Text(stringResource(R.string.soul_save)) }
                }
            }
        }

        // Custom rules write through SoulPromptRuleStore immediately; there is
        // no draft to lose, which is why this section has no Save button.
        SoulCustomRulesSection(
            rules = rules,
            groups = groupOptions,
            models = modelOptions,
            canAdd = groupOptions.isNotEmpty() || modelOptions.isNotEmpty(),
            onToggle = { rule, checked ->
                persistRules(
                    rules.map {
                        if (it.id == rule.id) it.copy(enabled = checked) else it
                    },
                )
            },
            onMove = { rule, delta ->
                // Only swaps rules of the same scope: the resolver always
                // appends group rules before model rules, so a cross-scope
                // swap would rewrite storage without changing the prompt.
                val next = SoulPromptRuleUi.moveWithinTier(rules, rule.id, delta)
                if (next !== rules) persistRules(next)
            },
            onEdit = { rule ->
                ruleBeingEdited = rule
                ruleEditorOpen = true
            },
            onDelete = { rule -> rulePendingDelete = rule },
            onAdd = {
                ruleBeingEdited = null
                ruleEditorOpen = true
            },
        )
    }

    // [T-android-soul-save-in-appbar] Unsaved-changes guard.
    //
    // Each button leads with the verdict and then names the consequence
    // ("OK, discard" / "Cancel, keep editing"), so the row scans correctly
    // whether the user reads the leading word or the trailing one — a bare
    // "Discard"/"Keep editing" pair reads fine but gives no cue about which
    // side is the safe one.
    //
    // Dismissing the dialog by tapping outside keeps the edits — the
    // conservative reading of an ambiguous gesture.
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.soul_discard_confirm_title)) },
            text = { Text(stringResource(R.string.soul_discard_confirm_body)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text(stringResource(R.string.soul_discard_confirm)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.soul_discard_cancel))
                }
            },
        )
    }

    saveError?.let { err ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text(stringResource(R.string.soul_save_error_title)) },
            text = { Text(err) },
            confirmButton = {
                MinisButton(onClick = { saveError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }

    // [T-android-soul-custom-icon] Image rejection is explained after the
    // fact here. There is no longer a transparency requirement to state up
    // front — any image is accepted (any aspect ratio: it is filled, not cropped
    // to a square on save) — so the only
    // remaining rejections are "couldn't be read" and "too large", neither of
    // which can be predicted before the user picks a file.
    iconError?.let { err ->
        AlertDialog(
            onDismissRequest = { iconError = null },
            title = { Text(stringResource(R.string.soul_icon_error_title)) },
            text = { Text(err) },
            confirmButton = {
                MinisButton(onClick = { iconError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }

    if (showEmojiSheet) {
        SoulEmojiPickerSheet(
            current = if (SoulIcon.isDataUri(icon)) "" else icon,
            onDismiss = { showEmojiSheet = false },
            onPick = { chosen -> icon = chosen; showEmojiSheet = false },
        )
    }

    // [T-soul-scoped-prompt] Add / edit one custom rule. Saving writes through
    // the same immediate-persist path as the switch and delete controls.
    if (ruleEditorOpen) {
        SoulPromptRuleEditorDialog(
            existing = ruleBeingEdited,
            groups = groupOptions,
            models = modelOptions,
            onDismiss = {
                ruleEditorOpen = false
                ruleBeingEdited = null
            },
            onSave = { saved ->
                val index = rules.indexOfFirst { it.id == saved.id }
                val next = if (index >= 0) {
                    rules.toMutableList().also { it[index] = saved }
                } else {
                    rules + saved
                }
                persistRules(next)
                ruleEditorOpen = false
                ruleBeingEdited = null
            },
        )
    }

    rulePendingDelete?.let { pending ->
        val unnamedRule = stringResource(R.string.soul_custom_rule_unnamed)
        AlertDialog(
            onDismissRequest = { rulePendingDelete = null },
            title = { Text(stringResource(R.string.soul_custom_rule_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.soul_custom_rule_delete_body,
                        pending.name.ifBlank { unnamedRule },
                    ),
                )
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    persistRules(rules.filterNot { it.id == pending.id })
                    rulePendingDelete = null
                }) { Text(stringResource(R.string.soul_custom_rule_delete)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { rulePendingDelete = null }) {
                    Text(stringResource(R.string.soul_cancel))
                }
            },
        )
    }

    rulesError?.let { err ->
        AlertDialog(
            onDismissRequest = { rulesError = null },
            title = { Text(stringResource(R.string.soul_save_error_title)) },
            text = { Text(stringResource(R.string.soul_custom_rules_save_error, err)) },
            confirmButton = {
                MinisButton(onClick = { rulesError = null }) {
                    Text(stringResource(R.string.soul_ok))
                }
            },
        )
    }
}

/**
 * [T-android-soul-custom-icon] The icon itself, rendered the same way
 * everywhere it appears: a decoded bitmap, an emoji, or the default sparkle.
 *
 * The bitmap decode is `remember`ed on the icon string. Without that, the
 * chat header would re-decode base64 on every recomposition — and headers
 * recompose on every streaming tick.
 */
@Composable
internal fun SoulIconGlyph(
    icon: String,
    sizeDp: Dp,
    emojiSp: TextUnit,
    sparkleTint: Brush? = null,
) {
    val bitmap = remember(icon) { SoulIcon.decode(icon) }
    when {
        // Rounded rectangle, NOT a circle: at 18dp in the chat header a circle
        // eats the corners of a small avatar. Clipping here is also what makes
        // an opaque image acceptable at all — the refusal that used to guard
        // against "opaque rectangle reads as a broken tile" was a presentation
        // concern, and this is the presentation fix.
        // [T-soul-avatar-fill] ContentScale.Crop: the stored image keeps its
        // own aspect ratio, so a non-square one must FILL the frame rather
        // than be letterboxed inside it. The default (Fit) left blank bands on
        // two sides — the "white edges" a square crop used to hide. With Crop
        // the image covers every pixel of the box, the rounded clip below cuts
        // the corners, and .clip() also clamps the overflow a scaled-up image
        // would otherwise paint outside the box.
        bitmap != null -> Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(sizeDp)
                .clip(RoundedCornerShape(sizeDp * SoulIcon.CORNER_RADIUS_FRACTION)),
        )
        icon.isNotEmpty() -> Text(text = icon, fontSize = emojiSp)
        // Unset: byte-for-byte the previous sparkle, so a user who never
        // touches this sees no visual change at all.
        sparkleTint != null -> Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(sizeDp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = sparkleTint, blendMode = BlendMode.SrcIn)
                },
        )
        else -> Text(text = SoulMetadata.DISPLAY_EMOJI, fontSize = emojiSp)
    }
}

@Composable
private fun SoulIconMenu(
    expanded: Boolean,
    hasIcon: Boolean,
    onDismiss: () -> Unit,
    onChooseEmoji: () -> Unit,
    onChooseImage: () -> Unit,
    onUseDefault: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.soul_icon_choose_emoji)) },
            onClick = onChooseEmoji,
        )
        // [T-soul-avatar-fill] Title only. The old "cropped to a square and
        // rounded" second line described the crop that no longer happens; a
        // sub-label would have to promise something about framing that the
        // renderer decides, so there is no hint line here at all.
        DropdownMenuItem(
            text = { Text(stringResource(R.string.soul_icon_choose_image)) },
            onClick = onChooseImage,
        )
        // Only offered when there is something to clear.
        if (hasIcon) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.soul_icon_use_default)) },
                onClick = onUseDefault,
            )
        }
    }
}

/**
 * Two rows of suggestions, tap-to-fill, a live preview at render size, and a
 * free-form field that normalizes per keystroke.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoulEmojiPickerSheet(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.soul_icon_emoji_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            // Preview at the size the icon is actually drawn on the card.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = draft.ifEmpty { SoulMetadata.DISPLAY_EMOJI },
                    fontSize = 30.sp,
                )
            }
            Spacer(Modifier.height(20.dp))

            SoulIcon.SUGGESTED_EMOJI.chunked(8).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    row.forEach { e ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (e == draft) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                                )
                                .clickable { draft = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(text = e, fontSize = 22.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft,
                // Normalized PER KEYSTROKE rather than validated on submit:
                // a second emoji replaces the first, and non-emoji input is
                // dropped silently instead of being accepted then rejected.
                onValueChange = { draft = SoulIcon.normalizeEmojiInput(it) },
                label = { Text(stringResource(R.string.soul_icon_emoji_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MinisOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.soul_cancel))
                }
                MinisButton(
                    onClick = { onPick(draft) },
                    enabled = draft.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.soul_icon_set)) }
            }
        }
    }
}
