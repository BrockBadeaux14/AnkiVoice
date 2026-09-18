package org.ankivoice.app

import java.util.concurrent.Executor
import org.ankivoice.core.answer.AnswerRecovery
import org.ankivoice.core.answer.AnswerStatus
import org.ankivoice.core.answer.CaptureStop
import org.ankivoice.core.commands.CommandContext
import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.contracts.CardProvider
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.ForegroundEvent
import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.ReviewOutcome
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.fakes.FakeGrader
import org.ankivoice.core.fakes.FakeJournalStore
import org.ankivoice.core.fakes.FakeSpeechInput
import org.ankivoice.core.fakes.WriteAnomaly
import org.ankivoice.core.journal.JournalRequest
import org.ankivoice.core.journal.ReviewJournal
import org.ankivoice.core.session.Interruption
import org.ankivoice.core.session.SessionState
import org.ankivoice.provider.GradingRoute
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * AV-026's study surface, over the AV-041 fakes.
 *
 * Two properties this file exists for, which the pinned-AVD run then demonstrates against
 * a real collection: **every control is derived from the session** — the screen never
 * holds a second copy of the turn — and **no control on the screen writes a review except
 * Confirm**, which reaches the writer only through AV-019's exchange.
 */
class StudyControllerTest {
    private val direct = Executor { it.run() }

    // -- opening ----------------------------------------------------------------- //

    /**
     * AV-050: the card is offered, read and answered with no tap anywhere in between. The
     * snapshots are what prove there was no resting state to tap in.
     */
    @Test
    fun `starting reads the first card and opens the microphone with no tap`() {
        val h = StudyHarness()
        val state = h.started()
        assertTrue(state.running)
        assertFalse(state.busy)
        assertEquals(1_789_414_083_106L, state.cardId)
        assertEquals("A box has three red blocks and two blue blocks. How many blocks are there in total?", state.prompt)
        assertEquals(listOf(1, 2, 3, 4), state.permittedRatings)
        assertNull(state.halt)
        assertTrue(h.wroteNothing)

        // The prompt was spoken, once, without anyone asking for it.
        assertEquals(
            listOf("A box has three red blocks and two blue blocks. How many blocks are there in total?"),
            h.promptsSpoken,
        )
        // And the microphone opened itself when that playback settled.
        assertNotNull(h.speechInput.listened, "the microphone never opened")
        assertEquals(1, h.state.attempt)
        assertEquals("Five blocks.", h.state.transcript)
        // The only touch in the whole run was opening the session.
        assertEquals(listOf("start"), h.evidence().sessionActions)
        assertEquals(emptyList<String>(), h.evidence().turns.first().touchActions)
        assertEquals(1, h.evidence().turns.first().automaticOpens)
    }

    /**
     * AV-050 D.1: the learner never hears a question the screen has not shown them.
     *
     * The card is read on the session thread and the prompt is played on the same thread,
     * so without an explicit publish between them the first thing a learner gets after
     * tapping Start studying is audio over an "Opening your deck…" screen.
     */
    @Test
    fun `the card is on screen before its prompt is spoken`() {
        val h = StudyHarness()
        val whenSpoken = mutableListOf<StudyState>()
        h.controller.onForegroundEvent(ForegroundEvent.RESUME)
        h.speechOutput.onSpeak = { whenSpoken += h.controller.state }
        h.controller.start()

        val atFirstWord = whenSpoken.first()
        assertEquals(1_789_414_083_106L, atFirstWord.cardId, "the prompt was spoken before the card was published")
        assertEquals(
            "A box has three red blocks and two blue blocks. How many blocks are there in total?",
            atFirstWord.prompt,
        )
        assertTrue(atFirstWord.running)
        assertNotEquals(StudyWords.OPENING, atFirstWord.status)
    }

    /** Nothing is spoken for a card that is not there. */
    @Test
    fun `an exhausted queue reads nothing and opens no microphone`() {
        val h = StudyHarness()
        h.collection.order.clear()
        h.started()

        assertEquals(emptyList<String>(), h.promptsSpoken)
        assertNull(h.speechInput.listened, "a microphone opened with no card to answer")
        assertEquals("exhausted", h.state.sessionState)
        assertTrue(h.wroteNothing)
    }

    /**
     * AV-050: a capture the learner did not stop is recorded under a reason of its own, so
     * a journal or a runbook can never read an endpoint as a gesture they made.
     */
    @Test
    fun `a capture that ended itself is recorded as an endpoint and never as a Done`() {
        val h = StudyHarness()
        // What the real transport reports when the learner stops speaking and its own
        // endpointing closes the microphone.
        h.captureStop = CaptureStop.ENDPOINT
        h.speechOnsetMs = 400
        h.announced()

        val turn = h.evidence().turns.first()
        assertEquals(listOf("endpoint"), turn.captureStops)
        assertFalse("done" in turn.touchActions, turn.touchActions.toString())
        assertEquals("Five blocks.", h.state.transcript, "an endpoint changed what was heard")
        assertEquals(3, h.state.pendingRating, "an endpoint changed the grading")
    }

    /** Both touch controls survive the automation: AV-048 keeps every control reachable. */
    @Test
    fun `Play prompt and Start answer are still offered where they apply`() {
        val h = StudyHarness()
        h.started()
        val waiting = h.beforeMicrophone()
        assertEquals("listening", waiting.sessionState)
        assertEquals("thinking", waiting.answerPhase)
        assertTrue(StudyControl.START_ANSWER in waiting.controls, waiting.controls.toString())
    }

