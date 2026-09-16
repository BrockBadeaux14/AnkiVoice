package org.ankivoice.core.journal

import org.ankivoice.core.contracts.*
import org.ankivoice.core.fakes.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * AV-018's durability and settle rules, on the JVM against the fakes. No emulator, no
 * network and no real clock.
 */
class ReviewJournalTest {
    private class Harness(
        val store: FakeJournalStore = FakeJournalStore(),
        retention: JournalRetention = JournalRetention.SELECTED,
        val wall: MutableLong = MutableLong(1_789_414_023_000),
    ) {
        val collection = demoCollection()
        val provider = FakeCardProvider(collection)
        val transport = FakeReviewTransport(collection)
        val clock = FakeClock()
        val guarded = FakeReviewWriter(provider, transport)
        val journal = ReviewJournal(store, clock, { wall.value }, retention)
        val card = provider.nextCard() as ScheduledCard
        val token = OperationToken("av018-session", 1, 1)

        /** A fresh journal over the same store: what the next process sees. */
        fun reopen(retention: JournalRetention = JournalRetention.SELECTED) =
            ReviewJournal(store, clock, { wall.value }, retention)

        fun writer(
            delegate: ReviewWriter = guarded,
            transcript: String = "five blocks",
        ) = JournaledReviewWriter(delegate, journal, token.sessionId) { transcript }

        fun intent(rating: Int = 3, revision: Int = 2, snapshot: ScheduledCard = card): ReviewIntent {
            val fresh = ReviewIntent(snapshot, rating, 4_200, token, revision)
            fresh.confirm(RatingConfirmation(token, snapshot.identity, rating, revision, ConfirmationSource.TOUCH))
            return fresh
        }
    }

    private class MutableLong(var value: Long)

    private class Crash : RuntimeException("process loss")

    // -- persisted before dispatch ------------------------------------------- //

    @Test fun `the entry is durable before the writer is called`() {
        val h = Harness()
        val seen = mutableListOf<Int>()
        val watching = object : ReviewWriter {
            override fun commit(intent: ReviewIntent): ReviewOutcome {
                // What a process killed at this instant would leave behind.
                seen += h.reopen().unsettled().size
                return h.guarded.commit(intent)
            }
        }
        val outcome = h.writer(watching).commit(h.intent())
        assertEquals(listOf(1), seen, "the entry must be readable from storage before dispatch")
        assertEquals(ReviewState.CONFIRMED, outcome.state)
        assertTrue(h.reopen().unsettled().isEmpty(), "a settled entry is no longer unsettled")
    }

    @Test fun `the entry records identity rating tokens revision transcript and the pre-state`() {
        val h = Harness()
        h.writer(transcript = "five blocks").commit(h.intent())
        val entry = h.reopen().entries().single()
        assertEquals(h.card.identity, entry.identity)
        assertEquals(3, entry.rating)
        assertEquals(4_200L, entry.elapsedMs)
        assertEquals("av018-session", entry.sessionId)
        assertEquals(1, entry.turn)
        assertEquals(1, entry.attempt)
        assertEquals(2, entry.transcriptRevision)
        assertEquals("five blocks", entry.transcript)
        assertEquals(h.card.state, entry.preState)
        assertEquals(1_789_414_023_000L, entry.wallClockMs)
        assertEquals(JournalPhase.SETTLED, entry.phase)
        assertEquals(ReviewState.CONFIRMED, entry.outcomeState)
        assertEquals(1, entry.acknowledgement)
        assertNotNull(entry.postState)
        assertNotEquals(entry.preState, entry.postState)
    }

    @Test fun `card content is never journalled`() {
        val h = Harness()
        h.writer(transcript = "five blocks").commit(h.intent())
        val stored = h.store.lines.joinToString("\n")
        for (secret in listOf(
            h.card.fields.prompt, h.card.fields.referenceAnswer, h.card.fields.extra,
            h.card.fields.acceptedAnswers.first(), h.card.fields.requiredConcepts.first(),
        )) {
            assertFalse(stored.contains(secret), "card content leaked into the journal: $secret")
        }
        assertTrue(stored.contains("five blocks"), "the learner's own transcript is what the owner chose to keep")
    }

