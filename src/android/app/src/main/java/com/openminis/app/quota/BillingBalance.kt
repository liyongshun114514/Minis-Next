package com.openminis.app.quota

import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal

/** Shared OpenAI-compatible ledger contract, independent of the relay's hostname. */
internal object BillingBalance {
    private val unlimitedSentinelUSD = BigDecimal(100_000_000)

    data class Result(val amounts: QuotaAmounts?, val unlimited: Boolean)

    fun unwrap(o: JsonObject): JsonObject = o.obj("data") ?: o

    fun needsUsage(subscription: JsonObject): Boolean {
        val sub = unwrap(subscription)
        return sub.dec("balance") == null && sub.dec("hard_limit_usd") != null &&
            sub.dec("total_usage") == null && sub.dec("used_usd") == null
    }

    fun parse(subscription: JsonObject, usage: JsonObject? = null): Result {
        val sub = unwrap(subscription)
        val use = usage?.let(::unwrap)
        val cents = sub.dec("total_usage") ?: use?.dec("total_usage")
        val usedUSD = sub.dec("used_usd") ?:
            cents?.divide(BigDecimal(100))?.canonical() ?: use?.dec("used_usd")
        // An explicit balance wins even when the server also returns a sentinel cap.
        sub.dec("balance")?.let {
            return Result(QuotaAmounts(remaining = it, used = usedUSD), false)
        }
        val hard = sub.dec("hard_limit_usd")
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "no hard_limit_usd / balance: not a billing API")
        if (hard >= unlimitedSentinelUSD) return Result(usedUSD?.let { QuotaAmounts(used = it) }, true)
        // Missing usage is not zero usage; never display the total cap as a balance.
        if (usedUSD == null) throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "billing usage has no readable amount")
        return Result(QuotaAmounts(used = usedUSD, remaining = hard.subtract(usedUSD).max(BigDecimal.ZERO).canonical()), false)
    }
}
