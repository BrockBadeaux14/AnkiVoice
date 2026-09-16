package org.ankivoice.core.answer

import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.ConfirmationSource
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.GradingContext
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.RatingConfirmation
import org.ankivoice.core.contracts.ReviewIntent
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.TranscriptKind
import org.ankivoice.core.fakes.FakeClock
import org.ankivoice.core.fakes.FakeSpeechInput
import org.ankivoice.core.fakes.demoCollection
import org.ankivoice.core.grading.GradingSource
import org.ankivoice.core.grading.TurnGrading
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * AV-012's answer/transcript policy, driven entirely by the `:core` fakes.
 *
 * No emulator, no recognizer and no live audio. Every failure keeps the card and waits
 * for an explicit learner action; nothing here submits a review or infers a rating.
 */
class AnswerBoundariesTest {
    private val clock = FakeClock(1_000)
    private val speech = FakeSpeechInput()
    private val card = demoCollection().scheduled(1_789_414_083_106)

    private fun turn(limits: AnswerLimits = AnswerLimits.SELECTED) =
        AnswerTurn(speech, clock, "s1", turn = 1, language = "en-US", limits = limits)

    private fun final(token: OperationToken, text: String, confidence: Confidence = Confidence.SUFFICIENT) =
        CaptureEvent.Transcript(token, text, TranscriptKind.FINAL, confidence)

    private fun partial(token: OperationToken, text: String) =
        CaptureEvent.Transcript(token, text, TranscriptKind.PARTIAL, Confidence.ABSENT)

    private fun failed(token: OperationToken, mode: SpeechInputFailure, detail: String = "") =
        CaptureEvent.Failed(token, Failure(mode, detail))

    /** A turn taken to a settled recognizer final, for tests that start after recognition. */
    private fun spoken(text: String, confidence: Confidence = Confidence.SUFFICIENT): Pair<AnswerTurn, Answer> {
        val turn = turn()
        val token = turn.startAnswer()
        clock.advance(2_000)
        turn.done()
        clock.advance(700)
        return turn to checkNotNull(turn.deliver(final(token, text, confidence)))
    }

    // The selected limits

    @Test
    fun `the pinned AV-042 limits, and three distinct clocks`() {
        val limits = AnswerLimits.SELECTED
        assertEquals(15_000, limits.windowMs)
        assertEquals(5_000, limits.finalizationMs)
        assertEquals(20_000, limits.attemptMs)
        assertEquals(0, AnswerLimits.AUTOMATIC_REARMS)

        val turn = turn()
        val token = turn.startAnswer()
        clock.advance(4_000)
        assertEquals(11_000, turn.remainingWindowMs())
        assertNull(turn.remainingFinalizationMs())
        assertEquals(4_000, turn.attemptElapsedMs())

        turn.done()
        clock.advance(1_000)
        // The window stops; the finalization deadline runs on its own; the attempt keeps going.
        assertNull(turn.remainingWindowMs())
        assertEquals(4_000, turn.remainingFinalizationMs())
        assertEquals(5_000, turn.attemptElapsedMs())
        assertEquals(CaptureStop.DONE, checkNotNull(turn.deliver(final(token, "Five."))).stoppedBy)
    }

    @Test
    fun `a nonpositive window or finalization deadline is refused`() {
        assertThrows<IllegalArgumentException> { AnswerLimits(windowMs = 0) }
        assertThrows<IllegalArgumentException> { AnswerLimits(finalizationMs = -1) }
    }

    // Thinking time is outside active capture

    @Test
    fun `thinking is unbounded and consumes no part of the window`() {
        val turn = turn()
        assertTrue(turn.thinking)
        assertNull(turn.remainingWindowMs())
        // Far longer than the whole 15-second window, and longer than a whole attempt.
        clock.advance(120_000)
        assertTrue(turn.thinking)
        assertEquals(0, turn.attempt)

        val token = turn.startAnswer()
        assertEquals(15_000, turn.remainingWindowMs())
        clock.advance(14_000)
        turn.done()
        clock.advance(690)
        val answer = checkNotNull(turn.deliver(final(token, "green blue red")))
        assertEquals(AnswerStatus.FINAL, answer.status)
        assertEquals("green blue red", answer.text)
        assertTrue(answer.gradable)
    }

