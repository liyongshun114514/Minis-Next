package com.openminis.app.ui.components

import com.openminis.app.ProductionSources
import org.junit.Assert.*
import org.junit.Test

/** Source bindings complement the rendered/callback tests in MiuixMigrationUiTest. */
class GlassUiPolishContractTest {
    @Test fun `only function menu triggers opt into the jelly spring`() {
        val buttons = ProductionSources.read("ui/components/MinisGlassTopBar.kt")
        assertTrue(buttons.contains("menuMotion: Boolean = false"))
        assertTrue(buttons.contains("val pressEnabled = enabled && (menuMotion || onLongClick != null)"))
        assertTrue(buttons.contains(".minisPressEvents(pressInteractions, pressEnabled)"))
        assertTrue(buttons.contains("Box(Modifier.glassPress(pressInteractions, pressEnabled))"))
        assertTrue(buttons.contains(".glassPress(interactions, enabled)"))
        assertFalse(buttons.contains("animateFloatAsState("))
        assertTrue(buttons.contains("shadow = GlassShadows.Regular"))
        assertTrue(buttons.contains("GlassIconButton("))
        assertTrue("long press must own DOWN before the nested clickable", buttons.contains("down.consume()"))
        assertTrue(buttons.contains("withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis)"))
        assertFalse("observing alongside the inner clickable cancels long press", buttons.contains("awaitLongPressOrCancellation"))
        val home = ProductionSources.read("ui/sessions/SessionListScreen.kt")
        assertTrue(home.contains("glassAnchor = terminalAnchor"))
        assertTrue(home.contains("menuMotion = true"))
        val provider = ProductionSources.read("ui/settings/ProviderListScreen.kt")
        assertTrue(provider.contains("MinisToolbarIconButton(onClick = { showMenu = true })"))
        assertFalse("add-provider opens a selection screen, not a spring menu", provider.contains("menuMotion = true"))
    }

    @Test fun `composer is glass over a full height viewport with measured scroll clearance`() {
        val chat = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(chat.contains("Box(modifier = Modifier.fillMaxSize()"))
        assertFalse(chat.contains("Box(modifier = Modifier.weight(1f).fillMaxWidth()"))
        assertTrue(chat.contains("val bottomReserve = composerHeightDp +"))
        assertTrue(chat.contains("composerHeightPx = it.size.height"))
        assertTrue(chat.contains("composerActionsTopInWindow = it.positionInWindow().y"))
        assertTrue(chat.contains(".padding(bottom = composerHeightDp)"))
        assertTrue(chat.contains(".glassPanel("))
        assertTrue(chat.contains("material = GlassMaterials.puredThinGlass(ChatColors.isDark)"))
        assertTrue(chat.contains("fallback = Modifier.clip(inputShape).background(ChatColors.inputBg)"))
        assertFalse("opaque paint would hide the sampled scene", chat.contains("val shadowPaint ="))
        assertTrue(chat.contains("glassAnchor = attachAnchor"))
    }

    @Test fun `small bars place trailing controls and keep page backgrounds consistent`() {
        val bar = ProductionSources.read("ui/settings/SettingsGlassTopBar.kt")
        assertTrue(bar.contains("fun MinisSmallTopAppBar("))
        assertTrue(bar.contains("end.placeRelative(width - end.width"))
        for (name in listOf("MountedFoldersScreen", "SharedFoldersScreen", "MountDetailScreen",
            "SharedFolderDetailScreen", "SystemPermissionsScreen")) {
            val page = ProductionSources.read("ui/settings/$name.kt")
            assertTrue(name, page.contains("MinisSmallTopAppBar as SmallTopAppBar"))
            assertFalse(name, page.contains("MiuixTheme.colorScheme.background"))
        }
        val background = ProductionSources.read("ui/settings/BackgroundSettingsScreen.kt")
        assertTrue(background.contains("SettingsScaffold("))
        assertTrue(background.contains("modifier = Modifier.clip(RoundedCornerShape(18.dp))"))
        val glass = ProductionSources.read("ui/components/MinisGlassTopBar.kt")
        assertTrue(glass.contains("CompositingStrategy.Offscreen"))
        assertTrue("one native progressive blur band", glass.contains("progressiveTextureBlur("))
        assertTrue("official 30% surface tint keeps the texture visible", glass.contains("copy(alpha = 0.30f)"))
        assertTrue(glass.contains("ProgressiveBlur.Top"))
    }

