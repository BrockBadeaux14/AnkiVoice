package org.ankivoice.provider

import java.math.BigDecimal
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GraderFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The provider path with a fake transport: no live call is ever made here, and CI makes
 * none either. Every refusal is a #7 Grader failure or unavailable grading, never a rating.
 */
class GradingProviderTest {
    @TempDir
    lateinit var directory: File

    private val key = "sk-or-v1-0123456789abcdef0123456789abcdef"
    private var now = 1_789_400_000_000L
    private val ledgerFile: File get() = File(directory, "ledger.jsonl")
    private val pin = PaidRoute.PINNED

    private class FakeSettings(
        override var dailyLimit: Int = 50,
        override var disclosureAcknowledged: Boolean = true,
        override var dailyCapUsd: BigDecimal = QuotaLedger.DEFAULT_DAILY_CAP_USD,
    ) : ProviderSettings

    private class FakeTransport(
        var priceCheck: HttpResult = ok(ENDPOINTS),
        var completion: HttpResult = ok(reply()),
        var paidPriceCheck: HttpResult = ok(PAID_ENDPOINTS),
        var paidCompletion: HttpResult = ok(paidReply()),
        val onPost: () -> Unit = {},
    ) : HttpTransport {
        val calls = mutableListOf<String>()
        var lastBody: String = ""
        var lastKey: String = ""

        override fun get(url: String, key: String, timeoutMs: Int): HttpResult {
            calls += "GET $url"
            lastKey = key
            return if (url == FreeRoute.endpointsUrl) priceCheck else paidPriceCheck
        }

        override fun post(url: String, key: String, body: String, timeoutMs: Int): HttpResult {
            val free = body.contains(""""model":"${FreeRoute.MODEL}"""")
            calls += if (free) "POST free" else "POST paid"
            lastBody = body
            lastKey = key
            onPost()
            return if (free) completion else paidCompletion
        }

        fun posts(route: String) = calls.count { it == "POST $route" }

        companion object {
            fun ok(body: String) = HttpResult.Response(200, body)

            val ENDPOINTS = """{"data":{"id":"${FreeRoute.MODEL}","endpoints":[
                {"name":"Liquid | fp8","provider_name":"Liquid","tag":"${FreeRoute.PROVIDER}","pricing":{"prompt":"0","completion":"0"}}]}}"""

            val PAID_ENDPOINTS = """{"data":{"id":"${PaidRoute.PINNED.model}","endpoints":[
                {"name":"OpenAI | paid","provider_name":"OpenAI","tag":"${PaidRoute.PINNED.provider}",
                 "pricing":{"prompt":"${PaidRoute.PINNED.promptUsdPerToken.toPlainString()}",
                            "completion":"${PaidRoute.PINNED.completionUsdPerToken.toPlainString()}",
                            "web_search":"0.01","input_cache_read":"0.000000025","discount":0}}]}}"""

            fun reply(
                content: String = """{\"label\":\"correct\",\"reason\":\"ok\"}""",
                finish: String = "stop",
                cost: String = "0",
                provider: String = "Liquid",
            ) = """{"model":"${FreeRoute.MODEL}","provider":"$provider","usage":{"cost":$cost},
                   "choices":[{"finish_reason":"$finish","message":{"content":"$content"}}]}"""

            fun paidReply(
                content: String = """{\"label\":\"correct\",\"reason\":\"ok\"}""",
                finish: String = "stop",
                usage: String = """{"cost":0.0000318}""",
                model: String = PaidRoute.PINNED.model,
                provider: String = "OpenAI",
            ) = """{"model":"$model","provider":"$provider","usage":$usage,
                   "choices":[{"finish_reason":"$finish","message":{"content":"$content"}}]}"""
        }
    }

    private fun stack(
        transport: FakeTransport = FakeTransport(),
        settings: FakeSettings = FakeSettings(),
        credentials: CredentialStore = InMemoryCredentialStore(key),
        diagnostics: Diagnostics = Diagnostics(clock = { now }),
    ): Triple<GradingProvider, FakeTransport, Diagnostics> {
        val ledger = QuotaLedger(ledgerFile) { now }
        return Triple(GradingProvider(credentials, ledger, settings, transport, diagnostics, { now * 1_000_000 }), transport, diagnostics)
    }

    private fun failure(outcome: ProviderOutcome): Failure =
        assertInstanceOf(ProviderOutcome.Failed::class.java, outcome).failure

    private fun ledger() = QuotaLedger(ledgerFile) { now }

    private fun same(expected: String, actual: BigDecimal) =
        assertTrue(BigDecimal(expected).compareTo(actual) == 0, "expected $expected, got ${actual.toPlainString()}")

    // Session preconditions

    @Test
    fun `no key means grading is unavailable and nothing is sent`() {
        val (provider, transport, _) = stack(credentials = InMemoryCredentialStore(null))
        val start = assertInstanceOf(SessionStart.Unavailable::class.java, provider.startSession("s1"))
        assertEquals(GradingUnavailable.NO_KEY, start.cause)
        assertTrue(start.cause.selfGradingAvailable)
        assertEquals(emptyList<String>(), transport.calls)
        assertFalse(ledgerFile.exists(), "the ledger is untouched")
        assertEquals(GradingUnavailable.NO_KEY, provider.unavailableCause)
    }

    @Test
    fun `an unacknowledged disclosure keeps grading off`() {
        val (provider, transport, _) = stack(settings = FakeSettings(disclosureAcknowledged = false))
        val start = assertInstanceOf(SessionStart.Unavailable::class.java, provider.startSession("s1"))
        assertEquals(GradingUnavailable.DISCLOSURE_REQUIRED, start.cause)
        assertEquals(emptyList<String>(), transport.calls)
    }

    @Test
    fun `the pre-session checks run for both routes and consume no allowance`() {
        val (provider, transport, _) = stack()
        val start = assertInstanceOf(SessionStart.Ready::class.java, provider.startSession("s1"))
        assertEquals("Liquid | fp8", start.endpointName)
        assertEquals(listOf("GET ${FreeRoute.endpointsUrl}", "GET ${PaidRoute.endpointsUrl(pin)}"), transport.calls)
        assertEquals(listOf(GradingRoute.FREE, GradingRoute.PAID), start.routes.map { it.route })
        assertTrue(start.routes.all { it.ready }, start.routes.toString())
        assertEquals("OpenAI | paid", start.routes[1].endpointName)
        assertEquals(30, start.allowance.sessionRemaining)
        assertFalse(ledgerFile.exists(), "a price check is not a grading request")
        assertNull(provider.unavailableCause)
    }

    @Test
    fun `a refused free price check leaves the paid route ready`() {
        val changed = """{"data":{"id":"${FreeRoute.MODEL}","endpoints":[
            {"name":"n","tag":"${FreeRoute.PROVIDER}","pricing":{"prompt":"0.2","completion":"0"}}]}}"""
        val (provider, transport, _) = stack(FakeTransport(priceCheck = FakeTransport.ok(changed)))
        val start = assertInstanceOf(SessionStart.Ready::class.java, provider.startSession("s1"))
        assertEquals("OpenAI | paid", start.endpointName, "the first usable route names the session")
        assertEquals(GradingUnavailable.ROUTE_REFUSED, start.routes[0].cause)
        assertEquals(LedgerStop.ROUTE_REFUSED, ledger().stopToday(GradingRoute.FREE))
        assertNull(ledger().stopToday(GradingRoute.PAID))
        assertEquals(GradingUnavailable.ROUTE_REFUSED, provider.unavailableCause(GradingRoute.FREE))
        assertNull(provider.unavailableCause, "grading is still available through the paid route")
        // A free request in the refused session dispatches nothing; a paid one is served.
        val free = assertInstanceOf(ProviderOutcome.Failed::class.java, provider.request("s1", "s", "u"))
        assertFalse(free.dispatched)
        assertEquals(GraderFailure.PROVIDER_ERROR, free.failure.mode)
        val paid = assertInstanceOf(ProviderOutcome.Content::class.java, provider.request("s1", "s", "u", route = GradingRoute.PAID))
        assertEquals(GradingRoute.PAID, paid.route)
        assertEquals(0, transport.posts("free"))
        assertEquals(1, transport.posts("paid"))
    }

    @Test
    fun `a refused paid price check leaves the free route ready`() {
        val repriced = FakeTransport.PAID_ENDPOINTS.replace(""""completion":"${pin.completionUsdPerToken.toPlainString()}"""", """"completion":"0.001"""")
        val (provider, transport, _) = stack(FakeTransport(paidPriceCheck = FakeTransport.ok(repriced)))
        val start = assertInstanceOf(SessionStart.Ready::class.java, provider.startSession("s1"))
        assertEquals("Liquid | fp8", start.endpointName)
        assertEquals(GradingUnavailable.PAID_ROUTE_REFUSED, start.routes[1].cause)
        assertTrue(start.routes[1].detail.contains("completion"), start.routes[1].detail)
        assertEquals(LedgerStop.PAID_ROUTE_REFUSED, ledger().stopToday(GradingRoute.PAID))
        assertNull(ledger().stopToday(GradingRoute.FREE))
        val paid = assertInstanceOf(ProviderOutcome.Failed::class.java, provider.request("s1", "s", "u", route = GradingRoute.PAID))
        assertFalse(paid.dispatched)
        assertEquals(0, transport.posts("paid"))
        assertInstanceOf(ProviderOutcome.Content::class.java, provider.request("s1", "s", "u"))
    }

    @Test
    fun `both routes refused is unavailable grading`() {
        val (provider, _, _) = stack(FakeTransport(priceCheck = HttpResult.Timeout, paidPriceCheck = HttpResult.Response(500, "")))
        val start = assertInstanceOf(SessionStart.Unavailable::class.java, provider.startSession("s1"))
        assertEquals(GradingUnavailable.ROUTE_REFUSED, start.cause)
        assertEquals(listOf(GradingUnavailable.ROUTE_REFUSED, GradingUnavailable.PAID_ROUTE_REFUSED), start.routes.map { it.cause })
        assertEquals(GradingUnavailable.ROUTE_REFUSED, provider.unavailableCause)
    }

    @Test
    fun `an exhausted allowance is checked before the session starts`() {
        QuotaLedger(ledgerFile) { now }.stop(LedgerStop.DAILY_LIMIT)
        val (provider, transport, _) = stack()
        val start = assertInstanceOf(SessionStart.Unavailable::class.java, provider.startSession("s1"))
        assertEquals(GradingUnavailable.QUOTA, start.cause)
        assertEquals(emptyList<String>(), transport.calls)
    }

    @Test
    fun `a zero cap turns the paid route off without a price check`() {
        val (provider, transport, _) = stack(settings = FakeSettings(dailyCapUsd = BigDecimal.ZERO))
        val start = assertInstanceOf(SessionStart.Ready::class.java, provider.startSession("s1"))
        assertEquals(listOf("GET ${FreeRoute.endpointsUrl}"), transport.calls)
        assertEquals(GradingUnavailable.PAID_DISABLED, start.routes[1].cause)
        val paid = assertInstanceOf(ProviderOutcome.Failed::class.java, provider.request("s1", "s", "u", route = GradingRoute.PAID))
        assertFalse(paid.dispatched)
        assertEquals(GraderFailure.PROVIDER_ERROR, paid.failure.mode)
        assertEquals(0, transport.posts("paid"))
        assertNull(ledger().stopToday(GradingRoute.PAID), "off is not exhausted")
    }

    // Requests

    @Test
    fun `a graded request reserves before dispatch and carries the reply text`() {
        var reservedWhenDispatched = 0
        val transport = FakeTransport(onPost = { reservedWhenDispatched = ledgerFile.readLines().count { it.isNotBlank() } })
        val (provider, _, _) = stack(transport)
        provider.startSession("s1")
        val outcome = provider.request("s1", "instruction", "context")
        assertEquals(1, reservedWhenDispatched, "the reservation must be on disk before the request is sent")
        val content = assertInstanceOf(ProviderOutcome.Content::class.java, outcome)
        assertEquals("""{"label":"correct","reason":"ok"}""", content.text)
        assertEquals(GradingRoute.FREE, content.route)
        assertTrue(transport.lastBody.contains(""""model":"${FreeRoute.MODEL}""""))
        assertTrue(transport.lastBody.contains(""""content":"instruction""""))
        assertEquals(key, transport.lastKey)
    }

    @Test
    fun `a free reply reporting the provider by name is accepted`() {
        // AV-017's live pass: the route reports `Liquid`, the pin is `liquid/fp8`.
        val (provider, _, diagnostics) = stack(FakeTransport(completion = FakeTransport.ok(FakeTransport.reply(provider = "Liquid"))))
        provider.startSession("s1")
        assertInstanceOf(ProviderOutcome.Content::class.java, provider.request("s1", "s", "u"))
        assertNull(ledger().stopToday(GradingRoute.FREE))
        assertTrue(diagnostics.counts().containsKey("reportedCost"))
        // By tag too, as the fixtures always did.
        ledgerFile.delete()
        val (byTag, _, _) = stack(FakeTransport(completion = FakeTransport.ok(FakeTransport.reply(provider = FreeRoute.PROVIDER))))
        byTag.startSession("s1")
        assertInstanceOf(ProviderOutcome.Content::class.java, byTag.request("s1", "s", "u"))
    }

    @Test
    fun `a rejected key turns both routes off for the session`() {
        val transport = FakeTransport(completion = HttpResult.Response(401, """{"error":{"message":"No auth"}}"""))
        val (provider, _, _) = stack(transport)
        provider.startSession("s1")
        assertEquals(GraderFailure.PROVIDER_ERROR, failure(provider.request("s1", "s", "u")).mode)
        // Still off on both routes, and no second dispatch.
        assertEquals(GraderFailure.PROVIDER_ERROR, failure(provider.request("s1", "s", "u")).mode)
        val paid = assertInstanceOf(ProviderOutcome.Failed::class.java, provider.request("s1", "s", "u", route = GradingRoute.PAID))
        assertFalse(paid.dispatched)
        assertEquals(1, transport.calls.count { it.startsWith("POST") })
        assertEquals(GradingUnavailable.KEY_REJECTED, provider.unavailableCause)
    }

    @Test
    fun `payment required and rate limiting stop the route they happened on`() {
        for ((status, stop) in listOf(402 to LedgerStop.PAYMENT_REQUIRED, 429 to LedgerStop.RATE_LIMITED)) {
            ledgerFile.delete()
            val (provider, transport, _) = stack(FakeTransport(completion = HttpResult.Response(status, "{}")))
            provider.startSession("s1")
            assertEquals(GraderFailure.QUOTA_EXHAUSTED, failure(provider.request("s1", "s", "u")).mode, "HTTP $status")
            assertEquals(stop, ledger().stopToday(GradingRoute.FREE), "HTTP $status")
            assertNull(ledger().stopToday(GradingRoute.PAID), "HTTP $status")
            // The paid route still dispatches in the same session.
            assertInstanceOf(ProviderOutcome.Content::class.java, provider.request("s1", "s", "u", route = GradingRoute.PAID))
            assertEquals(1, transport.posts("paid"))
        }
    }

    @Test
    fun `a timeout, a redirect and a provider error each become a Grader failure`() {
        val cases = listOf(
            HttpResult.Timeout to GraderFailure.GRADER_TIMEOUT,
            HttpResult.Refused("refusing a redirect from the pinned endpoint") to GraderFailure.PROVIDER_ERROR,
            HttpResult.Response(500, "oops") to GraderFailure.PROVIDER_ERROR,
        )
        for ((result, expected) in cases) {
            ledgerFile.delete()
            val (provider, _, _) = stack(FakeTransport(completion = result))
            provider.startSession("s1")
            val failed = assertInstanceOf(ProviderOutcome.Failed::class.java, provider.request("s1", "s", "u"))
            assertEquals(expected, failed.failure.mode, result.toString())
            assertTrue(failed.dispatched, result.toString())
        }
    }

    @Test
    fun `an unverified cost refuses the free reply and stops the free route only`() {
        val (provider, _, _) = stack(FakeTransport(completion = FakeTransport.ok(FakeTransport.reply(cost = "0.00002"))))
        provider.startSession("s1")
        val failure = failure(provider.request("s1", "s", "u"))
        assertEquals(GraderFailure.PROVIDER_ERROR, failure.mode)
        assertEquals("reported cost is not zero", failure.detail)
        assertEquals(LedgerStop.COST_NOT_VERIFIED, ledger().stopToday(GradingRoute.FREE))
        assertNull(ledger().stopToday(GradingRoute.PAID))
        assertInstanceOf(ProviderOutcome.Content::class.java, provider.request("s1", "s", "u", route = GradingRoute.PAID))
    }

    @Test
    fun `truncated, empty and unparsable replies are carried as failures`() {
        val cases = listOf(
            FakeTransport.ok(FakeTransport.reply(finish = "length")) to GraderFailure.OUTPUT_TRUNCATED,
            FakeTransport.ok(FakeTransport.reply(content = "")) to GraderFailure.PROVIDER_ERROR,
            FakeTransport.ok("<html>not json</html>") to GraderFailure.UNPARSABLE_RESPONSE,
        )
        for ((result, expected) in cases) {
            ledgerFile.delete()
            val (provider, _, _) = stack(FakeTransport(completion = result))
            provider.startSession("s1")
            assertEquals(expected, failure(provider.request("s1", "s", "u")).mode)
        }
    }

    @Test
    fun `the session limit refuses a thirty-first request without dispatching`() {
        val ledger = QuotaLedger(ledgerFile) { now }
        repeat(QuotaLedger.SESSION_LIMIT) { ledger.reserve("s1", 1_000) }
        val (provider, transport, _) = stack(settings = FakeSettings(dailyLimit = 1_000))
        val failure = failure(provider.request("s1", "s", "u"))
        assertEquals(GraderFailure.QUOTA_EXHAUSTED, failure.mode)
        assertEquals(LedgerStop.SESSION_LIMIT.reason, failure.detail)
        assertEquals(emptyList<String>(), transport.calls.filter { it.startsWith("POST") })
        // The paid route shares the session cap.
        assertEquals(GraderFailure.QUOTA_EXHAUSTED, failure(provider.request("s1", "s", "u", route = GradingRoute.PAID)).mode)
        assertEquals(emptyList<String>(), transport.calls.filter { it.startsWith("POST") })
    }

    @Test
    fun `an explicit retry is granted only within the remaining allowance`() {
        val (provider, transport, _) = stack(
            FakeTransport(completion = HttpResult.Timeout),
            settings = FakeSettings(dailyLimit = 2),
        )
        provider.startSession("s1")
        assertEquals(GraderFailure.GRADER_TIMEOUT, failure(provider.request("s1", "s", "u")).mode)
        assertEquals(GraderFailure.GRADER_TIMEOUT, failure(provider.request("s1", "s", "u")).mode)
        // The timed-out attempts consumed the allowance; nothing retried on its own.
        assertEquals(GraderFailure.QUOTA_EXHAUSTED, failure(provider.request("s1", "s", "u")).mode)
        assertEquals(2, transport.calls.count { it.startsWith("POST") })
    }

    // The paid route

    @Test
    fun `a paid request holds the ceiling before dispatch and charges the reported cost`() {
        var heldWhenDispatched = ""
        val transport = FakeTransport(onPost = { heldWhenDispatched = ledgerFile.readText() })
        val (provider, _, diagnostics) = stack(transport)
        provider.startSession("s1")
        val outcome = provider.request("s1", "instruction", "context", route = GradingRoute.PAID)
        assertTrue(heldWhenDispatched.contains(""""route":"paid""""), "the paid reservation must be on disk before dispatch")
        assertTrue(heldWhenDispatched.contains(""""usd":"${pin.ceilingUsd.toPlainString()}""""), heldWhenDispatched)
        val content = assertInstanceOf(ProviderOutcome.Content::class.java, outcome)
        assertEquals(GradingRoute.PAID, content.route)
        assertTrue(transport.lastBody.contains(""""model":"${pin.model}""""))
        assertTrue(transport.lastBody.contains(""""only":["${pin.provider}"]"""))
        same("0.0000318", provider.budget().spentTodayUsd)
        same("1.00", provider.budget().capUsd)
        assertTrue(diagnostics.counts().containsKey("paidReportedCost"))
        assertEquals(1, provider.status("s1")["paidReservedToday"])
    }

    @Test
    fun `a paid reply that reports no cost is refused and charged the ceiling`() {
        val (provider, transport, diagnostics) = stack(FakeTransport(paidCompletion = FakeTransport.ok(FakeTransport.paidReply(usage = """{"prompt_tokens":298}"""))))
        provider.startSession("s1")
        val failed = assertInstanceOf(ProviderOutcome.Failed::class.java, provider.request("s1", "s", "u", route = GradingRoute.PAID))
        assertEquals(GraderFailure.PROVIDER_ERROR, failed.failure.mode)
        assertEquals("no reported cost", failed.failure.detail)
        assertTrue(failed.dispatched)
        same(pin.ceilingUsd.toPlainString(), provider.budget().spentTodayUsd)
        assertEquals(GradingUnavailable.PAID_REPLY_REFUSED, provider.unavailableCause(GradingRoute.PAID))
        assertTrue(diagnostics.counts().containsKey("paidCostNotReported"))
        // Off for the rest of the session: no second dispatch, and the free route is untouched.
        assertFalse(assertInstanceOf(ProviderOutcome.Failed::class.java, provider.request("s1", "s", "u", route = GradingRoute.PAID)).dispatched)
        assertEquals(1, transport.posts("paid"))
        assertInstanceOf(ProviderOutcome.Content::class.java, provider.request("s1", "s", "u"))
    }

    @Test
    fun `a paid reply from another model is refused and its reported cost is still charged`() {
        val (provider, _, _) = stack(FakeTransport(paidCompletion = FakeTransport.ok(FakeTransport.paidReply(model = "openai/gpt-4.1-mini"))))
        provider.startSession("s1")
        val failure = failure(provider.request("s1", "s", "u", route = GradingRoute.PAID))
        assertEquals(GraderFailure.PROVIDER_ERROR, failure.mode)
        assertTrue(failure.detail.contains("not the pinned paid model"))
        same("0.0000318", provider.budget().spentTodayUsd)
        assertEquals(GradingUnavailable.PAID_REPLY_REFUSED, provider.unavailableCause(GradingRoute.PAID))
    }

    @Test
    fun `a paid timeout keeps its hold as spend`() {
        val (provider, _, _) = stack(FakeTransport(paidCompletion = HttpResult.Timeout))
        provider.startSession("s1")
        val failed = assertInstanceOf(ProviderOutcome.Failed::class.java, provider.request("s1", "s", "u", route = GradingRoute.PAID))
        assertEquals(GraderFailure.GRADER_TIMEOUT, failed.failure.mode)
        assertTrue(failed.dispatched)
        same(pin.ceilingUsd.toPlainString(), provider.budget().spentTodayUsd)
        assertNull(provider.unavailableCause(GradingRoute.PAID), "a timeout is not a refusal")
    }

    @Test
    fun `the cap stops the paid route for the day and never the free one`() {
        val expensive = FakeTransport.ok(FakeTransport.paidReply(usage = """{"cost":0.0095}"""))
        val (provider, transport, _) = stack(FakeTransport(paidCompletion = expensive), settings = FakeSettings(dailyCapUsd = BigDecimal("0.01")))
        provider.startSession("s1")
        assertInstanceOf(ProviderOutcome.Content::class.java, provider.request("s1", "s", "u", route = GradingRoute.PAID))
        same("0.0095", provider.budget().spentTodayUsd)
        // 0.0095 plus the next request's ceiling would pass $0.01: refused before dispatch, stopped for the day.
        val refused = assertInstanceOf(ProviderOutcome.Failed::class.java, provider.request("s1", "s", "u", route = GradingRoute.PAID))
        assertEquals(GraderFailure.QUOTA_EXHAUSTED, refused.failure.mode)
        assertEquals(LedgerStop.BUDGET_EXHAUSTED.reason, refused.failure.detail)
        assertFalse(refused.dispatched)
        assertEquals(1, transport.posts("paid"))
        assertEquals(LedgerStop.BUDGET_EXHAUSTED, ledger().stopToday(GradingRoute.PAID))
        assertEquals(GradingUnavailable.BUDGET, provider.unavailableCause(GradingRoute.PAID))
        assertNull(ledger().stopToday(GradingRoute.FREE))
        assertInstanceOf(ProviderOutcome.Content::class.java, provider.request("s1", "s", "u"))
        // A new session the same day starts with the paid route already off.
        val start = assertInstanceOf(SessionStart.Ready::class.java, provider.startSession("s2"))
        assertEquals(GradingUnavailable.BUDGET, start.routes[1].cause)
    }

    @Test
    fun `a spent budget blocks the paid route at session start without a price check`() {
        val ledger = QuotaLedger(ledgerFile) { now }
        val granted = assertInstanceOf(Reservation.Granted::class.java, ledger.reservePaid("earlier", 50, BigDecimal("0.05"), pin.ceilingUsd))
        ledger.charge(granted.id, BigDecimal("0.0495"))
        val (provider, transport, _) = stack(settings = FakeSettings(dailyCapUsd = BigDecimal("0.05")))
        val start = assertInstanceOf(SessionStart.Ready::class.java, provider.startSession("s1"))
        assertEquals(GradingUnavailable.BUDGET, start.routes[1].cause)
        assertTrue(start.routes[1].detail.contains("0.0495"), start.routes[1].detail)
        assertEquals(listOf("GET ${FreeRoute.endpointsUrl}"), transport.calls)
    }

    // Diagnostics

    @Test
    fun `diagnostics stay content-free and redact anything key-shaped`() {
        val diagnostics = Diagnostics(clock = { now })
        val (provider, _, _) = stack(
            FakeTransport(completion = HttpResult.Response(500, "denied for $key"), paidCompletion = HttpResult.Response(500, "denied for $key")),
            diagnostics = diagnostics,
        )
        provider.startSession("s1")
        provider.request("s1", "the grading instruction", "the learner said something")
        provider.request("s1", "the grading instruction", "the learner said something", route = GradingRoute.PAID)
        val text = diagnostics.entries().joinToString("\n")
        assertFalse(text.contains(key), text)
        assertFalse(text.contains("the learner said something"), text)
        assertFalse(text.contains("the grading instruction"), text)
        assertTrue(diagnostics.counts().containsKey("reserved"))
        assertTrue(diagnostics.counts().containsKey("paidPriceCheck"))
        assertEquals(
            mapOf("reservedToday" to 2, "reservedThisSession" to 2, "reservedTotal" to 2, "paidReservedToday" to 1),
            provider.status("s1"),
        )
    }
}
