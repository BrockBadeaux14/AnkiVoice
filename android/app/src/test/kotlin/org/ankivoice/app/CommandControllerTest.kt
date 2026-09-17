package org.ankivoice.app

import java.util.concurrent.Executor
import org.ankivoice.core.commands.CommandContext
import org.ankivoice.core.commands.CommandRouter
import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.contracts.*
import org.ankivoice.core.exchange.PrecommitExchange
import org.ankivoice.core.fakes.*
import org.ankivoice.core.grading.GradingSource
import org.ankivoice.core.session.ReviewSession
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * AV-014's debug surface, over the AV-041 fakes.
 *
 * The property this file exists for is the one the pinned-AVD run then demonstrates
 * against a real collection: **no control on the command surface writes a review.**
 */
class CommandControllerTest {
    private val collection = demoCollection()
    private val provider = FakeCardProvider(collection)
    private val transport = FakeReviewTransport(collection)
    private val capabilities = Capabilities(maxReviewTimeMs = collection.maxReviewTimeMs)
    private val speechOutput = FakeSpeechOutput()
    private val speechInput = FakeSpeechInput(FakeSpeechInput.Say("Five blocks."))
    private val grader = FakeGrader(FakeGrader.Answer(GradingResult(GradeLabel.CORRECT, "matched")))
    private var released = 0

    /** Synchronous on both sides, so the session is confined to the test thread. */
    private val direct = Executor { it.run() }

    private var failure: Failure? = null

    private val session by lazy {
        ReviewSession(
            provider = provider,
            speechOutput = speechOutput,
            speechInput = speechInput,
            grader = grader,
            writer = GuardedReviewWriter(provider, transport, capabilities),
            capabilities = capabilities,
            clock = FakeClock(),
            sessionId = "commands",
        )
    }

    private val controller = CommandController(
        {
            failure?.let { return@CommandController Result.failure(CommandSessionUnavailable(it)) }
            Result.success(
                CommandSession(
                    session = session,
                    router = CommandRouter(session, speechInput),
                    grader = grader,
                    exchange = PrecommitExchange(session, speechOutput),
                    gradingSource = { GradingSource.RULE },
                ) { released += 1 },
            )
        },
        direct,
        direct,
        direct,
    )

    private val wroteNothing: Boolean get() = transport.calls.isEmpty() && collection.reviews.isEmpty()

    private fun started(): CommandState {
        controller.onForegroundEvent(ForegroundEvent.RESUME)
        controller.start()
        return controller.state
    }

    @Test
    fun `starting offers a card and reports the command context`() {
        val state = started()
        assertTrue(state.running)
        assertFalse(state.busy)
        assertEquals("asking", state.sessionState)
        assertEquals(1_789_414_083_106L, state.cardId)
        assertTrue(state.notice?.contains("ready") == true, state.notice)
        assertTrue(wroteNothing)
    }

    @Test
    fun `an unavailable deck is reported by name rather than crashing`() {
        failure = Failure(CardProviderFailure.DECK_MISSING, "no deck is selected")
        val state = started()
        assertFalse(state.running)
        assertEquals(CardProviderFailure.DECK_MISSING, state.failure?.mode)
        assertTrue(state.notice?.contains("deckMissing") == true, state.notice)
        assertTrue(wroteNothing)
    }

    /** AV-007: RESUME never authorizes study on its own, and nothing opens behind it. */
    @Test
    fun `no session opens while the app is not in the foreground`() {
        controller.start()
        assertFalse(controller.state.running)
        assertTrue(controller.state.notice?.contains("foreground") == true, controller.state.notice)
        assertEquals(0, released)
        assertTrue(wroteNothing)
    }

    @Test
    fun `every command has a control, and the surface offers only the runnable ones`() {
        started()
        controller.ask()
        val state = controller.state
        assertEquals(CommandContext.COMMAND, state.context)
        // Waiting for a Start answer: repeat, pause, skip and finish are the live ones.
        assertEquals(
            listOf(VoiceCommand.REPEAT, VoiceCommand.PAUSE, VoiceCommand.FINISH_SESSION, VoiceCommand.SKIP),
            state.available,
        )
        assertFalse(VoiceCommand.RESUME in state.spokenAvailable, "resume is a touch control")
        assertTrue(wroteNothing)
    }

