package org.ankivoice.app

import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.ForegroundEvent
import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.fakes.FakeGrader
import org.ankivoice.core.fakes.FakeSpeechInput
import org.ankivoice.core.fakes.WriteAnomaly
import org.ankivoice.core.journal.JournalPhase
import org.ankivoice.core.session.SessionState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * AV-026 decision 4: the AV-007 named scenarios, driven through the **study controller**
 * with the fakes, asserting what the screen shows at each step.
 *
 * `NamedScenariosTest` in `:core` proves the session; this suite proves the surface over
 * it — the prompt, the transcript and its version, the grading status, the Announced
 * position, the halt in the learner's words, and the controls offered — with no emulator
 * and no network. Every scenario ends with the same two facts checked: how many reviews the
 * collection holds, and that no control other than Confirm ever wrote one.
 */
class StudyScenariosTest {
    private val correct = GradingResult(GradeLabel.CORRECT, "the learner stated the required concept")
    private val partial = GradingResult(GradeLabel.PARTIAL, "one required concept is missing")

    /** A full turn that verifies and advances. */
    @Test
    @Scenario("confirmed-commit")
    fun confirmedCommit() {
        val h = StudyHarness(grades = listOf(FakeGrader.Answer(correct)))
        // AV-050: opening the session reads the card and opens the microphone with no tap.
        h.started()
        assertEquals("A box has three red blocks and two blue blocks. How many blocks are there in total?", h.state.prompt)
        assertEquals(
            "Take your time. The microphone opens itself, or tap Start answer.",
            h.beforeMicrophone().status,
        )

        // The screen after the answer settled and was graded: transcript, version, source, rating.
        val announced = h.state
        assertEquals("Five blocks.", announced.transcript)
        assertEquals(1, announced.transcriptRevision)
        assertEquals(GradingRecord.RULE, announced.grading?.path)
        assertEquals(3, announced.pendingRating)
        assertEquals("rule", announced.ratingSource)
        assertEquals("A rating is waiting for your confirmation. Confirm it, or change it.", announced.status)
        assertTrue(StudyControl.CONFIRM in announced.controls)
        assertTrue(h.wroteNothing, "nothing may be written before the confirmation")

        h.controller.run(VoiceCommand.CONFIRM)
        // AV-050 D.5: the write is published and the session carries straight on, so the
        // saved review is read from the snapshot it was published in.
        val committed = h.afterCommit()
        assertEquals(ReviewState.CONFIRMED.specName, committed.outcomeState)
        assertEquals("Review saved.", committed.status)
        assertEquals(setOf(StudyControl.NEXT_CARD, StudyControl.UNDO_HANDOFF, StudyControl.FINISH, StudyControl.SPEAK_COMMAND), committed.controls)
        assertEquals(1, h.collection.reviews.size)
        assertEquals(3, h.collection.reviews.single().rating)
        assertEquals(JournalPhase.SETTLED, h.journal.entries().single().phase)

        // No Next card tap: the confirmation advanced, and the next card read itself.
        assertEquals("Reverse the sequence red, blue, green.", h.state.prompt)
        assertNull(h.state.outcomeState)
        assertEquals(1, h.transport.calls.size)
        // AV-007's only post-commit correction survives the advance.
        assertTrue(StudyControl.UNDO_HANDOFF in h.state.controls)
    }

    /** The learner changes the rating twice before it is submitted. */
    @Test
    @Scenario("precommit-correction")
    fun precommitCorrection() {
        val h = StudyHarness(grades = listOf(FakeGrader.Answer(partial)))
        h.settled()
        // A partial answer proposes nothing: the learner must supply the rating.
        assertNull(h.state.pendingRating)
        assertEquals(GradingRecord.ABSTAIN, h.state.grading?.path)
        assertTrue(StudyControl.RATE in h.state.controls)

        h.controller.rate(1)
        assertEquals(1, h.state.pendingRating)
        h.controller.rate(2)
        h.controller.rate(3)
        assertEquals(3, h.state.pendingRating)
        assertEquals("learner", h.state.ratingSource)
        assertTrue(h.wroteNothing, "nothing may be written before commit")

        h.controller.run(VoiceCommand.CONFIRM)
        assertEquals(1, h.transport.calls.size)
        assertEquals(3, h.transport.calls.single().rating)
        val turn = h.committedTurn()
        assertEquals(1, turn.selfGrade)
        assertEquals(listOf(RatingCorrection(1, 2), RatingCorrection(2, 3)), turn.ratingCorrections)
    }

