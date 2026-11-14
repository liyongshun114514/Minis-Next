package com.openminis.app.quota

import com.openminis.app.ProductionSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.net.URI

/**
 * [T-quota-relay-probe] Port of the iOS ProviderQuotaTests "Relay probe"
 * block plus the Android wiring (store hint / capability record / UI).
 *
 * [T-quota-generic-usd-balance] The probe order is Sub2API, OpenAI-compatible
 * billing, New API, LiteLLM: an OpenAI-compatible ledger reports real USD, so
 * it is preferred over New API's site-specific "quota" units. Billing is read
 * as a PREPAID USD balance (the cap is never shown as the balance) and works
 * on any host, with or without a path prefix.
 */
class QuotaRelayTest {

    private val now = 1_800_000_000_000L
    private val rsub = QuotaSubjectID(QuotaVendor.customRelay, "k")
    private fun same(a: BigDecimal?, b: String) = a != null && a.compareTo(BigDecimal(b)) == 0
    private fun desc(base: String?) =
        QuotaInstanceDescriptor("i", "L", QuotaInstanceDescriptor.ProviderKind.openAI, false, true, base)

    private class RouteTransport(
        private val routes: Map<String, Pair<Int, String>>,
        private val record: Boolean = false,
    ) : QuotaHttpTransport {
        val hits = mutableListOf<String>()
        val redirectFree = mutableListOf<String>()
        val bearers = mutableListOf<String>()
        val recordedHeaders = mutableListOf<Map<String, String>>()

        private fun reply(url: String): QuotaHttpResponse {
            val (code, body) = routes[URI(url).path] ?: (404 to "404 page not found")
            return QuotaHttpResponse(code, body.toByteArray(), emptyMap())
        }

        override suspend fun get(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse {
            hits += url
            if (record) {
                bearers += bearer
                recordedHeaders += headers
            }
            return reply(url)
        }

        override suspend fun getWithoutRedirect(
            url: String,
            bearer: String,
            headers: Map<String, String>,
        ): QuotaHttpResponse {
            redirectFree += url
            hits += url
            if (record) {
                bearers += bearer
                recordedHeaders += headers
            }
            return reply(url)
        }
    }

    private fun run(
        t: RouteTransport,
        base: String = "https://relay.example.com/v1",
        hint: RelayKind? = null,
        headers: Map<String, String> = emptyMap(),
    ): Result<QuotaFetchOutcome> = runBlocking {
        runCatching {
            RelayQuotaAdapter.fetch(QuotaFetchContext(desc(base), rsub, "sk-relay", t, now, headers, hint))
        }
    }

    private val sub2Wallet = """{"isValid":true,"balance":12.34,"usage":{"total":{"actual_cost":3.2}}}"""
    private val newAPI = """{"code":true,"message":"ok","data":{"object":"token_usage","name":"Default Token","total_granted":1000000,"total_used":250000,"total_available":750000,"unlimited_quota":false,"expires_at":0}}"""
    private val billingUse = """{"object":"list","total_usage":1234}"""

    @Test fun `Sub2API detected by isValid in one request`() {
        val t = RouteTransport(mapOf("/v1/usage" to (200 to sub2Wallet)))
        val o = run(t).getOrThrow()
        assertEquals(RelayKind.sub2api, o.relayKind)
        assertEquals(listOf("https://relay.example.com/v1/usage"), t.hits)
        val p = o.snapshot.pools.first()
        assertTrue(same(p.amounts?.remaining, "12.34") && p.unit == QuotaUnit.Currency("USD"))
        assertTrue(o.snapshot.provenance.source == QuotaSource.relayAccountLedger &&
            o.snapshot.provenance.confidence == QuotaConfidence.thirdParty &&
            o.snapshot.provenance.relayHost == "relay.example.com")
    }

    @Test fun `probe order is Sub2API, billing, New API, LiteLLM`() {
        assertEquals(
            "USD-reporting billing is preferred over New API's raw quota units",
            listOf(RelayKind.sub2api, RelayKind.openAIBilling, RelayKind.newAPI, RelayKind.liteLLM),
            RelayKind.entries.toList(),
        )
        val t = RouteTransport(mapOf(
            "/v1/dashboard/billing/subscription" to (200 to """{"hard_limit_usd":50,"total_usage":1234}"""),
        ))
        val o = run(t).getOrThrow()
        assertEquals(RelayKind.openAIBilling, o.relayKind)
        assertEquals(listOf(
            "https://relay.example.com/v1/usage",
            "https://relay.example.com/v1/dashboard/billing/subscription",
        ), t.hits)
        assertFalse("New API is not asked once billing answers", t.hits.any { it.endsWith("/api/usage/token") })
        assertFalse("LiteLLM is not asked once billing answers", t.hits.any { it.endsWith("/key/info") })
    }

    @Test fun `New API detected after the billing candidates, quota units kept`() {
        val t = RouteTransport(mapOf("/api/usage/token" to (200 to newAPI)))
        val o = run(t).getOrThrow()
        assertEquals(RelayKind.newAPI, o.relayKind)
        assertEquals("Sub2API is always first", "https://relay.example.com/v1/usage", t.hits.first())
        assertEquals("New API only after billing", "https://relay.example.com/api/usage/token", t.hits.last())
        assertTrue(
            "billing tries one or two documented paths before moving on",
            t.hits.size in 3..4,
        )
        assertFalse(t.hits.any { it.endsWith("/key/info") })
        val p = o.snapshot.pools.first()
        assertTrue(p.unit == QuotaUnit.Custom("quota") && same(p.amounts?.total, "1000000"))
        assertEquals(0.25, QuotaBarRules.poolFraction(p)!!, 1e-9)
    }

    @Test fun `billing wins when New API and billing both answer`() {
        val t = RouteTransport(mapOf(
            "/v1/dashboard/billing/subscription" to (200 to """{"hard_limit_usd":20,"total_usage":500}"""),
            "/api/usage/token" to (200 to newAPI),
        ))
        val o = run(t).getOrThrow()
        assertEquals(RelayKind.openAIBilling, o.relayKind)
        val p = o.snapshot.pools.single()
        assertEquals(QuotaPool.Kind.prepaidBalance, p.kind)
        assertEquals(QuotaUnit.Currency("USD"), p.unit)
        assertTrue(same(p.amounts?.remaining, "15"))
        assertNull("the cap is not a balance, so no total is shown", p.amounts?.total)
        assertFalse("New API never answers first", t.hits.any { it.contains("/api/usage/token") })
    }

    @Test fun `any relay host with a path prefix gets USD from automatic billing`() {
        // No customBalanceURL: plain automatic detection, on hosts the app has
        // never heard of. Nothing is keyed on a specific site.
        for (host in listOf("https://relay.example.com", "https://gw.example.net", "https://billing.example.org")) {
            val base = "$host/gw/v1/"
            val t = RouteTransport(mapOf(
                "/gw/v1/dashboard/billing/subscription" to
                    (200 to """{"data":{"hard_limit_usd":100,"total_usage":2500}}"""),
            ))
            val d = desc(base)
            assertNull("$base: automatic detection has no custom balance URL", d.customBalanceURL)
            assertEquals(QuotaVendor.customRelay, QuotaVendorDetector.vendor(d))
            assertEquals(RelayQuotaAdapter, QuotaAdapterRegistry.adapter(d))

            val o = run(t, base = base).getOrThrow()
            assertEquals(RelayKind.openAIBilling, o.relayKind)
            assertEquals(listOf(
                "$host/gw/v1/usage",
                "$host/gw/v1/dashboard/billing/subscription",
            ), t.hits)
            val p = o.snapshot.pools.single()
            assertEquals("$base: a ledger balance, not a cap", QuotaPool.Kind.prepaidBalance, p.kind)
            assertEquals(QuotaUnit.Currency("USD"), p.unit)
            assertTrue("$base: 100 - 25", same(p.amounts?.remaining, "75"))
            assertNull("$base: total is never the cap", p.amounts?.total)
        }
    }

    @Test fun `data wrapped inline usage needs no second request`() {
        val cents = RouteTransport(mapOf(
            "/v1/dashboard/billing/subscription" to (200 to """{"data":{"hard_limit_usd":40,"total_usage":1200}}"""),
        ))
        val o = run(cents).getOrThrow()
        assertEquals("inline total_usage is enough", listOf(
            "https://relay.example.com/v1/usage",
            "https://relay.example.com/v1/dashboard/billing/subscription",
        ), cents.hits)
        assertTrue(same(o.snapshot.pools.single().amounts?.remaining, "28"))
        assertNull(o.snapshot.pools.single().amounts?.total)

        val usd = RouteTransport(mapOf(
            "/v1/dashboard/billing/subscription" to (200 to """{"data":{"hard_limit_usd":40,"used_usd":12}}"""),
        ))
        val o2 = run(usd).getOrThrow()
        assertEquals("inline used_usd is enough", 2, usd.hits.size)
        assertTrue(same(o2.snapshot.pools.single().amounts?.remaining, "28"))
        assertNull(o2.snapshot.pools.single().amounts?.total)
    }

    @Test fun `an explicit balance beats the unlimited sentinel cap`() {
        val t = RouteTransport(mapOf(
            "/v1/dashboard/billing/subscription" to (200 to """{"hard_limit_usd":100000000,"balance":12.5}"""),
        ))
        val o = run(t).getOrThrow()
        val p = o.snapshot.pools.single()
        assertFalse("the sentinel is ignored when a balance is stated", p.unlimited)
        assertTrue(same(p.amounts?.remaining, "12.5"))
        assertNull(p.amounts?.total)
        assertNull("no usage field was sent", p.amounts?.used)
        assertEquals("no usage request is needed", 2, t.hits.size)
    }

    @Test fun `a cap without usage is never shown as a zero balance`() {
        val t = RouteTransport(mapOf(
            "/v1/dashboard/billing/subscription" to (200 to """{"hard_limit_usd":50}"""),
        ))
        val e = run(t).exceptionOrNull() as QuotaFetchError
        assertEquals(QuotaError.Kind.notFound, e.kind)
        assertTrue("the matching usage path is requested", t.hits.contains(
            "https://relay.example.com/v1/dashboard/billing/usage",
        ))
        assertEquals("the last family is still probed", "https://relay.example.com/key/info", t.hits.last())

        // With a known type there is no fallback: the missing usage is the error,
        // and no pool (and so no 0 balance) is produced.
        val hinted = RouteTransport(mapOf(
            "/v1/dashboard/billing/subscription" to (200 to """{"hard_limit_usd":50}"""),
        ))
        val e2 = run(hinted, hint = RelayKind.openAIBilling).exceptionOrNull() as QuotaFetchError
        assertEquals(QuotaError.Kind.notFound, e2.kind)
        assertEquals(2, hinted.hits.size)
    }

    @Test fun `billing falls back to the root path and keeps usage on the same path`() {
        val t = RouteTransport(mapOf(
            "/dashboard/billing/subscription" to (200 to """{"hard_limit_usd":30}"""),
            "/dashboard/billing/usage" to (200 to """{"total_usage":1000}"""),
        ))
        val o = run(t).getOrThrow()
        assertEquals(RelayKind.openAIBilling, o.relayKind)
        assertEquals(listOf(
            "https://relay.example.com/v1/usage",
            "https://relay.example.com/v1/dashboard/billing/subscription",
            "https://relay.example.com/dashboard/billing/subscription",
            "https://relay.example.com/dashboard/billing/usage",
        ), t.hits)
        val p = o.snapshot.pools.single()
        assertEquals(QuotaPool.Kind.prepaidBalance, p.kind)
        assertTrue(same(p.amounts?.remaining, "20"))
        assertTrue(same(p.amounts?.used, "10"))
        assertNull(p.amounts?.total)
    }

    @Test fun `SPA HTML is not mistaken for a relay, billing is a USD prepaid balance`() {
        val html = "<!doctype html><html><body>SPA</body></html>"
        val t = RouteTransport(mapOf(
            "/v1/usage" to (200 to html), "/api/usage/token" to (200 to html),
            "/v1/dashboard/billing/subscription" to (200 to """{"object":"billing_subscription","hard_limit_usd":50,"has_payment_method":true}"""),
            "/v1/dashboard/billing/usage" to (200 to billingUse),
        ))
        val o = run(t).getOrThrow()
        assertEquals(RelayKind.openAIBilling, o.relayKind)
        val p = o.snapshot.pools.first()
        assertEquals("a ledger balance, not a spend cap", QuotaPool.Kind.prepaidBalance, p.kind)
        assertEquals(QuotaUnit.Currency("USD"), p.unit)
        assertTrue(same(p.amounts?.remaining, "37.66") && same(p.amounts?.used, "12.34"))
        assertNull("the cap is not the balance", p.amounts?.total)
        assertFalse(p.unlimited)

        val t3b = RouteTransport(mapOf(
            "/v1/dashboard/billing/subscription" to (200 to """{"hard_limit_usd":100000000}"""),
            "/v1/dashboard/billing/usage" to (200 to billingUse),
        ))
        val u = run(t3b).getOrThrow().snapshot.pools.first()
        assertTrue("1e8 = unlimited, no fake cap", u.unlimited && u.amounts?.total == null)
        assertNull("unlimited does not invent a balance", u.amounts?.remaining)
    }

    @Test fun `nothing answers, every family refuses, hints and path prefixes`() {
        val none = run(RouteTransport(emptyMap())).exceptionOrNull() as QuotaFetchError
        assertTrue(none.kind == QuotaError.Kind.notFound && none.detail.contains("/v1/usage") &&
            none.detail.contains("/api/usage/token") && none.detail.contains("/v1/dashboard/billing/subscription") &&
            none.detail.contains("/key/info"))
        val all401 = run(RouteTransport(mapOf(
            "/v1/usage" to (401 to "{}"), "/api/usage/token" to (401 to "{}"), "/v1/dashboard/billing/subscription" to (401 to "{}"),
            "/key/info" to (401 to "{}"),
        ))).exceptionOrNull() as QuotaFetchError
        assertEquals("the key, not a missing endpoint", QuotaError.Kind.unauthorized, all401.kind)

        val t6 = RouteTransport(mapOf("/api/usage/token" to (200 to newAPI), "/v1/usage" to (200 to sub2Wallet)))
        val o6 = run(t6, hint = RelayKind.newAPI).getOrThrow()
        assertTrue("a known type skips the probe", o6.relayKind == RelayKind.newAPI && t6.hits == listOf("https://relay.example.com/api/usage/token"))
        val t7 = RouteTransport(mapOf("/api/usage/token" to (401 to "{}")))
        val e7 = run(t7, hint = RelayKind.newAPI).exceptionOrNull() as QuotaFetchError
        assertTrue("no re-probe storm", e7.kind == QuotaError.Kind.unauthorized && t7.hits.size == 1)
        val t8 = RouteTransport(mapOf("/gw/v1/usage" to (200 to sub2Wallet)))
        val o8 = run(t8, base = "https://relay.example.com/gw/v1/").getOrThrow()
        assertTrue("root keeps a path prefix, drops the trailing /v1",
            o8.relayKind == RelayKind.sub2api && t8.hits == listOf("https://relay.example.com/gw/v1/usage"))
    }

    @Test fun `billing query reuses the relay key and headers without following redirects`() = runBlocking {
        val urls = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, Map<String, String>>>()
        val t = object : QuotaHttpTransport {
            override suspend fun get(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse =
                error("billing must not use the redirect-following GET")

            override suspend fun getWithoutRedirect(
                url: String,
                bearer: String,
                headers: Map<String, String>,
            ): QuotaHttpResponse {
                urls += url
                sent += bearer to headers
                val body = if (URI(url).path.endsWith("/subscription")) """{"hard_limit_usd":50}"""
                else """{"total_usage":1200}"""
                return QuotaHttpResponse(200, body.toByteArray(), emptyMap())
            }
        }
        val o = RelayQuotaAdapter.fetch(QuotaFetchContext(
            desc("https://relay.example.com/v1"), rsub, "sk-relay", t, now,
            mapOf("X-Provider" to "fp"), RelayKind.openAIBilling,
        ))
        assertEquals(listOf(
            "https://relay.example.com/v1/dashboard/billing/subscription",
            "https://relay.example.com/v1/dashboard/billing/usage",
        ), urls)
        assertTrue("the saved key and identity headers go to both requests",
            sent.all { it.first == "sk-relay" && it.second == mapOf("X-Provider" to "fp") })
        assertTrue(same(o.snapshot.pools.single().amounts?.remaining, "38"))

        val redirected = mutableListOf<String>()
        val crossOrigin = object : QuotaHttpTransport {
            override suspend fun get(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse =
                error("billing must not use the redirect-following GET")

            override suspend fun getWithoutRedirect(
                url: String,
                bearer: String,
                headers: Map<String, String>,
            ): QuotaHttpResponse {
                redirected += url
                return QuotaHttpResponse(302, ByteArray(0), mapOf("location" to "https://elsewhere.example.net/x"))
            }
        }
        val e = runCatching {
            RelayQuotaAdapter.fetch(QuotaFetchContext(
                desc("https://relay.example.com/v1"), rsub, "sk-relay", crossOrigin, now,
                emptyMap(), RelayKind.openAIBilling,
            ))
        }.exceptionOrNull() as QuotaFetchError
        assertEquals("a cross-origin 3xx is returned, never followed", 1, redirected.size)
        assertEquals(QuotaError.Kind.badResponse, e.kind)
        assertEquals(302, e.httpStatus)
    }

    @Test fun `relay capability key is versioned so an old probe is not reused`() {
        val d = desc("https://relay.example.com/v1")
        val legacy = QuotaCapabilityKey(
            QuotaInstanceDescriptor.ProviderKind.openAI.name,
            QuotaCapabilityKey.normalize(d.customBaseURL),
        )
        assertNotEquals("the new probe order must not inherit an old record", legacy, d.capabilityKey)
        assertEquals("still stable for the same base", d.capabilityKey, desc("https://relay.example.com/v1/").capabilityKey)
        assertNotEquals("still per host", d.capabilityKey, desc("https://other.example.com/v1").capabilityKey)

        val state = QuotaState(capabilities = mapOf(legacy to QuotaCapabilityRecord(
            legacy, RelayKind.newAPI.adapterId, QuotaCapabilityRecord.Status.supported, probedAt = now, detail = "old",
        )))
        assertNull("an old capability record does not describe the new probe", state.relayDetection(d))
    }

    @Test fun `Sub2API modes and capability`() {
        val ql = RelayQuotaAdapter.parseSub2API(
            """{"isValid":true,"mode":"quota_limited","quota":{"limit":20,"remaining":5,"used":15}}""".toByteArray(), rsub, "r", now,
        ).pools.first()
        assertTrue(ql.kind == QuotaPool.Kind.spendCap)
        assertEquals(0.75, QuotaBarRules.poolFraction(ql)!!, 1e-9)
        val unl = RelayQuotaAdapter.parseSub2API(
            """{"isValid":true,"remaining":-1,"usage":{"total":{"actual_cost":2}}}""".toByteArray(), rsub, "r", now,
        ).pools.first()
        assertTrue(unl.unlimited)
        assertTrue(RelayQuotaAdapter.capability(desc("https://r.example.com/v1")).active)
        assertFalse(RelayQuotaAdapter.capability(desc(null)).active)
        assertEquals(
            QuotaCapability.activeQuery(QuotaSource.relayAccountLedger, QuotaConfidence.thirdParty),
            QuotaAdapterRegistry.adapter(desc("https://relay.example.com/v1")).capability(desc("https://relay.example.com/v1")),
        )
        assertTrue(RelayKind.entries.all { RelayKind.fromAdapterId(it.adapterId) == it })
        assertEquals(null, RelayKind.fromAdapterId("deepseek.balance"))
    }

    @Test fun `relay detection text from the capability record`() {
        val d = desc("https://relay.example.com/v1")
        val key = d.capabilityKey
        val found = QuotaState(capabilities = mapOf(key to QuotaCapabilityRecord(
            key, RelayKind.sub2api.adapterId, QuotaCapabilityRecord.Status.supported, probedAt = now, detail = "Sub2API · /v1/usage",
        )))
        assertEquals(QuotaState.RelayDetection("Sub2API · /v1/usage", true, now), found.relayDetection(d))
        val failed = QuotaState(capabilities = mapOf(key to QuotaCapabilityRecord(
            key, "relay.probe", QuotaCapabilityRecord.Status.failed, probedAt = now, detail = "no balance endpoint",
        )))
        assertEquals(QuotaState.RelayDetection("no balance endpoint", false, now), failed.relayDetection(d))
        assertEquals("not a relay", null, found.relayDetection(desc("https://api.deepseek.com/v1")))
    }

    @Test fun `automatic USD balance uses the original balance row without cap or usage percent`() {
        for (base in listOf("https://ledger.example.net", "https://ledger.example.net/v1", "https://ledger.example.net/v1/")) {
            val t = RouteTransport(mapOf(
                "/v1/dashboard/billing/subscription" to (200 to """{"data":{"hard_limit_usd":458.364966}}"""),
                "/v1/dashboard/billing/usage" to (200 to """{"data":{"total_usage":34828.8922}}"""),
            ), record = true)
            assertNull(desc(base).customBalanceURL)
            val p = run(t, base).getOrThrow().snapshot.pools.single()
            assertEquals(QuotaPool.Kind.prepaidBalance, p.kind)
            assertEquals(QuotaUnit.Currency("USD"), p.unit)
            assertTrue(same(p.amounts?.remaining, "110.076044"))
            assertNull(p.amounts?.total)
            assertNull(QuotaBarRules.poolFraction(p))
            val status = QuotaStatusRules.poolStatus(p, now)
            assertNull(status.utilization)
            assertEquals("US$110.08", QuotaFormat.amount(p.amounts!!.remaining!!, p.unit, java.util.Locale.CHINA))
            assertEquals("Balance US$110.08", QuotaFormat.shortText(p, status, java.util.Locale.CHINA))
            assertEquals(listOf(
                "https://ledger.example.net/v1/usage",
                "https://ledger.example.net/v1/dashboard/billing/subscription",
                "https://ledger.example.net/v1/dashboard/billing/usage",
            ), t.hits)
            assertEquals(t.hits, t.redirectFree)
            assertTrue(t.bearers.all { it == "sk-relay" })
        }
        val ui = ProductionSources.read("ui/quota/QuotaUi.kt")
        assertTrue("the original balance row supplies the title", ui.contains("R.string.quota_pool_balance_currency, unit.code"))
    }

    @Test fun `HTML billing candidate falls back within the gateway prefix with accurate provenance`() {
        val t = RouteTransport(mapOf(
            "/gateway/v1/dashboard/billing/subscription" to (200 to "<html>SPA</html>"),
            "/gateway/dashboard/billing/subscription" to (200 to """{"hard_limit_usd":5}"""),
            "/gateway/dashboard/billing/usage" to (200 to """{"total_usage":125}"""),
        ))
        val out = run(t, "https://gateway.example.net/gateway/v1", RelayKind.openAIBilling).getOrThrow()
        val p = out.snapshot.pools.single()
        assertTrue(same(p.amounts?.remaining, "3.75"))
        assertEquals("/dashboard/billing/subscription", out.snapshot.provenance.endpoint)
        assertEquals(out.snapshot.provenance, p.provenance)
        assertEquals(listOf(
            "https://gateway.example.net/gateway/v1/dashboard/billing/subscription",
            "https://gateway.example.net/gateway/dashboard/billing/subscription",
            "https://gateway.example.net/gateway/dashboard/billing/usage",
        ), t.hits)
    }

    @Test fun `new relay capabilities bypass both old refresh-timer gates`() {
        val store = ProductionSources.read("quota/QuotaStore.kt")
        assertTrue("direct refresh probes the new capability immediately",
            store.contains("adapter.vendor != QuotaVendor.customRelay || rec != null"))
        assertTrue("scheduled refresh does not skip the upgraded protocol",
            store.contains("st.capabilities[d.capabilityKey] == null"))
    }

    @Test fun `wiring - hint reuse, detected type recorded, shown in footer and sheet`() {
        val store = ProductionSources.read("quota/QuotaStore.kt")
        assertTrue(store.contains("RelayKind.fromAdapterId(it.adapterId)") && store.contains("relayHint,"))
        assertTrue(store.contains("adapterId = relayKind?.adapterId ?: adapter.adapterId") &&
            store.contains("detail = relayKind?.let { \"\${it.displayName} · \${it.path}\" }"))
        val section = ProductionSources.read("ui/quota/ProviderQuotaSection.kt")
        assertTrue(section.contains("R.string.quota_relay_type_detected") && section.contains("R.string.quota_relay_none_found"))
        assertTrue(section.contains("stringResource(R.string.quota_relay_type)"))
    }
}
