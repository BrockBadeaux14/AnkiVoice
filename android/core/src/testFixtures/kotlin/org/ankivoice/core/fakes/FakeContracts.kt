package org.ankivoice.core.fakes

import org.ankivoice.core.contracts.Capabilities
import org.ankivoice.core.contracts.CapabilitiesResult
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.CardIdentity
import org.ankivoice.core.contracts.CardProvider
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.Grader
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.GradingContext
import org.ankivoice.core.contracts.GradingOutcome
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.NextCardResult
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.PlaybackResult
import org.ankivoice.core.contracts.QueueExhausted
import org.ankivoice.core.contracts.RawAcknowledgement
import org.ankivoice.core.contracts.ReadCardResult
import org.ankivoice.core.contracts.ReviewTransport
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.contracts.SpeechInput
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.SpeechOutput
import org.ankivoice.core.contracts.Utterance
import org.ankivoice.core.contracts.VOICEQA_MODEL

// Every fake is open so a test can override one call, where the Python tests replace a
// bound method. A null script entry, like an empty script, falls through to the fake's
// ordinary behavior.

private fun <T> ArrayDeque<T>.pop(): T? = if (isEmpty()) null else removeFirst()

/** Reads only. Each script entry replaces one call, in order. */
open class FakeCardProvider(
    val collection: FakeCollection,
    capabilities: Capabilities? = null,
) : CardProvider {
    private val defaultCapabilities = capabilities ?: Capabilities(maxReviewTimeMs = collection.maxReviewTimeMs)
    val capabilitiesScript: ArrayDeque<CapabilitiesResult?> = ArrayDeque()
    val nextCardScript: ArrayDeque<NextCardResult?> = ArrayDeque()
    val readCardScript: ArrayDeque<ReadCardResult?> = ArrayDeque()
    val reads: MutableList<Long> = mutableListOf()

    override fun capabilities(): CapabilitiesResult = capabilitiesScript.pop() ?: defaultCapabilities

    override fun nextCard(): NextCardResult {
        nextCardScript.pop()?.let { return it }
        val cardId = collection.order.firstOrNull() ?: return QueueExhausted
        if (cardId !in collection.cards) return Failure(CardProviderFailure.CARD_NOT_FOUND, "card $cardId")
        return validate(collection.scheduled(cardId))
    }

    override fun readCard(cardId: Long): ReadCardResult {
        reads += cardId
        readCardScript.pop()?.let { return it }
        if (cardId !in collection.cards) return Failure(CardProviderFailure.CARD_NOT_FOUND, "card $cardId")
        return validate(collection.scheduled(cardId))
    }

    companion object {
        /** Identity and content validation precede exposing a card. */
        fun validate(card: ScheduledCard): ReadCardResult = when {
            card.identity.model != VOICEQA_MODEL ->
                Failure(CardProviderFailure.UNSUPPORTED_NOTE_TYPE, "VoiceQA required")
            card.identity.ordinal != 0 || card.fields.prompt.isBlank() || card.fields.referenceAnswer.isBlank() ->
                Failure(CardProviderFailure.MALFORMED_CARD, "invalid VoiceQA card")
            else -> card
        }
    }
}

/** Provider responses AV-004 observed or the contract must survive. */
enum class WriteAnomaly {
    /** Observed: 1, nothing saved. */
    ACKNOWLEDGE_WITHOUT_WRITE,

    /** Observed: invalid rating. */
    REJECT_WITH_ZERO,

    /** Null cursor. */
    NULL_RESPONSE,
    ERROR_RESPONSE,

    /** reps + 2. */
    DOUBLE_APPLY,

    /** Unexpected delta. */
    SUSPENDED_INSTEAD,

    /** Contradictory zero. */
    ZERO_BUT_APPLIED,
    UNEXPECTED_COUNT,
}

data class TransportCall(val identity: CardIdentity, val rating: Int, val elapsedMs: Long)

/** One single-shot write per intent. Records every call so a replay is visible. */
open class FakeReviewTransport(val collection: FakeCollection) : ReviewTransport {
    val anomalies: ArrayDeque<WriteAnomaly> = ArrayDeque()
    val calls: MutableList<TransportCall> = mutableListOf()

