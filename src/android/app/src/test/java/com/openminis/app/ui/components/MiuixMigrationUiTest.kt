package com.openminis.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.openminis.app.ui.navigation.MainDestination
import com.openminis.app.ui.navigation.MainNavigationLayout
import com.openminis.app.ui.settings.ImageEndpointLabel
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import androidx.compose.runtime.*
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

    @Test fun mainDockSwitchesTabsRestoresStateAndLeavesDetailRoutesFullScreen() {
        compose.setContent {
            MinisTheme {
                val nav = rememberNavController()
                MainNavigationLayout(nav) {
                    NavHost(nav, startDestination = MainDestination.Home.route) {
                        composable(MainDestination.Home.route) { Text("Conversations page") }
                        composable(MainDestination.Agents.route) { Text("Agents page") }
                        composable(MainDestination.Trajectory.route) {
                            var range by rememberSaveable { mutableStateOf("7 days") }
                            MinisButton(onClick = { range = "30 days" }) { Text(range) }
                        }
                        composable(MainDestination.Settings.route) {
                            MinisButton(onClick = { nav.navigate("sample_detail") }) { Text("Open detail") }
                        }
                        composable("sample_detail") {
                            MinisButton(onClick = { nav.popBackStack() }) { Text("Close detail") }
                        }
                    }
                }
            }
        }
        fun tab(destination: MainDestination) = compose.onNodeWithText(compose.activity.getString(destination.label))
        tab(MainDestination.Home).assertIsSelected()
        tab(MainDestination.Trajectory).performClick().assertIsSelected()
        compose.onNodeWithText("7 days").performClick()
        tab(MainDestination.Agents).performClick().assertIsSelected()
        compose.onNodeWithText("Agents page").assertIsDisplayed()
        tab(MainDestination.Trajectory).performClick().assertIsSelected()
        compose.onNodeWithText("30 days").assertIsDisplayed()
        tab(MainDestination.Settings).performClick().assertIsSelected()
        compose.onNodeWithText("Open detail").performClick()
        tab(MainDestination.Home).assertDoesNotExist()
        compose.onNodeWithText("Close detail").performClick()
        tab(MainDestination.Settings).assertIsSelected()
        tab(MainDestination.Home).performClick().assertIsSelected()
        compose.onNodeWithText("Conversations page").assertIsDisplayed()
    }

    @Config(qualifiers = "w900dp-h700dp")
    @Test fun wideMainNavigationUsesLeadingRail() {
        compose.setContent {
            MinisTheme {
                MainNavigationLayout(MainDestination.Home, onSelect = {}) { Text("Workspace") }
            }
        }
        val home = compose.onNodeWithText(compose.activity.getString(R.string.main_nav_home))
        val activity = compose.onNodeWithText(compose.activity.getString(R.string.usage_title))
        val homeBounds = home.assertIsSelected().getUnclippedBoundsInRoot()
        val activityBounds = activity.assertIsDisplayed().getUnclippedBoundsInRoot()
        val workspaceBounds = compose.onNodeWithText("Workspace").getUnclippedBoundsInRoot()
        assertTrue(homeBounds.top < activityBounds.top)
        assertTrue(homeBounds.right < workspaceBounds.left)
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
        assertEquals(labels[0].height, labels[1].height)
        assertEquals(labels[0].top, labels[1].top)
        assertEquals(labels[1].top, labels[2].top)
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
