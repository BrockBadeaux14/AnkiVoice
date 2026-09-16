package org.ankivoice.core.journal

import org.ankivoice.core.contracts.ReviewIntent
import org.ankivoice.core.contracts.ReviewOutcome
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.ReviewWriter

/**
 * Supplies the settled transcript for the revision a rating was computed from.
 *
 * The journal may not reach into the session for it: AV-012 owns transcript state and
 * AV-013 owns the turn, and both are closed. The composition root passes the text in,
 * returning an empty string whenever the revision it is asked for is not the one it
 * holds — a superseded revision's text is never journalled against a newer rating.
 */
fun interface SettledTranscripts {
    fun textFor(intent: ReviewIntent): String
}

/**
 * The seam that makes AV-018 durable without touching AV-024's writer or AV-013's session.
 *
 * It journals the intent and flushes it, then calls the guarded writer exactly once, then
 * settles the entry from the [ReviewOutcome] that writer returned — its state, reason,
 * acknowledgement and post-state — and from nothing else. It never re-reads the card for
 * a second opinion, never re-derives confirmation, and never turns an unknown outcome
 * into a confirmed one.
 *
 * Because the entry is flushed before [ReviewWriter.commit] is entered, it is also
 * flushed before the guarded writer's pre-commit reads and before its single dispatch. An
 * intent the writer rejects before dispatching still leaves an entry, which settles as
 * `failed` with no write attempted; that is a smaller price than a window in which a
 * dispatched write has no record.
 *
 * If the delegate throws, the entry is deliberately **left unsettled**. A throw is not
 * evidence: the write may already have been handed over, and inventing a `failed` settle
 * would claim knowledge this app does not have. Startup reconciliation resolves it.
 */
class JournaledReviewWriter(
    private val delegate: ReviewWriter,
    private val journal: ReviewJournal,
    private val sessionId: String,
    private val transcripts: SettledTranscripts = SettledTranscripts { "" },
) : ReviewWriter {
    override fun commit(intent: ReviewIntent): ReviewOutcome {
        // The guarded writer's own precondition, checked before anything is written down,
        // so a replay attempt cannot leave a spurious unsettled entry behind.
        check(intent.state == ReviewState.PENDING) { "Only a pending review may be committed" }
        val snapshot = intent.cardSnapshot
        val entry = journal.record(
            JournalRequest(
                sessionId = intent.token?.sessionId ?: sessionId,
                token = intent.token,
                identity = snapshot.identity,
                rating = intent.rating,
                elapsedMs = intent.elapsedMs,
                transcriptRevision = intent.transcriptRevision,
                transcript = transcripts.textFor(intent),
                preState = snapshot.state,
            ),
        )
        val outcome = delegate.commit(intent)
        journal.settle(entry.entryId, outcome)
        return outcome
    }
}
