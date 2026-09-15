package org.ankivoice.provider

import java.math.BigDecimal

/** The pinned endpoint passed its zero-price check, or the reason it did not. */
internal sealed interface RouteCheck {
    data class Allowed(val endpointName: String) : RouteCheck

    data class Refused(val reason: String) : RouteCheck
}

/**
 * AV-006's free-only route, ported from `tools/av006_providers.py`.
 *
 * One pinned free model through one pinned provider, with fallbacks off, every maximum
 * price zero, temperature 0, JSON object output and a 1,024-token cap. The guard refuses
 * the route whenever the endpoint's advertised price is changed, nonzero or unreadable,
 * or the reply was not served by the pinned model and provider at a verified zero cost.
 * A refusal disables grading for the session; it never becomes a rating.
 *
 * `:provider` carries the request and the reply text. The grading instruction, the reply's
 * content and the label policy belong to #18.
 */
internal object FreeRoute {
    const val BASE_URL: String = "https://openrouter.ai/api/v1/"
    const val MODEL: String = "liquid/lfm-2.5-2.6b:free"
    const val PROVIDER: String = "liquid/fp8"
    const val MAX_TOKENS: Int = 1024
    const val TEMPERATURE: Int = 0
    const val TIMEOUT_MS: Int = 30_000

    val endpointsUrl: String get() = BASE_URL + "models/" + MODEL + "/endpoints"
    val completionsUrl: String get() = BASE_URL + "chat/completions"

    /**
     * The pinned request envelope. [system] and [user] are #18's message contents; nothing
     * else varies. No tools, plugins, search, router or streaming key is present, and
     * `require_parameters` makes the provider honour the pinned settings or decline.
     */
    fun payload(system: String, user: String): Map<String, Any?> = linkedMapOf(
        "model" to MODEL,
        "stream" to false,
        "temperature" to TEMPERATURE,
        "max_tokens" to MAX_TOKENS,
        "provider" to linkedMapOf(
            "only" to listOf(PROVIDER),
            "allow_fallbacks" to false,
            "require_parameters" to true,
            "max_price" to linkedMapOf("prompt" to 0, "completion" to 0, "request" to 0),
            // AV-006 measured the route with data_collection=allow; the disclosure says so.
            "data_collection" to "allow",
        ),
        "response_format" to linkedMapOf("type" to "json_object"),
        "messages" to listOf(
            linkedMapOf("role" to "system", "content" to system),
            linkedMapOf("role" to "user", "content" to user),
        ),
    )

    /** Ported from `validate_endpoint`: the pinned free endpoint at a zero price, or a refusal. */
    fun priceCheck(body: Any?): RouteCheck {
        val data = body.asObject()?.child("data").asObject() ?: return RouteCheck.Refused("no endpoint data")
        if (data.child("id").asText() != MODEL) return RouteCheck.Refused("the endpoint is not the pinned model")
        if (!MODEL.endsWith(":free")) return RouteCheck.Refused("only a free model is allowed")
        val endpoints = data.child("endpoints").asList() ?: return RouteCheck.Refused("no endpoints listed")
        val matches = endpoints.mapNotNull { it.asObject() }.filter { it.child("tag").asText() == PROVIDER }
        val endpoint = matches.singleOrNull()
            ?: return RouteCheck.Refused("the pinned provider is unavailable or ambiguous")
        val prices = endpoint.child("pricing").asObject() ?: return RouteCheck.Refused("missing price information")
        if (!prices.keys.containsAll(listOf("prompt", "completion"))) {
            return RouteCheck.Refused("missing price information")
        }
        for ((name, price) in prices) {
            if (!isZero(price)) return RouteCheck.Refused("nonzero or unknown $name price; no request sent")
        }
        return RouteCheck.Allowed(endpoint.child("name").asText() ?: endpoint.child("tag").asText().orEmpty())
    }

    /**
     * The reply must come from the pinned model and provider — a served fallback is a
     * refusal — and report a cost that reads as exactly zero. An absent or unreadable
     * cost is refused too.
     */
    fun replyCheck(body: Any?): RouteCheck {
        val reply = body.asObject() ?: return RouteCheck.Refused("unreadable reply")
        val model = reply.child("model").asText()
        if (model != MODEL) return RouteCheck.Refused("served by $model, not the pinned model")
        val provider = reply.child("provider").asText()
        if (provider != null && provider != PROVIDER) return RouteCheck.Refused("served by $provider, not the pinned provider")
        val cost = reply.child("usage").asObject()?.child("cost")
        if (cost == null) return RouteCheck.Refused("no reported cost")
        if (!isZero(cost)) return RouteCheck.Refused("reported cost is not zero")
        return RouteCheck.Allowed(provider ?: PROVIDER)
    }

    /** True only for a finite decimal that is exactly zero, however it is spelled. */
    private fun isZero(value: Any?): Boolean {
        val text = JsonText.number(value) ?: return false
        return try {
            BigDecimal(text.trim()).signum() == 0
        } catch (_: NumberFormatException) {
            false
        }
    }
}
