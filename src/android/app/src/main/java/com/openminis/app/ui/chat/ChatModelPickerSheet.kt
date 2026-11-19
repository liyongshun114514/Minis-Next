package com.openminis.app.ui.chat

import top.yukonga.miuix.kmp.theme.MiuixTheme
// [T-android-split-chat] ModelPickerSheet (+ its private helpers fuzzyMatch /
// providerDotColor) extracted verbatim from ChatScreen.kt. Full import block
// copied from ChatScreen.kt (unused imports are warnings, not errors);
// ModelPickerSheet flipped private->internal so ChatScreen can call it.

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.Article
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.openminis.app.ui.components.FitOrWrapLine
import com.openminis.app.ui.components.ModelActiveBadge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MyLocation
import com.openminis.app.ui.components.miuix.DropdownMenu
import com.openminis.app.ui.components.miuix.DropdownMenuItem
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Surface
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.openminis.app.ui.components.rememberSheetScrollGuard
import androidx.compose.ui.geometry.Offset
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.offload.OffloadPermissionManager
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.theme.ChatColors
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.ui.unit.DpSize
import com.openminis.app.ui.components.MinisBottomSheet
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.FloatingActionButton

private val ModelPickerGutter = 12.dp
private val ModelPickerCardRadius = 18.dp