    @Test
    fun `no window opens without an explicit Start answer`() {
        val turn = turn()
        clock.advance(60_000)
        assertNull(turn.poll())
        assertEquals(AnswerPhase.THINKING, turn.phase)
        assertEquals(emptyList<String>(), speech.languages)
    }

    // Window expiry

    @Test
    fun `window expiry stops the microphone, preserves the card and manufactures nothing`() {
        val turn = turn()
        val token = turn.startAnswer()
        clock.advance(15_000)
        // Expiry alone is not an answer and never finalizes the learner's recall.
        assertNull(turn.poll())
        assertEquals(AnswerPhase.FINALIZING, turn.phase)
        assertNull(turn.answer)

        clock.advance(900)
        val answer = checkNotNull(turn.deliver(final(token, "green blue")))
        assertEquals(AnswerStatus.FINAL, answer.status)
        assertEquals(CaptureStop.WINDOW_EXPIRY, answer.stoppedBy)
        assertEquals("green blue", answer.text)
    }

    @Test
    fun `a Done after the window ran out is recorded as the expiry`() {
        val turn = turn()
        turn.startAnswer()
        clock.advance(15_400)
        turn.done()
        clock.advance(400)
        // The 400 ms the window overran is already gone when finalization starts.
        assertEquals(4_200, turn.remainingFinalizationMs())
        clock.advance(4_200)
        assertEquals(CaptureStop.WINDOW_EXPIRY, checkNotNull(turn.poll()).stoppedBy)
    }

    // Finalization

    @Test
    fun `the finalization deadline expires as a timeout, not as an answer`() {
        val turn = turn()
        val token = turn.startAnswer()
        clock.advance(3_000)
        turn.done()
        clock.advance(5_000)
        val timedOut = checkNotNull(turn.poll())
        assertEquals(AnswerStatus.TIMED_OUT, timedOut.status)
        assertEquals("", timedOut.text)
        assertFalse(timedOut.gradable)
        assertEquals(SpeechInputFailure.LISTEN_TIMEOUT, checkNotNull(timedOut.failure).mode)
        assertEquals(AnswerRecovery.entries, timedOut.recovery)
        // A final that arrives after the deadline is too late to become an answer.
        assertNull(turn.deliver(final(token, "five")))
        assertSame(timedOut, turn.answer)
    }

    @Test
    fun `a final inside the finalization deadline is accepted`() {
        val turn = turn()
        val token = turn.startAnswer()
        clock.advance(3_000)
        turn.done()
        clock.advance(4_999)
        assertEquals(AnswerStatus.FINAL, checkNotNull(turn.deliver(final(token, "five"))).status)
    }

    // The six states

    @Test
    fun `partial, final, user-corrected, cancelled, timed-out and failed stay separate`() {
        assertEquals(
            listOf("partial", "final", "user-corrected", "cancelled", "timed-out", "failed"),
            AnswerStatus.entries.map { it.specName },
        )
        assertEquals(listOf("thinking", "capturing", "finalizing", "settled"), AnswerPhase.entries.map { it.specName })
        assertEquals(listOf("done", "window-expiry", "cancelled"), CaptureStop.entries.map { it.specName })
    }

    @Test
    fun `partial text never starts grading and never becomes a revision`() {
        val turn = turn()
        val token = turn.startAnswer()
        val revision = turn.transcriptRevision
        val heard = checkNotNull(turn.deliver(partial(token, "green blue")))
        assertEquals(AnswerStatus.PARTIAL, heard.status)
        assertFalse(heard.gradable)
        assertEquals("green blue", turn.partialText)
        assertEquals(revision, turn.transcriptRevision)
        assertNull(turn.answer)
        assertEquals(AnswerPhase.CAPTURING, turn.phase)
    }

    @Test
    fun `only a learner action may create a corrected transcript`() {
        val turn = turn()
        val token = turn.startAnswer()
        assertThrows<IllegalArgumentException> {
            turn.deliver(CaptureEvent.Transcript(token, "five", TranscriptKind.CORRECTED, Confidence.SUFFICIENT))
        }
    }

    // Confidence

    @Test
    fun `absent confidence is unknown, not zero and not certainty`() {
        val (turn, answer) = spoken("it puts you first then five then seven", Confidence.ABSENT)
        assertEquals(AnswerStatus.FINAL, answer.status)
        // Not zero: the text is kept and shown.
        assertEquals("it puts you first then five then seven", answer.text)
        // Not certainty: the learner, not the recognizer, makes it gradable.
        assertFalse(answer.gradable)
        assertTrue(answer.needsLearnerReview)
        assertEquals(AnswerRecovery.entries, answer.recovery)

        val accepted = turn.correct(answer.text)
        assertEquals(AnswerStatus.USER_CORRECTED, accepted.status)
        assertTrue(accepted.gradable)
    }

