package org.ankivoice.ankidroid

import java.util.concurrent.Executor
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.SpeechInputFailure

data class Deck(val id: Long, val name: String)

data class AccessSnapshot(val decks: List<Deck> = emptyList(), val failure: Failure? = null)

/** ContentResolver/package/permission seam. null means unavailable, an empty list is valid. */
interface AccessPlatform {
    fun packageAvailable(): Boolean
    fun apiEnabled(): Boolean?
    fun databasePermissionGranted(): Boolean
    fun microphonePermissionGranted(): Boolean
    fun queryDecks(): List<Deck>?
    fun querySelectedDeck(): List<Deck>?
    fun updateSelectedDeck(deckId: Long): Int
}

interface DeckAccess {
    fun inspect(selectedDeckId: Long?, callback: (AccessSnapshot) -> Unit)
    fun select(deckId: Long, callback: (AccessSnapshot) -> Unit)
}

/**
 * All package checks and resolver work run on [worker]; only immutable results cross
 * to [delivery]. Production supplies a serial worker and Android's main executor.
 * The only mutation exposed here is selected_deck. There is no review transport.
 */
class AnkiDroidAccess(
    private val platform: AccessPlatform,
    private val worker: Executor,
    private val delivery: Executor,
) : DeckAccess {
    override fun inspect(selectedDeckId: Long?, callback: (AccessSnapshot) -> Unit) =
        dispatch(callback) { inspectNow(selectedDeckId) }

    override fun select(deckId: Long, callback: (AccessSnapshot) -> Unit) = dispatch(callback) {
        val before = inspectNow(deckId)
        if (before.failure != null) return@dispatch before
        val count = platform.updateSelectedDeck(deckId)
        val after = inspectNow(deckId)
        if (after.failure != null) return@dispatch after
        val selected = platform.querySelectedDeck() ?: return@dispatch unavailable()
        if (count != 1 || selected.singleOrNull()?.id != deckId) {
            AccessSnapshot(after.decks, Failure(CardProviderFailure.NULL_CURSOR, "Deck selection was not confirmed. Choose it again."))
        } else after
    }

    private fun inspectNow(selectedDeckId: Long?): AccessSnapshot {
        accessFailure()?.let { return AccessSnapshot(failure = it) }
        val decks = platform.queryDecks() ?: return unavailable()
        if (!platform.microphonePermissionGranted()) {
            return AccessSnapshot(decks, Failure(SpeechInputFailure.PERMISSION_DENIED))
        }
        if (selectedDeckId != null && decks.none { it.id == selectedDeckId }) {
            return AccessSnapshot(decks, Failure(CardProviderFailure.DECK_MISSING))
        }
        return AccessSnapshot(decks)
    }

    private fun accessFailure(): Failure? = when {
        !platform.packageAvailable() -> Failure(CardProviderFailure.PACKAGE_UNAVAILABLE)
        platform.apiEnabled() == false -> Failure(CardProviderFailure.API_DISABLED)
        !platform.databasePermissionGranted() -> Failure(CardProviderFailure.ACCESS_DENIED)
        else -> null
    }

    // Re-check after a null/exception: permissions or component state can change mid-call.
    private fun unavailable(): AccessSnapshot = AccessSnapshot(
        failure = accessFailure() ?: Failure(CardProviderFailure.NULL_CURSOR),
    )

    private fun dispatch(callback: (AccessSnapshot) -> Unit, operation: () -> AccessSnapshot) {
        worker.execute {
            val result = try {
                operation()
            } catch (_: SecurityException) {
                AccessSnapshot(failure = Failure(CardProviderFailure.ACCESS_DENIED))
            } catch (_: RuntimeException) {
                // Binder/provider failures cannot masquerade as an empty deck list.
                try { unavailable() } catch (_: RuntimeException) {
                    AccessSnapshot(failure = Failure(CardProviderFailure.NULL_CURSOR))
                }
            }
            delivery.execute { callback(result) }
        }
    }
}
