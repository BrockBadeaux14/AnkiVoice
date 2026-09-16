package org.ankivoice.core.journal

import org.ankivoice.core.contracts.CardProvider
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.contracts.isOneReviewTransition

/**
 * AV-018's **startup** reconciliation: the once-per-process pass that resolves whatever
 * an unclean exit left behind, before the first card is offered.
 *
 * It is deliberately not `ReviewSession.reconcile(...)`, and neither is implemented in
 * terms of the other. #14's method is the in-session path out of an `OUTCOME_UNKNOWN`
 * halt, and its evidence is what the learner reports after looking at AnkiDroid. This
 * class runs once per process start, and its evidence is re-reading the journalled card
 * by ID against the state the journal persisted before dispatch.
 *
 * ## Why it never auto-confirms
 *
 * AV-004 measured that the AnkiDroid contract exposes no revlog endpoint, no transaction
 * or idempotency key and no atomic compare-and-write, and recorded the consequence:
 * *reps/time cannot attribute a competing native/sync write to this caller.* So `reps + 1`
 * after a restart proves that **a** review happened, not that AnkiVoice wrote it. Only the
 * provably-unchanged case resolves automatically, and it resolves to `failed`. Everything
 * else is `outcome-unknown` and pauses for the learner.
 *
 * ## What it cannot see
 *
 * The journal stores identity and stored state, never card content — the owner's
 * September 16, 2026 decision widened it to the learner's transcript and to nothing else.
 * So the table's "content changed" is checked as far as identity allows and no further.
 * That does not weaken the `failed` branch: it is stored state, byte for byte, that
 * proves no review was recorded against the card, and editing a note does not write one.
 *
 * Nothing here retries, replays or compensates for a write, in any branch.
 */
class JournalReconciler(
    private val journal: ReviewJournal,
    private val provider: CardProvider,
) {
    /**
     * Resolve every unsettled entry, oldest first, and record each decision durably.
     *
     * Returns what to tell the learner. An empty list means there was nothing to
     * reconcile — not that a write succeeded.
     */
    fun reconcile(): List<Reconciliation> = journal.unsettled().map { entry ->
        val reconciliation = classify(entry)
        journal.resolve(reconciliation)
        reconciliation
    }

    /** True while an unknown outcome is waiting to be shown. No card may be offered first. */
    fun blocked(): Boolean = journal.unsettled().isNotEmpty() || journal.outstandingNotices().isNotEmpty()

    private fun classify(entry: JournalEntry): Reconciliation {
        if (entry.phase == JournalPhase.UNREADABLE) {
            return Reconciliation(
                entry, JournalResolution.OUTCOME_UNKNOWN,
                "A journal entry from an earlier run could not be read back, so what it recorded is unknown.",
            )
        }
        val read = try {
            provider.readCard(entry.identity.cardId)
        } catch (e: RuntimeException) {
            return unknown(entry, "The card could not be read back (${e.javaClass.simpleName}).")
        }
        if (read is Failure) {
            return unknown(entry, "The card could not be read back ($read).", failure = read)
        }
        read as ScheduledCard
        if (read.identity != entry.identity) {
            return unknown(entry, "The card's identity changed since it was journalled.", read.state)
        }
        if (read.state == entry.preState) {
            return Reconciliation(
                entry, JournalResolution.FAILED,
                "The card is byte-for-byte as it was journalled, so no review was recorded against it.",
                read.state,
            )
        }
        val reason = if (isOneReviewTransition(entry.preState, read.state)) {
            "The card shows one more review than it did, which this app cannot attribute to itself."
        } else {
            "The card changed in a way that is not a single review from what was journalled."
        }
        return unknown(entry, reason, read.state)
    }

    private fun unknown(
        entry: JournalEntry,
        reason: String,
        observed: org.ankivoice.core.contracts.CardState? = null,
        failure: Failure? = null,
    ) = Reconciliation(entry, JournalResolution.OUTCOME_UNKNOWN, reason, observed, failure)
}
