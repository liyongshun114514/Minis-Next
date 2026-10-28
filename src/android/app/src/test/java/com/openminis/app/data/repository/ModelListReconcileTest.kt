package com.openminis.app.data.repository

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.provider.openai.OpenAIModelsApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-authoritative-model-refresh] Refreshing a provider's models must read the
 * provider again and make its answer the catalog:
 *
 *  - ids it still lists keep their entry (uuid, overrides, hidden flag);
 *  - ids it stopped listing are DELETED, not parked in a grace window;
 *  - an empty answer is an answer and clears the list;
 *  - a failed read changes nothing and is reported as a failure — never as a
 *    "kept" success, and never by substituting models.dev / the built-in list;
 *  - two consecutive refreshes each reflect that response, in order;
 *  - every read bypasses the response cache.
 *
 * The decision rule is [ModelListReconcile] (pure, no Android context); the
 * repository wiring is asserted against the source because
 * [ProviderRepository] needs a Context, SharedPreferences and a network stack.
 */
class ModelListReconcileTest {

    private fun model(id: String, displayName: String = id) =
        LLMModel(id = id, displayName = displayName, provider = "openAI")

    private fun entry(
        id: String,
        uuid: String = "uuid-$id",
        isCustom: Boolean = false,
        hidden: Boolean = false,
        contextWindow: Int? = null,
    ) = ModelEntry(
        providerInstanceId = "inst",
        baseModel = model(id, displayName = "Nice $id"),
        overrides = ModelOverrides(contextWindow = contextWindow),
        isCustom = isCustom,
        isHidden = hidden,
        uuid = uuid,
    )

    // ── add / remove / metadata ─────────────────────────────────────────

    @Test
    fun `listed ids keep their user metadata while removed ids are dropped`() {
        val existing = listOf(
            entry("a", uuid = "ua", hidden = true, contextWindow = 200_000),
            entry("b", uuid = "ub"),
        )
        val result = ModelListReconcile.apply(
            instanceId = "inst",
            existing = existing,
            authoritative = listOf(model("b", displayName = "b"), model("c")),
        )

        assertEquals(listOf("b", "c"), result.entries.map { it.baseModel.id })
        val b = result.entries.single { it.baseModel.id == "b" }
        assertEquals("uuid carried forward", "ub", b.id)
        assertEquals("remote gave a bare id, prior name kept", "Nice b", b.baseModel.displayName)
        assertEquals(setOf("ua"), result.removedEntryIds)
        assertEquals(setOf("b", "c"), result.keptModelIds)
        assertNull("a returning model carries no absence mark", b.absentSince)
    }

    @Test
    fun `user overrides and hidden state survive a refresh`() {
        val existing = listOf(entry("a", uuid = "ua", hidden = true, contextWindow = 1_000_000))
        val result = ModelListReconcile.apply("inst", existing, listOf(model("a")))

        val a = result.entries.single()
        assertEquals("ua", a.id)
        assertTrue(a.isHidden)
        assertEquals(1_000_000, a.overrides.contextWindow)
        assertTrue(result.removedEntryIds.isEmpty())
    }

    // ── empty answer ────────────────────────────────────────────────────

    @Test
    fun `an empty authoritative answer clears the catalog`() {
        val existing = listOf(entry("a", uuid = "ua"), entry("b", uuid = "ub"))
        val result = ModelListReconcile.apply("inst", existing, emptyList())

        assertTrue("the provider listed nothing, so nothing is stored", result.entries.isEmpty())
        assertEquals(setOf("ua", "ub"), result.removedEntryIds)
    }

    @Test
    fun `custom rows and voice-template seeds are not provider ids and survive`() {
        val existing = listOf(
            entry("mine", uuid = "um", isCustom = true),
            entry("voice", uuid = "uv"),
            entry("a", uuid = "ua"),
        )
        val result = ModelListReconcile.apply(
            instanceId = "inst",
            existing = existing,
            authoritative = emptyList(),
            protectedVoiceIds = setOf("voice"),
        )

        assertEquals(setOf("mine", "voice"), result.entries.map { it.baseModel.id }.toSet())
        assertEquals(setOf("ua"), result.removedEntryIds)
    }

    // ── two consecutive, different answers ──────────────────────────────

    @Test
    fun `two consecutive refreshes follow each response, not the union`() {
        val first = ModelListReconcile.apply(
            instanceId = "inst",
            existing = listOf(entry("a", uuid = "ua", contextWindow = 111)),
            authoritative = listOf(model("a"), model("b")),
        )
        assertEquals(setOf("a", "b"), first.entries.map { it.baseModel.id }.toSet())

        val second = ModelListReconcile.apply(
            instanceId = "inst",
            existing = first.entries,
            authoritative = listOf(model("b"), model("c")),
            newUuid = { "new-uuid" },
        )

        assertEquals("a is gone, b survived, c arrived", setOf("b", "c"), second.entries.map { it.baseModel.id }.toSet())
        assertEquals("c is new", "new-uuid", second.entries.single { it.baseModel.id == "c" }.id)
        assertEquals("b keeps the uuid it had in the first answer", "uuid-b", second.entries.single { it.baseModel.id == "b" }.id)
        assertFalse(second.removedEntryIds.isEmpty())
    }

