@file:OptIn(ExperimentalMaterial3Api::class)

package com.openminis.app.intelligentui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * One field of a form/calculator component, decoded once from the protocol so that
 * rendering, defaults and validation all agree on the same interpretation.
 */
data class UiFieldSpec(
    val id: String,
    val label: String,
    val kind: String,
    val initial: String,
    val min: Double?,
    val max: Double?,
    val step: Double?,
    val required: Boolean,
    val options: List<String>,
    val disabled: Boolean,
    val serverError: String?,
    val placeholder: String,
)

/**
 * Pure, Compose-free interpretation and validation of the `fields` protocol.
 * Exposed so [IntelligentUiParser] (and any future parser revision) can reuse exactly
 * the same rules instead of growing a second, divergent validator.
 */
object UiFieldValidation {
    const val MAX_TEXT = 2000
    const val MAX_ABS = 1e12
    const val DEFAULT_MIN = 0.0
    const val DEFAULT_MAX = 100.0
    const val DATE_PATTERN = "yyyy-MM-dd"

    val kinds = setOf(
        "text", "textarea", "search", "number", "slider", "toggle", "select",
        "radio", "checkbox", "multiselect", "segmented", "date",
    )
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateShape = Regex("\\d{4}-\\d{2}-\\d{2}")

    fun isSupportedKind(kind: String): Boolean = kind in kinds

    /** Unknown kinds degrade to `text` instead of crashing a live conversation. */
    fun parse(field: JSONObject): UiFieldSpec {
        val rawKind = field.optString("kind", "text")
        val kind = if (isSupportedKind(rawKind)) rawKind else "text"
        val options = mutableListOf<String>()
        field.optJSONArray("options")?.let { array ->
            for (i in 0 until array.length()) {
                val option = array.opt(i)
                if (option is String) options.add(option)
            }
        }
        return UiFieldSpec(
            id = field.optString("id", "field"),
            label = field.optString("label", field.optString("id", "Field")),
            kind = kind,
            initial = initialValue(field, kind),
            min = doubleOrNull(field, "min"),
            max = doubleOrNull(field, "max"),
            step = doubleOrNull(field, "step"),
            required = field.opt("required") is Boolean && field.optBoolean("required"),
            options = options,
            disabled = field.opt("disabled") is Boolean && field.optBoolean("disabled"),
            serverError = field.optString("error").takeIf { it.isNotBlank() },
            placeholder = field.optString("placeholder"),
        )
    }

    fun parseAll(fields: JSONArray?): List<UiFieldSpec> =
        (0 until (fields?.length() ?: 0)).mapNotNull { index ->
            fields?.optJSONObject(index)?.let { parse(it) }
        }

    private fun initialValue(field: JSONObject, kind: String): String = when (kind) {
        "number" -> {
            val value = field.opt("value")
            if (value is Number) numberText(value.toDouble()) else ""
        }
        "slider" -> {
            val lo = doubleOrNull(field, "min") ?: DEFAULT_MIN
            val hi = doubleOrNull(field, "max") ?: DEFAULT_MAX
            val value = field.opt("value")
            if (value is Number) numberText(value.toDouble().coerceIn(lo, hi)) else numberText(lo)
        }
        "toggle", "checkbox" -> {
            val value = field.opt("value")
            (value is Boolean && value).toString()
        }
        "multiselect" -> {
            when (val value = field.opt("value")) {
                is JSONArray -> encodeList((0 until value.length()).mapNotNull { i -> value.opt(i) as? String })
                is String -> decodeList(value)?.let(::encodeList) ?: encodeList(emptyList())
                else -> encodeList(emptyList())
            }
        }
        else -> when (val value = field.opt("value")) {
            null, JSONObject.NULL -> ""
            is String -> value
            else -> value.toString()
        }
    }

