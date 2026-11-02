package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.AutoCompactPrefs
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.ui.chat.ModelPickerSheet
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisTextButton
import java.text.NumberFormat

/**
 * Per-chat-model threshold editor and global summary-model selection.
 * Both model choices reuse the actual chat composer picker (search, provider
 * sections and model rows), restricted to concrete usable text entries.
 * Selecting here only edits compaction settings, never a chat's binding.
 */
@Composable
fun AutoCompactSettingsScreen(
    onBack: () -> Unit,
    providerRepository: ProviderRepository,
) {
    val context = LocalContext.current
    val config by AutoCompactPrefs.config.collectAsState()
    val providerConfig by providerRepository.config.collectAsState()

    val instancesById = remember(providerConfig) {
        providerConfig.instances.associateBy { it.id }
    }
    val pickerConfig = remember(providerConfig) { compactionPickerConfig(providerConfig) }
    val selectableEntries = pickerConfig.modelEntries
    // This is an editor target, NOT the model that writes the summary. Keep the
    // target stable across rotations and summary-model changes.
    var thresholdEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val thresholdEntry = selectableEntries.firstOrNull { it.id == thresholdEntryId }
        ?: providerConfig.modelGroups.firstOrNull { it.id == providerConfig.defaultPrimaryGroupId }
            ?.memberEntryIds?.firstNotNullOfOrNull { id -> selectableEntries.firstOrNull { it.id == id } }
        ?: selectableEntries.firstOrNull()
    val thresholdTokens = thresholdEntry?.let { config.thresholdFor(it.id) }

    val selectedId = config.modelEntryId
    val selectedEntry = selectedId?.let { id -> providerConfig.modelEntries.firstOrNull { it.id == id } }
    val selectedInstance = selectedEntry?.let { instancesById[it.providerInstanceId] }
    val selectedUsable = selectedEntry?.isUsableForCompaction(selectedInstance) == true

    var showThresholdDialog by remember { mutableStateOf(false) }
    var pickerTarget by remember { mutableStateOf<CompactPickerTarget?>(null) }
    var thresholdDialogEntryId by remember { mutableStateOf<String?>(null) }
    var thresholdDraft by remember { mutableStateOf("") }
    var thresholdError by remember { mutableStateOf(ThresholdError.NONE) }

    SettingsScaffold(
        title = stringResource(R.string.auto_compact_settings_title),
        onBack = onBack,
    ) {
        SettingsSection(footer = stringResource(R.string.auto_compact_switch_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.auto_compact_switch_title),
                checked = config.enabled,
                onCheckedChange = { AutoCompactPrefs.setEnabled(context, it) },
                showDivider = false,
            )
        }

        SettingsSection(footer = stringResource(R.string.auto_compact_threshold_per_model_footer)) {
            SettingsValueRow(
                title = stringResource(R.string.auto_compact_threshold_model_title),
                value = thresholdEntry?.let { entryLabel(it, instancesById[it.providerInstanceId]) }
                    ?: stringResource(R.string.auto_compact_threshold_choose_model),
                onClick = { pickerTarget = CompactPickerTarget.THRESHOLD },
                showDivider = true,
            )
            if (thresholdEntry != null) {
                SettingsValueRow(
                    title = stringResource(R.string.auto_compact_threshold_title),
                    value = thresholdTokens
                        ?.let { stringResource(R.string.auto_compact_threshold_value_fmt, formatTokens(it)) }
                        ?: stringResource(R.string.auto_compact_threshold_default),
                    subtitle = if (thresholdTokens == null) {
                        stringResource(R.string.auto_compact_threshold_default_subtitle)
                    } else null,
                    onClick = {
                        // Capture the exact key so config refreshes while the
                        // dialog is open cannot apply a draft to another model.
                        thresholdDialogEntryId = thresholdEntry.id
                        thresholdDraft = thresholdTokens?.toString().orEmpty()
                        thresholdError = ThresholdError.NONE
                        showThresholdDialog = true
                    },
                    showDivider = false,
                )
            } else {
                Text(
                    stringResource(R.string.auto_compact_model_none),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SettingsSection(footer = stringResource(R.string.auto_compact_model_footer)) {
            // The value stays the user's saved choice even when it no longer
            // works; the subtitle explains. The row still opens the picker so
            // one tap replaces it.
            val modelValue = when {
                selectedId == null -> stringResource(R.string.auto_compact_model_follow_chat)
                selectedEntry != null -> entryLabel(selectedEntry, selectedInstance)
                else -> selectedId
            }
            val modelWarning = when {
                selectedId == null || selectedUsable -> null
                selectedEntry == null -> stringResource(
                    R.string.auto_compact_model_missing_fmt,
                    selectedId,
                )
                else -> stringResource(
                    R.string.auto_compact_model_unavailable_fmt,
                    entryLabel(selectedEntry, selectedInstance),
                )
            }
            SettingsValueRow(
                title = stringResource(R.string.auto_compact_model_title),
                value = modelValue,
                subtitle = modelWarning,
                valueColor = if (modelWarning != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                onClick = { pickerTarget = CompactPickerTarget.SUMMARY },
                showDivider = selectedId != null,
            )
            if (selectedId != null) {
                SettingsChoiceRow(
                    title = stringResource(R.string.auto_compact_model_follow_chat),
                    selected = false,
                    onSelect = { AutoCompactPrefs.setModelEntryId(context, null) },
                    showDivider = false,
                )
            }
        }
    }

    if (showThresholdDialog && thresholdDialogEntryId != null) {
        val editingEntryId = thresholdDialogEntryId!!
        val tooLargeMessage = stringResource(
            R.string.auto_compact_threshold_error_too_large,
            formatTokens(Int.MAX_VALUE),
        )
        val errorMessage = when (thresholdError) {
            ThresholdError.NONE -> null
            ThresholdError.NOT_POSITIVE -> stringResource(R.string.auto_compact_threshold_error_positive)
            ThresholdError.TOO_LARGE -> tooLargeMessage
        }
        AlertDialog(
            onDismissRequest = { showThresholdDialog = false },
            title = { Text(stringResource(R.string.auto_compact_threshold_dialog_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = thresholdDraft,
                        onValueChange = {
                            thresholdDraft = it
                            // Any edit clears the old complaint; the new text
                            // is validated when Save is pressed, so the draft is
                            // never discarded on an invalid keystroke.
                            thresholdError = ThresholdError.NONE
                        },
                        label = { Text(stringResource(R.string.auto_compact_threshold_dialog_label)) },
                        placeholder = { Text(stringResource(R.string.auto_compact_threshold_default)) },
                        singleLine = true,
                        isError = errorMessage != null,
                        supportingText = {
                            Text(
                                text = errorMessage
                                    ?: stringResource(R.string.auto_compact_threshold_dialog_hint),
                                color = if (errorMessage != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Only offered when a number is actually set; when blank the
                    // field already IS the default, so a reset button would do
                    // nothing visible.
                    if (config.thresholdFor(editingEntryId) != null) {
                        MinisTextButton(
                            onClick = {
                                thresholdDraft = ""
                                thresholdError = ThresholdError.NONE
                                AutoCompactPrefs.setThresholdTokens(context, editingEntryId, null)
                                showThresholdDialog = false
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(stringResource(R.string.auto_compact_threshold_use_default))
                        }
                    }
                }
            },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        // Validation is a pure function so the failure path is
                        // testable: on invalid input the dialog stays open with
                        // the draft intact, and nothing is persisted.
                        when (val parsed = parseThresholdInput(thresholdDraft)) {
                            is ThresholdParseResult.Valid -> {
                                AutoCompactPrefs.setThresholdTokens(context, editingEntryId, parsed.tokens)
                                showThresholdDialog = false
                            }
                            is ThresholdParseResult.Invalid -> thresholdError = parsed.error
                        }
                    },
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showThresholdDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    pickerTarget?.let { target ->
        ModelPickerSheet(
            // A threshold / explicit summary choice is a concrete entry, not
            // a routing group whose membership can change underneath it.
            groups = emptyList(),
            selectedGroupId = null,
            activeEntryId = if (target == CompactPickerTarget.THRESHOLD) thresholdEntry?.id else selectedId,
            defaultPrimaryGroupId = null,
            config = pickerConfig,
            providerRepository = providerRepository,
            onSelectGroup = {},
            onSelectGroupEntry = { _, _ -> },
            onSelectEntry = { entryId ->
                // Revalidate against the latest repository snapshot, not the
                // filtered list captured when this sheet was first opened.
                val latest = providerRepository.config.value
                val entry = latest.modelEntries.firstOrNull { it.id == entryId }
                if (entry?.isUsableForCompaction(latest.instances.firstOrNull { it.id == entry.providerInstanceId }) == true) {
                    when (target) {
                        CompactPickerTarget.THRESHOLD -> thresholdEntryId = entryId
                        CompactPickerTarget.SUMMARY -> AutoCompactPrefs.setModelEntryId(context, entryId)
                    }
                    pickerTarget = null
                }
            },
            onDismiss = { pickerTarget = null },
        )
    }
}

private enum class CompactPickerTarget { THRESHOLD, SUMMARY }

/** Detached, locally filtered config; never mutate the repository's lists. */
internal fun compactionPickerConfig(config: ProviderConfig): ProviderConfig {
    val instances = config.instances.filter { it.isEnabled && it.providerType.isUsable }
    val byId = instances.associateBy { it.id }
    return config.copy(
        instances = instances.toMutableList(),
        modelEntries = config.modelEntries.filter { it.isUsableForCompaction(byId[it.providerInstanceId]) }.toMutableList(),
        modelGroups = mutableListOf(),
    )
}

/**
 * A compaction model must be callable and must produce text:
 *
 *  - provider instance exists, is enabled, and its type is implemented here
 *    (a decode-only provider restored from another build cannot serve a call);
 *  - the entry is not hidden;
 *  - the model outputs text (null/unspecified output modalities count as text,
 *    matching `LLMModel.isTextOutput`).
 */
internal fun ModelEntry.isUsableForCompaction(instance: ProviderInstance?): Boolean =
    instance != null &&
        instance.isEnabled &&
        instance.providerType.isUsable &&
        !isHidden &&
        model.isTextOutput

@Composable
private fun entryLabel(entry: ModelEntry, instance: ProviderInstance?): String =
    if (instance == null || instance.label.isBlank()) {
        entry.model.displayName
    } else {
        stringResource(
            R.string.auto_compact_model_entry_fmt,
            entry.model.displayName,
            instance.label,
        )
    }

private fun formatTokens(tokens: Int): String =
    NumberFormat.getIntegerInstance().format(tokens.toLong())

/** Which validation complaint the threshold dialog is currently showing. */
internal enum class ThresholdError { NONE, NOT_POSITIVE, TOO_LARGE }

/**
 * Outcome of reading the threshold text field.
 *
 * [Valid] with `tokens = null` means "empty field = restore the capacity-derived
 * default", which is a valid save, not a failure. [Invalid] is what keeps the
 * dialog open with the draft still in the field.
 */
internal sealed class ThresholdParseResult {
    data class Valid(val tokens: Int?) : ThresholdParseResult()
    data class Invalid(val error: ThresholdError) : ThresholdParseResult()
}

/**
 * Validate the token field: blank restores the default; otherwise the text must
 * be a whole number in 1..Int.MAX_VALUE. Kept free of Compose state so the
 * reject path (0, negatives, overflow, junk) is unit-testable without a UI.
 */
internal fun parseThresholdInput(raw: String): ThresholdParseResult {
    val text = raw.trim()
    if (text.isEmpty()) return ThresholdParseResult.Valid(null)
    // BigInteger, not Long: a 30-digit paste is a real (if absurd) token count,
    // and must be reported as "too large" rather than mislabelled as junk.
    val parsed = text.toBigIntegerOrNull()
        ?: return ThresholdParseResult.Invalid(ThresholdError.NOT_POSITIVE)
    if (parsed.signum() <= 0) return ThresholdParseResult.Invalid(ThresholdError.NOT_POSITIVE)
    if (parsed > java.math.BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
        return ThresholdParseResult.Invalid(ThresholdError.TOO_LARGE)
    }
    return ThresholdParseResult.Valid(parsed.toInt())
}