    // ── empty-success is distinguishable from failure ───────────────────

    @Test
    fun `a parsed empty provider answer is reported as parsed, not as a failure`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"nope"}}"""))
            val base = server.url("/v1").toString().trimEnd('/')

            val empty = runBlocking {
                OpenAIModelsApi.fetchModelsDetailed("k", base, forceRefresh = true)
            }
            assertTrue("parsed empty list", empty.parsed)
            assertTrue(empty.models.isEmpty())

            val denied = runBlocking {
                OpenAIModelsApi.fetchModelsDetailed("k", base, forceRefresh = true)
            }
            assertFalse("an HTTP error is not an authoritative empty answer", denied.parsed)
            assertNotNull(denied.error)
        } finally {
            server.shutdown()
        }
    }

    // ── repository wiring: no fallback, keep on failure, bypass cache ────

    private val repo = ProductionSources.read("data/repository/ProviderRepository.kt")

    private fun refreshRegion(): String = repo
        .substringAfter("private suspend fun refreshModelsInner(")
        .substringBefore("internal sealed interface CodexDiscovery")

    @Test
    fun `a failed read keeps the stored list and reports the error`() {
        val region = refreshRegion()
        val keep = region.substringAfter("val failure = vendorFailure")
        assertTrue("failure returns KEPT", keep.contains("return ModelRefreshSource.KEPT"))
        assertFalse(
            "nothing is written when the read failed",
            keep.substringBefore("return ModelRefreshSource.KEPT").contains("replaceEntries("),
        )
        assertTrue("the reason reaches the caller", keep.contains("reportVendorError("))
        assertTrue(
            "only two honest outcomes exist",
            repo.contains("enum class ModelRefreshSource { VENDOR, KEPT }"),
        )
    }

    @Test
    fun `no public or built-in catalog stands in for the provider`() {
        assertFalse("no models.dev fallback", repo.contains("ModelsDevApi.fetchModels("))
        assertFalse("no compiled-in list swap", repo.contains("fetchModelsOAuth()"))
        assertFalse("no models.dev source", repo.contains("ModelRefreshSource.MODELS_DEV"))
        assertFalse("no built-in source", repo.contains("ModelRefreshSource.BUILT_IN"))
        assertFalse("no built-in top-up", repo.contains("addMissingBuiltInModels"))
        assertFalse("no absence grace retention", repo.contains("absentSince"))
    }

    @Test
    fun `every discovery call bypasses its response cache`() {
        val region = refreshRegion()
        val forced = Regex("""forceRefresh = true""").findAll(region).count()
        assertTrue("expected a forced read per discovery path, found $forced", forced >= 6)
        assertTrue("Codex discovery is forced", Regex("""forceRefresh = true""").findAll(repo).count() >= 7)
        assertFalse("no cache read in the refresh path", region.contains("cache.load("))
        assertTrue("the app context is threaded for write-through", region.contains("context = context"))
    }

    @Test
    fun `replaceEntries deletes what the provider dropped and prunes references`() {
        val fn = repo.substringAfter("fun replaceEntries(instanceId: String, models: List<LLMModel>)")
            .substringBefore("// --- Model Entry management ---")
        assertTrue(fn.contains("ModelListReconcile.apply("))
        assertFalse("no absent-grace window", fn.contains("MODEL_ABSENCE_GRACE_MS"))
        assertFalse("no suspicious-shrink escape hatch", fn.contains("SUSPICIOUS SHRINK"))
        assertTrue("group members are pruned", fn.contains("config.modelGroups[i].memberEntryIds.removeAll { it in result.removedEntryIds }"))
        assertTrue("agent-loop pins are pruned", fn.contains("config.agentLoopModelEntryIds.removeAll { it in result.removedEntryIds }"))
    }

    @Test
    fun `the refresh UI reports success or the failure reason, never a substitute`() {
        val screen = ProductionSources.read("ui/settings/ProviderDetailScreen.kt")
        assertTrue(screen.contains("stringResource(R.string.provider_refresh_result_ok, o.total, o.added)"))
        assertTrue(screen.contains("stringResource(R.string.provider_refresh_result_kept, error)"))
        assertFalse(screen.contains("provider_refresh_result_builtin"))
        assertFalse(screen.contains("provider_refresh_result_fallback"))
    }
}
