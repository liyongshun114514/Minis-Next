package com.openminis.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.chat.AlwaysStretchOverscrollBox
import com.openminis.app.ui.chat.ComposerEffortSlider
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.theme.MinisTheme
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Real native Miuix surfaces. Source contracts additionally bind these owners to production. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MiuixSurfaceRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun glassSheetRetainsContentAndRecordingUntilExitAndCanReopen() {
        var show by mutableStateOf(false)
        var finished = 0
        var scene: MinisSceneBackdrop? = null
        compose.setContent {
            MinisTheme {
                val owner = LocalMinisSceneBackdrop.current
                SideEffect { scene = owner }
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                    TextButton(text = "Open glass sheet", onClick = { show = true })
                }
                MinisBottomSheet(show = show, onDismissRequest = { show = false },
                    onDismissFinished = { finished++ }, insideMargin = androidx.compose.ui.unit.DpSize.Zero) {
                    val dismiss = LocalDismissState.current
                    Column(Modifier.fillMaxWidth().height(180.dp).testTag("glass-sheet-body")) {
                        Text("Choose a feature")
                        TextButton(text = "Close glass sheet", onClick = { dismiss?.invoke() })
                    }
                }
            }
        }
        repeat(2) { cycle ->
            compose.onNodeWithText("Open glass sheet").performClick()
            compose.onNodeWithTag("glass-sheet-body").assertIsDisplayed()
            compose.runOnIdle { assertTrue(scene?.recording == true) }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Close glass sheet").performClick()
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithTag("glass-sheet-body").assertExists()
            compose.runOnIdle { assertEquals(cycle, finished); assertTrue(scene?.recording == true) }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.onNodeWithTag("glass-sheet-body").assertDoesNotExist()
            compose.runOnIdle { assertEquals(cycle + 1, finished); assertFalse(scene?.recording == true) }
        }
    }

    @Test fun nativeGroupedRowsKeepPressInsideCornersAndOnDividerSeams() {
        compose.setContent {
            MinisTheme {
                Box(Modifier.fillMaxWidth().background(Color(0xFFCCDDEE)).testTag("press-frame")) {
                    SectionCard {
                        SettingsRow(title = "Top segment", onClick = {}, onLongClick = {}, minHeight = 64.dp)
                        SettingsRow(title = "Middle segment", onClick = {}, onLongClick = {}, minHeight = 64.dp)
                        SettingsRow(title = "Bottom segment", onClick = {}, onLongClick = {}, showDivider = false, minHeight = 64.dp)
                    }
                }
            }
        }
        val frame = compose.onNodeWithTag("press-frame")
        val initial = frame.captureToImage().toPixelMap()
        val root = frame.fetchSemanticsNode().boundsInRoot
        for (title in listOf("Top segment", "Middle segment", "Bottom segment")) {
            val row = compose.onNode(hasText(title) and hasClickAction())
            val resting = row.fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            row.performTouchInput { down(center); advanceEventTime(700) }
            compose.mainClock.advanceTimeBy(900)
            assertEquals("press never scales away from the separator", resting, row.fetchSemanticsNode().boundsInRoot)
            val down = frame.captureToImage().toPixelMap()
            val x = (resting.left - root.left).toInt() + 1
            val yTop = (resting.top - root.top).toInt() + 1
            val yBottom = (resting.bottom - root.top).toInt() - 2
            fun difference(y: Int): Float {
                val a = initial[x, y]; val b = down[x, y]
                return kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.green - b.green) + kotlin.math.abs(a.blue - b.blue)
            }
            if (title == "Top segment") assertTrue("top outside corner remains page colour", difference(yTop) < 0.015f)
            if (title == "Bottom segment") assertTrue("bottom outside corner remains page colour", difference(yBottom) < 0.015f)
            if (title != "Top segment") assertTrue("square seam press reaches row top", difference(yTop) > 0.03f)
            if (title != "Bottom segment") assertTrue("square seam press reaches divider", difference(yBottom) > 0.03f)
            row.performTouchInput { up() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }

    @Test fun shortReplyUsesFullViewportAndRemainsVisibleDuringDownPull() {
        compose.setContent {
            MinisTheme {
                Box(Modifier.fillMaxWidth().height(520.dp).testTag("chat-work-area")) {
                    AlwaysStretchOverscrollBox {
                        LazyColumn(
                            state = rememberLazyListState(), reverseLayout = true,
                            contentPadding = PaddingValues(top = 80.dp, bottom = 140.dp),
                            modifier = Modifier.fillMaxSize().testTag("full-message-viewport"),
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
                            overscrollEffect = it,
                        ) {
                            item { Text("Reply final line stays visible", Modifier.testTag("reply-tail")) }
                            item { Text("Minis Next") }
                            item { Text("Hello") }
                        }
                    }
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp).background(MiuixTheme.colorScheme.surfaceContainer)) {
                        Text("Floating composer")
                    }
                }
            }
        }
        val viewport = compose.onNodeWithTag("full-message-viewport")
        val work = compose.onNodeWithTag("chat-work-area").fetchSemanticsNode().boundsInRoot
        assertEquals(work.height, viewport.fetchSemanticsNode().boundsInRoot.height, 0.5f)
        compose.onNodeWithTag("reply-tail").assertIsDisplayed()
        viewport.performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 72f), delayMillis = 200)
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("reply-tail").assertIsDisplayed()
        val image = viewport.captureToImage().toPixelMap()
        assertTrue("full viewport is drawn during pull", image.height > 0)
        viewport.performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithTag("reply-tail").assertIsDisplayed()
    }

    @Test fun nativeEffortSliderAccessibleSelectionPersistsAndDisabledNeverCommits() {
        var level by mutableStateOf(ThinkingLevel.LOW)
        var enabled by mutableStateOf(true)
        var commits = 0
        compose.setContent {
            MinisTheme(fontScale = 1.5f) {
                Box(Modifier.width(280.dp)) {
                    ComposerEffortSlider(
                        levels = listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH),
                        current = level, enabled = enabled,
                        onSelect = { level = it; commits++ },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Thinking effort: Low")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(2f) }
        compose.runOnIdle { assertEquals(ThinkingLevel.HIGH, level); assertEquals(1, commits) }
        compose.onNodeWithText("Off").assertIsDisplayed()
        compose.onNodeWithText("High").assertIsDisplayed()
        compose.runOnIdle { enabled = false }
        compose.onNodeWithContentDescription("Thinking effort: High").assertIsNotEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithText("Off").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(ThinkingLevel.HIGH, level); assertEquals(1, commits) }
    }
}
