package org.ankivoice.core.journal

import org.ankivoice.core.contracts.CardIdentity
import org.ankivoice.core.contracts.CardState
import org.ankivoice.core.contracts.ConfirmationSource
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.ReviewState

/**
 * AV-018 (#20): the durable record of one review intent.
 *
 * The journal exists because AV-004 measured what the AnkiDroid contract does not
 * expose — no revlog endpoint, no transaction, no idempotency key and no atomic
 * compare-and-write — so a process that dies between dispatch and verification has no
 * way to ask the collection what it did. What survives a restart is this file, and the
 * only thing it can honestly support afterwards is the reconciliation in
 * [JournalReconciler]: prove that nothing landed, or say plainly that the outcome is
 * unknown.
 *
 * Nothing here retries, replays or compensates for a write, and nothing here upgrades an
 * unknown outcome into a confirmed one.
 */
enum class JournalPhase(val specName: String) {
    /** Flushed to storage before the writer was called. A write may or may not have landed. */
    DISPATCHING("dispatching"),

    /** The writer returned its own evidence and that evidence was recorded verbatim. */
    SETTLED("settled"),

    /** Startup reconciliation resolved an entry that process loss left dispatching. */
    RECONCILED("reconciled"),

    /** The stored line could not be read back. Never discarded and never assumed settled. */
    UNREADABLE("unreadable"),
}

/** How startup reconciliation resolved an unsettled entry. There is no third outcome. */
enum class JournalResolution(val specName: String) {
    /** The card is byte-for-byte as it was journalled, so provably no write landed. */
    FAILED("failed"),

    /** A write may have landed and this app cannot prove it was the one that made it. */
    OUTCOME_UNKNOWN("outcome-unknown"),
}

/**
 * One journalled review intent, folded from the records written for it.
 *
 * It carries the learner's own settled transcript — the owner's September 16, 2026
 * decision, so that #29's run can show what was said on a turn that went wrong. It
 * carries **no** card content: no prompt, no reference answer, no accepted answers, no
 * audio and no grading reason. Those stay in memory, where #13 and #18 keep them.
 */
data class JournalEntry(
    val entryId: Long,
    val sessionId: String,
    val turn: Int,
    val attempt: Int,
    val identity: CardIdentity,
    val rating: Int,
    val elapsedMs: Long,
    val transcriptRevision: Int,
    /** The final transcript for the revision the rating was computed from. */
    val transcript: String,
    /** True when [transcript] was cut to [JournalRetention.maxTranscriptChars] on store. */
    val transcriptTruncated: Boolean,
    /**
     * AV-047: what authorized this write — `spoken`, `touch` or `auto` — as the intent
     * carried it at dispatch, or null for a line written before this field existed.
     *
     * It is recorded because the journal is the only record of a write that survives the
     * process, and an automatic commit the learner never confirmed must be readable back
     * as one. Recording it is not re-deriving it: the writer's own guard is what decides
     * whether the confirmation authorizes anything.
     */
    val confirmationSource: ConfirmationSource?,
    val preState: CardState,
    val monotonicMs: Long,
    val wallClockMs: Long,
    val phase: JournalPhase,
    val outcomeState: ReviewState? = null,
    val outcomeReason: String = "",
    val acknowledgement: Int? = null,
    val postState: CardState? = null,
    val settledMonotonicMs: Long? = null,
    val settledWallClockMs: Long? = null,
    val resolution: JournalResolution? = null,
    val resolutionReason: String = "",
    /** What reconciliation read back, when it could read anything. */
    val observedState: CardState? = null,
    /** True once the learner has seen an [JournalResolution.OUTCOME_UNKNOWN] notice. */
    val acknowledgedByLearner: Boolean = false,
) {
    /** Persisted before dispatch and never settled: the case reconciliation exists for. */
    val unsettled: Boolean get() = phase == JournalPhase.DISPATCHING || phase == JournalPhase.UNREADABLE

    /** An unknown outcome the learner has not been shown yet. No restart erases it. */
    val noticeOutstanding: Boolean
        get() = !acknowledgedByLearner &&
            (resolution == JournalResolution.OUTCOME_UNKNOWN || outcomeState == ReviewState.OUTCOME_UNKNOWN)

    /**
     * A content-free summary, safe for a log line or a diagnostics entry.
     *
     * The transcript is deliberately absent: [JournalEntry.transcript] is readable only
     * by asking this entry for it, so AV-020's diagnostics cannot widen their footprint
     * by recording a journal entry.
     */
    fun summary(): String = buildString {
        append("entry $entryId, card ${identity.cardId}, rating $rating, revision $transcriptRevision, ")
        confirmationSource?.let { append("confirmed by ${it.specName}, ") }
        append("phase ${phase.specName}")
        outcomeState?.let { append(", outcome ${it.specName}") }
        resolution?.let { append(", reconciled ${it.specName}") }
    }

    override fun toString(): String = "JournalEntry(${summary()})"
}

/** The result of reconciling one unsettled entry on start. Never a retry and never a rating. */
data class Reconciliation(
    val entry: JournalEntry,
    val resolution: JournalResolution,
    val reason: String,
    val observedState: CardState? = null,
    val failure: Failure? = null,
) {
    /**
     * What the learner is told, on #15's debug-grade surface and later on #27's study
     * surface. It names the card and rating and never announces success.
     */
    val notice: String
        get() = when (resolution) {
            JournalResolution.FAILED ->
                "Card ${entry.identity.cardId}: rating ${entry.rating} was not saved. The card is " +
                    "unchanged from what this app recorded before submitting, so no review was " +
                    "written. Answer it again when you are ready."
            JournalResolution.OUTCOME_UNKNOWN ->
                "Card ${entry.identity.cardId}: this app cannot prove it saved rating ${entry.rating}. " +
                    "$reason Open AnkiDroid and check the card before studying it again. AnkiVoice " +
                    "will not resubmit it."
        }
}

/**
 * What it takes to journal one intent. Assembled by [JournaledReviewWriter] from the
 * intent, the session's token and the settled transcript for its revision.
 */
data class JournalRequest(
    val sessionId: String,
    val token: OperationToken?,
    val identity: CardIdentity,
    val rating: Int,
    val elapsedMs: Long,
    val transcriptRevision: Int,
    val transcript: String,
    val preState: CardState,
    /** AV-047: the confirmation's source as the intent carried it, or null when it had none. */
    val confirmationSource: ConfirmationSource? = null,
)

/**
 * The bound on what the journal keeps: the current session, plus the most recent
 * [maxSettledEntries] settled entries **or** [maxAgeMs], whichever is smaller.
 *
 * [maxTranscriptChars] is what makes the bound a measurement rather than a hope. A typed
 * correction has no length of its own, so the journal caps the stored transcript and
 * records that it did; `JournalSizeTest` measures the resulting worst case on disk.
 */
data class JournalRetention(
    val maxSettledEntries: Int = 50,
    val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
    val maxTranscriptChars: Int = 2_000,
) {
    init {
        require(maxSettledEntries > 0) { "The journal must keep at least one settled entry" }
        require(maxAgeMs > 0) { "The retention window must be a positive duration" }
        require(maxTranscriptChars > 0) { "A stored transcript must be allowed at least one character" }
    }

    companion object {
        /** #20's decided bound: 50 settled entries or 7 days, whichever is smaller. */
        val SELECTED: JournalRetention = JournalRetention()
    }
}
