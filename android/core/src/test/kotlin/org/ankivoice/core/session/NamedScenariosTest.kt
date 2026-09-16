package org.ankivoice.core.session

import org.ankivoice.core.answer.AnswerRecovery
import org.ankivoice.core.contracts.Capabilities
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.ConfirmationSource
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.QueueExhausted
import org.ankivoice.core.contracts.ReviewIntent
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.ReviewWriterFailure
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.TranscriptKind
import org.ankivoice.core.contracts.UtterancePurpose
import org.ankivoice.core.fakes.FakeGrader
import org.ankivoice.core.fakes.FakeSpeechInput
import org.ankivoice.core.fakes.ReviewSource
import org.ankivoice.core.fakes.WriteAnomaly
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The nineteen named AV-007 scenarios, ported from `tools/av007_scenarios.py` as JVM
 * tests against the AV-041 fakes. Each one asserts what its Python counterpart records
 * in `run.note(...)`, so a behavioral difference fails here rather than being described.
 *
 * ScenarioDriftTest holds this class to the generated manifest, so a scenario cannot be
 * dropped or renamed without the guard failing.
 */
class NamedScenariosTest {

    /** A full turn that verifies and advances. */
    @Test
    @Scenario("confirmed-commit")
    fun confirmedCommit() {
        val harness = build()
        val session = harness.session
        val outcome = askListenGrade(harness)
        proposeSuggested(harness, outcome)
        val committed = confirmAndCommit(session)

        assertEquals(ReviewState.CONFIRMED, committed.state)
        assertEquals(SessionState.COMMITTED, session.state)
        assertEquals(BindingSessionState.COMMITTED, session.bindingState)
        assertEquals("Saved rating 3.", session.announceResult(committed).text)
        assertEquals(UtterancePurpose.ANNOUNCEMENT, session.announceResult(committed).purpose)
        session.advance()
        assertEquals(SessionState.IDLE, session.state)
        assertEquals(1, harness.collection.reviews.size)
        assertEquals(3, harness.collection.reviews.single().rating)
        assertEquals(ReviewSource.API, harness.collection.reviews.single().source)
        assertEquals(1, harness.transport.calls.size)
    }

    /** The learner changes the rating twice before it is submitted. */
    @Test
    @Scenario("precommit-correction")
    fun precommitCorrection() {
        val harness = build(grades = listOf(FakeGrader.Answer(PARTIAL)))
        val session = harness.session
        val outcome = askListenGrade(harness)

        // A partial answer proposes nothing: the learner must supply the rating.
        assertNull(outcome.proposedRating(session))
        assertTrue(session.propose(1) is ProposalOutcome.Proposed)
        assertTrue(session.correct(2) is ProposalOutcome.Proposed)
        assertTrue(session.correct(3) is ProposalOutcome.Proposed)
        val intent = checkNotNull(session.intent)
        assertEquals(3, intent.rating)
        assertEquals(listOf(1, 2), intent.corrections)
        assertTrue(harness.wroteNothing, "nothing may be written before commit")

        val committed = confirmAndCommit(session)
        assertEquals(ReviewState.CONFIRMED, committed.state)
        assertEquals(1, harness.transport.calls.size)
        assertEquals(3, harness.transport.calls.single().rating)
    }

    /** A snapshot is not a reservation: the card moved before commit. */
    @Test
    @Scenario("stale-identity-rejection")
    fun staleIdentityRejection() {
        val harness = build()
        val session = harness.session
        proposeSuggested(harness, askListenGrade(harness))
        val cardId = checkNotNull(session.card).identity.cardId

        harness.collection.nativeAnswer(cardId)
        harness.collection.rebuildQueue(listOf(1_789_414_083_109, cardId))

        val outcome = confirmAndCommit(session)
        assertEquals(ReviewState.FAILED, outcome.state)
        assertEquals(ReviewWriterFailure.STALE_IDENTITY, outcome.failure?.mode)
        assertFalse(outcome.writeAttempted)
        assertTrue(harness.wroteNothing, "no write was handed over")
        assertEquals(SessionState.STOPPED, session.state)
        assertEquals(1, harness.collection.reviews.size, "only the native write is recorded")
        assertEquals(ReviewSource.NATIVE, harness.collection.reviews.single().source)
    }

