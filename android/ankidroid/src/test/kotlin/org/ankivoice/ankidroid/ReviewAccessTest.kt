package org.ankivoice.ankidroid

import java.util.concurrent.Executor
import org.ankivoice.core.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReviewAccessTest {
    private class Platform : ReviewPlatform {
        var available = true; var enabled: Boolean? = true; var permission = true
        var decks: List<Deck>? = listOf(Deck(7, "Demo"))
        var queue: List<QueueCard>? = listOf(QueueCard(20, 0, 4))
        var cards: List<StoredReviewCard>? = listOf(StoredReviewCard(30, 20, 7, 0, CardState(0, 0, 0, 1, 0)))
        var notes: List<ReviewNote>? = listOf(ReviewNote(40, "Question\u001fAnswer\u001fA\nB\u001fYes\nCorrect\u001fen-US\u001fExtra"))
        var models: List<InstalledNoteType>? = listOf(InstalledNoteType(40, "VoiceQA",
            listOf("Prompt", "ReferenceAnswer", "RequiredConcepts", "AcceptedAnswers", "Language", "Extra")))
        var cap: List<Long?>? = listOf(60_000)
        var writes = 0
        var throwSecurity = false
        override fun packageAvailable() = available
        override fun apiEnabled() = enabled
        override fun databasePermissionGranted() = permission
        override fun microphonePermissionGranted() = false // Card access must not require speech permission.
        override fun queryDecks() = decks
        override fun querySelectedDeck() = decks
        override fun updateSelectedDeck(deckId: Long) = error("not used")
        override fun querySchedule(deckId: Long, limit: Int): List<QueueCard>? {
            if (throwSecurity) throw SecurityException()
            return queue
        }
        override fun queryReviewCards(search: String) = cards
        override fun queryReviewNote(noteId: Long) = notes
        override fun queryReviewModels() = models
        override fun queryDeckTimeCap(deckId: Long) = cap
        override fun updateSchedule(identity: CardIdentity, rating: Int, elapsedMs: Long): Int { writes++; return 1 }
    }
    private val direct = Executor { it.run() }
    private fun provider(p: Platform) = AnkiDroidCardProvider(p, 7, direct, direct, MonotonicClock { 123 })

    @Test fun `identity content state and per-card flags come from rows`() {
        val p = Platform(); val provider = provider(p)
        val card = provider.nextCard() as ScheduledCard
        assertEquals(CardIdentity(30, 20, 7, 0, "VoiceQA"), card.identity)
        assertEquals(p.cards!!.single().state, card.state)
        assertEquals(listOf("A", "B"), card.fields.requiredConcepts)
        assertEquals(listOf("Yes", "Correct"), card.fields.acceptedAnswers)
        assertEquals("Extra", card.fields.extra); assertEquals(123L, card.observedAtMs)
        assertEquals(Capabilities(), provider.capabilities())
        p.queue = listOf(QueueCard(20, 0, 2)); p.cap = listOf(0)
        assertEquals(listOf(1, 2), (provider.readCard(30) as ScheduledCard).permittedRatings)
        assertEquals(Capabilities(permittedRatings = listOf(1, 2), maxReviewTimeMs = 0), provider.capabilities())
        p.queue = emptyList()
        assertEquals(emptyList<Int>(), (provider.readCard(30) as ScheduledCard).permittedRatings)
    }

    @Test fun `every classified failure stays separate from exhaustion`() {
        val cases = listOf<Pair<CardProviderFailure, (Platform) -> Unit>>(
            CardProviderFailure.PACKAGE_UNAVAILABLE to { it.available = false },
            CardProviderFailure.API_DISABLED to { it.enabled = false },
            CardProviderFailure.ACCESS_DENIED to { it.permission = false },
            CardProviderFailure.ACCESS_DENIED to { it.throwSecurity = true },
            CardProviderFailure.NULL_CURSOR to { it.queue = null },
            CardProviderFailure.NULL_CURSOR to { it.decks = null },
            CardProviderFailure.NULL_CURSOR to { it.cards = null },
            CardProviderFailure.NULL_CURSOR to { it.notes = null },
            CardProviderFailure.NULL_CURSOR to { it.models = null },
            CardProviderFailure.DECK_MISSING to { it.decks = emptyList(); it.queue = emptyList() },
            CardProviderFailure.CARD_NOT_FOUND to { it.cards = emptyList() },
            CardProviderFailure.COLLECTION_CHANGED to { it.notes = emptyList() },
            CardProviderFailure.COLLECTION_CHANGED to { it.models = emptyList() },
            CardProviderFailure.COLLECTION_CHANGED to { it.cards = listOf(it.cards!!.single().copy(deckId = 8)) },
            CardProviderFailure.UNSUPPORTED_NOTE_TYPE to { it.models = listOf(it.models!!.single().copy(name = "Basic")) },
            CardProviderFailure.MALFORMED_CARD to { it.notes = listOf(ReviewNote(40, "\u001f\u001f\u001f\u001f\u001f")) },
            CardProviderFailure.MALFORMED_CARD to { it.notes = listOf(ReviewNote(40, "missing fields")) },
            CardProviderFailure.MALFORMED_CARD to { it.queue = listOf(QueueCard(20, 0, 5)) },
        )
        for ((expected, mutate) in cases) {
            val p = Platform(); mutate(p)
            assertEquals(expected, (provider(p).nextCard() as Failure).mode)
            assertEquals(0, p.writes)
        }
        val p = Platform(); p.queue = emptyList()
        assertEquals(QueueExhausted, provider(p).nextCard())
        p.cards = emptyList(); assertEquals(CardProviderFailure.CARD_NOT_FOUND, (provider(p).readCard(30) as Failure).mode)
    }

    @Test fun `missing malformed and null caps cannot silently use a default`() {
        for (cap in listOf(emptyList(), listOf(null), listOf(-1L))) {
            val p = Platform(); p.cap = cap
            assertEquals(CardProviderFailure.MALFORMED_CARD, (provider(p).capabilities() as Failure).mode)
        }
        val p = Platform(); p.cap = null
        assertEquals(CardProviderFailure.NULL_CURSOR, (provider(p).capabilities() as Failure).mode)
    }

    @Test fun `all async reads execute on worker and deliver their exact token separately`() {
        val work = ArrayDeque<Runnable>(); val delivery = ArrayDeque<Runnable>()
        val provider = AnkiDroidCardProvider(Platform(), 7, Executor { work.add(it) }, Executor { delivery.add(it) })
        val tokens = mutableListOf<OperationToken>(); val token = OperationToken("session", 8, 12)
        provider.capabilities(token) { tokens.add(it.token) }
        provider.nextCard(token) { tokens.add(it.token) }
        provider.readCard(token, 30) { tokens.add(it.token) }
        assertTrue(tokens.isEmpty()); assertTrue(delivery.isEmpty()); assertEquals(3, work.size)
        while (work.isNotEmpty()) work.removeFirst().run()
        assertTrue(tokens.isEmpty()); assertEquals(3, delivery.size)
        while (delivery.isNotEmpty()) delivery.removeFirst().run()
        assertEquals(listOf(token, token, token), tokens)
    }

    @Test fun `raw invalid transport inputs never dispatch`() {
        val p = Platform(); val transport = AnkiDroidReviewTransport(p)
        val identity = CardIdentity(30, 20, 7, 0, "VoiceQA")
        for (rating in listOf(0, 5)) assertThrows(IllegalArgumentException::class.java) { transport.answerCard(identity, rating, 1) }
        assertThrows(IllegalArgumentException::class.java) { transport.answerCard(identity, 3, -1) }
        assertEquals(0, p.writes)
        assertEquals(RawAcknowledgement.UpdateCount(1), transport.answerCard(identity, 3, 98_765))
        assertEquals(1, p.writes)
    }
}
