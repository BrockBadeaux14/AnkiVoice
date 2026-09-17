package org.ankivoice.provider

import java.math.BigDecimal
import java.util.EnumMap
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GraderFailure

/** What the learner may change, held by `:app`'s private settings. */
interface ProviderSettings {
    /** 0..[QuotaLedger.MAX_DAILY_LIMIT]; the default is [QuotaLedger.DEFAULT_DAILY_LIMIT]. */
    var dailyLimit: Int

    /** Set only by the learner acknowledging the retention disclosure. Cleared with the key. */
    var disclosureAcknowledged: Boolean

    /**
     * AV-043: the paid route's daily cap in USD, 0 to [QuotaLedger.MAX_DAILY_CAP_USD]; the
     * default is [QuotaLedger.DEFAULT_DAILY_CAP_USD]. Zero disables the paid route.
     */
    var dailyCapUsd: BigDecimal
}

/** Why a route is off for this session. Each is shown to the learner, and none is a rating. */
enum class GradingUnavailable(val reason: String) {
    NO_KEY("Add an OpenRouter key to use AI grading. Self-grading stays available."),
    KEY_REJECTED("The provider rejected the key, so AI grading is off for this session. Self-grading stays available."),
    DISCLOSURE_REQUIRED("Read and acknowledge what is sent before AI grading is used."),
    ROUTE_REFUSED("The pinned free route did not pass its zero-price check, so free grading is off."),
    QUOTA("The grading allowance is used up. Self-grading stays available."),
    PAID_DISABLED("The paid route is off because the daily budget is \$0. Self-grading stays available."),
    PAID_ROUTE_REFUSED("The pinned paid route did not pass its price check, so paid grading is off."),
    BUDGET("Today's paid grading budget is used up. Self-grading stays available."),
    PAID_REPLY_REFUSED("A paid reply could not be verified, so paid grading is off for this session."),
    NOT_ENABLED("This grading route is not enabled."),
    ;

    /** Unavailable grading never proposes anything. Self-grading remains the way forward. */
    val selfGradingAvailable: Boolean get() = true
}

/** What the pre-session check found for one route. */
data class RouteStart(
    val route: GradingRoute,
    val endpointName: String?,
    val cause: GradingUnavailable?,
    val detail: String = "",
) {
    val ready: Boolean get() = cause == null
}

sealed interface SessionStart {
    /**
     * At least one route is usable. [endpointName] and [allowance] describe the first
     * usable route in [GradingRoute.ORDER]; [routes] describes every route the provider
     * tried to start.
     */
    data class Ready(
        val endpointName: String,
        val allowance: Allowance,
        val routes: List<RouteStart> = emptyList(),
    ) : SessionStart

    data class Unavailable(
        val cause: GradingUnavailable,
        val detail: String = "",
        val routes: List<RouteStart> = emptyList(),
    ) : SessionStart
}

sealed interface ProviderOutcome {
    /**
     * The reply text, carried verbatim, with the provider's own `finish_reason` and the
     * route that served it. #18 validates and labels the text, and rejects output that did
     * not terminate.
     */
    data class Content(
        val text: String,
        val finishReason: String? = null,
        val route: GradingRoute = GradingRoute.FREE,
    ) : ProviderOutcome

    /**
     * [dispatched] is false when nothing left the device: the route was off, blocked for
     * the session, or refused by the ledger before a request was built.
     */
    data class Failed(
        val failure: Failure,
        val route: GradingRoute = GradingRoute.FREE,
        val dispatched: Boolean = false,
    ) : ProviderOutcome
}

/**
 * The provider side of the grading routes: credential, guards, durable allowance and
 * budget, and content-free diagnostics around one HTTPS call per request.
 *
 * AV-020's free route is unchanged in what it verifies. AV-043 corrects its reply check
 * and adds the paid route behind the same seam: [request] takes the route, the ledger
 * reserves the request's ceiling against the owner's daily cap before dispatch, and the
 * reply's reported cost is charged afterwards. Which route is tried when is #18's
 * decision in [SemanticGrader]; this class only refuses what a route may not do.
 *
 * Nothing here decides a grade. Every refusal surfaces as a #7 `Grader` failure or as
 * unavailable grading, the session pauses for an explicit self-grade, and no failure
 * submits or implies a rating. Retries are the caller's explicit choice, one per learner
 * action, and only within the remaining allowance and budget.
 */