    @Test
    fun `low confidence keeps the text and asks the learner, and sufficient confidence does not`() {
        assertTrue(spoken("five", Confidence.LOW).second.needsLearnerReview)
        assertFalse(spoken("five", Confidence.SUFFICIENT).second.needsLearnerReview)
    }

    // Failures keep the card

    @Test
    fun `early recognizer closure keeps the card and offers explicit recovery only`() {
        val turn = turn()
        val token = turn.startAnswer()
        clock.advance(1_100)
        val answer = checkNotNull(turn.deliver(failed(token, SpeechInputFailure.EARLY_CLOSURE, "no usable final")))
        assertEquals(AnswerStatus.FAILED, answer.status)
        assertEquals("", answer.text)
        assertFalse(answer.gradable)
        assertEquals(
            listOf(AnswerRecovery.TRY_AGAIN, AnswerRecovery.TYPED_CORRECTION, AnswerRecovery.SELF_GRADE),
            answer.recovery,
        )
    }

    @Test
    fun `every bounded failure keeps the card and none becomes a rating`() {
        val modes = listOf(
            SpeechInputFailure.NO_MATCH,
            SpeechInputFailure.NO_SPEECH_DETECTED,
            SpeechInputFailure.NETWORK_UNAVAILABLE,
            SpeechInputFailure.PERMISSION_DENIED,
            SpeechInputFailure.RECOGNIZER_UNAVAILABLE,
            SpeechInputFailure.RECOGNIZER_ERROR,
            SpeechInputFailure.EARLY_CLOSURE,
            SpeechInputFailure.LISTEN_TIMEOUT,
            SpeechInputFailure.QUOTA_EXHAUSTED,
            SpeechInputFailure.LOW_CONFIDENCE,
        )
        for (mode in modes) {
            val turn = turn()
            val token = turn.startAnswer()
            val answer = checkNotNull(turn.deliver(failed(token, mode)))
            assertEquals(AnswerStatus.FAILED, answer.status, mode.specName)
            assertFalse(answer.gradable, mode.specName)
            assertEquals(mode, checkNotNull(answer.failure).mode, mode.specName)
            // Nothing in an answer can carry or imply an Anki rating.
            assertEquals(AnswerRecovery.entries, answer.recovery, mode.specName)
        }
    }

    @Test
    fun `an empty final is an undiagnosed no-match, not silence and not a wrong answer`() {
        val turn = turn()
        val token = turn.startAnswer()
        val answer = checkNotNull(turn.deliver(final(token, "   ")))
        assertEquals(AnswerStatus.FAILED, answer.status)
        val failure = checkNotNull(answer.failure)
        assertEquals(SpeechInputFailure.NO_MATCH, failure.mode)
        assertNotEquals(SpeechInputFailure.NO_SPEECH_DETECTED, failure.mode)
        assertTrue(failure.detail.contains("unknown"), failure.detail)
    }

    @Test
    fun `explicit Cancel settles without an answer and cleans up the recognizer`() {
        val turn = turn()
        val token = turn.startAnswer()
        clock.advance(2_000)
        val answer = turn.cancel()
        assertEquals(AnswerStatus.CANCELLED, answer.status)
        assertFalse(answer.gradable)
        assertEquals(listOf(token), speech.cancelled)
        assertEquals(AnswerPhase.SETTLED, turn.phase)
    }

    // Retry policy

    @Test
    fun `nothing re-arms on its own, and a settled turn refuses a new window`() {
        val turn = turn()
        val token = turn.startAnswer()
        turn.deliver(failed(token, SpeechInputFailure.NO_MATCH))
        assertEquals(AnswerPhase.SETTLED, turn.phase)
        assertThrows<IllegalStateException> { turn.startAnswer() }
        clock.advance(600_000)
        assertNull(turn.poll())
        assertEquals(1, turn.attempt)
        assertEquals(0, turn.retries)
    }

