package org.ankivoice.app

import java.io.File
import java.math.BigDecimal
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.ankivoice.core.answer.AnswerRecovery
import org.ankivoice.core.commands.CommandRouter
import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.contracts.*
import org.ankivoice.core.exchange.PrecommitExchange
import org.ankivoice.core.fakes.*
import org.ankivoice.core.journal.JournalPhase
import org.ankivoice.core.journal.JournalRequest
import org.ankivoice.core.journal.ReviewJournal
import org.ankivoice.core.session.ProposalOutcome
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionState
import org.ankivoice.provider.CredentialPolicy
import org.ankivoice.provider.CredentialStore
import org.ankivoice.provider.Diagnostics
import org.ankivoice.provider.LedgerStop
import org.ankivoice.provider.ProviderModule
import org.ankivoice.provider.ProviderSettings
import org.ankivoice.provider.QuotaLedger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * AV-045: the study session as `:app` composes it — #16's rules, #18's semantic grader over
 * #17's provider, and AV-018's reconciliation gate — against the AV-041 fakes.
 *
 * No test here can open a connection. Every provider fixture is refused before #17's
 * transport: there is no key, the disclosure is unacknowledged, the daily limit is zero or
 * the ledger holds a stop. A rule miss that reached the route would show up as a
 * reservation or a diagnostic, and the tests assert there is none where none is allowed.
 */
class StudyCompositionTest {
    @TempDir
    lateinit var directory: File

    private val key = "sk-or-v1-0123456789abcdef0123456789abcdef"
    private val now = 1_789_400_000_000L

    /**
     * AV-043's paid route is off here: a $0 cap keeps every rule miss on the free route,
     * which each fixture already refuses before the transport. Nothing in this class may
     * reach a paid dispatch, so the cap is not a knob any test turns up.
     */
    private class Settings(
        override var dailyLimit: Int = QuotaLedger.DEFAULT_DAILY_LIMIT,
        override var disclosureAcknowledged: Boolean = false,
        override var dailyCapUsd: BigDecimal = BigDecimal.ZERO,
    ) : ProviderSettings

    private class Credentials(private var key: String? = null) : CredentialStore {
        override fun read(): String? = key
        override fun save(key: String): Boolean = CredentialPolicy.valid(key).also { if (it) this.key = key }
        override fun clear() { key = null }
    }

    private val collection = demoCollection()
    private val cardProvider = FakeCardProvider(collection)
    private val transport = FakeReviewTransport(collection)
    private val capabilities = Capabilities(maxReviewTimeMs = collection.maxReviewTimeMs)
    private val speechInput = FakeSpeechInput()
    private val speechOutput = FakeSpeechOutput()
    private val direct = Executor { it.run() }

    private val settings = Settings()
    private val credentials = Credentials()
    private val diagnostics = Diagnostics(clock = { now })
    private val ledger by lazy { QuotaLedger(File(directory, "ledger.jsonl")) { now } }

    /** Grading work the test releases by hand, to put an edit between request and reply. */
    private val held = ArrayDeque<Runnable>()
    private var gradingWorker: Executor = direct

    /** Kept, so a test can build the journal a later process would read over the same file. */
    private val journalStore = FakeJournalStore()
    private val journal = ReviewJournal(journalStore)
    private val gate = ReconciliationGate(JournalAccess(journal, direct, direct), "study-test")

    private var created = 0
    private var session: ReviewSession? = null

    private val controller by lazy {
        CommandController(
            {
                created += 1
                val revision = AtomicInteger()
                val grading = ProviderModule.grading(credentials, ledger, settings, diagnostics)
                val grader = StudyGrader(grading, "study", revision, gradingWorker)
                // The same writer the composition root builds: AV-018's journal around
                // AV-024's guarded writer, with the settled transcript supplied for the
                // revision the rating was computed from and for no other.
                var opening: ReviewSession? = null
                val opened = ReviewSession(
                    provider = cardProvider,
                    speechOutput = speechOutput,
                    speechInput = speechInput,
                    grader = grader,
                    writer = studyWriter(
                        GuardedReviewWriter(cardProvider, transport, capabilities),
                        journal,
                        "study",
                    ) { opening },
                    capabilities = capabilities,
                    clock = FakeClock(),
                    sessionId = "study",
                )
                opening = opened
                session = opened
                Result.success(
                    CommandSession(
                        session = opened,
                        router = CommandRouter(opened, speechInput),
                        grader = grader,
                        exchange = PrecommitExchange(opened, speechOutput),
                        revision = revision,
                        gradingSource = grader::sourceOf,
                        speech = speechInput,
                        language = "en-US",
                        partial = { null },
                        release = {},
                    ),
                )
            },
            direct,
            direct,
            Executor { runnable -> gradingWorker.execute(runnable) },
            gate,
        ) { cardProvider }
    }