    @Test
    fun `an unavailable deck is explained and offers only a reload`() {
        val h = StudyHarness()
        h.failure = Failure(CardProviderFailure.DECK_MISSING, "no deck is selected")
        val state = h.started()
        assertFalse(state.running)
        assertEquals(CardProviderFailure.DECK_MISSING, state.failure?.mode)
        assertTrue(state.status.startsWith("Study is unavailable"), state.status)
        assertEquals(setOf(StudyControl.RELOAD), state.controls)
        assertTrue(h.wroteNothing)
    }

    /** AV-007: RESUME never authorizes study on its own, and nothing opens behind it. */
    @Test
    fun `no session opens while the app is not in the foreground`() {
        val h = StudyHarness()
        h.controller.start()
        assertFalse(h.state.running)
        assertTrue(h.state.notice?.contains("foreground") == true, h.state.notice)
        assertEquals(0, h.released)
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `every command has a control, and the surface offers only the runnable ones`() {
        val h = StudyHarness()
        h.started()
        // AV-050: the card reads itself and the microphone opens on the settle, so the
        // moment the answer phase is waiting is a snapshot the surface passed through.
        val state = h.beforeMicrophone()
        assertEquals(CommandContext.COMMAND, state.context)
        // Waiting for a Start answer: repeat, pause, skip and finish are the live ones.
        assertEquals(
            listOf(VoiceCommand.REPEAT, VoiceCommand.PAUSE, VoiceCommand.FINISH_SESSION, VoiceCommand.SKIP),
            state.available,
        )
        assertFalse(VoiceCommand.RESUME in state.spokenAvailable, "resume is a touch control")
        assertEquals(
            setOf(StudyControl.START_ANSWER, StudyControl.REPEAT, StudyControl.PAUSE, StudyControl.FINISH, StudyControl.SKIP, StudyControl.SPEAK_COMMAND),
            state.controls,
        )
        assertEquals("Take your time. The microphone opens itself, or tap Start answer.", state.status)
        assertTrue(h.wroteNothing)
    }

    // -- the answer --------------------------------------------------------------- //

    /**
     * Start answer is the call to `listen`, not a window opened beside it. Opening the
     * window alone left the surface reporting a capture that nothing had asked for, and no
     * microphone was ever opened.
     */
    @Test
    fun `Start answer opens the microphone for the session language`() {
        val h = StudyHarness()
        h.started()
        h.controller.ask()
        h.controller.startAnswer()

        assertEquals(listOf("en-US"), h.speechInput.languages)
        assertEquals(h.open.answerTurn?.token, h.speechInput.listened)
        assertTrue(h.wroteNothing)
    }

    /** The learner's own words, shown back to them: the partial while it is being made up, then the settled transcript with its version. */
    @Test
    fun `the surface shows what the recognizer heard and the settled transcript with its version`() {
        val h = StudyHarness()
        h.partialText = "five blo"
        val hearing = mutableListOf<String?>()
        h.speechInput.whileListening = { hearing += h.controller.hearing() }
        h.started()
        h.controller.ask()
        h.controller.startAnswer()

        assertEquals(listOf("five blo"), hearing)
        val state = h.state
        assertEquals("Five blocks.", state.heard)
        assertEquals("Five blocks.", state.transcript)
        assertEquals(1, state.transcriptRevision)
        assertEquals("final", state.transcriptKind)
        assertFalse(state.transcriptNeedsReview)
        // Nothing is left over the next attempt: a settled attempt reports no live partial.
        assertNull(h.controller.hearing(), "a settled attempt still reports a live partial")
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `a capture that produced no transcript pauses with the reason and the manual controls`() {
        val h = StudyHarness(transcripts = listOf(FakeSpeechInput.Fail(Failure(SpeechInputFailure.NO_MATCH, "empty result"))))
        h.started()
        h.controller.ask()
        h.controller.startAnswer()

        val state = h.state
        assertNull(state.heard)
        assertNull(state.transcript)
        assertEquals(SpeechInputFailure.NO_MATCH, state.failure?.mode)
        assertEquals("paused", state.halt?.kind)
        assertEquals("noMatch", state.halt?.reason)
        assertTrue(state.halt?.explanation?.startsWith("Nothing usable was heard") == true, state.halt?.explanation)
        assertEquals(state.halt?.explanation, state.status)
        assertEquals(listOf(AnswerRecovery.TRY_AGAIN, AnswerRecovery.TYPED_CORRECTION), state.recovery)
        assertEquals(
            setOf(StudyControl.TRY_AGAIN, StudyControl.EDIT_TRANSCRIPT, StudyControl.RESUME, StudyControl.FINISH, StudyControl.SPEAK_COMMAND),
            state.controls,
        )
        assertNoWriteControl(state)
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `the answer window is visible, offers only Done and Cancel, and no spoken command`() {
        val h = StudyHarness()
        h.started()
        h.controller.ask()
        h.controller.startAnswer()

        // The window as the surface showed it while the microphone was open, not after.
        val state = h.duringCapture.single()
        assertTrue(state.capturing)
        assertTrue(state.answering, "Done had no attempt to stop")
        assertTrue(state.busy)
        assertEquals(CommandContext.ANSWER, state.context)
        assertEquals("capturing", state.answerPhase)
        assertEquals(StudyWords.LISTENING_ANSWER, state.status)
        assertEquals(emptyList<VoiceCommand>(), state.spokenAvailable)
        assertEquals(setOf(StudyControl.DONE, StudyControl.CANCEL_ANSWER), state.controls)
        // Touch is still the fallback in the router's own view of the window.
        assertTrue(VoiceCommand.PAUSE in state.available)
        assertTrue(h.wroteNothing)
    }

    /**
     * Done, pressed while the microphone is open. The transport blocks inside `listen` for
     * the whole attempt, so the touch has to reach it there.
     */
    @Test
    fun `Done reaches the transport while the capture is open`() {
        val h = StudyHarness()
        h.speechInput.whileListening = { h.controller.finishAnswer() }
        h.started()
        h.controller.ask()
        h.controller.startAnswer()

        assertEquals(listOf(h.speechInput.listened), h.speechInput.stoppedWhileOpen)
        assertEquals(CaptureStop.DONE, h.open.answerTurn?.answer?.stoppedBy)
        assertTrue(h.evidence().turns.first().touchActions.contains("done"))
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `Cancel reaches the transport while the capture is open and keeps the card`() {
        val h = StudyHarness()
        h.speechInput.whileListening = { h.controller.cancelAnswer() }
        h.started()
        h.controller.ask()
        h.controller.startAnswer()

        assertTrue(h.speechInput.listened in h.speechInput.cancelled, "the transport was not told to cancel")
        assertEquals("answer_cancelled", h.state.halt?.reason)
        assertTrue(h.state.halt?.explanation?.startsWith("You cancelled") == true, h.state.halt?.explanation)
        assertNotNull(h.open.card, "the card was dropped by a cancel")
        assertEquals("cancelled", h.evidence().turns.first().recognition.single().status)
        assertTrue(StudyControl.TRY_AGAIN in h.state.controls)
        assertNoWriteControl(h.state)
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `Try again opens a new revision and waits for Start answer`() {
        val h = StudyHarness(
            transcripts = listOf(
                FakeSpeechInput.Fail(Failure(SpeechInputFailure.NO_MATCH, "empty result")),
                FakeSpeechInput.Say("Five blocks."),
            ),
        )
        h.started()
        assertEquals("paused", h.state.halt?.kind)

        // AV-050: a Try again is a new attempt in every sense — it hears the Prompt again
        // and gets its own single automatic open after its own playback.
        h.controller.retry()
        assertEquals("proposing", h.state.sessionState)
        assertEquals(3, h.state.transcriptRevision, "the settled retry raised the revision")
        assertEquals(2, h.promptsSpoken.size, "the retry did not read the card again")
        val turn = h.evidence().turns.first()
        assertEquals(1, turn.retries)
        assertEquals(listOf("failed", "final"), turn.recognition.map { it.status })
        // One automatic open per attempt, and two attempts. Never two inside one.
        assertEquals(2, turn.automaticOpens)
        assertEquals(2, h.state.attempt)
        // The retry's own waiting snapshot still offered the touch control.
        assertTrue(StudyControl.START_ANSWER in h.beforeMicrophone().controls)
    }

    @Test
    fun `an edit creates a new version, retires the suggestion and is graded again`() {
        val h = StudyHarness(
            grades = listOf(
                FakeGrader.Answer(GradingResult(GradeLabel.CORRECT, "matched")),
                FakeGrader.Answer(GradingResult(GradeLabel.UNCERTAIN, "no concept matched")),
            ),
        )
        h.announced()
        assertEquals(3, h.state.pendingRating)
        assertEquals(1, h.state.grading?.revision)

        h.controller.editTranscript("Six blocks.")

        val state = h.state
        assertEquals("Six blocks.", state.transcript)
        assertEquals(2, state.transcriptRevision)
        assertEquals("user-corrected", state.transcriptKind)
        assertNull(state.pendingRating, "the edit did not retire the pending rating")
        assertEquals(2, state.grading?.revision, "the new version was not graded")
        assertEquals(GradingRecord.ABSTAIN, state.grading?.path)
        assertTrue(state.announcement?.startsWith("No grade") == true, state.announcement)
        assertEquals(1, h.evidence().turns.first().transcriptEdits)
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `an edit while a grade is in flight drops the stale reply and grades the new version`() {
        val h = StudyHarness(
            grades = listOf(
                FakeGrader.Answer(GradingResult(GradeLabel.CORRECT, "matched")),
                FakeGrader.Answer(GradingResult(GradeLabel.CORRECT, "matched again")),
            ),
            holdGrading = true,
        )
        h.settled()
        assertTrue(h.state.gradingInFlight)
        assertEquals(1, h.held.size)

        h.controller.editTranscript("Six blocks.")
        assertEquals(2, h.state.transcriptRevision)
        assertEquals(1, h.held.size, "no second request is opened before the first replied")

        h.releaseGrading()

        assertTrue(h.open.events.any { it.step == "stale_grade" }, "the first reply was not dropped")
        assertEquals(2, h.state.grading?.revision)
        assertEquals("proposing", h.state.sessionState)
        assertEquals(3, h.state.pendingRating)
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `a low-confidence final is shown, not graded, and can be accepted by the learner`() {
        val h = StudyHarness()
        h.speechInput.lowConfidenceNext = true
        h.started()
        h.controller.ask()
        h.controller.startAnswer()

        val state = h.state
        assertEquals("Five blocks.", state.transcript)
        assertTrue(state.transcriptNeedsReview)
        assertEquals("lowConfidence", state.halt?.reason)
        assertNull(state.grading, "an unvouched transcript was graded")
        assertTrue(StudyControl.EDIT_TRANSCRIPT in state.controls)
        assertFalse(StudyControl.RATE in state.controls, "an unvouched transcript offered a rating")

        h.controller.editTranscript("Five blocks.")
        assertEquals("proposing", h.state.sessionState)
        assertEquals("user-corrected", h.state.transcriptKind)
        assertTrue(h.wroteNothing)
    }

    // -- commands ------------------------------------------------------------------ //

    @Test
    fun `a command publishes its notice and the halt it produced`() {
        val h = StudyHarness()
        h.started()
        h.controller.ask()
        h.controller.run(VoiceCommand.PAUSE)

        val state = h.state
        assertEquals("paused", state.sessionState)
        assertTrue(state.notice?.contains("Nothing was written") == true, state.notice)
        assertEquals("learner_paused", state.halt?.reason)
        assertTrue(state.halt?.explanation?.startsWith("Paused.") == true, state.halt?.explanation)
        assertEquals(setOf(StudyControl.RESUME, StudyControl.FINISH, StudyControl.SPEAK_COMMAND), state.controls)
        assertNoWriteControl(state)
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `a skip halts without a write and offers only Resume and Finish`() {
        val h = StudyHarness()
        h.announced()
        h.controller.run(VoiceCommand.SKIP)

        val state = h.state
        assertEquals("skip_requested", state.halt?.reason)
        assertTrue(state.halt?.explanation?.contains("will be offered again") == true, state.halt?.explanation)
        assertEquals(setOf(StudyControl.RESUME, StudyControl.FINISH, StudyControl.SPEAK_COMMAND), state.controls)
        assertNull(state.pendingRating, "a skipped card kept its pending rating on screen")
        assertNoWriteControl(state)
        assertTrue(h.wroteNothing)

        // AV-050: a resumed session picks the hands-free chain back up rather than leaving
        // the learner in front of a screen that waits for a tap. The script is exhausted, so
        // the resumed attempt fails rather than settling; the card is what matters here.
        h.controller.run(VoiceCommand.RESUME)
        assertEquals(1_789_414_083_106L, h.state.cardId, "resume re-read a different card")
        assertTrue(h.wroteNothing)
    }

    /** A control the surface should not have offered reports itself instead of crashing. */
    @Test
    fun `a command run from the wrong state is refused in the surface's own words`() {
        val h = StudyHarness()
        h.started()
        h.controller.run(VoiceCommand.RESUME)
        assertTrue(h.state.notice?.contains("not available") == true, h.state.notice)
        assertEquals("proposing", h.state.sessionState, "the refused command moved the turn")
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `finishing releases the transport, writes nothing and offers a fresh start`() {
        val h = StudyHarness()
        h.started()
        h.controller.stop()

        assertFalse(h.state.running)
        assertEquals(1, h.released)
        assertEquals("stopped", h.open.state.specName)
        assertEquals("finished", h.state.closed)
        assertEquals("session_finished", h.state.halt?.reason)
        assertEquals(setOf(StudyControl.RELOAD), h.state.controls)
        assertTrue(h.state.notice?.contains("Closing wrote nothing") == true, h.state.notice)
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `a spoken finish leaves the session stopped with a reload and a finish`() {
        val h = StudyHarness()
        h.started()
        h.controller.ask()
        h.controller.run(VoiceCommand.FINISH_SESSION)

        assertTrue(h.state.running)
        assertEquals("stopped", h.state.halt?.kind)
        assertEquals(setOf(StudyControl.RELOAD, StudyControl.FINISH), h.state.controls)
        assertNoWriteControl(h.state)
    }

    // -- interruptions ------------------------------------------------------------- //

    /** AV-007: leaving the foreground breaks the single-active-reviewer precondition. */
    @Test
    fun `an app switch interrupts the session, releases the microphone and requires a reload`() {
        val h = StudyHarness()
        h.announced()
        h.controller.onForegroundEvent(ForegroundEvent.PAUSE)

        val state = h.state
        assertFalse(state.running)
        assertEquals(1, h.released)
        assertEquals("interrupted", h.open.state.specName)
        assertEquals("interrupted", state.closed)
        assertEquals("app_switch", state.halt?.reason)
        assertTrue(state.halt?.explanation?.contains("left the foreground") == true, state.halt?.explanation)
        assertEquals(setOf(StudyControl.RELOAD), state.controls)
        assertEquals(ReviewState.FAILED, h.open.intent?.state, "the pending rating survived the interruption")
        assertTrue(h.wroteNothing)
        assertTrue(h.evidence().turns.first().halts.contains("app_switch"))
    }

    @Test
    fun `a screen lock interrupts the session and is recorded as a lock`() {
        val h = StudyHarness()
        h.announced()
        h.controller.onScreenLocked()
        // The activity's own pause follows the lock; it finds nothing open.
        h.controller.onForegroundEvent(ForegroundEvent.PAUSE)

        assertFalse(h.state.running)
        assertEquals(1, h.released)
        assertEquals("lock", h.state.halt?.reason)
        assertTrue(h.state.halt?.explanation?.contains("screen locked") == true, h.state.halt?.explanation)
        assertEquals(setOf(StudyControl.RELOAD), h.state.controls)
        assertTrue(h.wroteNothing)
    }

    /** An interruption mid-capture releases the microphone at once; the late result is dropped. */
    @Test
    fun `an interruption during a capture cancels it and drops the late transcript`() {
        val h = StudyHarness()
        h.speechInput.whileListening = { h.controller.onForegroundEvent(ForegroundEvent.PAUSE) }
        h.started()
        h.controller.ask()
        h.controller.startAnswer()

        assertTrue(h.speechInput.listened in h.speechInput.cancelled, "the capture was not cancelled")
        assertEquals("interrupted", h.open.state.specName)
        assertNull(h.open.answer, "the late transcript settled an answer")
        assertEquals(AnswerStatus.CANCELLED, h.open.answerTurn?.answer?.status, "the attempt was not cancelled")
        assertFalse(h.open.events.any { it.step == "listen" }, "the late transcript reached the session")
        assertFalse(h.state.running)
        assertEquals("interrupted", h.state.closed)
        assertEquals(1, h.released)
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `a grading reply that lands after an interruption is dropped`() {
        val h = StudyHarness(holdGrading = true)
        h.settled()
        assertEquals(1, h.held.size)
        h.controller.onForegroundEvent(ForegroundEvent.PAUSE)
        val closed = h.state

        h.releaseGrading()

        assertEquals(closed, h.state, "a stale grade repainted the interrupted screen")
        assertNull(h.open.suggestion)
        assertEquals("interrupted", h.open.state.specName)
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `a spoken command that lands after an interruption runs nothing`() {
        val h = StudyHarness(transcripts = listOf(FakeSpeechInput.Say("Five blocks."), FakeSpeechInput.Say("confirm")))
        h.announced()
        h.speechInput.whileListening = { h.controller.onForegroundEvent(ForegroundEvent.PAUSE) }

        h.controller.listenForCommand()

        assertEquals("interrupted", h.open.state.specName)
        assertFalse(h.state.running)
        assertTrue(h.wroteNothing, "a confirmation heard after the interruption reached the writer")
        assertTrue(h.journal.entries().isEmpty())
    }

    @Test
    fun `reload after an interruption goes through the startup gate and opens a fresh session`() {
        val store = FakeJournalStore()
        val gate = CountingGate(JournalAccess(ReviewJournal(store), direct, direct))
        val h = StudyHarness(gate = gate)
        h.started()
        assertEquals(1, gate.opens)
        h.controller.onForegroundEvent(ForegroundEvent.PAUSE)
        assertEquals("interrupted", h.state.closed)

        h.controller.onForegroundEvent(ForegroundEvent.RESUME)
        h.controller.reload()

        assertEquals(2, gate.opens, "the reload did not consult the gate")
        assertEquals(2, h.created, "the reload restarted the interrupted session instead of opening a new one")
        assertTrue(h.state.running)
        assertNull(h.state.closed)
        // AV-050: the reloaded session reads its card and opens its microphone on its own,
        // and the harness's one scripted transcript went to the session before it.
        assertEquals(1_789_414_083_106L, h.state.cardId)
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `reload is blocked while an unknown outcome from an earlier run is unacknowledged`() {
        val store = FakeJournalStore()
        val journal = ReviewJournal(store)
        val gate = CountingGate(JournalAccess(journal, direct, direct))
        val h = StudyHarness(gate = gate)
        val card = h.collection.scheduled(h.collection.order.first())
        val entry = journal.record(
            JournalRequest("earlier-run", OperationToken("earlier-run", 1, 1), card.identity, 3, 4_200, 2, "five blocks", card.state),
        )
        journal.settle(entry.entryId, ReviewOutcome(ReviewState.OUTCOME_UNKNOWN, "Unverified write", acknowledgement = 1))

        h.controller.onForegroundEvent(ForegroundEvent.RESUME)
        h.controller.reload()

        assertFalse(h.state.running)
        assertEquals(listOf(entry.entryId), h.state.journalOutstanding)
        assertTrue(h.state.journalNotices.single().contains("cannot prove it saved rating 3"))
        assertEquals(0, h.created, "a session opened around the notice")

        h.controller.acknowledgeJournalNotice(entry.entryId)
        h.controller.reload()
        assertTrue(h.state.running)
        assertEquals(1, h.created)
        assertTrue(h.transport.calls.isEmpty(), "the gate wrote or resubmitted")
    }

    // -- AV-019: the exchange on the study screen ------------------------------------ //

    /**
     * Confirm is the one control that writes, and it is reached only through an explicit
     * learner confirmation for the pending rating, attempt and revision.
     */
    @Test
    fun `no control except Confirm writes a review`() {
        val h = StudyHarness()
        h.announced()
        h.controller.run(VoiceCommand.RATE_GOOD)
        assertEquals("proposing", h.state.sessionState)

        // Every other control on the screen, with a pending rating on the table.
        for (command in VoiceCommand.entries - VoiceCommand.CONFIRM) h.controller.run(command)
        h.controller.rate(2)
        h.controller.retry()
        h.controller.editTranscript("Five blocks.")
        h.controller.grade()
        h.controller.nextCard()
        h.controller.handOffToUndo()
        h.controller.reportReconciled(true)
        assertTrue(h.wroteNothing, "a control other than Confirm reached the writer")
        assertTrue(h.journal.entries().isEmpty(), "an unwritten rating was journalled")
    }

    @Test
    fun `grading announces the pending rating, and binds it to its source and grading status`() {
        val h = StudyHarness()
        val state = h.announced()
        assertEquals(3, state.pendingRating)
        assertEquals("rule", state.ratingSource)
        assertEquals(1, state.announcedRevision)
        // AV-050 D.4: the rule match still binds the announcement and is still on screen as
        // the source and the grading status; it is simply no longer read aloud.
        assertEquals("Card graded good.", state.announcement)
        assertEquals(GradingRecord.RULE, state.grading?.path)
        assertEquals("Rule match: matched", state.grading?.status)
        assertEquals(listOf(1, 2, 3, 4), state.ratings)
        assertTrue(state.controls.containsAll(setOf(StudyControl.CONFIRM, StudyControl.CHANGE, StudyControl.RATE, StudyControl.REVEAL, StudyControl.EDIT_TRANSCRIPT, StudyControl.TRY_AGAIN)))
        assertFalse(state.confirmed)
        assertNull(state.outcomeState)
        assertTrue(h.wroteNothing, "an announcement reached the writer")
    }

    @Test
    fun `an AI suggestion names its route on the screen and in the evidence`() {
        val h = StudyHarness()
        h.gradingSource = org.ankivoice.core.grading.GradingSource.AI
        h.gradingRoute = GradingRoute.PAID
        val state = h.announced()
        assertEquals("ai", state.ratingSource)
        assertEquals(GradingRecord.AI_PAID, state.grading?.path)
        assertTrue(state.grading?.status?.startsWith("AI suggestion (correct, paid route)") == true, state.grading?.status)
        assertEquals(GradingRecord.AI_PAID, h.evidence().turns.first().gradingPath)
    }

    @Test
    fun `a confirmed rating is written once and the surface offers Next card and the Undo handoff`() {
        val h = StudyHarness()
        h.announced()

        h.controller.run(VoiceCommand.CONFIRM)

        // AV-050 D.5: published, then advanced — so the write is read from its snapshot.
        val state = h.afterCommit()
        assertEquals(ReviewState.CONFIRMED.specName, state.outcomeState)
        assertTrue(state.committed)
        assertFalse(state.reconcileRequired)
        assertNull(state.announcement, "a written rating is still shown as waiting")
        assertEquals("Review saved.", state.status)
        // A committed turn is still a command context: "finish session" may be spoken.
        assertEquals(setOf(StudyControl.NEXT_CARD, StudyControl.UNDO_HANDOFF, StudyControl.FINISH, StudyControl.SPEAK_COMMAND), state.controls)
        assertEquals(1, h.transport.calls.size)
        assertEquals(1, h.collection.reviews.size)
        assertTrue(state.notice?.contains("Saved Good.") == true, state.notice)

        // A duplicate confirm is not even offered, and cannot write a second review.
        assertFalse(VoiceCommand.CONFIRM in state.available)
        h.controller.run(VoiceCommand.CONFIRM)
        assertEquals(1, h.transport.calls.size)
        assertEquals(1, h.collection.reviews.size)

        val turn = h.evidence().turns.first()
        assertEquals("touch", turn.confirmationSource)
        assertEquals("confirmed", turn.outcome)
        assertEquals(3, turn.rating)
        assertEquals(1, h.evidence().journal.size)
    }

    @Test
    fun `Next card advances only after a confirmed review and clears the outcome`() {
        val h = StudyHarness(
            grades = listOf(
                FakeGrader.Answer(GradingResult(GradeLabel.CORRECT, "matched")),
                FakeGrader.Answer(Failure(GraderFailure.PROVIDER_ERROR, "no route")),
            ),
        )
        h.announced()
        h.controller.run(VoiceCommand.CONFIRM)

        h.controller.nextCard()

        val state = h.state
        assertEquals("Reverse the sequence red, blue, green.", state.prompt)
        assertNull(state.outcomeState, "the previous card's verdict outlived its turn")
        assertNull(state.announcement)
        assertNull(state.grading, "the previous card's grade outlived its turn")
        assertEquals(1, h.transport.calls.size)
        assertEquals(2, h.evidence().turns.size)
        // AV-050: the new card read itself and opened its own microphone, with no Next-card
        // tap followed by a Play-prompt tap followed by a Start-answer tap.
        assertEquals(2, h.promptsSpoken.size)
        assertEquals("Reverse the sequence red, blue, green.", h.promptsSpoken.last())
        assertEquals(1, h.evidence().turns.last().automaticOpens)
    }

    @Test
    fun `the Undo handoff closes the session, releases the transport and writes nothing more`() {
        val h = StudyHarness()
        h.announced()
        h.controller.run(VoiceCommand.CONFIRM)

        h.controller.handOffToUndo()

        assertFalse(h.state.running, "the stopped session was left open")
        assertEquals(1, h.released)
        assertEquals("stopped", h.open.state.specName)
        assertEquals("undo-handoff", h.state.closed)
        assertEquals("native_undo_handoff", h.state.halt?.reason)
        assertEquals(setOf(StudyControl.RELOAD), h.state.controls)
        assertTrue(h.state.notice?.contains("Undo") == true, h.state.notice)
        assertEquals(1, h.transport.calls.size)
        assertEquals(1, h.collection.reviews.size)
        assertEquals("undo-handoff", h.evidence().closed)
    }

    @Test
    fun `a write that provably did not land says so, keeps the card and offers no write`() {
        val h = StudyHarness()
        h.transport.anomalies.addLast(WriteAnomaly.REJECT_WITH_ZERO)
        h.announced()

        h.controller.run(VoiceCommand.CONFIRM)

        val state = h.state
        assertEquals(ReviewState.FAILED.specName, state.outcomeState)
        assertFalse(state.committed)
        assertEquals("paused", state.halt?.kind)
        assertEquals("writeRejected", state.halt?.reason)
        assertTrue(state.halt?.explanation?.startsWith("Nothing was saved") == true, state.halt?.explanation)
        assertEquals(setOf(StudyControl.RESUME, StudyControl.FINISH, StudyControl.SPEAK_COMMAND), state.controls)
        assertNoWriteControl(state)
        assertEquals(0, h.collection.reviews.size)
        assertNotNull(h.open.card, "the card was dropped after a failed write")
    }

    @Test
    fun `an unconfirmable write offers only the learner-reported reconcile`() {
        val h = StudyHarness()
        h.transport.anomalies.addLast(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        h.announced()

        h.controller.run(VoiceCommand.CONFIRM)

        val state = h.state
        assertEquals(ReviewState.OUTCOME_UNKNOWN.specName, state.outcomeState)
        assertTrue(state.reconcileRequired)
        assertEquals("outcome-unknown", state.halt?.kind)
        assertTrue(state.halt?.explanation?.startsWith("AnkiVoice cannot tell whether") == true, state.halt?.explanation)
        assertEquals(setOf(StudyControl.REPORT_RECONCILED), state.controls)
        assertEquals(CommandContext.UNAVAILABLE, state.context)

        h.controller.reportReconciled(saved = false)

        assertFalse(h.state.running)
        assertEquals(1, h.released)
        assertEquals("reconciled", h.state.closed)
        assertTrue(h.state.notice?.contains("not in AnkiDroid") == true, h.state.notice)
        assertEquals(1, h.transport.calls.size, "the report retried the write")
    }

    /** The abstain path: no suggestion, so the learner names a rating and still confirms it. */
    /**
     * AV-050 D.7: the grader would not rate this one, so the microphone opens for the word
     * the announcement just asked for, and that word is applied as if it had been tapped.
     */
    @Test
    fun `an abstention listens for a rating and applies the one it hears`() {
        val h = StudyHarness(
            grades = listOf(FakeGrader.Answer(GradingResult(GradeLabel.UNCERTAIN, "no concept matched"))),
            transcripts = listOf(FakeSpeechInput.Say("Five blocks."), FakeSpeechInput.Say("hard")),
        )
        h.settled()

        // The spoken word became the learner's own rating, with no tap anywhere.
        assertEquals(2, h.state.pendingRating)
        assertEquals("learner", h.state.ratingSource)
        assertEquals(ReviewState.PENDING, h.open.intent?.state)
        assertEquals(emptyList<String>(), h.evidence().turns.first().touchActions)
        // A rating the learner named is never armed, so it still waits for a confirmation.
        assertTrue(h.wroteNothing, "a spoken rating wrote a review on its own")
        assertTrue(StudyControl.CONFIRM in h.state.controls)
    }

    /**
     * The failure that matters: this microphone was opened for the learner, not by them, so
     * saying nothing to it must not cost them the turn. AV-014 stops the session when a
     * capture the learner *asked* for hears nothing; an offered one may not.
     */
    @Test
    fun `an abstention that hears no rating leaves the turn exactly as it was`() {
        val h = StudyHarness(
            grades = listOf(FakeGrader.Answer(GradingResult(GradeLabel.UNCERTAIN, "no concept matched"))),
            transcripts = listOf(FakeSpeechInput.Say("Five blocks.")),
        )
        h.settled()

        val state = h.state
        assertNull(state.halt, "an unanswered offer stopped the session: ${state.halt?.reason}")
        assertTrue(state.running)
        assertNull(state.pendingRating)
        assertEquals("none", state.ratingSource)
        assertEquals(listOf(1, 2, 3, 4), state.ratings, "the ratings left the screen")
        assertTrue(StudyControl.RATE in state.controls)
        assertTrue(h.wroteNothing)

        // And the turn still finishes by hand, exactly as it did before D.7.
        h.controller.rate(2)
        assertEquals(2, h.state.pendingRating)
    }

    /** A grader proposal is not an abstention, and nothing opens a microphone for it. */
    @Test
    fun `a proposed rating opens no rating capture`() {
        val h = StudyHarness()
        h.announced()

        assertEquals(3, h.state.pendingRating)
        // One capture this turn: the answer. The rating came from the grader.
        assertEquals(1, h.evidence().turns.first().recognition.size)
        assertEquals(emptyList<String>(), h.evidence().turns.first().spokenCommands)
    }

    @Test
    fun `an abstention offers a self-grade and still requires a separate confirmation`() {
        val h = StudyHarness(grades = listOf(FakeGrader.Answer(GradingResult(GradeLabel.UNCERTAIN, "no concept matched"))))
        h.settled()

        assertNull(h.state.pendingRating)
        assertEquals("none", h.state.ratingSource)
        assertTrue(h.state.announcement?.startsWith("No grade") == true, h.state.announcement)
        assertEquals(GradingRecord.ABSTAIN, h.state.grading?.path)
        assertTrue(h.state.grading?.status?.contains("Rate it yourself") == true, h.state.grading?.status)
        assertEquals(listOf(1, 2, 3, 4), h.state.ratings)
        assertTrue(StudyControl.RATE in h.state.controls)

        h.controller.rate(2)

        assertEquals(2, h.state.pendingRating)
        assertEquals("learner", h.state.ratingSource)
        assertEquals(ReviewState.PENDING, h.open.intent?.state)
        assertTrue(h.wroteNothing, "naming a rating wrote one")

        h.controller.run(VoiceCommand.CONFIRM)
        assertEquals(1, h.transport.calls.size)
        assertEquals(2, h.collection.reviews.single().rating)
        val turn = h.evidence().turns.first()
        assertEquals(2, turn.selfGrade)
        assertEquals(GradingRecord.ABSTAIN, turn.gradingPath)
    }

    @Test
    fun `a grading fault pauses with the card kept and the self-grade as the way on`() {
        val h = StudyHarness(grades = listOf(FakeGrader.Answer(Failure(GraderFailure.QUOTA_EXHAUSTED, "free tier spent"))))
        h.settled()

        val state = h.state
        assertEquals("paused", state.halt?.kind)
        assertEquals("quotaExhausted", state.halt?.reason)
        assertTrue(state.halt?.explanation?.startsWith("No grade was available") == true, state.halt?.explanation)
        assertEquals(GradingRecord.UNAVAILABLE, state.grading?.path)
        assertEquals(
            setOf(StudyControl.TRY_AGAIN, StudyControl.EDIT_TRANSCRIPT, StudyControl.RATE, StudyControl.RESUME, StudyControl.FINISH, StudyControl.SPEAK_COMMAND),
            state.controls,
        )
        assertNoWriteControl(state)

        h.controller.rate(3)
        assertEquals("proposing", h.state.sessionState)
        assertEquals("learner", h.state.ratingSource)
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `a correction records what replaced what`() {
        val h = StudyHarness()
        h.announced()
        h.controller.rate(2)
        h.controller.run(VoiceCommand.RATE_EASY)
        assertEquals(4, h.state.pendingRating)
        assertEquals(listOf(RatingCorrection(3, 2), RatingCorrection(2, 4)), h.evidence().turns.first().ratingCorrections)
        assertTrue(h.wroteNothing)
    }

    // -- AV-010 on the study screen ---------------------------------------------------- //

    @Test
    fun `an unstudiable card is skipped in text and the next card is offered without a write`() {
        val h = StudyHarness()
        h.provider.nextCardScript.addLast(h.rejectedCard(999))
        h.started()
        assertEquals(1_789_414_083_106L, h.state.cardId)
        assertEquals(1, h.state.skipped.size)
        assertTrue(h.state.skipped.single().startsWith("Skipped card 999"), h.state.skipped.single())
        assertNull(h.state.skipSummary)
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `five consecutive unstudiable cards stop the session with the summary and no write`() {
        val h = StudyHarness()
        repeat(5) { h.provider.nextCardScript.addLast(h.rejectedCard(900L + it)) }
        h.started()
        val state = h.state
        assertEquals("unsupported", state.halt?.kind)
        assertEquals(5, state.skipped.size)
        assertTrue(state.skipSummary?.startsWith("Stopped after 5 consecutive unstudiable cards") == true, state.skipSummary)
        assertEquals(setOf(StudyControl.RELOAD, StudyControl.FINISH), state.controls)
        assertNoWriteControl(state)
        assertTrue(h.wroteNothing)
    }

    @Test
    fun `an exhausted queue is explained and offers a reload and a finish`() {
        val h = StudyHarness()
        h.collection.rebuildQueue(emptyList())
        h.started()
        assertEquals("exhausted", h.state.halt?.kind)
        assertTrue(h.state.halt?.explanation?.startsWith("You have finished") == true)
        assertEquals(setOf(StudyControl.RELOAD, StudyControl.FINISH), h.state.controls)
        assertNull(h.state.prompt)
    }

    // -- evidence -------------------------------------------------------------------- //

    @Test
    fun `the evidence export carries the turn, the journal and every touch, and survives closing`() {
        val h = StudyHarness()
        h.announced()
        h.controller.run(VoiceCommand.CONFIRM)
        h.controller.stop()

        val evidence = h.evidence()
        assertEquals("study", evidence.sessionId)
        assertEquals("finished", evidence.closed)
        assertEquals(listOf("start"), evidence.sessionActions)
        assertEquals(h.open.events, evidence.events)
        assertEquals(h.open.outcomes, evidence.outcomes)
        assertEquals(1, evidence.journal.size)
        assertEquals("study", evidence.journal.single().sessionId)
        val turn = evidence.turns.first()
        assertEquals(1, turn.turn)
        assertEquals(1_789_414_083_106L, turn.cardId)
        // AV-050: the prompt and the microphone are no longer taps, so they are no longer
        // touch actions, and D.5 advances on the confirmation — so Finish is a touch on the
        // card that followed, not on this one. Confirm is all this turn saw.
        assertEquals(listOf("confirm"), turn.touchActions)
        assertEquals(listOf("finish"), evidence.turns.last().touchActions)
        assertEquals(1, turn.automaticOpens)
        // The `:core` fake answers inside `listen`, so no microphone was ever stopped here;
        // AV-050's own stop reasons are exercised against the transport and in
        // [StudyControllerTest.a capture that ended itself is recorded as an endpoint].
        assertEquals(emptyList<String>(), turn.captureStops)
        assertEquals("final", turn.recognition.single().status)
        assertEquals("sufficient", turn.recognition.single().confidence)
        assertEquals(0, turn.retries)
        assertEquals(0, turn.transcriptEdits)
        assertEquals(GradingRecord.RULE, turn.gradingPath)
        assertEquals("touch", turn.confirmationSource)
        assertEquals("confirmed", turn.outcome)
        assertEquals(emptyList<String>(), turn.spokenCommands)
    }

    @Test
    fun `a spoken confirmation is recorded with its source`() {
        val h = StudyHarness(transcripts = listOf(FakeSpeechInput.Say("Five blocks."), FakeSpeechInput.Say("confirm")))
        h.announced()
        h.controller.listenForCommand()
        assertEquals(1, h.transport.calls.size)
        val turn = h.evidence().turns.first()
        assertEquals("spoken", turn.confirmationSource)
        assertEquals(listOf("confirm: executed (spoken)"), turn.spokenCommands)
    }

    /** A gate whose calls a test can count. */
    private class CountingGate(journal: JournalAccess) : ReconciliationGate(journal, "study-test") {
        var opens = 0

        override fun open(cards: () -> CardProvider?, callback: (JournalReport) -> Unit) {
            opens += 1
            super.open(cards, callback)
        }
    }
}
