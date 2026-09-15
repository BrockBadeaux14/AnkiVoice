package org.ankivoice.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AV-006's free-only route: one pinned model and provider, every price zero. */
class FreeRouteGuardTest {
    private fun endpoints(
        id: String = FreeRoute.MODEL,
        tag: String = FreeRoute.PROVIDER,
        pricing: String = """{"prompt":"0","completion":"0","request":"0","image":"0"}""",
        extra: String = "",
    ) = Json.parse(
        """{"data":{"id":"$id","endpoints":[{"name":"Liquid | fp8","tag":"$tag","pricing":$pricing}$extra]}}""",
    )

    private fun reply(
        model: String = FreeRoute.MODEL,
        provider: String = FreeRoute.PROVIDER,
        usage: String = """{"cost":0}""",
    ) = Json.parse("""{"model":"$model","provider":"$provider","usage":$usage,"choices":[]}""")

    private fun refusal(check: RouteCheck): String = assertInstanceOf(RouteCheck.Refused::class.java, check).reason

    @Test
    fun `the payload pins the free model, provider and output limits`() {
        val payload = FreeRoute.payload("system", "user")
        assertEquals(FreeRoute.MODEL, payload["model"])
        assertEquals(false, payload["stream"])
        assertEquals(0, payload["temperature"])
        assertEquals(1024, payload["max_tokens"])
        assertEquals(mapOf("type" to "json_object"), payload["response_format"])
        val provider = payload["provider"] as Map<*, *>
        assertEquals(listOf(FreeRoute.PROVIDER), provider["only"])
        assertEquals(false, provider["allow_fallbacks"])
        assertEquals(true, provider["require_parameters"])
        assertEquals(mapOf("prompt" to 0, "completion" to 0, "request" to 0), provider["max_price"])
    }

    @Test
    fun `the payload requests no tools, plugins, search or router`() {
        val payload = FreeRoute.payload("system", "user")
        assertEquals(
            setOf("model", "stream", "temperature", "max_tokens", "provider", "response_format", "messages"),
            payload.keys,
        )
        val text = Json.write(payload)
        for (forbidden in listOf("tools", "tool_choice", "plugins", "web_search", "route", "models", "transforms")) {
            assertFalse(text.contains("\"$forbidden\""), forbidden)
        }
        assertTrue(FreeRoute.endpointsUrl.startsWith("https://"))
        assertTrue(FreeRoute.completionsUrl.startsWith("https://"))
        assertTrue(FreeRoute.MODEL.endsWith(":free"))
    }

    @Test
    fun `the messages carry only what the caller supplied`() {
        val messages = FreeRoute.payload("instruction", "context") ["messages"] as List<*>
        assertEquals(
            listOf(mapOf("role" to "system", "content" to "instruction"), mapOf("role" to "user", "content" to "context")),
            messages,
        )
    }

    @Test
    fun `a zero-priced pinned endpoint is allowed`() {
        assertEquals(RouteCheck.Allowed("Liquid | fp8"), FreeRoute.priceCheck(endpoints()))
        assertEquals(RouteCheck.Allowed("Liquid | fp8"), FreeRoute.priceCheck(endpoints(pricing = """{"prompt":0,"completion":0.0}""")))
    }

    @Test
    fun `a changed price refuses the route`() {
        assertTrue(refusal(FreeRoute.priceCheck(endpoints(pricing = """{"prompt":"0.0000001","completion":"0"}"""))).contains("prompt"))
        assertTrue(refusal(FreeRoute.priceCheck(endpoints(pricing = """{"prompt":"0","completion":"0.6"}"""))).contains("completion"))
    }

    @Test
    fun `an unknown or missing price refuses the route`() {
        assertTrue(refusal(FreeRoute.priceCheck(endpoints(pricing = """{"prompt":"free","completion":"0"}"""))).contains("prompt"))
        assertEquals("missing price information", refusal(FreeRoute.priceCheck(endpoints(pricing = """{"prompt":"0"}"""))))
        assertEquals("missing price information", refusal(FreeRoute.priceCheck(endpoints(pricing = "null"))))
    }

    @Test
    fun `another model or an ambiguous provider refuses the route`() {
        assertEquals("the endpoint is not the pinned model", refusal(FreeRoute.priceCheck(endpoints(id = "liquid/lfm-2.5-2.6b"))))
        assertEquals(
            "the pinned provider is unavailable or ambiguous",
            refusal(FreeRoute.priceCheck(endpoints(tag = "another/provider"))),
        )
        val duplicated = """,{"name":"second","tag":"${FreeRoute.PROVIDER}","pricing":{"prompt":"0","completion":"0"}}"""
        assertEquals("the pinned provider is unavailable or ambiguous", refusal(FreeRoute.priceCheck(endpoints(extra = duplicated))))
        assertEquals("no endpoint data", refusal(FreeRoute.priceCheck(Json.parse("""{"error":"nope"}"""))))
    }

    @Test
    fun `a reply served by a fallback model or provider refuses the route`() {
        assertTrue(refusal(FreeRoute.replyCheck(reply(model = "openai/gpt-4o"))).contains("not the pinned model"))
        assertTrue(refusal(FreeRoute.replyCheck(reply(provider = "novita"))).contains("not the pinned provider"))
    }

    @Test
    fun `a reply without a verified zero cost refuses the route`() {
        assertEquals("no reported cost", refusal(FreeRoute.replyCheck(reply(usage = """{"total_tokens":12}"""))))
        assertEquals("reported cost is not zero", refusal(FreeRoute.replyCheck(reply(usage = """{"cost":0.000004}"""))))
        assertEquals("reported cost is not zero", refusal(FreeRoute.replyCheck(reply(usage = """{"cost":"unknown"}"""))))
        assertEquals(RouteCheck.Allowed(FreeRoute.PROVIDER), FreeRoute.replyCheck(reply(usage = """{"cost":"0.0"}""")))
    }
}
