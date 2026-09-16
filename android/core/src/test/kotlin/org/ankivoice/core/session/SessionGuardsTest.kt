package org.ankivoice.core.session

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.ankivoice.core.answer.AnswerPhase
import org.ankivoice.core.answer.AnswerRecovery
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.PlaybackResult
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.fakes.FakeGrader
import org.ankivoice.core.fakes.FakeSpeechInput
import org.ankivoice.core.fakes.FakeSpeechOutput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The AV-013 guarantees the ported binding does not state on its own: the token rules,
 * AV-022's invalidate-then-clean-up ordering, main-thread confinement, and the explicit
 * states this card adds.
 *
 * Every test here drives a callback that a defective session would accept.
 */
class SessionGuardsTest {

    // -- invalidate then clean up -------------------------------------------- //

    /** A synthesizer that answers its own cancellation, the way a real engine can. */
    private class ReentrantSpeechOutput : FakeSpeechOutput() {
        var session: ReviewSession? = null

        override fun cancel(token: OperationToken) {
            super.cancel(token)
            session?.finishPlayback(PlaybackResult.Completed(token))
        }
    }

    private class ReentrantSpeechInput(vararg steps: Step) : FakeSpeechInput(*steps) {
        var session: ReviewSession? = null

        override fun cancel(token: OperationToken) {
            super.cancel(token)
            session?.acceptCapture(CaptureEvent.Transcript(token, ANSWER, confidence = Confidence.SUFFICIENT))
        }
    }

    private class ReentrantGrader(vararg steps: Step) : FakeGrader(*steps) {
        var session: ReviewSession? = null

        override fun cancel(request: GradingRequest) {
            super.cancel(request)
            session?.acceptGrade(GradingReply(request, CORRECT))
        }
    }

    @Test
    fun `a playback completion delivered during teardown is dropped`() {
        val output = ReentrantSpeechOutput()
        val harness = build(speechOutput = output)
        val session = harness.session
        output.session = session
        check(session.offerCard() is SessionResult.Produced)

        // Interrupt while the synthesizer is speaking; its cancel answers immediately.
        output.onSpeak = { session.interrupt(Interruption.EXTERNAL_AUDIO) }
        session.ask()

        assertEquals(SessionState.INTERRUPTED, session.state)
        assertFalse(SessionState.LISTENING in session.visited, "the dropped completion never opened capture")
        assertNull(session.playbackToken)
        assertTrue(harness.wroteNothing)
    }

    @Test
    fun `a capture delivered during teardown is dropped`() {
        val input = ReentrantSpeechInput(FakeSpeechInput.Say(ANSWER))
        val harness = build(speechInput = input)
        val session = harness.session
        input.session = session
        check(session.offerCard() is SessionResult.Produced)
        check(session.ask() is SessionResult.Produced)
        session.startAnswer()

        session.interrupt(Interruption.LOCK)

        assertEquals(SessionState.INTERRUPTED, session.state)
        assertNull(session.answer, "the re-entrant transcript never became this turn's answer")
        assertFalse(SessionState.GRADING in session.visited)
        assertTrue(harness.grader.seen.isEmpty())
        assertTrue(harness.wroteNothing)
    }

    @Test
    fun `a grading reply delivered during teardown is dropped`() {
        val grader = ReentrantGrader(FakeGrader.Answer(CORRECT))
        val harness = build(grader = grader)
        val session = harness.session
        grader.session = session
        askListenGrade(harness)
        session.beginGrade()

        session.interrupt(Interruption.SYNC)

        assertEquals(SessionState.INTERRUPTED, session.state)
        assertNull(session.suggestion, "the re-entrant reply never became a suggestion")
        assertNull(session.gradingRequest)
        assertTrue(harness.wroteNothing)
    }

