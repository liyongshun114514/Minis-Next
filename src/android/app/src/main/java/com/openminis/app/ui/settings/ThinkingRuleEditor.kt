package com.openminis.app.ui.settings

import com.openminis.app.ui.components.minisJellyPress

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.openminis.app.ui.components.miuix.DropdownMenu
import com.openminis.app.ui.components.miuix.DropdownMenuItem
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.provider.thinking.ThinkingWireFormat
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics

/** What the section asks the editor to open with. */
data class ThinkingRuleEditorRequest(
    /** Non-null → editing an existing custom rule (preserve id). Null → creating new. */
    val existingId: String?,
    val seed: ThinkingRule,
    val isNew: Boolean,
)

/**
 * [T-android-thinking-rules-phase2 §3] Full-screen-ish dialog to add/edit a custom rule.
 * A Dialog (own window) — deliberately NOT a sheet hosted by a section, which is the
 * navigation trap iOS hit (the sheet dismissed the whole settings stack).
 *
 * User-selectable wire formats are a deliberate SUBSET (the Chat-Completions family):
 * Gemini/Anthropic formats are model-generation dependent and would only build broken
 * rules on this path, so they are not offered.
 */
@Composable
fun ThinkingRuleEditorDialog(
    request: ThinkingRuleEditorRequest,
    onDismiss: () -> Unit,
    onSave: (ThinkingRule) -> Unit,
) {
    val seed = request.seed
    var label by remember { mutableStateOf(seed.label) }
    var allModels by remember { mutableStateOf(seed.scope is ThinkingRule.Scope.AllModels) }
    var pattern by remember {
        mutableStateOf((seed.scope as? ThinkingRule.Scope.ModelPattern)?.pattern ?: "")
    }
    var choice by remember { mutableStateOf(FormatChoice.from(seed.wireFormat)) }
    // Shared editable params across formats.
    var sendOffValue by remember { mutableStateOf(formatOffValue(seed.wireFormat) != null) }
    var offValue by remember { mutableStateOf(formatOffValue(seed.wireFormat) ?: "") }
    var path by remember { mutableStateOf(formatPath(seed.wireFormat) ?: "") }
    var highValue by remember {
        mutableStateOf((seed.wireFormat as? ThinkingWireFormat.CustomPath)?.values?.get(ThinkingLevel.HIGH) ?: "")
    }
    var formatMenuOpen by remember { mutableStateOf(false) }

    fun buildWireFormat(): ThinkingWireFormat = when (choice) {
        FormatChoice.OMIT -> ThinkingWireFormat.OmitEverything
        FormatChoice.REASONING_EFFORT -> ThinkingWireFormat.ReasoningEffort(if (sendOffValue) offValue.ifBlank { null } else null)
        FormatChoice.REASONING_EFFORT_NESTED -> ThinkingWireFormat.ReasoningEffortNested(if (sendOffValue) offValue.ifBlank { null } else null)
        FormatChoice.BOOLEAN_TOGGLE -> ThinkingWireFormat.BooleanToggle(path.ifBlank { "thinking" })
        FormatChoice.EXTRA_BODY_TOGGLE -> ThinkingWireFormat.ExtraBodyToggle(path.ifBlank { "thinking.enabled" })
        FormatChoice.DEEPSEEK_SIBLING -> ThinkingWireFormat.DeepSeekSibling
        FormatChoice.QWEN_DUAL -> ThinkingWireFormat.QwenDual
        FormatChoice.CUSTOM_PATH -> ThinkingWireFormat.CustomPath(
            path = path,
            values = if (highValue.isNotBlank()) mapOf(ThinkingLevel.HIGH to highValue) else emptyMap(),
            offValue = if (sendOffValue) offValue.ifBlank { null } else null,
        )
    }

    fun buildRule() = ThinkingRule(
        kind = ThinkingRule.Kind.CUSTOM,
        scope = if (allModels) ThinkingRule.Scope.AllModels else ThinkingRule.Scope.ModelPattern(pattern.trim()),
        wireFormat = buildWireFormat(),
        label = label.trim(),
    )

    // [GH#306] Per-field validation for CustomPath. Previously the only check was
    // "path is not blank", so a rule with an empty value saved cleanly and then emitted
    // nothing — indistinguishable, from the chat, from the rule not existing.
    //
    // Reserved roots are refused here as well as in the resolver: the resolver's refusal
    // is the safety net, but catching it in the editor is the only place the user can
    // still see WHY. Kept in sync with ThinkingRuleResolver.RESERVED_ROOT_KEYS.
    val reservedRoots = setOf(
        "messages", "model", "stream", "stream_options", "tools", "tool_choice",
        "n", "functions", "function_call", "response_format",
    )
    val isCustom = choice == FormatChoice.CUSTOM_PATH
    val pathRoot = path.split(".").firstOrNull()?.trim()?.lowercase().orEmpty()
    val pathError: String? = when {
        !isCustom -> null
        path.isBlank() -> stringResource(R.string.thinking_rules_error_path_required)
        pathRoot in reservedRoots -> stringResource(R.string.thinking_rules_error_path_reserved, pathRoot)
        else -> null
    }
    val valueError: String? =
        if (isCustom && highValue.isBlank()) stringResource(R.string.thinking_rules_error_value_required) else null
    val offValueError: String? =
        if (isCustom && sendOffValue && offValue.isBlank()) {
            stringResource(R.string.thinking_rules_error_off_value_required)
        } else {
            null
        }

    val isValid = label.isNotBlank() &&
        (allModels || pattern.isNotBlank()) &&
        pathError == null && valueError == null && offValueError == null

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = stringResource(
                        if (request.isNew) R.string.thinking_rules_new_rule else R.string.thinking_rules_edit_rule,
                    ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            // Name
                            TextField(
                                value = label,
                                onValueChange = { label = it },
                                singleLine = true,
                                label = stringResource(R.string.thinking_rules_rule_name),
                                useLabelAsPlaceholder = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.padding(6.dp))

                            // Applies to
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(stringResource(R.string.thinking_rules_all_models))
                                Switch(checked = allModels, onCheckedChange = { allModels = it })
                            }
                            if (!allModels) {
                                TextField(
                                    value = pattern,
                                    onValueChange = { pattern = it },
                                    singleLine = true,
                                    label = stringResource(R.string.thinking_rules_model_pattern),
                                    useLabelAsPlaceholder = false,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    stringResource(R.string.thinking_rules_glob_hint),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                )
                            }
                            Spacer(Modifier.padding(6.dp))

                            // What to send (format picker)
                            Text(
                                stringResource(R.string.thinking_rules_what_to_send),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )
                            Column {
                                TextButton(
                                    modifier = Modifier.minisJellyPress(),
                                    onClick = { formatMenuOpen = true },
                                    text = stringResource(choice.titleRes),
                                    colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                                )
                                DropdownMenu(expanded = formatMenuOpen, onDismissRequest = { formatMenuOpen = false }) {
                                    FormatChoice.entries.forEach { c ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(c.titleRes)) },
                                            onClick = { choice = c; formatMenuOpen = false },
                                        )
                                    }
                                }
                            }
                            Text(
                                stringResource(choice.explanationRes),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )

                            // Per-format fields
                            when (choice) {
                                FormatChoice.REASONING_EFFORT, FormatChoice.REASONING_EFFORT_NESTED -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(stringResource(R.string.thinking_rules_send_off_value))
                                        Switch(checked = sendOffValue, onCheckedChange = { sendOffValue = it })
                                    }
                                    if (sendOffValue) {
                                        TextField(
                                            value = offValue,
                                            onValueChange = { offValue = it },
                                            singleLine = true,
                                            label = stringResource(R.string.thinking_rules_off_value),
                                            useLabelAsPlaceholder = false,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                FormatChoice.BOOLEAN_TOGGLE, FormatChoice.EXTRA_BODY_TOGGLE -> {
                                    TextField(
                                        value = path,
                                        onValueChange = { path = it },
                                        singleLine = true,
                                        label = stringResource(R.string.thinking_rules_field_path),
                                        useLabelAsPlaceholder = false,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                FormatChoice.CUSTOM_PATH -> {
                                    // [GH#306] `pathError` / `valueError` below: a CustomPath rule with
                                    // a blank path or blank value used to save happily and then emit
                                    // nothing, which reads as "my rule is ignored". Validating at the
                                    // point of entry is the only place the user can still fix it.
                                    Column(Modifier.fillMaxWidth() .semantics { if (pathError != null) error("Invalid value") }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextField(
                                            value = path,
                                            onValueChange = { path = it },
                                            singleLine = true,
                                            colors = TextFieldDefaults.textFieldColors().let { if (pathError != null) it.copy(borderColor = MiuixTheme.colorScheme.error) else it },
                                            label = stringResource(R.string.thinking_rules_dotted_path),
                                            useLabelAsPlaceholder = false,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        val support: (@Composable () -> Unit)? = pathError?.let { { Text(it) } }
                                        support?.invoke()
                                    }
                                    Column(Modifier.fillMaxWidth() .semantics { if (valueError != null) error("Invalid value") }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextField(
                                            value = highValue,
                                            onValueChange = { highValue = it },
                                            singleLine = true,
                                            colors = TextFieldDefaults.textFieldColors().let { if (valueError != null) it.copy(borderColor = MiuixTheme.colorScheme.error) else it },
                                            label = stringResource(R.string.thinking_rules_value_at_high),
                                            useLabelAsPlaceholder = false,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        val support: (@Composable () -> Unit)? = valueError?.let { { Text(it) } }
                                        support?.invoke()
                                    }
                                    // [GH#306] The off-value row was missing for CustomPath, so
                                    // `sendOffValue` could never become true and the rule's offValue
                                    // was unreachable from the UI — a thinking OFF tier then wrote
                                    // nothing at all, silently leaving the vendor's default in force.
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(stringResource(R.string.thinking_rules_send_off_value))
                                        Switch(checked = sendOffValue, onCheckedChange = { sendOffValue = it })
                                    }
                                    if (sendOffValue) {
                                        Column(Modifier.fillMaxWidth() .semantics { if (offValueError != null) error("Invalid value") }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextField(
                                                value = offValue,
                                                onValueChange = { offValue = it },
                                                singleLine = true,
                                                colors = TextFieldDefaults.textFieldColors().let { if (offValueError != null) it.copy(borderColor = MiuixTheme.colorScheme.error) else it },
                                                label = stringResource(R.string.thinking_rules_off_value),
                                                useLabelAsPlaceholder = false,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            val support: (@Composable () -> Unit)? = offValueError?.let { { Text(it) } }
                                            support?.invoke()
                                        }
                                    }
                                }
                                else -> {}
                            }

                            // Request preview
                            Spacer(Modifier.padding(6.dp))
                            Text(
                                stringResource(R.string.thinking_rules_request_preview),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )
                            Text(
                                text = previewJson(buildRule()),
                                style = MiuixTheme.textStyles.body2,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                            )
                        }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                            onClick = { if (isValid) onSave(buildRule()) },
                            enabled = isValid,
                            text = stringResource(R.string.common_save),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                        )
            TextButton(
                            onClick = onDismiss,
                            text = stringResource(R.string.common_cancel),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                        )
            }
        }
    }
}