    /** maxTaken truncates the stored value; that is not a failure. */
    @Test
    @Scenario("capped-review-time")
    fun cappedReviewTime() {
        val harness = build()
        val session = harness.session
        proposeSuggested(harness, askListenGrade(harness, elapsedMs = 98_765))
        val outcome = confirmAndCommit(session)

        val stored = harness.collection.reviews.last().timeTakenMs
        assertEquals(98_765L, outcome.submittedTimeMs)
        assertEquals(60_000L, outcome.expectedStoredTimeMs)
        assertEquals(60_000L, stored)
        assertTrue(outcome.timeWasCapped)
        assertTrue(outcome.storedTimeIsExpected(stored))
        assertEquals(ReviewState.CONFIRMED, outcome.state)
    }

    /** An update count of 1 with no saved review, exactly as AV-004 observed. */
    @Test
    @Scenario("ambiguous-acknowledgement")
    fun ambiguousAcknowledgement() {
        val harness = build()
        harness.transport.anomalies.addLast(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        val session = harness.session
        proposeSuggested(harness, askListenGrade(harness))
        val outcome = confirmAndCommit(session)

        assertEquals(1, outcome.acknowledgement)
        assertEquals(ReviewState.OUTCOME_UNKNOWN, outcome.state)
        assertEquals(SessionState.OUTCOME_UNKNOWN, session.state)
        assertEquals(BindingSessionState.PAUSED, session.bindingState)
        assertTrue(checkNotNull(session.halt).reconciliationRequired)
        assertTrue(harness.collection.reviews.isEmpty(), "the acknowledged write saved nothing")

        // No replay, no advance, no success announcement, no resume.
        assertThrows(IllegalStateException::class.java) { session.writer.commit(checkNotNull(session.intent)) }
        assertThrows(IllegalStateException::class.java) { session.advance() }
        assertThrows(IllegalStateException::class.java) { session.announceResult(outcome) }
        assertThrows(IllegalStateException::class.java) { session.resume() }
        assertEquals(1, harness.transport.calls.size, "the write was never retried")

        session.reconcile(learnerConfirmedSaved = false)
        assertEquals("reconcile", session.events.last().step)
    }

    /** Five is not offered; it is rejected, never converted to Again. */
    @Test
    @Scenario("rating-out-of-range")
    fun ratingOutOfRange() {
        val harness = build()
        val session = harness.session
        askListenGrade(harness)

        val rejection = session.propose(5)
        assertTrue(rejection is ProposalOutcome.Rejected)
        assertEquals(ReviewWriterFailure.RATING_REJECTED, (rejection as ProposalOutcome.Rejected).failure.mode)
        assertEquals(SessionState.GRADING, session.state, "the window stayed open")

        // Constructed directly, the way AV-004's raw-answer path bypassed the session guard.
        val direct = ReviewIntent(checkNotNull(session.card), 5, 12_345)
        val refused = session.writer.commit(direct)
        assertEquals(ReviewState.FAILED, refused.state)
        assertEquals(ReviewWriterFailure.RATING_REJECTED, refused.failure?.mode)
        assertFalse(refused.writeAttempted)

        assertTrue(session.propose(4) is ProposalOutcome.Proposed)
        val committed = confirmAndCommit(session)
        assertEquals(ReviewState.CONFIRMED, committed.state)
        assertEquals(4, harness.collection.reviews.last().rating)
    }

    /** An empty queue ends the session; a null cursor pauses it. */
    @Test
    @Scenario("queue-exhausted-versus-null-cursor")
    fun queueExhaustedVersusNullCursor() {
        val empty = build()
        empty.collection.rebuildQueue(emptyList())
        val exhausted = empty.session.offerCard()
        assertTrue(exhausted is SessionResult.Produced && exhausted.value is QueueExhausted)
        assertEquals(SessionState.EXHAUSTED, empty.session.state)

        val disabled = build()
        disabled.provider.nextCardScript.addLast(
            Failure(CardProviderFailure.API_DISABLED, "AnkiDroid API switched off"),
        )
        val halted = disabled.session.offerCard()
        val halt = checkNotNull(halted.haltOrNull)
        assertEquals(SessionState.PAUSED, disabled.session.state)
        assertEquals(CardProviderFailure.API_DISABLED.specName, halt.reason)
        assertTrue(halt.resumable)

        // A missing deck is a third case and stops rather than pauses.
        val missing = build()
        missing.provider.nextCardScript.addLast(Failure(CardProviderFailure.DECK_MISSING, "deck gone"))
        missing.session.offerCard()
        assertEquals(SessionState.STOPPED, missing.session.state)
    }

    /** A skip halts without any write of any kind. */
    @Test
    @Scenario("skip-request")
    fun skipRequest() {
        val harness = build()
        val session = harness.session
        proposeSuggested(harness, askListenGrade(harness))
        val cardId = checkNotNull(session.card).identity.cardId
        val repsBefore = harness.collection.scheduled(cardId).state.reps

        val halt = session.requestSkip()
        assertEquals(SessionState.PAUSED, session.state)
        assertEquals("skip_requested", halt.reason)
        assertTrue(harness.wroteNothing)
        assertTrue(harness.collection.reviews.isEmpty())
        assertEquals(repsBefore, harness.collection.scheduled(cardId).state.reps, "the card is untouched")

        val exiting = build()
        proposeSuggested(exiting, askListenGrade(exiting))
        assertFalse(exiting.session.requestSkip(exitSession = true).resumable)
        assertEquals(SessionState.STOPPED, exiting.session.state)
    }

    /** After commit there is no in-app correction, only native Undo. */
    @Test
    @Scenario("native-undo-handoff")
    fun nativeUndoHandoff() {
        val harness = build()
        val session = harness.session
        proposeSuggested(harness, askListenGrade(harness))
        assertEquals(ReviewState.CONFIRMED, confirmAndCommit(session).state)

        assertThrows(IllegalStateException::class.java) { session.correct(1) }
        val halt = session.requestCorrectionAfterCommit()
        assertEquals(SessionState.STOPPED, halt.state)
        assertEquals("native_undo_handoff", halt.reason)
        assertFalse(halt.resumable)
        assertEquals(1, harness.transport.calls.size, "still exactly one write")
    }

    /** Every inconsistent post-state resolves to outcome-unknown. */
    @Test
    @Scenario("unconfirmed-write-variants")
    fun unconfirmedWriteVariants() {
        data class Case(
            val label: String,
            val anomaly: WriteAnomaly?,
            val postFailure: Failure?,
            val verification: Boolean = true,
            val expected: ReviewState = ReviewState.OUTCOME_UNKNOWN,
        )

        val cases = listOf(
            Case("explicit zero, card unchanged", WriteAnomaly.REJECT_WITH_ZERO, null, expected = ReviewState.FAILED),
            Case("null response", WriteAnomaly.NULL_RESPONSE, null),
            Case("error response", WriteAnomaly.ERROR_RESPONSE, null),
            Case("reps advanced twice", WriteAnomaly.DOUBLE_APPLY, null),
            Case("card came back suspended", WriteAnomaly.SUSPENDED_INSTEAD, null),
            Case("zero but the card changed", WriteAnomaly.ZERO_BUT_APPLIED, null),
            Case(
                "post-state unavailable", null,
                Failure(CardProviderFailure.ACCESS_DENIED, "permission revoked mid-turn"),
            ),
            Case("verification unsupported", null, null, verification = false),
        )

        for (case in cases) {
            val harness = build(capabilities = Capabilities(supportsPostWriteVerification = case.verification))
            case.anomaly?.let { harness.transport.anomalies.addLast(it) }
            val session = harness.session
            proposeSuggested(harness, askListenGrade(harness))
            case.postFailure?.let {
                harness.provider.readCardScript.addLast(null)
                harness.provider.readCardScript.addLast(it)
            }
            val outcome = confirmAndCommit(session)

            assertEquals(case.expected, outcome.state, case.label)
            if (case.expected == ReviewState.OUTCOME_UNKNOWN) {
                assertEquals(SessionState.OUTCOME_UNKNOWN, session.state, case.label)
                assertTrue(checkNotNull(session.halt).reconciliationRequired, case.label)
            } else {
                // A provable non-write pauses without an obligation to reconcile.
                assertEquals(SessionState.PAUSED, session.state, case.label)
                assertEquals(ReviewWriterFailure.WRITE_REJECTED, outcome.failure?.mode, case.label)
                assertFalse(checkNotNull(session.halt).reconciliationRequired, case.label)
            }
            assertEquals(1, harness.transport.calls.size, "${case.label}: exactly one dispatch")
        }
    }

    /** App switching and sync stop the session instead of reconciling. */
    @Test
    @Scenario("single-active-reviewer")
    fun singleActiveReviewer() {
        val pending = build()
        proposeSuggested(pending, askListenGrade(pending))
        val halt = pending.session.interrupt(Interruption.APP_SWITCH)
        assertEquals(SessionState.INTERRUPTED, pending.session.state)
        assertEquals(BindingSessionState.STOPPED, pending.session.bindingState)
        assertEquals(Interruption.APP_SWITCH.specName, halt.reason)
        assertFalse(halt.reconciliationRequired, "no write was ever handed over")
        assertTrue(pending.wroteNothing)
        assertEquals(ReviewState.FAILED, checkNotNull(pending.session.intent).state)

        val unconfirmed = build()
        unconfirmed.transport.anomalies.addLast(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        proposeSuggested(unconfirmed, askListenGrade(unconfirmed))
        confirmAndCommit(unconfirmed.session)
        val stop = unconfirmed.session.interrupt(Interruption.SYNC)
        assertEquals(SessionState.INTERRUPTED, unconfirmed.session.state)
        assertEquals(Interruption.SYNC.specName, stop.reason)
        assertTrue(stop.reconciliationRequired, "an ambiguous write still needs reconciling")
    }

    /** An exhausted grader quota never becomes a rating. */
    @Test
    @Scenario("grader-unavailable")
    fun graderUnavailable() {
        val harness = build(
            grades = listOf(FakeGrader.Answer(Failure(GraderFailure.QUOTA_EXHAUSTED, "free tier spent"))),
        )
        val session = harness.session
        val halt = checkNotNull(askListenGrade(harness).haltOrNull)

        assertEquals(SessionState.PAUSED, session.state)
        assertEquals(GraderFailure.QUOTA_EXHAUSTED.specName, halt.reason)
        assertNull(session.suggestion, "a fault leaves no suggestion behind")
        assertTrue(harness.wroteNothing)
        // The transcript is gradable, so a self-grade is one of the manual controls.
        assertTrue(AnswerRecovery.SELF_GRADE in session.recoveryOptions)

        assertTrue(session.selfGrade(2) is ProposalOutcome.Proposed)
        val outcome = confirmAndCommit(session, ConfirmationSource.SPOKEN)
        assertEquals(ReviewState.CONFIRMED, outcome.state)
        assertEquals(2, harness.collection.reviews.last().rating)
    }

    /** A denied microphone is a transport fault, not a wrong answer. */
    @Test
    @Scenario("microphone-denied")
    fun microphoneDenied() {
        val harness = build(
            transcripts = listOf(
                FakeSpeechInput.Fail(Failure(SpeechInputFailure.PERMISSION_DENIED, "RECORD_AUDIO refused")),
            ),
        )
        val session = harness.session
        val halt = checkNotNull(askListenGrade(harness).haltOrNull)

        assertEquals(SessionState.PAUSED, session.state)
        assertEquals(SpeechInputFailure.PERMISSION_DENIED.specName, halt.reason)
        assertTrue(halt.resumable)
        assertNull(session.intent, "a fault proposes no rating")
        assertTrue(harness.wroteNothing)
        assertFalse(checkNotNull(session.answer).gradable, "a failure is never a transcript")
        // The card is preserved and the manual controls are explicit.
        assertEquals(listOf(AnswerRecovery.TRY_AGAIN, AnswerRecovery.TYPED_CORRECTION), session.recoveryOptions)

        session.resume()
        assertEquals(SessionState.IDLE, session.state)
        assertNull(session.card, "the snapshot is dead; the session re-queries")
    }

    /** The question side never carries the reference answer or Extra. */
    @Test
    @Scenario("question-answer-separation")
    fun questionAnswerSeparation() {
        val harness = build()
        val session = harness.session
        val outcome = askListenGrade(harness)
        val card = checkNotNull(session.card)

        val question = harness.speechOutput.attempted.first()
        assertEquals(UtterancePurpose.QUESTION, question.purpose)
        assertEquals(card.fields.prompt, question.text)
        assertFalse(question.text.contains(card.fields.referenceAnswer, ignoreCase = true))
        assertFalse(question.text.contains(card.fields.extra, ignoreCase = true))

        val context = harness.grader.seen.single()
        assertEquals(card.fields.prompt, context.prompt)
        assertEquals(ANSWER, context.learnerAnswer)
        val visible = listOf(
            context.prompt, context.referenceAnswer, context.learnerAnswer, context.language,
        ) + context.requiredConcepts + context.acceptedAnswers
        assertFalse(visible.any { it.contains(card.fields.extra) }, "Extra never reaches the grader")

        assertEquals(UtterancePurpose.REVEAL, checkNotNull(session.reveal().valueOrNull).purpose)
        assertEquals(SessionState.GRADING, session.state, "reveal returns to the state it was asked from")
        assertTrue(SessionState.REVEALING in session.visited)
        val elaboration = checkNotNull(session.reveal(includeExtra = true).valueOrNull)
        assertEquals(UtterancePurpose.ELABORATION, elaboration.purpose)
        assertEquals(card.fields.extra, elaboration.text)

        proposeSuggested(harness, outcome)
        assertEquals(ReviewState.CONFIRMED, confirmAndCommit(session).state)
    }

    /** An edit invalidates the prior suggestion, pending rating and grade callback. */
    @Test
    @Scenario("corrected-transcript-and-stale-grade")
    fun correctedTranscriptAndStaleGrade() {
        val harness = build(transcripts = listOf(FakeSpeechInput.Say("Six blocks.")))
        val session = harness.session
        askListenGrade(harness)

        val staleRequest = session.beginGrade()
        assertTrue(session.propose(1) is ProposalOutcome.Proposed)
        val staleConfirmation = confirmation(session)

        session.correctTranscript(ANSWER)
        assertNull(session.intent, "the edit discarded the pending rating")
        assertTrue(session.acceptGrade(GradingReply(staleRequest, CORRECT)) is SessionResult.Ignored)
        assertNull(session.suggestion, "the old suggestion is cleared, not re-applied")
        assertFalse(session.confirm(staleConfirmation), "a confirmation for the old revision is refused")
        assertTrue(harness.wroteNothing)

        harness.grader.script.addLast(FakeGrader.Answer(CORRECT))
        assertNotNull(session.grade().valueOrNull)
        assertTrue(session.propose(3) is ProposalOutcome.Proposed)
        assertEquals(ReviewState.CONFIRMED, confirmAndCommit(session).state)
        assertEquals(ANSWER, harness.grader.seen.last().learnerAnswer)
    }

    /** A suggestion and elapsed silence cannot submit. */
    @Test
    @Scenario("absent-confirmation")
    fun absentConfirmation() {
        val harness = build()
        val session = harness.session
        askListenGrade(harness)
        assertTrue(session.propose(3) is ProposalOutcome.Proposed)
        harness.clock.advance(60_000)

        assertThrows(IllegalStateException::class.java) { session.commit() }
        assertEquals(ReviewState.PENDING, checkNotNull(session.intent).state)
        assertEquals(SessionState.PROPOSING, session.state)
        assertTrue(harness.wroteNothing)

        // Proposing consumes the advisory suggestion rather than carrying it forward, so
        // nothing about the model's confidence survives to stand in for the learner.
        assertNull(session.suggestion)
        assertFalse(checkNotNull(session.intent).hasConfirmation())
    }

    /** A final, sufficiently confident spoken command confirms the current rating. */
    @Test
    @Scenario("spoken-confirmation")
    fun spokenConfirmation() {
        val harness = build()
        val session = harness.session
        askListenGrade(harness)
        assertTrue(session.propose(3) is ProposalOutcome.Proposed)

        val intent = checkNotNull(session.intent)
        val base = confirmation(session, ConfirmationSource.SPOKEN)
        assertFalse(session.confirm(base.copy(confidence = Confidence.LOW)), "low confidence never confirms")
        assertFalse(session.confirm(base.copy(final = false)), "a non-final command never confirms")
        assertFalse(session.confirm(base.copy(rating = 4)), "a command for another rating never confirms")
        assertFalse(session.confirm(base.copy(transcriptRevision = intent.transcriptRevision + 1)))
        assertTrue(harness.wroteNothing)

        assertEquals(ReviewState.CONFIRMED, confirmAndCommit(session, ConfirmationSource.SPOKEN).state)
        assertEquals(1, harness.transport.calls.size)
    }

    /** Partial speech then early closure requires explicit retry or learner correction. */
    @Test
    @Scenario("early-closure-and-touch-fallback")
    fun earlyClosureAndTouchFallback() {
        val harness = build()
        val session = harness.session
        check(session.offerCard() is SessionResult.Produced)
        check(session.ask() is SessionResult.Produced)
        val token = session.startAnswer()

        assertTrue(session.acceptCapture(CaptureEvent.Transcript(token, "Fi", TranscriptKind.PARTIAL)) is SessionResult.Ignored)
        val halt = checkNotNull(
            session.acceptCapture(
                CaptureEvent.Failed(
                    token,
                    Failure(SpeechInputFailure.EARLY_CLOSURE, "recognizer ended without a usable final"),
                ),
            ).haltOrNull,
        )
        assertEquals(SessionState.PAUSED, session.state)
        assertEquals(SpeechInputFailure.EARLY_CLOSURE.specName, halt.reason)

        // The final that arrives after the turn settled cannot revive it.
        assertTrue(
            session.acceptCapture(
                CaptureEvent.Transcript(token, ANSWER, confidence = Confidence.SUFFICIENT),
            ) is SessionResult.Ignored,
        )
        assertTrue(harness.grader.seen.isEmpty(), "no partial, error or stale final started grading")
        assertTrue(harness.wroteNothing)

        session.correctTranscript(ANSWER)
        assertEquals(SessionState.GRADING, session.state)
        assertTrue(session.selfGrade(3) is ProposalOutcome.Proposed)
        assertEquals(ReviewState.CONFIRMED, confirmAndCommit(session).state)
    }

    /** Interruptions cancel operations and late completions cannot advance. */
    @Test
    @Scenario("playback-and-capture-interruption")
    fun playbackAndCaptureInterruption() {
        val duringPlayback = build()
        val playbackSession = duringPlayback.session
        check(playbackSession.offerCard() is SessionResult.Produced)
        duringPlayback.speechOutput.onSpeak = { playbackSession.interrupt(Interruption.EXTERNAL_AUDIO) }
        val asked = playbackSession.ask()

        assertTrue(asked is SessionResult.Halted)
        assertTrue(duringPlayback.speechOutput.cancelled.isNotEmpty(), "playback was cancelled")
        assertEquals(SessionState.INTERRUPTED, playbackSession.state)
        assertNull(playbackSession.playbackToken, "the token was invalidated before the cancel")
        assertTrue(duringPlayback.wroteNothing)

        val duringCapture = build()
        val captureSession = duringCapture.session
        check(captureSession.offerCard() is SessionResult.Produced)
        check(captureSession.ask() is SessionResult.Produced)
        val token = captureSession.startAnswer()
        captureSession.interrupt(Interruption.LOCK)

        assertTrue(token in duringCapture.speechInput.cancelled, "capture was cancelled")
        assertEquals(SessionState.INTERRUPTED, captureSession.state)
        val late = captureSession.acceptCapture(
            CaptureEvent.Transcript(token, ANSWER, confidence = Confidence.SUFFICIENT),
        )
        assertTrue(late is SessionResult.Ignored, "a callback that arrives mid-teardown is dropped")
        assertNull(captureSession.intent)
        assertTrue(duringCapture.wroteNothing)
        assertSame(SessionState.INTERRUPTED, captureSession.state)
    }
}