    private val wroteNothing: Boolean get() = transport.calls.isEmpty() && collection.reviews.isEmpty()
    private val reserved: Int get() = ledger.counts("study")["reservedTotal"] ?: 0

    /** Open a session and settle [spoken] as the answer to the first card. */
    private fun answered(spoken: String): ReviewSession {
        controller.onForegroundEvent(ForegroundEvent.RESUME)
        controller.start()
        controller.ask()
        // Start answer runs the capture itself, so the answer is scripted for the
        // transport rather than delivered to the session by hand.
        speechInput.script.addLast(FakeSpeechInput.Say(spoken))
        controller.startAnswer()
        val open = checkNotNull(session)
        assertEquals(SessionState.GRADING, open.state)
        return open
    }

    private fun gradingReady() {
        credentials.save(key)
        settings.disclosureAcknowledged = true
    }

    @Test
    fun `a rule match proposes a rating without a request or a reservation`() {
        gradingReady()
        // A zero limit refuses any reservation, so a request would leave a diagnostic behind.
        settings.dailyLimit = 0
        val open = answered("Five blocks.")
        val cardId = open.card?.identity?.cardId

        controller.grade()

        // AV-019 opens the exchange on the rule's rating, which is where #14's advisory
        // suggestion goes: propose() invalidates it, so the announcement is what carries
        // the label's proposal forward from here.
        assertEquals(3, controller.state.pendingRating)
        assertEquals("rule", controller.state.ratingSource)
        assertEquals(SessionState.PROPOSING, open.state)
        assertEquals(0, reserved, "a rule match reserves no quota")
        assertTrue(diagnostics.entries().isEmpty(), "a rule match reaches no provider: ${diagnostics.entries()}")
        assertEquals(cardId, open.card?.identity?.cardId)
        assertTrue(wroteNothing)
    }

    @Test
    fun `a rule miss with no key pauses for a self-grade and keeps the card`() {
        val open = answered("It is a mix of several things")
        val cardId = open.card?.identity?.cardId

        controller.grade()

        assertEquals(SessionState.PAUSED, open.state)
        assertTrue(open.lastFailure?.mode is GraderFailure, "${open.lastFailure}")
        assertNull(open.suggestion, "a failure is never a label")
        assertTrue(AnswerRecovery.SELF_GRADE in open.recoveryOptions)
        assertEquals(cardId, open.card?.identity?.cardId, "the card is kept")
        assertEquals(0, reserved)
        assertEquals(listOf("gradingUnavailable"), diagnostics.entries().map { it.name })
        assertTrue(diagnostics.entries().single().detail.contains("NO_KEY"))
        assertFalse(open.intent?.hasConfirmation() == true, "no rating was implied")
        assertTrue(wroteNothing)
    }

    @Test
    fun `an unacknowledged disclosure is treated like a missing key`() {
        credentials.save(key)
        val open = answered("It is a mix of several things")

        controller.grade()

        assertEquals(SessionState.PAUSED, open.state)
        assertNull(open.suggestion)
        assertEquals(0, reserved)
        assertTrue(diagnostics.entries().single().detail.contains("DISCLOSURE_REQUIRED"))
        assertTrue(wroteNothing)
    }

    /** A route refusal is recorded as a ledger stop for the day; every later request meets it. */
    @Test
    fun `a refused route pauses with the card kept and the self-grade still works`() {
        gradingReady()
        ledger.stop(LedgerStop.ROUTE_REFUSED)
        val open = answered("It is a mix of several things")
        val cardId = open.card?.identity?.cardId

        controller.grade()

        assertEquals(SessionState.PAUSED, open.state)
        assertEquals(GraderFailure.QUOTA_EXHAUSTED, open.lastFailure?.mode)
        assertNull(open.suggestion)
        assertEquals(cardId, open.card?.identity?.cardId)
        assertEquals(0, reserved)

        // The learner's own rating is the way on, and it still only proposes.
        assertTrue(open.selfGrade(3) is ProposalOutcome.Proposed)
        assertEquals(ReviewState.PENDING, open.intent?.state)
        assertTrue(wroteNothing)
    }