/** The user-selectable wire formats (a subset — Gemini/Anthropic families excluded). */
enum class FormatChoice(val titleRes: Int, val explanationRes: Int) {
    OMIT(R.string.thinking_fmt_omit_title, R.string.thinking_fmt_omit_desc),
    REASONING_EFFORT(R.string.thinking_fmt_effort_title, R.string.thinking_fmt_effort_desc),
    REASONING_EFFORT_NESTED(R.string.thinking_fmt_effort_nested_title, R.string.thinking_fmt_effort_nested_desc),
    BOOLEAN_TOGGLE(R.string.thinking_fmt_bool_title, R.string.thinking_fmt_bool_desc),
    EXTRA_BODY_TOGGLE(R.string.thinking_fmt_extrabody_title, R.string.thinking_fmt_extrabody_desc),
    DEEPSEEK_SIBLING(R.string.thinking_fmt_deepseek_title, R.string.thinking_fmt_deepseek_desc),
    QWEN_DUAL(R.string.thinking_fmt_qwen_title, R.string.thinking_fmt_qwen_desc),
    CUSTOM_PATH(R.string.thinking_fmt_custom_title, R.string.thinking_fmt_custom_desc);

    companion object {
        fun from(fmt: ThinkingWireFormat?): FormatChoice = when (fmt) {
            is ThinkingWireFormat.OmitEverything -> OMIT
            is ThinkingWireFormat.ReasoningEffort -> REASONING_EFFORT
            is ThinkingWireFormat.ReasoningEffortNested -> REASONING_EFFORT_NESTED
            is ThinkingWireFormat.BooleanToggle -> BOOLEAN_TOGGLE
            is ThinkingWireFormat.ExtraBodyToggle -> EXTRA_BODY_TOGGLE
            is ThinkingWireFormat.DeepSeekSibling -> DEEPSEEK_SIBLING
            is ThinkingWireFormat.QwenDual -> QWEN_DUAL
            is ThinkingWireFormat.CustomPath -> CUSTOM_PATH
            else -> REASONING_EFFORT
        }
    }
}

