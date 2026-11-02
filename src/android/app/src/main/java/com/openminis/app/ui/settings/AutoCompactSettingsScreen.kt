package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.AutoCompactPrefs
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisTextButton
import java.text.NumberFormat

/**
 * [T-android-auto-compact-settings] Settings › Context Compaction.
 *
 * One page owns the three knobs the threshold auto-compact path reads:
 *
 *  - the existing global on/off switch ([AutoCompactPrefs.isEnabled]);
 *  - a user-chosen token threshold, blank = the capacity-derived default;
 *  - which model writes the summary, default = follow the current chat model.
 *
 * The page is deliberately a plain settings list (grouped cards + AlertDialogs,
 * matching every other settings page) rather than a picker sheet: it is a
 * set-once preference, and the model list is filtered down to entries that can
 * actually do the job, so most users see a dozen rows at most.
 *
 * Nothing here triggers a network request. Availability is decided from the
 * provider config already loaded, so opening the page or picking a model costs
 * no tokens. A stored selection that no longer resolves is REPORTED, never
 * replaced: replacing it would silently change how summaries are written (and
 * on a metered provider, what they cost) without the user doing anything.
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
    // Enabled providers only: a model entry under a disabled provider cannot be
    // called, so offering it would let the user save a threshold that can never
    // compact. Hidden entries and non-text-output models (image / audio / video
    // generators) are excluded for the same reason — a summary is text.
    val selectableEntries = remember(providerConfig, instancesById) {
        providerConfig.modelEntries
            .filter { entry -> entry.isUsableForCompaction(instancesById[entry.providerInstanceId]) }
            .sortedWith(
                compareBy(
                    { instancesById[it.providerInstanceId]?.label.orEmpty().lowercase() },
                    { it.model.displayName.lowercase() },
                ),
            )
    }

    val selectedId = config.modelEntryId
    val selectedEntry = selectedId?.let { id -> providerConfig.modelEntries.firstOrNull { it.id == id } }
    val selectedInstance = selectedEntry?.let { instancesById[it.providerInstanceId] }
    val selectedUsable = selectedEntry?.isUsableForCompaction(selectedInstance) == true

    var showThresholdDialog by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
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
                showDivider = true,
            )
            SettingsValueRow(
                title = stringResource(R.string.auto_compact_threshold_title),
                value = config.thresholdTokens
                    ?.let { stringResource(R.string.auto_compact_threshold_value_fmt, formatTokens(it)) }
                    ?: stringResource(R.string.auto_compact_threshold_default),
                subtitle = if (config.thresholdTokens == null) {
                    stringResource(R.string.auto_compact_threshold_default_subtitle)
                } else {
                    null
                },
                onClick = {
                    thresholdDraft = config.thresholdTokens?.toString().orEmpty()
                    thresholdError = ThresholdError.NONE
                    showThresholdDialog = true
                },
                showDivider = false,
            )
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
                onClick = { showModelPicker = true },
                showDivider = false,
            )
        }
    }

    if (showThresholdDialog) {
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
                    if (config.thresholdTokens != null) {
                        MinisTextButton(
                            onClick = {
                                thresholdDraft = ""
                                thresholdError = ThresholdError.NONE
                                AutoCompactPrefs.setThresholdTokens(context, null)
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
                                AutoCompactPrefs.setThresholdTokens(context, parsed.tokens)
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

    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = { Text(stringResource(R.string.auto_compact_model_picker_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SettingsChoiceRow(
                        title = stringResource(R.string.auto_compact_model_follow_chat),
                        selected = selectedId == null,
                        onSelect = {
                            AutoCompactPrefs.setModelEntryId(context, null)
                            showModelPicker = false
                        },
                        showDivider = selectableEntries.isNotEmpty(),
                    )
                    if (selectableEntries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.auto_compact_model_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    selectableEntries.forEachIndexed { index, entry ->
                        val instance = instancesById[entry.providerInstanceId]
                        SettingsChoiceRow(
                            title = entryLabel(entry, instance),
                            selected = entry.id == selectedId,
                            onSelect = {
                                AutoCompactPrefs.setModelEntryId(context, entry.id)
                                showModelPicker = false
                            },
                            showDivider = index < selectableEntries.lastIndex,
                        )
                    }
                }
            },
            confirmButton = {
                MinisTextButton(onClick = { showModelPicker = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
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