    @Test
    fun `grading runs on the grading worker and a superseded reply is ignored`() {
        gradingWorker = Executor { held.addLast(it) }
        val open = answered("Five blocks.")

        controller.grade()
        assertTrue(controller.state.grading)
        assertEquals(1, held.size, "grading waits on its own worker")
        assertNull(open.suggestion, "nothing is graded on the session thread")

        // The learner edits the transcript while the reply is still on its way.
        open.correctTranscript("Six blocks.")
        while (held.isNotEmpty()) held.removeFirst().run()

        assertNull(open.suggestion, "a reply for the earlier revision is dropped")
        assertEquals(SessionState.GRADING, open.state)
        assertTrue(open.events.any { it.step == "stale_grade" })
        assertFalse(controller.state.grading)
        assertTrue(wroteNothing)
    }

    @Test
    fun `a start is refused while an unknown outcome is unacknowledged`() {
        val card = collection.scheduled(collection.order.first())
        val entry = journal.record(
            JournalRequest("earlier-run", OperationToken("earlier-run", 1, 1), card.identity, 3, 4_200, 2, "five blocks", card.state),
        )
        collection.applyReview(card.identity.cardId, 3, 4_200)
        val reviews = collection.reviews.size
        controller.onForegroundEvent(ForegroundEvent.RESUME)

        controller.start()
        assertFalse(controller.state.running)
        assertEquals(0, created, "no session is built before the notice is acknowledged")
        assertEquals(listOf(entry.entryId), controller.state.journalOutstanding)
        assertTrue(controller.state.journalNotices.single().contains("cannot prove it saved rating 3"))

        controller.start()
        assertEquals(0, created, "a second start is still blocked")

        controller.acknowledgeJournalNotice(entry.entryId)
        assertTrue(controller.state.journalOutstanding.isEmpty())
        controller.start()
        assertTrue(controller.state.running)
        assertEquals(1, created)
        assertEquals(reviews, collection.reviews.size, "the gate never writes or resubmits")
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `reconciliation runs once per process across both surfaces`() {
        val card = collection.scheduled(collection.order.first())
        journal.record(
            JournalRequest("earlier-run", OperationToken("earlier-run", 1, 1), card.identity, 3, 4_200, 2, "five blocks", card.state),
        )
        controller.onForegroundEvent(ForegroundEvent.RESUME)
        controller.start()
        assertTrue(controller.state.running)
        val reads = cardProvider.reads.size

        var again: JournalReport? = null
        gate.open({ error("a reconciled gate does not re-read the journalled card") }) { again = it }
        assertFalse(checkNotNull(again).blocking)
        assertEquals(reads, cardProvider.reads.size)
    }

    /**
     * AV-019: the one path to the writer, end to end through the composition — and the
     * journal entry AV-018 requires around it, flushed before the write and settled from
     * what the writer returned.
     */
    @Test
    fun `a confirmed rating is journalled before the write and settled from the outcome`() {
        gradingReady()
        settings.dailyLimit = 0
        val open = answered("Five blocks.")
        controller.grade()
        assertEquals("rule", controller.state.ratingSource, "the rule route was not named")
        assertEquals(3, controller.state.pendingRating)
        assertTrue(journal.entries().isEmpty(), "a pending rating was journalled")

        controller.run(VoiceCommand.CONFIRM)

        assertEquals(1, transport.calls.size, "the single write was not single")
        assertEquals(ReviewState.CONFIRMED, open.intent?.state)
        val entry = journal.entries().single()
        assertEquals(JournalPhase.SETTLED, entry.phase)
        assertEquals(ReviewState.CONFIRMED, entry.outcomeState)
        assertEquals(3, entry.rating)
        assertEquals(open.transcriptRevision, entry.transcriptRevision)
        assertEquals("Five blocks.", entry.transcript, "the settled transcript was not journalled")
        assertEquals("study", entry.sessionId)
        assertTrue(journal.unsettled().isEmpty())
        assertEquals(0, reserved, "a rule match reserved quota on the way to the writer")
    }

    /** Nothing short of an accepted confirmation reaches the writer, and none is journalled. */
    @Test
    fun `no other composed path reaches the writer`() {
        gradingReady()
        settings.dailyLimit = 0
        val open = answered("Five blocks.")
        controller.grade()
        controller.run(VoiceCommand.RATE_GOOD)
        controller.run(VoiceCommand.CHANGE)
        controller.run(VoiceCommand.RATE_HARD)
        assertEquals(ReviewState.PENDING, open.intent?.state)

        for (command in VoiceCommand.entries - VoiceCommand.CONFIRM) controller.run(command)
        controller.grade()
        controller.stop()

        assertTrue(wroteNothing, "a composed path other than Confirm reached the writer")
        assertTrue(journal.entries().isEmpty(), "an unwritten rating was journalled")
    }

    /**
     * An unknown outcome from this card's own commit, end to end.
     *
     * The session halts, the surface offers only the learner's report, and the journal
     * entry settles `outcome-unknown` and stays outstanding — so the notice AV-018 shows at
     * the next process start is still owed. The report records what the learner saw and
     * closes the session; it resolves the entry no more than it resubmits the review.
     *
     * AV-045's gate reconciles once per **process**, so nothing here re-blocks this one;
     * within the session it is #14's halt, not the gate, that keeps the learner from
     * studying on.
     */
    @Test
    fun `an unknown outcome halts the session and leaves the notice owed to the next run`() {
        gradingReady()
        settings.dailyLimit = 0
        transport.anomalies.addLast(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        val open = answered("Five blocks.")
        controller.grade()

        controller.run(VoiceCommand.CONFIRM)

        assertEquals(SessionState.OUTCOME_UNKNOWN, open.state)
        assertEquals(ReviewState.OUTCOME_UNKNOWN.specName, controller.state.outcomeState)
        assertTrue(controller.state.reconcileRequired)
        assertFalse(controller.state.committed)
        val entry = journal.entries().single()
        assertEquals(ReviewState.OUTCOME_UNKNOWN, entry.outcomeState)
        assertTrue(entry.noticeOutstanding)

        controller.reportReconciled(saved = true)

        assertFalse(controller.state.running, "the halted session was left open")
        assertEquals("reconcile", open.events.last().step)
        assertEquals(1, transport.calls.size, "the report resubmitted the review")
        // Still owed: the settle recorded what the writer returned and resolved nothing.
        assertTrue(journal.entries().single().noticeOutstanding)
        assertEquals(
            listOf(entry.entryId),
            ReviewJournal(journalStore).outstandingNotices().map { it.entryId },
            "a later process would not be shown the notice",
        )
    }

    /**
     * The journal may not reach into the session for the text, so the composition root
     * hands it over — and only for the revision the rating was computed from.
     */
    @Test
    fun `the settled transcript is supplied only for the revision the rating came from`() {
        gradingReady()
        settings.dailyLimit = 0
        val open = answered("Five blocks.")
        val card = checkNotNull(open.card)
        val writer = studyWriter(FakeReviewWriter(cardProvider, transport, capabilities), journal, "study") { open }

        val current = ReviewIntent(card, 3, 1_000, OperationToken("study", 1, 9), open.transcriptRevision)
        current.confirm(
            RatingConfirmation(
                checkNotNull(current.token), card.identity, 3, open.transcriptRevision,
                ConfirmationSource.TOUCH,
            ),
        )
        writer.commit(current)
        assertEquals("Five blocks.", journal.entries().single().transcript)

        val stale = ReviewIntent(card, 3, 1_000, OperationToken("study", 1, 10), open.transcriptRevision - 1)
        writer.commit(stale)

        val journalled = journal.entries().last()
        assertEquals(open.transcriptRevision - 1, journalled.transcriptRevision)
        assertEquals("", journalled.transcript, "a superseded revision's text was journalled")
        // Unconfirmed, so the guarded writer refused it before any dispatch.
        assertEquals(ReviewState.FAILED, journalled.outcomeState)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `events and outcomes are reachable and no card content reaches diagnostics`() {
        val open = answered("It is a mix of several things")
        controller.grade()

        var evidence: SessionEvidence? = null
        controller.evidence { evidence = it }
        val recorded = checkNotNull(evidence)
        assertEquals("study", recorded.sessionId)
        assertEquals(open.events, recorded.events)
        assertEquals(open.outcomes, recorded.outcomes)
        assertTrue(recorded.events.any { it.detail.contains("mix of several things") }, "the evidence keeps the turn")

        val prompt = checkNotNull(open.card).fields.prompt
        diagnostics.entries().forEach { entry ->
            assertFalse(entry.toString().contains("mix of several things"), "$entry")
            assertFalse(entry.toString().contains(prompt), "$entry")
        }
        assertTrue(diagnostics.retainedContent().isEmpty())
    }
}
