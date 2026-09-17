package org.ankivoice.provider

import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.Grader
import org.ankivoice.core.contracts.GradingOutcome
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.grading.GradingSource
import org.ankivoice.core.grading.RuleGrader
import org.ankivoice.core.grading.TurnGrading
import org.ankivoice.core.grading.bindSuggestion

/**
 * AV-016: the grading policy layer between #16's rules and #17's transport.
 *
 * Rules first. The AI grader runs only when [RuleGrader.grade] returns null for the
 * current transcript revision; a rule match sends no request and reserves no quota.
 * Rule and AI labels are never merged.
 *
 * Every result is advisory. A label proposes a rating through the existing
 * `GradeLabel.automaticProposal` mapping and nothing more: `partial` and `uncertain`
 * propose nothing, a proposal the card does not permit is dropped rather than
 * substituted, and #21 still requires explicit learner confirmation before any review is
 * written. An unavailable or failing grader keeps the card and falls back to an explicit
 * self-grade; it never writes a review or proposes a rating.
 *
 * ### Routes (AV-043)
 *
 * The AI leg tries the provider's routes in [GradingRoute.ORDER]: the pinned free route
 * first, and the pinned paid route only after the free route is refused by its guard,
 * unavailable for the session, past the deadline or failed — and only within the owner's
 * daily cap, which the provider enforces. A paid request is never sent while the free
 * route would have been tried. The paid route carries the same instruction, the same
 * two-key validation, the same deadline and the same single retry.
 *
 * ### Deadline and retry
 *
 * Each attempt gets [DEADLINE_MS], against AV-006's 12.253-second observed maximum, and
 * exactly one automatic retry per route is authorized. The retry:
 *
 * - reserves from the quota ledger like any other request, so it counts against the
 *   30-request session cap, the daily limit and, on the paid route, the cap; a retry
 *   that cannot reserve is not attempted;
 * - is bounded to one and fires only on a timeout or an invalid reply, never on a
 *   well-formed grade, a 401/403, a route refusal or a quota or budget stop;
 * - re-sends the same transcript revision, and is abandoned rather than retried when the
 *   transcript changed while the first attempt was in flight.
 *
 * Worst-case learner-visible latency for one graded turn is therefore about 80 seconds
 * (two attempts on each route), and one turn can consume four reservations, so a session
 * in which every AI turn fails on both routes can exhaust the session cap in seven turns.
 * #29's 30-turn run expects self-grading to carry part of the run.
 *
 * @param currentRevision the session's transcript revision, read again after each attempt.
 */
class SemanticGrader(
    private val provider: GradingProvider,
    private val sessionId: String,
    private val currentRevision: () -> Int,
) : Grader {
    companion object {
        /** AV-006's pinned per-attempt deadline. */
        const val DEADLINE_MS: Int = 20_000

        /** One attempt plus the one authorized automatic retry, per route. */
        const val ATTEMPTS: Int = 2
    }

    /** Requests the session withdrew. A withdrawn request is never dispatched or retried. */
    private val withdrawn = mutableSetOf<GradingRequest>()

    /** Why AI grading is off for this session on every route, or null. Self-grading always remains. */
    val unavailable: GradingUnavailable? get() = provider.unavailableCause

    /**
     * The route that produced the last AI label or failure, for diagnostics and the
     * evaluation harness. It is never an input to any policy here.
     */
    var lastRoute: GradingRoute? = null
        private set

    /** The AV-007 seam. The reply is bound to [request], which carries its revision. */
    override fun grade(request: GradingRequest): GradingReply = graded(request).reply

    /** Invalidates [request]: no dispatch, no retry, and any later reply is superseded. */
    override fun cancel(request: GradingRequest) {
        withdrawn += request
    }

    /**
     * [grade], bound to the revision current when the reply arrived and mapped to a
     * proposal the card permits. A reply for a superseded revision is discarded.
     */
    fun suggest(request: GradingRequest, permittedRatings: List<Int>): TurnGrading {
        val graded = graded(request)
        return bindSuggestion(graded.reply, graded.source, permittedRatings, currentRevision())
    }

    private class Graded(val reply: GradingReply, val source: GradingSource)

    private fun graded(request: GradingRequest): Graded {
        RuleGrader.grade(request.context)?.let { return Graded(GradingReply(request, it), GradingSource.RULE) }
        return Graded(GradingReply(request, ai(request)), GradingSource.AI)
    }

    /** One route after another, free first; each route gets at most [ATTEMPTS] dispatches. */
    private fun ai(request: GradingRequest): GradingOutcome {
        if (invalidated(request)) return WITHDRAWN
        val system = GradingInstruction.system(request.context)
        val user = GradingInstruction.user(request.context)
        var freeFailure: Failure? = null
        var last: Failure? = null
        for (route in provider.routes) {
            val attempted = attempts(route, request, system, user)
            lastRoute = route
            if (attempted.outcome is GradingResult) return attempted.outcome
            val failure = attempted.outcome as Failure
            if (route == GradingRoute.FREE) freeFailure = failure
            last = failure
            // An edited or withdrawn request is not carried to the next route.
            if (invalidated(request)) return failure
            // A paid route that never dispatched — off, blocked for the session, or refused
            // by the ledger — adds nothing the learner can act on; the free failure is what
            // actually happened this turn.
            if (route != GradingRoute.FREE && !attempted.dispatched && freeFailure != null) {
                lastRoute = GradingRoute.FREE
                return freeFailure
            }
        }
        return last ?: WITHDRAWN
    }

    private class Attempted(val outcome: GradingOutcome, val dispatched: Boolean)

    /** At most [ATTEMPTS] dispatches on [route], each reserved by #17 before it leaves the device. */
    private fun attempts(route: GradingRoute, request: GradingRequest, system: String, user: String): Attempted {
        var attempt = 0
        var dispatched = false
        while (true) {
            val sent = provider.request(sessionId, system, user, DEADLINE_MS, route)
            val outcome = when (sent) {
                is ProviderOutcome.Content -> {
                    dispatched = true
                    GradingInstruction.validate(sent.text, sent.finishReason)
                }
                is ProviderOutcome.Failed -> {
                    dispatched = dispatched || sent.dispatched
                    sent.failure
                }
            }
            if (outcome is GradingResult) return Attempted(outcome, true)
            val failure = outcome as Failure
            attempt++
            // Bounded to one retry, never on a terminal refusal, and never across an edit.
            if (attempt >= ATTEMPTS || !retryable(failure, route) || invalidated(request)) return Attempted(failure, dispatched)
        }
    }

    /**
     * A timeout or an invalid reply may be retried. A quota or budget stop and anything
     * that turned the route off for the session — a rejected key, a refused route, a 402
     * or a 429 — is terminal, and #17 already holds that state.
     */
    private fun retryable(failure: Failure, route: GradingRoute): Boolean =
        failure.mode != GraderFailure.QUOTA_EXHAUSTED && provider.unavailableCause(route) == null

    /** Withdrawn by the session, or graded against a transcript the learner has replaced. */
    private fun invalidated(request: GradingRequest): Boolean =
        request in withdrawn || currentRevision() != request.transcriptRevision
}

private val WITHDRAWN = Failure(GraderFailure.PROVIDER_ERROR, "The grading request was withdrawn before dispatch.")
