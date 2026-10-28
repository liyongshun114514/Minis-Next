package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.auth.CopilotModelsResult
import com.openminis.app.auth.fetchCopilotModelsDetailed
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.repository.ModelListReconcile
import com.openminis.app.provider.openai.OpenAIModelsApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-model-refresh-visible] "Refresh model list does nothing" (Open
 * Minis group, 2026-10-06, SenseNova at token.sensenova.cn/v1). With a valid
 * key the real endpoint refreshes fine (Pixel 4a, 2026-10-07: STEP1 vendor-api,
 * 10 models). What the user could see as "nothing" was every FAILURE: a 401, a
 * body that is not `{"data":[...]}` - each returned an empty list, the custom
 * base had no models.dev match, the old list was kept, and the button said
 * nothing.
 *
 * [T-authoritative-model-refresh] The one case that is NOT a failure is an
 * empty parsed list: the body was a model list and it had no models, which the
 * caller may store as an empty catalog. Everything here therefore separates
 * `parsed = true` from an error, and the repository wiring below pins that a
 * failed read keeps the stored entries while a Copilot HTTP/parse failure is
 * not mistaken for a legitimate empty answer.
 */
class ModelListRefreshTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() { server = MockWebServer(); server.start() }

    @After
    fun tearDown() = server.shutdown()

    private fun base() = server.url("/v1").toString().trimEnd('/')

    private fun fetch(body: String, code: Int = 200): OpenAIModelsApi.FetchResult = runBlocking {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        OpenAIModelsApi.fetchModelsDetailed("k", base(), forceRefresh = true)
    }

    private fun ids(r: OpenAIModelsApi.FetchResult) = r.models.map { it.id }

    // ── shapes ──────────────────────────────────────────────────────────

    @Test
    fun `standard data array`() {
        val r = fetch("""{"object":"list","data":[{"id":"sensenova-6.8-flash-lite","object":"model"},{"id":"glm-5.2"}]}""")
        assertNull(r.error)
        assertTrue(r.parsed)
        assertEquals(listOf("sensenova-6.8-flash-lite", "glm-5.2"), ids(r))
    }

    @Test
    fun `models wrapper, nested data, bare array`() {
        assertEquals(listOf("a", "b"), ids(fetch("""{"models":[{"id":"a"},{"id":"b"}]}""")))
        assertEquals(listOf("a"), ids(fetch("""{"data":{"models":[{"id":"a"}]}}""")))
        assertEquals(listOf("a", "b"), ids(fetch("""[{"id":"a"},{"id":"b"}]""")))
    }

    @Test
    fun `ids under model or name, and bare id strings`() {
        assertEquals(listOf("m1", "n1"), ids(fetch("""{"data":[{"model":"m1"},{"name":"n1"}]}""")))
        assertEquals(listOf("s1", "s2"), ids(fetch("""{"data":["s1","s2"]}""")))
    }

    @Test
    fun `an id that is also the name gets the derived display name, not a duplicate`() {
        val r = fetch("""{"data":[{"name":"deepseek-v4.1-flash"}]}""")
        assertEquals("deepseek-v4.1-flash", r.models.single().id)
    }

    // ── empty answer vs failure ─────────────────────────────────────────

    @Test
    fun `an empty data array is a parsed authoritative empty catalog, not a failure`() {
        val r = fetch("""{"data":[]}""")
        assertTrue("the body WAS a model list", r.parsed)
        assertTrue(r.models.isEmpty())
        assertNull("nothing failed, so there is nothing to report as an error", r.error)
    }

    @Test
    fun `401 is a failure, not an empty answer`() {
        val r = fetch("""{"error":{"code":16,"message":"Authorization Not Found"}}""", code = 401)
        assertFalse("an HTTP error is never parsed", r.parsed)
        assertTrue("empty: the caller keeps the existing entries", r.models.isEmpty())
        assertEquals("HTTP 401: Authorization Not Found", r.error)
    }

    @Test
    fun `an error object in a 200 is not a model list`() {
        val r = fetch("""{"error":{"message":"quota exceeded"}}""")
        assertFalse(r.parsed)
        assertTrue(r.models.isEmpty())
        assertTrue(r.error!!, r.error!!.startsWith("the response is not a model list: quota exceeded"))
    }

    @Test
    fun `html from a misconfigured base url is reported`() {
        val r = fetch("<html><body>Not Found</body></html>", code = 404)
        assertFalse(r.parsed)
        assertTrue(r.error!!.startsWith("HTTP 404: <html>"))
    }

    @Test
    fun `a network failure still throws for the caller to report`() {
        server.shutdown()
        val thrown = runCatching { runBlocking { OpenAIModelsApi.fetchModelsDetailed("k", base(), forceRefresh = true) } }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `fetchModels keeps its old contract`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertTrue(OpenAIModelsApi.fetchModels("k", base(), forceRefresh = true).isEmpty())
    }

    // ── consecutive live answers replace each other ─────────────────────

    @Test
    fun `two consecutive answers reconcile in order through the production fetch`() {
        val first = fetch("""{"data":[{"id":"a"},{"id":"b"}]}""")
        var n = 0
        val firstEntries = ModelListReconcile.apply(
            "inst", emptyList(), first.models, newUuid = { "u-${n++}" },
        )
        assertEquals(setOf("a", "b"), firstEntries.entries.map { it.baseModel.id }.toSet())

        val second = fetch("""{"data":[{"id":"b"},{"id":"c"}]}""")
        val secondEntries = ModelListReconcile.apply(
            "inst", firstEntries.entries, second.models,
            newUuid = { "new-uuid" },
        )
        assertEquals("the second answer, not the union", setOf("b", "c"),
            secondEntries.entries.map { it.baseModel.id }.toSet())
        assertTrue("a is gone", "u-0" in secondEntries.removedEntryIds)
    }

    // ── Copilot: HTTP/parse failure is distinct from a legitimate empty ─

    private fun copilot(body: String, code: Int = 200): CopilotModelsResult = runBlocking {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        fetchCopilotModelsDetailed("session", base())
    }

    @Test
    fun `copilot reads a well-formed list`() {
        val r = copilot(
            """{"data":[{"id":"gpt-5.4","name":"GPT-5.4","capabilities":{"type":"chat"}},
                       {"id":"embed","capabilities":{"type":"embedding"}}]}""",
        )
        val models = (r as CopilotModelsResult.Success).models
        assertEquals(listOf("gpt-5.4"), models.map { it.id })
        assertEquals("GPT-5.4", models.single().displayName)
    }

    @Test
    fun `copilot empty data array is a legitimate empty catalog`() {
        val r = copilot("""{"data":[]}""")
        assertTrue(r is CopilotModelsResult.Success)
        assertTrue((r as CopilotModelsResult.Success).models.isEmpty())
    }

    @Test
    fun `copilot HTTP error keeps the catalog`() {
        val r = copilot("""{"message":"bad credentials"}""", code = 401)
        assertTrue(r is CopilotModelsResult.Failed)
        assertTrue((r as CopilotModelsResult.Failed).reason.startsWith("HTTP 401: bad credentials"))
    }

    @Test
    fun `copilot a body without a data array is a shape failure, not empty`() {
        for (body in listOf("""{"models":[]}""", "<html>oops</html>", """{"data":"nope"}""")) {
            val r = copilot(body)
            assertTrue("$body must not read as an empty success", r is CopilotModelsResult.Failed)
            assertTrue((r as CopilotModelsResult.Failed).reason.startsWith("the response is not a model list"))
        }
    }

    // ── the outcome reaches the user ─────────────────────────────────────

    @Test
    fun `refresh is an expression returning ModelRefreshOutcome and the UI shows it`() {
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        assertTrue("refreshModels is the withContext expression body",
            Regex("""\): ModelRefreshOutcome\s*=\s*kotlinx\.coroutines\.withContext\(""").containsMatchIn(repo))
        assertFalse("the old block-body signature is gone", repo.contains("): ModelRefreshOutcome {"))
        assertTrue("the openAI branch uses the detailed fetch",
            repo.contains("OpenAIModelsApi.fetchModelsDetailed("))
        assertTrue("a parsed=false read keeps the stored list",
            repo.contains("if (!r.parsed)"))
        assertTrue("a failed read keeps the stored list and names the reason",
            repo.contains("reportVendorError(failure ?:"))
        assertTrue(repo.contains("reportVendorError(\"no API key for this provider\")"))
        assertTrue(repo.contains("return ModelRefreshSource.KEPT"))
        assertTrue("Copilot failures are their own type, not empty",
            repo.contains("is com.openminis.app.auth.CopilotModelsResult.Failed"))
        assertTrue("Copilot success is stored even when empty",
            repo.contains("is com.openminis.app.auth.CopilotModelsResult.Success -> fetched.models.map"))
        val screen = ProductionSources.read("ui/settings/ProviderDetailScreen.kt")
        assertTrue(screen.contains("refreshOutcome = providerRepository.refreshModels(instance, forceRefresh = true)"))
        assertTrue(screen.contains("stringResource(R.string.provider_refresh_result_kept, error)"))
        val rpc = ProductionSources.read("debug/ProviderMutationMethods.kt")
        assertTrue(rpc.contains("put(\"vendorError\", e)"))
    }

    @Test
    fun `the compiled-in OAuth list is not a refresh outcome`() {
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        assertFalse("Codex discovery stores the live answer only", repo.contains("codexImageModels()"))
        assertFalse("no compiled-in list swap", repo.contains("fetchModelsOAuth()"))
    }

    @Test
    fun `codex discovery stores result models verbatim`() {
        val models = listOf(
            LLMModel(id = "gpt-5.5", displayName = "GPT-5.5", provider = "OpenAI"),
            LLMModel(id = "gpt-6-sol", displayName = "GPT-6 Sol", provider = "OpenAI"),
        )
        val outcome = com.openminis.app.data.repository.ProviderRepository.codexDiscoveryFromResult(
            com.openminis.app.provider.openai.CodexModelsApi.Result.Success(models),
            forceRefresh = true,
        )
        val stored = (outcome as com.openminis.app.data.repository.ProviderRepository.CodexDiscovery.Success).models
        assertEquals(models.map { it.id }, stored.map { it.id })
        for (image in OpenAIModelsApi.codexImageModels()) {
            assertFalse("${image.id} must not be topped up into an authoritative answer",
                image.id in stored.map { it.id })
        }
    }
}