private fun formatOffValue(fmt: ThinkingWireFormat?): String? = when (fmt) {
    is ThinkingWireFormat.ReasoningEffort -> fmt.offValue
    is ThinkingWireFormat.ReasoningEffortNested -> fmt.offValue
    is ThinkingWireFormat.CustomPath -> fmt.offValue
    else -> null
}

private fun formatPath(fmt: ThinkingWireFormat?): String? = when (fmt) {
    is ThinkingWireFormat.BooleanToggle -> fmt.path
    is ThinkingWireFormat.ExtraBodyToggle -> fmt.path
    is ThinkingWireFormat.CustomPath -> fmt.path
    else -> null
}

/** Live preview: run the real resolver with this rule at HIGH against a sample body. */
private fun previewJson(rule: ThinkingRule): String {
    return try {
        val body = JSONObject()
        // Isolate: inject just this one rule as the sole custom rule under a throwaway id.
        val previewInstance = "__preview__"
        ThinkingRuleResolver.setCustomRules(previewInstance, listOf(rule))
        val ctx = ThinkingResolveContext(
            modelId = (rule.scope as? ThinkingRule.Scope.ModelPattern)?.pattern?.replace("*", "x") ?: "sample-model",
            instanceId = previewInstance,
            supportsReasoning = true,
            declaredEffortValues = null,
            level = ThinkingLevel.HIGH,
            maxTokens = 4096,
            isOpenRouter = false,
            usesUnifiedReasoningEffort = false,
            isMistral = false,
            isDashScope = false,
            offEffort = null,
        )
        ThinkingRuleResolver.apply(body, ctx)
        ThinkingRuleResolver.setCustomRules(previewInstance, emptyList())
        if (body.length() == 0) "{ }  (no thinking key sent)" else body.toString(2)
    } catch (e: Exception) {
        "(preview unavailable)"
    }
}

