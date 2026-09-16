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
 * self-grade; it never writes a review, proposes a rating, or switches to a paid or
 * alternative provider.
 *
 * ### Deadline and retry
 *
 * Each attempt gets [DEADLINE_MS], against AV-006's 12.253-second observed maximum, and
 * exactly one automatic retry is authorized. The retry:
 *
 * - reserves from the quota ledger like any other request, so it counts against the
 *   30-request session cap and the daily limit, and a retry that cannot reserve is not
 *   attempted;
 * - is bounded to one and fires only on a timeout or an invalid reply, never on a
 *   well-formed grade, a 401/403, a route refusal or a quota stop;
 * - re-sends the same transcript revision, and is abandoned rather than retried when the
 *   transcript changed while the first attempt was in flight.
 *
 * Worst-case learner-visible latency for one graded turn is therefore about 40 seconds,
 * and a fully AI-graded session can exhaust the session cap in 15 turns. #29's 30-turn
 * run expects self-grading to carry part of the run.
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

        /** One attempt plus the one authorized automatic retry. */
        const val ATTEMPTS: Int = 2
    }

    /** Requests the session withdrew. A withdrawn request is never dispatched or retried. */
    private val withdrawn = mutableSetOf<GradingRequest>()

    /** Why AI grading is off for this session, or null. Self-grading always remains. */
    val unavailable: GradingUnavailable? get() = provider.unavailableCause

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

    /** At most [ATTEMPTS] dispatches, each reserved by #17 before it leaves the device. */
    private fun ai(request: GradingRequest): GradingOutcome {
        if (invalidated(request)) return WITHDRAWN
        val system = GradingInstruction.system(request.context)
        val user = GradingInstruction.user(request.context)
        var attempt = 0
        while (true) {
            val outcome = when (val sent = provider.request(sessionId, system, user, DEADLINE_MS)) {
                is ProviderOutcome.Content -> GradingInstruction.validate(sent.text, sent.finishReason)
                is ProviderOutcome.Failed -> sent.failure
            }
            if (outcome is GradingResult) return outcome
            val failure = outcome as Failure
            attempt++
            // Bounded to one retry, never on a terminal refusal, and never across an edit.
            if (attempt >= ATTEMPTS || !retryable(failure) || invalidated(request)) return failure
        }
    }

    /**
     * A timeout or an invalid reply may be retried. A quota stop and anything that turned
     * grading off for the session — a rejected key, a refused route, a 402 or a 429 — is
     * terminal, and #17 already holds that state.
     */
    private fun retryable(failure: Failure): Boolean =
        failure.mode != GraderFailure.QUOTA_EXHAUSTED && provider.unavailableCause == null

    /** Withdrawn by the session, or graded against a transcript the learner has replaced. */
    private fun invalidated(request: GradingRequest): Boolean =
        request in withdrawn || currentRevision() != request.transcriptRevision
}

private val WITHDRAWN = Failure(GraderFailure.PROVIDER_ERROR, "The grading request was withdrawn before dispatch.")
