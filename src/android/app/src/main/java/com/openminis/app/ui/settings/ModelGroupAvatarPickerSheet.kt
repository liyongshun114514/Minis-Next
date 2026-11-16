package com.openminis.app.ui.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.groups.ModelGroupAvatarImage
import com.openminis.app.groups.ModelGroupAvatarResolver
import com.openminis.app.groups.ModelGroupAvatarSource
import com.openminis.app.groups.ModelGroupAvatarSpec
import com.openminis.app.groups.ModelGroupAvatarValue
import com.openminis.app.groups.ModelIconCatalogEntry
import com.openminis.app.ui.components.ModelGroupAvatar
import com.openminis.app.ui.components.ModelGroupAvatarGlyph
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.ui.components.rememberModelIconCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-model-group-avatar] The group-icon chooser.
 *
 * Three sections, in the resolver's own precedence order:
 *
 *  1. **Automatic** — shows what the current name matches (or "generic
 *     icon"). Selecting it CLEARS the stored override, so the icon keeps
 *     following renames.
 *  2. **Custom picture** — the photo picker; the picture is normalized to a
 *     bounded PNG data URI and stored inline.
 *  3. **Brand icons** — the bundled catalog, filtered by a text field.
 *
 * The sheet itself owns no persistence: [onSelect] receives the new stored
 * value (null = automatic) and the caller writes it through
 * `ProviderRepository.updateGroup`, so every presentation point refreshes from
 * the same config flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelGroupAvatarPickerSheet(
    group: ModelGroup,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val catalog = rememberModelIconCatalog()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val manual = remember(group.avatar) { ModelGroupAvatarValue.parse(group.avatar) }
    val auto = remember(group.name, catalog) { ModelGroupAvatarResolver.resolve(group.name, null, catalog) }

    var query by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var importFailed by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            importFailed = false
            val value = withContext(Dispatchers.IO) {
                runCatching { ModelGroupAvatarImage.import(context, uri) }.getOrNull()
            }
            importing = false
            if (value != null) {
                onSelect(value)
                onDismiss()
            } else {
                importFailed = true
            }
        }
    }

    val filtered = remember(query, catalog) {
        val q = query.trim()
        if (q.isEmpty()) catalog.entries
        else catalog.entries.filter { entry ->
            entry.id.contains(q, ignoreCase = true) ||
                entry.name.contains(q, ignoreCase = true) ||
                entry.aliases.any { it.contains(q, ignoreCase = true) }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.group_avatar_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
            SectionTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.group_avatar_search_hint),
                modifier = Modifier.padding(horizontal = 20.dp),
                containerColor = SectionDesign.screenBackgroundColor(),
                contentHorizontalPadding = 16.dp,
            )
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                item(key = "automatic") {
                    PickerOptionRow(
                        selected = manual == null,
                        title = stringResource(R.string.group_avatar_automatic),
                        subtitle = when {
                            auto.spec is ModelGroupAvatarSpec.Builtin &&
                                auto.source == ModelGroupAvatarSource.AUTO ->
                                stringResource(
                                    R.string.group_avatar_matches,
                                    (auto.spec as ModelGroupAvatarSpec.Builtin).entry.name,
                                )
                            else -> stringResource(R.string.group_avatar_no_match)
                        },
                        leading = {
                            ModelGroupAvatarGlyph(resolved = auto, size = 32.dp)
                        },
                        onClick = {
                            onSelect(null)
                            onDismiss()
                        },
                    )
                }

                item(key = "custom") {
                    val isImage = manual is ModelGroupAvatarValue.Manual.Image
                    PickerOptionRow(
                        selected = isImage,
                        title = if (isImage) {
                            stringResource(R.string.group_avatar_kind_custom_image)
                        } else {
                            stringResource(R.string.group_avatar_choose_picture)
                        },
                        subtitle = when {
                            importing -> stringResource(R.string.group_avatar_preparing)
                            importFailed -> stringResource(R.string.group_avatar_import_failed)
                            isImage -> stringResource(R.string.group_avatar_tap_to_replace)
                            else -> stringResource(R.string.group_avatar_from_photo_library)
                        },
                        leading = {
                            if (isImage) {
                                ModelGroupAvatar(
                                    name = group.name,
                                    storedAvatar = group.avatar,
                                    size = 32.dp,
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (importing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.AddPhotoAlternate,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        },
                        enabled = !importing,
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    )
                }

                item(key = "brand_header") {
                    Text(
                        text = stringResource(R.string.group_avatar_brand_icons),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }

                if (filtered.isEmpty()) {
                    item(key = "brand_empty") {
                        Text(
                            text = if (catalog.entries.isEmpty()) {
                                stringResource(R.string.group_avatar_catalog_missing)
                            } else {
                                stringResource(R.string.group_avatar_search_empty)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    items(
                        items = filtered.chunked(4),
                        key = { row -> "row:${row.first().id}" },
                    ) { rowEntries ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            rowEntries.forEach { entry ->
                                BrandIconTile(
                                    entry = entry,
                                    selected = (manual as? ModelGroupAvatarValue.Manual.Builtin)?.id == entry.id,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        onSelect(ModelGroupAvatarValue.builtinValue(entry.id))
                                        onDismiss()
                                    },
                                )
                            }
                            // Keep the last row's tiles the same width as a
                            // full row's instead of stretching two entries.
                            repeat(4 - rowEntries.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerOptionRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun BrandIconTile(
    entry: ModelIconCatalogEntry,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ModelGroupAvatarGlyph(
            resolved = com.openminis.app.groups.ResolvedModelGroupAvatar(
                spec = ModelGroupAvatarSpec.Builtin(entry),
                source = ModelGroupAvatarSource.MANUAL,
            ),
            size = 40.dp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = entry.name,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}