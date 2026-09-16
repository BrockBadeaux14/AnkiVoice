package org.ankivoice.core.grading

import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.GradingResult

/**
 * AV-016: what one graded turn may offer, and the revision the offer is bound to.
 *
 * Pure policy over an existing [GradingReply]. Nothing here sends a request, reads a
 * card or writes a review; the transport is #17's and the confirmation is #21's.
 */

/** Which policy produced a label. Rule and AI labels are never merged. */
enum class GradingSource(val specName: String) {
    /** #16's deterministic rules, decided on device with no request. */
    RULE("rule"),

    /** The pinned free route, reached only when the rules matched nothing. */
    AI("ai"),
}

/**
 * One advisory suggestion, bound to the transcript revision that produced it.
 *
 * [proposedRating] is null whenever the learner must supply the rating: for `partial`
 * and `uncertain`, and for a proposal the card does not permit. Null never means Again,
 * and a proposal is never substituted for one the card rejects.
 */
data class GradingSuggestion(
    val source: GradingSource,
    val request: GradingRequest,
    val result: GradingResult,
    val proposedRating: Int?,
) {
    val transcriptRevision: Int get() = request.transcriptRevision

    /** True when the learner, not the grader, supplies the rating for this turn. */
    val requiresSelfGrade: Boolean get() = proposedRating == null

    /**
     * False once the transcript is edited or retried. An invalidated suggestion is not
     * displayed, does not propose a rating, and cannot carry a pending confirmation
     * forward: [org.ankivoice.core.contracts.ReviewIntent.hasConfirmation] compares the
     * same revision.
     */
    fun appliesTo(currentRevision: Int): Boolean = transcriptRevision == currentRevision
}

/** The outcome of one graded turn. Nothing here submits a review. */
sealed interface TurnGrading {
    /** A validated label. The learner still confirms, and may still have to self-grade. */
    data class Graded(val suggestion: GradingSuggestion) : TurnGrading

    /** No label at all: the grader was unavailable or failed. Self-grading is the way on. */
    data class Ungraded(val reason: String, val failure: Failure? = null) : TurnGrading

    /** A reply for a superseded revision: discarded rather than displayed. */
    data object Superseded : TurnGrading
}

/**
 * Bind one reply to [currentRevision].
 *
 * A reply that graded an older revision is [TurnGrading.Superseded]: the transcript moved
 * on, so the label no longer describes what the learner said. A failure is
 * [TurnGrading.Ungraded] and keeps the card; it is never turned into `incorrect`,
 * `uncertain` or any rating.
 */
fun bindSuggestion(
    reply: GradingReply,
    source: GradingSource,
    permittedRatings: List<Int>,
    currentRevision: Int,
): TurnGrading {
    if (reply.request.transcriptRevision != currentRevision) return TurnGrading.Superseded
    return when (val result = reply.result) {
        is Failure -> TurnGrading.Ungraded(result.detail.ifEmpty { result.toString() }, result)
        is GradingResult -> TurnGrading.Graded(
            GradingSuggestion(source, reply.request, result, result.proposedRating(permittedRatings)),
        )
    }
}