class GradingProvider internal constructor(
    private val credentials: CredentialStore,
    private val ledger: QuotaLedger,
    private val settings: ProviderSettings,
    private val transport: HttpTransport,
    private val diagnostics: Diagnostics,
    private val elapsed: () -> Long = System::nanoTime,
    /** The paid endpoint. The app pins [PaidRoute.PINNED]; the AV-043 spike passes a candidate. */
    private val paidPin: PaidRoutePin = PaidRoute.PINNED,
    /** Which routes this provider may use. The spike measures the paid route on its own. */
    enabledRoutes: Collection<GradingRoute> = GradingRoute.ORDER,
) {
    /** The routes this provider may use, in the order they are tried. */
    val routes: List<GradingRoute> = GradingRoute.ORDER.filter { it in enabledRoutes }

    /** Set by a 401/403, a guard refusal or a spent budget; the route stays off until the next session. */
    private val blocked = EnumMap<GradingRoute, GradingUnavailable>(GradingRoute::class.java)

    /** The identities the endpoints listing gave each pinned endpoint at session start. */
    private val identities = EnumMap<GradingRoute, Set<String>>(GradingRoute::class.java)

    init {
        require(routes.isNotEmpty()) { "a grading provider needs at least one route" }
    }

    /** Why [route] is off for the rest of this session, or null. */
    fun unavailableCause(route: GradingRoute): GradingUnavailable? =
        if (route !in routes) GradingUnavailable.NOT_ENABLED else blocked[route]

    /**
     * Why grading is off for the rest of this session — every route blocked — or null while
     * any route remains. Terminal: #18 reads it to know that a failure must not be retried.
     */
    val unavailableCause: GradingUnavailable?
        get() = if (routes.any { unavailableCause(it) == null }) null else routes.firstNotNullOfOrNull { unavailableCause(it) }

    /** Today's paid spend against the owner's cap, for the settings screen. */
    fun budget(): Budget = ledger.budget(settings.dailyCapUsd)

    /**
     * Checked before a session starts: a key, an acknowledged disclosure, remaining
     * allowance, the free endpoint's zero prices and the paid endpoint's pinned prices.
     * The price checks are not grading requests and never reserve.
     */
    fun startSession(sessionId: String): SessionStart {
        blocked.clear()
        identities.clear()
        val key = credentials.read()
        if (key == null || !CredentialPolicy.valid(key)) return unavailable(GradingUnavailable.NO_KEY)
        if (!settings.disclosureAcknowledged) return unavailable(GradingUnavailable.DISCLOSURE_REQUIRED)
        val starts = routes.map { route ->
            when (route) {
                GradingRoute.FREE -> startFree(sessionId, key)
                GradingRoute.PAID -> startPaid(sessionId, key)
            }
        }
        val ready = starts.firstOrNull { it.ready }
        if (ready == null) {
            val first = starts.first()
            return SessionStart.Unavailable(checkNotNull(first.cause), first.detail, starts)
        }
        return SessionStart.Ready(checkNotNull(ready.endpointName), ledger.allowance(sessionId, settings.dailyLimit, ready.route), starts)
    }

    private fun startFree(sessionId: String, key: String): RouteStart {
        val allowance = ledger.allowance(sessionId, settings.dailyLimit, GradingRoute.FREE)
        if (!allowance.grantable) return blockedStart(GradingRoute.FREE, GradingUnavailable.QUOTA, allowance.stop?.reason.orEmpty())
        val started = elapsed()
        val result = transport.get(FreeRoute.endpointsUrl, key, FreeRoute.TIMEOUT_MS)
        diagnostics.record("priceCheck", millisSince(started))
        return when (val check = priceCheck(result) { FreeRoute.priceCheck(it) }) {
            is RouteCheck.Allowed -> {
                identities[GradingRoute.FREE] = check.providers
                RouteStart(GradingRoute.FREE, check.endpointName, null)
            }
            is RouteCheck.Refused -> {
                ledger.stop(LedgerStop.ROUTE_REFUSED, GradingRoute.FREE)
                blockedStart(GradingRoute.FREE, GradingUnavailable.ROUTE_REFUSED, check.reason)
            }
        }
    }

    private fun startPaid(sessionId: String, key: String): RouteStart {
        val cap = settings.dailyCapUsd
        if (cap.signum() <= 0) return blockedStart(GradingRoute.PAID, GradingUnavailable.PAID_DISABLED)
        val allowance = ledger.allowance(sessionId, settings.dailyLimit, GradingRoute.PAID)
        if (!allowance.grantable) {
            val cause = if (allowance.stop == LedgerStop.BUDGET_EXHAUSTED) GradingUnavailable.BUDGET else GradingUnavailable.QUOTA
            return blockedStart(GradingRoute.PAID, cause, allowance.stop?.reason.orEmpty())
        }
        val budget = ledger.budget(cap)
        if (!budget.fits(paidPin.ceilingUsd)) {
            return blockedStart(
                GradingRoute.PAID,
                GradingUnavailable.BUDGET,
                "spent ${QuotaLedger.formatUsd(budget.spentTodayUsd, 4)} of ${QuotaLedger.formatUsd(budget.capUsd)} today",
            )
        }
        val started = elapsed()
        val result = transport.get(PaidRoute.endpointsUrl(paidPin), key, PaidRoute.TIMEOUT_MS)
        diagnostics.record("paidPriceCheck", millisSince(started))
        return when (val check = priceCheck(result) { PaidRoute.priceCheck(paidPin, it) }) {
            is RouteCheck.Allowed -> {
                identities[GradingRoute.PAID] = check.providers
                RouteStart(GradingRoute.PAID, check.endpointName, null)
            }
            is RouteCheck.Refused -> {
                ledger.stop(LedgerStop.PAID_ROUTE_REFUSED, GradingRoute.PAID)
                blockedStart(GradingRoute.PAID, GradingUnavailable.PAID_ROUTE_REFUSED, check.reason)
            }
        }
    }

    /**
     * One grading request over [route]. The allowance — and, for the paid route, the
     * request's ceiling against the cap — is reserved on disk before dispatch, so a timeout
     * or process death still consumes it. [timeoutMs] is the caller's deadline for this
     * attempt; #18 pins a shorter one than the route's default.
     */
    fun request(
        sessionId: String,
        system: String,
        user: String,
        timeoutMs: Int = FreeRoute.TIMEOUT_MS,
        route: GradingRoute = GradingRoute.FREE,
    ): ProviderOutcome {
        unavailableCause(route)?.let { return unavailableOutcome(it, route) }
        val key = credentials.read()
        if (key == null || !CredentialPolicy.valid(key)) return unavailableOutcome(GradingUnavailable.NO_KEY, route)
        if (!settings.disclosureAcknowledged) return unavailableOutcome(GradingUnavailable.DISCLOSURE_REQUIRED, route)
        val reservation = when (route) {
            GradingRoute.FREE -> ledger.reserve(sessionId, settings.dailyLimit, GradingRoute.FREE)
            GradingRoute.PAID -> {
                val cap = settings.dailyCapUsd
                if (cap.signum() <= 0) {
                    blocked[GradingRoute.PAID] = GradingUnavailable.PAID_DISABLED
                    return unavailableOutcome(GradingUnavailable.PAID_DISABLED, route)
                }
                ledger.reservePaid(sessionId, settings.dailyLimit, cap, paidPin.ceilingUsd)
            }
        }
        val granted = when (reservation) {
            is Reservation.Refused -> {
                diagnostics.record("quotaRefused", detail = "${route.specName} ${reservation.stop.name}")
                if (reservation.stop == LedgerStop.BUDGET_EXHAUSTED) blocked[GradingRoute.PAID] = GradingUnavailable.BUDGET
                return ProviderOutcome.Failed(Failure(GraderFailure.QUOTA_EXHAUSTED, reservation.stop.reason), route, dispatched = false)
            }
            is Reservation.Granted -> reservation.also {
                val hold = if (route == GradingRoute.PAID) " hold=${it.holdUsd.toPlainString()}" else ""
                diagnostics.record("reserved", detail = "${route.specName} id=${it.id}$hold")
            }
        }
        val payload = when (route) {
            GradingRoute.FREE -> FreeRoute.payload(system, user)
            GradingRoute.PAID -> PaidRoute.payload(paidPin, system, user)
        }
        val started = elapsed()
        val result = transport.post(FreeRoute.completionsUrl, key, Json.write(payload), timeoutMs)
        val took = millisSince(started)
        return when (result) {
            HttpResult.Timeout -> {
                diagnostics.record("graderTimeout", took, route.specName)
                ProviderOutcome.Failed(Failure(GraderFailure.GRADER_TIMEOUT, "The grader did not answer in time."), route, dispatched = true)
            }
            is HttpResult.Refused -> {
                diagnostics.record("transportRefused", took, "${route.specName} ${result.reason}")
                ProviderOutcome.Failed(Failure(GraderFailure.PROVIDER_ERROR, result.reason), route, dispatched = true)
            }
            is HttpResult.Response -> response(result, took, route, granted)
        }
    }

    /** The counts a settings screen shows: content-free, and never a rating. */
    fun status(sessionId: String): Map<String, Int> = ledger.counts(sessionId)

    private fun response(result: HttpResult.Response, took: Long, route: GradingRoute, granted: Reservation.Granted): ProviderOutcome =
        when (result.status) {
            in 200..299 -> body(result.body, took, route, granted)
            401, 403 -> {
                // One key serves both routes, so a rejected key turns both off.
                for (each in routes) blocked[each] = GradingUnavailable.KEY_REJECTED
                diagnostics.record("credentialRejected", took, "${route.specName} HTTP ${result.status}")
                ProviderOutcome.Failed(Failure(GraderFailure.PROVIDER_ERROR, GradingUnavailable.KEY_REJECTED.reason), route, dispatched = true)
            }
            402, 429 -> {
                val stop = if (result.status == 402) LedgerStop.PAYMENT_REQUIRED else LedgerStop.RATE_LIMITED
                ledger.stop(stop, route)
                blocked[route] = GradingUnavailable.QUOTA
                diagnostics.record("quotaStopped", took, "${route.specName} HTTP ${result.status}")
                ProviderOutcome.Failed(Failure(GraderFailure.QUOTA_EXHAUSTED, stop.reason), route, dispatched = true)
            }
            else -> {
                diagnostics.record("providerError", took, "${route.specName} HTTP ${result.status}")
                ProviderOutcome.Failed(Failure(GraderFailure.PROVIDER_ERROR, "HTTP ${result.status}"), route, dispatched = true)
            }
        }

    private fun body(text: String, took: Long, route: GradingRoute, granted: Reservation.Granted): ProviderOutcome {
        val reply = try {
            Json.parse(text)
        } catch (_: RuntimeException) {
            // A paid reservation that cannot be read keeps its hold: the money may have been spent.
            diagnostics.record("unparsableResponse", took, route.specName)
            return ProviderOutcome.Failed(Failure(GraderFailure.UNPARSABLE_RESPONSE, "The reply was not JSON."), route, dispatched = true)
        }
        when (route) {
            GradingRoute.FREE -> when (val check = FreeRoute.replyCheck(reply, identities[GradingRoute.FREE] ?: setOf(FreeRoute.PROVIDER))) {
                is RouteCheck.Refused -> {
                    ledger.stop(LedgerStop.COST_NOT_VERIFIED, GradingRoute.FREE)
                    blocked[GradingRoute.FREE] = GradingUnavailable.ROUTE_REFUSED
                    diagnostics.record("costNotVerified", took, check.reason)
                    return ProviderOutcome.Failed(Failure(GraderFailure.PROVIDER_ERROR, check.reason), route, dispatched = true)
                }
                is RouteCheck.Allowed -> diagnostics.record("reportedCost", took, "0")
            }
            GradingRoute.PAID -> when (val check = PaidRoute.replyCheck(paidPin, reply, identities[GradingRoute.PAID] ?: setOf(paidPin.provider))) {
                is PaidReplyCheck.Verified -> {
                    ledger.charge(granted.id, check.costUsd)
                    diagnostics.record("paidReportedCost", took, check.costUsd.toPlainString())
                }
                is PaidReplyCheck.Refused -> {
                    // Refused as a label, charged anyway: the reported cost, or the ceiling when none was reported.
                    val charged = check.reportedCostUsd ?: paidPin.ceilingUsd
                    ledger.charge(granted.id, charged)
                    blocked[GradingRoute.PAID] = GradingUnavailable.PAID_REPLY_REFUSED
                    diagnostics.record(
                        if (check.reportedCostUsd == null) "paidCostNotReported" else "paidReplyRefused",
                        took,
                        "${check.reason}; charged ${charged.toPlainString()}",
                    )
                    return ProviderOutcome.Failed(Failure(GraderFailure.PROVIDER_ERROR, check.reason), route, dispatched = true)
                }
            }
        }
        val choice = reply.asObject()?.child("choices").asList()?.firstOrNull().asObject()
        val content = choice?.child("message").asObject()?.child("content").asText()
        val finish = choice?.child("finish_reason").asText()
        return when {
            finish == "length" -> {
                diagnostics.record("outputTruncated", took, route.specName)
                ProviderOutcome.Failed(Failure(GraderFailure.OUTPUT_TRUNCATED, "The reply hit the ${FreeRoute.MAX_TOKENS}-token cap."), route, dispatched = true)
            }
            content.isNullOrBlank() -> {
                diagnostics.record("providerError", took, "${route.specName} empty reply")
                ProviderOutcome.Failed(Failure(GraderFailure.PROVIDER_ERROR, "The provider returned no content."), route, dispatched = true)
            }
            // The text is carried, not read: #18 validates it and owns the label policy.
            else -> ProviderOutcome.Content(content, finish, route)
        }
    }

    private fun priceCheck(result: HttpResult, check: (Any?) -> RouteCheck): RouteCheck = when (result) {
        HttpResult.Timeout -> RouteCheck.Refused("the price check timed out")
        is HttpResult.Refused -> RouteCheck.Refused(result.reason)
        is HttpResult.Response -> when {
            result.status !in 200..299 -> RouteCheck.Refused("price check returned HTTP ${result.status}")
            else -> try {
                check(Json.parse(result.body))
            } catch (_: RuntimeException) {
                RouteCheck.Refused("the price check reply was not JSON")
            }
        }
    }

    private fun blockedStart(route: GradingRoute, cause: GradingUnavailable, detail: String = ""): RouteStart {
        blocked[route] = cause
        diagnostics.record(
            "gradingUnavailable",
            detail = listOf(route.specName, cause.name, detail).filter { it.isNotEmpty() }.joinToString(" "),
        )
        return RouteStart(route, null, cause, detail)
    }

    private fun unavailable(cause: GradingUnavailable, detail: String = ""): SessionStart.Unavailable {
        for (route in routes) blocked[route] = cause
        diagnostics.record("gradingUnavailable", detail = listOf(cause.name, detail).filter { it.isNotEmpty() }.joinToString(" "))
        return SessionStart.Unavailable(cause, detail)
    }

    private fun unavailableOutcome(cause: GradingUnavailable, route: GradingRoute): ProviderOutcome {
        val failure = when (cause) {
            GradingUnavailable.QUOTA, GradingUnavailable.BUDGET -> Failure(GraderFailure.QUOTA_EXHAUSTED, cause.reason)
            else -> Failure(GraderFailure.PROVIDER_ERROR, cause.reason)
        }
        return ProviderOutcome.Failed(failure, route, dispatched = false)
    }

    private fun millisSince(started: Long): Long = (elapsed() - started) / 1_000_000
}
