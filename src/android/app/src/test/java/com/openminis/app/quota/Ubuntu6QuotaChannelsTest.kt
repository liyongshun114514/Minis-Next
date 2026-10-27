package com.openminis.app.quota

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.net.URI

/**
 * [UBUNTU6] The balance channels added in this build, against fixtures taken
 * from the vendors' published examples:
 * - Novita AI   GET https://api.novita.ai/openapi/v1/billing/balance/detail
 * - Vercel AI Gateway GET https://ai-gateway.vercel.sh/v1/credits
 * - LiteLLM Proxy (relay) GET /key/info
 *
 * The rule under test throughout: a field that is missing or unreadable is an
 * error ([QuotaError.Kind.unsupportedResponse]), NEVER a zero balance; a
 * balance the vendor really reports as 0 is a real 0.
 */
class Ubuntu6QuotaChannelsTest {

    private val now = 1_800_000_000_000L
    private fun dec(s: String) = BigDecimal(s)
    private fun same(a: BigDecimal?, b: String) = a != null && a.compareTo(BigDecimal(b)) == 0

    private val novitaSub = QuotaSubjectID(QuotaVendor.novita, "k")
    private val vercelSub = QuotaSubjectID(QuotaVendor.vercelAIGateway, "k")
    private val relaySub = QuotaSubjectID(QuotaVendor.customRelay, "k")

    private fun desc(base: String?) =
        QuotaInstanceDescriptor("i", "L", QuotaInstanceDescriptor.ProviderKind.openAI, false, true, base)

    private inline fun <reified T : Throwable> thrown(block: () -> Unit): T? =
        try { block(); null } catch (e: Throwable) { e as? T }

    private class RecordingTransport(private val status: Int, private val body: String) : QuotaHttpTransport {
        var url: String? = null
        override suspend fun get(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse {
            this.url = url
            return QuotaHttpResponse(status, body.toByteArray(), emptyMap())
        }
    }

    // ── Novita AI ──────────────────────────────────────────────────────────

    /** The documented example: 1/10000 USD units. */
    private val novitaJSON = """{"availableBalance":"1000000","cashBalance":"800000","creditLimit":"200000","pendingCharges":"0","outstandingInvoices":"0"}"""

    @Test fun `Novita fixture is converted from 1-10000 USD and keeps its parts`() {
        val snap = NovitaQuotaAdapter.parse(novitaJSON.toByteArray(), subjectId = novitaSub, now = now)
        val p = snap.pools.single()
        assertEquals(QuotaPool.Kind.prepaidBalance, p.kind)
        assertEquals(QuotaUnit.Currency("USD"), p.unit)
        assertTrue("availableBalance 1000000 = $100.00", same(p.amounts?.remaining, "100"))
        val parts = p.amounts?.breakdown.orEmpty()
        assertTrue("cash 800000 = $80.00", same(parts.first { it.role == QuotaAmounts.Role.ToppedUp }.amount, "80"))
        assertTrue(
            "credit line 200000 = $20.00",
            same(parts.first { it.role is QuotaAmounts.Role.Other }.amount, "20"),
        )
        assertEquals(QuotaSource.officialQuery, snap.provenance.source)
        assertEquals(QuotaConfidence.documented, snap.provenance.confidence)
        assertEquals("novita.billing-balance", snap.provenance.adapterId)
        assertEquals(QuotaPoolBinding.Evidence.vendorDocumented, snap.bindings.single().evidence)
        // A prepaid balance never gets a percent, even with a total to derive one from.
        assertNull(p.amounts?.computedUtilization(p.kind, p.unlimited))
    }

    @Test fun `Novita zero is a real zero but a missing field is an error`() {
        val zero = NovitaQuotaAdapter.parse(
            """{"availableBalance":"0","cashBalance":"0","creditLimit":"0"}""".toByteArray(),
            subjectId = novitaSub, now = now,
        ).pools.single()
        assertTrue(same(zero.amounts?.remaining, "0"))
        assertEquals(QuotaStatus.exhausted, QuotaStatusRules.poolStatus(zero, now).status)

        for (body in listOf("""{"cashBalance":"800000"}""", """{"availableBalance":null}""", """{"availableBalance":"n/a"}""")) {
            val e = thrown<QuotaFetchError> { NovitaQuotaAdapter.parse(body.toByteArray(), subjectId = novitaSub, now = now) }
            assertNotNull("$body must not become a balance", e)
            assertEquals(QuotaError.Kind.unsupportedResponse, e!!.kind)
        }
    }

