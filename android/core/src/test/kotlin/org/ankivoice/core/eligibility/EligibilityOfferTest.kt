package org.ankivoice.core.eligibility

import org.ankivoice.core.contracts.*
import org.ankivoice.core.fakes.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EligibilityOfferTest {
    private class Harness {
        val collection = demoCollection()
        inner class Provider : FakeCardProvider(collection), EligibilityCardProvider {
            val candidates = ArrayDeque<CandidateRead>()
            val excluded = mutableSetOf<CardIdentity>()
            var candidateReads = 0
            override fun nextCandidate(): CandidateRead {
                candidateReads++
                if (candidates.isNotEmpty()) return candidates.removeFirst()
                val card = collection.order.map(collection::scheduled).firstOrNull { it.identity !in excluded }
                return card?.let { CandidateRead.Card(it) } ?: CandidateRead.Exhausted
            }
            override fun excludeIneligibleCard(identity: CardIdentity) { excluded += identity }
        }
        val provider = Provider()
        val transport = FakeReviewTransport(collection)
        val writer = FakeReviewWriter(provider, transport)
        val order = collection.order.toList()
        val states = collection.cards.values.map { it.state }
        val good = collection.scheduled(order.first())

        fun bad(id: Long = 999, reason: IneligibleReason = IneligibleReason.UNSUPPORTED_NOTE_TYPE): CandidateRead.Card {
            val card = good.copy(identity = good.identity.copy(cardId = id, noteId = id))
            return when (reason) {
                IneligibleReason.UNSUPPORTED_NOTE_TYPE -> CandidateRead.Card(card.copy(identity = card.identity.copy(model = "Basic")))
                IneligibleReason.BLANK_REQUIRED_FIELD -> CandidateRead.Card(card.copy(fields = card.fields.copy(referenceAnswer = "")))
                IneligibleReason.FIELD_LAYOUT_MISMATCH -> CandidateRead.Card(card, listOf("Prompt", "Answer"))
                IneligibleReason.MALFORMED_LANGUAGE -> CandidateRead.Card(card.copy(fields = card.fields.copy(language = "en_US")))
                IneligibleReason.UNSUPPORTED_TEMPLATE -> CandidateRead.Card(card.copy(identity = card.identity.copy(ordinal = 1)))
            }
        }
        fun offer() = offerNextCard(provider, "en-US")
        fun assertUnchanged() {
            assertTrue(collection.reviews.isEmpty(), "no review was recorded")
            assertTrue(transport.calls.isEmpty(), "the fake writer's transport was not called")
            assertEquals(order, collection.order, "the queue was not reordered")
            assertEquals(states, collection.cards.values.map { it.state }, "no bury, suspend or reschedule")
        }
    }

    @Test fun `each eligibility reason skips with card identity and no review or scheduling mutation`() {
        for (reason in IneligibleReason.entries) {
            val h = Harness()
            h.provider.candidates.add(h.bad(reason = reason))
            val ready = assertInstanceOf(CardOffer.Ready::class.java, h.offer())
            val skipped = ready.skipped.single()
            assertEquals(reason, skipped.reason)
            assertEquals(999L, skipped.identity.cardId)
            assertEquals(UtterancePurpose.ANNOUNCEMENT, skipped.announcement.purpose)
            assertTrue(skipped.announcement.text.contains("999"))
            assertTrue(skipped.announcement.text.contains("AnkiDroid"))
            assertEquals(h.good, ready.card)
            h.assertUnchanged()
        }
    }

    @Test fun `five consecutive ineligible cards stop before a sixth read with reason counts`() {
        val h = Harness()
        repeat(5) { h.provider.candidates.add(h.bad(900L + it,
            if (it < 3) IneligibleReason.BLANK_REQUIRED_FIELD else IneligibleReason.MALFORMED_LANGUAGE)) }
        val stopped = assertInstanceOf(CardOffer.Stopped::class.java, h.offer())
        assertEquals(5, stopped.skipped.size)
        assertEquals(5, h.provider.candidateReads)
        assertTrue(stopped.summary.contains("3 blank required field"), stopped.summary)
        assertTrue(stopped.summary.contains("2 malformed language tag"), stopped.summary)
        h.assertUnchanged()
    }

    @Test fun `a studiable card resets the consecutive ineligible counter`() {
        val h = Harness()
        repeat(4) { h.provider.candidates.add(h.bad(900L + it)) }
        assertEquals(4, assertInstanceOf(CardOffer.Ready::class.java, h.offer()).skipped.size)
        repeat(4) { h.provider.candidates.add(h.bad(950L + it)) }
        assertEquals(4, assertInstanceOf(CardOffer.Ready::class.java, h.offer()).skipped.size)
        h.assertUnchanged()
    }

    @Test fun `queue exhaustion after skips is not the consecutive cap`() {
        val h = Harness()
        h.provider.candidates.add(h.bad())
        h.provider.candidates.add(CandidateRead.Exhausted)
        val exhausted = assertInstanceOf(CardOffer.Exhausted::class.java, h.offer())
        assertEquals(1, exhausted.skipped.size)
        h.assertUnchanged()
    }

    @Test fun `provider failures including malformed rows pause instead of guessing a field error`() {
        for (mode in CardProviderFailure.entries) {
            val h = Harness()
            val failure = Failure(mode, "Field layout mismatch")
            h.provider.candidates.add(h.bad())
            h.provider.candidates.add(CandidateRead.Failed(failure))
            val paused = assertInstanceOf(CardOffer.Paused::class.java, h.offer())
            assertEquals(failure, paused.failure)
            assertEquals(1, paused.skipped.size)
            h.assertUnchanged()
        }
    }

    @Test fun `repeated rejected identity pauses without counting the same card five times`() {
        val h = Harness()
        h.provider.candidates.add(h.bad())
        h.provider.candidates.add(h.bad())
        val paused = assertInstanceOf(CardOffer.Paused::class.java, h.offer())
        assertEquals(1, paused.skipped.size)
        assertEquals(2, h.provider.candidateReads)
        h.assertUnchanged()
    }

    @Test fun `an untyped provider failure is never inferred to be a skippable card`() {
        val h = Harness()
        val provider = FakeCardProvider(h.collection)
        provider.nextCardScript.add(Failure(CardProviderFailure.MALFORMED_CARD))
        val paused = assertInstanceOf(CardOffer.Paused::class.java, offerNextCard(provider, "en-US"))
        assertTrue(paused.skipped.isEmpty())
        h.assertUnchanged()
    }

    @Test fun `invalid limits fail before accessing the provider`() {
        val h = Harness()
        for (limit in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) { offerNextCard(h.provider, "en-US", limit) }
        }
        assertEquals(0, h.provider.candidateReads)
    }
}
