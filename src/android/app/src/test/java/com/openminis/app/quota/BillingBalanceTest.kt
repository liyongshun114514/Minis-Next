package com.openminis.app.quota

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * [T-provider-custom-balance-url] The vendor-neutral OpenAI-compatible billing
 * ledger contract ([BillingBalance]) shared by the relay path and the
 * user-supplied balance endpoint.
 *
 * The rule under test: the subscription document carries a total cap
 * (`hard_limit_usd`) and a usage ledger carries cents (`total_usage`) or
 * dollars (`used_usd`); the displayed figure is the PREPAID BALANCE
 * (cap - used), never the cap itself, and a missing ledger is an error rather
 * than a 0 balance.
 */
class BillingBalanceTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun same(actual: BigDecimal?, expected: String, message: String = "") =
        assertTrue("$message expected $expected but was $actual",
            actual != null && actual.compareTo(BigDecimal(expected)) == 0)

    private fun rejected(json: String): QuotaFetchError {
        val e = runCatching { BillingBalance.parse(obj(json)) }.exceptionOrNull()
        assertTrue("$json should be rejected, got $e", e is QuotaFetchError)
        return e as QuotaFetchError
    }

    // MARK: - Envelope

    @Test fun `a data wrapper parses exactly like the bare root`() {
        val bare = obj("""{"hard_limit_usd":10,"total_usage":250}""")
        val wrapped = obj("""{"data":{"hard_limit_usd":10,"total_usage":250}}""")
        assertEquals(BillingBalance.parse(bare), BillingBalance.parse(wrapped))
        assertEquals(bare, BillingBalance.unwrap(wrapped))
        assertEquals(bare, BillingBalance.unwrap(bare))
    }

    @Test fun `a usage document is unwrapped from data too`() {
        val r = BillingBalance.parse(
            obj("""{"data":{"hard_limit_usd":20}}"""),
            obj("""{"data":{"total_usage":1250}}"""),
        )
        assertFalse(r.unlimited)
        same(r.amounts?.used, "12.5", "cents in data.total_usage")
        same(r.amounts?.remaining, "7.5", "20 - 12.50")
    }

    // MARK: - Amounts

    @Test fun `hard limit minus cents usage is the displayed balance`() {
        val r = BillingBalance.parse(obj("""{"data":{"hard_limit_usd":458.364966,"total_usage":34828.8922}}"""))
        assertFalse(r.unlimited)
        same(r.amounts?.used, "348.288922", "total_usage is cents")
        same(r.amounts?.remaining, "110.076044", "cap - used")
        assertNull("the cap is never shown as the balance", r.amounts?.total)
    }

    @Test fun `used_usd is already dollars and is never divided by 100`() {
        val inline = BillingBalance.parse(obj("""{"hard_limit_usd":10,"used_usd":3.5}"""))
        same(inline.amounts?.used, "3.5")
        same(inline.amounts?.remaining, "6.5")

        val separate = BillingBalance.parse(obj("""{"hard_limit_usd":10}"""), obj("""{"used_usd":"2.25"}"""))
        same(separate.amounts?.used, "2.25")
        same(separate.amounts?.remaining, "7.75")
    }

    @Test fun `an inline cents ledger wins over a separate document`() {
        val r = BillingBalance.parse(obj("""{"hard_limit_usd":10,"total_usage":100}"""), obj("""{"total_usage":900}"""))
        same(r.amounts?.used, "1", "the subscription's own ledger")
        same(r.amounts?.remaining, "9")
    }

    @Test fun `an overdrawn cap clamps at zero and never shows a negative balance`() {
        val r = BillingBalance.parse(obj("""{"hard_limit_usd":2,"total_usage":350}"""))
        same(r.amounts?.used, "3.5")
        same(r.amounts?.remaining, "0")
    }

    // MARK: - Explicit balance and the unlimited sentinel

    @Test fun `an explicit balance wins over the unlimited sentinel cap`() {
        val r = BillingBalance.parse(obj("""{"hard_limit_usd":100000000,"balance":12.5}"""))
        assertFalse("a real balance is not unlimited", r.unlimited)
        same(r.amounts?.remaining, "12.5")
        assertNull(r.amounts?.total)
        assertNull("no ledger, so no invented usage", r.amounts?.used)
    }

    @Test fun `a balance is usable without any hard limit`() {
        val r = BillingBalance.parse(obj("""{"data":{"balance":"3.25"}}"""))
        assertFalse(r.unlimited)
        same(r.amounts?.remaining, "3.25")
        assertNull(r.amounts?.total)

        val withUsage = BillingBalance.parse(obj("""{"balance":5,"used_usd":1.5}"""))
        same(withUsage.amounts?.remaining, "5", "the reported balance is authoritative")
        same(withUsage.amounts?.used, "1.5")
    }

    @Test fun `the unlimited sentinel reports unlimited and no balance`() {
        val bare = BillingBalance.parse(obj("""{"hard_limit_usd":100000000}"""))
        assertTrue(bare.unlimited)
        assertNull("no cap means no remaining amount", bare.amounts)

        val withUsage = BillingBalance.parse(obj("""{"hard_limit_usd":100000000,"total_usage":1234}"""))
        assertTrue(withUsage.unlimited)
        same(withUsage.amounts?.used, "12.34")
        assertNull(withUsage.amounts?.remaining)
    }

    // MARK: - Missing data is an error, never zero

    @Test fun `a cap without any usage is an error not a zero balance`() {
        val e = rejected("""{"hard_limit_usd":428.75}""")
        assertEquals(QuotaError.Kind.unsupportedResponse, e.kind)

        val fromEmptyUsage = runCatching {
            BillingBalance.parse(obj("""{"hard_limit_usd":1}"""), obj("""{"detail":"not found"}"""))
        }.exceptionOrNull()
        assertTrue("$fromEmptyUsage", fromEmptyUsage is QuotaFetchError)
        assertEquals(QuotaError.Kind.unsupportedResponse, (fromEmptyUsage as QuotaFetchError).kind)
    }

    @Test fun `a response with neither a cap nor a balance is rejected`() {
        for (json in listOf(
            """{}""",
            """{"status":"ok","user":{"name":"x"}}""",
            """{"data":{"total":10,"used":1}}""",
        )) {
            assertEquals(json, QuotaError.Kind.unsupportedResponse, rejected(json).kind)
        }
    }

    @Test fun `an unreadable amount is rejected and never read as zero`() {
        for (json in listOf(
            """{"hard_limit_usd":"12abc","total_usage":10}""",
            """{"balance":"n/a"}""",
            """{"balance":"  "}""",
            """{"hard_limit_usd":true}""",
        )) {
            assertEquals(json, QuotaError.Kind.unsupportedResponse, rejected(json).kind)
        }
    }

    // MARK: - Precision

    @Test fun `zero and sub-cent amounts keep their exact decimal value`() {
        val zero = BillingBalance.parse(obj("""{"hard_limit_usd":5,"total_usage":0}"""))
        same(zero.amounts?.used, "0")
        same(zero.amounts?.remaining, "5")

        val empty = BillingBalance.parse(obj("""{"balance":"0.00"}"""))
        same(empty.amounts?.remaining, "0")
        assertEquals("a zero balance is available, not missing", 0, empty.amounts!!.remaining!!.signum())

        val fractional = BillingBalance.parse(obj("""{"hard_limit_usd":0.1,"total_usage":3}"""))
        same(fractional.amounts?.used, "0.03")
        same(fractional.amounts?.remaining, "0.07")
        assertEquals("no binary float residue", "0.07", fractional.amounts!!.remaining!!.toPlainString())

        val halfCent = BillingBalance.parse(obj("""{"hard_limit_usd":5,"total_usage":0.5}"""))
        same(halfCent.amounts?.used, "0.005")
        same(halfCent.amounts?.remaining, "4.995")
    }

    // MARK: - Which document to fetch

    @Test fun `needsUsage is true only when the cap has no inline ledger`() {
        assertTrue(BillingBalance.needsUsage(obj("""{"hard_limit_usd":1}""")))
        assertTrue(BillingBalance.needsUsage(obj("""{"data":{"hard_limit_usd":1}}""")))
        assertFalse(BillingBalance.needsUsage(obj("""{"hard_limit_usd":1,"total_usage":10}""")))
        assertFalse(BillingBalance.needsUsage(obj("""{"hard_limit_usd":1,"used_usd":1}""")))
        assertFalse("an explicit balance needs no second request",
            BillingBalance.needsUsage(obj("""{"hard_limit_usd":100000000,"balance":1}""")))
        assertFalse("no cap at all", BillingBalance.needsUsage(obj("""{"balance":1}""")))
    }
}
