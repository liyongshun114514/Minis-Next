package com.openminis.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.SettingsSection
import com.openminis.app.ui.sessions.OmniDockItem
import com.openminis.app.ui.sessions.OmniFloatingDock
import com.openminis.app.ui.sessions.OmniSearchCapsule
import com.openminis.app.ui.theme.DarkChatPalette
import com.openminis.app.ui.theme.LightChatPalette
import com.openminis.app.ui.theme.LocalChatPalette
import com.openminis.app.ui.theme.MinisTheme
import com.openminis.app.ui.theme.OmniDarkPalette
import com.openminis.app.ui.theme.OmniLightPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real production composables and tap callbacks, not pixel/motion parity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OmniPresentationRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dockDoesNotDispatchUntilTapped() {
        var calls = 0
        compose.setContent {
            MinisTheme(darkTheme = false) {
                OmniFloatingDock(listOf(OmniDockItem(Icons.Outlined.Forum, "Existing action", onClick = { calls++ })), -1)
            }
        }
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("Existing action").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun searchRetainsQueryAndDismissCallbacks() {
        var edited: String? = null
        var dismissed = false
        compose.setContent {
            MinisTheme(darkTheme = true) {
                OmniSearchCapsule("original", { edited = it }, false, { dismissed = true }, "Search", "Dismiss")
            }
        }
        compose.onNodeWithText("original").performTextReplacement("updated")
        compose.runOnIdle { assertEquals("updated", edited); assertFalse(dismissed) }
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun settingsCardKeepsButtonCallback() {
        var clicked = false
        compose.setContent {
            MinisTheme(darkTheme = false) {
                SettingsSection("Group label") {
                    MinisButton(onClick = { clicked = true }) { Text("Existing setting") }
                }
            }
        }
        compose.onNodeWithText("Group label").assertIsDisplayed()
        compose.onNodeWithText("Existing setting").performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }

    @Test fun explicitDarkThemePublishesTheSameNeutralPaletteEverywhere() {
        compose.setContent {
            MinisTheme(darkTheme = true) {
                val scheme = MaterialTheme.colorScheme
                val chat = LocalChatPalette.current
                SideEffect {
                    assertEquals(OmniDarkPalette.accentPrimary, scheme.primary)
                    assertEquals(OmniDarkPalette.pageBackground, scheme.background)
                    assertEquals(OmniDarkPalette.surfacePrimary, scheme.surfaceBright)
                    assertEquals(OmniDarkPalette.surfaceElevated, chat.userBubble)
                    assertEquals(DarkChatPalette, chat)
                }
                Text("Dark")
            }
        }
        compose.onNodeWithText("Dark").assertIsDisplayed()
    }

    @Test fun explicitLightThemePublishesTheSameNeutralPaletteEverywhere() {
        compose.setContent {
            MinisTheme(darkTheme = false) {
                val scheme = MaterialTheme.colorScheme
                val chat = LocalChatPalette.current
                SideEffect {
                    assertEquals(OmniLightPalette.accentPrimary, scheme.primary)
                    assertEquals(OmniLightPalette.pageBackground, scheme.background)
                    assertEquals(OmniLightPalette.surfacePrimary, scheme.surfaceBright)
                    assertEquals(OmniLightPalette.surfaceElevated, chat.userBubble)
                    assertEquals(LightChatPalette, chat)
                }
                Text("Light")
            }
        }
        compose.onNodeWithText("Light").assertIsDisplayed()
    }
}