// ---- Summaries shared with the section list ----

fun ruleScopeSummary(rule: ThinkingRule): String = when (val s = rule.scope) {
    is ThinkingRule.Scope.AllModels -> "All models"
    is ThinkingRule.Scope.ModelPattern -> s.pattern
}

fun wireFormatSummary(fmt: ThinkingWireFormat?): String = when (fmt) {
    null -> "no opinion"
    is ThinkingWireFormat.OmitEverything -> "send nothing"
    is ThinkingWireFormat.ReasoningEffort -> "reasoning_effort" + (fmt.offValue?.let { " · off = $it" } ?: "")
    is ThinkingWireFormat.ReasoningEffortNested -> "reasoning.effort" + (fmt.offValue?.let { " · off = $it" } ?: "")
    is ThinkingWireFormat.DeepSeekSibling -> "thinking + reasoning_effort"
    is ThinkingWireFormat.QwenDual -> "enable_thinking + budget"
    is ThinkingWireFormat.AnthropicThinking -> "anthropic thinking"
    is ThinkingWireFormat.GeminiBudget -> "thinkingBudget · floor ${fmt.floor}"
    is ThinkingWireFormat.GeminiThinkingLevel -> "thinkingLevel"
    is ThinkingWireFormat.BooleanToggle -> "boolean toggle · ${fmt.path}"
    is ThinkingWireFormat.ExtraBodyToggle -> "extra_body toggle · ${fmt.path}"
    is ThinkingWireFormat.CustomPath -> "custom path · ${fmt.path}"
}

/** "sample-model → rule #N "label"" — the resolution trace shown under the section. */
fun resolveHitDescription(
    custom: List<ThinkingRule>,
    builtIns: List<ThinkingRule>,
    sampleModelId: String,
): String? {
    val all = custom + builtIns
    val idx = all.indexOfFirst { it.scope.matches(sampleModelId) }
    if (idx < 0) return null
    val rule = all[idx]
    return "$sampleModelId → rule #${idx + 1} “${rule.label}”"
}
