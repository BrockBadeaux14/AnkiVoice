package org.ankivoice.provider

import java.math.BigDecimal

/**
 * AV-043: one paid endpoint pin — the model, the endpoint tag, and the prices the
 * endpoints listing states for it, in USD per token exactly as OpenRouter lists them.
 *
 * The shipped pin is [PaidRoute.PINNED]. The AV-043 spike constructs a pin per candidate
 * so the harness can measure two or three models on the tuning 20 through the shipped
 * provider; nothing else constructs one.
 */
data class PaidRoutePin(
    val model: String,
    val provider: String,
    val promptUsdPerToken: BigDecimal,
    val completionUsdPerToken: BigDecimal,
) {
    init {
        require(model.isNotBlank() && provider.isNotBlank()) { "a paid pin names a model and an endpoint tag" }
        require(!model.endsWith(":free")) { "a paid pin names a paid model, not a free variant" }
        require(promptUsdPerToken.signum() >= 0 && completionUsdPerToken.signum() >= 0) { "prices are not negative" }
    }

    /**
     * The most one request can cost at the listed prices: every prompt token up to
     * [PaidRoute.PROMPT_TOKEN_CAP] and every completion token up to [PaidRoute.MAX_TOKENS].
     * The ledger holds this amount from dispatch until the reply's own cost replaces it, and
     * a reply that reports no cost is charged this amount, because the money may have been
     * spent.
     */
    val ceilingUsd: BigDecimal
        get() = promptUsdPerToken * PaidRoute.PROMPT_TOKEN_CAP.toBigDecimal() +
            completionUsdPerToken * PaidRoute.MAX_TOKENS.toBigDecimal()

    /** USD per million tokens, the unit OpenRouter's `max_price` uses. */
    val promptUsdPerMillion: BigDecimal get() = (promptUsdPerToken * PER_MILLION).stripTrailingZeros()
    val completionUsdPerMillion: BigDecimal get() = (completionUsdPerToken * PER_MILLION).stripTrailingZeros()

    companion object {
        private val PER_MILLION = BigDecimal(1_000_000)

        fun of(model: String, provider: String, promptUsdPerToken: String, completionUsdPerToken: String): PaidRoutePin =
            PaidRoutePin(model, provider, BigDecimal(promptUsdPerToken), BigDecimal(completionUsdPerToken))
    }
}

/** The paid reply's cost verification. A refused reply is never a label. */
sealed interface PaidReplyCheck {
    data class Verified(val provider: String, val costUsd: BigDecimal) : PaidReplyCheck

    /**
     * [reportedCostUsd] is still charged although the reply is refused: the money may have
     * been spent. Null means no cost was reported at all, and the caller charges the
     * request's ceiling instead.
     */
    data class Refused(val reason: String, val reportedCostUsd: BigDecimal?) : PaidReplyCheck
}

/**
 * AV-043's paid fallback route: the same envelope as [FreeRoute] — one pinned model through
 * one pinned endpoint tag, fallbacks off, `require_parameters`, temperature 0, JSON object
 * output and the same 1,024-token cap — with `max_price` set to the pinned listed prices
 * so a repriced endpoint is declined by OpenRouter rather than paid.
 *
 * Before a session, [priceCheck] confirms the pinned endpoint is still listed at or below
 * the pinned prices. After a reply, [replyCheck] requires the pinned model and a numeric
 * `usage.cost`; the ledger charges that cost against the owner's daily cap. Anything else
 * is refused and never becomes a label.
 *
 * The pin below is the AV-043 candidate chosen from the public endpoints listing on
 * September 16, 2026, pending the bounded spike recorded in docs/testing/av043/results.md.
 */
internal object PaidRoute {
    /**
     * The pinned paid endpoint. `docs/testing/av043/results.md` records the reason and the
     * measured cost and latency; `tests/test_av043_paid_route.py` fails if this drifts from
     * what that page states.
     */
    val PINNED: PaidRoutePin = PaidRoutePin.of(
        model = "openai/gpt-4.1-nano",
        provider = "openai",
        promptUsdPerToken = "0.0000001",
        completionUsdPerToken = "0.0000004",
    )

    /** #18's decoding settings, unchanged for the paid route. */
    const val MAX_TOKENS: Int = FreeRoute.MAX_TOKENS
    const val TEMPERATURE: Int = FreeRoute.TEMPERATURE
    const val TIMEOUT_MS: Int = FreeRoute.TIMEOUT_MS

    /**
     * The prompt-side token cap the ceiling assumes. AV-006 measured about 295 prompt tokens
     * per grading request over the VoiceQA fields and a bounded 15-second answer; the cap is
     * generous so the ceiling is an upper bound rather than an estimate.
     */
    const val PROMPT_TOKEN_CAP: Int = 4_096

    /** Price keys the request can never incur, so the listing may price them freely. */
    private val UNREACHABLE_PRICES = setOf(
        "image", "audio", "input_audio_cache", "video", "web_search", "input_cache_write", "discount",
    )

