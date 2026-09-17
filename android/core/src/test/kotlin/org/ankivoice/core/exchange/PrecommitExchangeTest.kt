package org.ankivoice.core.exchange

import org.ankivoice.core.answer.AnswerRecovery
import org.ankivoice.core.commands.CommandContext
import org.ankivoice.core.commands.CommandOutcome
import org.ankivoice.core.commands.CommandRefusal
import org.ankivoice.core.commands.CommandRouter
import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.contracts.Capabilities
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.ConfirmationSource
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.RatingConfirmation
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.SpeechOutputFailure
import org.ankivoice.core.contracts.UtterancePurpose
import org.ankivoice.core.fakes.FakeCardProvider
import org.ankivoice.core.fakes.FakeClock
import org.ankivoice.core.fakes.FakeGrader
import org.ankivoice.core.fakes.FakeReviewTransport
import org.ankivoice.core.fakes.FakeReviewWriter
import org.ankivoice.core.fakes.FakeSpeechInput
import org.ankivoice.core.fakes.FakeSpeechOutput
import org.ankivoice.core.fakes.WriteAnomaly
import org.ankivoice.core.fakes.demoCollection
import org.ankivoice.core.session.ProposalOutcome
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionResult
import org.ankivoice.core.session.SessionState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * AV-019: the pre-commit exchange, against the AV-041 fakes.
 *
 * No emulator, no network and no real clock. The properties this file exists for are the
 * ones the pinned-AVD run then demonstrates against a real collection: a rating is never
 * announced without its source, **only** an accepted confirmation writes, it writes once,
 * and everything the learner is told after the write comes from the outcome the writer
 * returned.
 */
class PrecommitExchangeTest {
    private val collection = demoCollection()
    private val provider = FakeCardProvider(collection)
    private val transport = FakeReviewTransport(collection)
    private val capabilities = Capabilities(maxReviewTimeMs = collection.maxReviewTimeMs)
    private val speechOutput = FakeSpeechOutput()
    private val speechInput = FakeSpeechInput()
    private val grader = FakeGrader()
    private val clock = FakeClock()

    private val session = ReviewSession(
        provider = provider,
        speechOutput = speechOutput,
        speechInput = speechInput,
        grader = grader,
        writer = FakeReviewWriter(provider, transport, capabilities),
        capabilities = capabilities,
        clock = clock,
        sessionId = "exchange",
    )
    private val router = CommandRouter(session, speechInput)
    private val exchange = PrecommitExchange(session, speechOutput)

    private val wroteNothing: Boolean get() = transport.calls.isEmpty() && collection.reviews.isEmpty()

    private val announcements: List<String>
        get() = speechOutput.spoken.filter { it.purpose == UtterancePurpose.ANNOUNCEMENT }.map { it.text }

    /** Drive one turn to a settled, gradable answer. Nothing is graded and nothing proposed. */
    private fun answered(text: String = "Five blocks."): ReviewSession {
        session.start()
        session.offerCard()
        session.ask()
        clock.advance(12_345)
        session.startAnswer()
        val token = checkNotNull(session.answerTurn?.token)
        session.acceptCapture(CaptureEvent.Transcript(token, text, confidence = Confidence.SUFFICIENT))
        assertEquals(SessionState.GRADING, session.state)
        return session
    }

    /** An advisory label from the grader, delivered the way `:app` delivers one. */
    private fun graded(label: GradeLabel): GradingResult {
        grader.script.addLast(FakeGrader.Answer(GradingResult(label, "fixture")))
        val result = session.grade()
        assertTrue(result is SessionResult.Produced, "the fixture grade did not land: $result")
        return (result as SessionResult.Produced).value
    }

    /** Announce the grader's rating exactly as the composition root does. */
    private fun announceGrade(label: GradeLabel, source: RatingSource): ExchangeStep {
        val result = graded(label)
        val rating = result.proposedRating(checkNotNull(session.card).permittedRatings)
            ?: return exchange.abstain("the grader answered ${result.label.specName}")
        return exchange.openWithProposal(rating, source)
    }

