package org.ankivoice.core.contracts

// Operation results. Each is sealed, so a `when` over one is checked exhaustively and
// a Failure can never be mistaken for a value.

/** [Capabilities] or [Failure]. */
sealed interface CapabilitiesResult

/** [ScheduledCard], [QueueExhausted] or [Failure]. */
sealed interface NextCardResult

/** [ScheduledCard] or [Failure]: every read result is also a possible next-card result. */
sealed interface ReadCardResult : NextCardResult

/** Reads the scheduled VoiceQA queue. Performs no writes. */
interface CardProvider {
    fun capabilities(): CapabilitiesResult

    /** Offer the next scheduled card, or report a valid empty queue. */
    fun nextCard(): NextCardResult

    /** Re-read one card by ID for freshness and post-write verification. */
    fun readCard(cardId: Long): ReadCardResult
}

/** Speaks one utterance. Receives Prompt text only for question audio. */
interface SpeechOutput {
    fun speak(token: OperationToken, utterance: Utterance): PlaybackResult

    /** Idempotent. Later completions for [token] are ignored. */
    fun cancel(token: OperationToken)
}

/** Captures one spoken answer. A failure is never an incorrect answer. */
interface SpeechInput {
    fun listen(token: OperationToken, language: String): CaptureEvent

    /** Idempotent. Later events for [token] are ignored. */
    fun cancel(token: OperationToken)
}

/** Advisory semantic grading. Never returns or implies an Anki rating. */
interface Grader {
    fun grade(request: GradingRequest): GradingReply

    /** Invalidates [request]; later replies are ignored even if computation continues. */
    fun cancel(request: GradingRequest)
}

/** The single-shot write. Called at most once per intent, never replayed. */
interface ReviewTransport {
    fun answerCard(identity: CardIdentity, rating: Int, elapsedMs: Long): RawAcknowledgement
}

/** Commits one review under the AV-004 limits and classifies the outcome. */
interface ReviewWriter {
    fun commit(intent: ReviewIntent): ReviewOutcome
}
