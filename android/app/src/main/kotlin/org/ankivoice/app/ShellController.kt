package org.ankivoice.app

import java.util.concurrent.Executor
import java.util.UUID
import org.ankivoice.ankidroid.CardReadReply
import org.ankivoice.ankidroid.AccessSnapshot
import org.ankivoice.ankidroid.Deck
import org.ankivoice.ankidroid.DeckAccess
import org.ankivoice.ankidroid.ProvisioningReport
import org.ankivoice.ankidroid.ProvisioningStep
import org.ankivoice.ankidroid.Provisioner
import org.ankivoice.core.contracts.*
import org.ankivoice.core.eligibility.CardOffer
import org.ankivoice.core.eligibility.Eligibility
import org.ankivoice.core.eligibility.offerNextCard

internal interface ShellSettings {
    var selectedDeckId: Long?
    var language: String
}

internal sealed interface PreviewStatus {
    data object Idle : PreviewStatus
    data object Starting : PreviewStatus
    data object CardReady : PreviewStatus
    data object Exhausted : PreviewStatus
    data class Paused(val failure: Failure? = null) : PreviewStatus
    data object Stopped : PreviewStatus
    data object Unavailable : PreviewStatus
    /** Five consecutive ineligible cards; not [Exhausted] and not a transport pause. */
    data object IneligibleLimit : PreviewStatus

    /**
     * AV-018: a journalled review from an earlier process could not be attributed to this
     * app. No card is offered until the learner has seen it.
     */
    data object OutcomeUnknown : PreviewStatus
}

internal data class ShellState(
    val decks: List<Deck> = emptyList(),
    val selectedDeckId: Long? = null,
    val language: String = "en-US",
    val accessFailure: Failure? = null,
    val checking: Boolean = true,
    val status: PreviewStatus = PreviewStatus.Idle,
    /** AV-039: the last thing setup found or did. Null until setup has been inspected. */
    val setup: ProvisioningReport? = null,
    val setupBusy: Boolean = false,
    /** True while the full-sync disclosure is waiting for the learner's answer. */
    val disclosing: Boolean = false,
    val skipped: List<Eligibility.Ineligible> = emptyList(),
    val skipSummary: String? = null,
    /** AV-018: what startup reconciliation could not attribute to this app, in its own words. */
    val journalNotices: List<String> = emptyList(),
    /** The journal entries those notices belong to, so the learner can acknowledge them. */
    val journalOutstanding: List<Long> = emptyList(),
)

/**
 * Shell preview only, not ReviewSession. The Activity and all DeckAccess callbacks
 * use the main thread. Each user action/foreground change invalidates older replies.
 */
