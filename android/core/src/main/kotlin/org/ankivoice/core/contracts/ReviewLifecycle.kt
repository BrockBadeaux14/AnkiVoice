package org.ankivoice.core.contracts

enum class ReviewState(val specName: String, val isTerminal: Boolean) {
    /** Proposed, correctable, nothing written. */
    PENDING("pending", isTerminal = false),

    /** Exactly one write handed over. */
    SUBMITTING("submitting", isTerminal = false),

    /** A consistent one-review transition was read back. */
    CONFIRMED("confirmed", isTerminal = true),

    /** Provably no write landed. */
    FAILED("failed", isTerminal = true),

    /** A write may or may not have landed. */
    OUTCOME_UNKNOWN("outcome-unknown", isTerminal = true),
}

val TERMINAL_REVIEW_STATES: Set<ReviewState> = ReviewState.entries.filter { it.isTerminal }.toSet()

/**
 * A rating the learner may still change. Correction is open until commit.
 *
 * Mutable, like the Python binding, because the session (#14) and the guarded writer
 * (#25) advance it in place; only :core may do so. Confine it to the session's owned
 * execution context. This type is not a thread-safety mechanism.
 */
class ReviewIntent(
    val cardSnapshot: ScheduledCard,
    rating: Int,
    elapsedMs: Long,
    token: OperationToken? = null,
    val transcriptRevision: Int = 0,
) {
    var rating: Int = rating
        private set
    var elapsedMs: Long = elapsedMs
        internal set
    var state: ReviewState = ReviewState.PENDING
        internal set
    var corrections: List<Int> = emptyList()
        private set
    var token: OperationToken? = token
        internal set
    var confirmation: RatingConfirmation? = null
        internal set

    /** Set when the single-active-reviewer precondition broke during submission. */
    var interrupted: Boolean = false
        internal set

    /** Replace the rating before commit. The previous confirmation no longer applies. */
    fun correct(rating: Int) {
        check(state == ReviewState.PENDING) { "Correction is only permitted before commit" }
        corrections = corrections + this.rating
        this.rating = rating
        confirmation = null
    }

    /** True only for a final, current confirmation bound to this intent. */
    fun hasConfirmation(): Boolean {
        val event = confirmation ?: return false
        val token = token ?: return false
        val confident = when (event.source) {
            ConfirmationSource.TOUCH -> true
            ConfirmationSource.SPOKEN -> event.confidence == Confidence.SUFFICIENT
        }
        return event.token == token &&
            event.identity == cardSnapshot.identity &&
            event.rating == rating &&
            event.transcriptRevision == transcriptRevision &&
            event.final &&
            confident
    }

    override fun toString(): String =
        "ReviewIntent(card=${cardSnapshot.identity.cardId}, rating=$rating, elapsedMs=$elapsedMs, " +
            "state=$state, corrections=$corrections, token=$token, transcriptRevision=$transcriptRevision)"
}

/**
 * One provider answer to the single-shot write. An update count of 1 is not proof of a
 * saved review; only post-write verification can confirm one.
 */
sealed interface RawAcknowledgement {
    /** The count the provider returned, or null when it returned none. */
    val updateCount: Int?

    data class UpdateCount(override val updateCount: Int) : RawAcknowledgement

    /** A null cursor in answer to the write. */
    data object NullResponse : RawAcknowledgement {
        override val updateCount: Int? get() = null
    }

    data class ErrorResponse(val failure: Failure) : RawAcknowledgement {
        override val updateCount: Int? get() = null
    }
}

data class ReviewOutcome(
    val state: ReviewState,
    val reason: String,
    val failure: Failure? = null,
    val acknowledgement: Int? = null,
    val preState: CardState? = null,
    val postState: CardState? = null,
    val submittedTimeMs: Long? = null,
    val expectedStoredTimeMs: Long? = null,
    val writeAttempted: Boolean = false,
) {
    /** An expected transformation by the deck's `maxTaken`, not a unit error. */
    val timeWasCapped: Boolean
        get() = submittedTimeMs != null && expectedStoredTimeMs != null && expectedStoredTimeMs < submittedTimeMs

    /** For offline evidence only. The app cannot read the revlog back. */
    fun storedTimeIsExpected(storedMs: Long): Boolean = storedMs == expectedStoredTimeMs
}