    @Test fun `a crash between persist and dispatch leaves a readable unsettled entry`() {
        val h = Harness()
        // Journalled, then the process dies before the writer is ever entered.
        h.journal.record(
            JournalRequest("av018-session", h.token, h.card.identity, 3, 4_200, 2, "five blocks", h.card.state),
        )
        val next = h.reopen()
        val entry = next.unsettled().single()
        assertEquals(JournalPhase.DISPATCHING, entry.phase)
        assertEquals(3, entry.rating)
        assertEquals("five blocks", entry.transcript)
        assertTrue(h.collection.reviews.isEmpty(), "nothing was dispatched")
    }

    @Test fun `a crash between dispatch and settle leaves the entry unsettled and never retries`() {
        val h = Harness()
        val dying = object : ReviewWriter {
            override fun commit(intent: ReviewIntent): ReviewOutcome {
                h.guarded.commit(intent)
                throw Crash()
            }
        }
        assertThrows(Crash::class.java) { h.writer(dying).commit(h.intent()) }
        assertEquals(1, h.collection.reviews.size, "the write landed before the process died")
        val entry = h.reopen().unsettled().single()
        assertEquals(JournalPhase.DISPATCHING, entry.phase)
        assertNull(entry.outcomeState, "a throw is not evidence and never settles an entry")
        assertEquals(1, h.transport.calls.size, "no branch replays the write")
    }

    // -- settling from the writer's evidence only ---------------------------- //

    @Test fun `a duplicate settle is ignored and the first evidence stands`() {
        val h = Harness()
        val outcome = h.writer().commit(h.intent())
        val entry = h.journal.entries().single()
        val invented = ReviewOutcome(ReviewState.CONFIRMED, "invented", acknowledgement = 99)
        h.journal.settle(entry.entryId, invented)
        h.journal.settle(entry.entryId, invented)
        val settled = h.reopen().entries().single()
        assertEquals(outcome.reason, settled.outcomeReason)
        assertEquals(1, settled.acknowledgement)
        assertEquals(2, h.store.appends, "a duplicate settle writes nothing")
    }

    @Test fun `a settle for an unknown entry is refused rather than invented`() {
        val h = Harness()
        assertNull(h.journal.settle(404, ReviewOutcome(ReviewState.CONFIRMED, "stale callback")))
        assertTrue(h.journal.entries().isEmpty())
        assertEquals(0, h.store.appends)
    }

    @Test fun `a stale settle after reconciliation never upgrades the resolution`() {
        val h = Harness()
        val entry = h.journal.record(
            JournalRequest("av018-session", h.token, h.card.identity, 3, 4_200, 2, "five", h.card.state),
        )
        val next = h.reopen()
        JournalReconciler(next, h.provider).reconcile()
        assertEquals(JournalResolution.FAILED, next.entries().single().resolution)
        next.settle(entry.entryId, ReviewOutcome(ReviewState.CONFIRMED, "late callback"))
        val after = h.reopen().entries().single()
        assertEquals(JournalPhase.RECONCILED, after.phase)
        assertNull(after.outcomeState, "a late callback cannot settle an entry reconciliation already resolved")
        assertEquals(JournalResolution.FAILED, after.resolution)
    }

