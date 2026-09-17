package org.ankivoice.app

import org.ankivoice.ankidroid.*
import org.ankivoice.core.contracts.*
import org.ankivoice.core.eligibility.CONSECUTIVE_INELIGIBLE_LIMIT
import org.ankivoice.core.eligibility.IneligibleReason
import org.ankivoice.core.fakes.*
import org.ankivoice.core.journal.JournalRequest
import org.ankivoice.core.journal.JournalResolution
import org.ankivoice.core.journal.ReviewJournal
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
    private class Provisioning : Provisioner {
        val inspections = ArrayDeque<(ProvisioningReport) -> Unit>()
        val answers = mutableListOf<Boolean>()
        val provisions = ArrayDeque<(ProvisioningReport) -> Unit>()
        override fun inspect(callback: (ProvisioningReport) -> Unit) { inspections.add(callback) }
        override fun provision(fullSyncAccepted: Boolean, callback: (ProvisioningReport) -> Unit) {
            answers.add(fullSyncAccepted)
            provisions.add(callback)
        }
        fun inspected(report: ProvisioningReport) { inspections.removeFirst()(report) }
        fun provisioned(report: ProvisioningReport) { provisions.removeFirst()(report) }
    }
    private val access = Access()
    private val provisioner = Provisioning()
    private val settings = Settings()
    private val provider = FakeCardProvider(demoCollection())
    private fun rejected(id: Long = 999, model: String = "Basic"): ScheduledCard {
        val card = provider.collection.scheduled(provider.collection.order.first())
        return card.copy(identity = card.identity.copy(cardId = id, noteId = id, model = model))
    }
    private var creations = 0
    private val controller = ShellController(access, settings, provisioner) { creations++; provider }

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
        provider.nextCardScript.add(Failure(CardProviderFailure.ACCESS_DENIED))
        controller.start(); access.deliver()
        assertEquals(CardProviderFailure.ACCESS_DENIED, (controller.state.status as PreviewStatus.Paused).failure?.mode)
    }
    @Test fun `an ineligible card is skipped in text and the next card is offered without a review`() {
        ready()
        provider.nextCardScript.add(rejected())
        controller.start(); access.deliver()
        assertEquals(PreviewStatus.CardReady, controller.state.status)
        assertEquals(1, controller.state.skipped.size)
        assertEquals(IneligibleReason.UNSUPPORTED_NOTE_TYPE, controller.state.skipped.single().reason)
        assertTrue(controller.state.skipped.single().announcement.text.contains("AnkiDroid"))
        assertTrue(provider.collection.reviews.isEmpty())
    }
    @Test fun `five consecutive ineligible cards stop with a summary rather than exhaustion`() {
        ready()
        repeat(CONSECUTIVE_INELIGIBLE_LIMIT) {
            provider.nextCardScript.add(rejected(1000L + it, VOICEQA_MODEL).let { card ->
                card.copy(fields = card.fields.copy(referenceAnswer = ""))
            })
        }
        controller.start(); access.deliver()
        assertEquals(PreviewStatus.IneligibleLimit, controller.state.status)
        assertEquals(5, controller.state.skipped.size)
        assertTrue(controller.state.skipSummary.orEmpty().contains("5"), controller.state.skipSummary)
        assertTrue(provider.collection.reviews.isEmpty())
    }
    @Test fun `queue exhaustion after a skip stays exhausted`() {
        ready()
        provider.nextCardScript.add(rejected())
        provider.nextCardScript.add(QueueExhausted)
        controller.start(); access.deliver()
        assertEquals(PreviewStatus.Exhausted, controller.state.status)
        assertEquals(1, controller.state.skipped.size)
        assertNull(controller.state.skipSummary)
    }
    @Test fun `a new start clears the previous rejection summary`() {
        ready()
        repeat(5) { provider.nextCardScript.add(rejected(1000L + it)) }
        controller.start(); access.deliver()
        assertEquals(PreviewStatus.IneligibleLimit, controller.state.status)
        controller.start()
        assertTrue(controller.state.skipped.isEmpty())
        assertNull(controller.state.skipSummary)
        access.deliver()
        assertEquals(PreviewStatus.CardReady, controller.state.status)
    }
    @Test fun `deck selection clears rejection messages for the previous deck`() {
        ready(); provider.nextCardScript.add(rejected())
        controller.start(); access.deliver()
        assertEquals(1, controller.state.skipped.size)
        controller.selectDeck(8)
        assertTrue(controller.state.skipped.isEmpty())
        assertNull(controller.state.skipSummary)
        access.deliver(AccessSnapshot(listOf(Deck(8, "Other"))))
    }
    @Test fun `late rejection summary cannot replace a stopped session`() {
        val work = ArrayDeque<Runnable>(); val deliveries = ArrayDeque<Runnable>()
        val shell = ShellController(access, settings, provisioner,
            java.util.concurrent.Executor { work.add(it) }, java.util.concurrent.Executor { deliveries.add(it) }) { provider }
        shell.onForegroundEvent(ForegroundEvent.RESUME); access.deliver()
        repeat(5) { provider.nextCardScript.add(rejected(1000L + it)) }
        shell.start(); access.deliver(); work.removeFirst().run()
        shell.stop(); deliveries.removeFirst().run()
        assertEquals(PreviewStatus.Stopped, shell.state.status)
        assertTrue(shell.state.skipped.isEmpty()); assertNull(shell.state.skipSummary)
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
        val release = ShellController(access, settings, provisioner) { null }
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
        val restored = ShellController(access, settings, provisioner) { provider }
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

    // AV-039 setup: an explicit action, disclosed before the first write.

    private val missing = ProvisioningReport(
        ProvisioningStatus.Incomplete(listOf("Install the VoiceQA note type.")),
    )
    private val installed = ProvisioningReport(
        ProvisioningStatus.Complete, ProvisioningStep.CREATED, ProvisioningStep.CREATED,
        ProvisioningStep.CREATED, 4,
    )

    @Test fun `setup inspects on demand and never provisions by itself`() {
        ready()
        controller.checkSetup()
        assertTrue(controller.state.setupBusy)
        provisioner.inspected(missing)
        assertEquals(missing, controller.state.setup)
        assertFalse(controller.state.setupBusy)
        assertFalse(controller.state.disclosing)
        assertTrue(provisioner.answers.isEmpty())
    }
    @Test fun `the setup action discloses the full sync before any write`() {
        ready(); controller.checkSetup(); provisioner.inspected(missing)
        controller.startSetup()
        assertTrue(controller.state.disclosing)
        assertTrue(provisioner.answers.isEmpty())
        controller.confirmSetup()
        assertEquals(listOf(true), provisioner.answers)
        assertFalse(controller.state.disclosing)
        assertTrue(controller.state.setupBusy)
    }
    @Test fun `declining reaches the provisioner so the refusal is what stops the write`() {
        ready(); controller.checkSetup(); provisioner.inspected(missing)
        controller.startSetup(); controller.declineSetup()
        assertEquals(listOf(false), provisioner.answers)
        provisioner.provisioned(ProvisioningReport(ProvisioningStatus.Declined))
        assertEquals(ProvisioningStatus.Declined, controller.state.setup?.status)
        assertFalse(controller.state.disclosing)
    }
    @Test fun `a provisioned collection is rechecked rather than written to again`() {
        ready(); controller.checkSetup()
        provisioner.inspected(ProvisioningReport(ProvisioningStatus.Complete, ProvisioningStep.PRESENT))
        controller.startSetup()
        assertFalse(controller.state.disclosing)
        assertTrue(provisioner.answers.isEmpty())
        assertEquals(1, provisioner.inspections.size)
    }
    @Test fun `setup without an inspection inspects first and discloses nothing`() {
        ready()
        controller.startSetup()
        assertFalse(controller.state.disclosing)
        assertEquals(1, provisioner.inspections.size)
        assertTrue(provisioner.answers.isEmpty())
    }
    @Test fun `a created demo deck is picked up by a deck re-read`() {
        ready(); controller.checkSetup(); provisioner.inspected(missing)
        controller.startSetup(); controller.confirmSetup()
        provisioner.provisioned(installed)
        assertEquals(installed, controller.state.setup)
        assertTrue(controller.state.checking)
        access.deliver(AccessSnapshot(listOf(Deck(7, "Demo"), Deck(9, "VoiceQA Demo"))))
        assertEquals(2, controller.state.decks.size)
    }
    @Test fun `a skipped demo deck causes no deck re-read`() {
        ready(); controller.checkSetup(); provisioner.inspected(missing)
        controller.startSetup(); controller.confirmSetup()
        provisioner.provisioned(ProvisioningReport(
            ProvisioningStatus.Complete, ProvisioningStep.CREATED,
            ProvisioningStep.PRESENT, ProvisioningStep.SKIPPED,
        ))
        assertFalse(controller.state.checking)
        assertTrue(access.callbacks.isEmpty())
    }
    @Test fun `backgrounding drops a pending disclosure and blocks setup actions`() {
        ready(); controller.checkSetup(); provisioner.inspected(missing)
        controller.startSetup()
        controller.onForegroundEvent(ForegroundEvent.PAUSE)
        assertFalse(controller.state.disclosing)
        controller.confirmSetup()
        controller.checkSetup()
        controller.startSetup()
        assertTrue(provisioner.answers.isEmpty())
        assertTrue(provisioner.inspections.isEmpty())
    }
    @Test fun `a report for a completed write is published even after backgrounding`() {
        ready(); controller.checkSetup(); provisioner.inspected(missing)
        controller.startSetup(); controller.confirmSetup()
        controller.onForegroundEvent(ForegroundEvent.PAUSE)
        provisioner.provisioned(installed)
        assertEquals(installed, controller.state.setup)
        assertFalse(controller.state.setupBusy)
        assertTrue(access.callbacks.isEmpty()) // The deck re-read waits for the foreground.
    }
    @Test fun `setup stays available while the microphone is denied`() {
        controller.onForegroundEvent(ForegroundEvent.RESUME)
        access.deliver(AccessSnapshot(listOf(Deck(7, "Demo")), Failure(SpeechInputFailure.PERMISSION_DENIED)))
        controller.checkSetup(); provisioner.inspected(missing)
        controller.startSetup(); controller.confirmSetup()
        assertEquals(listOf(true), provisioner.answers)
    }
    @Test fun `a second setup action is ignored while one is in flight`() {
        ready(); controller.checkSetup()
        controller.checkSetup()
        controller.startSetup()
        assertEquals(1, provisioner.inspections.size)
        assertTrue(provisioner.answers.isEmpty())
    }
    @Test fun `real card work is dispatched and late completion after stop cannot restore readiness`() {
        val work = ArrayDeque<Runnable>()
        val deliveries = ArrayDeque<Runnable>()
        var boundDeck: Long? = null
        val shell = ShellController(access, settings, provisioner,
            java.util.concurrent.Executor { work.add(it) }, java.util.concurrent.Executor { deliveries.add(it) }) {
            boundDeck = it
            provider
        }
        shell.onForegroundEvent(ForegroundEvent.RESUME); access.deliver()
        shell.start(); access.deliver()
        assertEquals(PreviewStatus.Starting, shell.state.status)
        assertNull(boundDeck)
        work.removeFirst().run()
        assertEquals(7L, boundDeck)
        assertEquals(PreviewStatus.Starting, shell.state.status)
        shell.stop()
        deliveries.removeFirst().run()
        assertEquals(PreviewStatus.Stopped, shell.state.status)
        assertTrue(provider.collection.reviews.isEmpty())
    }
}

