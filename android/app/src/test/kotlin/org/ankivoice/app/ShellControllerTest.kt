package org.ankivoice.app

import org.ankivoice.ankidroid.*
import org.ankivoice.core.contracts.*
import org.ankivoice.core.fakes.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShellControllerTest {
    private class Settings : ShellSettings {
        override var selectedDeckId: Long? = 7
        override var language = "en-US"
    }
    private class Access : DeckAccess {
        val callbacks = ArrayDeque<(AccessSnapshot) -> Unit>()
        val selections = mutableListOf<Long>()
        override fun inspect(selectedDeckId: Long?, callback: (AccessSnapshot) -> Unit) { callbacks.add(callback) }
        override fun select(deckId: Long, callback: (AccessSnapshot) -> Unit) { selections.add(deckId); callbacks.add(callback) }
        fun deliver(result: AccessSnapshot = AccessSnapshot(listOf(Deck(7, "Demo")))) { callbacks.removeFirst()(result) }
    }
    private val access = Access()
    private val settings = Settings()
    private val provider = FakeCardProvider(demoCollection())
    private var creations = 0
    private val controller = ShellController(access, settings) { creations++; provider }

    private fun ready() { controller.onForegroundEvent(ForegroundEvent.RESUME); access.deliver() }

    @Test fun `start checks access then capabilities and a card without a review`() {
        ready()
        controller.start()
        assertEquals(0, creations)
        access.deliver()
        assertEquals(PreviewStatus.CardReady, controller.state.status)
        assertEquals(1, creations)
        assertTrue(provider.collection.reviews.isEmpty())
    }
    @Test fun `empty fake queue is exhausted`() {
        ready()
        provider.nextCardScript.add(QueueExhausted)
        controller.start(); access.deliver()
        assertEquals(PreviewStatus.Exhausted, controller.state.status)
    }
    @Test fun `capability failure pauses before asking for a card`() {
        ready()
        provider.capabilitiesScript.add(Failure(CardProviderFailure.ACCESS_DENIED))
        provider.nextCardScript.add(QueueExhausted)
        controller.start(); access.deliver()
        assertEquals(CardProviderFailure.ACCESS_DENIED, (controller.state.status as PreviewStatus.Paused).failure?.mode)
        assertEquals(1, provider.nextCardScript.size)
    }
    @Test fun `card failure is named and paused`() {
        ready()
        provider.nextCardScript.add(Failure(CardProviderFailure.MALFORMED_CARD))
        controller.start(); access.deliver()
        assertEquals(CardProviderFailure.MALFORMED_CARD, (controller.state.status as PreviewStatus.Paused).failure?.mode)
    }
    @Test fun `every preflight failure prevents provider creation`() {
        val modes = listOf(CardProviderFailure.PACKAGE_UNAVAILABLE, CardProviderFailure.API_DISABLED,
            CardProviderFailure.ACCESS_DENIED, CardProviderFailure.NULL_CURSOR,
            CardProviderFailure.DECK_MISSING, SpeechInputFailure.PERMISSION_DENIED)
        for (mode in modes) {
            ready(); controller.start()
            access.deliver(AccessSnapshot(failure = Failure(mode)))
            assertEquals(mode, (controller.state.status as PreviewStatus.Paused).failure?.mode)
        }
        assertEquals(0, creations)
    }
    @Test fun `start refuses a missing deck even if adapter reports success`() {
        ready(); controller.start(); access.deliver(AccessSnapshot())
        assertEquals(CardProviderFailure.DECK_MISSING, controller.state.accessFailure?.mode)
        assertEquals(0, creations)
    }
    @Test fun `stop rejects a late successful preflight and never starts provider`() {
        ready(); controller.start(); controller.stop()
        val stopped = controller.state
        access.deliver()
        assertEquals(stopped, controller.state)
        assertEquals(0, creations)
    }
    @Test fun `stop rejects a late failure too`() {
        ready(); controller.start(); controller.stop()
        val stopped = controller.state
        access.deliver(AccessSnapshot(failure = Failure(CardProviderFailure.ACCESS_DENIED)))
        assertEquals(stopped, controller.state)
    }
    @Test fun `pause cancels pending start and resume only checks access`() {
        ready(); controller.start()
        controller.onForegroundEvent(ForegroundEvent.PAUSE)
        access.deliver()
        controller.onForegroundEvent(ForegroundEvent.STOP)
        controller.onForegroundEvent(ForegroundEvent.RESUME)
        access.deliver()
        assertEquals(PreviewStatus.Paused(), controller.state.status)
        assertEquals(0, creations)
        controller.start(); access.deliver()
        assertEquals(PreviewStatus.CardReady, controller.state.status)
    }
    @Test fun `stopped session stays stopped through resume and revoked permission`() {
        ready(); controller.stop()
        controller.onForegroundEvent(ForegroundEvent.PAUSE)
        controller.onForegroundEvent(ForegroundEvent.STOP)
        controller.onForegroundEvent(ForegroundEvent.RESUME)
        access.deliver(AccessSnapshot(failure = Failure(SpeechInputFailure.PERMISSION_DENIED)))
        assertEquals(PreviewStatus.Stopped, controller.state.status)
        assertEquals(SpeechInputFailure.PERMISSION_DENIED, controller.state.accessFailure?.mode)
    }
    @Test fun `release without provider retains onboarding and reports unavailable`() {
        val release = ShellController(access, settings) { null }
        release.onForegroundEvent(ForegroundEvent.RESUME); access.deliver()
        release.start(); access.deliver()
        assertEquals(PreviewStatus.Unavailable, release.state.status)
    }
    @Test fun `selected deck saved only after successful selection`() {
        ready(); controller.selectDeck(8)
        assertEquals(7L, settings.selectedDeckId)
        access.deliver(AccessSnapshot(listOf(Deck(8, "Other"))))
        assertEquals(8L, settings.selectedDeckId)
        assertEquals(8L, controller.state.selectedDeckId)
    }
    @Test fun `failed selection preserves saved deck`() {
        ready(); controller.selectDeck(8)
        access.deliver(AccessSnapshot(failure = Failure(CardProviderFailure.DECK_MISSING)))
        assertEquals(7L, settings.selectedDeckId)
    }
    @Test fun `late selection after stop does not change status or setting`() {
        ready(); controller.selectDeck(8); controller.stop()
        val stopped = controller.state
        access.deliver(AccessSnapshot(listOf(Deck(8, "Other"))))
        assertEquals(stopped, controller.state)
        assertEquals(7L, settings.selectedDeckId)
    }
    @Test fun `settings survive controller recreation and language change stops preview`() {
        ready(); controller.start(); access.deliver()
        controller.setLanguage("en-GB")
        assertEquals(PreviewStatus.Stopped, controller.state.status)
        val restored = ShellController(access, settings) { provider }
        assertEquals("en-GB", restored.state.language)
        assertEquals(7L, restored.state.selectedDeckId)
    }
    @Test fun `configuration resume during start rechecks access and completes the authorized start`() {
        ready(); controller.start()
        controller.onForegroundEvent(ForegroundEvent.RESUME)
        access.deliver() // Old Activity's in-flight preflight is stale.
        assertEquals(0, creations)
        access.deliver()
        assertEquals(PreviewStatus.CardReady, controller.state.status)
        assertEquals(1, creations)
    }
    @Test fun `configuration resume during selection preserves result then rechecks access`() {
        ready(); controller.selectDeck(8)
        controller.onForegroundEvent(ForegroundEvent.RESUME)
        access.deliver(AccessSnapshot(listOf(Deck(8, "Other"))))
        assertEquals(8L, settings.selectedDeckId)
        assertTrue(controller.state.checking)
        access.deliver(AccessSnapshot(listOf(Deck(8, "Other"))))
        assertFalse(controller.state.checking)
        assertEquals(8L, controller.state.selectedDeckId)
    }
    @Test fun `refresh pauses an active preview if access was revoked`() {
        ready(); controller.start(); access.deliver()
        controller.refresh()
        access.deliver(AccessSnapshot(failure = Failure(CardProviderFailure.ACCESS_DENIED)))
        assertEquals(CardProviderFailure.ACCESS_DENIED, (controller.state.status as PreviewStatus.Paused).failure?.mode)
    }
}