    @Test fun `attachment menu stays independent of editor while send uses neutral colors`() {
        val chat = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(chat.contains("val editorMinimumHeight = ComposerSurfaceGeometry.EDITOR_MIN_HEIGHT_DP.dp"))
        assertTrue(chat.contains(".heightIn(min = editorMinimumHeight)"))
        val attachment = chat.substringAfter("glassAnchor = attachAnchor").substringBefore("ComposerMenuHeader(")
        assertFalse(attachment.contains("onPanelMeasured"))
        assertFalse(chat.contains("attachmentMenuHeightPx"))
        val palette = ProductionSources.read("ui/theme/Theme.kt")
        assertTrue(palette.contains("sendButton = colors.onSurface"))
        assertTrue(palette.contains("sendButtonDisabled = colors.onSurface.copy(alpha = 0.10f)"))
        assertFalse(palette.contains("sendButton = colors.primary"))
    }

    @Test fun `settings choice press state and skill tabs have rounded silhouettes`() {
        val settings = ProductionSources.read("ui/settings/SettingsComponents.kt")
        val choices = settings.substringAfter("fun SettingsChoiceRow(")
        assertTrue(choices.contains("RadioButtonPreference("))
        assertFalse("group Card owns outside corners; do not round internal divider seams", choices.substringBefore("private fun settingsRowIcon").contains(".clip("))
        assertTrue(settings.contains("Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)"))
        val skills = ProductionSources.read("ui/settings/SkillsManagementScreen.kt")
        val importer = skills.substringAfter("private fun SkillImportSheet(").substringBefore("// ───")
        assertTrue(importer.contains("TabRowWithContour("))
        assertFalse(importer.contains("\n            TabRow("))
        assertTrue(skills.contains("glassAnchor = addAnchor"))
        assertTrue(skills.contains("glassAnchor = actionAnchor"))
    }

    @Test fun `forms separate field insets card gutters and semantic surfaces`() {
        val field = ProductionSources.read("ui/components/SectionTextField.kt")
        assertTrue(field.contains("contentHorizontalPadding: Dp = SectionDesign.RowHorizontalPadding"))
        assertFalse(field.contains("contentHorizontalPadding: Dp = 0.dp"))
        val tokens = ProductionSources.read("ui/components/SectionDesign.kt")
        assertTrue(tokens.contains("val ScreenHorizontalPadding = 12.dp"))
        assertTrue(tokens.contains("SmallTitle(text = text, modifier = modifier)"))
        val detail = ProductionSources.read("ui/settings/ModelGroupDetailScreen.kt")
        assertFalse(detail.contains("MiuixTheme.colorScheme.background"))
        assertTrue(detail.contains("SettingsGlassTopBar("))
        assertTrue(detail.contains("glassAnchor = memberAnchor"))
        val theme = ProductionSources.read("ui/theme/Theme.kt")
        assertFalse(theme.contains("MINIS_UI_DENSITY_SCALE"))
        assertFalse(theme.contains("LocalDensity provides"))
        assertFalse(theme.contains("textAlign = TextAlign.Left"))
    }