/**
 * AV-018 (#20) at the shell boundary: reconciliation runs before the first card is
 * offered, an unknown outcome stops the start rather than being studied over, and no
 * branch of it submits or resubmits a review.
 */
class ShellJournalTest {
    private class Settings : ShellSettings {
        override var selectedDeckId: Long? = 7
        override var language = "en-US"
    }

    private class Access : DeckAccess {
        val callbacks = ArrayDeque<(AccessSnapshot) -> Unit>()
        override fun inspect(selectedDeckId: Long?, callback: (AccessSnapshot) -> Unit) { callbacks.add(callback) }
        override fun select(deckId: Long, callback: (AccessSnapshot) -> Unit) { callbacks.add(callback) }
        fun deliver(result: AccessSnapshot = AccessSnapshot(listOf(Deck(7, "Demo")))) {
            callbacks.removeFirst()(result)
        }
    }

    private class Provisioning : Provisioner {
        override fun inspect(callback: (ProvisioningReport) -> Unit) = Unit
        override fun provision(fullSyncAccepted: Boolean, callback: (ProvisioningReport) -> Unit) = Unit
    }

    private val access = Access()
    private val provider = FakeCardProvider(demoCollection())
    private val card = provider.collection.scheduled(provider.collection.order.first())
    private val store = FakeJournalStore()
    private val journal = ReviewJournal(store)
    private val direct = java.util.concurrent.Executor { it.run() }
    private var creations = 0
    private val controller = ShellController(
        access, Settings(), Provisioning(), direct, direct,
        ReconciliationGate(JournalAccess(journal, direct, direct), "shell-test"),
    ) { creations++; provider }

