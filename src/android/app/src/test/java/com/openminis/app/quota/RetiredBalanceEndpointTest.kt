package com.openminis.app.quota

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Retired endpoints may round-trip in old backups, but never affect live requests. */
class RetiredBalanceEndpointTest {
    private fun instance(legacy: String?) = ProviderInstance(
        id = "retired-endpoint", label = "Legacy",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = "https://api.deepseek.com/v1",
        customBalanceURL = legacy,
    )

    @Test fun `legacy URL cannot override the official provider`() = runBlocking {
        val old = "https://retired.example/balance?token=legacy"
        val descriptor = QuotaStore.descriptor(instance(old))
        val clean = QuotaStore.descriptor(instance(null))
        assertEquals(clean, descriptor)
        assertEquals(QuotaVendor.deepSeek, QuotaVendorDetector.vendor(descriptor))
        assertEquals(clean.capabilityKey, descriptor.capabilityKey)
        val urls = mutableListOf<String>()
        val transport = object : QuotaHttpTransport {
            override suspend fun get(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse {
                urls += url
                return QuotaHttpResponse(200,
                    """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"10","granted_balance":"0","topped_up_balance":"10"}]}""".toByteArray(), emptyMap())
            }
        }
        val adapter = QuotaAdapterRegistry.adapter(descriptor)
        adapter.fetch(QuotaFetchContext(descriptor, QuotaSubjectID(QuotaVendor.deepSeek, "test"), "test-key", transport, 1_900_000_000_000L))
        assertEquals(listOf("https://api.deepseek.com/user/balance"), urls)
        assertFalse(urls.any { it.contains("retired.example") })
    }

    @Test fun `legacy descriptor slot is ignored by capability detection`() {
        val clean = QuotaStore.descriptor(instance(null))
        val legacy = clean.copy(customBalanceURL = "https://retired.example/balance")
        assertEquals(QuotaVendorDetector.vendor(clean), QuotaVendorDetector.vendor(legacy))
        assertEquals(clean.capabilityKey, legacy.capabilityKey)
    }

    @Test fun `old cache enum has no executable query adapter`() {
        val adapter = QuotaAdapterRegistry.adapter(QuotaVendor.customBalance)
        assertTrue(adapter is UnsupportedQuotaAdapter)
        assertFalse(adapter.capability(QuotaStore.descriptor(instance(null))).active)
    }
}
