package org.ankivoice.core.contracts

import org.ankivoice.core.fakes.FakeCardProvider
import org.ankivoice.core.fakes.FakeCollection
import org.ankivoice.core.fakes.demoCollection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Labels propose ratings; capabilities carry the AV-004 measurements. */
class RatingsAndCapabilitiesTest {
    private val permittedRanges = listOf(
        listOf(1, 2, 3, 4),
        listOf(1, 3),
        listOf(1, 2),
        listOf(2, 3, 4),
        listOf(2),
        emptyList(),
    )

    @Test
    fun `labels map to proposals, never to commits`() {
        assertEquals(3, GradeLabel.CORRECT.automaticProposal)
        assertEquals(1, GradeLabel.INCORRECT.automaticProposal)
        assertNull(GradeLabel.PARTIAL.automaticProposal)
        assertNull(GradeLabel.UNCERTAIN.automaticProposal)
    }

    @Test
    fun `a proposal is limited to the ratings permitted for this card`() {
        for (permitted in permittedRanges) {
            for (label in GradeLabel.entries) {
                val proposal = GradingResult(label, "reason").proposedRating(permitted)
                if (proposal == null) {
                    assertTrue(label.automaticProposal.let { it == null || it !in permitted }, "$label $permitted")
                } else {
                    assertEquals(label.automaticProposal, proposal, "$label $permitted")
                    assertTrue(proposal in permitted, "$label $permitted")
                }
            }
        }
        assertEquals(3, GradingResult(GradeLabel.CORRECT, "ok").proposedRating(listOf(1, 2, 3, 4)))
        // An unoffered Good is not replaced with another rating, and never with Again.
        assertNull(GradingResult(GradeLabel.CORRECT, "ok").proposedRating(listOf(1, 2)))
        assertNull(GradingResult(GradeLabel.INCORRECT, "no").proposedRating(listOf(2, 3, 4)))
    }

    @Test
    fun `partial and uncertain propose nothing for any permitted range`() {
        for (label in listOf(GradeLabel.PARTIAL, GradeLabel.UNCERTAIN)) {
            for (permitted in permittedRanges) {
                assertNull(GradingResult(label, "unclear").proposedRating(permitted), "$label $permitted")
            }
        }
    }

    @Test
    fun `defaults record the AV-004 measurements`() {
        val capabilities = Capabilities()
        assertEquals(listOf(1, 2, 3, 4), capabilities.permittedRatings)
        assertEquals(60_000L, capabilities.maxReviewTimeMs)
        assertTrue(capabilities.supportsPostWriteVerification)
        with(capabilities) {
            for ((name, value) in listOf(
                "supportsSkip" to supportsSkip,
                "supportsProgrammaticUndo" to supportsProgrammaticUndo,
                "supportsRevlogQuery" to supportsRevlogQuery,
                "supportsTransactions" to supportsTransactions,
                "supportsIdempotencyKey" to supportsIdempotencyKey,
                "supportsAtomicCompareAndWrite" to supportsAtomicCompareAndWrite,
            )) {
                assertFalse(value, name)
            }
        }
    }

    @Test
    fun `a zero time cap is a valid cap, a negative one is not`() {
        assertEquals(0L, Capabilities(maxReviewTimeMs = 0).maxReviewTimeMs)
        assertThrows<IllegalArgumentException> { Capabilities(maxReviewTimeMs = -1) }
        val collection = FakeCollection(demoCollection().cards.values.toList(), maxReviewTimeMs = 0)
        assertEquals(Capabilities(maxReviewTimeMs = 0), FakeCardProvider(collection).capabilities())
    }

    @Test
    fun `an empty permitted range is representable and proposes nothing`() {
        val capabilities = Capabilities(permittedRatings = emptyList())
        assertTrue(capabilities.permittedRatings.isEmpty())
        for (label in GradeLabel.entries) {
            assertNull(GradingResult(label, "reason").proposedRating(capabilities.permittedRatings), label.name)
        }
    }

    @Test
    fun `permitted ratings are read per card, not assumed constant`() {
        val collection = demoCollection()
        collection.setPermittedRatings(1_789_414_083_106, listOf(1, 3))
        val provider = FakeCardProvider(collection)
        assertEquals(listOf(1, 3), (provider.nextCard() as ScheduledCard).permittedRatings)
        assertEquals(listOf(1, 2, 3, 4), (provider.readCard(1_789_414_083_109) as ScheduledCard).permittedRatings)
    }
}