    private fun strand(rating: Int = 3) = journal.record(
        JournalRequest("earlier-run", OperationToken("earlier-run", 1, 1), card.identity, rating, 4_200, 2, "five blocks", card.state),
    )

    private fun ready() { controller.onForegroundEvent(ForegroundEvent.RESUME); access.deliver() }

    @Test fun `with an empty journal the start is unchanged`() {
        ready()
        controller.start()
        access.deliver()
        assertEquals(PreviewStatus.CardReady, controller.state.status)
        assertTrue(controller.state.journalNotices.isEmpty())
    }

    @Test fun `a provably failed review is resolved and the start continues`() {
        strand()
        ready()
        controller.start()
        assertEquals(listOf(card.identity.cardId), provider.reads, "the journalled card is re-read first")
        assertEquals(PreviewStatus.Starting, controller.state.status, "no card is offered before reconciliation")
        access.deliver()
        assertEquals(PreviewStatus.CardReady, controller.state.status)
        assertTrue(controller.state.journalNotices.isEmpty(), "a provable non-write needs no warning")
        assertTrue(provider.collection.reviews.isEmpty(), "reconciliation writes nothing")
    }

    @Test fun `an unknown outcome stops the start and offers no card`() {
        strand()
        provider.collection.applyReview(card.identity.cardId, 3, 4_200)
        ready()
        controller.start()
        assertEquals(PreviewStatus.OutcomeUnknown, controller.state.status)
        assertTrue(access.callbacks.isEmpty(), "no access preflight and no card follows a blocking notice")
        val notice = controller.state.journalNotices.single()
        assertTrue(notice.contains("cannot prove it saved rating 3"))
        assertTrue(notice.contains("Open AnkiDroid"))
        assertFalse(notice.contains("Saved"))
        assertEquals(1, provider.collection.reviews.size, "no second review is added")
    }

    @Test fun `acknowledging the notice clears it without resolving the review`() {
        val entry = strand()
        provider.collection.applyReview(card.identity.cardId, 3, 4_200)
        ready()
        controller.start()
        controller.acknowledgeJournalNotice(entry.entryId)
        assertEquals(PreviewStatus.Idle, controller.state.status)
        assertTrue(controller.state.journalOutstanding.isEmpty())
        assertEquals(1, provider.collection.reviews.size, "acknowledging never writes")
        assertEquals(
            JournalResolution.OUTCOME_UNKNOWN,
            journal.entries().single().resolution,
            "the recorded resolution is never upgraded by an acknowledgement",
        )
    }

    @Test fun `reconciliation runs once per process not once per start`() {
        strand()
        ready()
        controller.start(); access.deliver()
        val reads = provider.reads.size
        controller.stop()
        controller.start(); access.deliver()
        assertEquals(reads, provider.reads.size, "a second start does not reconcile again")
        assertEquals(PreviewStatus.CardReady, controller.state.status)
    }
}
