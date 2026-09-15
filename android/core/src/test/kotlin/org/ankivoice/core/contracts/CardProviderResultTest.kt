package org.ankivoice.core.contracts

import org.ankivoice.core.fakes.FakeCardProvider
import org.ankivoice.core.fakes.StoredCard
import org.ankivoice.core.fakes.demoCollection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A null cursor is not an empty queue, and a missing deck is not exhaustion. */
class CardProviderResultTest {
    private val firstCardId = 1_789_414_083_106

    /** Exhaustive by construction: adding a result type breaks this `when`. */
    private fun classify(result: NextCardResult): String = when (result) {
        is ScheduledCard -> "card ${result.identity.cardId}"
        QueueExhausted -> "exhausted"
        is Failure -> "failure $result"
    }

    private fun classify(result: ReadCardResult): String = when (result) {
        is ScheduledCard -> "card ${result.identity.cardId}"
        is Failure -> "failure $result"
    }

    private fun classify(result: CapabilitiesResult): String = when (result) {
        is Capabilities -> "capabilities"
        is Failure -> "failure $result"
    }

    @Test
    fun `the identity is the tuple AV-004 proved readable`() {
        val card = demoCollection().scheduled(firstCardId)
        assertEquals(
            CardIdentity(firstCardId, firstCardId, card.identity.deckId, 0, VOICEQA_MODEL),
            card.identity,
        )
        assertTrue(card.identity.deckId > 0)
    }

    @Test
    fun `a valid empty queue is exhaustion`() {
        val collection = demoCollection()
        collection.rebuildQueue(emptyList())
        assertEquals("exhausted", classify(FakeCardProvider(collection).nextCard()))
    }

    @Test
    fun `a null cursor is a failure, whatever its known cause, never exhaustion`() {
        for (mode in listOf(
            CardProviderFailure.NULL_CURSOR,
            CardProviderFailure.API_DISABLED,
            CardProviderFailure.PACKAGE_UNAVAILABLE,
        )) {
            val provider = FakeCardProvider(demoCollection())
            provider.nextCardScript.addLast(Failure(mode, "scripted"))
            val result = provider.nextCard()
            assertNotEquals(QueueExhausted, result, mode.specName)
            assertEquals(mode, (result as Failure).mode)
            // The script replaced one call only; the queue is still there.
            assertEquals("card $firstCardId", classify(provider.nextCard()))
        }
    }

    @Test
    fun `a missing deck is a failure, not exhaustion`() {
        val provider = FakeCardProvider(demoCollection())
        provider.nextCardScript.addLast(Failure(CardProviderFailure.DECK_MISSING, "deck removed"))
        assertEquals("failure CardProvider.deckMissing: deck removed", classify(provider.nextCard()))
    }

    @Test
    fun `the provider validates VoiceQA identity and content before exposing a card`() {
        val cases = listOf(
            { card: StoredCard -> card.identity = card.identity.copy(model = "Basic") } to CardProviderFailure.UNSUPPORTED_NOTE_TYPE,
            { card: StoredCard -> card.identity = card.identity.copy(ordinal = 1) } to CardProviderFailure.MALFORMED_CARD,
            { card: StoredCard -> card.fields = card.fields.copy(referenceAnswer = "") } to CardProviderFailure.MALFORMED_CARD,
            { card: StoredCard -> card.fields = card.fields.copy(prompt = "  ") } to CardProviderFailure.MALFORMED_CARD,
        )
        for ((change, mode) in cases) {
            val collection = demoCollection()
            change(collection.cards.getValue(firstCardId))
            val provider = FakeCardProvider(collection)
            assertEquals(mode, (provider.nextCard() as Failure).mode)
            assertEquals(mode, (provider.readCard(firstCardId) as Failure).mode)
        }
    }

    @Test
    fun `a card that no longer exists is not found`() {
        val collection = demoCollection()
        collection.cards.remove(firstCardId)
        val provider = FakeCardProvider(collection)
        assertEquals(CardProviderFailure.CARD_NOT_FOUND, (provider.nextCard() as Failure).mode)
        assertEquals(CardProviderFailure.CARD_NOT_FOUND, (provider.readCard(42) as Failure).mode)
        assertEquals(listOf(42L), provider.reads)
    }

    @Test
    fun `a rebuilt queue does not reserve the previously offered card`() {
        val collection = demoCollection()
        val provider = FakeCardProvider(collection)
        val offered = provider.nextCard() as ScheduledCard
        collection.rebuildQueue(listOf(1_789_414_083_109))
        assertNotEquals(offered.identity, (provider.nextCard() as ScheduledCard).identity)
    }

    @Test
    fun `each script entry replaces one call and a null entry falls through`() {
        val provider = FakeCardProvider(demoCollection())
        provider.readCardScript.addAll(listOf(null, Failure(CardProviderFailure.ACCESS_DENIED, "revoked")))
        provider.capabilitiesScript.addLast(Failure(CardProviderFailure.DECK_MISSING))
        assertEquals("card $firstCardId", classify(provider.readCard(firstCardId)))
        assertEquals("failure CardProvider.accessDenied: revoked", classify(provider.readCard(firstCardId)))
        assertEquals("card $firstCardId", classify(provider.readCard(firstCardId)))
        assertEquals("failure CardProvider.deckMissing", classify(provider.capabilities()))
        assertEquals("capabilities", classify(provider.capabilities()))
        assertEquals(listOf(firstCardId, firstCardId, firstCardId), provider.reads)
    }
}
