package org.ankivoice.provider

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AV-043's paid route: the pinned endpoint at or below the pinned prices, and a reply that reports its cost. */
class PaidRouteTest {
    private val pin = PaidRoute.PINNED

    private fun listing(
        id: String = pin.model,
        tag: String = pin.provider,
        prompt: String = pin.promptUsdPerToken.toPlainString(),
        completion: String = pin.completionUsdPerToken.toPlainString(),
        more: String = "",
        extra: String = "",
        providerName: String = "OpenAI",
    ) = Json.parse(
        """{"data":{"id":"$id","endpoints":[{"name":"OpenAI | $id-2025-04-14","provider_name":"$providerName","tag":"$tag",""" +
            """"pricing":{"prompt":"$prompt","completion":"$completion"$more}}$extra]}}""",
    )

    private fun reply(
        model: String = pin.model,
        provider: String? = "OpenAI",
        usage: String = """{"prompt_tokens":298,"completion_tokens":41,"cost":0.0000318}""",
    ) = Json.parse(
        """{"model":"$model",""" + (provider?.let { """"provider":"$it",""" } ?: "") +
            """"usage":$usage,"choices":[{"finish_reason":"stop","message":{"content":"{}"}}]}""",
    )

    private val accepted: Set<String> = setOf(pin.provider, "OpenAI")

    private fun refusal(check: RouteCheck): String = assertInstanceOf(RouteCheck.Refused::class.java, check).reason

    private fun allowed(check: RouteCheck): RouteCheck.Allowed = assertInstanceOf(RouteCheck.Allowed::class.java, check)

    private fun same(expected: String, actual: BigDecimal) =
        assertTrue(BigDecimal(expected).compareTo(actual) == 0, "expected $expected, got ${actual.toPlainString()}")

    @Test
    fun `the pin names a paid model with listed prices and a bounded ceiling`() {
        assertFalse(pin.model.endsWith(":free"))
        assertTrue(pin.promptUsdPerToken.signum() > 0 && pin.completionUsdPerToken.signum() > 0)
        val ceiling = pin.promptUsdPerToken * BigDecimal(PaidRoute.PROMPT_TOKEN_CAP) + pin.completionUsdPerToken * BigDecimal(PaidRoute.MAX_TOKENS)
        assertTrue(ceiling.compareTo(pin.ceilingUsd) == 0)
        assertTrue(pin.ceilingUsd < BigDecimal("0.01"), "one request holds less than a cent: ${pin.ceilingUsd.toPlainString()}")
        assertEquals(FreeRoute.MAX_TOKENS, PaidRoute.MAX_TOKENS)
        assertEquals(0, PaidRoute.TEMPERATURE)
        assertThrows(IllegalArgumentException::class.java) { PaidRoutePin.of("liquid/lfm-2.5-2.6b:free", "liquid/fp8", "0", "0") }
    }

    @Test
    fun `the payload pins the paid model, provider and the listed prices as the maximum`() {
        val payload = PaidRoute.payload(pin, "system", "user")
        assertEquals(pin.model, payload["model"])
        assertEquals(false, payload["stream"])
        assertEquals(0, payload["temperature"])
        assertEquals(1024, payload["max_tokens"])
        assertEquals(mapOf("type" to "json_object"), payload["response_format"])
        assertEquals(FreeRoute.payload("system", "user").keys, payload.keys, "the same envelope as the free route")
        val provider = payload["provider"] as Map<*, *>
        assertEquals(listOf(pin.provider), provider["only"])
        assertEquals(false, provider["allow_fallbacks"])
        assertEquals(true, provider["require_parameters"])
        val maxPrice = provider["max_price"] as Map<*, *>
        // USD per million tokens, the unit OpenRouter's max_price uses; per-request pricing stays refused.
        same(pin.promptUsdPerToken.multiply(BigDecimal(1_000_000)).toPlainString(), maxPrice["prompt"] as BigDecimal)
        same(pin.completionUsdPerToken.multiply(BigDecimal(1_000_000)).toPlainString(), maxPrice["completion"] as BigDecimal)
        assertEquals(0, maxPrice["request"])
        val text = Json.write(payload)
        assertTrue(text.contains(""""max_price":{"prompt":${pin.promptUsdPerMillion.toPlainString()},"completion":${pin.completionUsdPerMillion.toPlainString()},"request":0}"""), text)
        for (forbidden in listOf("tools", "tool_choice", "plugins", "web_search", "route", "models", "transforms")) {
            assertFalse(text.contains("\"$forbidden\""), forbidden)
        }
    }

