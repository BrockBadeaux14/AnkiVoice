package org.ankivoice.app

import org.ankivoice.ankidroid.AccessSnapshot
import org.ankivoice.ankidroid.Deck
import org.ankivoice.ankidroid.DeckAccess
import org.ankivoice.core.contracts.*

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
}

internal data class ShellState(
    val decks: List<Deck> = emptyList(),
    val selectedDeckId: Long? = null,
    val language: String = "en-US",
    val accessFailure: Failure? = null,
    val checking: Boolean = true,
    val status: PreviewStatus = PreviewStatus.Idle,
)

/**
 * Shell preview only, not ReviewSession. The Activity and all DeckAccess callbacks
 * use the main thread. Each user action/foreground change invalidates older replies.
 */
internal class ShellController(
    private val access: DeckAccess,
    private val settings: ShellSettings,
    private val cardProvider: () -> CardProvider?,
) : ForegroundEventPort {
    var state = ShellState(selectedDeckId = settings.selectedDeckId, language = settings.language)
        private set
    var observer: ((ShellState) -> Unit)? = null
    private var generation = 0L
    private var foreground = false
    private var selecting = false
    private var refreshAfterSelection = false

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
        publish(state.copy(checking = true, status = PreviewStatus.Idle))
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
        publish(state.copy(checking = true, status = PreviewStatus.Starting))
        access.inspect(deckId) { result ->
            if (token != generation || !foreground) return@inspect
            finishStart(result, deckId, token)
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
        val provider = cardProvider()
        val status = if (provider == null) PreviewStatus.Unavailable else {
            when (val capabilities = provider.capabilities()) {
                is Failure -> PreviewStatus.Paused(capabilities)
                is Capabilities -> when (val card = provider.nextCard()) {
                    is Failure -> PreviewStatus.Paused(card)
                    is ScheduledCard -> PreviewStatus.CardReady
                    QueueExhausted -> PreviewStatus.Exhausted
                }
            }
        }
        if (token == generation && foreground) publish(snapshot.copy(status = status))
    }

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
                publish(state.copy(checking = false, status = status))
            }
        }
    }

    private fun ShellState.withAccess(result: AccessSnapshot) = copy(
        decks = result.decks, accessFailure = result.failure, checking = false,
    )
}
