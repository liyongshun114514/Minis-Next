package com.openminis.app.quota

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
 * [T-provider-custom-balance-url] The user-supplied balance endpoint: URL
 * validation, routing, credential use and the supported response shapes.
 */
class CustomBalanceQuotaTest {

    private val now = 1_900_000_000_000L
    private val subject = QuotaSubjectID(QuotaVendor.customBalance, "instance:i")

    private fun desc(url: String? = "https://relay.example.com/api/balance?token=t") =
        QuotaInstanceDescriptor(
            instanceId = "i",
            label = "L",
            providerKind = QuotaInstanceDescriptor.ProviderKind.openAI,
            isOAuth = false,
            isEnabled = true,
            customBaseURL = "https://api.deepseek.com/v1",
            customBalanceURL = url,
        )

    private class FakeTransport(private val status: Int = 200, private val body: String) : QuotaHttpTransport {
        val urls = mutableListOf<String>()
        val bearers = mutableListOf<String>()
        var redirectFreeCalls = 0
        override suspend fun get(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse {
            urls += url
            bearers += bearer
            return QuotaHttpResponse(status, body.toByteArray(), emptyMap())
        }
        override suspend fun getWithoutRedirect(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse {
            redirectFreeCalls++
            return get(url, bearer, headers)
        }
    }

    private fun same(a: BigDecimal?, b: String) = a != null && a.compareTo(BigDecimal(b)) == 0

    @Test fun `configured URL overrides vendor detection and is the only request`() = runBlocking {
        val t = FakeTransport(
            body = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}""",
        )
        val d = desc()
        assertEquals(QuotaVendor.customBalance, QuotaVendorDetector.vendor(d))
        val adapter = QuotaAdapterRegistry.adapter(d)
        assertEquals(CustomBalanceQuotaAdapter, adapter)
        val cap = adapter.capability(d)
        assertTrue(cap.active)
        assertEquals(QuotaSource.compatibleInterface, cap.source)
        assertEquals(QuotaConfidence.userDeclared, cap.confidence)

        val out = adapter.fetch(QuotaFetchContext(d, subject, "sk-test-key", t, now))
        assertEquals(listOf("https://relay.example.com/api/balance?token=t"), t.urls)
        assertEquals("sk-test-key", t.bearers.single())
        assertEquals("a user endpoint never follows a redirect", 1, t.redirectFreeCalls)
        val p = out.snapshot.pools.single()
        assertTrue(same(p.amounts?.remaining, "110"))
        assertEquals(QuotaUnit.Currency("CNY"), p.unit)
        assertTrue(p.provenance.source == QuotaSource.compatibleInterface)
        assertEquals("relay.example.com/api/balance", p.provenance.endpoint)
        assertFalse("query string must not be persisted", p.provenance.endpoint!!.contains("token"))
    }

    @Test fun `blank URL keeps automatic detection`() {
        val auto = desc(url = null)
        assertEquals(QuotaVendor.deepSeek, QuotaVendorDetector.vendor(auto))
        assertNotEquals(CustomBalanceQuotaAdapter, QuotaAdapterRegistry.adapter(auto))
        val blank = desc(url = "   ")
        assertEquals(QuotaVendor.deepSeek, QuotaVendorDetector.vendor(blank))
        assertFalse(CustomBalanceQuotaAdapter.capability(blank).active)
    }

    @Test fun `URL validation requires full HTTPS without secrets`() {
        assertEquals("https://billing.example.com/v1/balance", CustomBalanceEndpoint.normalize(" https://billing.example.com/v1/balance "))
        assertNull(CustomBalanceEndpoint.normalize(null))
        assertNull(CustomBalanceEndpoint.normalize(""))
        assertNull("plain http carries the key in clear", CustomBalanceEndpoint.normalize("http://billing.example.com/balance"))
        assertNull("userinfo is ambiguous", CustomBalanceEndpoint.normalize("https://user:pass@billing.example.com/balance"))
        assertNull("fragment is not sent", CustomBalanceEndpoint.normalize("https://billing.example.com/balance#x"))
        assertNull("host required", CustomBalanceEndpoint.normalize("https:///balance"))
        assertNull("not a URL", CustomBalanceEndpoint.normalize("billing.example.com/balance"))
        assertNull("too long", CustomBalanceEndpoint.normalize("https://billing.example.com/" + "a".repeat(CustomBalanceEndpoint.MAX_LENGTH)))
    }

    @Test fun `capability identity is stable yet changes with the URL and hides its query`() {
        val a = CustomBalanceEndpoint.capabilityIdentity("https://billing.example.com/balance?token=SECRET-A")
        val b = CustomBalanceEndpoint.capabilityIdentity("https://billing.example.com/balance?token=SECRET-B")
        assertEquals(a, CustomBalanceEndpoint.capabilityIdentity("https://billing.example.com/balance?token=SECRET-A"))
        assertNotEquals("a different URL starts a fresh probe", a, b)
        assertFalse(a.contains("SECRET"))
        assertTrue(a.startsWith("billing.example.com/balance#"))
        assertEquals(
            QuotaCapabilityKey("openAI#customBalance", a),
            desc("https://billing.example.com/balance?token=SECRET-A").capabilityKey,
        )
    }

    @Test fun `Moonshot shape keeps available voucher and cash apart`() = runBlocking {
        val t = FakeTransport(
            body = """{"code":0,"data":{"available_balance":49.58,"voucher_balance":46.58,"cash_balance":3.00},"status":true}""",
        )
        val out = CustomBalanceQuotaAdapter.fetch(QuotaFetchContext(desc(), subject, "k", t, now))
        val p = out.snapshot.pools.single()
        assertTrue(same(p.amounts?.remaining, "49.58"))
        assertEquals(2, p.amounts?.breakdown?.size)
        assertEquals(QuotaUnit.Custom("balance"), p.unit)
    }

    @Test fun `New API shape stays in quota units and honours unlimited`() {
        val text = """{"data":{"name":"Default","total_granted":1000000,"total_used":250000,"total_available":750000,"unlimited_quota":false}}"""
        val snap = CustomBalanceQuotaAdapter.parse(text.toByteArray(), "https://relay.example.com/api/usage/token", subject, now)
        val p = snap.pools.single()
        assertEquals(QuotaUnit.Custom("quota"), p.unit)
        assertTrue(same(p.amounts?.remaining, "750000"))
        val unlimited = CustomBalanceQuotaAdapter.parse(
            """{"data":{"total_granted":-1,"total_used":5,"unlimited_quota":true}}""".toByteArray(),
            "https://relay.example.com/api/usage/token", subject, now,
        ).pools.single()
        assertTrue(unlimited.unlimited)
    }

    @Test fun `generic balance with explicit currency`() {
        val snap = CustomBalanceQuotaAdapter.parse(
            """{"balance":"12.50","currency":"usd"}""".toByteArray(),
            "https://billing.example.com/balance", subject, now,
        )
        val p = snap.pools.single()
        assertTrue(same(p.amounts?.remaining, "12.5"))
        assertEquals(QuotaUnit.Currency("USD"), p.unit)
        assertEquals("USD balance", p.vendorLabel)
    }

    @Test fun `generic spend cap computes remaining only from total and used`() {
        val snap = CustomBalanceQuotaAdapter.parse(
            """{"data":{"total":100,"used":40,"currency":"USD"}}""".toByteArray(),
            "https://billing.example.com/balance", subject, now,
        )
        val p = snap.pools.single()
        assertTrue(same(p.amounts?.remaining, "60"))
        assertEquals(QuotaPool.Kind.spendCap, p.kind)
    }

    @Test fun `missing amounts is an error never zero`() {
        val e = runCatching {
            CustomBalanceQuotaAdapter.parse(
                """{"status":"ok","user":{"name":"x"}}""".toByteArray(),
                "https://billing.example.com/balance", subject, now,
            )
        }.exceptionOrNull()
        assertTrue(e is QuotaFetchError)
        assertEquals(QuotaError.Kind.unsupportedResponse, (e as QuotaFetchError).kind)

        val bad = runCatching {
            CustomBalanceQuotaAdapter.parse(
                """{"balance":"12abc"}""".toByteArray(),
                "https://billing.example.com/balance", subject, now,
            )
        }.exceptionOrNull() as QuotaFetchError
        assertEquals(QuotaError.Kind.unsupportedResponse, bad.kind)
    }

    @Test fun `HTTP 401 is reported as unauthorized with no pool`() = runBlocking {
        val t = FakeTransport(status = 401, body = """{"error":"bad key"}""")
        val e = runCatching {
            CustomBalanceQuotaAdapter.fetch(QuotaFetchContext(desc(), subject, "k", t, now))
        }.exceptionOrNull() as QuotaFetchError
        assertEquals(QuotaError.Kind.unauthorized, e.kind)
        assertEquals(401, e.httpStatus)
        assertEquals(1, t.urls.size)
        assertFalse("error detail must not include the query string", e.detail.contains("token"))
    }

    @Test fun `root available_balance is a generic balance`() {
        val p = CustomBalanceQuotaAdapter.parse(
            """{"available_balance":"3.25","currency":"EUR"}""".toByteArray(),
            "https://billing.example.com/balance", subject, now,
        ).pools.single()
        assertTrue(same(p.amounts?.remaining, "3.25"))
        assertEquals(QuotaUnit.Currency("EUR"), p.unit)
    }

    @Test fun `Novita scaled shape and LiteLLM budget shape`() {
        val novita = CustomBalanceQuotaAdapter.parse(
            """{"availableBalance":"1000000","cashBalance":"800000","creditLimit":"200000"}""".toByteArray(),
            "https://api.novita.ai/openapi/v1/billing/balance/detail", subject, now,
        ).pools.single()
        assertTrue(same(novita.amounts?.remaining, "100"))
        assertEquals(QuotaUnit.Currency("USD"), novita.unit)

        val litellm = CustomBalanceQuotaAdapter.parse(
            """{"key":"sk-hidden","info":{"spend":0.42,"max_budget":10.0,"key_alias":"cli","status":"active"}}""".toByteArray(),
            "https://proxy.example.com/key/info", subject, now,
        ).pools.single()
        assertTrue(same(litellm.amounts?.remaining, "9.58"))
        assertEquals("LiteLLM · cli", litellm.vendorLabel)
    }

    @Test fun `host inference only for the vendor's own hosts`() {
        val sf = CustomBalanceQuotaAdapter.parse(
            """{"data":{"totalBalance":20,"chargeBalance":5,"balance":15}}""".toByteArray(),
            "https://api.siliconflow.com/v1/user/info", subject, now,
        ).pools.single()
        assertEquals(QuotaUnit.Currency("USD"), sf.unit)

        val unknown = CustomBalanceQuotaAdapter.parse(
            """{"data":{"available_balance":7}}""".toByteArray(),
            "https://relay.example.com/v1/balance", subject, now,
        ).pools.single()
        assertEquals(QuotaUnit.Custom("balance"), unknown.unit)
    }
}
