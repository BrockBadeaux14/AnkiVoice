package org.ankivoice.app

import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.contracts.ConfirmationSource
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.ForegroundEvent
import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.exchange.AutomaticGrading
import org.ankivoice.core.fakes.FakeGrader
import org.ankivoice.core.fakes.FakeSpeechInput
import org.ankivoice.core.grading.GradingSource
import org.ankivoice.core.session.Interruption
import org.ankivoice.core.session.SessionState
import org.ankivoice.provider.GradingRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AV-047 on the study surface: the option, the cancel window and what still waits.
 *
 * The exchange's own rules are covered in `:core`'s `AutomaticGradingTest`. What this file
 * is for is the part only the surface can get wrong: that the window is a **timer the
 * controller owns**, that the screen offers exactly one control to stop it, that stopping
 * it beats the timer even when the timer has already fired, and that a turn the option
 * does not cover still reaches the writer only through a confirmation the learner made.
 *
 * [ManualScheduler] stands in for that timer, so nothing here waits on a clock: `elapse()`
 * is the window running out, and `release()` is the instant a real timer hands its task
 * over — the one moment a cancellation can no longer recall it.
 */
class AutomaticStudyTest {
    private fun on(
        grades: List<FakeGrader.Step> = listOf(FakeGrader.Answer(GradingResult(GradeLabel.CORRECT, "matched"))),
        transcripts: List<FakeSpeechInput.Step> = listOf(FakeSpeechInput.Say("Five blocks.")),
        holdGrading: Boolean = false,
    ) = StudyHarness(
        grades = grades,
        transcripts = transcripts,
        holdGrading = holdGrading,
        automatic = AutomaticGrading.ON,
    )

    // -- off: today's behaviour, unchanged ------------------------------------- //

    @Test
    fun `with the option off nothing is armed, nothing is offered and nothing is written`() {
        val h = StudyHarness()
        val state = h.announced()

        assertFalse(state.automaticGrading)
        assertNull(state.autoCommitWindowMs)
        assertFalse(state.autoCommitCancelled)
        assertFalse(StudyControl.CANCEL_AUTOMATIC in state.controls, state.controls.toString())
        assertTrue(h.scheduler.delays.isEmpty(), "a session with the option off opened a window")

        h.scheduler.elapse()
        assertTrue(h.wroteNothing)
        assertEquals(SessionState.PROPOSING.specName, h.state.sessionState)
        assertFalse(h.evidence().turns.first().automaticGrading)
    }

    // -- on: the window, and the commit at the end of it ----------------------- //

    @Test
    fun `a grader proposal opens the window and the screen says nothing about it`() {
        val h = on()
        val state = h.announced()

        // The behaviour is AV-047's, unchanged: the option is on and a window is armed.
        assertTrue(state.automaticGrading)
        assertEquals(AutomaticGrading.DEFAULT_CANCEL_WINDOW_MS, state.autoCommitWindowMs)
        assertEquals(listOf(AutomaticGrading.DEFAULT_CANCEL_WINDOW_MS), h.scheduler.delays)
        assertEquals(3, state.pendingRating)
        assertTrue(h.wroteNothing, "opening the window wrote a review")

        // AV-050: and nothing the learner can read says so. The announcement is spoken as
        // well as shown, so it is the one that matters most.
        assertNoAutomaticGradingText(state)
        assertEquals("Card graded good.", state.announcement)
        assertFalse(state.announcement?.contains("unless you stop it") == true, state.announcement)
    }

    @Test
    fun `the window running out commits the rule-matched rating with no learner gesture`() {
        val h = on()
        h.announced()
        h.scheduler.elapse()

        val state = h.afterCommit()
        assertEquals(ReviewState.CONFIRMED.specName, state.outcomeState)
        assertEquals(SessionState.COMMITTED.specName, state.sessionState)
        assertTrue(state.committed)
        assertEquals(1, h.collection.reviews.size)
        assertEquals(3, h.collection.reviews.single().rating)
        assertNull(state.autoCommitWindowMs, "the window stayed open over a committed review")
        // AV-050: the saved review is still announced — a write the learner did not make is
        // exactly what they need told — and the sentence names the rating, never the mode.
        assertTrue(state.notice?.contains("Saved Good") == true, state.notice)
        assertTrue(state.notice?.contains("Good") == true, state.notice)
        assertNoAutomaticGradingText(state)
    }

