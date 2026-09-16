package org.ankivoice.core.journal

import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.ReviewOutcome
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.fakes.FakeCardProvider
import org.ankivoice.core.fakes.FakeClock
import org.ankivoice.core.fakes.FakeJournalStore
import org.ankivoice.core.fakes.demoCollection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The measurement behind AV-018's retention bound.
 *
 * The card requires the worst case on disk to be measured with transcript text included,
 * rather than assumed. These numbers are what `docs/testing/av018/results.md` records;
 * this test fails if a format change moves them past the recorded ceiling, so the
 * documented figure cannot quietly go stale.
 */
class JournalSizeTest {
    /** The ceiling the results page quotes for the retained history, in bytes. */
    private val retainedHistoryCeiling = 700_000

    private fun measure(transcript: String, entries: Int): Int {
        val store = FakeJournalStore()
        val collection = demoCollection()
        val provider = FakeCardProvider(collection)
        val card = provider.nextCard() as ScheduledCard
        val journal = ReviewJournal(store, FakeClock(), { 1_789_414_023_000 })
        val token = OperationToken("av018-worst-case", 9_999, 9_999)
        repeat(entries) {
            val entry = journal.record(
                JournalRequest(
                    "av018-worst-case", token, card.identity, 4, 999_999,
                    999, transcript, card.state,
                ),
            )
            journal.settle(
                entry.entryId,
                ReviewOutcome(
                    ReviewState.OUTCOME_UNKNOWN,
                    "No consistent acknowledged one-review transition",
                    acknowledgement = 1,
                    preState = card.state,
                    postState = card.state.copy(reps = card.state.reps + 1, lastReviewTimeSecs = 1_789_414_083),
                    writeAttempted = true,
                ),
            )
        }
        return store.byteSize()
    }

    @Test fun `the retained history stays under the recorded ceiling in every transcript shape`() {
        val cap = JournalRetention.SELECTED.maxTranscriptChars
        val kept = JournalRetention.SELECTED.maxSettledEntries
        val shapes = linkedMapOf(
            "plain ASCII" to "a".repeat(cap),
            "words and punctuation" to "the answer is five blocks, ".repeat(cap).take(cap),
            "every character escaped" to "".repeat(cap),
            "four-byte UTF-8" to "😀".repeat(cap / 2),
        )
        val measured = shapes.mapValues { (_, transcript) -> measure(transcript, kept) }
        val perEntry = shapes.mapValues { (_, transcript) -> measure(transcript, 1) }
        println("AV-018 journal worst case, $kept settled entries at a $cap-character cap:")
        for ((name, bytes) in measured) {
            println("  %-24s %,9d bytes total, %,7d bytes per entry".format(name, bytes, perEntry.getValue(name)))
        }
        for ((name, bytes) in measured) {
            assertTrue(bytes <= retainedHistoryCeiling, "$name measured $bytes bytes, over the recorded ceiling")
        }
        assertEquals(shapes.keys, measured.keys)
    }

    @Test fun `storage does not grow with an over-long transcript`() {
        val cap = JournalRetention.SELECTED.maxTranscriptChars
        val atCap = measure("x".repeat(cap), 1)
        val farOver = measure("x".repeat(cap * 8), 1)
        // The only difference is the recorded `transcriptTruncated` flag, "false" to "true".
        assertTrue(farOver <= atCap, "eight times the input must not grow the file: $atCap then $farOver")
        assertEquals(atCap - 1, farOver)
    }
}