    /** An edit invalidates the prior suggestion, pending rating and grade callback. */
    @Test
    @Scenario("corrected-transcript-and-stale-grade")
    fun correctedTranscriptAndStaleGrade() {
        val h = StudyHarness(
            grades = listOf(FakeGrader.Answer(correct), FakeGrader.Answer(correct)),
            transcripts = listOf(FakeSpeechInput.Say("Six blocks.")),
            holdGrading = true,
        )
        h.settled()
        assertEquals("Six blocks.", h.state.transcript)
        assertTrue(h.state.gradingInFlight)
        assertEquals("Checking your answer…", h.state.status)

        h.controller.editTranscript("Five blocks.")
        assertEquals("Five blocks.", h.state.transcript)
        assertEquals(2, h.state.transcriptRevision)
        assertNull(h.state.pendingRating)

        h.releaseGrading()
        assertTrue(h.open.events.any { it.step == "stale_grade" }, "the reply for the old revision was applied")
        assertEquals(2, h.state.grading?.revision, "the label does not describe the transcript shown")
        assertEquals(3, h.state.pendingRating)
        assertEquals(2, h.state.announcedRevision)
        assertTrue(h.wroteNothing)

        h.controller.run(VoiceCommand.CONFIRM)
        assertEquals(ReviewState.CONFIRMED.specName, h.afterCommit().outcomeState)
        assertEquals("Five blocks.", h.grader.seen.last().learnerAnswer)
        assertEquals("Five blocks.", h.journal.entries().single().transcript)
    }

    /** A suggestion and elapsed silence cannot submit. */
    @Test
    @Scenario("absent-confirmation")
    fun absentConfirmation() {
        val h = StudyHarness(grades = listOf(FakeGrader.Answer(correct)))
        h.announced()
        assertEquals(3, h.state.pendingRating)

        // Nothing the learner does short of Confirm advances the card.
        h.controller.nextCard()
        assertTrue(h.state.notice?.contains("not available") == true, h.state.notice)
        assertEquals("proposing", h.state.sessionState)
        assertFalse(h.state.confirmed)
        assertTrue(h.wroteNothing)

        h.controller.stop()
        assertTrue(h.wroteNothing)
        assertTrue(h.journal.entries().isEmpty())
        assertNull(h.evidence().turns.single().confirmationSource)
    }

    /** A final, sufficiently confident spoken command confirms the current rating. */
    @Test
    @Scenario("spoken-confirmation")
    fun spokenConfirmation() {
        val h = StudyHarness(
            grades = listOf(FakeGrader.Answer(correct)),
            transcripts = listOf(FakeSpeechInput.Say("Five blocks."), FakeSpeechInput.Say("confirm"), FakeSpeechInput.Say("confirm")),
        )
        h.announced()
        assertTrue(StudyControl.SPEAK_COMMAND in h.state.controls)
        assertTrue(VoiceCommand.CONFIRM in h.state.spokenAvailable)

        // Heard below sufficient: refused, re-prompted once, nothing written.
        h.speechInput.lowConfidenceNext = true
        h.controller.listenForCommand()
        assertTrue(h.state.notice?.contains("still waiting") == true, h.state.notice)
        assertEquals(3, h.state.pendingRating)
        assertTrue(h.wroteNothing, "an uncertain spoken confirmation wrote a review")

        // Heard clearly: the confirmation executes and the single write runs.
        h.controller.listenForCommand()
        assertEquals(ReviewState.CONFIRMED.specName, h.afterCommit().outcomeState)
        assertEquals(1, h.transport.calls.size)
        val turn = h.committedTurn()
        assertEquals("spoken", turn.confirmationSource)
        assertEquals(2, turn.spokenCommands.size)
    }

    /** Partial speech then early closure requires explicit retry or learner correction. */
    @Test
    @Scenario("early-closure-and-touch-fallback")
    fun earlyClosureAndTouchFallback() {
        val h = StudyHarness(
            grades = listOf(FakeGrader.Answer(correct)),
            transcripts = listOf(FakeSpeechInput.Fail(Failure(SpeechInputFailure.EARLY_CLOSURE, "recognizer ended without a usable final"))),
        )
        h.settled()

        val paused = h.state
        assertEquals("earlyClosure", paused.halt?.reason)
        assertTrue(paused.halt?.explanation?.startsWith("The recording ended") == true, paused.halt?.explanation)
        assertEquals(
            setOf(StudyControl.TRY_AGAIN, StudyControl.EDIT_TRANSCRIPT, StudyControl.RESUME, StudyControl.FINISH, StudyControl.SPEAK_COMMAND),
            paused.controls,
        )
        assertNoWriteControl(paused)
        assertTrue(h.grader.seen.isEmpty(), "a failed capture started grading")

        // The touch fallback: the learner types the answer, which is graded and confirmed.
        h.controller.editTranscript("Five blocks.")
        assertEquals("proposing", h.state.sessionState)
        assertEquals("user-corrected", h.state.transcriptKind)
        h.controller.run(VoiceCommand.CONFIRM)
        assertEquals(ReviewState.CONFIRMED.specName, h.afterCommit().outcomeState)
        val turn = h.committedTurn()
        assertEquals(1, turn.transcriptEdits)
        assertEquals("failed", turn.recognition.first().status)
    }