@Composable
internal fun ModelPickerSheet(
    show: Boolean,
    groups: List<ModelGroup>,
    selectedGroupId: String?,
    activeEntryId: String?,
    defaultPrimaryGroupId: String?,
    config: ProviderConfig,
    providerRepository: ProviderRepository,
    onSelectGroup: (String) -> Unit,
    onSelectGroupEntry: (String, String) -> Unit,
    onSelectEntry: (String) -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    /** [T-android-modelpicker-group-edit] "Edit" affordance on the Model Groups
     *  section header — dismisses the sheet and navigates to the Model Groups
     *  management screen. Null hides the button (callers without a route). */
    onEditGroups: (() -> Unit)? = null,
    /** [T-android-picker-provider-edit] Edit button on each provider section
     *  header — opens that provider instance's settings. Null hides it. */
    onEditProvider: ((instanceId: String) -> Unit)? = null,
) {
    val openTime = remember { System.nanoTime() }

    LaunchedEffect(Unit) {
        AppLogger.info("ModelPicker", "[ModelPicker] open triggered")
        withFrameNanos { frameTime ->
            val ms = (frameTime - openTime) / 1_000_000.0
            AppLogger.info("ModelPicker", "[ModelPicker] first frame rendered: ${"%.1f".format(ms)}ms (total since trigger)")
        }
    }

    var searchText by remember { mutableStateOf("") }
    var expandedGroupIds by remember { mutableStateOf(setOf<String>()) }
    // Note: the non-text-output "may not work as an Agent" confirmation lives
    // in ChatScreen's callback wrappers (ee828dba), NOT here — the sheet stays
    // a dumb list and the caller owns selection policy.
    val allInstanceIds = remember(config) {
        config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    }
    var collapsedInstanceIds by remember(allInstanceIds) {
        mutableStateOf(allInstanceIds)
    }

    /**
     * [T-android-model-picker-polish] Model whose Quick Test sheet is open.
     *
     * Reuses the same [com.openminis.app.ui.components.QuickTestSheet] the
     * provider settings screens already use, as iOS reuses ModelQuickTestSheet
     * from its picker — a model that behaves one way when tested from Settings
     * and another from the picker would be worse than no button at all.
     */
    var quickTestEntry by remember { mutableStateOf<ModelEntry?>(null) }
    // [T-quota-badge-ui] Provider whose quota sheet is open (header pill or a
    // Model Group member pill).
    var quotaSheetInstanceId by remember { mutableStateOf<String?>(null) }

    // [T-picker-search-debounce] Filter on the DEBOUNCED, normalized query,
    // not on every keystroke (iOS GH#271). `searchText` still drives the field
    // and the empty-state icon; only the search itself trails it.
    val query = com.openminis.app.ui.components.rememberDebouncedQuery(searchText)

    // Filtered groups — cheap (a handful of groups), stays in composition.
    val filteredGroups = remember(groups, query) {
        if (query.isEmpty()) groups
        else groups.filter { com.openminis.app.ui.components.ModelSearch.score(it.name, query) > 0 }
    }

    // [T-picker-search-cap] Provider sections built ONCE per config: one pass
    // grouping entries by instance. This used to re-filter the whole entry
    // list once per provider on every keystroke, in composition, and log a
    // line per provider each time.
    val baseSections = remember(config) {
        val byInstance = config.modelEntries.asSequence()
            .filter { !it.isHidden }
            .groupBy { it.providerInstanceId }
        config.instances
            .filter { it.isEnabled }
            .map { it to (byInstance[it.id] ?: emptyList()) }
            .filter { it.second.isNotEmpty() }
    }
    fun sectionLabel(instance: com.openminis.app.data.model.ProviderInstance) =
        instance.label.ifEmpty { instance.providerType.displayName }

    // [T-picker-search-relevance] Rank + cap off the main thread: with 7,000+
    // models a search is thousands of string scans. The last result stays on
    // screen until the new one lands, so a keystroke never flashes the
    // unfiltered list.
    var searchResult by remember {
        mutableStateOf<com.openminis.app.ui.components.ModelSearch.Result<com.openminis.app.data.model.ProviderInstance>?>(null)
    }
    LaunchedEffect(baseSections, query) {
        if (query.isEmpty()) {
            searchResult = null
            return@LaunchedEffect
        }
        val t0 = System.nanoTime()
        val r = withContext(Dispatchers.Default) {
            com.openminis.app.ui.components.ModelSearch.search(baseSections, ::sectionLabel, query)
        }
        searchResult = r
        AppLogger.info(
            "ModelPicker",
            "[ModelPicker] search len=${query.length} matches=${r.totalMatches} shown=${r.shown} " +
                "${"%.1f".format((System.nanoTime() - t0) / 1_000_000.0)}ms",
        )
    }
    val allInstancesWithEntries: List<Pair<com.openminis.app.data.model.ProviderInstance, List<ModelEntry>>> =
        if (query.isEmpty()) baseSections
        else searchResult?.sections?.map { it.key to it.entries } ?: baseSections
    val truncatedSearch = searchResult?.takeIf { query.isNotEmpty() && it.truncated }

    val colors = MiuixTheme.colorScheme
    val sheetDensity = LocalDensity.current
    val pickerContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                // The Miuix sheet owns the top safe bound and IME avoidance.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .pointerInput(show) {
                    if (!show) awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                                .changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ModelPickerGutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.model_picker_title),
                    style = MiuixTheme.textStyles.title4,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
                )
                TextButton(
                    onClick = onDismiss,
                    enabled = show,
                    text = stringResource(R.string.model_picker_done),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }

            TextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = ModelPickerGutter, vertical = 8.dp),
                label = stringResource(R.string.model_picker_search_placeholder),
                enabled = show,
                useLabelAsPlaceholder = true,
                singleLine = true,
                insideMargin = DpSize(14.dp, 10.dp),
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = colors.surfaceContainerHigh,
                    labelColor = colors.onSurfaceContainerVariant,
                ),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { searchText = "" }) {
                            Icon(Icons.Default.Close, stringResource(R.string.model_picker_search_clear), modifier = Modifier.size(20.dp))
                        }
                    }
                },
            )

            // [T-android-picker-scroll-guard] Swallow fling momentum at the top
            // of the list so it cannot dismiss the sheet, while leaving a
            // deliberate finger drag free to close it.
            val pickerScrollGuard = rememberSheetScrollGuard()

            // [FEAT-261025-02] Where the current selection sits in the list, as
            // (item index, offset in the item): the selected group's row inside
            // the one Model Groups card item, else the active model's row - or
            // its provider's card while that is collapsed. Mirrors the item
            // order emitted below. Null when the selection is not listed (a
            // search that filters it out).
            val pickerListState = rememberLazyListState()
            val pickerScope = rememberCoroutineScope()
            var selectedGroupRowTop by remember { mutableStateOf(0) }
            var selectedGroupRowOnScreen by remember { mutableStateOf(false) }
            val pickerJumpTarget: Pair<Int, Int>? = run {
                var index = 0
                if (filteredGroups.isNotEmpty()) {
                    if (selectedGroupId != null) {
                        return@run if (filteredGroups.any { it.id == selectedGroupId }) 1 to selectedGroupRowTop else null
                    }
                    index += if (searchText.isEmpty()) 3 else 2
                }
                if (selectedGroupId != null) return@run null
                for ((instance, entries) in allInstancesWithEntries) {
                    val collapsed = collapsedInstanceIds.contains(instance.id)
                    val at = entries.indexOfFirst { it.id == activeEntryId }
                    if (at >= 0) return@run (if (collapsed) index else index + 1 + at) to 0
                    index += 1 + if (collapsed) 0 else entries.size
                }
                null
            }
            val showJumpButton by remember(pickerJumpTarget, selectedGroupRowOnScreen) {
                derivedStateOf {
                    val target = pickerJumpTarget ?: return@derivedStateOf false
                    if (!pickerListState.canScrollForward && !pickerListState.canScrollBackward) return@derivedStateOf false
                    val info = pickerListState.layoutInfo
                    val item = info.visibleItemsInfo.firstOrNull { it.index == target.first } ?: return@derivedStateOf true
                    if (selectedGroupId != null) return@derivedStateOf !selectedGroupRowOnScreen
                    item.offset < info.viewportStartOffset || item.offset + item.size > info.viewportEndOffset
                }
            }

            // The list keeps its indentation inside this Box, which only
            // overlays the jump button on it.
            Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            LazyColumn(
                state = pickerListState,
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(pickerScrollGuard),
            ) {
                if (filteredGroups.isNotEmpty()) {
                    item(key = "model_groups_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = ModelPickerGutter),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SmallTitle(
                                text = stringResource(R.string.model_picker_groups_section),
                                modifier = Modifier.weight(1f).semantics { heading() },
                                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            )
                            if (onEditGroups != null) {
                                TextButton(
                                    onClick = onEditGroups,
                                    enabled = show,
                                    text = stringResource(R.string.model_picker_groups_edit),
                                    colors = ButtonDefaults.textButtonColors(
                                        color = Color.Transparent,
                                        disabledColor = Color.Transparent,
                                        textColor = colors.primary,
                                    ),
                                )
                            }
                        }
                    }
                    item(key = "model_groups_card") {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = ModelPickerGutter),
                            cornerRadius = ModelPickerCardRadius,
                        ) {
                            filteredGroups.forEachIndexed { index, group ->
                                val isSelected = group.id == selectedGroupId
                                val isDefault = group.id == defaultPrimaryGroupId
                                val isExpanded = expandedGroupIds.contains(group.id)
                                val strategyLabel = when (group.strategy) {
                                    RoutingStrategy.fallback -> "FB"
                                    RoutingStrategy.loadBalance -> "LB"
                                }
                                // Resolve entry: try memberEntryIds first, fallback to activeEntryId ONLY if this group is selected
                                // SystemVoiceEntries fallback: voice groups are
                                // seeded with "__builtin_system_speech__/…"
                                // members that are synthesized on demand and
                                // never stored in config.modelEntries, so
                                // matching modelEntries alone counts them as 0.
                                val resolvedEntry = group.memberEntryIds.firstNotNullOfOrNull { entryId ->
                                    config.modelEntries.find { it.id == entryId }
                                        ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId)
                                } ?: if (isSelected && activeEntryId != null) config.modelEntries.find { it.id == activeEntryId } else null
                                // Count of resolved members for display
                                val resolvedCount = group.memberEntryIds.count { entryId ->
                                    config.modelEntries.any { it.id == entryId } ||
                                        com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId) != null
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            // [FEAT-261025-02] Where the jump button
                                            // scrolls to: this row's top in the
                                            // card, and whether it is in view.
                                            if (isSelected) Modifier.onGloballyPositioned { c ->
                                                selectedGroupRowTop = c.positionInParent().y.toInt()
                                                selectedGroupRowOnScreen = c.boundsInWindow().height >= c.size.height * 0.9f
                                            } else Modifier
                                        )
                                        .modelPickerSelection(isSelected, show) { onSelectGroup(group.id) }
                                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSelected) colors.primary
                                        else MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.3f),
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    // [T-android-model-picker-polish] Group
                                    // glyph, matching iOS's blue "layers" mark
                                    // (SessionModelPicker). Without it a group
                                    // row and a provider row differ only by
                                    // their subtitle, which is easy to miss
                                    // when scrolling — and the two mean very
                                    // different things (a group can fail over
                                    // or load-balance; a model cannot).
                                    Icon(
                                        Icons.Default.Layers,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                group.name,
                                                style = MiuixTheme.textStyles.body1,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            // iOS: ⊕ FB / ⊕ LB badge with icon
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .background(
                                                        MiuixTheme.colorScheme.surfaceContainerHighest,
                                                        RoundedCornerShape(8.dp),
                                                    )
                                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                                            ) {
                                                Icon(
                                                    if (group.strategy == RoutingStrategy.fallback)
                                                        Icons.Default.ArrowCircleDown
                                                    else Icons.Default.AccountTree,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(9.dp),
                                                    tint = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.7f),
                                                )
                                                Spacer(Modifier.width(2.dp))
                                                Text(
                                                    strategyLabel,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.7f),
                                                )
                                            }
                                        }
                                        // iOS: "→ ModelName" resolved entry
                                        if (resolvedEntry != null) {
                                            Text(
                                                "→ ${resolvedEntry.model.displayName}",
                                                style = MiuixTheme.textStyles.body2,
                                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.6f),
                                            )
                                        } else if (resolvedCount > 0) {
                                            Text(
                                                pluralStringResource(R.plurals.model_picker_models_count, resolvedCount, resolvedCount),
                                                style = MiuixTheme.textStyles.body2,
                                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.5f),
                                            )
                                        } else {
                                            Text(
                                                stringResource(R.string.model_picker_models_count_unlinked, group.memberEntryIds.size),
                                                style = MiuixTheme.textStyles.body2,
                                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.4f),
                                            )
                                        }
                                    }

                                    // [T-android-model-picker-polish] "Default"
                                    // badge, matched to iOS
                                    // (UnifiedModelPicker.swift:669-674):
                                    // caption2 medium on a 10%-blue capsule with
                                    // 5/1 padding. Android was running 6/2 —
                                    // double the vertical inset — which made the
                                    // capsule noticeably taller than the group
                                    // name beside it and pulled the eye away from
                                    // the group's own label. Font drops 10sp -> 9sp
                                    // to match caption2's optical weight at this
                                    // density, and lineHeight is pinned so Compose
                                    // does not re-add the font's ascent/descent
                                    // slack on top of the 1.dp padding.
                                    if (isDefault) {
                                        Text(
                                            stringResource(R.string.model_picker_default_badge),
                                            fontSize = 9.sp,
                                            lineHeight = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.onPrimaryContainer,
                                            modifier = Modifier
                                                .background(
                                                    colors.primaryContainer,
                                                    RoundedCornerShape(50),
                                                )
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }

                                    PickerDisclosureButton(
                                        expanded = isExpanded,
                                        enabled = show,
                                        label = stringResource(if (isExpanded) R.string.group_collapse else R.string.group_expand),
                                        onClick = {
                                            expandedGroupIds = if (isExpanded) expandedGroupIds - group.id
                                            else expandedGroupIds + group.id
                                        },
                                    )
                                }

                                // Expanded group member entries
                                if (isExpanded) {
                                    // Resolve actual entries from memberEntryIds
                                    val resolvedMembers = group.memberEntryIds.mapNotNull { entryId ->
                                        config.modelEntries.find { it.id == entryId }
                                            ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId)
                                    }
                                    // Fallback: show active entry ONLY if this group is selected
                                    val displayMembers = resolvedMembers.ifEmpty {
                                        if (isSelected && activeEntryId != null)
                                            listOfNotNull(config.modelEntries.find { it.id == activeEntryId })
                                        else emptyList()
                                    }
                                    if (displayMembers.isEmpty()) {
                                        Text(
                                            stringResource(R.string.model_picker_no_linked_models),
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp),
                                        )
                                    }
                                    displayMembers.forEachIndexed { memberIndex, entry ->
                                            // [T-android-model-picker-polish]
                                            // Hairline between members. The group
                                            // CARDS had one and the provider rows
                                            // had one, but the members inside an
                                            // expanded group did not — so a group
                                            // with several models read as one
                                            // undifferentiated block, which is
                                            // exactly where separation matters
                                            // most (these rows are indented and
                                            // visually similar). Drawn BEFORE each
                                            // row except the first, so it never
                                            // collides with the divider that
                                            // follows the group card itself.
                                            if (memberIndex > 0) {
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                                                    thickness = 0.5.dp,
                                                    color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.35f),
                                                )
                                            }
                                            val isActive = activeEntryId == entry.id
                                            val isMemberSelected = isSelected && isActive
                                            val instance = config.instances.find { it.id == entry.providerInstanceId }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 48.dp)
                                                    .modelPickerSelection(isMemberSelected, show) { onSelectGroupEntry(group.id, entry.id) }
                                                    .padding(start = 48.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    if (isMemberSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                    contentDescription = null,
                                                    tint = if (isMemberSelected) colors.primary
                                                    else MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.2f),
                                                    modifier = Modifier.size(17.dp),
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .background(providerDotColor(instance?.providerType), CircleShape),
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    ModelNameWithActiveBadge(
                                                        entry.model.displayName,
                                                        showActiveBadge = isActive,
                                                        quotaPill = if (instance != null) {
                                                            {
                                                                // [T-quota-group-member-pill] Group rows have no
                                                                // provider header: the model's (or its account's)
                                                                // quota sits after the name, before Active.
                                                                com.openminis.app.ui.quota.ModelQuotaMemberPill(
                                                                    instanceId = entry.providerInstanceId,
                                                                    modelId = entry.model.id,
                                                                ) { quotaSheetInstanceId = entry.providerInstanceId }
                                                            }
                                                        } else {
                                                            null
                                                        },
                                                    )
                                                    Row {
                                                        if (instance != null) {
                                                            Text(
                                                                instance.label.ifEmpty { instance.providerType.displayName },
                                                                style = MiuixTheme.textStyles.footnote2,
                                                                fontWeight = FontWeight.Medium,
                                                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.7f),
                                                            )
                                                            Text(
                                                                " · ",
                                                                style = MiuixTheme.textStyles.footnote2,
                                                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.4f),
                                                            )
                                                        }
                                                        Text(
                                                            entry.model.id,
                                                            style = MiuixTheme.textStyles.footnote2,
                                                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.4f),
                                                        )
                                                    }
                                                }
                                                QuickTestButton(onClick = { quickTestEntry = entry })
                                            }
                                    }
                                }

                                // Inset hairline between groups, matches MinisMenuDivider rhythm.
                                if (index < filteredGroups.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 48.dp, end = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.35f),
                                    )
                                }
                            }
                        }
                    }

                    if (searchText.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.model_picker_groups_footer),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.5f),
                                // T236: hint sits between Model Groups card and
                                // first Provider card — top 8 hugs the group
                                // card, bottom 12 separates from the next card.
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                            )
                        }
                    }
                }

                // ── Individual Models by Provider (one section card per provider) ──
                // Each provider becomes a single grouped card containing: an
                // embedded header row with the collapse chevron, then either
                // the collapsed summary row or the expanded entry list. Cards
                // are visually separated from each other by a 12dp gap, and
                // sit on a higher tonal surface so the boundary between
                // providers is unmistakable even on the dark sheet background.
                if (allInstancesWithEntries.isNotEmpty()) {
                    allInstancesWithEntries.forEach { (instance, entries) ->
                        val isCollapsed = collapsedInstanceIds.contains(instance.id)
                        // [T-picker-search-cap] An EXPANDED provider's rows are
                        // their own lazy items (below), so a 7,000-model
                        // aggregator composes only what is on screen. This
                        // item is the card's top: header, hairline and — when
                        // collapsed — the summary rows, closing the card.
                        item(key = "section_${instance.id}") {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = ModelPickerGutter)
                                    .padding(top = 12.dp)
                                    .background(
                                        colors.surfaceContainer,
                                        if (isCollapsed) RoundedCornerShape(ModelPickerCardRadius)
                                        else RoundedCornerShape(topStart = ModelPickerCardRadius, topEnd = ModelPickerCardRadius),
                                    ),
                            ) {
                                // Header row, embedded in the card.
                                Row(
                                    // T236: tightened provider header padding
                                    // (top=10/bottom=8) so the gap to the first
                                    // model row reads as ~8dp rather than the
                                    // earlier 12dp+row-padding stack.
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val providerName = instance.label.ifEmpty { instance.providerType.displayName }
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            providerName,
                                            // Same rank as the "Model Groups"
                                            // header above — see that comment.
                                            style = MiuixTheme.textStyles.headline1,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                            // [T-android-picker-provider-edit] One
                                            // line, so a long name cannot push the
                                            // two buttons off the row.
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        // [T-provider-quota P2] The account's balance /
                                        // quota right after the name - the only place
                                        // a shared pool shows in the picker. Empty
                                        // without data; a tap opens the quota sheet.
                                        com.openminis.app.ui.quota.QuotaSectionHeaderBadge(instance.id) {
                                            quotaSheetInstanceId = instance.id
                                        }
                                    }
                                    // [T-android-picker-provider-edit] Edit
                                    // button left of the chevron, as on iOS
                                    // (698cc65c6): its own tap target, so it
                                    // never folds the section, same 24dp
                                    // circle as the chevron but tinted so it
                                    // reads as an action.
                                    if (onEditProvider != null) {
                                        // [T-android-picker-provider-edit-text] A text "Edit" link,
                                        // styled like the Model Groups "Edit" (iOS a0acb1c0f replaced its
                                        // icon button with this). Compact rather than a MinisTextButton so
                                        // the header row keeps its height; still its own tap target, so it
                                        // never folds the section.
                                        val editLabel = stringResource(R.string.model_picker_edit_provider, providerName)
                                        Text(
                                            stringResource(R.string.model_picker_groups_edit),
                                            style = MiuixTheme.textStyles.button,
                                            color = MiuixTheme.colorScheme.primary,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier
                                                .padding(end = 4.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable(enabled = show) { onEditProvider(instance.id) }
                                                .semantics { contentDescription = editLabel }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                    PickerDisclosureButton(
                                        expanded = !isCollapsed,
                                        enabled = show,
                                        label = stringResource(
                                            if (isCollapsed) R.string.model_picker_expand_provider else R.string.model_picker_collapse_provider,
                                            providerName,
                                        ),
                                        onClick = {
                                            collapsedInstanceIds = if (isCollapsed) collapsedInstanceIds - instance.id
                                            else collapsedInstanceIds + instance.id
                                        },
                                    )
                                }

                                // [T-android-model-picker-polish] Hairline under
                                // the provider name. The rows below it are
                                // models, not more provider chrome, and without
                                // a rule the header read as the first list item
                                // — the same separation the group card gets
                                // between its own header and its members.
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.35f),
                                )

                                if (isCollapsed) {
                                    // Collapsed summary — show selected or first entry + model count.
                                    val selectedEntry = entries.firstOrNull { it.id == activeEntryId && selectedGroupId == null }
                                    val displayEntry = selectedEntry ?: entries.firstOrNull()
                                    if (displayEntry != null) {
                                        val dotColor = providerDotColor(instance.providerType)
                                        Row(
                                            // T236: collapsed summary row —
                                            // vertical 14→8 + heightIn(min=48dp)
                                            // so tap target stays comfortable.
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .clip(RoundedCornerShape(bottomStart = ModelPickerCardRadius, bottomEnd = ModelPickerCardRadius))
                                                .modelPickerSelection(selectedEntry != null, show) { onSelectEntry(displayEntry.id) }
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                if (selectedEntry != null) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (selectedEntry != null) MiuixTheme.colorScheme.primary
                                                else MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.3f),
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(dotColor, CircleShape),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            // [T-model-row-premature-wrap] The collapsed
                                            // representative is the same row as an
                                            // expanded one (iOS renders it with entryRow
                                            // too): name line, then quota hint + id with
                                            // the modality chips. It used to be one
                                            // FlowRow of name + chips with no id line.
                                            ProviderEntryRowText(
                                                entry = displayEntry,
                                                showActiveBadge = selectedGroupId != null && activeEntryId == displayEntry.id,
                                                modifier = Modifier.weight(1f),
                                            )
                                            // [T-android-model-picker-polish]
                                            // Quick Test, not a model count. The
                                            // count is already stated by the
                                            // "Show N models" row directly below,
                                            // so repeating it here spent the row's
                                            // trailing slot on a duplicate —
                                            // where every OTHER model row in this
                                            // sheet carries a bolt. iOS puts the
                                            // bolt here for the same reason.
                                            QuickTestButton(onClick = { quickTestEntry = displayEntry })
                                        }
                                        // Only when there is something to show:
                                        // a single-model provider's collapsed
                                        // preview IS its entire list, so
                                        // "Show 1 model" would expand to the
                                        // exact row already on screen.
                                        if (entries.size > 1) {
                                        // Hairline before the expand row: it is a
                                        // control, not another model, and butting
                                        // it against the summary row above made
                                        // the two read as one two-line entry.
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                                            thickness = 0.5.dp,
                                            color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.35f),
                                        )
                                        // [T-android-model-picker-polish] Explicit
                                        // "Show N models" affordance, as iOS has.
                                        // The chevron in the header already
                                        // expands, but it is a small target in the
                                        // corner and reads as decoration — the
                                        // collapsed row gave no hint that the
                                        // other N-1 models were one tap away.
                                        // Tapping the summary row itself SELECTS
                                        // that model, so expanding needed its own
                                        // control rather than sharing that one.
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 40.dp)
                                                .clip(RoundedCornerShape(bottomStart = ModelPickerCardRadius, bottomEnd = ModelPickerCardRadius))
                                                .clickable { collapsedInstanceIds = collapsedInstanceIds - instance.id }
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // Chevron LEADS the label and the row
                                            // starts at the card's own inset: the
                                            // arrow is what signals "this expands",
                                            // so it has to be the first thing read,
                                            // and the earlier 30.dp indent left it
                                            // floating under the model names above
                                            // rather than aligned with the card.
                                            Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MiuixTheme.colorScheme.primary,
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                pluralStringResource(
                                                    R.plurals.model_picker_show_models,
                                                    entries.size,
                                                    entries.size,
                                                ),
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = MiuixTheme.colorScheme.primary,
                                            )
                                        }
                                        }
                                    }
                                }
                            }
                        }
                        if (!isCollapsed) {
                            itemsIndexed(entries, key = { _, e -> "entry_${instance.id}_${e.id}" }) { index, entry ->
                                val isLastRow = index == entries.size - 1
                                Column(
                                    modifier = Modifier
                                        .padding(horizontal = ModelPickerGutter)
                                        .background(
                                            colors.surfaceContainer,
                                            if (isLastRow) RoundedCornerShape(bottomStart = ModelPickerCardRadius, bottomEnd = ModelPickerCardRadius)
                                            else androidx.compose.ui.graphics.RectangleShape,
                                        ),
                                ) {
                                        val isSelected = activeEntryId == entry.id && selectedGroupId == null
                                        val dotColor = providerDotColor(instance.providerType)
                                        // Last row clips its own bottom so the
                                        // ripple respects the card corners.
                                        val rowShape = if (index == entries.size - 1) {
                                            RoundedCornerShape(bottomStart = ModelPickerCardRadius, bottomEnd = ModelPickerCardRadius)
                                        } else {
                                            RoundedCornerShape(0.dp)
                                        }
                                        Row(
                                            // T236: expanded entry row —
                                            // vertical 14→8 + heightIn(min=48dp)
                                            // for accessible tap target.
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .clip(rowShape)
                                                .modelPickerSelection(isSelected, show) { onSelectEntry(entry.id) }
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (isSelected) MiuixTheme.colorScheme.primary
                                                else MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.3f),
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(dotColor, CircleShape),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            ProviderEntryRowText(
                                                entry = entry,
                                                showActiveBadge = selectedGroupId != null && activeEntryId == entry.id,
                                                modifier = Modifier.weight(1f),
                                            )
                                            QuickTestButton(onClick = { quickTestEntry = entry })
                                        }
                                        // Inset hairline between entries.
                                        if (index < entries.size - 1) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                                thickness = 0.5.dp,
                                                color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.35f),
                                            )
                                        }
                                }
                            }
                        }
                    }
                }

                // [T-picker-search-cap] Say when a search was truncated, so the
                // capped list never silently reads as "that's all".
                truncatedSearch?.let { r ->
                    item(key = "__search_cap_footer__") {
                        Text(
                            stringResource(R.string.model_picker_search_capped, r.shown, r.totalMatches),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }

                // ── Empty / No Results ──
                if (filteredGroups.isEmpty() && allInstancesWithEntries.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                if (searchText.isNotEmpty()) Icons.Default.Search else Icons.Default.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.3f),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(if (searchText.isNotEmpty()) R.string.model_picker_no_results else R.string.model_picker_no_models),
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )
                            if (searchText.isEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.model_picker_configure_hint),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.model_picker_try_different_search),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }
                // [FEAT-261025-02] Jump to what this chat uses, for a list too
                // long to find it by eye (field request: many Model Groups).
                // Shown only while the list can scroll and the target is off
                // screen.
                val jumpTarget = pickerJumpTarget
                if (jumpTarget != null && showJumpButton) {
                    FloatingActionButton(
                        onClick = { pickerScope.launch { pickerListState.animateScrollToItem(jumpTarget.first, jumpTarget.second) } },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 20.dp, bottom = 12.dp),
                        containerColor = MiuixTheme.colorScheme.surfaceContainerHighest,
                        minWidth = 48.dp,
                        minHeight = 48.dp,
                    ) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = stringResource(R.string.model_picker_jump_current),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }

    MinisBottomSheet(
        show = show,
        backgroundColor = colors.surface,
        dragHandleColor = colors.onSurfaceVariantSummary,
        insideMargin = DpSize.Zero,
        onDismissRequest = { if (show) onDismiss() },
        onDismissFinished = onDismissFinished,
    ) {
        // Android Dialog supplies its own density. Use the app density for the picker content.
        CompositionLocalProvider(LocalDensity provides sheetDensity, content = pickerContent)
    }

    // [T-quota-badge-ui] Read-only quota details, on top of the picker.
    quotaSheetInstanceId?.let { id ->
        key(id) {
            com.openminis.app.ui.quota.QuotaDetailSheet(instanceId = id, onDismiss = { quotaSheetInstanceId = null })
        }
    }

    // [T-android-model-picker-polish] Quick Test host. Keyed on the entry id so
    // switching models rebuilds the sheet's state rather than reusing a run
    // from the previously tested model — the same identity trap iOS documents
    // at UnifiedModelPicker.swift:524.
    quickTestEntry?.let { entry ->
        key(entry.id) {
            com.openminis.app.ui.components.QuickTestSheet(
                entry = entry,
                providerRepository = providerRepository,
                onDismiss = { quickTestEntry = null },
            )
        }
    }
}

