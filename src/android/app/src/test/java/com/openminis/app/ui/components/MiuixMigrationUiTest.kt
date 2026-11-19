package com.openminis.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.state.ToggleableState
import com.openminis.app.ui.settings.imageEndpointLabels
import com.openminis.app.ui.theme.ChatColors
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import com.openminis.app.R
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.chat.ComposerSurfaceGeometry
import com.openminis.app.ui.chat.ModelPickerSheet
import com.openminis.app.ui.sessions.FolderCard
import com.openminis.app.ui.sessions.FolderGroupBlock
import com.openminis.app.ui.chat.ChatGlassTopBar
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.openminis.app.ui.components.miuix.*
import com.openminis.app.ui.theme.MinisTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.DpSize
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.basic.TextField

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MiuixMigrationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeButtonsHonorEnabledAndCallback() {
        var clicks = 0
        compose.setContent {
            MinisTheme {
                Column {
                    TextButton(
                        onClick = { clicks++ },
                        modifier = Modifier.testTag("active"),
                        text = "Save",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    TextButton(
                        onClick = { clicks += 100 },
                        enabled = false,
                        modifier = Modifier.testTag("disabled"),
                        text = "Disabled",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
        compose.onNodeWithTag("active").assertIsEnabled().performClick()
        compose.onNodeWithTag("disabled").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun textFieldEditsAndPasswordTransformationRemainInteractive() {
        var result = ""
        compose.setContent {
            MinisTheme {
                var value by remember { mutableStateOf("") }
                TextField(
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    value = value,
                    onValueChange = { value = it; result = it },
                    label = "API key",
                    useLabelAsPlaceholder = false,
                    modifier = Modifier.testTag("field"),
                )
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("secret-test-value")
        compose.runOnIdle { assertEquals("secret-test-value", result) }
    }

    @Test fun booleanCheckboxMapsToNativeStateAndToggles() {
        var checked = false
        compose.setContent {
            MinisTheme {
                var value by remember { mutableStateOf(false) }
                Checkbox(
                    state = if (value) ToggleableState.On else ToggleableState.Off,
                    onClick = { value = !value; checked = value },
                    modifier = Modifier.testTag("checkbox"),
                )
            }
        }
        compose.onNodeWithTag("checkbox").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(checked) }
    }

    @Test fun windowDialogRendersFromRootThemeAndDismisses() {
        var accepted = false
        compose.setContent {
            MinisTheme {
                var shown by remember { mutableStateOf(true) }
                if (shown) WindowDialog(
                    show = true,
                    onDismissRequest = { shown = false },
                    title = "Miuix confirmation",
                    summary = "Dialog body",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                                                onClick = { accepted = true; shown = false },
                                                text = "Accept",
                                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Dialog body").assertIsDisplayed()
        compose.onNodeWithText("Accept").performClick()
        compose.runOnIdle { assertTrue(accepted) }
        compose.onNodeWithText("Dialog body").assertDoesNotExist()
    }

    @Test fun sheetRendersAndCancelCallbackIsDelivered() {
        var closed = false
        compose.setContent {
            MinisTheme {
                var shown by remember { mutableStateOf(true) }
                if (shown) WindowBottomSheet(
                    show = true,
                    insideMargin = DpSize.Zero,
                    onDismissRequest = { shown = false; closed = true },
                ) {
                    Text("Sheet content")
                    TextButton(
                        onClick = { shown = false; closed = true },
                        text = "Close sheet",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
        compose.onNodeWithText("Sheet content").assertIsDisplayed()
        compose.onNodeWithText("Close sheet").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun settingsGlassBarStaysFixedAndBackWorksAfterScrolling() {
        verifySettingsScrollAndBack(dark = false)
    }

    @Config(sdk = [32])
    @Test fun settingsGlassBarFallbackStillScrollsAndNavigates() {
        verifySettingsScrollAndBack(dark = true)
    }

    private fun verifySettingsScrollAndBack(dark: Boolean) {
        var backClicks = 0
        compose.setContent {
            MinisTheme(darkTheme = dark) {
                SettingsScaffold(title = "Glass Settings", onBack = { backClicks++ }, centerTitle = true) {
                    SettingsSection(header = "Appearance") { Text("First preference") }
                    Spacer(Modifier.height(1100.dp))
                    SettingsSection(header = "About") { Text("Last preference") }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        val title = compose.onNodeWithText("Glass Settings").assertIsDisplayed()
        val initialTitleBounds = title.getUnclippedBoundsInRoot()
        compose.onNodeWithText("First preference").assertIsDisplayed()
        compose.onNodeWithText("Last preference").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("First preference").assertIsNotDisplayed()
        assertEquals(initialTitleBounds, title.getUnclippedBoundsInRoot())
        val backLabel = compose.activity.getString(R.string.settings_back)
        compose.onNodeWithContentDescription(backLabel).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, backClicks) }
    }

    @Test fun anchoredMenuScrollsAndDispatchesSelectionOnce() {
        var selections = 0
        var finishedDismissals = 0
        compose.setContent {
            MinisTheme(darkTheme = true, fontScale = 1.25f) {
                Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.TopEnd) {
                    Box {
                        var expanded by remember { mutableStateOf(false) }
                        TextButton(
                            onClick = { expanded = true },
                            text = "More actions",
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                        MinisMenu(
                            expanded = expanded,
                            alignEnd = true,
                            onDismissRequest = { expanded = false },
                            onDismissFinished = { finishedDismissals++ },
                        ) {
                            repeat(28) { index ->
                                DropdownMenuItem(
                                    text = { Text("Action ${index + 1}") },
                                    onClick = { expanded = false; selections++ },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("More actions").performClick()
        compose.onNodeWithText("Action 1").assertIsDisplayed()
        compose.onNodeWithText("Action 28").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Action 28").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, selections)
            assertEquals(1, finishedDismissals)
        }
    }

    @Config(sdk = [32])
    @Test fun dropdownDismissesOnOutsideTapWithoutActivatingAnItem() {
        var dismissed = 0
        var selected = 0
        compose.setContent {
            MinisTheme {
                Box(Modifier.fillMaxSize().testTag("menu-page").padding(12.dp)) {
                    Box {
                        var expanded by remember { mutableStateOf(false) }
                        TextButton(
                            onClick = { expanded = true },
                            text = "Show options",
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                        DropdownMenu(expanded, onDismissRequest = { expanded = false; dismissed++ }) {
                            DropdownMenuItem(text = { Text("Option") }, onClick = { selected++ })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Show options").performClick()
        compose.onNodeWithText("Option").assertIsDisplayed()
        compose.onNodeWithTag("menu-page").performTouchInput { click(bottomRight - androidx.compose.ui.geometry.Offset(4f, 4f)) }
        compose.onNodeWithText("Option").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, selected)
        }
    }

    @Config(sdk = [32])
    @Test fun chatGlassFallbackKeepsBothToolbarActionsClickable() {
        var backClicks = 0
        var moreClicks = 0
        compose.setContent {
            MinisTheme(darkTheme = true) {
                ChatGlassTopBar(
                    backdrop = null,
                    title = { Text("Conversation") },
                    navigationIcon = {
                        MinisGlassIconButton(onClick = { backClicks++ }) { Text("Back") }
                    },
                    actions = {
                        MinisGlassIconButton(onClick = { moreClicks++ }) { Text("More") }
                    },
                )
            }
        }
        compose.onNodeWithText("Conversation").assertIsDisplayed()
        compose.onNodeWithText("Back").assertIsDisplayed().performClick()
        compose.onNodeWithText("More").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, backClicks)
            assertEquals(1, moreClicks)
        }
    }

    @Test fun glassMenuCanReopenAndHandsOffToSubmenuAfterExit() {
        var finished = 0
        var selected = 0
        compose.setContent {
            MinisTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                    Box {
                        var primary by remember { mutableStateOf(false) }
                        var pendingSecondary by remember { mutableStateOf(false) }
                        var secondary by remember { mutableStateOf(false) }
                        val anchor = rememberGlassPopupAnchor()
                        MinisGlassIconButton(
                            onClick = { primary = true },
                            modifier = Modifier.glassPopupAnchor(anchor, cornerRadius = 24.dp, floating = true),
                        ) { Text("Menu", Modifier.glassPopupAnchorContent(anchor)) }
                        MinisMenu(
                            expanded = primary,
                            onDismissRequest = { primary = false },
                            glassAnchor = anchor,
                            anchorContent = { Text("Menu") },
                            onDismissFinished = {
                                finished++
                                if (pendingSecondary) { pendingSecondary = false; secondary = true }
                            },
                        ) {
                            DropdownMenuItem(text = { Text("Open secondary") }, onClick = {
                                pendingSecondary = true
                                primary = false
                            })
                        }
                        MinisMenu(expanded = secondary, onDismissRequest = { secondary = false }) {
                            DropdownMenuItem(text = { Text("Select secondary") }, onClick = {
                                selected++
                                secondary = false
                            })
                        }
                    }
                }
            }
        }
        repeat(2) {
            compose.onNodeWithText("Menu").performClick()
            compose.onNodeWithText("Open secondary").assertIsDisplayed().performClick()
            compose.onNodeWithText("Open secondary").assertDoesNotExist()
            compose.onNodeWithText("Select secondary").assertIsDisplayed().performClick()
            compose.onNodeWithText("Select secondary").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(2, finished); assertEquals(2, selected) }
    }

    @Test fun composerChoiceGlassMenuPreservesFocusedDraftAndSingleSelection() {
        var sends = 0
        var draft = ""
        compose.setContent {
            MinisTheme {
                Column {
                    var value by remember { mutableStateOf("") }
                    TextField(
                        value = value,
                        onValueChange = { value = it; draft = it },
                        modifier = Modifier.testTag("draft"),
                    )
                    com.openminis.app.ui.chat.ComposerSendButton(canActivate = true, onSend = { sends++ })
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("Unsent draft")
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithContentDescription("Send").performTouchInput { longClick() }
        compose.onNodeWithText(compose.activity.getString(R.string.send_return_mode_newline)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithText(compose.activity.getString(R.string.send_return_mode_newline)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.send_return_mode_newline)).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { assertEquals("Unsent draft", draft); assertEquals(0, sends) }
    }

    @Test fun providerImageEndpointTabsScrollAndSelectAtLargerTextSize() {
        var selected = 0
        val labels = listOf(
            R.string.provider_image_endpoint_auto,
            R.string.provider_image_endpoint_images,
            R.string.provider_image_endpoint_chat,
        ).map { compose.activity.getString(it) }
        compose.setContent {
            MinisTheme(fontScale = 1.5f) {
                var index by remember { mutableStateOf(0) }
                val tabs = imageEndpointLabels()
                Box(Modifier.width(200.dp)) {
                    TabRowWithContour(
                        tabs = tabs,
                        selectedTabIndex = index,
                        onTabSelected = { index = it; selected = it },
                        minWidth = preferenceTabMinWidth(tabs),
                        height = preferenceTabHeight(),
                    )
                }
            }
        }
        compose.onNodeWithText(labels.first()).assertIsSelected()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(labels.lastIndex)
        compose.onNodeWithText(labels.last()).assertIsDisplayed().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(labels.lastIndex, selected) }
    }

    @Config(sdk = [32])
    @Test fun glassDirectActionAndMenuHaveSeparateMotionAndDispatch() {
        var directClicks = 0
        var choices = 0
        compose.setContent {
            MinisTheme {
                MinisPageScaffold {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomStart) {
                        Column {
                            MinisToolbarIconButton(onClick = { directClicks++ }, modifier = Modifier.testTag("direct-glass")) {
                                Text("Direct page")
                            }
                            Box {
                                var show by remember { mutableStateOf(false) }
                                val anchor = rememberGlassPopupAnchor()
                                MinisToolbarIconButton(
                                    onClick = { show = true },
                                    menuMotion = true,
                                    modifier = Modifier.testTag("function-glass")
                                        .glassPopupAnchor(anchor, cornerRadius = 24.dp, floating = true),
                                ) { Text("Plus") }
                                MinisMenu(
                                    expanded = show,
                                    onDismissRequest = { show = false },
                                    glassAnchor = anchor,
                                    anchorContent = { Text("Plus") },
                                ) {
                                    DropdownMenuItem(text = { Text("Attachment action") }, onClick = { choices++; show = false })
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("direct-glass").performClick()
        repeat(2) {
            compose.onNodeWithTag("function-glass").performClick()
            compose.onNodeWithText("Attachment action").assertIsDisplayed().performClick()
            compose.onNodeWithText("Attachment action").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(1, directClicks); assertEquals(2, choices) }
    }

    @Config(sdk = [32])
    @Test fun glassLongPressDoesNotAlsoNavigateAndDisabledActionDoesNothing() {
        var taps = 0
        var holds = 0
        compose.setContent {
            MinisTheme {
                Column {
                    MinisGlassIconButton(
                        onClick = { taps++ },
                        onLongClick = { holds++ },
                        modifier = Modifier.testTag("long-glass"),
                    ) { Text("New") }
                    MinisGlassIconButton(
                        onClick = { taps += 100 },
                        enabled = false,
                        modifier = Modifier.testTag("disabled-glass"),
                    ) { Text("Disabled") }
                }
            }
        }
        compose.onNodeWithTag("long-glass").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, holds); assertEquals(0, taps) }
        compose.onNodeWithTag("long-glass").performTouchInput { click() }
        compose.onNodeWithTag("disabled-glass").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, holds); assertEquals(1, taps) }
    }

    @Test fun settingsTrailingGlassActionsDoNotOverlap() {
        var first = 0
        var second = 0
        compose.setContent {
            MinisTheme {
                SettingsScaffold(title = "Two actions", onBack = {}, actions = {
                    MinisToolbarIconButton(onClick = { first++ }, modifier = Modifier.testTag("sort-action")) { Text("Sort") }
                    MinisToolbarIconButton(onClick = { second++ }, modifier = Modifier.testTag("add-action")) { Text("Add") }
                }) { SettingsSection { Text("Preferences") } }
            }
        }
        val a = compose.onNodeWithTag("sort-action").assertIsDisplayed().getUnclippedBoundsInRoot()
        val b = compose.onNodeWithTag("add-action").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("distinct right-side targets", a.right <= b.left)
        compose.onNodeWithTag("sort-action").performClick()
        compose.onNodeWithTag("add-action").performClick()
        compose.runOnIdle { assertEquals(1, first); assertEquals(1, second) }
    }

    @Test fun mountedFolderAddRemainsVisibleAndOpensPickerIntro() {
        verifyMountedFolderAdd()
    }

    @Config(sdk = [32])
    @Test fun mountedFolderAddFallbackRemainsVisibleAndOpensPickerIntro() {
        verifyMountedFolderAdd()
    }

    private fun verifyMountedFolderAdd() {
        // Environment.isExternalStorageManager reads UserEnvironment.externalDirs[0],
        // a separate Robolectric list from StorageManager's volumes. Seed BOTH,
        // rather than bypassing the production permission probe or the screen.
        val storage = compose.activity.getSystemService(android.os.storage.StorageManager::class.java)
        val external = requireNotNull(org.robolectric.shadows.ShadowEnvironment.addExternalDir("mounted-folders-external"))
        org.robolectric.shadows.ShadowEnvironment.setExternalStorageState(android.os.Environment.MEDIA_MOUNTED)
        val volume = org.robolectric.shadows.StorageVolumeBuilder(
            "primary", external, "Internal storage", android.os.Process.myUserHandle(),
            android.os.Environment.MEDIA_MOUNTED,
        ).setIsPrimary(true).setIsEmulated(true).build()
        org.robolectric.Shadows.shadowOf(storage).addStorageVolume(volume)
        val store = com.openminis.app.data.MountedFoldersStore(compose.activity)
        compose.setContent {
            MinisTheme {
                com.openminis.app.ui.settings.MountedFoldersScreen(
                    store = store, onBack = {}, onMountClick = {},
                )
            }
        }
        val add = compose.activity.getString(R.string.mount_add_dialog_title)
        compose.onNodeWithContentDescription(add).assertIsDisplayed().assertIsEnabled()
            .performTouchInput { click() }
        compose.onNodeWithText(compose.activity.getString(R.string.mount_picker_intro_message))
            .assertIsDisplayed()
    }

    @Test fun compactGlassBarKeepsLongTitleBetweenVisibleBackAndMultipleActions() {
        var back = 0
        var first = 0
        var second = 0
        compose.setContent {
            MinisTheme(fontScale = 1.5f) {
                MinisPageScaffold(topBar = {
                    com.openminis.app.ui.settings.MinisSmallTopAppBar(
                        title = "Mounted external folders and permissions",
                        navigationIcon = {
                            MinisToolbarIconButton(onClick = { back++ }, modifier = Modifier.testTag("compact-back")) { Text("B") }
                        },
                        actions = {
                            MinisToolbarIconButton(onClick = { first++ }, modifier = Modifier.testTag("compact-add")) { Text("+") }
                            MinisToolbarIconButton(onClick = { second++ }, modifier = Modifier.testTag("compact-sort")) { Text("S") }
                        },
                    )
                }) { Text("Page body", Modifier.padding(it)) }
            }
        }
        val a = compose.onNodeWithTag("compact-add").assertIsDisplayed().getUnclippedBoundsInRoot()
        val b = compose.onNodeWithTag("compact-sort").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(a.right <= b.left)
        compose.onNodeWithTag("compact-back").performTouchInput { click() }
        compose.onNodeWithTag("compact-add").performTouchInput { click() }
        compose.onNodeWithTag("compact-sort").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, back); assertEquals(1, first); assertEquals(1, second) }
    }

    @Test fun attachmentPanelLeavesComposerBoundsUnchangedAndRestoresAnchor() {
        var dismissed = 0
        compose.setContent {
            MinisTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                    Column(Modifier.fillMaxWidth().testTag("composer-card")) {
                        Spacer(Modifier.height(ComposerSurfaceGeometry.EDITOR_MIN_HEIGHT_DP.dp))
                        Box {
                            var expanded by remember { mutableStateOf(false) }
                            val anchor = rememberGlassPopupAnchor()
                            MinisGlassIconButton(
                                onClick = { expanded = true },
                                modifier = Modifier.testTag("height-plus")
                                    .glassPopupAnchor(anchor, cornerRadius = 24.dp, floating = true),
                            ) { Text("+") }
                            MinisMenu(
                                expanded = expanded, onDismissRequest = { expanded = false },
                                glassAnchor = anchor, anchorContent = { Text("+") },
                                onDismissFinished = { dismissed++ },
                            ) {
                                repeat(5) { index ->
                                    DropdownMenuItem(
                                        text = { Text("Attachment action $index") },
                                        onClick = { expanded = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val card = compose.onNodeWithTag("composer-card")
        val initialBounds = card.getUnclippedBoundsInRoot()
        repeat(2) {
            compose.onNodeWithTag("height-plus").assertIsDisplayed().performTouchInput { click() }
            val action = compose.onNodeWithText("Attachment action 4")
            action.assertIsDisplayed()
            assertEquals(initialBounds, card.getUnclippedBoundsInRoot())
            action.performClick()
            action.assertDoesNotExist()
            compose.onNodeWithTag("height-plus").assertIsDisplayed()
            assertEquals(initialBounds, card.getUnclippedBoundsInRoot())
        }
        compose.runOnIdle { assertEquals(2, dismissed) }
    }

    @Test fun sessionGroupTapsFollowTheLatestExpandedState() {
        val folder = FolderEntity(id = "work", name = "Work group", createdAt = 1, updatedAt = 1)
        var collapsed by mutableStateOf(true)
        compose.setContent {
            MinisTheme {
                val wasCollapsed = collapsed
                FolderCard(
                    block = FolderGroupBlock(
                        folder = folder,
                        ids = if (wasCollapsed) emptyList() else listOf("conversation"),
                        totalCount = 1,
                        isCollapsed = wasCollapsed,
                        latestUpdatedAt = 0,
                    ),
                    onToggle = { collapsed = !wasCollapsed },
                    onTogglePin = {}, onRename = {}, onDissolve = {},
                    onNewChatInGroup = {}, onDeleteWithSessions = {},
                )
            }
        }
        repeat(4) { index ->
            compose.onNode(hasText("Work group") and hasClickAction())
                .assertIsDisplayed().performTouchInput { click() }
            compose.runOnIdle { assertEquals(index % 2 == 1, collapsed) }
        }
    }

    @Test fun modelPickerShowsSemanticSelectionAndCompletesExitBeforeReopening() {
        val groups = listOf(ModelGroup(id = "primary", name = "Primary group"), ModelGroup(id = "other", name = "Other group"))
        val repository = ProviderRepository(compose.activity.applicationContext)
        var shown by mutableStateOf(true)
        var selectedGroup by mutableStateOf("primary")
        var finished = 0
        compose.setContent {
            MinisTheme(darkTheme = true) {
                ModelPickerSheet(
                    show = shown,
                    groups = groups,
                    selectedGroupId = selectedGroup,
                    activeEntryId = null,
                    defaultPrimaryGroupId = "primary",
                    config = ProviderConfig(),
                    providerRepository = repository,
                    onSelectGroup = { selectedGroup = it; shown = false },
                    onSelectGroupEntry = { _, _ -> },
                    onSelectEntry = {},
                    onDismiss = { shown = false },
                    onDismissFinished = { finished++ },
                )
            }
        }
        val primary = compose.onNode(hasText("Primary group") and hasClickAction())
        val other = compose.onNode(hasText("Other group") and hasClickAction())
        primary.assertIsDisplayed().assertIsSelected()
        other.assertIsDisplayed().assertIsNotSelected().performClick()
        other.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("other", selectedGroup)
            assertEquals(1, finished)
            shown = true
        }
        other.assertIsDisplayed().assertIsSelected()
    }

    @Test fun systemPromptDirectoryExposesOnlySoulAndWorkspace() {
        var soul = 0
        var workspace = 0
        compose.setContent {
            MinisTheme {
                com.openminis.app.ui.settings.PromptSettingsScreen(
                    onBack = {}, onIdentityClick = { soul++ }, onWorkspaceInstructionsClick = { workspace++ },
                )
            }
        }
        compose.onNodeWithText("SOUL").assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.settings_workspace_instructions))
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, soul); assertEquals(1, workspace) }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun particleThinkingSliderRetainsAllTierLabelsAndAccessibleValue() {
        compose.setContent {
            MinisTheme {
                com.openminis.app.ui.chat.ComposerEffortSlider(
                    levels = listOf(com.openminis.app.data.model.ThinkingLevel.OFF,
                        com.openminis.app.data.model.ThinkingLevel.LOW,
                        com.openminis.app.data.model.ThinkingLevel.HIGH),
                    current = com.openminis.app.data.model.ThinkingLevel.HIGH,
                    enabled = false,
                    onSelect = { error("disabled slider must not commit") },
                )
            }
        }
        compose.onNodeWithContentDescription("Thinking effort: High").assertIsDisplayed()
    }

    @Test fun composerSendPaletteIsNeutralInLightAndDark() {
        var dark by mutableStateOf(false)
        var active = Color.Unspecified
        var inactive = Color.Unspecified
        compose.setContent {
            MinisTheme(darkTheme = dark) {
                val palette = ChatColors
                SideEffect { active = palette.sendButton; inactive = palette.sendButtonDisabled }
                com.openminis.app.ui.chat.ComposerSendButton(canActivate = true, onSend = {})
            }
        }
        val light = compose.runOnIdle {
            assertEquals(active.red, active.green, 0.01f)
            assertEquals(active.green, active.blue, 0.01f)
            assertEquals(active.red, inactive.red, 0.01f)
            assertEquals(0.10f, inactive.alpha, 0.01f)
            dark = true
            active
        }
        compose.runOnIdle {
            assertNotEquals(light, active)
            assertEquals(active.red, active.green, 0.01f)
            assertEquals(active.green, active.blue, 0.01f)
            assertEquals(active.red, inactive.red, 0.01f)
            assertEquals(0.10f, inactive.alpha, 0.01f)
        }
    }

    @Test fun nativePaletteAndFontScaleFollowAppOverride() {
        var dark by mutableStateOf(false)
        var page = Color.Unspecified
        var card = Color.Unspecified
        var composer = Color.Unspecified
        var field = Color.Unspecified
        var fontSize = 0f
        var densityRatio = 0f
        var fontScaleRatio = 0f
        compose.setContent {
            val systemDensity = LocalDensity.current
            MinisTheme(darkTheme = dark, fontScale = 1.25f) {
                val appDensity = LocalDensity.current
                val colors = MiuixTheme.colorScheme
                val pageColor = colors.surface
                val cardColor = colors.surfaceContainer
                val fieldColor = colors.secondaryContainer
                val inputColor = ChatColors.inputBg
                val nativeFontSize = MiuixTheme.textStyles.main.fontSize.value
                SideEffect {
                    page = pageColor
                    card = cardColor
                    field = fieldColor
                    composer = inputColor
                    fontSize = nativeFontSize
                    densityRatio = appDensity.density / systemDensity.density
                    fontScaleRatio = appDensity.fontScale / systemDensity.fontScale
                }
                Text("Themed content")
            }
        }
        val lightPage = compose.runOnIdle {
            assertNotEquals(page, card)
            assertNotEquals(page, composer)
            assertNotEquals(card, field)
            dark = true
            page
        }
        compose.runOnIdle {
            assertNotEquals(lightPage, page)
            assertNotEquals(page, card)
            assertNotEquals(page, composer)
            assertNotEquals(card, field)
            assertEquals(21.25f, fontSize, 0.01f)
            assertEquals(0.92f, densityRatio, 0.001f)
            assertEquals(1f, fontScaleRatio, 0.001f)
        }
    }
}