    /** An exhausted grader quota never becomes a rating. */
    @Test
    @Scenario("grader-unavailable")
    fun graderUnavailable() {
        val h = StudyHarness(grades = listOf(FakeGrader.Answer(Failure(GraderFailure.QUOTA_EXHAUSTED, "free tier spent"))))
        h.settled()

        val paused = h.state
        assertEquals("paused", paused.halt?.kind)
        assertEquals("quotaExhausted", paused.halt?.reason)
        assertNull(paused.pendingRating, "a fault proposed a rating")
        assertEquals(GradingRecord.UNAVAILABLE, paused.grading?.path)
        assertEquals(listOf(1, 2, 3, 4), paused.ratings)
        assertNoWriteControl(paused)
        assertTrue(h.wroteNothing)

        h.controller.rate(2)
        assertEquals("learner", h.state.ratingSource)
        h.controller.run(VoiceCommand.CONFIRM)
        assertEquals(2, h.collection.reviews.single().rating)
        val turn = h.committedTurn()
        assertEquals(GradingRecord.UNAVAILABLE, turn.gradingPath)
        assertEquals(2, turn.selfGrade)
    }

    /** A skip halts without any write of any kind. */
    @Test
    @Scenario("skip-request")
    fun skipRequest() {
        val h = StudyHarness(grades = listOf(FakeGrader.Answer(correct)))
        h.announced()
        val cardId = checkNotNull(h.state.cardId)
        val repsBefore = h.collection.scheduled(cardId).state.reps

        h.controller.run(VoiceCommand.SKIP)

        val paused = h.state
        assertEquals("skip_requested", paused.halt?.reason)
        assertEquals(setOf(StudyControl.RESUME, StudyControl.FINISH, StudyControl.SPEAK_COMMAND), paused.controls)
        assertNoWriteControl(paused)
        assertTrue(h.wroteNothing)
        assertEquals(repsBefore, h.collection.scheduled(cardId).state.reps, "the card is untouched")
        assertTrue(h.evidence().turns.single().touchActions.contains("skip"))
    }

    /** After commit there is no in-app correction, only native Undo. */
    @Test
    @Scenario("native-undo-handoff")
    fun nativeUndoHandoff() {
        val h = StudyHarness(grades = listOf(FakeGrader.Answer(correct)))
        h.announced()
        h.controller.run(VoiceCommand.CONFIRM)
        assertFalse(StudyControl.RATE in h.state.controls, "a committed review offered a correction")
        assertFalse(StudyControl.CHANGE in h.state.controls)

        h.controller.handOffToUndo()

        assertFalse(h.state.running)
        assertEquals("undo-handoff", h.state.closed)
        assertEquals("native_undo_handoff", h.state.halt?.reason)
        assertTrue(h.state.halt?.explanation?.contains("AnkiDroid's own Undo") == true)
        assertEquals(setOf(StudyControl.RELOAD), h.state.controls)
        assertEquals(1, h.transport.calls.size, "still exactly one write")
        assertEquals(1, h.released)
    }

