package com.openminis.app.quota

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest

/**
 * [T-provider-custom-balance-url] Validation for the user-supplied balance
 * endpoint. Kept separate from the adapter so the UI, the capability key and
 * the request path all apply exactly the same rule.
 *
 * Only a full HTTPS URL is accepted: the request carries this instance's API
 * key, so a plain-http or ambiguous value is rejected rather than sent. A
 * user-info component (https://key@host/...) and a fragment are rejected too;
 * a query string IS allowed (many billing endpoints expect the key there) and
 * is never written to logs, the capability record or provenance.
 */
object CustomBalanceEndpoint {
    const val MAX_LENGTH = 2048

    /** The trimmed URL when it is usable, else null. Never rewrites the value. */
    fun normalize(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.length > MAX_LENGTH) return null
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrEmpty()) return null
        if (uri.rawUserInfo != null) return null
        if (uri.rawFragment != null) return null
        return s
    }

    /** "host[:port]/path" - what may be persisted or logged; the query is dropped. */
    fun display(raw: String?): String {
        val url = normalize(raw) ?: return "custom balance endpoint"
        val uri = runCatching { URI(url) }.getOrNull() ?: return "custom balance endpoint"
        val host = uri.host.orEmpty()
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return host + port + path
    }

    /**
     * Stable capability id for one endpoint: the readable host/path plus a
     * hash of the full URL (so two URLs that differ only by a secret query
     * value cannot inherit each other's probe result). No secret is stored.
     */
    fun capabilityIdentity(raw: String?): String {
        val url = normalize(raw) ?: return "invalid"
        return display(url) + "#" + sha256(url).take(16)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * [T-provider-custom-balance-url] Active balance query against a URL the user
 * configured on the instance.
 *
 * Safety:
 * - ONE request, to exactly that URL, with this instance's own API key as
 *   `Authorization: Bearer ...`. Nothing is sent anywhere else, and a blank
 *   URL falls back to the normal vendor adapter instead of this one.
 * - A missing / unreadable amount is an ERROR ([QuotaError.Kind.unsupportedResponse]);
 *   it is never shown as 0.
 * - Provenance is compatibleInterface / userDeclared: these figures are the
 *   user's endpoint talking, not a vendor-documented API.
 *
 * Supported response shapes (the same field sets the built-in adapters use):
 * - DeepSeek: `balance_infos[] {currency,total_balance,granted_balance,topped_up_balance}`
 * - Moonshot: `data.available_balance` (+ `voucher_balance`, `cash_balance`)
 * - SiliconFlow: `data.totalBalance` (+ `chargeBalance`, `balance`)
 * - New API: `data.total_granted / total_used / total_available / unlimited_quota`
 * - Sub2API: `isValid` + wallet `balance`, `quota.{limit,used,remaining}` or `remaining`
 * - OpenAI billing: `hard_limit_usd` (+ `total_usage` in cents)
 * - LiteLLM: `info.{spend,max_budget,key_alias,blocked,status}`
 * - Novita: `availableBalance` (+ `cashBalance`/`creditLimit`), 1/10000 USD
 * - generic: `balance` / `remaining` / `available_balance` / `available` /
 *   `credit(s)` / `quota` at the root or under `data`, with an optional
 *   `currency`; `total`/`limit` + `used` is read as a spend cap.
 * Amounts may be JSON strings or numbers; decimals are parsed exactly.
 * Anything else is rejected - no parser guesses a field or a currency.
 */
object CustomBalanceQuotaAdapter : ProviderQuotaAdapter {
    override val adapterId = "custom.balance-url"
    override val vendor = QuotaVendor.customBalance

    private val UNLIMITED_SENTINEL_USD = BigDecimal(100_000_000)

    override fun capability(d: QuotaInstanceDescriptor): QuotaCapability =
        if (CustomBalanceEndpoint.normalize(d.customBalanceURL) != null) {
            QuotaCapability.activeQuery(QuotaSource.compatibleInterface, QuotaConfidence.userDeclared)
        } else {
            // Set but unusable (or called without one): explain it instead of
            // silently querying some other endpoint.
            QuotaCapability.unsupported(QuotaSubject.UnsupportedReason.unknownVendor)
        }

    override suspend fun fetch(ctx: QuotaFetchContext): QuotaFetchOutcome {
        val url = CustomBalanceEndpoint.normalize(ctx.descriptor.customBalanceURL)
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "custom balance URL is not a valid HTTPS URL")
        val endpoint = CustomBalanceEndpoint.display(url)
        val r = ctx.transport.getWithoutRedirect(url, ctx.credential, ctx.extraHeaders)
        QuotaHttp.error(r, endpoint, ctx.now)?.let { throw it }
        val snapshot = parse(r.body, url, ctx.subjectId, ctx.now, QuotaHttp.clockOffset(r, ctx.now))
        return QuotaFetchOutcome(snapshot, isComplete = true)
    }

    fun parse(
        data: ByteArray,
        url: String,
        subjectId: QuotaSubjectID,
        now: Long,
        clockOffset: Long? = null,
    ): UpstreamQuotaSnapshot {
        val endpoint = CustomBalanceEndpoint.display(url)
        val obj = QuotaHttp.jsonObject(data, endpoint)
        val prov = QuotaProvenance(
            source = QuotaSource.compatibleInterface,
            confidence = QuotaConfidence.userDeclared,
            adapterId = adapterId,
            endpoint = endpoint,
        )
        val pools = parsePools(obj, url, subjectId, prov, now)
        if (pools.isEmpty()) {
            throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "no readable balance at $endpoint")
        }
        return UpstreamQuotaSnapshot(
            subjectId = subjectId,
            provenance = prov,
            capturedAt = now,
            serverClockOffset = clockOffset,
            pools = pools,
            bindings = pools.map {
                QuotaPoolBinding(it.id, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.assumedSubjectWide)
            },
        )
    }

    // MARK: - Shapes

    private fun parsePools(
        o: JsonObject,
        url: String,
        subjectId: QuotaSubjectID,
        prov: QuotaProvenance,
        now: Long,
    ): List<QuotaPool> {
        // DeepSeek: one prepaid pool per currency.
        o.arr("balance_infos")?.let { infos ->
            val available = o.bool("is_available")
            val pools = infos.mapNotNull { element ->
                val info = element.asObject() ?: return@mapNotNull null
                val currency = currencyCode(info) ?: return@mapNotNull null
                val total = info.dec("total_balance")
                val parts = mutableListOf<QuotaAmounts.Part>()
                info.dec("granted_balance")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.Granted, it) }
                info.dec("topped_up_balance")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.ToppedUp, it) }
                if (total == null && parts.isEmpty()) return@mapNotNull null
                pool(
                    subjectId, prov, now, currency, QuotaPool.Kind.prepaidBalance,
                    label = "$currency balance",
                    amounts = QuotaAmounts(remaining = total, breakdown = parts),
                    verdict = available?.let { if (it) QuotaPool.Verdict.allowed else QuotaPool.Verdict.rejected },
                )
            }
            if (pools.isNotEmpty()) return pools
            throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "no readable balance_infos")
        }

        // Sub2API: isValid marks the family; wallet / key quota / subscription.
        if (o.bool("isValid") != null) {
            return listOf(sub2api(o, subjectId, prov, now))
        }

        // LiteLLM: info.spend / info.max_budget, USD.
        o.obj("info")?.let { info ->
            val spend = info.dec("spend")
            val max = info.dec("max_budget")
            if (spend != null || max != null) return listOf(liteLLM(o, info, spend, max, subjectId, prov, now))
        }

        // Novita: the 1/10000 USD shape is only recognised with its siblings.
        val novita = o.dec("availableBalance")
        if (novita != null && (o.dec("cashBalance") != null || o.dec("creditLimit") != null)) {
            return listOf(novita(novita, o, subjectId, prov, now))
        }

        // OpenAI billing: hard_limit_usd, with total_usage in cents when present.
        o.dec("hard_limit_usd")?.let { hard ->
            return listOf(openAIBilling(o, hard, o.obj("data"), subjectId, prov, now))
        }

        // Moonshot / SiliconFlow / New API live under `data`.
        o.obj("data")?.let { d ->
            parseData(d, o, url, subjectId, prov, now)?.let { return listOf(it) }
        }

        // Generic root-level balance.
        generic(o, null, url, subjectId, prov, now)?.let { return listOf(it) }
        return emptyList()
    }

    private fun parseData(
        d: JsonObject,
        root: JsonObject,
        url: String,
        subjectId: QuotaSubjectID,
        prov: QuotaProvenance,
        now: Long,
    ): QuotaPool? {
        // New API token quota (units are the relay's own, never assumed USD).
        val granted = d.dec("total_granted")
        val available = d.dec("total_available")
        if (granted != null || available != null) {
            val used = d.dec("total_used")
            val unlimited = d.bool("unlimited_quota") == true || (granted?.let { it.signum() < 0 } ?: false)
            val name = d.str("name")?.trim()?.takeIf { it.isNotEmpty() }
            return pool(
                subjectId, prov, now, null, QuotaPool.Kind.allowance,
                label = name?.let { "New API · $it" } ?: "New API token quota",
                amounts = if (unlimited) QuotaAmounts(used = used)
                else QuotaAmounts(total = granted, used = used, remaining = available),
                unlimited = unlimited,
                unit = QuotaUnit.Custom("quota"),
            )
        }

        // SiliconFlow user info.
        val sfTotal = d.dec("totalBalance")
        if (sfTotal != null) {
            val parts = mutableListOf<QuotaAmounts.Part>()
            d.dec("chargeBalance")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.ToppedUp, it) }
            d.dec("balance")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.Granted, it) }
            val currency = currencyCode(d) ?: currencyCode(root) ?: siliconFlowCurrency(url)
            return pool(
                subjectId, prov, now, currency, QuotaPool.Kind.prepaidBalance,
                label = currency?.let { "$it balance" } ?: "SiliconFlow balance",
                amounts = QuotaAmounts(remaining = sfTotal, breakdown = parts),
            )
        }

        // Moonshot balance.
        val msAvail = d.dec("available_balance")
        if (msAvail != null) {
            val parts = mutableListOf<QuotaAmounts.Part>()
            d.dec("voucher_balance")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.Voucher, it) }
            d.dec("cash_balance")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.Cash, it) }
            val currency = currencyCode(d) ?: currencyCode(root) ?: moonshotCurrency(url)
            return pool(
                subjectId, prov, now, currency, QuotaPool.Kind.prepaidBalance,
                label = currency?.let { "$it balance" } ?: "Balance",
                amounts = QuotaAmounts(remaining = msAvail, breakdown = parts),
            )
        }

        return generic(d, root, url, subjectId, prov, now)
    }

    private fun generic(
        o: JsonObject,
        root: JsonObject?,
        url: String,
        subjectId: QuotaSubjectID,
        prov: QuotaProvenance,
        now: Long,
    ): QuotaPool? {
        fun decAny(vararg keys: String): BigDecimal? {
            for (k in keys) o.dec(k)?.let { return it }
            return null
        }
        val currency = currencyCode(o) ?: root?.let { currencyCode(it) }
        val remaining = decAny(
            "balance", "remaining", "remaining_balance", "available_balance",
            "available", "credit", "credits", "quota",
        )
        val total = decAny("total", "limit", "total_balance")
        val used = decAny("used", "total_used", "spent")
        val unit = currency?.let { QuotaUnit.Currency(it) } ?: QuotaUnit.Custom("balance")
        return when {
            remaining != null -> pool(
                subjectId, prov, now, currency, QuotaPool.Kind.prepaidBalance,
                label = currency?.let { "$it balance" } ?: "Balance",
                amounts = QuotaAmounts(remaining = remaining),
                unit = unit,
            )
            total != null && used != null -> {
                val left = total.subtract(used).max(BigDecimal.ZERO).canonical()
                pool(
                    subjectId, prov, now, currency, QuotaPool.Kind.spendCap,
                    label = currency?.let { "$it spend limit" } ?: "Spend limit",
                    amounts = QuotaAmounts(total = total, used = used, remaining = left),
                    unit = unit,
                )
            }
            else -> null
        }
    }

    // MARK: - Vendor-specific helpers

    private fun sub2api(
        o: JsonObject,
        subjectId: QuotaSubjectID,
        prov: QuotaProvenance,
        now: Long,
    ): QuotaPool {
        val isValid = o.bool("isValid") == true
        val keyUsed = o.obj("usage")?.obj("total")?.dec("actual_cost")
        val mode = o.str("mode")
        val q = o.obj("quota")
        var kind = QuotaPool.Kind.prepaidBalance
        var label = "Sub2API wallet"
        var amounts: QuotaAmounts? = null
        var unlimited = false
        when {
            mode == "quota_limited" -> {
                val limit = q?.dec("limit")
                val remaining = q?.dec("remaining")?.max(BigDecimal.ZERO)
                val used = q?.dec("used") ?: keyUsed
                kind = if (limit != null) QuotaPool.Kind.spendCap else QuotaPool.Kind.prepaidBalance
                label = "Sub2API key quota"
                amounts = QuotaAmounts(total = limit, used = used, remaining = remaining)
            }
            o.dec("balance") != null -> {
                label = "Sub2API wallet"
                amounts = QuotaAmounts(remaining = o.dec("balance"))
            }
            else -> {
                val remaining = o.dec("remaining")
                label = "Sub2API subscription"
                if (remaining != null && remaining.signum() < 0) {
                    unlimited = true
                    amounts = keyUsed?.let { QuotaAmounts(used = it) }
                } else {
                    amounts = QuotaAmounts(used = keyUsed, remaining = remaining)
                }
            }
        }
        if (amounts?.remaining == null && amounts?.total == null && !unlimited) {
            throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "Sub2API response without amounts")
        }
        return pool(
            subjectId, prov, now, "USD", kind, label, amounts,
            unlimited = unlimited,
            verdict = if (isValid) null else QuotaPool.Verdict.rejected,
        )
    }

    private fun liteLLM(
        o: JsonObject,
        info: JsonObject,
        spend: BigDecimal?,
        max: BigDecimal?,
        subjectId: QuotaSubjectID,
        prov: QuotaProvenance,
        now: Long,
    ): QuotaPool {
        val alias = info.str("key_alias")?.trim()?.takeIf { it.isNotEmpty() }
        val status = info.str("status")
        val rejected = info.bool("blocked") == true || (status != null && status != "active")
        val remaining = if (max != null && spend != null) max.subtract(spend).max(BigDecimal.ZERO).canonical() else null
        return pool(
            subjectId, prov, now, "USD", QuotaPool.Kind.spendCap,
            label = alias?.let { "LiteLLM · $it" } ?: "LiteLLM key budget",
            amounts = if (max != null) QuotaAmounts(total = max, used = spend, remaining = remaining)
            else QuotaAmounts(used = spend),
            unlimited = max == null,
            verdict = if (rejected) QuotaPool.Verdict.rejected else null,
        )
    }

    private fun novita(
        available: BigDecimal,
        o: JsonObject,
        subjectId: QuotaSubjectID,
        prov: QuotaProvenance,
        now: Long,
    ): QuotaPool {
        fun scale(v: BigDecimal) = v.movePointLeft(4).canonical()
        val parts = mutableListOf<QuotaAmounts.Part>()
        o.dec("cashBalance")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.ToppedUp, scale(it)) }
        o.dec("creditLimit")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.Other("credit limit"), scale(it)) }
        return pool(
            subjectId, prov, now, "USD", QuotaPool.Kind.prepaidBalance,
            label = "Novita AI credits",
            amounts = QuotaAmounts(remaining = scale(available), breakdown = parts),
        )
    }

    private fun openAIBilling(
        root: JsonObject,
        hard: BigDecimal,
        data: JsonObject?,
        subjectId: QuotaSubjectID,
        prov: QuotaProvenance,
        now: Long,
    ): QuotaPool {
        val cents = root.dec("total_usage") ?: data?.dec("total_usage")
        val usedUSD = cents?.divide(BigDecimal(100))?.canonical() ?: root.dec("used_usd") ?: data?.dec("used_usd")
        val unlimited = hard >= UNLIMITED_SENTINEL_USD
        val remaining = if (usedUSD != null) hard.subtract(usedUSD).max(BigDecimal.ZERO).canonical() else null
        return pool(
            subjectId, prov, now, "USD", QuotaPool.Kind.spendCap,
            label = "Relay billing",
            amounts = if (unlimited) usedUSD?.let { QuotaAmounts(used = it) }
            else QuotaAmounts(total = hard, used = usedUSD, remaining = remaining),
            unlimited = unlimited,
        )
    }

    // MARK: - Pool construction

    private fun pool(
        subjectId: QuotaSubjectID,
        prov: QuotaProvenance,
        now: Long,
        currency: String?,
        kind: QuotaPool.Kind,
        label: String,
        amounts: QuotaAmounts?,
        unlimited: Boolean = false,
        verdict: QuotaPool.Verdict? = null,
        unit: QuotaUnit = currency?.let { QuotaUnit.Currency(it) } ?: QuotaUnit.Custom("balance"),
    ): QuotaPool {
        val key = QuotaPoolKey.of(subjectId, "balance", currency ?: kind.name)
        return QuotaPool(
            id = key, subjectId = subjectId, kind = kind, unit = unit,
            vendorLabel = label, amounts = amounts, verdict = verdict,
            unlimited = unlimited, provenance = prov, observedAt = now,
        )
    }

    // MARK: - Currency inference

    private fun currencyCode(o: JsonObject?): String? {
        if (o == null) return null
        for (key in listOf("currency", "currency_code", "currencyCode", "unit")) {
            val raw = o.str(key)?.trim()?.uppercase() ?: continue
            if (raw.matches(Regex("^[A-Z]{3}$"))) return raw
        }
        return null
    }

    /** The two official hosts each bill in one currency; other hosts are not guessed. */
    private fun siliconFlowCurrency(url: String): String? =
        when (runCatching { URI(url).host }.getOrNull()?.lowercase()) {
            "api.siliconflow.com" -> "USD"
            "api.siliconflow.cn" -> "CNY"
            else -> null
        }

    private fun moonshotCurrency(url: String): String? =
        when (runCatching { URI(url).host }.getOrNull()?.lowercase()) {
            "api.moonshot.ai" -> "USD"
            "api.moonshot.cn" -> "CNY"
            else -> null
        }
}
