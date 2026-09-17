package org.ankivoice.app

import java.util.concurrent.Executor
import org.ankivoice.core.commands.CommandContext
import org.ankivoice.core.commands.CommandRouter
import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.contracts.*
import org.ankivoice.core.fakes.*
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
                CommandSession(session, CommandRouter(session, speechInput), grader) { released += 1 },
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
        assertTrue(controller.state.notice?.contains("No review was submitted") == true, controller.state.notice)
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

    @Test
    fun `no control on the surface writes a review`() {
        started()
        controller.ask()
        controller.startAnswer()
        // Settle the attempt with a recognizer final, so the ratings go live and a
        // confirmation becomes possible. The surface never fabricates one.
        val token = checkNotNull(session.answerTurn?.token)
        session.acceptCapture(CaptureEvent.Transcript(token, "Five blocks.", confidence = Confidence.SUFFICIENT))
        session.grade()
        controller.run(VoiceCommand.RATE_GOOD)
        assertEquals("proposing", controller.state.sessionState)
        controller.run(VoiceCommand.CONFIRM)
        assertTrue(session.intent?.hasConfirmation() == true, "the confirmation was not recorded")
        assertEquals(ReviewState.PENDING, session.intent?.state, "the surface submitted the review")

        for (command in VoiceCommand.entries) controller.run(command)
        assertTrue(wroteNothing, "a command reached the writer")
    }
}