    /** App switching stops the session instead of reconciling; an ambiguous write still needs reconciling. */
    @Test
    @Scenario("single-active-reviewer")
    fun singleActiveReviewer() {
        val pending = StudyHarness(grades = listOf(FakeGrader.Answer(correct)))
        pending.announced()
        pending.controller.onForegroundEvent(ForegroundEvent.PAUSE)
        assertEquals("interrupted", pending.state.closed)
        assertEquals("app_switch", pending.state.halt?.reason)
        assertFalse(pending.state.halt?.reconciliationRequired == true, "no write was ever handed over")
        assertEquals(setOf(StudyControl.RELOAD), pending.state.controls)
        assertTrue(pending.wroteNothing)

        pending.controller.onForegroundEvent(ForegroundEvent.RESUME)
        pending.controller.reload()
        assertTrue(pending.state.running)
        assertEquals(2, pending.created, "the reload did not open a fresh session")

        val unconfirmed = StudyHarness(grades = listOf(FakeGrader.Answer(correct)))
        unconfirmed.transport.anomalies.addLast(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        unconfirmed.announced()
        unconfirmed.controller.run(VoiceCommand.CONFIRM)
        assertEquals(setOf(StudyControl.REPORT_RECONCILED), unconfirmed.state.controls)
        unconfirmed.controller.onForegroundEvent(ForegroundEvent.PAUSE)
        // The halt no command may leave stands through the interruption.
        assertEquals("outcome-unknown", unconfirmed.state.halt?.kind)
        assertTrue(unconfirmed.state.halt?.reconciliationRequired == true, "an ambiguous write still needs reconciling")
        assertEquals(1, unconfirmed.transport.calls.size, "the write was retried")
    }

    /** Every inconsistent post-state resolves to outcome-unknown and offers only the report. */
    @Test
    @Scenario("unconfirmed-write-variants")
    fun unconfirmedWriteVariants() {
        val cases = mapOf(
            WriteAnomaly.REJECT_WITH_ZERO to ReviewState.FAILED,
            WriteAnomaly.NULL_RESPONSE to ReviewState.OUTCOME_UNKNOWN,
            WriteAnomaly.ERROR_RESPONSE to ReviewState.OUTCOME_UNKNOWN,
            WriteAnomaly.DOUBLE_APPLY to ReviewState.OUTCOME_UNKNOWN,
            WriteAnomaly.SUSPENDED_INSTEAD to ReviewState.OUTCOME_UNKNOWN,
            WriteAnomaly.ZERO_BUT_APPLIED to ReviewState.OUTCOME_UNKNOWN,
            WriteAnomaly.UNEXPECTED_COUNT to ReviewState.OUTCOME_UNKNOWN,
        )
        for ((anomaly, expected) in cases) {
            val h = StudyHarness(grades = listOf(FakeGrader.Answer(correct)))
            h.transport.anomalies.addLast(anomaly)
            h.announced()
            h.controller.run(VoiceCommand.CONFIRM)

            val state = h.state
            assertEquals(expected.specName, state.outcomeState, anomaly.name)
            assertEquals(1, h.transport.calls.size, "$anomaly: exactly one dispatch")
            assertNoWriteControl(state)
            if (expected == ReviewState.OUTCOME_UNKNOWN) {
                assertEquals(setOf(StudyControl.REPORT_RECONCILED), state.controls, anomaly.name)
                assertTrue(state.halt?.explanation?.startsWith("AnkiVoice cannot tell") == true, anomaly.name)
            } else {
                // A provable non-write pauses with the card kept and the way back in.
                assertEquals("paused", state.halt?.kind, anomaly.name)
                assertTrue(StudyControl.RESUME in state.controls, anomaly.name)
                assertTrue(state.halt?.explanation?.startsWith("Nothing was saved") == true, anomaly.name)
            }
        }

        // The post-state cannot be read at all: unknown, never success.
        val unreadable = StudyHarness(grades = listOf(FakeGrader.Answer(correct)))
        unreadable.announced()
        unreadable.provider.readCardScript.addLast(null)
        unreadable.provider.readCardScript.addLast(Failure(CardProviderFailure.ACCESS_DENIED, "permission revoked mid-turn"))
        unreadable.controller.run(VoiceCommand.CONFIRM)
        assertEquals(ReviewState.OUTCOME_UNKNOWN.specName, unreadable.state.outcomeState)
        assertEquals(setOf(StudyControl.REPORT_RECONCILED), unreadable.state.controls)
    }

    /** Names the AV-007 scenario a test drives, mirroring `:core`'s annotation for the surface. */
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    annotation class Scenario(val name: String)

    /** The eleven scenarios decision 4 names, all driven above. */
    @Test
    fun `every scenario decision 4 names is driven through the surface`() {
        val driven = StudyScenariosTest::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(Scenario::class.java)?.name }
            .toSet()
        assertEquals(
            setOf(
                "confirmed-commit", "precommit-correction", "corrected-transcript-and-stale-grade",
                "absent-confirmation", "spoken-confirmation", "early-closure-and-touch-fallback",
                "grader-unavailable", "skip-request", "native-undo-handoff", "single-active-reviewer",
                "unconfirmed-write-variants",
            ),
            driven,
        )
        assertEquals(SessionState.PROPOSING.specName, "proposing")
    }
}
