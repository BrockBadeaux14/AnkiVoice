package org.ankivoice.ankidroid

import java.util.concurrent.Executor
import org.ankivoice.core.contracts.*
import org.ankivoice.core.eligibility.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Mirrors the pinned provider's stable, non-consuming schedule prefix. */
class EligibilityIntegrationTest {
    private class Platform : ReviewPlatform {
        val state = CardState(0, 0, 0, 1, 0)
        val queue = mutableListOf<QueueCard>()
        val cards = mutableMapOf<Long, StoredReviewCard>()
        val notes = mutableMapOf<Long, ReviewNote>()
        val models = mutableListOf<InstalledNoteType>()
        val limits = mutableListOf<Int>()
        var writes = 0
        var nullSchedule = false
        var malformedSchedule = false
        fun add(id: Long, model: String = VOICEQA_MODEL, names: List<String> = VOICEQA_FIELD_NAMES,
                prompt: String = "Question", reference: String = "Answer", language: String = "en-US",
                ordinal: Int = 0, values: String? = null) {
            queue += QueueCard(id, ordinal, 4)
            cards[id] = StoredReviewCard(id + 100, id, 7, ordinal, state)
            notes[id] = ReviewNote(id, values ?: listOf(prompt, reference, "", "", language, "Extra").joinToString("\u001f"))
            models += InstalledNoteType(id, model, names)
        }
        override fun packageAvailable() = true
        override fun apiEnabled() = true
        override fun databasePermissionGranted() = true
        override fun microphonePermissionGranted() = false
        override fun queryDecks() = listOf(Deck(7, "Demo"))
        override fun querySelectedDeck() = queryDecks()
        override fun updateSelectedDeck(deckId: Long): Int { writes++; error("No deck update allowed") }
        override fun querySchedule(deckId: Long, limit: Int): List<QueueCard>? {
            limits += limit
            if (nullSchedule) return null
            if (malformedSchedule) return listOf(queue.first(), queue.first())
            return queue.take(limit)
        }
        override fun queryReviewCards(search: String) = if (search.startsWith("nid:"))
            listOfNotNull(cards[search.removePrefix("nid:").toLong()]) else
            cards.values.filter { it.cardId == search.removePrefix("cid:").toLong() }
        override fun queryReviewNote(noteId: Long) = listOfNotNull(notes[noteId])
        override fun queryReviewModels() = models
        override fun queryDeckTimeCap(deckId: Long) = listOf(60_000L)
        override fun updateSchedule(identity: CardIdentity, rating: Int, elapsedMs: Long): Int {
            writes++; error("No review, bury or suspend allowed")
        }
    }
    private val direct = Executor { it.run() }
    private fun provider(p: Platform) = AnkiDroidCardProvider(p, 7, direct, direct)

    @Test fun `real adapter advances a stable queue across all rejection reasons without writes`() {
        val reasons = listOf(
            IneligibleReason.UNSUPPORTED_NOTE_TYPE to { p: Platform -> p.add(1, model = "Basic") },
            IneligibleReason.BLANK_REQUIRED_FIELD to { p: Platform -> p.add(1, reference = " ") },
            IneligibleReason.FIELD_LAYOUT_MISMATCH to { p: Platform -> p.add(1, names = VOICEQA_FIELD_NAMES.reversed()) },
            IneligibleReason.FIELD_LAYOUT_MISMATCH to { p: Platform -> p.add(1, values = "Question\u001fAnswer") },
            IneligibleReason.MALFORMED_LANGUAGE to { p: Platform -> p.add(1, language = "en_US") },
            IneligibleReason.UNSUPPORTED_TEMPLATE to { p: Platform -> p.add(1, ordinal = 1) },
        )
        for ((reason, arrange) in reasons) {
            val p = Platform(); arrange(p); p.add(2)
            val queue = p.queue.toList(); val cards = p.cards.toMap()
            val provider = provider(p)
            val ready = assertInstanceOf(CardOffer.Ready::class.java, offerNextCard(provider, "en-US"))
            assertEquals(102L, ready.card.identity.cardId)
            assertEquals(reason, ready.skipped.single().reason)
            assertEquals(101L, ready.skipped.single().identity.cardId)
            assertEquals(listOf(1, 2), p.limits)
            // Later writer freshness reads must offer the same card, not the rejected head.
            assertEquals(ready.card.identity, (provider.nextCard() as ScheduledCard).identity)
            assertEquals(listOf(1, 2, 3, 4), (provider.readCard(102) as ScheduledCard).permittedRatings)
            assertEquals(queue, p.queue); assertEquals(cards, p.cards); assertEquals(0, p.writes)
        }
    }

    @Test fun `five distinct rejected cards stop without reading a sixth`() {
        val p = Platform()
        repeat(5) { p.add(it + 1L, model = if (it < 2) "Basic" else "Cloze") }; p.add(6)
        val stopped = assertInstanceOf(CardOffer.Stopped::class.java, offerNextCard(provider(p), "en-US"))
        assertEquals(5, stopped.skipped.map { it.identity }.distinct().size)
        assertEquals(listOf(1, 2, 3, 4, 5), p.limits)
        assertTrue(stopped.summary.contains("5 unsupported note type"))
        assertEquals(0, p.writes)
    }

    @Test fun `a shorter queue exhausts after exclusions and a new provider starts fresh`() {
        val p = Platform(); p.add(1, reference = "")
        val first = assertInstanceOf(CardOffer.Exhausted::class.java, offerNextCard(provider(p), "en-US"))
        assertEquals(1, first.skipped.size)
        assertEquals(listOf(1, 2), p.limits)
        val second = assertInstanceOf(CardOffer.Exhausted::class.java, offerNextCard(provider(p), "en-US"))
        assertEquals(first.skipped, second.skipped)
        assertEquals(0, p.writes)
    }

    @Test fun `null and malformed schedule rows pause instead of being skipped or exhausted`() {
        for (malformed in listOf(false, true)) {
            val p = Platform(); p.add(1)
            p.malformedSchedule = malformed; p.nullSchedule = !malformed
            val paused = assertInstanceOf(CardOffer.Paused::class.java, offerNextCard(provider(p), "en-US"))
            assertEquals(if (malformed) CardProviderFailure.MALFORMED_CARD else CardProviderFailure.NULL_CURSOR, paused.failure.mode)
            assertTrue(paused.skipped.isEmpty()); assertEquals(0, p.writes)
        }
    }

    @Test fun `legacy reads reject invalid layouts and language without exposing an unsafe card`() {
        val p = Platform(); p.add(1, language = "en_US")
        assertEquals(CardProviderFailure.MALFORMED_CARD, (provider(p).nextCard() as Failure).mode)
        assertEquals(CardProviderFailure.MALFORMED_CARD, (provider(p).readCard(101) as Failure).mode)
        val q = Platform(); q.add(1, names = VOICEQA_FIELD_NAMES + "Unexpected")
        assertEquals(CardProviderFailure.MALFORMED_CARD, (provider(q).nextCard() as Failure).mode)
        assertEquals(0, p.writes); assertEquals(0, q.writes)
    }

    @Test fun `well formed unavailable locale and empty language stay studiable`() {
        for (language in listOf("qaa", "")) {
            val p = Platform(); p.add(1, language = language)
            val ready = assertInstanceOf(CardOffer.Ready::class.java, offerNextCard(provider(p), "de-DE"))
            assertEquals(language.ifEmpty { "de-DE" }, ready.language)
            assertEquals(ready.language, questionUtterance(ready.card, "de-DE").language)
            assertTrue(ready.skipped.isEmpty()); assertEquals(0, p.writes)
        }
    }
}
