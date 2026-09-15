package org.ankivoice.provider

import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GraderFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
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

    private class FakeSettings(
        override var dailyLimit: Int = 50,
        override var disclosureAcknowledged: Boolean = true,
    ) : ProviderSettings

    private class FakeTransport(
        var priceCheck: HttpResult = ok(ENDPOINTS),
        var completion: HttpResult = ok(reply()),
        val onPost: () -> Unit = {},
    ) : HttpTransport {
        val calls = mutableListOf<String>()
        var lastBody: String = ""
        var lastKey: String = ""

        override fun get(url: String, key: String, timeoutMs: Int): HttpResult {
            calls += "GET $url"
            lastKey = key
            return priceCheck
        }

        override fun post(url: String, key: String, body: String, timeoutMs: Int): HttpResult {
            calls += "POST $url"
            lastBody = body
            lastKey = key
            onPost()
            return completion
        }

        companion object {
            fun ok(body: String) = HttpResult.Response(200, body)

            val ENDPOINTS = """{"data":{"id":"${FreeRoute.MODEL}","endpoints":[
                {"name":"Liquid | fp8","tag":"${FreeRoute.PROVIDER}","pricing":{"prompt":"0","completion":"0"}}]}}"""

            fun reply(content: String = """{\"label\":\"correct\",\"reason\":\"ok\"}""", finish: String = "stop", cost: String = "0") =
                """{"model":"${FreeRoute.MODEL}","provider":"${FreeRoute.PROVIDER}","usage":{"cost":$cost},
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
        return Triple(GradingProvider(credentials, ledger, settings, transport, diagnostics) { now * 1_000_000 }, transport, diagnostics)
    }

    private fun failure(outcome: ProviderOutcome): Failure =
        assertInstanceOf(ProviderOutcome.Failed::class.java, outcome).failure

    // Session preconditions

    @Test
    fun `no key means grading is unavailable and nothing is sent`() {
        val (provider, transport, _) = stack(credentials = InMemoryCredentialStore(null))
        val start = assertInstanceOf(SessionStart.Unavailable::class.java, provider.startSession("s1"))
        assertEquals(GradingUnavailable.NO_KEY, start.cause)
        assertTrue(start.cause.selfGradingAvailable)
        assertEquals(emptyList<String>(), transport.calls)
        assertFalse(ledgerFile.exists(), "the ledger is untouched")
    }

    @Test
    fun `an unacknowledged disclosure keeps grading off`() {
        val (provider, transport, _) = stack(settings = FakeSettings(disclosureAcknowledged = false))
        val start = assertInstanceOf(SessionStart.Unavailable::class.java, provider.startSession("s1"))
        assertEquals(GradingUnavailable.DISCLOSURE_REQUIRED, start.cause)
        assertEquals(emptyList<String>(), transport.calls)
    }

    @Test
    fun `the pre-session price check runs and consumes no allowance`() {
        val (provider, transport, _) = stack()
        val start = assertInstanceOf(SessionStart.Ready::class.java, provider.startSession("s1"))
        assertEquals("Liquid | fp8", start.endpointName)
        assertEquals(listOf("GET ${FreeRoute.endpointsUrl}"), transport.calls)
        assertEquals(30, start.allowance.sessionRemaining)
        assertFalse(ledgerFile.exists(), "a price check is not a grading request")
    }

    @Test
    fun `a refused price check disables grading and stops the ledger`() {
        val changed = """{"data":{"id":"${FreeRoute.MODEL}","endpoints":[
            {"name":"n","tag":"${FreeRoute.PROVIDER}","pricing":{"prompt":"0.2","completion":"0"}}]}}"""
        val (provider, transport, _) = stack(FakeTransport(priceCheck = FakeTransport.ok(changed)))
        val start = assertInstanceOf(SessionStart.Unavailable::class.java, provider.startSession("s1"))
        assertEquals(GradingUnavailable.ROUTE_REFUSED, start.cause)
        assertEquals(LedgerStop.ROUTE_REFUSED, QuotaLedger(ledgerFile) { now }.stopToday())
        // A request in the disabled session dispatches nothing.
        assertEquals(GraderFailure.PROVIDER_ERROR, failure(provider.request("s1", "s", "u")).mode)
        assertEquals(listOf("GET ${FreeRoute.endpointsUrl}"), transport.calls)
    }

    @Test
    fun `an exhausted allowance is checked before the session starts`() {
        QuotaLedger(ledgerFile) { now }.stop(LedgerStop.DAILY_LIMIT)
        val (provider, transport, _) = stack()
        val start = assertInstanceOf(SessionStart.Unavailable::class.java, provider.startSession("s1"))
        assertEquals(GradingUnavailable.QUOTA, start.cause)
        assertEquals(emptyList<String>(), transport.calls)
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
        assertEquals("""{"label":"correct","reason":"ok"}""", assertInstanceOf(ProviderOutcome.Content::class.java, outcome).text)
        assertTrue(transport.lastBody.contains(""""model":"${FreeRoute.MODEL}""""))
        assertTrue(transport.lastBody.contains(""""content":"instruction""""))
        assertEquals(key, transport.lastKey)
    }

    @Test
    fun `a rejected key makes grading unavailable for the session`() {
        val transport = FakeTransport(completion = HttpResult.Response(401, """{"error":{"message":"No auth"}}"""))
        val (provider, _, _) = stack(transport)
        provider.startSession("s1")
        assertEquals(GraderFailure.PROVIDER_ERROR, failure(provider.request("s1", "s", "u")).mode)
        // Still off, and no second dispatch.
        assertEquals(GraderFailure.PROVIDER_ERROR, failure(provider.request("s1", "s", "u")).mode)
        assertEquals(1, transport.calls.count { it.startsWith("POST") })
    }

    @Test
    fun `payment required and rate limiting stop the ledger`() {
        for ((status, stop) in listOf(402 to LedgerStop.PAYMENT_REQUIRED, 429 to LedgerStop.RATE_LIMITED)) {
            ledgerFile.delete()
            val (provider, _, _) = stack(FakeTransport(completion = HttpResult.Response(status, "{}")))
            provider.startSession("s1")
            assertEquals(GraderFailure.QUOTA_EXHAUSTED, failure(provider.request("s1", "s", "u")).mode, "HTTP $status")
            assertEquals(stop, QuotaLedger(ledgerFile) { now }.stopToday(), "HTTP $status")
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
            assertEquals(expected, failure(provider.request("s1", "s", "u")).mode, result.toString())
        }
    }

    @Test
    fun `an unverified cost refuses the reply and stops the ledger`() {
        val (provider, _, _) = stack(FakeTransport(completion = FakeTransport.ok(FakeTransport.reply(cost = "0.00002"))))
        provider.startSession("s1")
        val failure = failure(provider.request("s1", "s", "u"))
        assertEquals(GraderFailure.PROVIDER_ERROR, failure.mode)
        assertEquals("reported cost is not zero", failure.detail)
        assertEquals(LedgerStop.COST_NOT_VERIFIED, QuotaLedger(ledgerFile) { now }.stopToday())
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

    // Diagnostics

    @Test
    fun `diagnostics stay content-free and redact anything key-shaped`() {
        val diagnostics = Diagnostics(clock = { now })
        val (provider, _, _) = stack(
            FakeTransport(completion = HttpResult.Response(500, "denied for $key")),
            diagnostics = diagnostics,
        )
        provider.startSession("s1")
        provider.request("s1", "the grading instruction", "the learner said something")
        val text = diagnostics.entries().joinToString("\n")
        assertFalse(text.contains(key), text)
        assertFalse(text.contains("the learner said something"), text)
        assertFalse(text.contains("the grading instruction"), text)
        assertTrue(diagnostics.counts().containsKey("reserved"))
        assertEquals(mapOf("reservedToday" to 1, "reservedThisSession" to 1, "reservedTotal" to 1), provider.status("s1"))
    }
}