    private fun announced(step: ExchangeStep): RatingAnnouncement {
        assertTrue(step is ExchangeStep.Announced, "expected an announcement, got $step")
        return (step as ExchangeStep.Announced).announcement
    }

    // -- the announcement, for each of the four sources ------------------------ //

    @Test
    fun `an exact rule match is announced with its rating, its source and its answer version`() {
        answered()
        val made = announced(announceGrade(GradeLabel.CORRECT, RatingSource.RULE))

        assertEquals(3, made.rating)
        assertEquals(RatingSource.RULE, made.source)
        assertEquals(1, made.transcriptRevision, "the first settled answer is revision 1")
        assertTrue(made.spoken, "the announcement was not played")
        assertEquals(UtterancePurpose.ANNOUNCEMENT, made.utterance.purpose)
        assertTrue(made.text.contains("Good is waiting"), made.text)
        assertTrue(made.text.contains("exact rule match"), made.text)
        assertTrue(made.text.contains("answer version 1"), made.text)
        assertTrue(made.text.contains("Confirm"), made.text)
        assertEquals(listOf(made.text), announcements)
        assertEquals(ReviewState.PENDING, session.intent?.state)
        assertTrue(wroteNothing)
    }

    @Test
    fun `an AI suggestion is announced as the grader's, not as a rule match`() {
        answered()
        val made = announced(announceGrade(GradeLabel.CORRECT, RatingSource.AI))

        assertEquals(RatingSource.AI, made.source)
        assertTrue(made.text.contains("AI grader"), made.text)
        assertFalse(made.text.contains("rule match"), made.text)
        assertTrue(wroteNothing)
    }

    @Test
    fun `a rating the learner names is announced as learner-named`() {
        answered()
        announceGrade(GradeLabel.PARTIAL, RatingSource.RULE)

        val made = announced(exchange.onCommand(router.touch(VoiceCommand.RATE_HARD)))

        assertEquals(2, made.rating)
        assertEquals(RatingSource.LEARNER, made.source)
        assertTrue(made.text.contains("Hard is waiting"), made.text)
        assertTrue(made.text.contains("you chose it"), made.text)
        assertEquals(ReviewState.PENDING, session.intent?.state)
        assertTrue(wroteNothing)
    }

    @Test
    fun `an abstention invites a rating and never presents one`() {
        answered()
        val made = announced(announceGrade(GradeLabel.UNCERTAIN, RatingSource.RULE))

        assertNull(made.rating)
        assertTrue(made.isAbstention)
        assertEquals(RatingSource.NONE, made.source)
        assertTrue(made.text.startsWith("No rating was suggested"), made.text)
        assertTrue(made.text.contains("Again, Hard, Good or Easy"), made.text)
        assertNull(session.intent, "an abstention proposed a rating")
        assertEquals(SessionState.GRADING, session.state)
        assertTrue(wroteNothing)
    }

    /**
     * A grader proposal the card no longer offers is not substituted, downgraded or
     * quietly converted to Again: the exchange opens with nothing pending instead.
     */
    @Test
    fun `a rating this card did not offer opens the exchange with nothing pending`() {
        collection.cards.getValue(collection.order.first()).permittedRatings = listOf(1, 2)
        answered()

        val made = announced(announceGrade(GradeLabel.CORRECT, RatingSource.RULE))

        assertTrue(made.isAbstention)
        assertNull(session.intent)
        assertTrue(wroteNothing)
    }

    // -- the abstain path ------------------------------------------------------ //