internal class ShellController(
    private val access: DeckAccess,
    private val settings: ShellSettings,
    private val provisioner: Provisioner,
    private val worker: Executor = Executor { it.run() },
    private val delivery: Executor = Executor { it.run() },
    private val journal: JournalAccess? = null,
    private val cardProvider: (Long) -> CardProvider?,
) : ForegroundEventPort {
    var state = ShellState(selectedDeckId = settings.selectedDeckId, language = settings.language)
        private set
    var observer: ((ShellState) -> Unit)? = null
    private val sessionId = UUID.randomUUID().toString()
    private var generation = 0L
    private var foreground = false
    private var selecting = false
    private var refreshAfterSelection = false

    /** AV-018 reconciliation is a once-per-process pass, not a once-per-start one. */
    private var reconciled = false

    private fun publish(next: ShellState) {
        state = next
        observer?.invoke(next)
    }

    fun refresh() {
        if (selecting && foreground) {
            refreshAfterSelection = true
            return
        }
        val continueStart = state.status == PreviewStatus.Starting
        val token = ++generation
        publish(state.copy(checking = true))
        access.inspect(state.selectedDeckId) { result ->
            if (token != generation || !foreground) return@inspect
            if (continueStart) finishStart(result, state.selectedDeckId, token) else {
                val snapshot = state.withAccess(result)
                val active = state.status == PreviewStatus.CardReady || state.status == PreviewStatus.Exhausted
                publish(if (active && result.failure != null) snapshot.copy(status = PreviewStatus.Paused(result.failure)) else snapshot)
            }
        }
    }

    fun selectDeck(deckId: Long) {
        if (!foreground || state.checking) return
        val token = ++generation
        selecting = true
        publish(state.copy(checking = true, status = PreviewStatus.Idle, skipped = emptyList(), skipSummary = null))
        access.select(deckId) { result ->
            if (token != generation || !foreground) return@select
            selecting = false
            if (result.failure == null) settings.selectedDeckId = deckId
            publish(state.withAccess(result).copy(
                selectedDeckId = settings.selectedDeckId,
                status = result.failure?.let { PreviewStatus.Paused(it) } ?: PreviewStatus.Idle,
            ))
            if (refreshAfterSelection) {
                refreshAfterSelection = false
                refresh()
            }
        }
    }

    /**
     * AV-039 setup. Inspection is read-only, so it is safe to run whenever the learner asks.
     * Provisioning is never automatic: it happens only after [confirmSetup], and
     * [declineSetup] still reaches the provisioner so the refusal, not the UI, is what
     * keeps the collection unchanged.
     */
    fun checkSetup() {
        if (!foreground || state.setupBusy) return
        publish(state.copy(setupBusy = true, disclosing = false))
        provisioner.inspect(::finishSetup)
    }

    fun startSetup() {
        if (!foreground || state.setupBusy) return
        val report = state.setup
        // Disclose before the first write, using what the last inspection found.
        if (report != null && report.workRemains) publish(state.copy(disclosing = true)) else checkSetup()
    }

    fun confirmSetup() = answerDisclosure(accepted = true)
    fun declineSetup() = answerDisclosure(accepted = false)

    private fun answerDisclosure(accepted: Boolean) {
        if (!state.disclosing || state.setupBusy) return
        publish(state.copy(setupBusy = true, disclosing = false))
        provisioner.provision(accepted, ::finishSetup)
    }

    /**
     * A provisioning report describes work that has already happened, so it is published
     * even when the shell has moved on. Only the deck re-read waits for the foreground.
     */
    private fun finishSetup(report: ProvisioningReport) {
        publish(state.copy(setup = report, setupBusy = false, disclosing = false))
        // A created demo deck is the only thing provisioning adds to the deck list.
        if (foreground && report.demoDeck == ProvisioningStep.CREATED) refresh()
    }

    fun setLanguage(language: String) {
        val trimmed = language.trim()
        if (trimmed.isBlank() || trimmed == state.language) return
        stop()
        settings.language = trimmed
        publish(state.copy(language = trimmed))
    }

    fun start() {
        if (!foreground || state.checking || state.selectedDeckId == null) return
        val token = ++generation
        val deckId = state.selectedDeckId
        publish(state.copy(checking = true, status = PreviewStatus.Starting, skipped = emptyList(), skipSummary = null))
        // AV-018: whatever an unclean exit left in the journal is resolved before any card
        // is offered, and an unknown outcome stops the start rather than being studied over.
        if (journal != null && !reconciled) {
            journal.reconcile(sessionId, { cardProvider(requireNotNull(deckId)) }) { report ->
                if (token != generation || !foreground) return@reconcile
                reconciled = true
                val next = state.copy(
                    journalNotices = report.notices,
                    journalOutstanding = report.outstanding.map { it.entryId },
                )
                if (report.blocking) {
                    publish(next.copy(checking = false, status = PreviewStatus.OutcomeUnknown))
                } else {
                    publish(next)
                    access.inspect(deckId) { result ->
                        if (token != generation || !foreground) return@inspect
                        finishStart(result, deckId, token)
                    }
                }
            }
            return
        }
        access.inspect(deckId) { result ->
            if (token != generation || !foreground) return@inspect
            finishStart(result, deckId, token)
        }
    }

    /**
     * The learner has read an unknown-outcome notice. Clearing it does not resolve the
     * review: AnkiDroid is still the only place that can say what happened, and this app
     * never resubmits it.
     */
    fun acknowledgeJournalNotice(entryId: Long) {
        val access = journal ?: return
        access.acknowledge(entryId) { report ->
            val remaining = report.outstanding.map { it.entryId }
            publish(
                state.copy(
                    journalOutstanding = remaining,
                    journalNotices = if (remaining.isEmpty()) emptyList() else state.journalNotices,
                    status = if (remaining.isEmpty() && state.status == PreviewStatus.OutcomeUnknown)
                        PreviewStatus.Idle else state.status,
                ),
            )
        }
    }

    private fun finishStart(result: AccessSnapshot, deckId: Long?, token: Long) {
        val snapshot = state.withAccess(result)
        if (result.failure != null) {
            publish(snapshot.copy(status = PreviewStatus.Paused(result.failure)))
            return
        }
        // Defend the shell boundary too: an adapter may not silently drop a deck.
        if (result.decks.none { it.id == deckId }) {
            val failure = Failure(CardProviderFailure.DECK_MISSING)
            publish(snapshot.copy(accessFailure = failure, status = PreviewStatus.Paused(failure)))
            return
        }
        val operation = OperationToken(sessionId, token.toInt(), token.toInt())
        worker.execute {
            val provider = cardProvider(requireNotNull(deckId))
            val preview = if (provider == null) StartPreview(PreviewStatus.Unavailable) else {
                when (val capabilities = provider.capabilities()) {
                    is Failure -> StartPreview(PreviewStatus.Paused(capabilities))
                    is Capabilities -> when (val offer = offerNextCard(provider, snapshot.language)) {
                        is CardOffer.Ready -> StartPreview(PreviewStatus.CardReady, offer.skipped)
                        is CardOffer.Exhausted -> StartPreview(PreviewStatus.Exhausted, offer.skipped)
                        is CardOffer.Stopped -> StartPreview(PreviewStatus.IneligibleLimit, offer.skipped, offer.summary)
                        is CardOffer.Paused -> StartPreview(PreviewStatus.Paused(offer.failure), offer.skipped)
                    }
                }
            }
            val reply = CardReadReply(operation, preview)
            delivery.execute {
                if (reply.token == operation && token == generation && foreground)
                    publish(snapshot.copy(
                        status = reply.result.status,
                        skipped = reply.result.skipped,
                        skipSummary = reply.result.skipSummary,
                    ))
            }
        }
    }

    private data class StartPreview(
        val status: PreviewStatus,
        val skipped: List<Eligibility.Ineligible> = emptyList(),
        val skipSummary: String? = null,
    )

    fun stop() {
        ++generation
        selecting = false
        refreshAfterSelection = false
        publish(state.copy(checking = false, status = PreviewStatus.Stopped))
    }

    override fun onForegroundEvent(event: ForegroundEvent) {
        when (event) {
            ForegroundEvent.RESUME -> {
                foreground = true
                refresh()
            }
            ForegroundEvent.PAUSE, ForegroundEvent.STOP -> {
                foreground = false
                ++generation
                selecting = false
                refreshAfterSelection = false
                val status = when (state.status) {
                    PreviewStatus.Starting, PreviewStatus.CardReady, PreviewStatus.Exhausted -> PreviewStatus.Paused()
                    else -> state.status
                }
                // A pending disclosure is not carried across a return; setup is asked again.
                publish(state.copy(checking = false, status = status, disclosing = false))
            }
        }
    }

    private fun ShellState.withAccess(result: AccessSnapshot) = copy(
        decks = result.decks, accessFailure = result.failure, checking = false,
    )
}