    @Test
    fun `an AI-proposed rating commits the same way`() {
        val h = on(grades = listOf(FakeGrader.Answer(GradingResult(GradeLabel.INCORRECT, "missed a concept"))))
        h.gradingSource = GradingSource.AI
        h.gradingRoute = GradingRoute.FREE
        h.announced()
        assertEquals("ai", h.state.ratingSource)

        h.scheduler.elapse()

        assertEquals(ReviewState.CONFIRMED.specName, h.afterCommit().outcomeState)
        assertEquals(1, h.collection.reviews.single().rating)
    }

    @Test
    fun `the record and the journal tell an automatic commit from a confirmed one`() {
        val automatic = on()
        automatic.announced()
        automatic.scheduler.elapse()

        val turn = automatic.committedTurn()
        assertEquals(ConfirmationSource.AUTO.specName, turn.confirmationSource)
        assertTrue(turn.automaticGrading)
        assertFalse(turn.automaticCancelled)
        assertEquals(ReviewState.CONFIRMED.specName, turn.outcome)
        assertEquals(3, turn.rating)
        assertEquals(ConfirmationSource.AUTO, automatic.evidence().journal.single().confirmationSource)

        val manual = StudyHarness()
        manual.announced()
        manual.controller.run(VoiceCommand.CONFIRM)

        val confirmed = manual.committedTurn()
        assertEquals(ConfirmationSource.TOUCH.specName, confirmed.confirmationSource)
        assertFalse(confirmed.automaticGrading)
        assertEquals(ConfirmationSource.TOUCH, manual.evidence().journal.single().confirmationSource)
    }

    @Test
    fun `the next card opens a window of its own`() {
        val h = on(
            grades = listOf(
                FakeGrader.Answer(GradingResult(GradeLabel.CORRECT, "matched")),
                FakeGrader.Answer(GradingResult(GradeLabel.CORRECT, "matched again")),
            ),
            transcripts = listOf(
                FakeSpeechInput.Say("Five blocks."),
                FakeSpeechInput.Say("Green, blue, red."),
            ),
        )
        // AV-050 D.5: the saved first card advances by itself, reads the second and opens
        // its microphone — so the second card arms a window of its own with no tap at all,
        // and running the scheduler out carries the pair of them through.
        h.announced()
        h.scheduler.elapse()

        assertEquals(
            listOf(AutomaticGrading.DEFAULT_CANCEL_WINDOW_MS, AutomaticGrading.DEFAULT_CANCEL_WINDOW_MS),
            h.scheduler.delays,
            "the second card did not arm a window of its own",
        )
        assertEquals(2, h.collection.reviews.size, "the second card was not saved automatically")
        assertEquals(2, h.evidence().turns.size)
        assertTrue(h.evidence().turns.all { it.automaticGrading })
    }

    // -- cancelling ------------------------------------------------------------ //

    @Test
    fun `keeping it manual writes nothing and leaves the rating correctable`() {
        val h = on()
        h.announced()
        h.controller.cancelAutomaticCommit()

        val state = h.state
        assertTrue(h.wroteNothing, "keeping the turn manual wrote a review")
        assertNull(state.autoCommitWindowMs)
        assertTrue(state.autoCommitCancelled)
        assertFalse(StudyControl.CANCEL_AUTOMATIC in state.controls)
        assertEquals(SessionState.PROPOSING.specName, state.sessionState)
        assertEquals(3, state.pendingRating, "the pending rating left the screen")
        assertTrue(StudyControl.CONFIRM in state.controls)
        assertTrue(state.notice?.contains("nothing was written") == true, state.notice)

        // The window is gone for good: running the scheduler out writes nothing.
        h.scheduler.elapse()
        assertTrue(h.wroteNothing)

        // And the learner's own confirmation still works, at their own pace.
        h.controller.rate(2)
        h.controller.run(VoiceCommand.CONFIRM)
        assertEquals(2, h.collection.reviews.single().rating)
        assertEquals(ConfirmationSource.TOUCH.specName, h.committedTurn().confirmationSource)
        assertTrue(h.evidence().turns.first().automaticCancelled)
    }