    @Test fun `Novita fetch uses the documented endpoint on the instance host`() = runBlocking {
        val t = RecordingTransport(200, novitaJSON)
        val o = NovitaQuotaAdapter.fetch(
            QuotaFetchContext(desc("https://api.novita.ai/v3/openai"), novitaSub, "sk-novita", t, now),
        )
        assertEquals("https://api.novita.ai/openapi/v1/billing/balance/detail", t.url)
        assertTrue(o.isComplete)
        assertEquals(QuotaVendor.novita, QuotaVendorDetector.vendor(desc("https://api.novita.ai/v3/openai")))
    }

    // ── Vercel AI Gateway ──────────────────────────────────────────────────

    private val vercelJSON = """{"balance":"95.50","total_used":"4.50"}"""

    @Test fun `Vercel credits fixture is a USD prepaid balance without an invented total`() {
        val snap = VercelAIGatewayQuotaAdapter.parse(vercelJSON.toByteArray(), subjectId = vercelSub, now = now)
        val p = snap.pools.single()
        assertEquals(QuotaPool.Kind.prepaidBalance, p.kind)
        assertEquals(QuotaUnit.Currency("USD"), p.unit)
        assertTrue(same(p.amounts?.remaining, "95.5"))
        // total_used is LIFETIME spend, not the used part of this balance.
        assertNull("no used/total is derived from lifetime spend", p.amounts?.used)
        assertNull(p.amounts?.total)
        assertNull(p.amounts?.computedUtilization(p.kind, p.unlimited))
        assertEquals(QuotaSource.officialQuery, snap.provenance.source)
        assertEquals(QuotaConfidence.documented, snap.provenance.confidence)
        assertEquals("vercel.ai-gateway-credits", snap.provenance.adapterId)
    }

    @Test fun `Vercel zero balance is exhausted, missing balance is an error`() {
        val zero = VercelAIGatewayQuotaAdapter.parse(
            """{"balance":"0.00","total_used":"4.50"}""".toByteArray(), subjectId = vercelSub, now = now,
        ).pools.single()
        assertTrue(same(zero.amounts?.remaining, "0"))
        assertEquals(QuotaStatus.exhausted, QuotaStatusRules.poolStatus(zero, now).status)

        for (body in listOf("""{"total_used":"4.50"}""", """{"balance":null}""", """{"balance":"soon"}""")) {
            val e = thrown<QuotaFetchError> { VercelAIGatewayQuotaAdapter.parse(body.toByteArray(), subjectId = vercelSub, now = now) }
            assertNotNull("$body must not become a balance", e)
            assertEquals(QuotaError.Kind.unsupportedResponse, e!!.kind)
        }
    }

    @Test fun `Vercel fetch uses the documented endpoint`() = runBlocking {
        val t = RecordingTransport(200, vercelJSON)
        VercelAIGatewayQuotaAdapter.fetch(
            QuotaFetchContext(desc("https://ai-gateway.vercel.sh/v1"), vercelSub, "vck_test", t, now),
        )
        assertEquals("https://ai-gateway.vercel.sh/v1/credits", t.url)
        assertEquals(QuotaVendor.vercelAIGateway, QuotaVendorDetector.vendor(desc("https://ai-gateway.vercel.sh/v1")))
    }

    // ── Registry / detection ───────────────────────────────────────────────

    @Test fun `both channels are registered as documented active queries`() {
        // The host map itself: an instance pointed at the vendor's own base URL
        // must not fall through to customRelay.
        assertEquals(QuotaVendor.novita, QuotaVendorDetector.vendorForHost("api.novita.ai"))
        assertEquals(QuotaVendor.vercelAIGateway, QuotaVendorDetector.vendorForHost("ai-gateway.vercel.sh"))
        assertNull(QuotaVendorDetector.vendorForHost("relay.example.com"))

        for ((d, id) in listOf(
            desc("https://api.novita.ai/v3/openai") to "novita.billing-balance",
            desc("https://ai-gateway.vercel.sh/v1") to "vercel.ai-gateway-credits",
        )) {
            val a = QuotaAdapterRegistry.adapter(d)
            assertEquals(id, a.adapterId)
            val cap = a.capability(d)
            assertTrue(cap.active)
            assertTrue(cap.manualRefresh)
            assertEquals(QuotaSource.officialQuery, cap.source)
            assertEquals(QuotaConfidence.documented, cap.confidence)
        }
        // An unknown custom endpoint still probes as a relay, not as these vendors.
        assertEquals(
            QuotaVendor.customRelay,
            QuotaVendorDetector.vendor(desc("https://relay.example.com/v1")),
        )
    }

    // ── LiteLLM (relay) ────────────────────────────────────────────────────

    private val liteLLM = """{"key":"sk-...","info":{"key_alias":"cli","spend":0.42,"max_budget":10.0,"budget_duration":"30d","budget_reset_at":"2026-11-01T00:00:00Z","blocked":false,"status":"active","token":"hashed"}}"""