    @Test
    fun `the answer window is visible and offers no spoken command`() {
        started()
        controller.ask()
        controller.startAnswer()

        val state = controller.state
        assertTrue(state.capturing)
        assertEquals(CommandContext.ANSWER, state.context)
        assertEquals("capturing", state.answerPhase)
        assertEquals(emptyList<VoiceCommand>(), state.spokenAvailable)
        // Touch is still the fallback: pause and skip remain reachable by button.
        assertTrue(VoiceCommand.PAUSE in state.available)
        assertTrue(VoiceCommand.SKIP in state.available)
        assertTrue(wroteNothing)
    }

    @Test
    fun `a command publishes its notice and the turn it produced`() {
        started()
        controller.ask()
        controller.run(VoiceCommand.PAUSE)

        val state = controller.state
        assertEquals("paused", state.sessionState)
        assertTrue(state.notice?.contains("Nothing was written") == true, state.notice)
        assertTrue(VoiceCommand.RESUME in state.available)
        assertTrue(wroteNothing)
    }

    /** A control the surface should not have offered reports itself instead of crashing. */
    @Test
    fun `a command run from the wrong state is refused in the surface's own words`() {
        started()
        controller.run(VoiceCommand.RESUME)
        assertTrue(controller.state.notice?.contains("not available") == true, controller.state.notice)
        assertEquals("asking", controller.state.sessionState)
        assertTrue(wroteNothing)
    }

    @Test
    fun `closing the session releases the transport and writes nothing`() {
        started()
        controller.ask()
        controller.stop()

        assertFalse(controller.state.running)
        assertEquals(1, released)
        assertEquals("stopped", session.state.specName)
        assertTrue(controller.state.notice?.contains("Closing wrote nothing") == true, controller.state.notice)
        assertTrue(wroteNothing)
    }

    /** AV-007: leaving the foreground breaks the single-active-reviewer precondition. */
    @Test
    fun `leaving the foreground interrupts the session and releases the microphone`() {
        started()
        controller.ask()
        controller.onForegroundEvent(ForegroundEvent.PAUSE)

        assertFalse(controller.state.running)
        assertEquals(1, released)
        assertEquals("interrupted", session.state.specName)
        assertTrue(wroteNothing)
    }

    /**
     * AV-019: Confirm is the one control that writes, and it is reached only through an
     * explicit learner confirmation for the pending rating, attempt and revision.
     */
    @Test
    fun `no control except Confirm writes a review`() {
        announced()
        controller.run(VoiceCommand.RATE_GOOD)
        assertEquals("proposing", controller.state.sessionState)

        // Every other control in the vocabulary, with a pending rating on the table.
        for (command in VoiceCommand.entries - VoiceCommand.CONFIRM) controller.run(command)
        assertTrue(wroteNothing, "a command other than Confirm reached the writer")
    }

    @Test
    fun `grading announces the pending rating with its source and its answer version`() {
        announced()

        val state = controller.state
        assertEquals(3, state.pendingRating)
        assertEquals("rule", state.ratingSource)
        assertEquals(1, state.announcedRevision)
        assertTrue(state.announcement?.contains("Good is waiting") == true, state.announcement)
        assertTrue(state.announcement?.contains("exact rule match") == true, state.announcement)
        assertFalse(state.confirmed)
        assertNull(state.outcomeState)
        assertTrue(wroteNothing, "an announcement reached the writer")
    }

    @Test
    fun `a confirmed rating is written once and the surface offers Next card and the Undo handoff`() {
        announced()

        controller.run(VoiceCommand.CONFIRM)

        val state = controller.state
        assertEquals(ReviewState.CONFIRMED.specName, state.outcomeState)
        assertTrue(state.committed)
        assertFalse(state.reconcileRequired)
        assertNull(state.announcement, "a written rating is still shown as waiting")
        assertEquals(1, transport.calls.size)
        assertEquals(1, collection.reviews.size)
        assertTrue(state.notice?.contains("Saved rating 3.") == true, state.notice)
        assertTrue(state.notice?.contains("AnkiDroid's own Undo") == true, state.notice)

        // A duplicate confirm is not even offered, and cannot write a second review.
        assertFalse(VoiceCommand.CONFIRM in state.available)
        controller.run(VoiceCommand.CONFIRM)
        assertEquals(1, transport.calls.size)
        assertEquals(1, collection.reviews.size)
    }

