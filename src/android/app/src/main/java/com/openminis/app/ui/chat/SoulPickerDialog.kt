package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.SoulProfiles
import com.openminis.app.ui.components.SoulAvatarGlyph
import java.util.Locale

/**
 * [T-multi-soul] The chat's SOUL picker.
 *
 * The selection belongs to ONE conversation: it is written to
 * [SoulProfiles.select] under the session id, so switching persona in this chat
 * leaves every other chat alone. The prompt body, the assistant name and the
 * assistant icon all read the same selection at the next turn — nothing here
 * rewrites history, and the note at the bottom says so rather than letting the
 * user wonder why the last reply still came from the previous SOUL.
 *
 * Copy is chosen from the device locale instead of a new string resource per
 * locale: adding keys would make every `values-*` file incomplete until each
 * translation lands, and this dialog ships inside one already-reviewed change.
 */
@Composable
internal fun SoulPickerDialog(
    profiles: List<SoulProfiles.SoulProfile>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val zh = Locale.getDefault().language.startsWith("zh")
    val sparkleGradient = Brush.linearGradient(listOf(SparkleColor1, SparkleColor2))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (zh) "选择 SOUL" else "Choose SOUL",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (zh) {
                        "仅对本对话生效，从下一条消息开始"
                    } else {
                        "Applies to this chat only, from the next message"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            if (profiles.isEmpty()) {
                Text(
                    text = if (zh) "还没有 SOUL，请在设置中创建" else "No SOUL yet — create one in Settings",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        val name = profile.file.metadata.name
                            .trim()
                            .ifEmpty { profile.id }
                        val selected = profile.id == selectedId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surface,
                                )
                                .clickable { onSelect(profile.id) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SoulAvatarGlyph(
                                icon = profile.file.metadata.icon,
                                sizeDp = 26.dp,
                                emojiSp = 20.sp,
                                sparkleTint = sparkleGradient,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}