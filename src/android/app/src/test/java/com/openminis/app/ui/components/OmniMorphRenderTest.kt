package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSwitchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real composables, real callbacks, real popup windows — the wiring of the two
 * container transforms, not a pixel/motion comparison.
 *
 * What is asserted here is what the unit tests cannot: that a tapped settings row
 * dispatches its unchanged callback *and* leaves a measured hand-off for the
 * destination page, that the destination consumes it and disposes into a return
 * hand-off, that a toggle row does not, and that a menu's items stay clickable,
 * dismissable and mounted only for as long as its clock says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OmniMorphRenderTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun clearHandOffs() {
        OmniMorphBridge.clear()
    }

    @Test
    fun settingsRowDispatchesItsCallbackAndRecordsItsMeasuredRect() {
        var clicks = 0
        compose.setContent {
            SettingsRow(
                title = "Manage providers",
                icon = Icons.Outlined.Description,
                onClick = { clicks++ },
            )
        }
        compose.onNodeWithText("Manage providers").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, clicks) }

        compose.onNodeWithText("Manage providers").performClick()

        compose.runOnIdle {
            assertEquals("the caller's onClick must run unchanged", 1, clicks)
            val handOff = OmniMorphBridge.consumeOpen()
            assertNotNull("the row must hand its bounds to the destination", handOff)
            assertTrue("bounds must be measured, not zero", handOff!!.bounds.width > 0f)
            assertTrue("bounds must be measured, not zero", handOff.bounds.height > 0f)
            assertEquals(OmniMorphMotion.RowSourceRadius, handOff.cornerRadius)
        }
    }

    @Test
    fun toggleRowTogglesWithoutLeavingAContainerHandOff() {
        var checked by mutableStateOf(false)
        compose.setContent {
            SettingsSwitchRow(
                title = "Encryption",
                checked = checked,
                onCheckedChange = { checked = it },
            )
        }
        compose.onNodeWithText("Encryption").performClick()
        compose.runOnIdle {
            assertTrue("the switch must still toggle", checked)
            assertNull(
                "a row that does not navigate must not leave a hand-off",
                OmniMorphBridge.consumeOpen(),
            )
        }
    }

    @Test
    fun destinationPageConsumesTheHandOffAndDisposesIntoAReturn() {
        OmniMorphBridge.recordOpen(
            OmniMorphSource(
                bounds = Rect(0f, 300f, 400f, 380f),
                cornerRadius = 22.dp,
                color = Color.White,
            ),
        )
        var show by mutableStateOf(true)
        var backs = 0
        compose.setContent {
            if (show) {
                SettingsScaffold(title = "Providers", onBack = { backs++ }) {
                    Text("provider list")
                }
            } else {
                Box {}
            }
        }
        compose.onNodeWithText("Providers").assertIsDisplayed()
        compose.onNodeWithText("provider list").assertIsDisplayed()
        // The expansion completes inside 300ms; the page must stay interactive.
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("provider list").assertIsDisplayed()

        // Back is the caller's own lambda, unchanged...
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }

        // ...and leaving the composition records the return hand-off for the page
        // underneath, which is what "cancel / back restores" is built on.
        compose.runOnIdle { show = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotNull(
                "the dismissed page must hand the row rect back",
                OmniMorphBridge.consumeReturn(),
            )
        }
    }

    @Test
    fun menuOpensWithItsItemsClickableAndReturnsBeforeUnmounting() {
        // Test the middle of the return, not the post-idle state: the default
        // clock drains every finite animation before assertIsDisplayed runs.
        compose.mainClock.autoAdvance = false
        var expanded by mutableStateOf(false)
        var dismissals = 0
        var itemClicks = 0
        compose.setContent {
            MinisMenu(expanded = expanded, onDismissRequest = { dismissals++ }) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { itemClicks++ },
                )
            }
        }
        compose.runOnIdle {
            assertFalse(expanded)
            assertEquals(0, itemClicks)
        }

        compose.runOnIdle { expanded = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Delete").assertIsDisplayed()

        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals(1, itemClicks) }

        // Closing keeps the popup window alive for the 500ms return, then
        // releases it: the item must be gone only after that.
        compose.runOnIdle { expanded = false }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.runOnIdle { assertEquals("dismissal callback is not invented", 0, dismissals) }
    }

    @Test
    fun contextMenuSettlesFasterThanAStandardMenu() {
        var expanded by mutableStateOf(false)
        compose.setContent {
            MinisMenu(
                expanded = expanded,
                onDismissRequest = {},
                alignEnd = true,
            ) {
                DropdownMenuItem(text = { Text("Context action") }, onClick = {})
            }
        }
        compose.runOnIdle { expanded = true }
        // 200ms context clock: settled well before the 450ms standard clock.
        compose.mainClock.advanceTimeBy(230)
        compose.onNodeWithText("Context action").assertIsDisplayed()

        compose.runOnIdle { expanded = false }
        compose.mainClock.advanceTimeBy(230)
        compose.onNodeWithText("Context action").assertDoesNotExist()
    }

    @Test
    fun settingsTopBarRendersTheLargeTitleAndKeepsItsActions() {
        var actions = 0
        compose.setContent {
            OmniSettingsTopBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Outlined.Description, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { actions++ }) {
                        Icon(Icons.Outlined.Description, contentDescription = "Add")
                    }
                },
            )
        }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add").performClick()
        compose.runOnIdle { assertEquals(1, actions) }
    }
}
