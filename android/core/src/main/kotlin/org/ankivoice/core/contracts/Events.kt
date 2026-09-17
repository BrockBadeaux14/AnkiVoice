package org.ankivoice.core.contracts

/**
 * Identifies one turn-scoped operation so late callbacks can be discarded. Not a
 * collection generation and not a provider transaction.
 */
data class OperationToken(
    val sessionId: String,
    val turn: Int,
    val sequence: Int,
)

enum class TranscriptKind(val specName: String) {
    PARTIAL("partial"),
    FINAL("final"),

    /** Created only by an explicit learner edit, never by a recognizer callback. */
    CORRECTED("learner-corrected"),
}

/** A policy classification, not an invented numeric recognizer threshold. */
enum class Confidence(val specName: String) {
    SUFFICIENT("sufficient"),
    LOW("low"),
    ABSENT("absent"),
}

/**
 * One capture event. A failure takes precedence over any accompanying text, so a
 * failed capture carries no transcript at all.
 */
sealed interface CaptureEvent {
    val token: OperationToken

    data class Transcript(
        override val token: OperationToken,
        val text: String,
        val kind: TranscriptKind = TranscriptKind.FINAL,
        val confidence: Confidence = Confidence.ABSENT,
    ) : CaptureEvent

    data class Failed(
        override val token: OperationToken,
        val failure: Failure,
    ) : CaptureEvent
}

/** The end of one playback. Success means playback completed. */
sealed interface PlaybackResult {
    val token: OperationToken

    data class Completed(override val token: OperationToken) : PlaybackResult

    data class Failed(
        override val token: OperationToken,
        val failure: Failure,
    ) : PlaybackResult
}

data class GradingRequest(
    val operationToken: OperationToken,
    val transcriptRevision: Int,
    val context: GradingContext,
)

data class GradingReply(
    val request: GradingRequest,
    val result: GradingOutcome,
)

enum class ConfirmationSource(val specName: String) {
    SPOKEN("spoken"),
    TOUCH("touch"),

    /**
     * AV-047: the session confirmed a grader proposal itself, because the learner turned
     * **Automatic grading** on and let the cancel window run out.
     *
     * It is a named source rather than a forged touch, so the journal and the session
     * record can tell an automatic commit from one the learner made. It is not a
     * recognition event, so it carries no confidence requirement; every other binding the
     * guard checks — token, identity, rating, transcript revision, finality — applies to
     * it exactly as it does to the other two. Only
     * [org.ankivoice.core.exchange.PrecommitExchange] mints one, and only while the option
     * is on for the session.
     */
    AUTO("auto"),
}

/**
 * A distinct event that authorizes exactly one pending review. A spoken command must be
 * final and sufficiently confident; touch is an explicit gesture with no recognition
 * confidence requirement; [ConfirmationSource.AUTO] is the automatic grading option's own,
 * and is never produced while that option is off.
 */
data class RatingConfirmation(
    val token: OperationToken,
    val identity: CardIdentity,
    val rating: Int,
    val transcriptRevision: Int,
    val source: ConfirmationSource,
    val final: Boolean = true,
    val confidence: Confidence = Confidence.ABSENT,
)