    @Test
    fun `a cancel that arrives after the timer fired still writes nothing`() {
        val h = on()
        h.announced()

        // The timer has released its task; nothing can recall it any more.
        val fired = h.scheduler.release()
        h.controller.cancelAutomaticCommit()
        fired.run()

        assertTrue(h.wroteNothing, "a window that fired wrote over the learner's cancel")
        assertEquals(SessionState.PROPOSING.specName, h.state.sessionState)
        assertEquals(3, h.state.pendingRating)
        assertTrue(h.evidence().turns.first().automaticCancelled)
    }

    @Test
    fun `the cancelled note belongs to that rating and does not follow the learner to the next one`() {
        val h = on(
            grades = listOf(
                FakeGrader.Answer(GradingResult(GradeLabel.CORRECT, "matched")),
                FakeGrader.Answer(GradingResult(GradeLabel.UNCERTAIN, "no concept matched")),
            ),
            transcripts = listOf(
                FakeSpeechInput.Say("Five blocks."),
                FakeSpeechInput.Say("Green, blue, red."),
            ),
        )
        h.announced()
        h.controller.cancelAutomaticCommit()
        assertTrue(h.state.autoCommitCancelled)

        h.controller.run(VoiceCommand.CONFIRM)
        h.controller.nextCard()
        h.controller.ask()
        h.controller.startAnswer()
        // The second card abstains, so nothing arms; the learner names a rating themselves.
        h.controller.rate(2)

        assertEquals(2, h.state.pendingRating)
        assertFalse(
            h.state.autoCommitCancelled,
            "the first card's cancel was still claimed over a rating the learner named on the second",
        )
    }

    @Test
    fun `cancelling is offered only while a window is open`() {
        val h = on()
        // AV-050: opening a session now runs all the way to the proposal, so the moment
        // before one is armed is a snapshot the surface passed through.
        assertTrue(StudyControl.CANCEL_AUTOMATIC in h.started().controls)
        assertFalse(StudyControl.CANCEL_AUTOMATIC in h.beforeMicrophone().controls)
        h.controller.cancelAutomaticCommit()
        assertFalse(StudyControl.CANCEL_AUTOMATIC in h.state.controls)
    }

    // -- AV-050: the running screen carries no sign of the mode ---------------- //

    /**
     * The guard AV-050 asks for: no text a running study screen can produce says
     * "Automatic grading". It is applied to a whole session's worth of snapshots rather
     * than to one, because the banner it replaces was on screen for the whole session.
     */
    @Test
    fun `no snapshot of a whole automatic session names the mode`() {
        val h = on()
        h.announced()
        h.scheduler.elapse()
        h.controller.nextCard()
        h.controller.retry()
        h.controller.stop()

        assertTrue(h.published.isNotEmpty())
        h.published.forEach(::assertNoAutomaticGradingText)
        // The evidence still records it, because the record is not the screen.
        assertTrue(h.evidence().turns.all { it.automaticGrading })
    }

    /** A window that is retired mid-flight writes nothing and still says nothing. */
    @Test
    fun `a corrected rating retires the window, writes nothing and names no mode`() {
        val h = on()
        h.announced()
        // The learner corrects the rating while the timer is already in the scheduler's
        // hand: the released task finds a window that no longer applies and writes nothing.
        val released = h.scheduler.release()
        h.controller.rate(2)
        released.run()

        val state = h.state
        assertTrue(h.collection.reviews.isEmpty(), "a retired window still wrote a review")
        assertEquals(2, state.pendingRating)
        assertNull(state.autoCommitWindowMs)
        assertNoAutomaticGradingText(state)
    }

    // -- what stays manual whatever the option says ---------------------------- //

    @Test
    fun `an abstention opens no window and hands the turn back`() {
        val h = on(grades = listOf(FakeGrader.Answer(GradingResult(GradeLabel.UNCERTAIN, "no concept matched"))))
        val state = h.settled()

        assertEquals("none", state.ratingSource)
        assertNull(state.pendingRating)
        assertNull(state.autoCommitWindowMs)
        assertFalse(StudyControl.CANCEL_AUTOMATIC in state.controls)
        assertTrue(h.scheduler.delays.isEmpty(), "an abstention opened a window")

        h.scheduler.elapse()
        assertTrue(h.wroteNothing)
        assertTrue(StudyControl.RATE in h.state.controls, "the learner was not offered the self-grade")
    }

