package org.ankivoice.provider

import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GraderFailure

/** What the learner may change, held by `:app`'s private settings. */
interface ProviderSettings {
    /** 0..[QuotaLedger.MAX_DAILY_LIMIT]; the default is [QuotaLedger.DEFAULT_DAILY_LIMIT]. */
    var dailyLimit: Int

    /** Set only by the learner acknowledging the retention disclosure. Cleared with the key. */
    var disclosureAcknowledged: Boolean
}

/** Why grading is off for this session. Each is shown to the learner, and none is a rating. */
enum class GradingUnavailable(val reason: String) {
    NO_KEY("Add an OpenRouter key to use AI grading. Self-grading stays available."),
    KEY_REJECTED("The provider rejected the key, so AI grading is off for this session. Self-grading stays available."),
    DISCLOSURE_REQUIRED("Read and acknowledge what is sent before AI grading is used."),
    ROUTE_REFUSED("The pinned free route did not pass its zero-price check, so AI grading is off."),
    QUOTA("The grading allowance is used up. Self-grading stays available."),
    ;

    /** Unavailable grading never proposes anything. Self-grading remains the way forward. */
    val selfGradingAvailable: Boolean get() = true
}

sealed interface SessionStart {
    data class Ready(val endpointName: String, val allowance: Allowance) : SessionStart

    data class Unavailable(val cause: GradingUnavailable, val detail: String = "") : SessionStart
}

sealed interface ProviderOutcome {
    /**
     * The reply text, carried verbatim, with the provider's own `finish_reason`. #18
     * validates and labels the text, and rejects output that did not terminate.
     */
    data class Content(val text: String, val finishReason: String? = null) : ProviderOutcome

    data class Failed(val failure: Failure) : ProviderOutcome
}

/**
 * The provider side of AV-006's free-only route: credential, guard, durable allowance and
 * content-free diagnostics around one HTTPS call.
 *
 * Nothing here decides a grade. Every refusal surfaces as a #7 `Grader` failure or as
 * unavailable grading, the session pauses for an explicit self-grade, and no failure
 * submits or implies a rating. Retries are the caller's explicit choice, one per learner
 * action, and only within the remaining allowance.
 */
