package org.ankivoice.app

import java.util.concurrent.Executor
import org.ankivoice.ankidroid.AccessSnapshot
import org.ankivoice.ankidroid.DeckAccess
import org.ankivoice.core.contracts.Failure

/**
 * Binds AV-025's runtime speech capability into #23's access preflight.
 *
 * `:ankidroid` may only see `:core`, so the two halves of the support matrix are joined
 * here in the composition root. An AnkiDroid failure still wins: there is no point
 * reporting a missing voice to someone who cannot reach their collection at all.
 *
 * The check resolves real platform objects and can block while the engine initializes, so
 * it runs on [worker] and only the finished snapshot crosses back to [delivery]. That
 * keeps the existing contract: every `DeckAccess` callback arrives on the main thread.
 */
internal class SpeechAwareAccess(
    private val delegate: DeckAccess,
    private val worker: Executor,
    private val delivery: Executor,
    private val readiness: () -> Failure?,
) : DeckAccess {

    override fun inspect(selectedDeckId: Long?, callback: (AccessSnapshot) -> Unit) =
        delegate.inspect(selectedDeckId) { augment(it, callback) }

    override fun select(deckId: Long, callback: (AccessSnapshot) -> Unit) =
        delegate.select(deckId) { augment(it, callback) }

    private fun augment(snapshot: AccessSnapshot, callback: (AccessSnapshot) -> Unit) {
        if (snapshot.failure != null) {
            callback(snapshot)
            return
        }
        worker.execute {
            val failure = try {
                readiness()
            } catch (e: RuntimeException) {
                // A capability check that throws is a failed capability, not a ready device.
                Failure(
                    org.ankivoice.core.contracts.SpeechOutputFailure.ENGINE_UNAVAILABLE,
                    "speech capability check failed: ${e.message}",
                )
            }
            delivery.execute { callback(snapshot.copy(failure = failure)) }
        }
    }
}