    private fun doubleOrNull(field: JSONObject, key: String): Double? =
        (field.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

    /** Multiselect keeps its transport shape: a JSON array string, so followUp values stay strings. */
    fun encodeList(values: Collection<String>): String = JSONArray(values.toList()).toString()

    fun decodeList(raw: String): List<String>? = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val item = array.opt(i)
            if (item is String) item else return null
        }
    }.getOrNull()

    fun number(raw: String): Double? = raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    /** Deterministic, locale-independent rendering (no comma decimal separators). */
    fun numberText(value: Double): String {
        if (!value.isFinite()) return ""
        if (value == kotlin.math.floor(value) && abs(value) < 1e15) return value.toLong().toString()
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
    }

    fun dateOrNull(raw: String): LocalDate? = raw.takeIf { dateShape.matches(it) }
        ?.let { runCatching { LocalDate.parse(it, dateFormatter) }.getOrNull() }

    /** `null` means valid; otherwise a human-readable message shown under the field. */
    fun validate(spec: UiFieldSpec, raw: String): String? = when (spec.kind) {
        "toggle", "checkbox" ->
            if (spec.required && raw != "true") "${spec.label} is required" else null
        "number", "slider" -> {
            val value = number(raw) ?: return "${spec.label} needs a valid number"
            if (abs(value) > MAX_ABS) "${spec.label} is outside the supported range"
            else if (spec.min != null && value < spec.min) "${spec.label} must be at least ${numberText(spec.min)}"
            else if (spec.max != null && value > spec.max) "${spec.label} must be at most ${numberText(spec.max)}"
            else null
        }
        "date" -> when {
            raw.isBlank() -> if (spec.required) "${spec.label} is required" else null
            dateOrNull(raw) == null -> "${spec.label} must be a date (YYYY-MM-DD)"
            else -> null
        }
        "select", "radio", "segmented" -> when {
            raw.isBlank() -> if (spec.required) "${spec.label} is required" else null
            spec.options.isNotEmpty() && raw !in spec.options -> "${spec.label} has an unsupported choice"
            else -> null
        }
        "multiselect" -> {
            val selected = decodeList(raw) ?: return "${spec.label} has an invalid selection"
            when {
                spec.required && selected.isEmpty() -> "${spec.label} needs at least one selection"
                spec.options.isNotEmpty() && selected.any { it !in spec.options } ->
                    "${spec.label} has an unsupported choice"
                else -> null
            }
        }
        else -> when {
            raw.length > MAX_TEXT -> "${spec.label} is too long"
            spec.required && raw.isBlank() -> "${spec.label} is required"
            else -> null
        }
    }

    fun validate(field: JSONObject, raw: String): String? = validate(parse(field), raw)

    /** Exactly the strings handed to [IntelligentUiParser.followUp]; never re-encoded objects. */
    fun collect(specs: List<UiFieldSpec>, values: List<String>): Map<String, String> =
        specs.mapIndexed { index, spec ->
            val raw = values.getOrNull(index) ?: spec.initial
            spec.id to when (spec.kind) {
                "number", "slider" -> number(raw)?.let(::numberText) ?: raw.trim()
                "toggle", "checkbox" -> (raw == "true").toString()
                "multiselect" -> decodeList(raw)?.let(::encodeList) ?: encodeList(emptyList())
                else -> raw
            }
        }.toMap()
}

/**
 * Advanced native form/calculator for one `native_ui` node. Replaces the legacy
 * [UiFields] renderer: every control is a real Material component, drafts survive
 * rotation and busy conversations, and nothing is ever sent without an explicit tap.
 */