    @Test fun `environment editor closes before clearing its content`() {
        val env = ProductionSources.read("ui/settings/EnvironmentVariablesScreen.kt")
        val sheet = env.substringAfter("// Visibility, editor identity").substringBefore("// Delete confirmation")
        assertTrue(sheet.contains("onDismissRequest = { showAddSheet = false }"))
        assertTrue(sheet.contains("onDismissFinished = {"))
        assertTrue(sheet.contains("formSession++"))
        assertTrue(sheet.contains("key(formSession)"))
        assertFalse(sheet.contains("if (formActive)"))
        assertTrue(sheet.contains("MinisDialog("))
        assertFalse(env.contains("MinisBottomSheet("))
        assertEquals(2, Regex("GlassDialogButton\\(").findAll(env).count())
        assertTrue(env.contains("GlassDialogButtonRole.Primary"))
        val form = env.substringAfter("private fun EnvVarFormSheetContent(")
        assertFalse("the dialog already owns the form surface", form.contains("Card("))
        assertFalse("use the same default field fill as proxy editor", form.contains("backgroundColor ="))
        assertTrue(form.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(form.contains("weight(1f, fill = false)"))
        assertEquals(3, Regex("DialogTextField\\(").findAll(form).count())
        assertTrue(form.contains("isValid && !isDuplicate"))
        assertTrue(form.contains("envVarRepository.update("))
        assertTrue(form.contains("envVarRepository.add("))
    }

    @Test fun `dropdown keeps its role and disabled state when native click is absent`() {
        val dropdown = ProductionSources.read("ui/components/MinisDropdownPreference.kt")
        assertTrue(dropdown.contains("semantics(mergeDescendants = true)"))
        assertTrue(dropdown.contains("role = Role.DropdownList"))
        assertTrue(dropdown.contains("if (!active) disabled()"))
        assertTrue(dropdown.contains("enabled = active"))
        assertTrue(dropdown.contains("LaunchedEffect(active) { if (!active) expanded = false }"))
        assertFalse("do not bypass native input gating with a second clickable", dropdown.contains(".clickable("))
    }

    @Test fun `bottom sheets share native glass and retain recording until native disposal`() {
        val sheet = ProductionSources.read("ui/components/MinisBottomSheet.kt")
        assertTrue(sheet.contains("GlassBottomSheet("))
        assertTrue(sheet.contains("val surfaceBackdrop = backdrop ?: LocalMinisPageBackdrop.current ?: LocalMinisActionBackdrop.current ?: scene?.backdrop"))
        assertTrue(sheet.contains("backdrop = surfaceBackdrop,"))
        assertTrue(sheet.contains("DisposableEffect(scene)"))
        assertTrue(sheet.contains("scene?.acquire()"))
        assertTrue(sheet.contains("onDispose { release?.invoke() }"))
        assertTrue(sheet.contains("CompositionLocalProvider(locals)"))
        assertTrue(sheet.contains("if (!sameWindow)"))
        assertTrue(sheet.contains("WindowBottomSheet("))
        for (path in listOf("ui/chat/ChatModelPickerSheet.kt", "ui/components/UnifiedModelPickerSheet.kt",
            "ui/chat/StandardChatSheet.kt", "ui/settings/ProviderListScreen.kt")) {
            val source = ProductionSources.read(path)
            assertTrue(path, source.contains("MinisBottomSheet("))
            assertFalse(path, source.contains("import top.yukonga.miuix.kmp.window.WindowBottomSheet"))
            assertFalse(path, source.contains("import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet"))
        }
    }

    @Test fun `chat has one full height overscroll visual and no short list clipping edge`() {
        val bounce = ProductionSources.read("ui/chat/AlwaysStretchOverscroll.kt")
        assertTrue(bounce.contains("effect?.withoutVisualEffect()"))
        assertTrue(bounce.contains("overscrollEffect = eventsOnly"))
        assertTrue(bounce.contains(".overscroll(effect)"))
        assertTrue(bounce.contains("enabled = !contentScrollable"))
        assertTrue(bounce.contains("content(if (contentScrollable) eventsOnly else null)"))
        val chat = ProductionSources.read("ui/chat/ChatScreen.kt")
        val list = chat.substringAfter("AlwaysStretchOverscrollBox(").substringAfter("LazyColumn(")
            .substringBefore("// [T-android-session-entering-dots] Fades")
        // Short conversations stay top-aligned: the list itself wraps its
        // content and only the outer overscroll box spans the viewport.
        assertTrue(list.contains(".fillMaxWidth()"))
        assertFalse(list.contains(".fillMaxSize()"))
        val bar = ProductionSources.read("ui/components/RichTitleAppBar.kt")
        assertFalse("Surface clips native glass shadows at the bar edge", bar.contains("Surface(color = color)"))
        assertTrue(bar.contains("MinisGlassTopBar(backdrop = LocalMinisActionBackdrop.current"))
    }

    @Test fun `all modal forms share glass surfaces and filled actions without changing page buttons`() {
        val dialog = ProductionSources.read("ui/components/MinisDialog.kt")
        assertTrue(dialog.contains("GlassDialog("))
        assertTrue(dialog.contains("scene?.acquire()"))
        assertTrue(dialog.contains("if (!sameWindow)"))
        assertTrue(dialog.contains("LocalMinisDialogSurface provides true"))
        val button = ProductionSources.read("ui/components/MinisTextButton.kt")
        assertTrue(button.contains("if (!LocalMinisDialogSurface.current)"))
        assertTrue(button.contains("GlassDialogButton("))
        assertTrue(button.contains("GlassDialogButtonRole.Destructive"))
        val scene = ProductionSources.read("ui/components/MinisOverlayHost.kt")
        assertFalse("uncovered page remains sharp", scene.contains(".textureBlur("))
        assertFalse(scene.contains("backgroundBlurred"))
        assertTrue(dialog.contains("backdrop = scene?.backdrop ?: backdrop"))
        for (path in listOf("ui/settings/MemoryManagementScreen.kt", "ui/settings/ThinkingRuleEditor.kt",
            "ui/settings/ProviderDetailScreen.kt", "ui/settings/EnvironmentVariablesScreen.kt")) {
            val source = ProductionSources.read(path)
            assertTrue(path, source.contains("import com.openminis.app.ui.components.MinisDialog"))
            assertTrue(path, source.contains("import com.openminis.app.ui.components.MinisTextButton as TextButton"))
            assertFalse(path, source.contains("import top.yukonga.miuix.kmp.window.WindowDialog"))
        }
    }

    @Test fun `thinking popup keeps slider behavior and maps its trigger to a real tap`() {
        val chat = ProductionSources.read("ui/chat/ChatScreen.kt")
        val popup = chat.substringAfter("internal fun ThinkingLevelSheet(").substringBefore("/** [T-speed-tier]")
        assertTrue(popup.contains("MinisMenu("))
        assertFalse(popup.contains("MinisBottomSheet("))
        assertTrue(popup.contains("tapPositionInWindow = tapPositionInWindow"))
        assertTrue(popup.contains("ComposerEffortSlider("))
        assertTrue("native popup width probes must not reach SubcomposeLayout", popup.contains("Modifier.width(panelWidth)"))
        assertTrue("native popup height probes must not reach SubcomposeLayout", popup.contains(".height(sliderHeight)"))
        for (dimension in listOf("SLOT_HEIGHT_DP", "LABEL_GAP_DP", "LABEL_BAND_DP")) {
            assertTrue("keep the original slider geometry: $dimension", popup.contains("ThinkingSliderGeometry.$dimension"))
        }
        assertTrue(popup.contains("LocalMinisMenuInteractive.current"))
        assertTrue(popup.contains("bottomBoundaryInWindow = bottomBoundaryInWindow"))
        assertTrue(chat.contains("composerActionsTopInWindow = it.positionInWindow().y"))
        assertTrue(chat.contains("bottomBoundaryInWindow = composerActionsTopInWindow?.minus(thinkingPopupGapPx)"))
        val row = chat.substringAfter("// Button row below text field").substringBefore("// Expand from +")
        assertTrue("measure the + / model / send row, not the text field", row.contains("composerActionsTopInWindow = it.positionInWindow().y"))
        assertFalse("multiline input must not lift the panel above the editor", chat.contains("composerTopInWindow"))
        assertTrue(chat.contains("val thinkingPopupGapPx = with(LocalDensity.current) { 4.dp.toPx() }"))
        assertTrue(chat.contains("thinkingLevelTap = thinkingLevelAnchor.clickPosition()"))
        assertTrue("keyboard dismissal must update the active touch point", chat.contains("thinkingLevelAnchor.trackedClickPosition() ?: thinkingLevelTap"))
        assertTrue(popup.contains("expandFromTap = true"))
        val menu = ProductionSources.read("ui/components/MinisMenu.kt")
        val point = menu.substringAfter("if (expandFromTap && actualTap != null)").substringBefore("else if (glassAnchor != null)")
        assertTrue("native transform starts at a separate real point anchor", point.contains("anchor = pointAnchor"))
        assertTrue(point.contains("val localPoint = actualTap - hostOrigin"))
        assertTrue(point.contains("GlassTransformPopup("))
        assertFalse("never clamp the animation origin to the panel's settled edge", point.contains("coerceAtMost(safeBounds.bottom)"))
        val editor = ProductionSources.read("ui/settings/ThinkingRuleEditor.kt")
        assertTrue(editor.contains("formatTap = formatAnchor.clickPosition()"))
        assertTrue(editor.contains("tapPositionInWindow = formatTap"))
        assertTrue(editor.contains("onDismissFinished = {"))
        assertTrue(editor.contains("if (result != null) onSave(result) else onDismiss()"))
    }

    @Test fun `group typography keeps the original layout and computer keeps its native title slot`() {
        val groups = ProductionSources.read("ui/settings/ModelGroupsScreen.kt").substringAfter("internal fun GroupRow(")
        assertTrue(groups.contains("Column(modifier = Modifier.weight(1f))"))
        assertFalse("badges stay beside the name", groups.contains("FlowRow("))
        assertFalse("do not introduce a separate badge line", groups.contains("verticalArrangement = Arrangement.spacedBy"))
        val heading = groups.substringAfter("// Keep the original").substringBefore("val disabledCount")
        assertTrue(heading.contains("style = MiuixTheme.textStyles.body1"))
        assertTrue(heading.contains("FontWeight.Medium"))
        assertTrue(heading.contains("horizontalArrangement = Arrangement.spacedBy(8.dp)"))
        assertTrue(heading.indexOf("groupTopModalities(") > heading.indexOf("text = group.name"))
        assertTrue(heading.indexOf("BadgeLabel(\"Primary\"") > heading.indexOf("groupTopModalities("))
        assertEquals(2, Regex("style = MiuixTheme.textStyles.footnote1").findAll(groups).count())
        assertTrue(groups.contains("maxLines = 1"))
        assertFalse(groups.contains("maxLines = 2"))
        assertFalse(groups.contains("Spacer(Modifier.width(12.dp))"))
        val computer = ProductionSources.read("ui/chat/ChatToolDetailUI.kt").substringAfter("internal fun ToolDetailSheet(")
        val header = computer.indexOf("startAction = {")
        val title = computer.indexOf("text = \"Minis Computer\"")
        val body = computer.indexOf("// ── Content Area")
        assertTrue("custom header occupies native title slot", header >= 0 && title > header && title < body)
        assertFalse(computer.substring(header, body).contains("vertical = 10.dp"))
        val menu = ProductionSources.read("ui/components/MinisMenu.kt")
        assertTrue(menu.contains("Modifier.heightIn(max = with(density) { safeBounds.bottom.toDp() })"))
        assertFalse("do not stretch the glass panel to host height", menu.contains("Modifier.height(with(density)"))
        assertTrue(menu.contains("imeBottom <= systemBottom && !preferAbove"))
    }

    @Test fun `settings and phone home chat share both push and pop translations`() {
        val motion = ProductionSources.read("ui/navigation/PageSlideMotion.kt")
        assertEquals(4, Regex("tween\\(260").findAll(motion).count())
        assertFalse(motion.contains("spring("))
        val navigation = ProductionSources.read("ui/navigation/AppNavigation.kt")
        val panes = ProductionSources.read("ui/navigation/ChatSplitScaffold.kt")
        for (name in listOf("pushEnter", "pushExit", "popEnter", "popExit")) {
            assertTrue(name, navigation.contains("PageSlideMotion.$name()"))
            assertTrue(name, panes.contains("PageSlideMotion.$name()"))
        }
        assertTrue(panes.contains("currentSessionId ?: if (!twoPane) lastDetailSessionId else null"))
    }

    @Test fun `provider format choices use native contour tabs`() {
        val provider = ProductionSources.read("ui/settings/ProviderDetailScreen.kt")
        assertEquals(2, Regex("TabRowWithContour\\(").findAll(provider).count())
        assertEquals(2, Regex("minWidth = preferenceTabMinWidth\\(").findAll(provider).count())
    }

    @Test fun `glass surfaces sample recorded pages and keep the original effort slider`() {
        val sheet = ProductionSources.read("ui/components/MinisBottomSheet.kt")
        assertTrue(sheet.contains("backdrop: Backdrop? = null"))
        assertTrue(sheet.contains("LocalMinisActionBackdrop.current ?: scene?.backdrop"))
        val settings = ProductionSources.read("ui/settings/SettingsComponents.kt")
        assertTrue(settings.contains("LocalMinisActionBackdrop provides null"))
        assertTrue(settings.contains("LocalMinisPageBackdrop provides backdrop"))
        val bar = ProductionSources.read("ui/components/MinisGlassTopBar.kt")
        assertTrue(bar.contains("progressiveTextureBlur(") && bar.contains("copy(alpha = 0.30f)"))
        val chat = ProductionSources.read("ui/chat/ChatScreen.kt")
        for (label in listOf("Scroll to previous message", "Scroll to bottom")) {
            val button = chat.substringBefore("contentDescription = \"$label\"").substringAfterLast("MinisGlassIconButton(")
            assertTrue(label, button.contains("size = 36.dp,") && button.contains("backdrop = chatTopBackdrop,"))
        }
        val viewport = chat.substringAfter("AlwaysStretchOverscrollBox(").substringBefore("LazyColumn(")
        assertTrue(viewport.contains("Modifier.layerBackdrop(chatTopBackdrop)"))
        assertTrue(bar.contains("foreground.height + 12.dp.roundToPx()"))
        val workBar = ProductionSources.read("ui/chat/ChatComposerWidgets.kt")
        val status = ProductionSources.read("ui/chat/SubAgentStatusPanel.kt")
        assertTrue(workBar.contains(".chatStatusBackground(backdrop, top.yukonga.miuix.kmp.material.GlassShape(10.dp))"))
        assertTrue(status.contains(".chatStatusBackground(backdrop, shape)"))
        val statusSurface = ProductionSources.read("ui/chat/ChatStatusSurface.kt")
        assertTrue(statusSurface.contains("glassPanel(") && statusSurface.contains("GlassMaterials.puredThinGlass(ChatColors.isDark)"))
        assertFalse(statusSurface.contains("textureBlur("))
        val manualScroll = chat.substringAfter("// A finger lift never triggers").substringBefore("// [T-android-updown-fab-asymmetry]")
        assertFalse("manual drag end must not snap the list", manualScroll.contains("tracedScrollToItem("))
        assertTrue(manualScroll.contains("userScrolledAway = listState.canScrollBackward"))
        val editor = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")
        assertTrue(editor.contains("DocumentEditorField("))
        val slider = ProductionSources.read("ui/chat/NativeComposerExtras.kt")
        assertTrue(slider.contains("Canvas(") && slider.contains("ThinkingSliderGeometry.track("))
    }

    @Test fun `pressed settings rows keep separators outside their input plane`() {
        val settings = ProductionSources.read("ui/settings/SettingsComponents.kt")
        assertTrue(settings.contains("val modifier = Modifier.heightIn(min = minHeight)"))
        assertTrue(settings.contains("if (showDivider) PreferenceDivider"))
        val provider = ProductionSources.read("ui/settings/ProviderDetailScreen.kt")
        val rows = provider.substringAfter("val modelRow:").substringBefore("// [T-android-model-row-hide-action] Row context menu")
        assertFalse(rows.contains(".combinedClickable("))
        assertTrue(rows.contains("onLongClick = { menuEntryId = entry.id }"))
        val tokens = ProductionSources.read("ui/components/SectionDesign.kt")
        assertTrue(tokens.contains("val CardInnerVerticalPadding = 0.dp"))
        val theme = ProductionSources.read("ui/theme/Theme.kt")
        assertTrue(theme.contains("lineHeight = if (lineHeight.isSp) lineHeight * factor else lineHeight"))
    }

    @Test fun `group lists render directly and toolbar actions keep separate space`() {
        val palette = ProductionSources.read("ui/theme/Theme.kt")
        assertTrue(palette.contains("userBubble = colors.surfaceContainerHigh"))
        for (source in listOf("ui/sessions/SessionListScreen.kt", "ui/settings/ModelGroupsScreen.kt")) {
            assertFalse(source, ProductionSources.read(source).contains("layerBackdrop("))
        }
        val scene = ProductionSources.read("ui/components/MinisOverlayHost.kt")
        assertTrue(scene.contains("if (scene?.recording == true) Modifier.layerBackdrop(scene.backdrop) else Modifier"))
        val page = ProductionSources.read("ui/components/MinisPageScaffold.kt")
        assertTrue(page.contains("recordBodyForActions && isRuntimeShaderSupported()"))
        val menus = ProductionSources.read("ui/components/MinisMenu.kt")
        assertTrue(menus.contains("hostVisible.value = false"))
        assertTrue(menus.contains("scene?.acquire()"))
        assertTrue(menus.contains("backdrop = menuBackdrop"))
        val bar = ProductionSources.read("ui/settings/SettingsGlassTopBar.kt")
        assertTrue(bar.contains("Arrangement.spacedBy(8.dp)"))
        assertFalse("multiple actions must not stack in one Box", bar.contains("Box(contentAlignment = Alignment.CenterEnd) { actions?.invoke() }"))
        val toolPill = ProductionSources.read("ui/chat/ChatAssistantMessageUI.kt")
        assertTrue("tool calls use the original Minis capsule surface", toolPill.contains(".background(ChatColors.toolCapsuleBg)"))
        assertFalse("user bubble tint must not leak into tool calls", toolPill.contains(".background(ChatColors.userBubble)"))
        val mcp = ProductionSources.read("ui/settings/MCPIntegrationsScreen.kt")
        assertTrue("MCP JSON input uses the top-aligned field", mcp.contains("topAligned = true"))
        val statusSurface = ProductionSources.read("ui/chat/ChatStatusSurface.kt")
        assertTrue("floating tool bars keep the composer's neutral fallback", statusSurface.contains("fallback = Modifier.clip(shape).background(ChatColors.inputBg)"))
    }
}
