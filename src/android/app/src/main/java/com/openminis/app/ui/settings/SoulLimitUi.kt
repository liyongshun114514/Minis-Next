package com.openminis.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.agent.SoulBodyLimitMode
import com.openminis.app.agent.SoulBodyLimitSettings
import com.openminis.app.agent.SoulStore

/**
 * [T-native-composer-soul-limit] UI-only controls for the SOUL.md body
 * length gate.
 *
 * This screen controls the LENGTH policy only. It never edits prompt
 * construction, injection patterns, or instruction priority; those stay
 * in `SystemPromptBuilder` unchanged. Changing the mode is persisted
 * immediately by the caller via [com.openminis.app.agent.SoulBodyLimitPolicy].
 *
 * Labels are inline English because `res/values/strings.xml` is outside
 * this port's file boundary (another owner is editing it). Moving these
 * four labels into resources is a follow-up, not a behavior change.
 */
@Composable
internal fun SoulBodyLimitControls(
    settings: SoulBodyLimitSettings,
    onChange: (SoulBodyLimitSettings) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            text = "Application-side body length gate",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Prompt construction, injection checks and instruction priority are native and are NOT changed by this setting.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoulLimitModeChip(
                label = "Native caps",
                selected = settings.mode == SoulBodyLimitMode.NATIVE,
                onClick = { onChange(settings.copy(mode = SoulBodyLimitMode.NATIVE)) },
            )
            SoulLimitModeChip(
                label = "Unlimited ∞",
                selected = settings.mode == SoulBodyLimitMode.UNLIMITED,
                onClick = { onChange(settings.copy(mode = SoulBodyLimitMode.UNLIMITED)) },
            )
            SoulLimitModeChip(
                label = "Custom",
                selected = settings.mode == SoulBodyLimitMode.CUSTOM,
                onClick = { onChange(settings.copy(mode = SoulBodyLimitMode.CUSTOM)) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (settings.mode) {
                SoulBodyLimitMode.NATIVE ->
                    "Native: Chinese ≤ ${SoulStore.CHINESE_CHAR_LIMIT} chars or English ≤ ${SoulStore.ENGLISH_WORD_LIMIT} words, picked by the majority language."
                SoulBodyLimitMode.UNLIMITED ->
                    "Unlimited: the application no longer rejects a long body and the editor counter shows / ∞. Provider context limits and fees still apply."
                SoulBodyLimitMode.CUSTOM ->
                    "Custom: both caps below are enforced; the majority language selects which one applies."
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (settings.mode == SoulBodyLimitMode.CUSTOM) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SoulLimitNumberField(
                    value = settings.chineseCharLimit,
                    label = "Chinese chars",
                    modifier = Modifier.weight(1f),
                    onChange = { onChange(settings.copy(chineseCharLimit = it)) },
                )
                SoulLimitNumberField(
                    value = settings.englishWordLimit,
                    label = "English words",
                    modifier = Modifier.weight(1f),
                    onChange = { onChange(settings.copy(englishWordLimit = it)) },
                )
            }
        }
    }
}

@Composable
private fun SoulLimitModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun SoulLimitNumberField(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(7)
            text = digits
            digits.toIntOrNull()?.let { onChange(it) }
        },
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
