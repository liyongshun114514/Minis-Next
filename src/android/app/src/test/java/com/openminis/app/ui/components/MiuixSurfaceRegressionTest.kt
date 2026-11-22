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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
import com.openminis.app.ui.chat.chatStatusBackground
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
import top.yukonga.miuix.kmp.blur.layerBackdrop
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

    @Test fun insetGlassDialogRetainsContentThroughExitAndUsesFilledEqualActions() {
        var show by mutableStateOf(false)
        var finished = 0
        var confirmed = 0
        var scene: MinisSceneBackdrop? = null
        compose.setContent {
            MinisTheme {
                val owner = LocalMinisSceneBackdrop.current
                SideEffect { scene = owner }
                TextButton(text = "Open inset dialog", onClick = { show = true })
                MinisDialog(show = show, title = "Add variable", onDismissRequest = { show = false },
                    onDismissFinished = { finished++ }) {
                    Column(Modifier.fillMaxWidth().testTag("inset-dialog-body")) {
                        Text("Variable draft")
                        MinisDialogActions(
                            dismissButton = {
                                MinisTextButton("Cancel draft", onClick = { show = false }, modifier = Modifier.fillMaxWidth())
                            },
                            confirmButton = {
                                MinisTextButton("Save draft", onClick = { confirmed++ },
                                    modifier = Modifier.fillMaxWidth(), enabled = false)
                            },
                        )
                    }
                }
            }
        }
        repeat(2) { cycle ->
            compose.onNodeWithText("Open inset dialog").performClick()
            compose.onNodeWithTag("inset-dialog-body").assertIsDisplayed()
            val cancel = compose.onNodeWithText("Cancel draft")
            val save = compose.onNodeWithText("Save draft").assertIsNotEnabled()
            val left = cancel.fetchSemanticsNode().boundsInRoot
            val right = save.fetchSemanticsNode().boundsInRoot
            assertEquals(left.width, right.width, 1f)
            assertEquals(left.center.y, right.center.y, 1f)
            assertTrue(left.right < right.left)
            assertTrue(cancel.captureToImage().width > 0)
            compose.runOnIdle {
                assertTrue(scene?.recording == true)
                assertEquals(0, confirmed)
            }
            compose.mainClock.autoAdvance = false
            cancel.performClick()
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithTag("inset-dialog-body").assertExists()
            compose.runOnIdle { assertEquals(cycle, finished); assertTrue(scene?.recording == true) }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.onNodeWithTag("inset-dialog-body").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(cycle + 1, finished)
                assertFalse(scene?.recording == true)
            }
        }
    }

    @Test fun menuAnchorUsesTheActualTapAndFallsBackToCentreForKeyboard() {
        var anchor: MinisMenuAnchor? = null
        var lastClick: androidx.compose.ui.geometry.Offset? = null
        var movedDown by mutableStateOf(0.dp)
        compose.setContent {
            MinisTheme {
                val target = rememberMinisMenuAnchor()
                SideEffect { anchor = target }
                TextButton(
                    text = "Choose format", onClick = { lastClick = target.clickPosition() },
                    modifier = Modifier.offset(y = movedDown).width(240.dp).height(64.dp).minisMenuAnchor(target).testTag("format-anchor"),
                )
            }
        }
        val button = compose.onNodeWithTag("format-anchor")
        button.performTouchInput { click(androidx.compose.ui.geometry.Offset(13f, 17f)) }
        compose.runOnIdle {
            val coordinates = requireNotNull(anchor?.coordinates)
            val expected = coordinates.localToWindow(androidx.compose.ui.geometry.Offset(13f, 17f))
            assertEquals(expected.x, requireNotNull(lastClick).x, 1f)
            assertEquals(expected.y, requireNotNull(lastClick).y, 1f)
        }
        compose.runOnIdle { movedDown = 100.dp }
        compose.waitForIdle()
        compose.runOnIdle {
            val coordinates = requireNotNull(anchor?.coordinates)
            val expected = coordinates.localToWindow(androidx.compose.ui.geometry.Offset(13f, 17f))
            val followed = requireNotNull(anchor?.trackedClickPosition())
            assertEquals("original local touch X follows the control", expected.x, followed.x, 1f)
            assertEquals("original local touch Y follows the control", expected.y, followed.y, 1f)
        }
        button.performClick()
        compose.runOnIdle {
            val coordinates = requireNotNull(anchor?.coordinates)
            val expected = coordinates.localToWindow(androidx.compose.ui.geometry.Offset(
                coordinates.size.width / 2f, coordinates.size.height / 2f,
            ))
            assertEquals(expected.x, requireNotNull(lastClick).x, 1f)
            assertEquals(expected.y, requireNotNull(lastClick).y, 1f)
        }
    }

    @Test fun horizontalThinkingPopupCanCloseAndReopenWithoutChangingTheLevel() {
        var shown by mutableStateOf(false)
        var commits = 0
        var tap: androidx.compose.ui.geometry.Offset? = null
        var actionsTop: Float? by mutableStateOf(null)
        var editorHeight by mutableStateOf(220.dp)
        var keyboardLift by mutableStateOf(0.dp)
        compose.setContent {
            MinisTheme {
                val anchor = rememberMinisMenuAnchor()
                val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.toPx() }
                Box(Modifier.fillMaxSize().testTag("effort-test-window")) {
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = keyboardLift)
                        .fillMaxWidth().testTag("effort-composer")) {
                        Box(Modifier.fillMaxWidth().height(editorHeight).testTag("effort-editor"))
                        Row(Modifier.fillMaxWidth().testTag("effort-actions")
                            .onGloballyPositioned { actionsTop = it.positionInWindow().y },
                            horizontalArrangement = Arrangement.End) {
                            TextButton("Open effort", onClick = { tap = anchor.clickPosition(); shown = true },
                                modifier = Modifier.height(52.dp).minisMenuAnchor(anchor).testTag("effort-trigger"))
                        }
                    }
                    com.openminis.app.ui.chat.ThinkingLevelSheet(
                        show = shown, currentLevel = ThinkingLevel.OFF, availableLevels = emptyList(),
                        onSelect = { commits++ }, onDismiss = { shown = false },
                        tapPositionInWindow = anchor.trackedClickPosition() ?: tap,
                        bottomBoundaryInWindow = actionsTop?.minus(gapPx),
                    )
                }
            }
        }
        repeat(2) { cycle ->
            compose.runOnIdle { keyboardLift = 96.dp }
            val trigger = compose.onNodeWithTag("effort-trigger")
            val touch = if (cycle == 0) androidx.compose.ui.geometry.Offset(13f, 17f)
                else androidx.compose.ui.geometry.Offset(37f, 23f)
            val triggerBounds = trigger.fetchSemanticsNode().boundsInRoot
            trigger.performTouchInput { click(touch) }
            val marker = compose.onNodeWithTag("minis-menu-tap-origin").fetchSemanticsNode().boundsInRoot
            assertEquals("native transform starts at actual touch X", triggerBounds.left + touch.x, marker.center.x, 2f)
            assertEquals("native transform starts at actual touch Y", triggerBounds.top + touch.y, marker.center.y, 2f)
            val slider = compose.onNodeWithContentDescription("Thinking effort: Off").assertIsDisplayed()
            val bounds = slider.fetchSemanticsNode().boundsInRoot
            val window = compose.onNodeWithTag("effort-test-window").fetchSemanticsNode().boundsInRoot
            assertTrue("effort popup remains horizontal", bounds.width > bounds.height * 3f)
            assertTrue("slider fits the window", bounds.left >= window.left && bounds.right <= window.right)
            val panel = compose.onNodeWithTag("thinking-level-panel").fetchSemanticsNode().boundsInRoot
            val actions = compose.onNodeWithTag("effort-actions").fetchSemanticsNode().boundsInRoot
            val editor = compose.onNodeWithTag("effort-editor").fetchSemanticsNode().boundsInRoot
            assertTrue("whole menu clears the + / model / send row", panel.bottom < actions.top)
            val gapDp = with(compose.activity.resources.displayMetrics) { (actions.top - panel.bottom) / density }
            assertTrue("panel sits just above the action controls", gapDp in 4f..36f)
            assertTrue("panel may cover the editor, not sit above it", panel.top > editor.top)
            assertTrue("panel keeps its natural horizontal size", panel.width > panel.height)
            compose.runOnIdle { editorHeight = 280.dp }
            val after = compose.onNodeWithTag("thinking-level-panel").fetchSemanticsNode().boundsInRoot
            assertEquals("growing the draft must not lift the panel", panel.bottom, after.bottom, 1f)
            compose.runOnIdle { keyboardLift = 0.dp }
            val lowered = compose.onNodeWithTag("thinking-level-panel").fetchSemanticsNode().boundsInRoot
            val loweredActions = compose.onNodeWithTag("effort-actions").fetchSemanticsNode().boundsInRoot
            assertTrue("keyboard dismissal lowers the open menu", lowered.bottom > after.bottom + 40f)
            assertEquals("menu follows the controls by exactly their displacement",
                loweredActions.top - actions.top, lowered.bottom - panel.bottom, 2f)
            assertEquals("menu retains its control-row clearance",
                actions.top - panel.bottom, loweredActions.top - lowered.bottom, 2f)
            slider.assertHeightIsEqualTo(52.dp)
            slider.performTouchInput { click() }
            val close = compose.onNodeWithContentDescription(compose.activity.getString(com.openminis.app.R.string.common_close))
            close.assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Thinking effort: Off").assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, commits) }
        }
    }

    @Test fun modelGroupRowKeepsInlineBadgesAndSmallerTypographyAtLargeFont() {
        val group = com.openminis.app.data.model.ModelGroup(id = "hierarchy", name = "Default")
        val instance = com.openminis.app.data.model.ProviderInstance(
            id = "hierarchy-provider", label = "Provider",
            providerType = com.openminis.app.data.model.ProviderType.openAI,
            credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
        )
        val entry = com.openminis.app.data.model.ModelEntry(
            providerInstanceId = instance.id,
            baseModel = com.openminis.app.data.model.LLMModel("model", "DeepSeek V4.1 Flash", "openai"),
        )
        group.memberEntryIds.add(entry.id)
        val config = com.openminis.app.data.model.ProviderConfig(
            instances = mutableListOf(instance), modelEntries = mutableListOf(entry),
            defaultPrimaryGroupId = group.id,
        )
        var opens = 0
        compose.setContent {
            MinisTheme(fontScale = 1.5f) {
                Box(Modifier.width(320.dp)) {
                    com.openminis.app.ui.settings.GroupRow(group, config, onClick = { opens++ })
                }
            }
        }
        fun bounds(part: String) = compose.onNodeWithTag("group-$part:hierarchy", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val heading = bounds("heading")
        val title = bounds("title")
        val badge = compose.onNodeWithText("Primary", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val summary = bounds("summary")
        val members = bounds("members")
        assertEquals("name and badge remain on the original row", title.center.y, badge.center.y, 1f)
        assertTrue("badge remains after the title", title.right < badge.left)
        assertTrue("summary stays below heading", heading.bottom <= summary.top)
        assertTrue("single-line preview stays below summary", summary.bottom <= members.top)
        val textLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("group-title:hierarchy", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(textLayouts))
            }
        assertEquals("title uses the smaller 16sp native style and follows app font scale", 24f,
            textLayouts.single().layoutInput.style.fontSize.value, 0.01f)
        assertTrue("title remains visually stronger than details", title.height > summary.height)
        compose.onNodeWithText("DeepSeek V4.1 Flash").assertIsDisplayed()
        compose.onNodeWithText(group.name).performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun computerHeaderUsesTheNativeTitleSlotWithoutAnEmptyExtraRow() {
        compose.setContent {
            MinisTheme {
                com.openminis.app.ui.chat.ToolDetailSheet(
                    toolBlocks = listOf(AssistantBlock(
                        id = "computer-gap", kind = "tool_use", toolName = "file_read",
                        toolStatus = ToolBlockStatus.SUCCESS, content = "Tool output",
                    )), initialIndex = 0, onDismiss = {},
                )
            }
        }
        val sheet = compose.onNodeWithTag("computer-sheet", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("computer-header", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val gapDp = with(compose.activity.resources.displayMetrics) { (header.top - sheet.top) / density }
        assertTrue("one native handle plus its header gap", gapDp in 20f..34f)
        compose.onNodeWithText("Minis Computer").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(com.openminis.app.R.string.common_close)).assertIsDisplayed()
    }

    @Test fun ruleEditorUsesAnAnchoredFormatMenuAndSavesOnlyAfterExit() {
        var shown by mutableStateOf(false)
        var saved: com.openminis.app.provider.thinking.ThinkingRule? = null
        val request = com.openminis.app.ui.settings.ThinkingRuleEditorRequest(
            existingId = null, isNew = true,
            seed = com.openminis.app.provider.thinking.ThinkingRule(
                kind = com.openminis.app.provider.thinking.ThinkingRule.Kind.CUSTOM,
                scope = com.openminis.app.provider.thinking.ThinkingRule.Scope.AllModels,
                wireFormat = com.openminis.app.provider.thinking.ThinkingWireFormat.ReasoningEffort(null),
                label = "",
            ),
        )
        compose.setContent {
            MinisTheme {
                TextButton("Open rule", onClick = { shown = true })
                if (shown) com.openminis.app.ui.settings.ThinkingRuleEditorDialog(
                    request = request, onDismiss = { shown = false }, onSave = { saved = it; shown = false },
                )
            }
        }
        compose.onNodeWithText("Open rule").performClick()
        val save = compose.onNodeWithText(compose.activity.getString(com.openminis.app.R.string.common_save))
        save.assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Test rule")
        val format = compose.activity.getString(com.openminis.app.R.string.thinking_fmt_effort_title)
        val nested = compose.activity.getString(com.openminis.app.R.string.thinking_fmt_effort_nested_title)
        compose.onNodeWithText(format).performTouchInput { click(topLeft + androidx.compose.ui.geometry.Offset(8f, 8f)) }
        compose.onNodeWithText(nested).assertIsDisplayed().performClick()
        save.assertIsEnabled()
        compose.mainClock.autoAdvance = false
        save.performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.onNode(hasSetTextAction() and hasText("Test rule")).assertExists()
        compose.runOnIdle { assertNull(saved) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Test rule", saved?.label)
            assertTrue(saved?.wireFormat is com.openminis.app.provider.thinking.ThinkingWireFormat.ReasoningEffortNested)
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

    @Test fun completedToolCapsulesRemainVisibleOnTheRealPageInBothThemes() {
        var dark by mutableStateOf(false)
        compose.setContent {
            MinisTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).testTag("capsule-page")) {
                    ToolCallPill(block = AssistantBlock(
                        id = "visible-tool", kind = "tool_use", toolStatus = ToolBlockStatus.SUCCESS,
                        toolName = "file_read", toolTitle = "Read notes",
                    ))
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            val frame = compose.onNodeWithTag("capsule-page")
            val image = frame.captureToImage().toPixelMap()
            val root = frame.fetchSemanticsNode().boundsInRoot
            val capsule = compose.onNode(hasText("Read notes") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            val x = (capsule.left - root.left).toInt() + 4
            val y = (capsule.center.y - root.top).toInt()
            val fill = image[x, y]
            val page = image[image.width - 2, image.height - 2]
            val difference = kotlin.math.abs(fill.red - page.red) +
                kotlin.math.abs(fill.green - page.green) + kotlin.math.abs(fill.blue - page.blue)
            assertTrue("tool capsule must be visible on page (dark=$night)", difference > 0.09f)
        }
    }

    @Test fun bothStatusRowsKeepComposerFillAcrossThemeAndPreviewChanges() {
        var dark by mutableStateOf(false)
        var preview by mutableStateOf(false)
        compose.setContent {
            MinisTheme(darkTheme = dark) {
                Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface)) {
                    CompositionLocalProvider(com.openminis.app.ui.chat.LocalToolPreviewEnabled provides preview) {
                        com.openminis.app.ui.chat.FloatingToolStatusBar(
                            toolBlocks = listOf(AssistantBlock(
                                id = "shared-fill", kind = "tool_use", toolName = "file_read",
                                toolTitle = "Read notes", toolStatus = ToolBlockStatus.SUCCESS,
                            )),
                            backdrop = null,
                            statusEntry = { Box(Modifier.fillMaxSize().testTag("upper-status-content")) },
                            modifier = Modifier.testTag("shared-status-fill"),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(65.dp)
                        .background(com.openminis.app.ui.theme.ChatColors.inputBg)
                        .testTag("composer-fill"))
                }
            }
        }
        for (night in listOf(false, true)) {
            for (showPreview in listOf(false, true, false)) {
                compose.runOnIdle { dark = night; preview = showPreview }
                compose.onNodeWithTag("upper-status-content").assertIsDisplayed()
                val frame = compose.onNodeWithTag("shared-status-fill")
                val work = frame.captureToImage().toPixelMap()
                val bounds = frame.fetchSemanticsNode().boundsInRoot
                val upper = compose.onNodeWithTag("upper-status-content").fetchSemanticsNode().boundsInRoot
                val title = compose.onNodeWithText("Read notes").fetchSemanticsNode().boundsInRoot
                assertTrue("both rows stay separate after a preview toggle", upper.bottom < title.top)
                val composer = compose.onNodeWithTag("composer-fill").captureToImage().toPixelMap()
                // Right-hand clear area avoids thumbnail, text and pager controls.
                val upperFill = work[work.width * 3 / 4, work.height / 4]
                val lowerFill = work[work.width * 3 / 4, work.height * 3 / 4]
                assertEquals("both row fills must match", upperFill, lowerFill)
                assertEquals("fallback must match composer", composer[composer.width / 2, composer.height / 2], upperFill)
                val gapY = ((upper.bottom - bounds.top) + 1).toInt()
                assertNotEquals("row gap remains the page color", upperFill, work[work.width * 3 / 4, gapY])
            }
        }
    }

    @Test fun twoStatusRowsMatchComposerOverARealRecordedBackdrop() {
        var dark by mutableStateOf(false)
        compose.setContent {
            MinisTheme(darkTheme = dark) {
                val backdrop = top.yukonga.miuix.kmp.blur.rememberLayerBackdrop()
                Box(Modifier.fillMaxSize().testTag("glass-scene")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop)
                        .background(Color(0xFF456789)))
                    Column(Modifier.padding(24.dp)) {
                        for (name in listOf("agent-glass", "work-glass", "input-glass")) {
                            Box(Modifier.fillMaxWidth().height(if (name == "input-glass") 64.dp else 32.5.dp)
                                .chatStatusBackground(backdrop, top.yukonga.miuix.kmp.material.GlassShape(10.dp))
                                .testTag(name))
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            val pixels = listOf("agent-glass", "work-glass", "input-glass").map { tag ->
                val image = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
                image[image.width / 2, image.height / 2]
            }
            for (color in pixels.take(2)) {
                assertEquals(pixels.last().red, color.red, 0.01f)
                assertEquals(pixels.last().green, color.green, 0.01f)
                assertEquals(pixels.last().blue, color.blue, 0.01f)
            }
            val scene = compose.onNodeWithTag("glass-scene").captureToImage().toPixelMap()
            assertEquals("outside rounded panels must retain scene color", Color(0xFF456789), scene[2, scene.height / 2])
        }
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

    @Test fun capturedPageMasksInlineSamplingWhileBarAndOverlayKeepTheirSource() {
        var barHasSource = false
        var bodyHasSource = true
        var overlayHasSource = false
        compose.setContent {
            MinisTheme {
                MinisPageScaffold(topBar = {
                    val source = LocalMinisActionBackdrop.current
                    SideEffect { barHasSource = source != null }
                    Text("Top bar")
                }) {
                    val inline = LocalMinisActionBackdrop.current
                    val overlay = LocalMinisPageBackdrop.current
                    SideEffect {
                        bodyHasSource = inline != null
                        overlayHasSource = overlay != null
                    }
                    Column {
                        repeat(3) { index ->
                            MinisGlassIconButton(onClick = {}) { Text("Member $index") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Member 2").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("bar can sample its sibling page", barHasSource)
            assertFalse("inline glass must not draw the layer containing itself", bodyHasSource)
            assertTrue("out-of-tree sheets retain a source", overlayHasSource)
        }
    }

    @Test fun homeOverlayHasItsOwnBackdropAndSearchRemainsEditable() {
        var sourceInBody = true
        var sourceInOverlay = false
        var query by mutableStateOf("")
        compose.setContent {
            MinisTheme {
                MinisPageScaffold(
                    bodyOverlay = {
                        val source = LocalMinisActionBackdrop.current
                        SideEffect { sourceInOverlay = source != null }
                        MinisGlassSearchField(
                            value = query, onValueChange = { query = it },
                            label = "Search conversations",
                            leadingIcon = { Text("⌕", Modifier.testTag("search-icon")) },
                            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).testTag("glass-search"),
                        )
                    },
                ) {
                    val source = LocalMinisActionBackdrop.current
                    SideEffect { sourceInBody = source != null }
                    Text("Conversation behind the glass")
                }
            }
        }
        compose.onNodeWithText("Search conversations").performTextInput("hello")
        compose.runOnIdle {
            assertEquals("hello", query)
            assertFalse("body must not consume its own capture", sourceInBody)
            assertTrue("floating search samples the separate body", sourceInOverlay)
        }
        val search = compose.onNodeWithTag("glass-search").fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag("search-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val inset = with(compose.activity.resources.displayMetrics) { (icon.left - search.left) / density }
        assertTrue("search icon has an inner left inset", inset >= 15f)
        assertTrue(compose.onNodeWithTag("glass-search").captureToImage().width > 0)
    }

    @Test fun dialogActionsShareOneBottomRowAtLargeFontAndKeepDisabledState() {
        var cancelled = 0
        var confirmed = 0
        compose.setContent {
            MinisTheme(fontScale = 1.5f) {
                Column(Modifier.width(280.dp)) {
                    Text("New group")
                    MinisDialogActions(
                        dismissButton = { TextButton("Cancel", onClick = { cancelled++ }, modifier = Modifier.fillMaxWidth()) },
                        confirmButton = { TextButton("Create group", onClick = { confirmed++ }, enabled = false, modifier = Modifier.fillMaxWidth()) },
                    )
                }
            }
        }
        val cancel = compose.onNodeWithText("Cancel")
        val confirm = compose.onNodeWithText("Create group")
        val left = cancel.fetchSemanticsNode().boundsInRoot
        val right = confirm.fetchSemanticsNode().boundsInRoot
        assertTrue(left.right < right.left)
        assertEquals("both actions are on one row", left.center.y, right.center.y, 1f)
        assertEquals("both slots get equal width", left.width, right.width, 1f)
        confirm.assertIsNotEnabled()
        cancel.performClick()
        compose.runOnIdle { assertEquals(1, cancelled); assertEquals(0, confirmed) }
    }

    @Test fun modelGroupDetailCanEnterLeaveAndEnterAgainWithGlassMembers() {
        val repository = com.openminis.app.data.repository.ProviderRepository(compose.activity.applicationContext)
        val instance = com.openminis.app.data.model.ProviderInstance(
            id = "group-glass-provider", label = "Test provider",
            providerType = com.openminis.app.data.model.ProviderType.openAI,
            credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
        )
        repository.addInstance(instance)
        val entry = com.openminis.app.data.model.ModelEntry(
            providerInstanceId = instance.id,
            baseModel = com.openminis.app.data.model.LLMModel("test-model", "Test model", "openai"),
        )
        repository.addEntry(entry)
        // Saving an entry canonicalizes its random UUID to instanceId/modelId.
        // Bind the group to the published entry, as the real picker does.
        val storedEntry = repository.config.value.modelEntries.single {
            it.providerInstanceId == instance.id && it.baseModel.id == entry.baseModel.id
        }
        val group = com.openminis.app.data.model.ModelGroup(
            id = "group-glass", name = "Glass group",
            memberEntryIds = mutableListOf(storedEntry.id),
        )
        repository.addGroup(group)
        val storedConfig = repository.config.value
        val storedMemberId = storedConfig.modelGroups.single { it.id == group.id }.memberEntryIds.single()
        assertEquals(storedEntry.id, storedMemberId)
        assertEquals("Test model", storedConfig.modelEntries.single { it.id == storedMemberId }.model.displayName)
        var selected by mutableStateOf<String?>(null)
        compose.setContent {
            MinisTheme {
                if (selected == null) {
                    com.openminis.app.ui.settings.ModelGroupsScreen(
                        providerRepository = repository, onBack = {}, onGroupClick = { selected = it },
                    )
                } else {
                    com.openminis.app.ui.settings.ModelGroupDetailScreen(
                        groupId = selected!!, providerRepository = repository,
                        onBack = { selected = null }, onAddModels = {},
                    )
                }
            }
        }
        repeat(3) {
            compose.onAllNodesWithText("Glass group").onFirst().performClick()
            compose.onNodeWithTag("model-group-detail-list").performScrollToNode(hasText("Test model"))
            compose.onNodeWithText("Test model").assertIsDisplayed()
            compose.onRoot().captureToImage()
            compose.onNodeWithContentDescription(compose.activity.getString(com.openminis.app.R.string.settings_back)).performClick()
            compose.runOnIdle { assertNull(selected) }
        }
    }

    @Test fun phoneHomeAndChatSlideBothWaysWithoutDroppingTheDepartingChat() {
        compose.setContent {
            MinisTheme {
                com.openminis.app.ui.navigation.ChatSplitScaffold(
                    initialSessionId = null,
                    listPane = { _, _, open ->
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).testTag("slide-home")) {
                            TextButton("Open slide chat", onClick = { open("slide-session") })
                        }
                    },
                    detailPane = { id, back, _, _, _, _ ->
                        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).testTag("slide-chat")) {
                            Text("Session $id")
                            TextButton("Back to slide home", onClick = back)
                        }
                    },
                    onOpenSharedDraft = {},
                )
            }
        }
        repeat(2) {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Open slide chat").performClick()
            compose.mainClock.advanceTimeBy(80)
            val entering = compose.onNodeWithTag("slide-chat").fetchSemanticsNode().boundsInRoot
            assertTrue("chat slides in from the right", entering.left > 0f)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val settled = compose.onNodeWithTag("slide-chat").fetchSemanticsNode().boundsInRoot
            assertEquals(0f, settled.left, 1f)
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Back to slide home").performClick()
            compose.mainClock.advanceTimeBy(80)
            // AnimatedPane hides an exiting pane from accessibility before
            // disposal. Its drawn content must still survive in the unmerged tree.
            compose.onNodeWithText("Session slide-session", useUnmergedTree = true).assertExists()
            val leaving = compose.onNodeWithTag("slide-chat", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("departing chat slides right with its content", leaving.left > settled.left)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.onNodeWithTag("slide-chat", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("Session slide-session", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("Open slide chat").assertIsDisplayed()
        }
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