    @Test
    fun `a listing at or below the pinned prices is allowed and carries the identities`() {
        val check = allowed(PaidRoute.priceCheck(pin, listing()))
        assertEquals("OpenAI | ${pin.model}-2025-04-14", check.endpointName)
        assertEquals(setOf(pin.provider, "OpenAI"), check.providers)
        allowed(PaidRoute.priceCheck(pin, listing(prompt = "0.00000005", completion = "0.0000002")))
        // Prices this request cannot incur are not consulted; bounded ones must stay under the pin.
        val realistic = ""","web_search":"0.01","input_cache_read":"0.000000025","input_cache_write":"0.0000002","image":"0.001","discount":0"""
        allowed(PaidRoute.priceCheck(pin, listing(more = realistic)))
        allowed(PaidRoute.priceCheck(pin, listing(more = ""","internal_reasoning":"${pin.completionUsdPerToken.toPlainString()}","request":"0"""")))
    }

    @Test
    fun `a listing above the pinned prices refuses the route`() {
        assertTrue(refusal(PaidRoute.priceCheck(pin, listing(prompt = "0.00000011"))).contains("prompt"))
        assertTrue(refusal(PaidRoute.priceCheck(pin, listing(completion = "0.00000041"))).contains("completion"))
        assertTrue(refusal(PaidRoute.priceCheck(pin, listing(more = ""","request":"0.001""""))).contains("request"))
        assertTrue(refusal(PaidRoute.priceCheck(pin, listing(more = ""","internal_reasoning":"0.000001""""))).contains("internal_reasoning"))
        assertTrue(refusal(PaidRoute.priceCheck(pin, listing(more = ""","input_cache_read":"0.000001""""))).contains("input_cache_read"))
        assertTrue(refusal(PaidRoute.priceCheck(pin, listing(prompt = "cheap"))).contains("missing price"))
        assertTrue(refusal(PaidRoute.priceCheck(pin, listing(more = ""","internal_reasoning":"soon""""))).contains("unknown"))
    }

    @Test
    fun `another model, tag or an ambiguous tag refuses the route`() {
        assertEquals("the endpoint is not the pinned paid model", refusal(PaidRoute.priceCheck(pin, listing(id = "openai/gpt-4.1-mini"))))
        assertEquals("the pinned paid provider is unavailable or ambiguous", refusal(PaidRoute.priceCheck(pin, listing(tag = "azure"))))
        val duplicated = """,{"name":"second","tag":"${pin.provider}","pricing":{"prompt":"0","completion":"0"}}"""
        assertEquals("the pinned paid provider is unavailable or ambiguous", refusal(PaidRoute.priceCheck(pin, listing(extra = duplicated))))
        assertEquals("no endpoint data", refusal(PaidRoute.priceCheck(pin, Json.parse("""{"error":"nope"}"""))))
    }

    @Test
    fun `a paid reply with the pinned model and a numeric cost is verified`() {
        val verified = assertInstanceOf(PaidReplyCheck.Verified::class.java, PaidRoute.replyCheck(pin, reply(), accepted))
        assertEquals("OpenAI", verified.provider)
        same("0.0000318", verified.costUsd)
        same("0.0000318", assertInstanceOf(PaidReplyCheck.Verified::class.java, PaidRoute.replyCheck(pin, reply(provider = pin.provider), accepted)).costUsd)
        assertEquals(pin.provider, assertInstanceOf(PaidReplyCheck.Verified::class.java, PaidRoute.replyCheck(pin, reply(provider = null), accepted)).provider)
        same("0", assertInstanceOf(PaidReplyCheck.Verified::class.java, PaidRoute.replyCheck(pin, reply(usage = """{"cost":0}"""), accepted)).costUsd)
    }

    @Test
    fun `a paid reply from another model or provider is refused and its cost is carried`() {
        val model = assertInstanceOf(PaidReplyCheck.Refused::class.java, PaidRoute.replyCheck(pin, reply(model = "openai/gpt-4.1-mini"), accepted))
        assertTrue(model.reason.contains("not the pinned paid model"))
        same("0.0000318", checkNotNull(model.reportedCostUsd))
        val provider = assertInstanceOf(PaidReplyCheck.Refused::class.java, PaidRoute.replyCheck(pin, reply(provider = "Azure"), accepted))
        assertTrue(provider.reason.contains("not the pinned paid provider"))
        same("0.0000318", checkNotNull(provider.reportedCostUsd))
    }

    @Test
    fun `a paid reply without a numeric cost is refused with nothing to charge but the ceiling`() {
        val absent = assertInstanceOf(PaidReplyCheck.Refused::class.java, PaidRoute.replyCheck(pin, reply(usage = """{"prompt_tokens":298}"""), accepted))
        assertEquals("no reported cost", absent.reason)
        assertNull(absent.reportedCostUsd)
        assertNull(assertInstanceOf(PaidReplyCheck.Refused::class.java, PaidRoute.replyCheck(pin, reply(usage = """{"cost":"unknown"}"""), accepted)).reportedCostUsd)
        assertNull(assertInstanceOf(PaidReplyCheck.Refused::class.java, PaidRoute.replyCheck(pin, reply(usage = """{"cost":-1}"""), accepted)).reportedCostUsd)
        assertEquals("unreadable reply", assertInstanceOf(PaidReplyCheck.Refused::class.java, PaidRoute.replyCheck(pin, "nope", accepted)).reason)
    }
}
