package org.ankivoice.app

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.ankivoice.core.commands.CommandRouter
import org.ankivoice.core.contracts.Capabilities
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.ForegroundEvent
import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.GuardedReviewWriter
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.exchange.PrecommitExchange
import org.ankivoice.core.fakes.FakeCardProvider
import org.ankivoice.core.fakes.FakeClock
import org.ankivoice.core.fakes.FakeGrader
import org.ankivoice.core.fakes.FakeJournalStore
import org.ankivoice.core.fakes.FakeReviewTransport
import org.ankivoice.core.fakes.FakeSpeechInput
import org.ankivoice.core.fakes.FakeSpeechOutput
import org.ankivoice.core.fakes.demoCollection
import org.ankivoice.core.grading.GradingSource
import org.ankivoice.core.journal.ReviewJournal
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionState
import org.ankivoice.provider.GradingRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/** The recognizer as one test sees it: what it was asked, what stopped it, and a hook mid-capture. */
internal open class ObservedSpeechInput(vararg steps: Step) : FakeSpeechInput(*steps) {
    var listened: OperationToken? = null

    /** Stops that arrived before the capture returned; a dropped Done leaves it empty. */
    var stoppedWhileOpen: List<OperationToken> = emptyList()

    /** What the operator does mid-capture. The transport blocks there, so tests act there. */
    var whileListening: () -> Unit = {}

    /** The next final comes back below `sufficient`, as a recognizer that was unsure would send it. */
    var lowConfidenceNext = false

    override fun listen(token: OperationToken, language: String): CaptureEvent {
        listened = token
        whileListening()
        stoppedWhileOpen = stopped.toList()
        val event = super.listen(token, language)
        if (lowConfidenceNext && event is CaptureEvent.Transcript) {
            lowConfidenceNext = false
            return event.copy(confidence = Confidence.LOW)
        }
        return event
    }
}

/**
 * The study controller over the AV-041 fakes, with the same writer the composition root
 * builds — AV-018's journal around AV-024's guarded writer — so the wiring under test is
 * the wiring that ships. Synchronous on every executor, so the session is confined to the
 * test thread and every snapshot is published before the call returns.
 *
 * Every factory call opens a **fresh** session, as the app does, so a reload after an
 * interruption is a second session and not a restarted one.
 */
internal class StudyHarness(
    grades: List<FakeGrader.Step> = listOf(FakeGrader.Answer(GradingResult(GradeLabel.CORRECT, "matched"))),
    transcripts: List<FakeSpeechInput.Step> = listOf(FakeSpeechInput.Say("Five blocks.")),
    val gate: ReconciliationGate? = null,
    /** Grading work the test releases by hand, to put an edit or an interruption between request and reply. */
    val holdGrading: Boolean = false,
) {
    val collection = demoCollection()
    val provider = FakeCardProvider(collection)
    val cards = StudiableCardProvider(provider, "en-US")
    val transport = FakeReviewTransport(collection)
    val capabilities = Capabilities(maxReviewTimeMs = collection.maxReviewTimeMs)
    val speechOutput = FakeSpeechOutput()
    val speechInput = ObservedSpeechInput(*transcripts.toTypedArray())
    val grader = FakeGrader(*grades.toTypedArray())
    val journalStore = FakeJournalStore()
    val journal = ReviewJournal(journalStore)
    val held = ArrayDeque<Runnable>()

    /** What the recognizer has heard so far, as the surface polls it mid-capture. */
    var partialText: String? = null

    /** Every snapshot the surface published while the microphone was open. */
    val duringCapture = mutableListOf<StudyState>()

    var released = 0
    var created = 0
    var failure: Failure? = null
    var gradingSource: GradingSource? = GradingSource.RULE
    var gradingRoute: GradingRoute? = null

    /** The session the last factory call opened. */
    var session: ReviewSession? = null

    private val direct = Executor { it.run() }

    val controller: StudyController = StudyController(
        {
            failure?.let { return@StudyController Result.failure(StudySessionUnavailable(it)) }
            created += 1
            var opening: ReviewSession? = null
            val opened = ReviewSession(
                provider = cards,
                speechOutput = speechOutput,
                speechInput = speechInput,
                grader = grader,
                writer = studyWriter(GuardedReviewWriter(cards, transport, capabilities), journal, "study") { opening },
                capabilities = capabilities,
                clock = FakeClock(),
                sessionId = "study",
            )
            opening = opened
            session = opened
            Result.success(
                StudySession(
                    session = opened,
                    grader = grader,
                    exchange = PrecommitExchange(opened, speechOutput),
                    revision = AtomicInteger(),
                    gradingSource = { gradingSource },
                    gradingRoute = { gradingRoute },
                    speech = speechInput,
                    language = "en-US",
                    partial = { partialText },
                    skips = cards::report,
                    release = { released += 1 },
                ),
            )
        },
        direct,
        direct,
        if (holdGrading) Executor { held.addLast(it) } else direct,
        gate,
        { cards },
    ) { sessionId -> journal.entries().filter { it.sessionId == sessionId } }

    init {
        speechInput.whileListening = { duringCapture += controller.state }
    }

    val state: StudyState get() = controller.state

    val wroteNothing: Boolean get() = transport.calls.isEmpty() && collection.reviews.isEmpty()

    /** The open session, which every driven scenario has. */
    val open: ReviewSession get() = checkNotNull(session) { "no session was opened" }

    /** Bring the app to the foreground and open a session. */
    fun started(): StudyState {
        controller.onForegroundEvent(ForegroundEvent.RESUME)
        controller.start()
        return controller.state
    }

    /** Open a session, play the prompt and settle one attempt on the scripted transcript. */
    fun settled(): StudyState {
        started()
        controller.ask()
        // Start answer settles the attempt on the fake's recognizer final and grades it at
        // once, so the ratings go live and a confirmation becomes possible. The surface
        // never fabricates an answer.
        controller.startAnswer()
        return controller.state
    }

    /** Settle an attempt whose grade opened AV-019's Announced position with a pending rating. */
    fun announced(): StudyState {
        settled()
        assertEquals(SessionState.PROPOSING.specName, controller.state.sessionState, controller.state.notice)
        return controller.state
    }

    /** Run every grading task the held worker is holding, and anything it queues meanwhile. */
    fun releaseGrading() {
        while (held.isNotEmpty()) held.removeFirst().run()
    }

    /** Export the evidence, synchronously on these executors. */
    fun evidence(): StudyEvidence {
        var exported: StudyEvidence? = null
        controller.evidence { exported = it }
        return checkNotNull(exported) { "no evidence was exported" }
    }

    /** A card the fixture shapes would refuse: a Basic note, delivered straight from the queue. */
    fun rejectedCard(id: Long) = collection.scheduled(collection.order.first()).let { card ->
        card.copy(identity = card.identity.copy(cardId = id, noteId = id, model = "Basic"))
    }
}

/** No halted state may offer a control that writes. */
internal fun assertNoWriteControl(state: StudyState) {
    assertFalse(StudyControl.CONFIRM in state.controls, "a halted screen offered Confirm: ${state.controls}")
    assertTrue(state.pendingRating == null || state.sessionState == SessionState.PROPOSING.specName, "a halt kept a pending rating on screen")
}

/** The router the controller built, for tests that inspect what a command would do. */
internal fun routerFor(session: ReviewSession, speech: FakeSpeechInput) = CommandRouter(session, speech)