    fun endpointsUrl(pin: PaidRoutePin): String = FreeRoute.BASE_URL + "models/" + pin.model + "/endpoints"

    val completionsUrl: String get() = FreeRoute.completionsUrl

    /** The same envelope as [FreeRoute.payload], with the pinned prices as the maximum. */
    fun payload(pin: PaidRoutePin, system: String, user: String): Map<String, Any?> = linkedMapOf(
        "model" to pin.model,
        "stream" to false,
        "temperature" to TEMPERATURE,
        "max_tokens" to MAX_TOKENS,
        "provider" to linkedMapOf(
            "only" to listOf(pin.provider),
            "allow_fallbacks" to false,
            "require_parameters" to true,
            "max_price" to linkedMapOf(
                "prompt" to pin.promptUsdPerMillion,
                "completion" to pin.completionUsdPerMillion,
                "request" to 0,
            ),
            "data_collection" to "allow",
        ),
        "response_format" to linkedMapOf("type" to "json_object"),
        "messages" to listOf(
            linkedMapOf("role" to "system", "content" to system),
            linkedMapOf("role" to "user", "content" to user),
        ),
    )

    /**
     * The pinned paid endpoint, listed at or below the pinned prices, or a refusal.
     *
     * Prompt and completion prices must not exceed the pin. A per-request price must be
     * zero, a reasoning-token price must not exceed the pinned completion price, and a
     * cache-read price must not exceed the pinned prompt price, so the ceiling stays an
     * upper bound. Prices for modalities this request cannot contain are not consulted.
     */
    fun priceCheck(pin: PaidRoutePin, body: Any?): RouteCheck {
        val data = body.asObject()?.child("data").asObject() ?: return RouteCheck.Refused("no endpoint data")
        if (data.child("id").asText() != pin.model) return RouteCheck.Refused("the endpoint is not the pinned paid model")
        val endpoints = data.child("endpoints").asList() ?: return RouteCheck.Refused("no endpoints listed")
        val endpoint = endpoints.mapNotNull { it.asObject() }.filter { it.child("tag").asText() == pin.provider }.singleOrNull()
            ?: return RouteCheck.Refused("the pinned paid provider is unavailable or ambiguous")
        val prices = endpoint.child("pricing").asObject() ?: return RouteCheck.Refused("missing price information")
        val prompt = price(prices.child("prompt")) ?: return RouteCheck.Refused("missing price information")
        val completion = price(prices.child("completion")) ?: return RouteCheck.Refused("missing price information")
        if (prompt > pin.promptUsdPerToken) return RouteCheck.Refused("the listed prompt price exceeds the pinned price; no request sent")
        if (completion > pin.completionUsdPerToken) {
            return RouteCheck.Refused("the listed completion price exceeds the pinned price; no request sent")
        }
        for ((name, value) in prices) {
            val key = name as? String ?: continue
            if (key == "prompt" || key == "completion" || key in UNREACHABLE_PRICES) continue
            val listed = price(value) ?: return RouteCheck.Refused("unknown $key price; no request sent")
            val bound = when (key) {
                "request" -> BigDecimal.ZERO
                "internal_reasoning" -> pin.completionUsdPerToken
                "input_cache_read" -> pin.promptUsdPerToken
                else -> BigDecimal.ZERO
            }
            if (listed > bound) return RouteCheck.Refused("the listed $key price is not covered by the pinned prices; no request sent")
        }
        return RouteCheck.Allowed(
            endpoint.child("name").asText() ?: endpoint.child("tag").asText().orEmpty(),
            FreeRoute.providerIdentities(endpoint, pin.provider),
        )
    }

    /**
     * The reply must come from the pinned paid model and provider and report a numeric
     * `usage.cost`. A refusal carries the reported cost, if any, so it is still charged.
     */
    fun replyCheck(pin: PaidRoutePin, body: Any?, accepted: Set<String> = setOf(pin.provider)): PaidReplyCheck {
        val reply = body.asObject() ?: return PaidReplyCheck.Refused("unreadable reply", null)
        val cost = price(reply.child("usage").asObject()?.child("cost"))
        val model = reply.child("model").asText()
        if (model != pin.model) return PaidReplyCheck.Refused("served by $model, not the pinned paid model", cost)
        val provider = reply.child("provider").asText()
        if (provider != null && provider !in accepted) {
            return PaidReplyCheck.Refused("served by $provider, not the pinned paid provider", cost)
        }
        if (cost == null) return PaidReplyCheck.Refused("no reported cost", null)
        return PaidReplyCheck.Verified(provider ?: pin.provider, cost)
    }

    /** A finite, non-negative decimal read from the listing's or the reply's own text, or null. */
    internal fun price(value: Any?): BigDecimal? {
        val text = JsonText.number(value) ?: return null
        return try {
            BigDecimal(text.trim()).takeIf { it.signum() >= 0 }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