    @Test
    fun `teardown invalidates the pending rating before cancelling anything`() {
        val harness = build()
        val session = harness.session
        proposeSuggested(harness, askListenGrade(harness))
        val intent = checkNotNull(session.intent)
        check(session.confirm(confirmation(session)))

        session.interrupt(Interruption.APP_SWITCH)

        assertEquals(ReviewState.FAILED, intent.state)
        assertNull(intent.confirmation, "the confirmation cannot survive teardown")
        assertFalse(intent.hasConfirmation())
        assertTrue(harness.wroteNothing)
    }

    // -- tokens --------------------------------------------------------------- //

    @Test
    fun `a capture token never collides with a playback or grading token`() {
        val harness = build()
        val session = harness.session
        check(session.offerCard() is SessionResult.Produced)
        check(session.ask() is SessionResult.Produced)
        val capture = session.startAnswer()
        val playback = harness.speechOutput.attempted.size

        assertEquals(1, playback)
        assertNotEquals(session.sessionId, capture.sessionId, "capture tokens are namespaced apart")
        check(
            session.acceptCapture(
                CaptureEvent.Transcript(capture, ANSWER, confidence = Confidence.SUFFICIENT),
            ) is SessionResult.Produced,
        )
        val request = session.beginGrade()
        assertNotEquals(capture, request.operationToken)
        assertEquals(session.sessionId, request.operationToken.sessionId)
    }

    @Test
    fun `a callback from the previous card cannot advance the current one`() {
        val harness = build(
            transcripts = listOf(FakeSpeechInput.Say(ANSWER), FakeSpeechInput.Say("Green, blue, red.")),
        )
        val session = harness.session
        proposeSuggested(harness, askListenGrade(harness))
        val firstToken = checkNotNull(session.answerTurn).token
        confirmAndCommit(session)
        session.advance()

        check(session.offerCard() is SessionResult.Produced)
        check(session.ask() is SessionResult.Produced)
        session.startAnswer()

        val stale = session.acceptCapture(
            CaptureEvent.Transcript(checkNotNull(firstToken), "not this card", confidence = Confidence.SUFFICIENT),
        )
        assertTrue(stale is SessionResult.Ignored)
        assertEquals(SessionState.LISTENING, session.state)
        assertEquals(1, harness.transport.calls.size, "the stale callback produced no second write")
    }

    @Test
    fun `a callback from a superseded attempt cannot replace the current answer`() {
        val harness = build(
            transcripts = listOf(
                FakeSpeechInput.Fail(Failure(SpeechInputFailure.NO_SPEECH_DETECTED, "silence")),
                FakeSpeechInput.Say(ANSWER),
            ),
        )
        val session = harness.session
        check(session.offerCard() is SessionResult.Produced)
        check(session.ask() is SessionResult.Produced)
        session.listen()
        val firstAttempt = checkNotNull(session.answerTurn).token
        assertEquals(SessionState.PAUSED, session.state)

        session.retry()
        assertEquals(SessionState.RETRYING, session.state)
        session.listen()
        assertEquals(SessionState.GRADING, session.state)
        assertEquals(ANSWER, checkNotNull(session.answer).text)

        val late = session.acceptCapture(
            CaptureEvent.Transcript(checkNotNull(firstAttempt), "stale", confidence = Confidence.SUFFICIENT),
        )
        assertTrue(late is SessionResult.Ignored)
        assertEquals(ANSWER, checkNotNull(session.answer).text)
    }

    @Test
    fun `a duplicate final cannot produce a second rating for one answer`() {
        val harness = build()
        val session = harness.session
        askListenGrade(harness)
        val token = checkNotNull(checkNotNull(session.answerTurn).token)

        assertTrue(
            session.acceptCapture(
                CaptureEvent.Transcript(token, ANSWER, confidence = Confidence.SUFFICIENT),
            ) is SessionResult.Ignored,
        )
        proposeSuggested(harness, TurnOutcome.Graded(CORRECT))
        confirmAndCommit(session)

        assertThrows(IllegalStateException::class.java) { session.propose(3) }
        assertThrows(IllegalStateException::class.java) { session.correct(1) }
        assertFalse(session.confirm(confirmation(session)))
        assertEquals(1, harness.transport.calls.size)
        assertEquals(1, harness.collection.reviews.size)
    }

