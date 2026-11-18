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
import com.openminis.app.ui.chat.ChatGlassIconButton
import com.openminis.app.ui.chat.ChatGlassTopBar
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                        ChatGlassIconButton(null, { backClicks++ }) { Text("Back") }
                    },
                    actions = {
                        ChatGlassIconButton(null, { moreClicks++ }) { Text("More") }
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
                            backdrop = null,
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

    @Test fun nativePaletteAndFontScaleFollowAppOverride() {
        var dark by mutableStateOf(false)
        var page = Color.Unspecified
        var card = Color.Unspecified
        var composer = Color.Unspecified
        var field = Color.Unspecified
        var fontSize = 0f
        compose.setContent {
            MinisTheme(darkTheme = dark, fontScale = 1.25f) {
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
        }
    }
}