@Composable
internal fun UiAdvancedFields(
    title: String,
    n: JSONObject,
    interactive: Boolean,
    onFollowUp: (String) -> Unit,
) {
    val nodeKey = n.toString()
    val specs = remember(nodeKey) { UiFieldValidation.parseAll(n.optJSONArray("fields")) }
    val defaults = remember(nodeKey) { ArrayList(specs.map { it.initial }) }
    val config = remember(nodeKey) { n.optJSONObject("form") ?: n }
    val isCalculator = n.optString("type") == "calculator"

    var values by rememberSaveable(nodeKey) { mutableStateOf(ArrayList(defaults)) }
    var touched by rememberSaveable(nodeKey) { mutableStateOf(ArrayList<String>()) }
    var submitAttempted by rememberSaveable(nodeKey) { mutableStateOf(false) }
    var cancelled by rememberSaveable(nodeKey) { mutableStateOf(false) }
    var openDateId by rememberSaveable(nodeKey) { mutableStateOf<String?>(null) }

    fun valueAt(index: Int): String = values.getOrNull(index) ?: specs[index].initial

    fun setValue(index: Int, raw: String) {
        if (index !in specs.indices) return
        val next = ArrayList(values)
        while (next.size <= index) next.add(specs[next.size].initial)
        next[index] = raw
        values = next
    }

    fun markTouched(id: String) {
        if (id !in touched) touched = ArrayList(touched).apply { add(id) }
    }

    fun resetAll() {
        values = ArrayList(defaults)
        touched = ArrayList()
        submitAttempted = false
        cancelled = false
        openDateId = null
    }

    val errors: Map<String, String?> = specs.mapIndexed { index, spec ->
        spec.id to (UiFieldValidation.validate(spec, valueAt(index)) ?: spec.serverError?.takeIf { spec.id !in touched })
    }.toMap()
    val hasErrors = errors.values.any { it != null }

    val followUpPrompt = config.optString("followUp").ifBlank { n.optString("followUp") }
    val componentForFollowUp = remember(nodeKey, followUpPrompt) {
        if (n.has("followUp") || followUpPrompt.isBlank()) n
        else JSONObject(n.toString()).put("followUp", followUpPrompt)
    }

    fun submit() {
        submitAttempted = true
        if (!interactive || cancelled || hasErrors) return
        val collected = UiFieldValidation.collect(specs, specs.indices.map { valueAt(it) })
        onFollowUp(IntelligentUiParser.followUp(title, componentForFollowUp, collected))
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (cancelled) {
            Text(
                "Form cancelled. Nothing was sent.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { cancelled = false },
                    modifier = Modifier.testTag("ui_resume"),
                ) { Text("Continue editing") }
                OutlinedButton(
                    onClick = { resetAll() },
                    modifier = Modifier.testTag("ui_reset"),
                ) { Text(config.optString("resetLabel").ifBlank { "Reset" }) }
            }
        } else {
            specs.forEachIndexed { index, spec ->
                key(spec.id) {
                    val localError = errors[spec.id]
                    val showError = submitAttempted || spec.id in touched
                    UiFieldEditor(
                        spec = spec,
                        value = valueAt(index),
                        error = if (showError || spec.serverError != null) localError else null,
                        onValueChange = { raw ->
                            setValue(index, raw)
                            markTouched(spec.id)
                        },
                        onOpenDate = { openDateId = spec.id },
                    )
                }
            }
            if (isCalculator) {
                CalculatorResults(n = n, specs = specs, values = specs.indices.map { valueAt(it) }, valid = !hasErrors)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isCalculator || followUpPrompt.isNotBlank()) {
                    Button(
                        onClick = { submit() },
                        enabled = interactive,
                        modifier = Modifier.testTag("ui_submit"),
                    ) { Text(config.optString("submitLabel").ifBlank { "Send" }) }
                }
                OutlinedButton(
                    onClick = { resetAll() },
                    modifier = Modifier.testTag("ui_reset"),
                ) { Text(config.optString("resetLabel").ifBlank { "Reset" }) }
                if (!isCalculator) {
                    TextButton(
                        onClick = { cancelled = true },
                        modifier = Modifier.testTag("ui_cancel"),
                    ) { Text(config.optString("cancelLabel").ifBlank { "Cancel" }) }
                }
            }
            if (!interactive) {
                Text(
                    "Sending is paused while the assistant is busy. Your edits are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    val dateIndex = specs.indexOfFirst { it.id == openDateId }
    if (openDateId != null && dateIndex >= 0) {
        val spec = specs[dateIndex]
        val initialMillis = remember(openDateId, valueAt(dateIndex)) {
            UiFieldValidation.dateOrNull(valueAt(dateIndex))?.toEpochDay()?.times(86_400_000L)
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { openDateId = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        setValue(dateIndex, LocalDate.ofEpochDay(millis / 86_400_000L).toString())
                        markTouched(spec.id)
                    }
                    openDateId = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { openDateId = null }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }
}

@Composable
private fun UiFieldEditor(
    spec: UiFieldSpec,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onOpenDate: () -> Unit,
) {
    val editable = !spec.disabled
    val tag = Modifier.testTag("field_${spec.id}")
    when (spec.kind) {
        "toggle" -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = value == "true",
                        enabled = editable,
                        role = Role.Switch,
                        onValueChange = { onValueChange(it.toString()) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(spec.label, Modifier.weight(1f))
                    Switch(
                        checked = value == "true",
                        onCheckedChange = null,
                        enabled = editable,
                        modifier = tag,
                    )
                }
                UiFieldError(error)
            }
        }
        "checkbox" -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = value == "true",
                        enabled = editable,
                        role = Role.Checkbox,
                        onValueChange = { onValueChange(it.toString()) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = value == "true",
                        onCheckedChange = null,
                        enabled = editable,
                        modifier = tag,
                    )
                    Text(spec.label, Modifier.padding(start = 8.dp))
                }
                UiFieldError(error)
            }
        }
        "select" -> SelectField(spec, value, error, editable, tag, onValueChange)
        "radio" -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spec.label, style = MaterialTheme.typography.bodyMedium)
                spec.options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = value == option,
                            enabled = editable,
                            role = Role.RadioButton,
                            onClick = { onValueChange(option) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = value == option,
                            onClick = null,
                            enabled = editable,
                            modifier = Modifier.testTag("field_${spec.id}_$option"),
                        )
                        Text(option, Modifier.padding(start = 8.dp))
                    }
                }
                UiFieldError(error)
            }
        }
        "multiselect" -> {
            val selected = UiFieldValidation.decodeList(value)?.toSet() ?: emptySet()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spec.label, style = MaterialTheme.typography.bodyMedium)
                spec.options.forEach { option ->
                    val checked = option in selected
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = checked,
                            enabled = editable,
                            role = Role.Checkbox,
                            onValueChange = { yes ->
                                val next = if (yes) selected + option else selected - option
                                onValueChange(
                                    UiFieldValidation.encodeList(spec.options.filter { it in next }),
                                )
                            },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            enabled = editable,
                            modifier = Modifier.testTag("field_${spec.id}_$option"),
                        )
                        Text(option, Modifier.padding(start = 8.dp))
                    }
                }
                UiFieldError(error)
            }
        }
        "segmented" -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(spec.label, style = MaterialTheme.typography.bodyMedium)
                if (spec.options.isNotEmpty()) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        spec.options.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = value == option,
                                onClick = { onValueChange(option) },
                                shape = SegmentedButtonDefaults.itemShape(index, spec.options.size),
                                enabled = editable,
                                modifier = Modifier.testTag("field_${spec.id}_$option"),
                            ) { Text(option) }
                        }
                    }
                }
                UiFieldError(error)
            }
        }
        "slider" -> {
            val lo = spec.min ?: UiFieldValidation.DEFAULT_MIN
            val hi = spec.max ?: UiFieldValidation.DEFAULT_MAX
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(spec.label)
                    Text(value.ifBlank { UiFieldValidation.numberText(lo) })
                }
                if (hi > lo) {
                    val current = (UiFieldValidation.number(value) ?: lo).coerceIn(lo, hi).toFloat()
                    Slider(
                        value = current,
                        onValueChange = { raw ->
                            val candidate = raw.toDouble()
                            val stepped = if (spec.step != null && spec.step > 0) {
                                lo + kotlin.math.round((candidate - lo) / spec.step) * spec.step
                            } else candidate
                            onValueChange(UiFieldValidation.numberText(stepped.coerceIn(lo, hi)))
                        },
                        valueRange = lo.toFloat()..hi.toFloat(),
                        enabled = editable,
                        modifier = tag,
                    )
                } else {
                    UiFieldError("${spec.label} has an invalid range")
                }
                UiFieldError(error)
            }
        }
        "date" -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spec.label, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = onOpenDate,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth().then(tag),
                ) {
                    Icon(Icons.Filled.DateRange, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(value.ifBlank { spec.placeholder.ifBlank { "YYYY-MM-DD" } })
                }
                UiFieldError(error)
            }
        }
        else -> {
            OutlinedTextField(
                value = value,
                onValueChange = { raw ->
                    if (raw.length <= UiFieldValidation.MAX_TEXT) onValueChange(raw)
                },
                label = { Text(spec.label) },
                placeholder = if (spec.placeholder.isNotBlank()) {
                    { Text(spec.placeholder) }
                } else null,
                singleLine = spec.kind != "textarea",
                minLines = if (spec.kind == "textarea") 3 else 1,
                enabled = editable,
                isError = error != null,
                supportingText = if (error != null) {
                    { Text(error) }
                } else null,
                leadingIcon = if (spec.kind == "search") {
                    { Icon(Icons.Filled.Search, contentDescription = null) }
                } else null,
                keyboardOptions = if (spec.kind == "number") {
                    KeyboardOptions(keyboardType = KeyboardType.Decimal)
                } else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().then(tag),
            )
        }
    }
}