    @Test
    fun `Try again opens a fresh bounded window with a new attempt and revision`() {
        val turn = turn()
        val first = turn.startAnswer()
        turn.deliver(failed(first, SpeechInputFailure.NO_MATCH))
        val afterFailure = turn.transcriptRevision

        turn.tryAgain()
        assertEquals(afterFailure + 1, turn.transcriptRevision)
        assertTrue(turn.thinking)
        assertNull(turn.answer)
        // Thinking again: the new window still only opens on an explicit Start answer.
        clock.advance(30_000)
        assertTrue(turn.thinking)

        val second = turn.startAnswer()
        assertNotEquals(first, second)
        assertEquals(2, turn.attempt)
        assertEquals(1, turn.retries)
        assertEquals(15_000, turn.remainingWindowMs())
        clock.advance(1_000)
        turn.done()
        clock.advance(690)
        assertEquals("five", checkNotNull(turn.deliver(final(second, "five"))).text)
    }

    // Stale and duplicate callbacks

    @Test
    fun `a late result from an older attempt is ignored`() {
        val turn = turn()
        val first = turn.startAnswer()
        turn.deliver(failed(first, SpeechInputFailure.RECOGNIZER_ERROR))
        turn.tryAgain()
        val second = turn.startAnswer()
        val current = checkNotNull(turn.deliver(final(second, "five")))

        assertNull(turn.deliver(final(first, "six")))
        assertSame(current, turn.answer)
        assertEquals(1, turn.ignored.size)
    }

    @Test
    fun `a duplicate final for the same attempt is ignored`() {
        val turn = turn()
        val token = turn.startAnswer()
        val answer = checkNotNull(turn.deliver(final(token, "five")))
        assertNull(turn.deliver(final(token, "six")))
        assertSame(answer, turn.answer)
        assertEquals("five", checkNotNull(turn.answer).text)
    }

    @Test
    fun `a callback before Start answer is ignored`() {
        val turn = turn()
        assertNull(turn.deliver(final(OperationToken("s1", 1, 1), "five")))
        assertEquals(AnswerPhase.THINKING, turn.phase)
        assertEquals(1, turn.ignored.size)
    }

    @Test
    fun `a token from another turn is never current`() {
        val turn = turn()
        val token = turn.startAnswer()
        assertTrue(turn.isCurrent(token))
        assertFalse(turn.isCurrent(OperationToken("s1", 2, 1)))
        assertFalse(turn.isCurrent(OperationToken("s2", 1, 1)))
    }

    // Revisions, suggestions and confirmations

    @Test
    fun `editing after a suggestion invalidates the suggestion and the pending confirmation`() {
        val (turn, answer) = spoken("green blue red")
        val graded = turn.bind(reply(turn.transcriptRevision, answer.text), GradingSource.AI, card.permittedRatings)
        val suggestion = (graded as TurnGrading.Graded).suggestion
        assertEquals(3, suggestion.proposedRating)
        assertTrue(turn.isCurrent(suggestion))

        val intent = ReviewIntent(card, 3, 12_345, suggestion.request.operationToken, answer.transcriptRevision)
        assertTrue(intent.confirm(confirmation(answer.transcriptRevision, suggestion.request.operationToken)))

        val corrected = turn.correct("green, blue, red")
        assertEquals(AnswerStatus.USER_CORRECTED, corrected.status)
        assertEquals(answer.transcriptRevision + 1, corrected.transcriptRevision)
        assertFalse(turn.isCurrent(suggestion))
        assertFalse(intent.confirm(confirmation(corrected.transcriptRevision, suggestion.request.operationToken)))
        assertFalse(intent.hasConfirmation())
    }

    @Test
    fun `a grade for a superseded revision is discarded rather than displayed`() {
        val (turn, answer) = spoken("green blue red")
        val stale = reply(answer.transcriptRevision, answer.text)
        turn.correct("green, blue, red")
        assertEquals(TurnGrading.Superseded, turn.bind(stale, GradingSource.AI, card.permittedRatings))
        // The grade for the current revision still binds.
        val current = turn.bind(reply(turn.transcriptRevision, "green, blue, red"), GradingSource.AI, card.permittedRatings)
        assertTrue(current is TurnGrading.Graded)
    }

    @Test
    fun `Try again invalidates the previous suggestion immediately`() {
        val (turn, answer) = spoken("five")
        val graded = turn.bind(reply(answer.transcriptRevision, answer.text), GradingSource.AI, card.permittedRatings)
        val suggestion = (graded as TurnGrading.Graded).suggestion
        turn.tryAgain()
        assertFalse(turn.isCurrent(suggestion))
    }

