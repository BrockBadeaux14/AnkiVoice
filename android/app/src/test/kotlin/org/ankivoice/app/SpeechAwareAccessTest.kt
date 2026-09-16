package org.ankivoice.app

import java.util.concurrent.Executor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ankivoice.ankidroid.AccessSnapshot
import org.ankivoice.ankidroid.Deck
import org.ankivoice.ankidroid.DeckAccess
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.SpeechOutputFailure

/** AV-025 joins the speech capability to #23's preflight in the composition root. */
class SpeechAwareAccessTest {

    private val decks = listOf(Deck(1, "AV002 demo"))
    private val immediate = Executor { it.run() }
    private var readiness: Failure? = null
    private var readinessCalls = 0

    private class FakeAccess(private val snapshot: AccessSnapshot) : DeckAccess {
        var inspections = 0
        var selections = 0

        override fun inspect(selectedDeckId: Long?, callback: (AccessSnapshot) -> Unit) {
            inspections++
            callback(snapshot)
        }

        override fun select(deckId: Long, callback: (AccessSnapshot) -> Unit) {
            selections++
            callback(snapshot)
        }
    }

    private fun access(snapshot: AccessSnapshot, check: () -> Failure? = { readinessCalls++; readiness }) =
        FakeAccess(snapshot) to check

    private fun wrap(pair: Pair<FakeAccess, () -> Failure?>) =
        SpeechAwareAccess(pair.first, immediate, immediate, pair.second)

    @Test fun `a ready device passes the access snapshot through untouched`() {
        var result: AccessSnapshot? = null
        wrap(access(AccessSnapshot(decks))).inspect(1) { result = it }

        assertNull(result?.failure)
        assertEquals(decks, result?.decks)
        assertEquals(1, readinessCalls)
    }

    @Test fun `an unusable speech route fails the preflight instead of the first card`() {
        readiness = Failure(SpeechOutputFailure.ENGINE_UNAVAILABLE, "engine failed to initialize")
        var result: AccessSnapshot? = null
        wrap(access(AccessSnapshot(decks))).inspect(1) { result = it }

        assertEquals(SpeechOutputFailure.ENGINE_UNAVAILABLE, result?.failure?.mode)
        // The decks are still reported: this is a capability failure, not a read failure.
        assertEquals(decks, result?.decks)
    }

    @Test fun `an AnkiDroid failure wins and the speech check is not even run`() {
        readiness = Failure(SpeechInputFailure.RECOGNIZER_UNAVAILABLE)
        val existing = Failure(CardProviderFailure.PACKAGE_UNAVAILABLE)
        var result: AccessSnapshot? = null
        wrap(access(AccessSnapshot(failure = existing))).inspect(1) { result = it }

        assertEquals(existing, result?.failure)
        assertEquals(0, readinessCalls)
    }

    @Test fun `deck selection is checked the same way`() {
        readiness = Failure(SpeechInputFailure.RECOGNIZER_UNAVAILABLE, "no recognition service")
        var result: AccessSnapshot? = null
        wrap(access(AccessSnapshot(decks))).select(1) { result = it }

        assertEquals(SpeechInputFailure.RECOGNIZER_UNAVAILABLE, result?.failure?.mode)
    }

    @Test fun `a capability check that throws is a failure, never a ready device`() {
        var result: AccessSnapshot? = null
        val pair = FakeAccess(AccessSnapshot(decks)) to { throw IllegalStateException("engine blew up") }
        SpeechAwareAccess(pair.first, immediate, immediate, pair.second).inspect(1) { result = it }

        assertEquals(SpeechOutputFailure.ENGINE_UNAVAILABLE, result?.failure?.mode)
        assertTrue(result?.failure?.detail.orEmpty().contains("engine blew up"))
    }
}
