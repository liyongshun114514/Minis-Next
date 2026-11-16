package com.openminis.app.ui.settings

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.SoulFile
import com.openminis.app.agent.SoulIcon
import com.openminis.app.agent.SoulMDParser
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulProfiles
import com.openminis.app.agent.SoulStore
import com.openminis.app.ui.components.DocumentEditorField
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.SoulAvatarGlyph
import com.openminis.app.ui.components.safeLaunchPicker
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-multi-soul] Settings → System Prompt: a library of SOUL profiles plus the
 * editor for the selected one.
 *
 * The page lists every profile (default first), lets the user create, edit,
 * delete and — when a chat is supplied — choose which SOUL that chat uses.
 * Name, icon and body are edited exactly as before; the existing icon picker,
 * image importer, emoji sheet and unsaved-changes guard are reused rather than
 * duplicated.
 *
 * Storage lives in [SoulProfiles]. The legacy default is still read and written
 * through `SoulStore.load(context)` / `SoulStore.save(context, currentFile)` —
 * [SoulProfiles] delegates for [SoulProfiles.DEFAULT_ID], so this screen does
 * not duplicate the sidecar-icon, cache or default-content logic. All other
 * profiles go to `minis-global/memory/souls/<uuid>.md` with the icon inline.
 *
 * Load and write failures are surfaced as dialogs. A save that fails leaves
 * the draft on screen and the button state unchanged; a profile that cannot be
 * loaded falls back to the default and says so. Nothing reports success before
 * the write has actually landed.
 *
 * Localized copy for the NEW controls is kept in [SoulUiText] (zh/en chosen
 * from the device locale) so this change does not touch shared string
 * resources; the main integration can move those keys into resources later.
 * Existing copy still comes from `R.string.*`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoulSettingsScreen(onBack: () -> Unit, sessionId: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Published by SoulProfiles; save/create/delete republish synchronously,
    // so an edit is visible in this list as soon as the write returns.
    val profileList by SoulProfiles.profiles.collectAsState()
    val selectionMap by SoulProfiles.selections.collectAsState()

    // Which profile the editor fields below belong to. Kept separate from the
    // per-chat selection: browsing/editing a profile must not silently change
    // what a conversation uses.
    var editingId by remember { mutableStateOf(SoulProfiles.DEFAULT_ID) }
    // Bumping this re-runs the load effect for the same ID (external edits,
    // imported profiles, a deleted file). A profile switch changes editingId.
    var reloadKey by remember { mutableStateOf(0) }

    var name by remember { mutableStateOf(SoulMetadata.DEFAULT.name) }
    // Preserved, not editable. Kept in state so Save re-emits the values the
    // loaded file had; dropping them would rewrite metadata the user set
    // elsewhere just because this page no longer shows a field for it.
    var style by remember { mutableStateOf(SoulMetadata.DEFAULT.style) }
    var lang by remember { mutableStateOf(SoulMetadata.DEFAULT.lang) }
    var body by remember { mutableStateOf("") }
    // [T-android-soul-two-scope-switch] Save is a one-shot: the first tap sets
    // this before the coroutine starts, so a second tap cannot enqueue a
    // second write — and the button below is disabled while it is true.
    var saving by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    // Preserve the raw `emoji` field exactly as it appears in the file so Save
    // round-trips without clobbering a value the user may have set on another
    // device (or an older build).
    var preservedEmoji by remember { mutableStateOf(SoulMetadata.DEFAULT.emoji) }
    var icon by remember { mutableStateOf(SoulMetadata.DEFAULT.icon) }
    var showIconMenu by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }
    var iconError by remember { mutableStateOf<String?>(null) }

    // What was loaded from disk, kept so "has the user changed anything" is a
    // comparison rather than a flag. A boolean would report dirty after a
    // round trip that lands back on the original text; a snapshot compared by
    // value cannot drift out of sync with the fields.
    var baseline by remember { mutableStateOf<SoulFile?>(null) }

    // [T-android-soul-custom-icon] Image import: the source stream is sampled
    // while decoding ([SoulIcon.importFrom]), the aspect ratio is preserved,
    // the longest edge is bounded ([SoulIcon.STORED_PIXELS]), then it is
    // re-encoded as PNG + base64. Runs off the main thread.
    val iconUnreadableMsg = stringResource(R.string.soul_icon_error_unreadable)
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SoulIcon.importFrom { context.contentResolver.openInputStream(uri) }
                }.getOrElse {
                    SoulIcon.EncodeResult.Failure(SoulIcon.Rejection.UNREADABLE)
                }
            }
            when (result) {
                is SoulIcon.EncodeResult.Success -> icon = result.dataUri
                is SoulIcon.EncodeResult.Failure -> iconError = iconUnreadableMsg
            }
        }
    }

    // Apply a parsed file to every editor field and reset the dirty baseline.
    // Rebuilt through the same normalisation Save applies (ifBlank fallbacks)
    // so a file whose `lang` is empty on disk does not open already dirty.
    val applyLoaded: (SoulFile) -> Unit = { parsed ->
        name = parsed.metadata.name
        preservedEmoji = parsed.metadata.emoji
        icon = parsed.metadata.icon
        style = parsed.metadata.style
        lang = parsed.metadata.lang
        body = parsed.body
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

    // Load whenever the edited profile changes or reloadKey is bumped.
    LaunchedEffect(editingId, reloadKey) {
        val targetId = editingId
        loading = true
        loaded = false
        saveError = null
        val parsed = withContext(Dispatchers.IO) {
            // refresh() also notices profiles created/removed outside this
            // screen and refreshes SoulStore.cachedMetadata for the default.
            SoulProfiles.refresh(context)
            SoulProfiles.load(context, targetId)
        }
        if (parsed == null) {
            if (targetId == SoulProfiles.DEFAULT_ID) {
                // First run / cleared data: the editor has always shown the
                // built-in default before the first save seeds SOUL.md.
                applyLoaded(SoulMDParser.parse(SoulStore.DEFAULT_CONTENT))
            } else {
                loadError = SoulUiText.loadFailed(targetId)
                editingId = SoulProfiles.DEFAULT_ID
            }
        } else {
            applyLoaded(parsed)
        }
        loading = false
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
    var pendingAction by remember { mutableStateOf<PendingSoulAction?>(null) }
    var deleteTarget by remember { mutableStateOf<SoulProfiles.SoulProfile?>(null) }

    // One write per tap for the edited profile. `saving` is set synchronously,
    // before the coroutine starts, so a double tap cannot start two writes.
    // On failure the draft stays exactly as typed and the dialog reports it;
    // on success the baseline advances so the page shows "Saved".
    val save: () -> Unit = {
        if (!saving) {
            saving = true
            val snapshot = currentFile
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        SoulProfiles.save(context, editingId, snapshot)
                    }
                    baseline = snapshot
                } catch (t: Throwable) {
                    saveError = t.message ?: "save failed"
                } finally {
                    saving = false
                }
            }
        }
        Unit
    }

    fun createProfile() {
        scope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    SoulProfiles.create(
                        context,
                        SoulFile(
                            metadata = SoulMetadata(
                                name = SoulUiText.newProfileName(),
                                emoji = SoulMetadata.DEFAULT.emoji,
                                icon = "",
                                style = SoulMetadata.DEFAULT.style,
                                lang = SoulMetadata.DEFAULT.lang,
                            ),
                            body = "",
                        ),
                    )
                }
                editingId = id
            } catch (t: Throwable) {
                saveError = t.message ?: "create failed"
            }
        }
    }

    fun runAction(action: PendingSoulAction) {
        when (action) {
            is PendingSoulAction.SwitchProfile -> editingId = action.id
            PendingSoulAction.Create -> createProfile()
            PendingSoulAction.Back -> onBack()
        }
    }

    // Leaving the current draft (switch/create/back) asks first when dirty.
    // Dismissing the dialog keeps the edits — the conservative reading.
    fun requestAction(action: PendingSoulAction) {
        if (isDirty) {
            pendingAction = action
            showDiscardDialog = true
        } else {
            runAction(action)
        }
    }

    fun selectForSession(id: String) {
        val session = sessionId ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SoulProfiles.select(context, session, id) }
            } catch (t: Throwable) {
                selectionError = t.message ?: "select failed"
            }
        }
    }

    fun deleteProfile(profile: SoulProfiles.SoulProfile) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SoulProfiles.delete(context, profile.id) }
                if (editingId == profile.id) {
                    editingId = SoulProfiles.DEFAULT_ID
                }
            } catch (t: Throwable) {
                saveError = t.message ?: "delete failed"
            }
        }
    }

    // Which profile this chat uses. Mirrors SoulProfiles.selectedId's fallback
    // using the published map, so the radio state cannot point at a profile
    // that is no longer listed.
    val sessionSelectedId: String? = sessionId?.let { session ->
        val stored = selectionMap[session]
        if (stored != null && profileList.any { it.id == stored }) {
            stored
        } else {
            SoulProfiles.DEFAULT_ID
        }
    }

    // [T-android-soul-save-in-appbar] Leaving with unsaved edits asks first.
    // Routed through one lambda so the app bar's back arrow and the system
    // back gesture cannot disagree about whether the guard applies.
    val attemptBack: () -> Unit = {
        if (isDirty) {
            pendingAction = PendingSoulAction.Back
            showDiscardDialog = true
        } else {
            onBack()
        }
    }
    BackHandler(enabled = isDirty) {
        pendingAction = PendingSoulAction.Back
        showDiscardDialog = true
    }

    SettingsScaffold(
        // "SOUL" is the product name of the setting; it is the same word in
        // every locale, so it is not routed through a translated resource.
        title = SoulUiText.screenTitle(),
        onBack = attemptBack,
    ) {
        // ── Profile library ────────────────────────────────────────────────
        SettingsSection(
            header = SoulUiText.profilesHeader(),
            footer = SoulUiText.profilesFooter(),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                profileList.forEachIndexed { index, profile ->
                    val isEditing = profile.id == editingId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (profile.id != editingId) {
                                    requestAction(PendingSoulAction.SwitchProfile(profile.id))
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SoulAvatarGlyph(
                            icon = profile.file.metadata.icon,
                            sizeDp = 34.dp,
                            emojiSp = 19.sp,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                text = profileDisplayName(profile),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isEditing) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = profilePreview(profile),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (sessionSelectedId != null) {
                            RadioButton(
                                selected = profile.id == sessionSelectedId,
                                onClick = { selectForSession(profile.id) },
                                modifier = Modifier.semantics {
                                    contentDescription = SoulUiText.sessionUse()
                                },
                            )
                        }
                        if (profile.id != SoulProfiles.DEFAULT_ID) {
                            IconButton(onClick = { deleteTarget = profile }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = SoulUiText.delete(),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (index < profileList.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 60.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { requestAction(PendingSoulAction.Create) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = SoulUiText.newProfile(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }

        // ── Editor: identity ───────────────────────────────────────────────
        SettingsSection(header = SoulUiText.editorHeader()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The icon sits on a filled circle with a hairline border and a
                // pencil badge so it reads as tappable; the circle doubles as
                // the backdrop for transparent PNGs.
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
                        SoulIconGlyph(icon = icon, sizeDp = 44.dp, emojiSp = 26.sp)
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

        // ── Editor: body + save ────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.soul_section_personality),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                DocumentEditorField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    placeholder = stringResource(R.string.soul_body_placeholder),
                    contentPadding = PaddingValues(16.dp),
                    outlined = true,
                    ownScroll = false,
                    minHeight = 240.dp,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            !loaded -> if (loading) SoulUiText.loading() else ""
                            saving -> stringResource(R.string.system_prompt_saving)
                            isDirty -> stringResource(R.string.kairos_prompt_global_unsaved)
                            else -> stringResource(R.string.kairos_prompt_global_saved)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDirty || saving) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                    MinisButton(
                        onClick = save,
                        enabled = loaded && isDirty && !saving,
                    ) { Text(stringResource(R.string.soul_save)) }
                }
            }
        }
    }// [T-android-soul-save-in-appbar] Unsaved-changes guard.
    //
    // Each button leads with the verdict and then names the consequence
    // ("OK, discard" / "Cancel, keep editing"), so the row scans correctly
    // whether the user reads the leading word or the trailing one.
    //
    // Dismissing the dialog by tapping outside keeps the edits — the
    // conservative reading of an ambiguous gesture.
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = {
                showDiscardDialog = false
                pendingAction = null
            },
            title = { Text(stringResource(R.string.soul_discard_confirm_title)) },
            text = { Text(stringResource(R.string.soul_discard_confirm_body)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    val action = pendingAction
                    showDiscardDialog = false
                    pendingAction = null
                    if (action != null) runAction(action)
                }) { Text(stringResource(R.string.soul_discard_confirm)) }
            },
            dismissButton = {
                MinisTextButton(onClick = {
                    showDiscardDialog = false
                    pendingAction = null
                }) {
                    Text(stringResource(R.string.soul_discard_cancel))
                }
            },
        )
    }

    // Write failures (save/create/delete) name the operation that failed and
    // leave the draft in place; the button becomes usable again because
    // `saving` is reset in `finally`.
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

    loadError?.let { err ->
        AlertDialog(
            onDismissRequest = { loadError = null },
            title = { Text(SoulUiText.loadErrorTitle()) },
            text = { Text(err) },
            confirmButton = {
                MinisButton(onClick = { loadError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }

    selectionError?.let { err ->
        AlertDialog(
            onDismissRequest = { selectionError = null },
            title = { Text(SoulUiText.selectionErrorTitle()) },
            text = { Text(err) },
            confirmButton = {
                MinisButton(onClick = { selectionError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }

    // [T-android-soul-custom-icon] The only remaining import rejection is "the
    // image could not be read"; any size/aspect ratio is accepted.
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

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(SoulUiText.deleteConfirmTitle()) },
            text = { Text(SoulUiText.deleteConfirmBody(profileDisplayName(target))) },
            confirmButton = {
                MinisTextButton(onClick = {
                    deleteTarget = null
                    deleteProfile(target)
                }) { Text(SoulUiText.delete()) }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.soul_cancel))
                }
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
}

/** A guarded navigation action: run now, or after the discard confirmation. */
private sealed interface PendingSoulAction {
    data class SwitchProfile(val id: String) : PendingSoulAction
    data object Create : PendingSoulAction
    data object Back : PendingSoulAction
}

private fun profileDisplayName(profile: SoulProfiles.SoulProfile): String =
    profile.file.metadata.name.ifBlank {
        if (profile.id == SoulProfiles.DEFAULT_ID) SoulMetadata.DEFAULT.name else profile.id
    }

private fun profilePreview(profile: SoulProfiles.SoulProfile): String =
    profile.file.body.lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.take(90)
        ?: SoulUiText.emptyBody()

/**
 * Local zh/en copy for the controls added with multi-SOUL.
 *
 * Kept here instead of the shared `strings.xml` resources (whose per-locale
 * variants are managed separately) because this change is not
 * allowed to touch shared resources; the main integration can promote these
 * keys later without changing behaviour. The device locale is the same input
 * Android resource selection uses unless the app overrides its locale.
 */
private object SoulUiText {
    private fun zh(): Boolean = Locale.getDefault().language.lowercase().startsWith("zh")

    private fun pick(zh: String, en: String): String = if (zh()) zh else en

    fun screenTitle(): String = "SOUL"

    fun profilesHeader(): String = pick("SOUL 档案", "SOUL profiles")

    fun profilesFooter(): String = pick(
        "每个档案有独立的名字、图标和正文。正文会作为所选对话的应用系统提示词发送。",
        "Each profile has its own name, icon and body. The body is sent as the application system prompt of the chat that selects it.",
    )

    fun editorHeader(): String = pick("编辑 SOUL", "Edit SOUL")

    fun newProfile(): String = pick("新建 SOUL", "New SOUL")

    fun newProfileName(): String = pick("新 SOUL", "New SOUL")

    fun delete(): String = pick("删除", "Delete")

    fun deleteConfirmTitle(): String = pick("删除这个 SOUL？", "Delete this SOUL?")

    fun deleteConfirmBody(name: String): String = pick(
        "「$name」将从设备上删除，无法撤销。选择它的对话会回退到默认 SOUL。",
        "“$name” will be removed from this device. This cannot be undone, and chats using it fall back to the default SOUL.",
    )

    fun loadErrorTitle(): String = pick("无法载入 SOUL", "Could not load SOUL")

    fun loadFailed(id: String): String = pick(
        "无法读取档案「$id」，它可能已被删除或文件不可读。已切换到默认 SOUL。",
        "Could not read the profile “$id”. It may have been deleted or its file is unreadable. Switched to the default SOUL.",
    )

    fun selectionErrorTitle(): String = pick("无法保存对话 SOUL", "Could not save chat SOUL")

    fun sessionUse(): String = pick("当前对话使用此 SOUL", "Use this SOUL in the current chat")

    fun emptyBody(): String = pick("正文为空", "Empty body")

    fun loading(): String = pick("正在载入…", "Loading…")
}

/**
 * [T-android-soul-custom-icon] The icon itself, rendered the same way
 * everywhere it appears: a decoded bitmap, an emoji, or the default sparkle.
 *
 * The bitmap decode is `remember`ed on the icon string inside
 * [SoulAvatarGlyph]. Without that, the chat header would re-decode base64 on
 * every recomposition — and headers recompose on every streaming tick.
 */
@Composable
internal fun SoulIconGlyph(
    icon: String,
    sizeDp: Dp,
    emojiSp: TextUnit,
    sparkleTint: Brush? = null,
) {
    // [T-soul-avatar-circle] One renderer for every surface: the picture is
    // cropped to fill a clipped circle with no padding, matching the chat
    // header and the assistant row.
    SoulAvatarGlyph(
        icon = icon,
        sizeDp = sizeDp,
        emojiSp = emojiSp,
        sparkleTint = sparkleTint,
    )
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
        // Title only: the renderer decides framing, so no crop promise here.
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