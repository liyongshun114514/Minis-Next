package com.openminis.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
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
