package org.ankivoice.core.contracts

import org.ankivoice.core.fakes.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GuardedReviewWriterTest {
    private class Harness(val caps: Capabilities = Capabilities()) {
        val collection = demoCollection()
        val provider = FakeCardProvider(collection)
        val transport = FakeReviewTransport(collection)
        val writer = FakeReviewWriter(provider, transport, caps)
        val card = provider.nextCard() as ScheduledCard
        val intent = ReviewIntent(card, 3, 98_765, OperationToken("test", 1, 1), 2)
        init { confirm() }
        fun confirm() { intent.confirm(RatingConfirmation(intent.token!!, card.identity, intent.rating, 2, ConfirmationSource.TOUCH)) }
        fun commit() = writer.commit(intent)
        fun rejected(mode: ReviewWriterFailure) {
            val result = commit()
            assertEquals(ReviewState.FAILED, result.state)
            assertEquals(mode, result.failure?.mode)
            assertFalse(result.writeAttempted)
            assertTrue(transport.calls.isEmpty())
        }
    }

    @Test fun `one transition submits measured time once and records capped expectation`() {
        val h = Harness()
        val out = h.commit()
        assertEquals(ReviewState.CONFIRMED, out.state)
        assertEquals(1, h.transport.calls.size)
        assertEquals(98_765L, h.transport.calls.single().elapsedMs)
        assertEquals(60_000L, out.expectedStoredTimeMs)
        assertTrue(out.timeWasCapped)
        assertNotNull(out.preState); assertNotNull(out.postState)
        assertThrows(IllegalStateException::class.java) { h.commit() }
        assertThrows(IllegalStateException::class.java) { h.intent.correct(4) }
        assertEquals(1, h.transport.calls.size)
    }

    @Test fun `all transport anomalies settle conservatively and never replay`() {
        for (anomaly in WriteAnomaly.entries) {
            val h = Harness(); h.transport.anomalies.add(anomaly)
            val out = h.commit()
            assertEquals(if (anomaly == WriteAnomaly.REJECT_WITH_ZERO) ReviewState.FAILED else ReviewState.OUTCOME_UNKNOWN,
                out.state, anomaly.name)
            if (anomaly == WriteAnomaly.REJECT_WITH_ZERO) assertEquals(ReviewWriterFailure.WRITE_REJECTED, out.failure?.mode)
            assertEquals(2, h.provider.reads.size, "freshness and post-write evidence for every acknowledgement")
            assertThrows(IllegalStateException::class.java) { h.commit() }
            assertEquals(1, h.transport.calls.size)
        }
    }

    @Test fun `each identity stored-state and content component is checked in both freshness reads`() {
        val base = Harness().card
        val identities = listOf(base.identity.copy(cardId = 99), base.identity.copy(noteId = 99),
            base.identity.copy(deckId = 99), base.identity.copy(ordinal = 1), base.identity.copy(model = "Basic"))
        val states = listOf(base.state.copy(reps = 999), base.state.copy(cardType = 99), base.state.copy(queue = 99),
            base.state.copy(due = 99), base.state.copy(intervalDays = 99), base.state.copy(lastReviewTimeSecs = 99))
        val fields = listOf(base.fields.copy(prompt = "changed"), base.fields.copy(referenceAnswer = "changed"),
            base.fields.copy(requiredConcepts = listOf("changed")), base.fields.copy(acceptedAnswers = listOf("changed")),
            base.fields.copy(language = "changed"), base.fields.copy(extra = "changed"))
        val variants = identities.map { base.copy(identity = it) } + states.map { base.copy(state = it) } + fields.map { base.copy(fields = it) }
        for (variant in variants) for (queue in listOf(true, false)) {
            val h = Harness()
            if (queue) h.provider.nextCardScript.add(variant) else h.provider.readCardScript.add(variant)
            h.rejected(ReviewWriterFailure.STALE_IDENTITY)
        }
        val h = Harness(); h.provider.nextCardScript.add(QueueExhausted); h.rejected(ReviewWriterFailure.STALE_IDENTITY)
    }

    @Test fun `provider failure sweep retains causes before dispatch and becomes unknown afterwards`() {
        for (mode in CardProviderFailure.entries) {
            for (queue in listOf(true, false)) {
                val h = Harness(); val failure = Failure(mode)
                if (queue) h.provider.nextCardScript.add(failure) else h.provider.readCardScript.add(failure)
                val out = h.commit()
                assertEquals(ReviewWriterFailure.PRECOMMIT_READ_FAILED, out.failure?.mode)
                assertEquals(failure, out.failure?.cause); assertTrue(h.transport.calls.isEmpty())
            }
            val h = Harness(); h.provider.readCardScript.add(null); h.provider.readCardScript.add(Failure(mode))
            assertEquals(ReviewState.OUTCOME_UNKNOWN, h.commit().state)
            assertEquals(1, h.transport.calls.size)
        }
    }

    @Test fun `ratings confirmation and time guards reject without coercion`() {
        for (rating in listOf(0, 5)) {
            val h = Harness(); h.intent.correct(rating); h.confirm(); h.rejected(ReviewWriterFailure.RATING_REJECTED)
        }
        val negative = Harness(); negative.intent.elapsedMs = -1; negative.rejected(ReviewWriterFailure.INVALID_REVIEW_TIME)
        val noConfirmation = Harness(); noConfirmation.intent.correct(3); noConfirmation.rejected(ReviewWriterFailure.CONFIRMATION_REQUIRED)
        for (queue in listOf(true, false)) {
            val h = Harness(); val changed = h.card.copy(permittedRatings = emptyList())
            if (queue) h.provider.nextCardScript.add(changed) else h.provider.readCardScript.add(changed)
            h.rejected(ReviewWriterFailure.RATING_REJECTED)
        }
        val zero = Harness(Capabilities(maxReviewTimeMs = 0))
        assertEquals(0L, zero.commit().expectedStoredTimeMs)
        val elapsedZero = Harness(); elapsedZero.intent.elapsedMs = 0
        assertEquals(ReviewState.CONFIRMED, elapsedZero.commit().state)
    }

    @Test fun `bad confirmations and corrections cannot authorize a review`() {
        val h = Harness()
        val good = h.intent.confirmation!!
        for (bad in listOf(good.copy(token = OperationToken("old", 1, 1)), good.copy(rating = 4),
            good.copy(identity = good.identity.copy(cardId = 99)), good.copy(transcriptRevision = 3),
            good.copy(final = false), good.copy(source = ConfirmationSource.SPOKEN, confidence = Confidence.LOW),
            good.copy(source = ConfirmationSource.SPOKEN, confidence = Confidence.ABSENT))) {
            assertFalse(h.intent.confirm(bad)); assertFalse(h.intent.hasConfirmation())
        }
        assertTrue(h.intent.confirm(good.copy(source = ConfirmationSource.SPOKEN, confidence = Confidence.SUFFICIENT)))
        h.intent.correct(4); h.intent.correct(3); assertFalse(h.intent.hasConfirmation())
        h.intent.cancel(); assertThrows(IllegalStateException::class.java) { h.commit() }
        assertTrue(h.transport.calls.isEmpty())
    }

    @Test fun `cancel during either read prevents dispatch and during submission or verification stays unknown`() {
        for (stage in listOf("next", "pre", "write", "post")) {
            val h = Harness(); var reads = 0
            val provider = object : CardProvider by h.provider {
                override fun nextCard(): NextCardResult {
                    if (stage == "next") h.intent.cancel()
                    return h.provider.nextCard()
                }
                override fun readCard(cardId: Long): ReadCardResult {
                    reads++
                    if ((stage == "pre" && reads == 1) || (stage == "post" && reads == 2)) h.intent.cancel()
                    return h.provider.readCard(cardId)
                }
            }
            val transport = object : ReviewTransport {
                override fun answerCard(identity: CardIdentity, rating: Int, elapsedMs: Long): RawAcknowledgement {
                    assertEquals(ReviewState.SUBMITTING, h.intent.state)
                    if (stage == "write") h.intent.cancel()
                    return h.transport.answerCard(identity, rating, elapsedMs)
                }
            }
            val out = GuardedReviewWriter(provider, transport, h.caps).commit(h.intent)
            assertEquals(if (stage in listOf("next", "pre")) ReviewState.FAILED else ReviewState.OUTCOME_UNKNOWN, out.state)
            assertEquals(if (stage in listOf("next", "pre")) 0 else 1, h.transport.calls.size)
        }
    }

    @Test fun `no verification and unexpected post identity or content never confirm`() {
        val h = Harness(Capabilities(supportsPostWriteVerification = false))
        assertEquals(ReviewState.OUTCOME_UNKNOWN, h.commit().state)
        for (change in listOf<(ScheduledCard) -> ScheduledCard>(
            { it.copy(identity = it.identity.copy(deckId = 99)) }, { it.copy(fields = it.fields.copy(extra = "changed")) })) {
            val other = Harness(); other.provider.readCardScript.add(null); other.provider.readCardScript.add(change(other.card))
            assertEquals(ReviewState.OUTCOME_UNKNOWN, other.commit().state)
        }
    }

    @Test fun `structural verification rejects every inconsistent post state without scheduling arithmetic`() {
        val pre = CardState(5, 2, 2, 123, 7, 100)
        val good = CardState(6, 2, 2, 124, 8, 101)
        assertTrue(isOneReviewTransition(pre, good))
        for (bad in listOf(good.copy(reps = 5), good.copy(reps = 7), good.copy(lastReviewTimeSecs = null),
            good.copy(lastReviewTimeSecs = 0), good.copy(lastReviewTimeSecs = 99), good.copy(cardType = 0),
            good.copy(queue = -1), good.copy(queue = -2), good.copy(intervalDays = -1),
            pre.copy(reps = 6, lastReviewTimeSecs = 101))) assertFalse(isOneReviewTransition(pre, bad))
    }
}
