package org.ankivoice.core.journal

import org.ankivoice.core.contracts.*
import org.ankivoice.core.fakes.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * AV-018's startup reconciliation, row by row.
 *
 * Every case here begins with an entry that process loss left unsettled. Only the
 * provably-unchanged row resolves automatically, and it resolves to `failed`; no row
 * reaches `confirmed`, and no row dispatches, replays or compensates for a write.
 */
class JournalReconciliationTest {
    private class Harness {
        val store = FakeJournalStore()
        val collection = demoCollection()
        val provider = FakeCardProvider(collection)
        val transport = FakeReviewTransport(collection)
        val clock = FakeClock()
        val journal = ReviewJournal(store, clock, { 1_789_414_023_000 })
        val card = provider.nextCard() as ScheduledCard
        val token = OperationToken("av018-session", 1, 1)

        /** Journal an intent and then lose the process before anything settles it. */
        fun stranded(rating: Int = 3, transcript: String = "five blocks"): JournalEntry =
            journal.record(
                JournalRequest("av018-session", token, card.identity, rating, 4_200, 2, transcript, card.state),
            )

        fun reopen() = ReviewJournal(store, clock, { 1_789_414_023_000 })

        fun reconcile(journal: ReviewJournal = reopen()): Pair<ReviewJournal, List<Reconciliation>> =
            journal to JournalReconciler(journal, provider).reconcile()
    }

    // -- row 1: provably nothing landed -------------------------------------- //

    @Test fun `an unchanged card resolves to failed and never to confirmed`() {
        val h = Harness()
        h.stranded()
        val (journal, results) = h.reconcile()
        val result = results.single()
        assertEquals(JournalResolution.FAILED, result.resolution)
        assertEquals(h.card.state, result.observedState)
        assertTrue(result.notice.contains("was not saved"))
        assertTrue(result.notice.contains("Card ${h.card.identity.cardId}"))
        assertEquals(JournalPhase.RECONCILED, journal.entries().single().phase)
        assertTrue(h.collection.reviews.isEmpty(), "reconciliation writes nothing")
        assertTrue(h.transport.calls.isEmpty(), "reconciliation never dispatches")
    }

    @Test fun `a failed resolution needs no notice to be acknowledged`() {
        val h = Harness()
        h.stranded()
        val (journal, _) = h.reconcile()
        assertTrue(journal.outstandingNotices().isEmpty())
        assertFalse(JournalReconciler(journal, h.provider).blocked())
    }

    // -- row 2: a one-review transition this app cannot claim ----------------- //

    @Test fun `a consistent one-review transition is never auto-confirmed`() {
        val h = Harness()
        val entry = h.stranded()
        // The write this process could not verify did land — or a native reviewer made it.
        h.collection.applyReview(h.card.identity.cardId, 3, 4_200)
        val observed = h.provider.readCard(h.card.identity.cardId) as ScheduledCard
        assertTrue(isOneReviewTransition(h.card.state, observed.state), "the row's precondition")
        val (journal, results) = h.reconcile()
        val result = results.single()
        assertEquals(JournalResolution.OUTCOME_UNKNOWN, result.resolution)
        assertTrue(result.reason.contains("cannot attribute"))
        assertTrue(result.notice.contains("cannot prove it saved rating 3"))
        assertTrue(result.notice.contains("Open AnkiDroid"))
        assertFalse(result.notice.contains("Saved"), "no success message before the final state is known")
        assertEquals(1, h.collection.reviews.size, "no second review is added")
        val stored = journal.entries().single()
        assertEquals(entry.entryId, stored.entryId)
        assertNull(stored.outcomeState)
        assertTrue(stored.noticeOutstanding)
    }

    @Test fun `a competing native write between persist and restart is not claimed either`() {
        val h = Harness()
        h.stranded()
        // Another Anki session answers the card while this app is not running.
        h.collection.nativeAnswer(h.card.identity.cardId, rating = 2)
        val (_, results) = h.reconcile()
        assertEquals(JournalResolution.OUTCOME_UNKNOWN, results.single().resolution)
        assertEquals(listOf(ReviewSource.NATIVE), h.collection.reviews.map { it.source })
        assertEquals(1, h.collection.reviews.size, "no second review is added")
    }

    // -- row 3: any other change, or a changed identity ----------------------- //

    @Test fun `two reviews since the journal entry resolve to outcome-unknown`() {
        val h = Harness()
        h.stranded()
        h.collection.applyReview(h.card.identity.cardId, 3, 4_200, repetitions = 2)
        val (_, results) = h.reconcile()
        assertEquals(JournalResolution.OUTCOME_UNKNOWN, results.single().resolution)
        assertTrue(results.single().reason.contains("not a single review"))
    }

    @Test fun `any single stored-state component moving on its own is outcome-unknown`() {
        val fields = listOf<(CardState) -> CardState>(
            { it.copy(reps = it.reps + 5) }, { it.copy(cardType = 3) }, { it.copy(queue = 1) },
            { it.copy(due = it.due + 7) }, { it.copy(intervalDays = it.intervalDays + 9) },
            { it.copy(lastReviewTimeSecs = 1_789_500_000) },
        )
        for (change in fields) {
            val h = Harness()
            h.stranded()
            h.collection.mutateState(h.card.identity.cardId, change)
            val (_, results) = h.reconcile()
            assertEquals(JournalResolution.OUTCOME_UNKNOWN, results.single().resolution)
            assertTrue(h.collection.reviews.isEmpty())
        }
    }