    @Test fun `LiteLLM key info becomes a USD spend cap`() {
        val snap = RelayQuotaAdapter.parseLiteLLM(liteLLM.toByteArray(), relaySub, "litellm.example.com", now)
        val p = snap.pools.single()
        assertEquals(QuotaPool.Kind.spendCap, p.kind)
        assertEquals(QuotaUnit.Currency("USD"), p.unit)
        assertTrue(same(p.amounts?.total, "10"))
        assertTrue(same(p.amounts?.used, "0.42"))
        assertTrue(same(p.amounts?.remaining, "9.58"))
        assertFalse(p.unlimited)
        assertEquals("LiteLLM · cli", p.vendorLabel)
        assertEquals(QuotaSource.relayAccountLedger, snap.provenance.source)
        assertEquals(QuotaConfidence.thirdParty, snap.provenance.confidence)
        assertEquals("relay.liteLLM", snap.provenance.adapterId)
        assertEquals(QuotaStatus.available, QuotaStatusRules.poolStatus(p, now).status)
    }

    @Test fun `LiteLLM key without a budget shows tracked spend, never a zero balance`() {
        val body = """{"key":"sk-...","info":{"spend":1.5,"max_budget":null}}"""
        val p = RelayQuotaAdapter.parseLiteLLM(body.toByteArray(), relaySub, "litellm.example.com", now).pools.single()
        assertTrue(p.unlimited)
        assertTrue(same(p.amounts?.used, "1.5"))
        assertNull(p.amounts?.remaining)
        assertNull(p.amounts?.total)
        assertEquals(QuotaStatus.available, QuotaStatusRules.poolStatus(p, now).status)
    }

    @Test fun `LiteLLM blocked key is a refusal, unreadable info is an error`() {
        val blocked = """{"info":{"spend":0.0,"max_budget":5.0,"blocked":true,"status":"active"}}"""
        val p = RelayQuotaAdapter.parseLiteLLM(blocked.toByteArray(), relaySub, "h", now).pools.single()
        assertEquals(QuotaPool.Verdict.rejected, p.verdict)
        assertEquals(QuotaStatus.exhausted, QuotaStatusRules.poolStatus(p, now).status)

        for (body in listOf("""{"hello":"world"}""", """{"info":{"key_alias":"x"}}""", """{"info":{"spend":"many"}}""")) {
            val e = thrown<QuotaFetchError> { RelayQuotaAdapter.parseLiteLLM(body.toByteArray(), relaySub, "h", now) }
            assertNotNull("$body must not be read as a balance", e)
            assertEquals(QuotaError.Kind.unsupportedResponse, e!!.kind)
        }
    }

    private class RouteTransport(private val routes: Map<String, Pair<Int, String>>) : QuotaHttpTransport {
        val hits = mutableListOf<String>()
        override suspend fun get(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse {
            hits += url
            val (code, body) = routes[URI(url).path] ?: (404 to "404 page not found")
            return QuotaHttpResponse(code, body.toByteArray(), emptyMap())
        }
    }

    private fun probe(t: RouteTransport, hint: RelayKind? = null) = runBlocking {
        runCatching {
            RelayQuotaAdapter.fetch(
                QuotaFetchContext(desc("https://litellm.example.com/v1"), relaySub, "sk-relay", t, now, relayKindHint = hint),
            )
        }
    }

    @Test fun `a LiteLLM proxy is detected on the fourth probe and reused afterwards`() {
        val t = RouteTransport(mapOf("/key/info" to (200 to liteLLM)))
        val o = probe(t).getOrThrow()
        assertEquals(RelayKind.liteLLM, o.relayKind)
        assertEquals(
            listOf("/v1/usage", "/api/usage/token", "/v1/dashboard/billing/subscription", "/key/info"),
            t.hits.map { URI(it).path },
        )
        assertEquals("LiteLLM", RelayKind.liteLLM.displayName)
        assertEquals("/key/info", RelayKind.liteLLM.path)
        assertEquals(RelayKind.liteLLM, RelayKind.fromAdapterId(o.snapshot.provenance.adapterId))

        val hinted = RouteTransport(mapOf("/key/info" to (200 to liteLLM)))
        val again = probe(hinted, RelayKind.liteLLM).getOrThrow()
        assertEquals(listOf("/key/info"), hinted.hits.map { URI(it).path })
        assertEquals(RelayKind.liteLLM, again.relayKind)
    }

    @Test fun `an unrelated 200 on key-info is not mistaken for LiteLLM`() {
        val t = RouteTransport(mapOf("/key/info" to (200 to """{"hello":"world"}""")))
        val e = probe(t).exceptionOrNull()
        assertNotNull("no family may claim this relay", e)
        assertTrue(e is QuotaFetchError && e.kind == QuotaError.Kind.notFound)
    }
}