    // -- no overlap ------------------------------------------------------------ //

    @Test
    fun `capture cannot open while the question is playing`() {
        val harness = build()
        val session = harness.session
        check(session.offerCard() is SessionResult.Produced)

        assertThrows(IllegalStateException::class.java) { session.startAnswer() }
        assertThrows(IllegalStateException::class.java) { session.listen() }
        check(session.ask() is SessionResult.Produced)
        assertEquals(SessionState.LISTENING, session.state)
        assertEquals(AnswerPhase.THINKING, checkNotNull(session.answerTurn).phase)
    }

    @Test
    fun `playback cannot restart while the answer phase is open`() {
        val harness = build()
        val session = harness.session
        check(session.offerCard() is SessionResult.Produced)
        check(session.ask() is SessionResult.Produced)

        assertThrows(IllegalStateException::class.java) { session.ask() }
        assertThrows(IllegalStateException::class.java) { session.reveal() }
        assertEquals(1, harness.speechOutput.attempted.size)
    }

    // -- explicit states ------------------------------------------------------- //

    @Test
    fun `an unsupported card has its own state and never pauses`() {
        val harness = build()
        harness.provider.nextCardScript.addLast(
            Failure(CardProviderFailure.UNSUPPORTED_NOTE_TYPE, "VoiceQA required"),
        )
        val halt = checkNotNull(harness.session.offerCard().haltOrNull)

        assertEquals(SessionState.UNSUPPORTED, harness.session.state)
        assertEquals(BindingSessionState.STOPPED, harness.session.bindingState)
        assertFalse(halt.resumable)
        assertThrows(IllegalStateException::class.java) { harness.session.resume() }
    }

    @Test
    fun `an explicit cancel keeps the card and offers manual controls`() {
        val harness = build()
        val session = harness.session
        check(session.offerCard() is SessionResult.Produced)
        check(session.ask() is SessionResult.Produced)
        val token = session.startAnswer()

        val halt = session.cancelAnswer()

        assertEquals(SessionState.PAUSED, session.state)
        assertEquals("answer_cancelled", halt.reason)
        assertTrue(token in harness.speechInput.cancelled)
        assertNotEquals(null, session.card, "the card is preserved")
        assertEquals(
            listOf(AnswerRecovery.TRY_AGAIN, AnswerRecovery.TYPED_CORRECTION),
            session.recoveryOptions,
        )
        assertTrue(harness.wroteNothing)
    }

    @Test
    fun `retry opens a new revision and never re-arms the microphone on its own`() {
        val harness = build(
            transcripts = listOf(
                FakeSpeechInput.Fail(Failure(SpeechInputFailure.LISTEN_TIMEOUT, "no final")),
                FakeSpeechInput.Say(ANSWER),
            ),
        )
        val session = harness.session
        check(session.offerCard() is SessionResult.Produced)
        check(session.ask() is SessionResult.Produced)
        session.listen()
        val revisionBefore = session.transcriptRevision
        val card = checkNotNull(session.card)

        session.retry()

        assertEquals(SessionState.RETRYING, session.state)
        assertEquals(BindingSessionState.LISTENING, session.bindingState)
        assertEquals(card, session.card, "retry preserves the current card")
        assertTrue(session.transcriptRevision > revisionBefore)
        assertNull(session.answer)
        assertEquals(AnswerPhase.THINKING, checkNotNull(session.answerTurn).phase)
        assertNull(session.captureToken, "no window opens until the next explicit Start answer")

        // Only the next explicit Start answer opens one, and it is recorded as a retry.
        session.listen()
        assertEquals(SessionState.GRADING, session.state)
        assertEquals(1, checkNotNull(session.answerTurn).retries)
        assertEquals(ANSWER, checkNotNull(session.answer).text)
    }