    @Test
    fun `a grader failure opens no window and hands the turn back`() {
        val h = on(grades = listOf(FakeGrader.Answer(Failure(GraderFailure.QUOTA_EXHAUSTED, "free tier spent"))))
        val state = h.settled()

        assertNull(state.autoCommitWindowMs)
        assertTrue(h.scheduler.delays.isEmpty(), "a grading failure opened a window")
        h.scheduler.elapse()
        assertTrue(h.wroteNothing)
        assertEquals(GradingRecord.UNAVAILABLE, h.evidence().turns.first().gradingPath)
    }

    @Test
    fun `a rating the learner named is never saved on its own`() {
        val h = on(grades = listOf(FakeGrader.Answer(GradingResult(GradeLabel.PARTIAL, "half of it"))))
        h.settled()
        h.controller.rate(2)

        assertEquals("learner", h.state.ratingSource)
        assertNull(h.state.autoCommitWindowMs)
        assertTrue(h.scheduler.delays.isEmpty(), "a self-grade opened a window")
        h.scheduler.elapse()
        assertTrue(h.wroteNothing, "a rating the learner named was written without a confirmation")
        assertEquals(2, h.state.pendingRating)
    }

    @Test
    fun `correcting the grader's rating closes the window and waits for a confirmation`() {
        val h = on()
        h.announced()
        h.controller.rate(4)

        assertEquals("learner", h.state.ratingSource)
        assertNull(h.state.autoCommitWindowMs)
        h.scheduler.elapse()
        assertTrue(h.wroteNothing, "a corrected rating was written without a confirmation")
        assertEquals(4, h.state.pendingRating)

        h.controller.run(VoiceCommand.CONFIRM)
        assertEquals(4, h.collection.reviews.single().rating)
        assertEquals(ConfirmationSource.TOUCH.specName, h.committedTurn().confirmationSource)
    }

    @Test
    fun `a transcript edit inside the window withdraws the rating and writes nothing`() {
        val h = on(
            grades = listOf(
                FakeGrader.Answer(GradingResult(GradeLabel.CORRECT, "matched")),
                FakeGrader.Answer(GradingResult(GradeLabel.CORRECT, "matched again")),
            ),
            holdGrading = true,
        )
        h.started()
        h.controller.ask()
        h.controller.startAnswer()
        h.releaseGrading()
        assertEquals(AutomaticGrading.DEFAULT_CANCEL_WINDOW_MS, h.state.autoCommitWindowMs)

        // The timer had already fired when the edit landed: the worst ordering there is.
        val fired = h.scheduler.release()
        h.controller.editTranscript("Five city blocks.")
        fired.run()

        assertTrue(h.wroteNothing, "an edited answer's old rating was written")
        assertNull(h.state.pendingRating, "the edit did not withdraw the pending rating")
        assertEquals(2, h.state.transcriptRevision)
        assertEquals(1, h.evidence().turns.first().transcriptEdits)
    }

    @Test
    fun `an interruption inside the window releases the microphone and writes nothing`() {
        val h = on()
        h.announced()
        h.controller.onForegroundEvent(ForegroundEvent.PAUSE)

        assertTrue(h.wroteNothing)
        assertFalse(h.state.running)
        assertEquals("interrupted", h.state.closed)
        h.scheduler.elapse()
        assertTrue(h.wroteNothing, "a window fired into an interrupted session")
        assertTrue(h.evidence().turns.first().halts.contains(Interruption.APP_SWITCH.specName))
    }

    @Test
    fun `finishing the session inside the window writes nothing`() {
        val h = on()
        h.announced()
        h.controller.stop()

        h.scheduler.elapse()
        assertTrue(h.wroteNothing, "a window fired into a closed session")
        assertEquals("finished", h.state.closed)
    }

    @Test
    fun `a window never reaches a session that was replaced by a reload`() {
        val h = on()
        h.announced()
        val fired = h.scheduler.release()
        h.controller.onForegroundEvent(ForegroundEvent.PAUSE)
        h.controller.onForegroundEvent(ForegroundEvent.RESUME)
        h.controller.reload()
        fired.run()

        assertTrue(h.wroteNothing, "a window from the first session wrote into the second")
        assertEquals(2, h.created, "the reload did not open a fresh session")
    }
}
