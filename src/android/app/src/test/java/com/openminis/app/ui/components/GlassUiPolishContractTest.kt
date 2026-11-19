package com.openminis.app.ui.components

import com.openminis.app.ProductionSources
import org.junit.Assert.*
import org.junit.Test

/** Source bindings complement the rendered/callback tests in MiuixMigrationUiTest. */
class GlassUiPolishContractTest {
    @Test fun `only function menu triggers opt into the jelly spring`() {
        val buttons = ProductionSources.read("ui/components/MinisGlassTopBar.kt")
        assertTrue(buttons.contains("menuMotion: Boolean = false"))
        assertTrue(buttons.contains("if (menuMotion || onLongClick != null) Modifier.minisJellyPress(enabled) else Modifier"))
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
        assertTrue(chat.contains(".onGloballyPositioned { composerHeightPx = it.size.height }"))
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
        assertTrue("fade the complete tinted result, not only the blur", glass.contains("drawRect(edgeFade, blendMode = BlendMode.DstIn)"))
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
        assertTrue(choices.contains(".clip(RoundedCornerShape(16.dp))"))
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
        assertTrue(env.contains("ButtonDefaults.textButtonColorsPrimary()"))
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
    }
}