class GradingProvider internal constructor(
    private val credentials: CredentialStore,
    private val ledger: QuotaLedger,
    private val settings: ProviderSettings,
    private val transport: HttpTransport,
    private val diagnostics: Diagnostics,
    private val elapsed: () -> Long = System::nanoTime,
) {
    /** Set by a 401/403 or a guard refusal; grading stays off until the next session. */
    private var blocked: GradingUnavailable? = null

    /**
     * Why grading is off for the rest of this session, or null. Terminal: #18 reads it to
     * know that a failure must not be retried.
     */
    val unavailableCause: GradingUnavailable? get() = blocked

    /**
     * Checked before a session starts: a key, an acknowledged disclosure, remaining
     * allowance, and the pinned endpoint's zero prices. The price check is not a grading
     * request and never reserves.
     */
    fun startSession(sessionId: String): SessionStart {
        blocked = null
        val key = credentials.read()
        if (key == null || !CredentialPolicy.valid(key)) return unavailable(GradingUnavailable.NO_KEY)
        if (!settings.disclosureAcknowledged) return unavailable(GradingUnavailable.DISCLOSURE_REQUIRED)
        val allowance = ledger.allowance(sessionId, settings.dailyLimit)
        if (!allowance.grantable) {
            return unavailable(GradingUnavailable.QUOTA, allowance.stop?.reason.orEmpty())
        }
        val started = elapsed()
        val result = transport.get(FreeRoute.endpointsUrl, key, FreeRoute.TIMEOUT_MS)
        diagnostics.record("priceCheck", millisSince(started))
        return when (val check = priceCheck(result)) {
            is RouteCheck.Allowed -> SessionStart.Ready(check.endpointName, allowance)
            is RouteCheck.Refused -> {
                ledger.stop(LedgerStop.ROUTE_REFUSED)
                unavailable(GradingUnavailable.ROUTE_REFUSED, check.reason)
            }
        }
    }

    /**
     * One grading request. The allowance is reserved on disk before dispatch, so a
     * timeout or process death still consumes it. [timeoutMs] is the caller's deadline
     * for this attempt; #18 pins a shorter one than the route's default.
     */
    fun request(
        sessionId: String,
        system: String,
        user: String,
        timeoutMs: Int = FreeRoute.TIMEOUT_MS,
    ): ProviderOutcome {
        blocked?.let { return unavailableOutcome(it) }
        val key = credentials.read()
        if (key == null || !CredentialPolicy.valid(key)) return unavailableOutcome(GradingUnavailable.NO_KEY)
        if (!settings.disclosureAcknowledged) return unavailableOutcome(GradingUnavailable.DISCLOSURE_REQUIRED)
        when (val reservation = ledger.reserve(sessionId, settings.dailyLimit)) {
            is Reservation.Refused -> {
                diagnostics.record("quotaRefused", detail = reservation.stop.name)
                return ProviderOutcome.Failed(Failure(GraderFailure.QUOTA_EXHAUSTED, reservation.stop.reason))
            }
            is Reservation.Granted -> diagnostics.record("reserved", detail = "id=${reservation.id}")
        }
        val body = Json.write(FreeRoute.payload(system, user))
        val started = elapsed()
        val result = transport.post(FreeRoute.completionsUrl, key, body, timeoutMs)
        val took = millisSince(started)
        return when (result) {
            HttpResult.Timeout -> {
                diagnostics.record("graderTimeout", took)
                ProviderOutcome.Failed(Failure(GraderFailure.GRADER_TIMEOUT, "The grader did not answer in time."))
            }
            is HttpResult.Refused -> {
                diagnostics.record("transportRefused", took, result.reason)
                ProviderOutcome.Failed(Failure(GraderFailure.PROVIDER_ERROR, result.reason))
            }
            is HttpResult.Response -> response(result, took)
        }
    }

    /** The counts a settings screen shows: content-free, and never a rating. */
    fun status(sessionId: String): Map<String, Int> = ledger.counts(sessionId)

    private fun response(result: HttpResult.Response, took: Long): ProviderOutcome = when (result.status) {
        in 200..299 -> body(result.body, took)
        401, 403 -> {
            blocked = GradingUnavailable.KEY_REJECTED
            diagnostics.record("credentialRejected", took, "HTTP ${result.status}")
            ProviderOutcome.Failed(Failure(GraderFailure.PROVIDER_ERROR, GradingUnavailable.KEY_REJECTED.reason))
        }
        402, 429 -> {
            val stop = if (result.status == 402) LedgerStop.PAYMENT_REQUIRED else LedgerStop.RATE_LIMITED
            ledger.stop(stop)
            blocked = GradingUnavailable.QUOTA
            diagnostics.record("quotaStopped", took, "HTTP ${result.status}")
            ProviderOutcome.Failed(Failure(GraderFailure.QUOTA_EXHAUSTED, stop.reason))
        }
        else -> {
            diagnostics.record("providerError", took, "HTTP ${result.status}")
            ProviderOutcome.Failed(Failure(GraderFailure.PROVIDER_ERROR, "HTTP ${result.status}"))
        }
    }

    private fun body(text: String, took: Long): ProviderOutcome {
        val reply = try {
            Json.parse(text)
        } catch (_: RuntimeException) {
            diagnostics.record("unparsableResponse", took)
            return ProviderOutcome.Failed(Failure(GraderFailure.UNPARSABLE_RESPONSE, "The reply was not JSON."))
        }
        when (val check = FreeRoute.replyCheck(reply)) {
            is RouteCheck.Refused -> {
                ledger.stop(LedgerStop.COST_NOT_VERIFIED)
                blocked = GradingUnavailable.ROUTE_REFUSED
                diagnostics.record("costNotVerified", took, check.reason)
                return ProviderOutcome.Failed(Failure(GraderFailure.PROVIDER_ERROR, check.reason))
            }
            is RouteCheck.Allowed -> diagnostics.record("reportedCost", took, "0")
        }
        val choice = reply.asObject()?.child("choices").asList()?.firstOrNull().asObject()
        val content = choice?.child("message").asObject()?.child("content").asText()
        val finish = choice?.child("finish_reason").asText()
        return when {
            finish == "length" -> {
                diagnostics.record("outputTruncated", took)
                ProviderOutcome.Failed(Failure(GraderFailure.OUTPUT_TRUNCATED, "The reply hit the ${FreeRoute.MAX_TOKENS}-token cap."))
            }
            content.isNullOrBlank() -> {
                diagnostics.record("providerError", took, "empty reply")
                ProviderOutcome.Failed(Failure(GraderFailure.PROVIDER_ERROR, "The provider returned no content."))
            }
            // The text is carried, not read: #18 validates it and owns the label policy.
            else -> ProviderOutcome.Content(content, finish)
        }
    }

    private fun priceCheck(result: HttpResult): RouteCheck = when (result) {
        HttpResult.Timeout -> RouteCheck.Refused("the price check timed out")
        is HttpResult.Refused -> RouteCheck.Refused(result.reason)
        is HttpResult.Response -> when {
            result.status !in 200..299 -> RouteCheck.Refused("price check returned HTTP ${result.status}")
            else -> try {
                FreeRoute.priceCheck(Json.parse(result.body))
            } catch (_: RuntimeException) {
                RouteCheck.Refused("the price check reply was not JSON")
            }
        }
    }

    private fun unavailable(cause: GradingUnavailable, detail: String = ""): SessionStart.Unavailable {
        blocked = cause
        diagnostics.record("gradingUnavailable", detail = listOf(cause.name, detail).filter { it.isNotEmpty() }.joinToString(" "))
        return SessionStart.Unavailable(cause, detail)
    }

    private fun unavailableOutcome(cause: GradingUnavailable): ProviderOutcome {
        val failure = when (cause) {
            GradingUnavailable.QUOTA -> Failure(GraderFailure.QUOTA_EXHAUSTED, cause.reason)
            else -> Failure(GraderFailure.PROVIDER_ERROR, cause.reason)
        }
        return ProviderOutcome.Failed(failure)
    }

    private fun millisSince(started: Long): Long = (elapsed() - started) / 1_000_000
}
