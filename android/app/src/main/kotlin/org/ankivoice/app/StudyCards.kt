package org.ankivoice.app

import org.ankivoice.core.contracts.CapabilitiesResult
import org.ankivoice.core.contracts.CardIdentity
import org.ankivoice.core.contracts.CardProvider
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.NextCardResult
import org.ankivoice.core.contracts.QueueExhausted
import org.ankivoice.core.contracts.ReadCardResult
import org.ankivoice.core.eligibility.CardOffer
import org.ankivoice.core.eligibility.Eligibility
import org.ankivoice.core.eligibility.offerNextCard

/** What AV-010's traversal skipped so far in this session, for the study screen and #29's evidence. */
internal data class SkipReport(
    val skipped: List<Eligibility.Ineligible> = emptyList(),
    /** Set once five consecutive unstudiable cards stopped the traversal. */
    val summary: String? = null,
)

/**
 * AV-010's bounded, read-only skipping, placed where the session reads its cards.
 *
 * `ReviewSession.offerCard` asks the provider for the next card and nothing else, so the
 * eligibility traversal has to live in the provider it is given. Every `nextCard` runs
 * `offerNextCard`: an unstudiable card is announced and excluded in memory — never rated,
 * buried, suspended or reordered — and the first studiable one is what the session sees.
 * Five consecutive rejections stop the session with the summary AV-010 records, reported
 * as an unsupported card so the session halts without a write and the learner is told to
 * fix the notes in AnkiDroid.
 *
 * `readCard` and `capabilities` pass straight through, so the freshness reads AV-024's
 * guarded writer performs keep their guarantees. Confine it to the session's worker.
 */
internal class StudiableCardProvider(
    private val delegate: CardProvider,
    private val language: String,
) : CardProvider {
    private val skipped = LinkedHashMap<CardIdentity, Eligibility.Ineligible>()
    private var summary: String? = null

    fun report(): SkipReport = SkipReport(skipped.values.toList(), summary)

    override fun capabilities(): CapabilitiesResult = delegate.capabilities()

    override fun nextCard(): NextCardResult {
        val offer = offerNextCard(delegate, language)
        offer.skipped.forEach { skipped[it.identity] = it }
        return when (offer) {
            is CardOffer.Ready -> offer.card
            is CardOffer.Exhausted -> QueueExhausted
            is CardOffer.Paused -> offer.failure
            is CardOffer.Stopped -> {
                summary = offer.summary
                Failure(CardProviderFailure.UNSUPPORTED_NOTE_TYPE, offer.summary)
            }
        }
    }

    override fun readCard(cardId: Long): ReadCardResult = delegate.readCard(cardId)
}