    @Test
    fun `a self-grade after a grading failure opens Announced as learner-named`() {
        answered()
        grader.script.addLast(FakeGrader.Answer(Failure(GraderFailure.PROVIDER_ERROR, "no route")))
        val failed = session.grade()
        assertTrue(failed is SessionResult.Halted)
        val abstention = announced(exchange.abstain("no grade: ${(failed as SessionResult.Halted).halt.reason}"))
        assertTrue(abstention.isAbstention)
        assertEquals(SessionState.PAUSED, session.state)
        assertTrue(AnswerRecovery.SELF_GRADE in session.recoveryOptions)
        // The abstention stands while the learner may still name a rating.
        assertEquals(abstention, exchange.position)

        assertTrue(session.selfGrade(4) is ProposalOutcome.Proposed)
        val made = announced(exchange.announceLearnerRating())

        assertEquals(4, made.rating)
        assertEquals(RatingSource.LEARNER, made.source)
        // Naming is not confirming: the separate step is still required.
        assertEquals(ReviewState.PENDING, session.intent?.state)
        assertFalse(session.intent?.hasConfirmation() == true)
        assertTrue(wroteNothing)
    }

    // -- correction ------------------------------------------------------------ //

    @Test
    fun `a correction re-announces the new rating and writes nothing`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)
        assertTrue(exchange.onCommand(router.touch(VoiceCommand.CONFIRM)) is ExchangeStep.Committed)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `correcting an announced rating replaces it, re-announces it and never commits`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)

        val corrected = announced(exchange.onCommand(router.touch(VoiceCommand.RATE_AGAIN)))

        assertEquals(1, corrected.rating)
        assertEquals(RatingSource.LEARNER, corrected.source)
        assertEquals(2, announcements.size, "the correction was not re-announced")
        assertTrue(announcements.last().contains("Again is waiting"), announcements.last())
        assertEquals(SessionState.PROPOSING, session.state)
        assertEquals(ReviewState.PENDING, session.intent?.state)
        assertTrue(wroteNothing, "a correction reached the writer")
    }

    @Test
    fun `a correction discards the confirmation collected for the rating it replaced`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)
        exchange.onCommand(router.touch(VoiceCommand.CHANGE))
        assertTrue(session.confirm(confirmationFor(session)))
        assertTrue(session.intent?.hasConfirmation() == true)

        exchange.onCommand(router.touch(VoiceCommand.RATE_HARD))

        assertFalse(session.intent?.hasConfirmation() == true, "the old confirmation survived a correction")
        assertEquals(2, session.intent?.rating)
        assertTrue(wroteNothing)
    }

    // -- the re-prompt rule ---------------------------------------------------- //

    @Test
    fun `a spoken confirmation below sufficient is re-prompted once and then sent to touch`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)
        assertEquals(CommandContext.CONFIRMATION, router.context())

        val first = exchange.onCommand(router.spoken("confirm", Confidence.LOW))
        assertTrue(first is ExchangeStep.Reprompted, "$first")
        first as ExchangeStep.Reprompted
        assertEquals(1, first.refusal)
        assertFalse(first.useTouch)
        assertTrue(first.notice.contains("Say Confirm again"), first.notice)

        val second = exchange.onCommand(router.spoken("confirm", Confidence.ABSENT))
        assertTrue(second is ExchangeStep.Reprompted, "$second")
        second as ExchangeStep.Reprompted
        assertEquals(2, second.refusal)
        assertTrue(second.useTouch)
        assertTrue(second.notice.contains("tap Confirm"), second.notice)
        assertFalse(second.notice.contains("Say Confirm again"), second.notice)

        // Neither refusal committed anything or cleared the pending rating.
        assertEquals(SessionState.PROPOSING, session.state)
        assertEquals(3, exchange.position?.rating)
        assertEquals(ReviewState.PENDING, session.intent?.state)
        assertFalse(session.intent?.hasConfirmation() == true)
        assertTrue(wroteNothing)
        // Both re-prompts were spoken, and neither replaced the announcement.
        assertEquals(3, announcements.size)
        assertEquals(3, exchange.position?.rating)
    }

    @Test
    fun `an ambiguous phrase in the confirmation context counts as a refused confirmation`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)

        val heard = router.spoken("again", Confidence.SUFFICIENT)
        assertTrue(heard is CommandOutcome.Refused && heard.reason == CommandRefusal.AMBIGUOUS, "$heard")
        val step = exchange.onCommand(heard)

        assertTrue(step is ExchangeStep.Reprompted, "$step")
        assertEquals(1, (step as ExchangeStep.Reprompted).refusal)
        assertEquals(3, exchange.position?.rating, "the pending rating was cleared")
        assertTrue(wroteNothing)
    }

    @Test
    fun `a correction starts the re-prompt count over`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)
        exchange.onCommand(router.spoken("confirm", Confidence.LOW))
        exchange.onCommand(router.spoken("confirm", Confidence.LOW))
        assertEquals(2, exchange.refusals)

        exchange.onCommand(router.touch(VoiceCommand.RATE_EASY))
        assertEquals(0, exchange.refusals)

        val again = exchange.onCommand(router.spoken("confirm", Confidence.LOW))
        assertFalse((again as ExchangeStep.Reprompted).useTouch, "the new position inherited an old refusal")
        assertTrue(wroteNothing)
    }

    // -- commit ---------------------------------------------------------------- //

    @Test
    fun `an accepted confirmation commits exactly once and is announced from the outcome`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)

        val step = exchange.onCommand(router.touch(VoiceCommand.CONFIRM))

        assertTrue(step is ExchangeStep.Committed, "$step")
        step as ExchangeStep.Committed
        assertEquals(ReviewState.CONFIRMED, step.outcome.state)
        assertEquals("Saved rating 3.", step.announcement?.text)
        assertEquals(SessionState.COMMITTED, session.state)
        assertEquals(1, transport.calls.size, "the single write was not single")
        assertEquals(3, transport.calls.single().rating)
        assertEquals(1, session.outcomes.size)
        assertTrue(announcements.last().startsWith("Saved rating 3."), announcements.last())
        assertNull(exchange.position, "a committed rating is still announced as waiting")
        assertEquals(step.outcome, exchange.settled)
    }

    @Test
    fun `a spoken confirmation at sufficient confidence commits the same way`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)

        val step = exchange.onCommand(router.spoken("confirm", Confidence.SUFFICIENT))

        assertTrue(step is ExchangeStep.Committed, "$step")
        assertEquals(ReviewState.CONFIRMED, (step as ExchangeStep.Committed).outcome.state)
        assertEquals(ConfirmationSource.SPOKEN, session.intent?.confirmation?.source)
        assertEquals(1, transport.calls.size)
    }

    // -- one committed rating per attempt -------------------------------------- //

    @Test
    fun `a duplicate confirm after commit is unavailable and no second write happens`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)
        exchange.onCommand(router.touch(VoiceCommand.CONFIRM))

        assertFalse(VoiceCommand.CONFIRM in router.available())
        val again = router.touch(VoiceCommand.CONFIRM)
        assertTrue(again is CommandOutcome.Refused, "$again")
        assertEquals(CommandRefusal.UNAVAILABLE_HERE, (again as CommandOutcome.Refused).reason)
        assertTrue(exchange.onCommand(again) is ExchangeStep.Untouched)

        assertEquals(1, transport.calls.size)
        assertEquals(1, session.outcomes.size)
        assertEquals(1, collection.reviews.size)
    }

    @Test
    fun `a rating command after commit is unavailable, so no second exchange can open`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)
        exchange.onCommand(router.touch(VoiceCommand.CONFIRM))

        for (command in listOf(
            VoiceCommand.RATE_AGAIN, VoiceCommand.RATE_HARD,
            VoiceCommand.RATE_GOOD, VoiceCommand.RATE_EASY, VoiceCommand.CHANGE,
        )) {
            val refused = router.touch(command)
            assertTrue(refused is CommandOutcome.Refused, "$command: $refused")
            assertTrue(exchange.onCommand(refused) is ExchangeStep.Untouched)
        }
        assertEquals(1, transport.calls.size)
    }

    /** A confirmation minted before a correction is a delayed, out-of-order event. */
    @Test
    fun `a confirmation carrying a superseded token never authorizes a write`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)
        val stale = confirmationFor(session)

        exchange.onCommand(router.touch(VoiceCommand.RATE_HARD))
        assertFalse(session.confirm(stale), "a superseded token was accepted")
        assertFalse(session.intent?.hasConfirmation() == true)

        val refused = router.touch(VoiceCommand.CONFIRM)
        assertTrue(refused is CommandOutcome.Executed, "a current confirmation is still possible")
        assertTrue(wroteNothing, "a rejected confirmation still reached the writer")
    }

    @Test
    fun `a confirmation carrying a stale transcript revision never authorizes a write`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)
        val forRevisionZero = confirmationFor(session)

        session.correctTranscript("Six blocks.")
        assertEquals(2, session.transcriptRevision)
        assertNull(session.intent, "the transcript edit kept the pending rating")

        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)
        assertFalse(session.confirm(forRevisionZero), "a stale revision was accepted")
        assertTrue(wroteNothing)
    }

    @Test
    fun `a transcript edit during Announced discards the rating and never re-announces the old one`() {
        answered()
        val first = announced(announceGrade(GradeLabel.CORRECT, RatingSource.RULE))
        assertTrue(session.confirm(confirmationFor(session)))
        assertTrue(session.intent?.hasConfirmation() == true)

        session.correctTranscript("Six blocks, roughly.")

        assertNull(session.intent, "the pending rating survived the edit")
        assertNull(exchange.position, "the old announcement still applies to a new revision")
        assertEquals(SessionState.GRADING, session.state)

        val second = announced(announceGrade(GradeLabel.CORRECT, RatingSource.RULE))
        assertEquals(2, second.transcriptRevision)
        assertTrue(second.text.contains("answer version 2"), second.text)
        assertEquals(1, first.transcriptRevision, "the edit rewrote the earlier announcement")
        assertTrue(wroteNothing)
    }

    // -- what is never a confirmation ------------------------------------------ //

    @Test
    fun `silence leaves the pending rating waiting and never expires into a write`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)

        clock.advance(24 * 60 * 60 * 1_000)
        assertEquals(SessionResult.Ignored, session.poll())

        assertEquals(SessionState.PROPOSING, session.state)
        assertEquals(3, exchange.position?.rating)
        assertEquals(ReviewState.PENDING, session.intent?.state)
        assertFalse(session.intent?.hasConfirmation() == true)
        assertTrue(wroteNothing)
    }

    @Test
    fun `a recognizer failure in the exchange pauses with the card kept and writes nothing`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)
        speechInput.script.addLast(FakeSpeechInput.Fail(Failure(SpeechInputFailure.NO_MATCH, "nothing heard")))

        val step = exchange.onCommand(router.listenForCommand())

        assertTrue(step is ExchangeStep.Untouched, "$step")
        assertEquals(SessionState.PAUSED, session.state)
        assertNull(exchange.position, "a cancelled rating is still shown as waiting")
        assertNotNull(session.card)
        assertTrue(wroteNothing)
    }

    @Test
    fun `an announcement that cannot be spoken keeps the pending rating`() {
        answered()
        speechOutput.script.addLast(
            FakeSpeechOutput.Fail(Failure(SpeechOutputFailure.PLAYBACK_INTERRUPTED, "playback was cut off")),
        )
        val made = announced(announceGrade(GradeLabel.CORRECT, RatingSource.RULE))

        assertFalse(made.spoken, "a failed playback was reported as spoken")
        assertTrue(made.text.contains("Good is waiting"), made.text)
        assertEquals(SessionState.PROPOSING, session.state)
        assertEquals(3, exchange.position?.rating)
        assertTrue(wroteNothing)
    }

    @Test
    fun `an announcement is refused while the microphone is open`() {
        session.start()
        session.offerCard()
        session.ask()
        session.startAnswer()

        val refused = assertThrows(IllegalStateException::class.java) {
            exchange.abstain("nothing yet")
        }
        assertTrue(refused.message?.contains("open microphone") == true, refused.message)
        assertTrue(wroteNothing)
    }

    // -- the three outcome classes --------------------------------------------- //

    @Test
    fun `a write that provably did not land says nothing was saved`() {
        transport.anomalies.addLast(WriteAnomaly.REJECT_WITH_ZERO)
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)

        val step = exchange.onCommand(router.touch(VoiceCommand.CONFIRM)) as ExchangeStep.Committed

        assertEquals(ReviewState.FAILED, step.outcome.state)
        assertNull(step.announcement, "a failed write was announced as saved")
        assertTrue(step.notice.startsWith("Nothing was saved."), step.notice)
        assertTrue(step.notice.contains(step.outcome.reason), step.notice)
        assertTrue(session.halted)
        assertEquals(0, collection.reviews.size)
        assertFalse(announcements.any { it.startsWith("Saved") })
    }

    @Test
    fun `an unconfirmable write halts and only the learner's report clears it`() {
        transport.anomalies.addLast(WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE)
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)

        val step = exchange.onCommand(router.touch(VoiceCommand.CONFIRM)) as ExchangeStep.Committed

        assertEquals(ReviewState.OUTCOME_UNKNOWN, step.outcome.state)
        assertNull(step.announcement)
        assertTrue(step.notice.contains("could not confirm"), step.notice)
        assertTrue(step.notice.contains("will not send this review again"), step.notice)
        assertEquals(SessionState.OUTCOME_UNKNOWN, session.state)
        assertTrue(session.halt?.reconciliationRequired == true)
        // No command may leave this halt, so nothing can go around the reconcile.
        assertEquals(CommandContext.UNAVAILABLE, router.context())
        assertFalse(announcements.any { it.startsWith("Saved") })

        session.reconcile(learnerConfirmedSaved = true)

        assertEquals("reconcile", session.events.last().step)
        assertTrue(session.events.last().detail.contains("saved"))
        assertEquals(1, transport.calls.size, "reconciling retried the write")
    }

    // -- the post-commit handoff ------------------------------------------------ //

    @Test
    fun `the undo handoff stops the session, writes nothing and resumes nothing`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)
        exchange.onCommand(router.touch(VoiceCommand.CONFIRM))
        val writes = transport.calls.size

        val halt = session.requestCorrectionAfterCommit()

        assertEquals("native_undo_handoff", halt.reason)
        assertEquals(SessionState.STOPPED, session.state)
        assertFalse(halt.resumable, "a stopped handoff offered a resume")
        assertTrue(halt.detail.contains("AnkiDroid's Undo"), halt.detail)
        assertEquals(writes, transport.calls.size, "the handoff wrote something")
        assertEquals(1, collection.reviews.size)
        assertFalse(VoiceCommand.RESUME in router.available())
    }

    @Test
    fun `only a confirmed review may advance, and advancing clears the outcome`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)
        exchange.onCommand(router.touch(VoiceCommand.CONFIRM))
        assertNotNull(exchange.settled)

        session.advance()

        assertEquals(SessionState.IDLE, session.state)
        assertNull(exchange.settled, "the previous card's outcome outlived its turn")
        assertNull(exchange.position)
        assertEquals(1, transport.calls.size)
    }

    private fun confirmationFor(session: ReviewSession): RatingConfirmation {
        val intent = checkNotNull(session.intent)
        return RatingConfirmation(
            token = checkNotNull(intent.token),
            identity = intent.cardSnapshot.identity,
            rating = intent.rating,
            transcriptRevision = intent.transcriptRevision,
            source = ConfirmationSource.TOUCH,
        )
    }

    /** Guard: a foreign token can never be mistaken for the session's own playback. */
    @Test
    fun `announcement playback is namespaced away from the session's own`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)
        assertNull(session.playbackToken, "an announcement resolved as session playback")
        val foreign = OperationToken("exchange/announcement", 0, 1)
        assertNotEquals(session.captureToken, foreign)
    }
}
