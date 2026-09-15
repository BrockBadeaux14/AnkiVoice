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
}

/**
 * A distinct learner event that authorizes exactly one pending review. A spoken
 * command must be final and sufficiently confident; touch is an explicit gesture with
 * no recognition confidence requirement.
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