@Composable
private fun SelectField(
    spec: UiFieldSpec,
    value: String,
    error: String?,
    editable: Boolean,
    tag: Modifier,
    onValueChange: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(spec.label, style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = editable,
                modifier = Modifier.fillMaxWidth().then(tag),
            ) {
                Text(
                    value.ifBlank { spec.placeholder.ifBlank { "Choose" } },
                    Modifier.weight(1f),
                )
                Text("▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                spec.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        },
                        enabled = editable,
                        modifier = Modifier.testTag("field_${spec.id}_option_$option"),
                    )
                }
            }
        }
        UiFieldError(error)
    }
}

@Composable
private fun UiFieldError(error: String?) {
    if (error != null) {
        Text(
            error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CalculatorResults(
    n: JSONObject,
    specs: List<UiFieldSpec>,
    values: List<String>,
    valid: Boolean,
) {
    val variables = specs.mapIndexedNotNull { index, spec ->
        if (spec.kind != "number" && spec.kind != "slider") null
        else UiFieldValidation.number(values[index])?.let { spec.id to it }
    }.toMap()
    val primaryExpression = n.optString("expression")
    val outputs = n.optJSONArray("outputs")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!valid) {
            Text(
                "Cannot compute until every input is valid.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("ui_result_error"),
            )
        }
        if (primaryExpression.isNotBlank()) {
            val outcome = if (!valid) null else runCatching { UiExpression.evaluate(primaryExpression, variables) }
            if (outcome == null) {
                if (valid) Text(
                    "Cannot compute result.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("ui_result_error"),
                )
            } else outcome.fold(
                onSuccess = { value ->
                    Text(
                        "${UiFieldValidation.numberText(value)}${unitSuffix(n.optString("unit"))}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("ui_result"),
                    )
                },
                onFailure = { failure ->
                    Text(
                        "Cannot compute result: ${failure.message ?: "invalid expression"}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("ui_result_error"),
                    )
                },
            )
        }
        repeat(outputs?.length() ?: 0) { index ->
            val output = outputs?.optJSONObject(index) ?: return@repeat
            val expression = output.optString("expression")
            if (expression.isBlank()) return@repeat
            val label = output.optString("label").ifBlank { "Output ${index + 1}" }
            val outcome = if (!valid) null else runCatching { UiExpression.evaluate(expression, variables) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                if (!valid || outcome == null) {
                    Text(
                        "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (outcome.isSuccess) {
                    Text(
                        "${UiFieldValidation.numberText(outcome.getOrThrow())}${unitSuffix(output.optString("unit"))}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("ui_output_${index}"),
                    )
                } else {
                    Text(
                        "Cannot compute $label: ${outcome.exceptionOrNull()?.message ?: "invalid expression"}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("ui_output_${index}"),
                    )
                }
            }
        }
    }
}

private fun unitSuffix(unit: String): String = if (unit.isBlank()) "" else " $unit"