    @Test
    fun `a final transcript alone never submits a review`() {
        val (turn, answer) = spoken("green blue red")
        assertTrue(answer.gradable)
        // The answer carries a transcript and nothing that could become a rating.
        val intent = ReviewIntent(card, 3, 12_345, answer.token, answer.transcriptRevision)
        assertFalse(intent.hasConfirmation())
        assertEquals(emptyList<Int>(), intent.corrections)
        // A grader failure on that transcript still proposes nothing.
        val failed = GradingReply(
            request(turn.transcriptRevision, answer.text),
            Failure(GraderFailure.GRADER_TIMEOUT, "no grade"),
        )
        assertTrue(turn.bind(failed, GradingSource.AI, card.permittedRatings) is TurnGrading.Ungraded)
    }

    // The #6 recognition failures, retained as fixtures

    @Test
    fun `the AV-006 short-number no-matches stay undiagnosed and keep the card`() {
        for (spokenText in listOf("Five.", "Six.")) {
            val turn = turn()
            val token = turn.startAnswer()
            clock.advance(1_100)
            val answer = checkNotNull(turn.deliver(failed(token, SpeechInputFailure.NO_MATCH, "ERROR_NO_MATCH (7)")))
            assertEquals(AnswerStatus.FAILED, answer.status, spokenText)
            assertFalse(answer.gradable, spokenText)
            // The learner can still supply the words the recognizer never returned.
            val typed = turn.correct(spokenText)
            assertEquals(spokenText, typed.text, spokenText)
            assertTrue(typed.gradable, spokenText)
            assertEquals(1, turn.corrections)
            assertEquals(0, turn.recognitions)
        }
    }

    @Test
    fun `the AV-006 two-you substitution is carried verbatim and stays correctable`() {
        val (turn, heard) = spoken("it puts you first then five then seven")
        // The policy never repairs a transcript: what the recognizer said is what is shown.
        assertEquals("it puts you first then five then seven", heard.text)
        assertEquals(1, turn.recognitions)

        val corrected = turn.correct("It puts two first, then five, then seven.")
        assertEquals("It puts two first, then five, then seven.", corrected.text)
        assertEquals(1, turn.corrections)
        assertEquals(heard.transcriptRevision + 1, corrected.transcriptRevision)
        assertEquals(2, turn.history.size)
        assertEquals(listOf(AnswerStatus.FINAL, AnswerStatus.USER_CORRECTED), turn.history.map { it.status })
    }

    @Test
    fun `the AV-006 negation transcripts are preserved unchanged`() {
        for (heard in listOf("no round does not satisfy Square", "it is not valid because it is not Square", "no")) {
            val (_, answer) = spoken(heard)
            assertEquals(heard, answer.text)
            assertTrue(answer.gradable)
        }
    }

    // The contract seam

    @Test
    fun `listen drives the contract with the card language and one attempt`() {
        val input = FakeSpeechInput(FakeSpeechInput.Say("green blue red"))
        val turn = AnswerTurn(input, clock, "s1", turn = 1, language = "en-US")
        val answer = checkNotNull(turn.listen())
        assertEquals("green blue red", answer.text)
        assertEquals(listOf("en-US"), input.languages)
        assertEquals(1, turn.attempt)
    }

    @Test
    fun `an exhausted recognizer script is an early closure, not an answer`() {
        val input = FakeSpeechInput()
        val turn = AnswerTurn(input, clock, "s1", turn = 1, language = "en-US")
        val answer = checkNotNull(turn.listen())
        assertEquals(AnswerStatus.FAILED, answer.status)
        assertEquals(SpeechInputFailure.EARLY_CLOSURE, checkNotNull(answer.failure).mode)
    }

    private fun request(revision: Int, text: String) = GradingRequest(
        OperationToken("s1", 1, 9),
        revision,
        GradingContext(card.fields.prompt, card.fields.referenceAnswer, emptyList(), emptyList(), text, "en-US"),
    )

    private fun reply(revision: Int, text: String) =
        GradingReply(request(revision, text), GradingResult(GradeLabel.CORRECT, "the learner said it"))

    private fun confirmation(revision: Int, token: OperationToken) = RatingConfirmation(
        token,
        card.identity,
        rating = 3,
        transcriptRevision = revision,
        source = ConfirmationSource.TOUCH,
    )
}