    @Test fun `a changed identity is outcome-unknown even when the state matches`() {
        val h = Harness()
        h.stranded()
        h.provider.readCardScript.add(h.card.copy(identity = h.card.identity.copy(noteId = 7)))
        val (_, results) = h.reconcile()
        assertEquals(JournalResolution.OUTCOME_UNKNOWN, results.single().resolution)
        assertTrue(results.single().reason.contains("identity changed"))
    }

    // -- row 4: the card cannot be read at all -------------------------------- //

    @Test fun `every read failure resolves to outcome-unknown and reports its cause`() {
        val modes = listOf(
            CardProviderFailure.CARD_NOT_FOUND, CardProviderFailure.DECK_MISSING,
            CardProviderFailure.ACCESS_DENIED, CardProviderFailure.API_DISABLED,
            CardProviderFailure.PACKAGE_UNAVAILABLE, CardProviderFailure.NULL_CURSOR,
            CardProviderFailure.COLLECTION_CHANGED,
        )
        for (mode in modes) {
            val h = Harness()
            h.stranded()
            h.provider.readCardScript.add(Failure(mode, "reconciliation read"))
            val (_, results) = h.reconcile()
            val result = results.single()
            assertEquals(JournalResolution.OUTCOME_UNKNOWN, result.resolution, mode.name)
            assertEquals(mode, result.failure?.mode)
            assertNull(result.observedState)
            assertTrue(h.collection.reviews.isEmpty(), mode.name)
        }
    }

    @Test fun `a throwing provider is outcome-unknown rather than a crash`() {
        val h = Harness()
        h.stranded()
        val throwing = object : CardProvider by h.provider {
            override fun readCard(cardId: Long): ReadCardResult = throw IllegalStateException("cursor gone")
        }
        val journal = h.reopen()
        val results = JournalReconciler(journal, throwing).reconcile()
        assertEquals(JournalResolution.OUTCOME_UNKNOWN, results.single().resolution)
    }

    // -- an unreadable line --------------------------------------------------- //

    @Test fun `an unreadable entry is reconciled as outcome-unknown and never discarded`() {
        val h = Harness()
        h.stranded()
        h.store.corruptLastLine()
        val (journal, results) = h.reconcile()
        assertEquals(JournalResolution.OUTCOME_UNKNOWN, results.single().resolution)
        assertTrue(results.single().reason.contains("could not be read back"))
        assertEquals(1, journal.entries().size)
    }

    // -- the obligation, and what it is not ----------------------------------- //

    @Test fun `an unknown outcome blocks the first card until the learner acknowledges it`() {
        val h = Harness()
        val entry = h.stranded()
        h.collection.applyReview(h.card.identity.cardId, 3, 4_200)
        val (journal, _) = h.reconcile()
        val reconciler = JournalReconciler(journal, h.provider)
        assertTrue(reconciler.blocked())
        // A restart does not erase it.
        val next = h.reopen()
        assertTrue(JournalReconciler(next, h.provider).blocked())
        assertEquals(1, next.outstandingNotices().size)
        next.acknowledge(entry.entryId)
        assertFalse(JournalReconciler(h.reopen(), h.provider).blocked())
    }

    @Test fun `reconciliation runs once per entry and never re-reads a resolved one`() {
        val h = Harness()
        h.stranded()
        val (journal, first) = h.reconcile()
        assertEquals(1, first.size)
        val readsAfterFirst = h.provider.reads.size
        assertTrue(JournalReconciler(journal, h.provider).reconcile().isEmpty())
        assertTrue(JournalReconciler(h.reopen(), h.provider).reconcile().isEmpty())
        assertEquals(readsAfterFirst, h.provider.reads.size, "a resolved entry is never re-read")
    }

    @Test fun `several stranded entries are each resolved on their own evidence`() {
        val h = Harness()
        val second = h.provider.readCard(1_789_414_083_109) as ScheduledCard
        h.stranded()
        h.journal.record(
            JournalRequest("av018-session", h.token, second.identity, 4, 900, 1, "green blue red", second.state),
        )
        h.collection.applyReview(second.identity.cardId, 4, 900)
        val (_, results) = h.reconcile()
        assertEquals(
            listOf(JournalResolution.FAILED, JournalResolution.OUTCOME_UNKNOWN),
            results.map { it.resolution },
        )
        assertEquals(1, h.collection.reviews.size)
    }

    @Test fun `nothing in this card widens the session's own reconcile`() {
        // #14's reconcile(...) is the in-session, learner-reported path and stays a
        // one-argument method. AV-018 resolves into it; it never implements it.
        val method = org.ankivoice.core.session.ReviewSession::class.java.methods
            .single { it.name == "reconcile" }
        assertEquals(1, method.parameterCount)
        assertEquals(java.lang.Boolean.TYPE, method.parameterTypes.single())
    }
}