/**
 * A picker row's model name, then its tags: the group member's quota pill and
 * the green "Active" badge.
 *
 * The badge used to sit at the row's trailing edge, beside the quick-test
 * button. There it took width from the whole text column, so the second line
 * (provider label + model id) wrapped early: long ids such as
 * `claude-opus-4-6-thinking` broke onto a third line. Inline after the name,
 * it only competes with the name.
 *
 * [T-model-row-premature-wrap] Name and tags share one line when they fit;
 * otherwise the name keeps the full width and the tags drop to their own line
 * (iOS memberRowName / memberRowTags in a ViewThatFits). The earlier Row with
 * `weight(1f, fill = false)` on the name wrapped the NAME early instead.
 */
@Composable
private fun ModelNameWithActiveBadge(
    name: String,
    showActiveBadge: Boolean,
    quotaPill: (@Composable () -> Unit)? = null,
) {
    FitOrWrapLine(
        lead = {
            Text(name, style = MiuixTheme.textStyles.body1)
        },
        tags = {
            quotaPill?.invoke()
            if (showActiveBadge) ModelActiveBadge()
        },
    )
}

/**
 * [T-model-row-premature-wrap] Text column of a provider-section model row
 * (iOS entryRow), shared by the expanded rows and the collapsed section's
 * representative row: the name (+ Active badge), then the quota hint + model
 * id, "·" and the modality chips on one line when they fit, else the id keeps
 * the full width and the chips drop below it without the "·".
 */
