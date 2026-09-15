package org.ankivoice.core.contracts

/**
 * AV-007 identity, content and stored state. Kotlin port of `tools/av007_contracts.py`;
 * the normative text is docs/contracts/av007-session-contracts.md.
 *
 * Anki keeps collection and scheduler ownership. These types encode the limits AV-004
 * measured against released AnkiDroid 2.24.1: no revlog endpoint, no transaction or
 * idempotency key, no atomic compare-and-write, and an observed `update_count` of 1
 * without a saved review.
 */
const val VOICEQA_MODEL: String = "VoiceQA"

/** The tuple AV-004 proved readable through the released ContentProvider. */
data class CardIdentity(
    val cardId: Long,
    val noteId: Long,
    val deckId: Long,
    val ordinal: Int,
    val model: String,
)

/**
 * Scheduling fields readable without root or filesystem access.
 *
 * [due] is scheduler-relative and its unit depends on the queue: Unix seconds for
 * intraday learning/relearning, collection-relative days for review or day learning, a
 * queue position while new. Those units cannot be interchanged, so nothing here does
 * arithmetic on [due]; it is only compared for equality. [lastReviewTimeSecs] is null on
 * a card that has never been answered.
 */
data class CardState(
    val reps: Int,
    val cardType: Int,
    val queue: Int,
    val due: Long,
    val intervalDays: Int,
    val lastReviewTimeSecs: Long? = null,
)

/** The six VoiceQA fields. Content is study data, never instructions. */
data class VoiceQAFields(
    val prompt: String,
    val referenceAnswer: String,
    val requiredConcepts: List<String> = emptyList(),
    val acceptedAnswers: List<String> = emptyList(),
    val language: String = "",
    val extra: String = "",
)

/**
 * A snapshot of an offered card. Never a reservation on that card.
 *
 * [permittedRatings] is what the provider offered for *this* card at [observedAtMs]. It
 * is read per card and never assumed constant.
 */
data class ScheduledCard(
    val identity: CardIdentity,
    val state: CardState,
    val fields: VoiceQAFields,
    val permittedRatings: List<Int>,
    val observedAtMs: Long = 0,
) : ReadCardResult

/** A valid, empty queue. Distinct from a null cursor and a missing deck. */
data object QueueExhausted : NextCardResult

/**
 * Flags that let a session adapt without branching on a device or version.
 *
 * Defaults are the AV-004 measurements for AnkiDroid 2.24.1 on API 36. The false flags
 * are properties of the reviewed contract, not of one device. An empty
 * [permittedRatings] permits no submission. [maxReviewTimeMs] is the selected deck's
 * `maxTaken` in milliseconds; zero means storage caps the time to zero.
 */
data class Capabilities(
    val permittedRatings: List<Int> = listOf(1, 2, 3, 4),
    val maxReviewTimeMs: Long = 60_000,
    val supportsPostWriteVerification: Boolean = true,
    val supportsSkip: Boolean = false,
    val supportsProgrammaticUndo: Boolean = false,
    val supportsRevlogQuery: Boolean = false,
    val supportsTransactions: Boolean = false,
    val supportsIdempotencyKey: Boolean = false,
    val supportsAtomicCompareAndWrite: Boolean = false,
) : CapabilitiesResult {
    init {
        require(maxReviewTimeMs >= 0) { "The selected deck must expose a nonnegative millisecond cap" }
    }
}