    override fun answerCard(identity: CardIdentity, rating: Int, elapsedMs: Long): RawAcknowledgement {
        calls += TransportCall(identity, rating, elapsedMs)
        val cardId = identity.cardId
        return when (anomalies.pop()) {
            WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE -> RawAcknowledgement.UpdateCount(1)
            WriteAnomaly.REJECT_WITH_ZERO -> RawAcknowledgement.UpdateCount(0)
            WriteAnomaly.NULL_RESPONSE -> RawAcknowledgement.NullResponse
            WriteAnomaly.ERROR_RESPONSE -> RawAcknowledgement.ErrorResponse(
                Failure(CardProviderFailure.API_DISABLED, "AnkiDroid API switched off mid-write"),
            )
            WriteAnomaly.DOUBLE_APPLY -> {
                collection.applyReview(cardId, rating, elapsedMs, repetitions = 2)
                RawAcknowledgement.UpdateCount(1)
            }
            WriteAnomaly.SUSPENDED_INSTEAD -> {
                collection.applyReview(cardId, rating, elapsedMs)
                collection.mutateState(cardId) { it.copy(queue = -1) }
                RawAcknowledgement.UpdateCount(1)
            }
            WriteAnomaly.ZERO_BUT_APPLIED -> {
                collection.applyReview(cardId, rating, elapsedMs)
                RawAcknowledgement.UpdateCount(0)
            }
            WriteAnomaly.UNEXPECTED_COUNT -> {
                collection.applyReview(cardId, rating, elapsedMs)
                RawAcknowledgement.UpdateCount(2)
            }
            null -> {
                collection.applyReview(cardId, rating, elapsedMs)
                RawAcknowledgement.UpdateCount(1)
            }
        }
    }
}

/** Records every attempted utterance, including ones that failed to play. */
open class FakeSpeechOutput : SpeechOutput {
    sealed interface Step

    /** Return this result as-is, even if its token is stale. */
    data class Deliver(val result: PlaybackResult) : Step

    data class Fail(val failure: Failure) : Step

    val script: ArrayDeque<Step> = ArrayDeque()
    val attempted: MutableList<Utterance> = mutableListOf()
    val spoken: MutableList<Utterance> = mutableListOf()
    val cancelled: MutableList<OperationToken> = mutableListOf()

    /** Runs as playback starts, so a test can interrupt mid-utterance. */
    var onSpeak: ((OperationToken) -> Unit)? = null

    override fun speak(token: OperationToken, utterance: Utterance): PlaybackResult {
        attempted += utterance
        onSpeak?.invoke(token)
        when (val step = script.pop()) {
            is Deliver -> return step.result
            is Fail -> return PlaybackResult.Failed(token, step.failure)
            null -> Unit
        }
        if (token !in cancelled) spoken += utterance
        return PlaybackResult.Completed(token)
    }

    override fun cancel(token: OperationToken) {
        if (token !in cancelled) cancelled += token
    }
}

/** Each script entry is a transcript or a failure; a failure is not an answer. */
open class FakeSpeechInput(vararg steps: Step) : SpeechInput {
    sealed interface Step

    /** A final transcript with sufficient confidence. */
    data class Say(val text: String) : Step

    /** Return this event as-is, even if its token is stale. */
    data class Deliver(val event: CaptureEvent) : Step

    data class Fail(val failure: Failure) : Step

    val script: ArrayDeque<Step> = ArrayDeque(steps.asList())
    val languages: MutableList<String> = mutableListOf()
    val cancelled: MutableList<OperationToken> = mutableListOf()

    /** Every Done this fake received, so a test can tell a stop from a cancel. */
    val stopped: MutableList<OperationToken> = mutableListOf()

    override fun listen(token: OperationToken, language: String): CaptureEvent {
        languages += language
        return when (val step = script.pop()) {
            null -> CaptureEvent.Failed(
                token,
                Failure(SpeechInputFailure.EARLY_CLOSURE, "script exhausted without a final"),
            )
            is Deliver -> step.event
            is Fail -> CaptureEvent.Failed(token, step.failure)
            is Say -> CaptureEvent.Transcript(token, step.text, confidence = Confidence.SUFFICIENT)
        }
    }

    /** This fake answers within [listen], so Done has nothing to stop; it is recorded. */
    override fun finishAnswer(token: OperationToken) {
        stopped += token
    }

    override fun cancel(token: OperationToken) {
        if (token !in cancelled) cancelled += token
    }
}

/** Advisory only. [seen] proves Extra never reaches the grading criteria. */
open class FakeGrader(vararg steps: Step) : Grader {
    sealed interface Step

    /** Answer the current request with this result or failure. */
    data class Answer(val outcome: GradingOutcome) : Step

    /** Return this reply as-is, even if it answers an older request. */
    data class Deliver(val reply: GradingReply) : Step

    val script: ArrayDeque<Step> = ArrayDeque(steps.asList())
    val seen: MutableList<GradingContext> = mutableListOf()
    val requests: MutableList<GradingRequest> = mutableListOf()
    val cancelled: MutableList<GradingRequest> = mutableListOf()

    override fun grade(request: GradingRequest): GradingReply {
        requests += request
        seen += request.context
        return when (val step = script.pop()) {
            null -> GradingReply(request, Failure(GraderFailure.PROVIDER_ERROR, "script exhausted"))
            is Answer -> GradingReply(request, step.outcome)
            is Deliver -> step.reply
        }
    }

    override fun cancel(request: GradingRequest) {
        if (request !in cancelled) cancelled += request
    }
}