    @Test
    fun `reveal is an explicit state that returns to where it was requested from`() {
        val harness = build()
        val session = harness.session
        askListenGrade(harness)
        proposeSuggested(harness, TurnOutcome.Graded(CORRECT))

        check(session.reveal() is SessionResult.Produced)

        assertTrue(SessionState.REVEALING in session.visited)
        assertEquals(SessionState.PROPOSING, session.state)
        assertNull(session.playbackToken)
        assertNull(SessionState.REVEALING.binding, "the binding has no separate reveal state")
    }

    @Test
    fun `outcome-unknown pauses and never lets the write be retried`() {
        val harness = build()
        harness.transport.anomalies.addLast(org.ankivoice.core.fakes.WriteAnomaly.NULL_RESPONSE)
        val session = harness.session
        proposeSuggested(harness, askListenGrade(harness))
        val outcome = confirmAndCommit(session)

        assertEquals(ReviewState.OUTCOME_UNKNOWN, outcome.state)
        assertEquals(SessionState.OUTCOME_UNKNOWN, session.state)
        assertTrue(checkNotNull(session.halt).reconciliationRequired)
        assertThrows(IllegalStateException::class.java) { session.commit() }
        assertThrows(IllegalStateException::class.java) { session.resume() }
        assertThrows(IllegalStateException::class.java) { session.retry() }
        assertEquals(1, harness.transport.calls.size)

        // A later halt cannot erase the obligation to reconcile.
        val stop = session.interrupt(Interruption.PROCESS_RESUME)
        assertTrue(stop.reconciliationRequired)
    }

    @Test
    fun `a write fault is resumed, not retried in place`() {
        val harness = build()
        val session = harness.session
        proposeSuggested(harness, askListenGrade(harness))
        harness.provider.readCardScript.addLast(
            Failure(CardProviderFailure.PACKAGE_UNAVAILABLE, "AnkiDroid disabled"),
        )
        confirmAndCommit(session)

        assertEquals(SessionState.PAUSED, session.state)
        // The card snapshot may be stale, so the route out is a fresh query, not a retry.
        assertTrue(session.recoveryOptions.isEmpty())
        assertThrows(IllegalStateException::class.java) { session.retry() }
        assertThrows(IllegalArgumentException::class.java) { session.correctTranscript(ANSWER) }
        assertTrue(harness.wroteNothing)

        session.resume()
        assertEquals(SessionState.IDLE, session.state)
        assertNull(session.card)
    }

    // -- confinement ------------------------------------------------------------ //

    @Test
    fun `a transition from another thread fails loudly`() {
        val harness = build()
        val session = harness.session
        check(session.offerCard() is SessionResult.Produced)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val thrown = worker.submit<Throwable?> {
                runCatching { session.ask() }.exceptionOrNull()
            }.get(10, TimeUnit.SECONDS)
            assertTrue(thrown is IllegalStateException, "expected a confinement failure, got $thrown")
            assertTrue(checkNotNull(thrown).message.orEmpty().contains("owning thread"))
        } finally {
            worker.shutdownNow()
        }
        assertEquals(SessionState.ASKING, session.state, "the off-thread call changed nothing")
        assertTrue(harness.speechOutput.attempted.isEmpty())
    }

    @Test
    fun `a callback posted to the owning thread is accepted`() {
        val harness = build()
        val session = harness.session
        check(session.offerCard() is SessionResult.Produced)
        check(session.ask() is SessionResult.Produced)
        val token = session.startAnswer()

        // A real recognizer callback arrives on its own thread and must be posted back.
        val worker = Executors.newSingleThreadExecutor()
        val event = try {
            worker.submit<CaptureEvent> {
                CaptureEvent.Transcript(token, ANSWER, confidence = Confidence.SUFFICIENT)
            }.get(10, TimeUnit.SECONDS)
        } finally {
            worker.shutdownNow()
        }

        assertTrue(session.acceptCapture(event) is SessionResult.Produced)
        assertEquals(SessionState.GRADING, session.state)
    }
}
