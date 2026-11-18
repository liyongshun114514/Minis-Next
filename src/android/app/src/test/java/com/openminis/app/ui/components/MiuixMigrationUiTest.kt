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
import com.openminis.app.ui.settings.ImageEndpointLabel
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
                    MinisButton(onClick = { clicks++ }, modifier = Modifier.testTag("active")) { Text("Save") }
                    MinisButton(onClick = { clicks += 100 }, enabled = false, modifier = Modifier.testTag("disabled")) { Text("Disabled") }
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
                OutlinedTextField(value, { value = it; result = it }, modifier = Modifier.testTag("field"),
                    label = { Text("API key") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
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
                Checkbox(value, { value = it; checked = it }, Modifier.testTag("checkbox"))
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
                if (shown) AlertDialog(
                    onDismissRequest = { shown = false },
                    title = { Text("Miuix confirmation") }, text = { Text("Dialog body") },
                    confirmButton = { MinisButton(onClick = { accepted = true; shown = false }) { Text("Accept") } },
                )
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
                if (shown) ModalBottomSheet(onDismissRequest = { shown = false; closed = true }) {
                    Text("Sheet content")
                    MinisButton(onClick = { shown = false; closed = true }) { Text("Close sheet") }
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
                        MinisButton(onClick = { expanded = true }) { Text("More actions") }
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
                        MinisButton(onClick = { expanded = true }) { Text("Show options") }
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
                    OutlinedTextField(value, { value = it; draft = it }, modifier = Modifier.testTag("draft"))
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

    @Test fun providerImageEndpointLabelsFitOneLineAtLargerTextSize() {
        compose.setContent {
            MinisTheme(fontScale = 1.25f) {
                SingleChoiceSegmentedButtonRow {
                    listOf("Auto", "Images API", "Chat").forEachIndexed { index, label ->
                        SegmentedButton(index == 0, onClick = {}, shape = SegmentedButtonDefaults.itemShape(index, 3)) {
                            ImageEndpointLabel(label)
                        }
                    }
                }
            }
        }
        val labels = listOf("Auto", "Images API", "Chat").map {
            compose.onNodeWithText(it).assertIsDisplayed().getUnclippedBoundsInRoot()
        }
        val first = labels.first()
        labels.drop(1).forEach { bounds ->
            assertEquals(first.top, bounds.top)
            assertEquals(first.bottom, bounds.bottom)
        }
    }

    @Test fun nativePaletteAndFontScaleFollowAppOverride() {
        var dark by mutableStateOf(false)
        var lightColor = androidx.compose.ui.graphics.Color.Unspecified
        var darkColor = androidx.compose.ui.graphics.Color.Unspecified
        var fontSize = 0f
        compose.setContent {
            MinisTheme(darkTheme = dark, fontScale = 1.25f) {
                val nativeFontSize = MiuixTheme.textStyles.main.fontSize.value
                SideEffect { fontSize = nativeFontSize }
                val background = MiuixTheme.colorScheme.background
                SideEffect { if (dark) darkColor = background else lightColor = background }
                Text("Themed content")
            }
        }
        compose.runOnIdle { dark = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotEquals(lightColor, darkColor)
            assertEquals(21.25f, fontSize, 0.01f)
        }
    }
}
