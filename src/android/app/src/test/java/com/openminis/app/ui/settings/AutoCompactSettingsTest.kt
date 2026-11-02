package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-auto-compact-settings] Contract for the Context Compaction page.
 *
 * The page is mostly composition, but three decisions carry real risk and are
 * pinned here without needing Robolectric to inflate Compose:
 *
 *  - threshold text validation (0 / negatives / overflow must NOT be saved);
 *  - which model entries are offered (enabled provider, visible, text output);
 *  - the page being reachable, and its strings existing in both locales with
 *    the same format arguments.
 */
class AutoCompactSettingsTest {

    // ── Threshold validation ─────────────────────────────────────────────────

    @Test
    fun `blank means use the model default`() {
        assertEquals(
            ThresholdParseResult.Valid(null),
            parseThresholdInput(""),
        )
        assertEquals(
            ThresholdParseResult.Valid(null),
            parseThresholdInput("   "),
        )
    }

    @Test
    fun `positive integers are accepted`() {
        assertEquals(ThresholdParseResult.Valid(1), parseThresholdInput("1"))
        assertEquals(ThresholdParseResult.Valid(200_000), parseThresholdInput(" 200000 "))
        assertEquals(ThresholdParseResult.Valid(Int.MAX_VALUE), parseThresholdInput(Int.MAX_VALUE.toString()))
    }

    @Test
    fun `zero negatives junk and overflow are rejected`() {
        for (bad in listOf("0", "-1", "-200000", "abc", "12.5", "1e6", "NaN")) {
            assertEquals(
                "expected $bad to be rejected",
                ThresholdParseResult.Invalid(ThresholdError.NOT_POSITIVE),
                parseThresholdInput(bad),
            )
        }
        // One past Int.MAX_VALUE is a distinct complaint: the number is a real
        // count, it just cannot be stored in an Int.
        assertEquals(
            ThresholdParseResult.Invalid(ThresholdError.TOO_LARGE),
            parseThresholdInput("2147483648"),
        )
        assertEquals(
            ThresholdParseResult.Invalid(ThresholdError.TOO_LARGE),
            parseThresholdInput("999999999999999999999"),
        )
    }

    // ── Model filtering ──────────────────────────────────────────────────────

    @Test
    fun `an enabled visible text entry is offered`() {
        assertTrue(entry().isUsableForCompaction(instance()))
        assertTrue(entry(outputModalities = listOf("text")).isUsableForCompaction(instance()))
        // null modalities mean "text out only" per LLMModel.isTextOutput.
        assertTrue(entry(outputModalities = null).isUsableForCompaction(instance()))
    }

    @Test
    fun `disabled providers hidden entries and non-text models are not offered`() {
        assertFalse(entry().isUsableForCompaction(instance(enabled = false)))
        assertFalse(entry().isUsableForCompaction(null))
        assertFalse(entry(hidden = true).isUsableForCompaction(instance()))
        assertFalse(entry(outputModalities = listOf("image")).isUsableForCompaction(instance()))
        assertFalse(entry(outputModalities = listOf("audio")).isUsableForCompaction(instance()))
        // Decode-only provider types restored from another build cannot serve
        // a request here, so they must not be selectable either.
        assertFalse(entry().isUsableForCompaction(instance(type = ProviderType.unsupported)))
        assertFalse(entry().isUsableForCompaction(instance(type = ProviderType.antigravity)))
    }

    // ── Resources / wiring ───────────────────────────────────────────────────

    @Test
    fun `every string exists in both locales with the same format arguments`() {
        val en = strings("values")
        val zh = strings("values-zh")
        assertEquals("zh must translate every auto-compact string", en.keys, zh.keys)
        assertTrue("page strings are present", en.size >= 20)

        for ((name, enValue) in en) {
            val args = Regex("""%\d\$[sd]""").findAll(enValue).map { it.value }.toSet()
            if (args.isEmpty()) continue
            val zhValue = zh.getValue(name)
            for (arg in args) {
                assertTrue("values-zh/$name lost $arg", zhValue.contains(arg))
            }
        }
    }

    @Test
    fun `the settings screen links to a registered route`() {
        val settings = ProductionSources.read("ui/settings/SettingsScreen.kt")
        assertTrue(settings.contains("onAutoCompactClick: () -> Unit = {}"))
        assertTrue(settings.contains("R.string.auto_compact_settings_title"))

        val nav = ProductionSources.read("ui/navigation/AppNavigation.kt")
        assertTrue(nav.contains("const val AUTO_COMPACT = \"auto_compact\""))
        assertTrue(nav.contains("onAutoCompactClick = { navController.safeNavigate(Routes.AUTO_COMPACT) }"))
        assertTrue(nav.contains("composable(Routes.AUTO_COMPACT)"))
        assertTrue(nav.contains("AutoCompactSettingsScreen("))

        val page = ProductionSources.read("ui/settings/AutoCompactSettingsScreen.kt")
        // The saved selection must be displayed from the stored id, not
        // replaced when it fails to resolve.
        assertTrue(page.contains("AutoCompactPrefs.config.collectAsState()"))
        assertTrue(page.contains("auto_compact_model_missing_fmt"))
        assertTrue(page.contains("auto_compact_model_unavailable_fmt"))
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun strings(dir: String): Map<String, String> {
        val res = File(ProductionSources.mainRoot()!!.parentFile.parentFile.parentFile.parentFile, "res")
        val xml = File(res, "$dir/auto_compact_settings.xml").readText()
        return Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun instance(
        enabled: Boolean = true,
        type: ProviderType = ProviderType.openAI,
    ): ProviderInstance = ProviderInstance(
        id = "instance-1",
        label = "Test Provider",
        providerType = type,
        credentialType = ProviderCredential.apiKey,
        isEnabled = enabled,
    )

    private fun entry(
        hidden: Boolean = false,
        outputModalities: List<String>? = null,
    ): ModelEntry = ModelEntry(
        providerInstanceId = "instance-1",
        baseModel = LLMModel(
            id = "model-a",
            displayName = "Model A",
            provider = "openai",
            outputModalities = outputModalities,
        ),
        isHidden = hidden,
        uuid = "instance-1/model-a",
    )
}