@Composable
private fun ProviderEntryRowText(
    entry: ModelEntry,
    showActiveBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    val badges = com.openminis.app.ui.components.modalityBadges(entry.model)
    Column(modifier = modifier) {
        ModelNameWithActiveBadge(entry.model.displayName, showActiveBadge = showActiveBadge)
        FitOrWrapLine(
            separator = if (badges.isNotEmpty()) {
                { com.openminis.app.ui.components.ModelRowSeparatorDot() }
            } else {
                null
            },
            lead = {
                // [T-provider-quota P2] Only a warning on a pool bound to
                // THIS model; the account is on the section header.
                com.openminis.app.ui.quota.ModelQuotaHintView(
                    instanceId = entry.providerInstanceId,
                    modelId = entry.model.id,
                )
                Text(
                    entry.model.id,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.5f),
                )
            },
            tags = {
                badges.forEach { badge -> com.openminis.app.ui.components.ModalityBadge(badge) }
            },
        )
    }
}

/**
 * [T-android-model-picker-polish] Per-row Quick Test affordance, mirroring
 * iOS's `bolt.badge.checkmark` button (UnifiedModelPicker.swift:542).
 *
 * An independent icon action starts the test without changing the selection.
 */
@Composable
private fun QuickTestButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, minWidth = 48.dp, minHeight = 48.dp) {
        Icon(
            Icons.Default.Bolt,
            contentDescription = stringResource(R.string.model_picker_quick_test),
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun PickerDisclosureButton(expanded: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, minWidth = 48.dp, minHeight = 48.dp) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = label,
            tint = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun Modifier.modelPickerSelection(selected: Boolean, enabled: Boolean, onClick: () -> Unit): Modifier {
    val color = MiuixTheme.colorScheme.primary.copy(alpha = if (selected) 0.08f else 0f)
    return background(color).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
}

// Provider type marks
private fun providerDotColor(providerType: ProviderType?): Color = when (providerType) {
    ProviderType.anthropic -> Color(0xFFAB47BC) // purple
    ProviderType.gemini -> Color(0xFF42A5F5)    // blue
    ProviderType.openAI -> Color(0xFF4CAF50)    // green
    ProviderType.openRouter -> Color(0xFF00BCD4) // cyan
    ProviderType.xAI -> Color(0xFFFF7043)        // orange — Grok brand
    ProviderType.kimiCode -> Color(0xFF5C6BC0)   // indigo — Kimi accent
    ProviderType.githubCopilot -> Color(0xFF6E5494) // purple — GitHub accent
    // [T-android-provider-type-parity] Responses API instances are
    // OpenAI under the hood — same green dot. Undrivable types share
    // the neutral gray used for "no provider".
    ProviderType.openAIResponses -> Color(0xFF4CAF50)
    ProviderType.antigravity,
    ProviderType.unsupported -> Color(0xFF8E8E93)
    null -> Color(0xFF8E8E93)                    // gray
}
