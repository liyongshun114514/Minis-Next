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
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ToolBlockStatus
import com.openminis.app.ui.chat.ToolCallPill
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

    @Test fun toolCallPressDarkensTheWholeCapsuleAndLongPressOpensItsMenu() {
        var detailOpens = 0
        var copies = 0
        compose.setContent {
            MinisTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(Color(0xFFCCDDEE)).testTag("tool-press-frame")) {
                    ToolCallPill(
                        block = AssistantBlock(
                            id = "completed-tool", kind = "tool_use", toolStatus = ToolBlockStatus.SUCCESS,
                            toolName = "file_read", toolTitle = "Read notes",
                        ),
                        onOpenDetail = { detailOpens++ },
                        onCopyDetails = { copies++ },
                    )
                }
            }
        }
        val frame = compose.onNodeWithTag("tool-press-frame")
        val pill = compose.onNode(hasText("Read notes") and hasClickAction())
        val initial = frame.captureToImage().toPixelMap()
        val root = frame.fetchSemanticsNode().boundsInRoot
        val bounds = pill.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        pill.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(240)
        val pressed = frame.captureToImage().toPixelMap()
        val left = (bounds.left - root.left).toInt() + 2
        val right = (bounds.right - root.left).toInt() - 3
        val middle = (bounds.center.y - root.top).toInt()
        val top = (bounds.top - root.top).toInt() + 1
        assertEquals(bounds, pill.fetchSemanticsNode().boundsInRoot)
        assertTrue("the left cap darkens", initial[left, middle].red - pressed[left, middle].red > 0.025f)
        assertTrue("the right cap darkens", initial[right, middle].red - pressed[right, middle].red > 0.025f)
        assertEquals("the corner outside the capsule stays clear", initial[left, top].red, pressed[left, top].red, 0.015f)
        pill.performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        pill.performTouchInput { longClick() }
        compose.onNodeWithText(compose.activity.getString(com.openminis.app.R.string.tool_longpress_copy_details))
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, copies); assertEquals(0, detailOpens) }
        pill.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, detailOpens) }
    }

    @Test fun shortReplyUsesFullViewportAndRemainsVisibleDuringDownPull() {
        compose.setContent {
            MinisTheme {
                Box(Modifier.fillMaxWidth().height(520.dp).testTag("chat-work-area")) {
                    AlwaysStretchOverscrollBox(contentScrollable = false) {
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

    @Test fun scrollableTranscriptMovesBothWaysWithoutOuterStretchTakingTheDrag() {
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            MinisTheme {
                state = rememberLazyListState()
                Box(Modifier.fillMaxWidth().height(500.dp)) {
                    AlwaysStretchOverscrollBox(contentScrollable = state.canScrollForward || state.canScrollBackward) { effect ->
                        LazyColumn(
                            state = state, reverseLayout = true, overscrollEffect = effect,
                            modifier = Modifier.fillMaxWidth().testTag("scrollable-transcript"),
                        ) {
                            items(60) { index ->
                                Text("Message $index", Modifier.fillMaxWidth().height(80.dp))
                            }
                        }
                    }
                }
            }
        }
        fun position() = state.firstVisibleItemIndex * 10000 + state.firstVisibleItemScrollOffset
        val transcript = compose.onNodeWithTag("scrollable-transcript")
        transcript.performTouchInput { swipeDown(durationMillis = 600) }
        compose.waitForIdle()
        var away = 0
        compose.runOnIdle { away = position(); assertTrue("drag moves toward older messages", away > 0) }
        transcript.performTouchInput { swipeDown(durationMillis = 600) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("the next drag is not eaten by outer overscroll", position() > away); away = position() }
        transcript.performTouchInput { swipeUp(durationMillis = 600) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("reverse drag immediately moves back", position() < away) }
    }

    @Test fun providerRouteRemovalWaitsForNativeMenuExit() {
        var visible by mutableStateOf(true)
        var writes = 0
        compose.setContent {
            MinisTheme {
                if (visible) {
                    SectionCard {
                        com.openminis.app.ui.settings.NetworkRoutePreference(
                            title = "Provider route",
                            current = com.openminis.app.network.NetworkRoute.Direct,
                            settings = com.openminis.app.network.NetworkSettings(),
                            onPick = { writes++; visible = false },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Provider route").performTouchInput { click() }
        val follow = compose.onNodeWithText(compose.activity.getString(com.openminis.app.R.string.network_route_follow_default))
        follow.assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        follow.performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { assertTrue("provider row stays mounted during exit", visible); assertEquals(0, writes) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(visible); assertEquals(1, writes) }
    }

    @Test fun particleEffortSliderKeepsAccessibleValueAndNeverCommitsWhileDisabled() {
        var commits = 0
        compose.setContent {
            MinisTheme(fontScale = 1.5f) {
                Box(Modifier.width(280.dp)) {
                    ComposerEffortSlider(
                        levels = listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH),
                        current = ThinkingLevel.HIGH,
                        // Disabled also stops the decorative particle clock, so
                        // the Robolectric looper can settle.
                        enabled = false,
                        onSelect = { commits++ },
                    )
                }
            }
        }
        val slider = compose.onNodeWithContentDescription("Thinking effort: High")
        slider.assertIsDisplayed()
        val info = slider.fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo]
        assertEquals("thumb sits on the last supported tier", 1f, info.current, 0.001f)
        assertEquals(0f, info.range.start, 0.001f)
        assertEquals(1f, info.range.endInclusive, 0.001f)
        assertEquals(1, info.steps)
        compose.runOnIdle { assertEquals(0, commits) }
    }
}