    @Test fun `an unknown outcome is recorded as unknown and never as confirmed`() {
        val h = Harness()
        h.transport.anomalies.add(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        val outcome = h.writer().commit(h.intent())
        assertEquals(ReviewState.OUTCOME_UNKNOWN, outcome.state)
        val entry = h.reopen().entries().single()
        assertEquals(ReviewState.OUTCOME_UNKNOWN, entry.outcomeState)
        assertTrue(entry.noticeOutstanding, "the learner still has to be told")
    }

    @Test fun `every write anomaly journals one entry, one dispatch and no replay`() {
        for (anomaly in WriteAnomaly.entries) {
            val h = Harness()
            h.transport.anomalies.add(anomaly)
            val outcome = h.writer().commit(h.intent())
            val entries = h.reopen().entries()
            assertEquals(1, entries.size, anomaly.name)
            assertEquals(1, h.transport.calls.size, "${anomaly.name} dispatched more than once")
            val entry = entries.single()
            assertEquals(JournalPhase.SETTLED, entry.phase, anomaly.name)
            assertEquals(outcome.state, entry.outcomeState, anomaly.name)
            assertNotEquals(ReviewState.CONFIRMED, entry.outcomeState, anomaly.name)
            // A settled entry is never reconciled, so no branch can re-read or re-dispatch it.
            assertTrue(JournalReconciler(h.reopen(), h.provider).reconcile().isEmpty(), anomaly.name)
            assertEquals(1, h.transport.calls.size, "${anomaly.name} replayed during reconciliation")
        }
    }

    @Test fun `an interrupted submission is journalled once and never compensated`() {
        val h = Harness()
        val intent = h.intent()
        val interrupting = object : ReviewWriter {
            override fun commit(intent: ReviewIntent): ReviewOutcome {
                h.transport.anomalies.add(WriteAnomaly.NULL_RESPONSE)
                return h.guarded.commit(intent)
            }
        }
        intent.cancel()
        // A cancelled intent is no longer pending, so nothing is journalled and nothing is sent.
        assertThrows(IllegalStateException::class.java) { h.writer(interrupting).commit(intent) }
        assertTrue(h.reopen().entries().isEmpty())
        assertTrue(h.transport.calls.isEmpty())
    }

    @Test fun `a replay attempt is refused before anything is journalled`() {
        val h = Harness()
        val intent = h.intent()
        h.writer().commit(intent)
        assertEquals(1, h.journal.entries().size)
        assertThrows(IllegalStateException::class.java) { h.writer().commit(intent) }
        assertEquals(1, h.journal.entries().size, "a refused replay leaves no spurious unsettled entry")
        assertEquals(1, h.transport.calls.size)
    }

    @Test fun `a pre-dispatch rejection settles as failed with no write attempted`() {
        val h = Harness()
        h.provider.nextCardScript.add(QueueExhausted)
        val outcome = h.writer().commit(h.intent())
        assertEquals(ReviewState.FAILED, outcome.state)
        val entry = h.reopen().entries().single()
        assertEquals(ReviewState.FAILED, entry.outcomeState)
        assertFalse(entry.noticeOutstanding)
        assertTrue(h.transport.calls.isEmpty())
    }

    // -- damaged storage ------------------------------------------------------ //

    @Test fun `a truncated final line survives as an unreadable entry`() {
        val h = Harness()
        h.writer().commit(h.intent())
        h.journal.record(JournalRequest("av018-session", h.token, h.card.identity, 4, 10, 3, "again", h.card.state))
        h.store.truncateLastLine()
        val entries = h.reopen().entries()
        assertEquals(2, entries.size, "the damaged line is kept, not discarded")
        assertEquals(JournalPhase.SETTLED, entries[0].phase)
        assertEquals(JournalPhase.UNREADABLE, entries[1].phase)
        assertTrue(entries[1].unsettled)
    }

    @Test fun `a corrupt line is unreadable rather than assumed settled`() {
        val h = Harness()
        h.journal.record(JournalRequest("av018-session", h.token, h.card.identity, 3, 10, 1, "five", h.card.state))
        h.store.corruptLastLine()
        val entry = h.reopen().entries().single()
        assertEquals(JournalPhase.UNREADABLE, entry.phase)
        assertNull(entry.outcomeState)
    }

    @Test fun `a settle record for a line whose dispatch was lost creates nothing`() {
        val h = Harness()
        h.writer().commit(h.intent())
        h.store.lines.removeAt(0)
        assertTrue(h.reopen().entries().isEmpty(), "an orphan settle never invents an entry")
    }

    // -- retention ------------------------------------------------------------ //

    @Test fun `pruning keeps the newest settled entries oldest first`() {
        val retention = JournalRetention(maxSettledEntries = 3)
        val h = Harness(retention = retention)
        val ids = (1..6).map { settleOne(h, "answer $it") }
        assertEquals(3, h.journal.prune(currentSessionId = "another-session"))
        val kept = h.reopen(retention).entries().map { it.entryId }
        assertEquals(ids.takeLast(3), kept)
        assertEquals(1, h.store.rewrites)
    }

    @Test fun `entries older than the retention window are pruned even under the count`() {
        val retention = JournalRetention(maxSettledEntries = 50, maxAgeMs = 7L * 24 * 60 * 60 * 1000)
        val h = Harness(retention = retention)
        val old = settleOne(h, "old")
        h.wall.value += 8L * 24 * 60 * 60 * 1000
        val fresh = settleOne(h, "fresh")
        assertEquals(1, h.journal.prune(currentSessionId = "another-session"))
        assertEquals(listOf(fresh), h.reopen(retention).entries().map { it.entryId })
        assertFalse(h.store.lines.joinToString("\n").contains("\"transcript\":\"old\""))
        assertTrue(old > 0)
    }

    @Test fun `pruning never drops the current session an unsettled entry or an unshown notice`() {
        val retention = JournalRetention(maxSettledEntries = 1)
        val h = Harness(retention = retention)
        settleOne(h, "older")
        settleOne(h, "newer")
        h.transport.anomalies.add(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        h.writer(transcript = "unknown outcome").commit(h.intent(snapshot = h.provider.nextCard() as ScheduledCard))
        h.journal.record(JournalRequest("current", h.token, h.card.identity, 2, 5, 1, "unsettled", h.card.state))
        h.journal.record(JournalRequest("current", h.token, h.card.identity, 2, 5, 1, "mine", h.card.state))
            .let { h.journal.settle(it.entryId, ReviewOutcome(ReviewState.FAILED, "rejected")) }
        h.journal.prune(currentSessionId = "current")
        val kept = h.reopen(retention).entries()
        assertEquals(
            listOf("newer", "unknown outcome", "unsettled", "mine"),
            kept.map { it.transcript },
            "only resolved history from other sessions may be pruned",
        )
    }

    @Test fun `pruning keeps an unreadable line`() {
        val retention = JournalRetention(maxSettledEntries = 1)
        val h = Harness(retention = retention)
        h.journal.record(JournalRequest("old", h.token, h.card.identity, 3, 10, 1, "damaged", h.card.state))
        h.store.corruptLastLine()
        val reopened = h.reopen(retention)
        reopened.prune(currentSessionId = "current")
        assertEquals(listOf(JournalPhase.UNREADABLE), h.reopen(retention).entries().map { it.phase })
    }

    // -- the transcript, and where it may not go ------------------------------ //

    @Test fun `transcript text survives a persist and reload round trip verbatim`() {
        val awkward = "he said \"five\", then\na newline\tand a tab \\ backslash"
        val h = Harness()
        h.writer(transcript = awkward).commit(h.intent())
        assertEquals(awkward, h.reopen().entries().single().transcript)
        assertEquals(1, h.store.lines.count { it.contains("\"record\":\"dispatch\"") })
        assertTrue(h.store.lines.none { it.contains("\n") }, "a stored line never contains a raw newline")
    }

    @Test fun `an over-long transcript is capped and says so`() {
        val retention = JournalRetention(maxTranscriptChars = 16)
        val h = Harness(retention = retention)
        h.writer(transcript = "x".repeat(400)).commit(h.intent())
        val entry = h.reopen(retention).entries().single()
        assertEquals(16, entry.transcript.length)
        assertTrue(entry.transcriptTruncated)
    }

    @Test fun `no content-free surface of an entry exposes the transcript`() {
        val h = Harness()
        h.transport.anomalies.add(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        h.writer(transcript = "the learner's own words").commit(h.intent())
        val entry = h.reopen().entries().single()
        val reconciliation = Reconciliation(entry, JournalResolution.OUTCOME_UNKNOWN, "unverified")
        for (text in listOf(entry.summary(), entry.toString(), reconciliation.notice, reconciliation.toString())) {
            assertFalse(text.contains("the learner's own words"), "transcript text leaked into: $text")
        }
        assertTrue(entry.summary().contains("card ${entry.identity.cardId}"))
    }

    private fun settleOne(h: Harness, transcript: String): Long {
        val entry = h.journal.record(
            JournalRequest("older-session", h.token, h.card.identity, 3, 10, 1, transcript, h.card.state),
        )
        h.journal.settle(entry.entryId, ReviewOutcome(ReviewState.CONFIRMED, "consistent one-review transition"))
        return entry.entryId
    }
}