    @Test
    fun `Next card advances only after a confirmed review and clears the outcome`() {
        announced()
        controller.run(VoiceCommand.CONFIRM)

        controller.nextCard()

        val state = controller.state
        assertEquals("asking", state.sessionState)
        assertNull(state.outcomeState, "the previous card's verdict outlived its turn")
        assertNull(state.announcement)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `the Undo handoff stops the session, releases the transport and writes nothing more`() {
        announced()
        controller.run(VoiceCommand.CONFIRM)

        controller.handOffToUndo()

        assertFalse(controller.state.running, "the stopped session was left open")
        assertEquals(1, released)
        assertEquals("stopped", session.state.specName)
        assertTrue(controller.state.notice?.contains("Undo") == true, controller.state.notice)
        assertTrue(controller.state.notice?.contains("cannot take a review back") == true, controller.state.notice)
        assertEquals(1, transport.calls.size)
        assertEquals(1, collection.reviews.size)
    }

    @Test
    fun `a write that provably did not land says so and keeps the card`() {
        transport.anomalies.addLast(WriteAnomaly.REJECT_WITH_ZERO)
        announced()

        controller.run(VoiceCommand.CONFIRM)

        val state = controller.state
        assertEquals(ReviewState.FAILED.specName, state.outcomeState)
        assertFalse(state.committed)
        assertFalse(state.reconcileRequired)
        assertTrue(state.notice?.startsWith("Nothing was saved.") == true, state.notice)
        assertEquals(0, collection.reviews.size)
        assertNotNull(session.card, "the card was dropped after a failed write")
    }

    @Test
    fun `an unconfirmable write offers only the learner-reported reconcile`() {
        transport.anomalies.addLast(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        announced()

        controller.run(VoiceCommand.CONFIRM)

        val state = controller.state
        assertEquals(ReviewState.OUTCOME_UNKNOWN.specName, state.outcomeState)
        assertTrue(state.reconcileRequired)
        assertFalse(state.committed)
        assertTrue(state.notice?.contains("could not confirm") == true, state.notice)
        // No command may leave this halt, so nothing can go around the report.
        assertEquals(CommandContext.UNAVAILABLE, state.context)
        assertEquals(emptyList<VoiceCommand>(), state.spokenAvailable)

        controller.reportReconciled(saved = false)

        assertFalse(controller.state.running)
        assertEquals(1, released)
        assertTrue(controller.state.notice?.contains("not in AnkiDroid") == true, controller.state.notice)
        assertEquals(1, transport.calls.size, "the report retried the write")
    }

    /** The abstain path: no suggestion, so the learner names a rating and still confirms it. */
    @Test
    fun `an abstention offers a self-grade and still requires a separate confirmation`() {
        grader.script.clear()
        grader.script.addLast(FakeGrader.Answer(GradingResult(GradeLabel.UNCERTAIN, "no concept matched")))
        settled()
        controller.grade()

        assertNull(controller.state.pendingRating)
        assertEquals("none", controller.state.ratingSource)
        assertTrue(controller.state.announcement?.startsWith("No rating was suggested") == true)
        assertEquals(listOf(1, 2, 3, 4), controller.state.selfGradable)

        controller.selfGrade(2)

        assertEquals(2, controller.state.pendingRating)
        assertEquals("learner", controller.state.ratingSource)
        assertEquals(ReviewState.PENDING, session.intent?.state)
        assertTrue(wroteNothing, "naming a rating wrote one")

        controller.run(VoiceCommand.CONFIRM)
        assertEquals(1, transport.calls.size)
        assertEquals(2, collection.reviews.single().rating)
    }

    /** Settle one attempt, so the ratings go live. The surface never fabricates an answer. */
    private fun settled() {
        started()
        controller.ask()
        controller.startAnswer()
        val token = checkNotNull(session.answerTurn?.token)
        session.acceptCapture(CaptureEvent.Transcript(token, "Five blocks.", confidence = Confidence.SUFFICIENT))
    }

    /** Settle an attempt and grade it, which opens AV-019's Announced position. */
    private fun announced() {
        settled()
        controller.grade()
        assertEquals("proposing", controller.state.sessionState)
    }
}
