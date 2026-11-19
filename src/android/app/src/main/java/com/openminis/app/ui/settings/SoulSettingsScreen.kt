package com.openminis.app.ui.settings

import com.openminis.app.ui.components.minisJellyPress

import top.yukonga.miuix.kmp.theme.MiuixTheme
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import com.openminis.app.ui.components.miuix.DropdownMenu
import com.openminis.app.ui.components.miuix.DropdownMenuItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.safeLaunchPicker
import com.openminis.app.agent.SoulIcon
import com.openminis.app.agent.SoulFile
import com.openminis.app.agent.SoulMDParser
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.ui.unit.DpSize
import com.openminis.app.ui.components.MinisBottomSheet
import top.yukonga.miuix.kmp.basic.TextField

/**
 * [K-global-prompt-only] Settings → System Prompt.
 *
 * The page edits the application's ONE global system prompt plus the identity
 * shown in chat: avatar, name and the Markdown body of SOUL.md. There is no
 * GLOBAL/CUSTOM switch, no per-model or per-group prompt and no body-length
 * control — older `SOUL_PROMPT_RULES.json` data is left on disk untouched but
 * is never read to build a prompt, and the global body is saved and sent as
 * written.
 *
 * `style`, `lang` and `emoji` are still round-tripped through the file so an
 * older SOUL.md is not rewritten just because this page has no field for them.
 *
 * Prompt assembly and instruction priority live in the agent layer
 * ([com.openminis.app.agent.SystemPromptBuilder]) and are unchanged by this
 * screen.
 */
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
    // [T-android-soul-two-scope-switch] Save is a one-shot: the first tap sets
    // this before the coroutine starts, so a second tap cannot enqueue a
    // second write — and the button below is disabled while it is true.
    var saving by remember { mutableStateOf(false) }
    // The global editor still needs load readiness and write-error state even
    // though mode/rule state was removed. Keep drafts visible on save failure.
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

    // [T-android-soul-save-in-appbar] What was loaded from disk, kept so
    // "has the user changed anything" is a comparison rather than a flag.
    //
    // A boolean set by every onValueChange would report dirty after a round
    // trip that lands back on the original text (type a character, delete it),
    // and would need a reset at each of the several places state is
    // reassigned — the load and the save itself. A snapshot compared by value
    // cannot drift out of sync with the fields.
    var baseline by remember { mutableStateOf<SoulFile?>(null) }

    // [T-android-soul-custom-icon] Image import: the source stream is sampled
    // while decoding ([SoulIcon.importFrom]), the aspect ratio is preserved,
    // the longest edge is bounded ([SoulIcon.STORED_PIXELS]), then it is
    // re-encoded as PNG + base64. Nothing is cropped on save; the avatar
    // renderer fills its circle at draw time. Runs off the main thread.
    val iconUnreadableMsg = stringResource(R.string.soul_icon_error_unreadable)
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // [T-soul-avatar-import-no-size-cap] Sample while decoding, so a
            // 100MP source never becomes a ~400MB bitmap. Any byte size /
            // pixel count is accepted; there is no "too large" rejection to
            // report.
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SoulIcon.importFrom { context.contentResolver.openInputStream(uri) }
                }.getOrElse {
                    SoulIcon.EncodeResult.Failure(SoulIcon.Rejection.UNREADABLE)
                }
            }
            when (result) {
                is SoulIcon.EncodeResult.Success -> icon = result.dataUri
                // Identity row is a full circle now, so a failure can only be
                // "the image could not be read"; no size verdict is surfaced.
                is SoulIcon.EncodeResult.Failure -> iconError = iconUnreadableMsg
            }
        }
    }

    // Initial load + (defensive) ensureExists. The Application-level call
    // already seeded on first run, but loading from a freshly cleared app
    // shouldn't crash.
    LaunchedEffect(Unit) {
        val parsed = withContext(Dispatchers.IO) {
            SoulStore.ensureExists(context)
            SoulStore.load(context) ?: SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
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

    // [T-android-soul-two-scope-switch] One write per tap. `saving` is set
    // synchronously, before the coroutine is launched, so a double tap cannot
    // start two SOUL.md writes; the button below is disabled from the same
    // flag. A failure reports itself and leaves `body` exactly as the user
    // typed it — the draft only ends when a successful save leaves the screen.
    val save: () -> Unit = {
        if (!saving) {
            saving = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { SoulStore.save(context, currentFile) }
                    onBack()
                } catch (t: Throwable) {
                    saveError = t.message ?: "save failed"
                } finally {
                    saving = false
                }
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
                                    .background(MiuixTheme.colorScheme.surfaceVariant)
                                    .border(
                                        width = 1.dp,
                                        color = MiuixTheme.colorScheme.dividerLine,
                                        shape = CircleShape,
                                    )
                                    .minisJellyPress()
                                    .clickable { showIconMenu = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                SoulIconGlyph(icon = icon, sizeDp = 44.dp, emojiSp = 26.sp)
                                // Badge kept INSIDE the circle's bounds: on iOS an
                                // overhanging badge was clipped to a sliver by the
                                // enclosing button label.
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(2.dp)
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(MiuixTheme.colorScheme.primary)
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
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            label = stringResource(R.string.soul_field_name),
                            useLabelAsPlaceholder = false,
                            modifier = Modifier
                                                        .weight(1f)
                                                        .padding(start = 4.dp),
                        )
                    }
                }

                // [K-global-prompt-only] Identity and the global body share this
                // section because both are committed by the same SOUL.md save.
                SettingsSection(
                    header = stringResource(R.string.soul_section_personality),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        // [T-android-editor-keyboard-overlap] The page scrolls and
                        // pads for the keyboard (SettingsScaffold); this field asks
                        // it to keep the caret's line in view, not just the field.
                        com.openminis.app.ui.components.DocumentEditorField(
                            value = body,
                            onValueChange = { body = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MiuixTheme.textStyles.body1.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            placeholder = stringResource(R.string.soul_body_placeholder),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
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
                                    !loaded -> ""
                                    // [T-android-soul-two-scope-switch] Saving is
                                    // its own state: the draft is still dirty
                                    // until the write lands, but saying
                                    // "Unsaved changes" mid-write reads as if
                                    // the tap was ignored.
                                    saving -> stringResource(R.string.system_prompt_saving)
                                    isDirty -> stringResource(R.string.kairos_prompt_global_unsaved)
                                    else -> stringResource(R.string.kairos_prompt_global_saved)
                                },
                                style = MiuixTheme.textStyles.body2,
                                color = if (isDirty || saving) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceContainerVariant
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = save,
                                enabled = loaded && isDirty && !saving,
                                text = stringResource(R.string.soul_save),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }
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
        WindowDialog(
            show = true,
            onDismissRequest = { showDiscardDialog = false },
            title = stringResource(R.string.soul_discard_confirm_title),
            summary = stringResource(R.string.soul_discard_confirm_body),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                                    onClick = {
                                    showDiscardDialog = false
                                    onBack()
                                },
                                    text = stringResource(R.string.soul_discard_confirm),
                                    colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                                )
                TextButton(
                                    onClick = { showDiscardDialog = false },
                                    text = stringResource(R.string.soul_discard_cancel),
                                    colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                                )
                }
            }
        }
    }

    saveError?.let { err ->
        WindowDialog(
            show = true,
            onDismissRequest = { saveError = null },
            title = stringResource(R.string.soul_save_error_title),
            summary = err,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                                    onClick = { saveError = null },
                                    text = stringResource(R.string.soul_ok),
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                )
                }
            }
        }
    }

    // [T-android-soul-custom-icon] Any image size and aspect ratio is
    // accepted (sampled on import, filled into the circle on draw), so the
    // only remaining rejection is "the image could not be read" — nothing
    // about size can be predicted before the user picks a file.
    iconError?.let { err ->
        WindowDialog(
            show = true,
            onDismissRequest = { iconError = null },
            title = stringResource(R.string.soul_icon_error_title),
            summary = err,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                                    onClick = { iconError = null },
                                    text = stringResource(R.string.soul_ok),
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                )
                }
            }
        }
    }

    if (showEmojiSheet) {
        SoulEmojiPickerSheet(
            current = if (SoulIcon.isDataUri(icon)) "" else icon,
            onDismiss = { showEmojiSheet = false },
            onPick = { chosen -> icon = chosen; showEmojiSheet = false },
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
    // [T-soul-avatar-circle] One renderer for every surface: the picture is
    // cropped to fill a clipped circle with no padding, matching the chat
    // header and the assistant row.
    com.openminis.app.ui.components.SoulAvatarGlyph(
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
@Composable
private fun SoulEmojiPickerSheet(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    MinisBottomSheet(
        show = true,
        deferDismissRequest = true,
        insideMargin = DpSize.Zero,
        onDismissRequest = onDismiss,
    ) {
        val dismissSheet = top.yukonga.miuix.kmp.theme.LocalDismissState.current ?: onDismiss
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
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            // Preview at the size the icon is actually drawn on the card.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surfaceVariant),
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
                                    if (e == draft) MiuixTheme.colorScheme.primaryContainer
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
            TextField(
                value = draft,
                onValueChange = { draft = SoulIcon.normalizeEmojiInput(it) },
                singleLine = true,
                label = stringResource(R.string.soul_icon_emoji_field),
                useLabelAsPlaceholder = false,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    onClick = dismissSheet,
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.soul_cancel),
                )
                TextButton(
                    onClick = { onPick(draft) },
                    enabled = draft.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.soul_icon_set),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
