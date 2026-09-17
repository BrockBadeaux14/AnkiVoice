package org.ankivoice.core.exchange

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
import org.ankivoice.core.contracts.GuardedReviewWriter
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.RatingConfirmation
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.ReviewWriterFailure
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.contracts.UtterancePurpose
import org.ankivoice.core.fakes.FakeCardProvider
import org.ankivoice.core.fakes.FakeClock
import org.ankivoice.core.fakes.FakeGrader
import org.ankivoice.core.fakes.FakeJournalStore
import org.ankivoice.core.fakes.FakeReviewTransport
import org.ankivoice.core.fakes.FakeSpeechInput
import org.ankivoice.core.fakes.FakeSpeechOutput
import org.ankivoice.core.fakes.demoCollection
import org.ankivoice.core.journal.JournalPhase
import org.ankivoice.core.journal.JournalRequest
import org.ankivoice.core.journal.JournaledReviewWriter
import org.ankivoice.core.journal.ReviewJournal
import org.ankivoice.core.session.Interruption
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionResult
import org.ankivoice.core.session.SessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AV-047: automatic grading, against the AV-041 fakes and the writer that ships.
 *
 * The card reverses AV-007's "every rating requires an explicit learner confirmation" for
 * grader proposals alone, so these tests are written around what it did **not** reverse.
 * The writer's guard is the same guard; the confirmation is a named source and not a
 * forged touch; a learner's own rating, an abstention and a grading failure still wait;
 * and the cancel window is a real chance to stop a write and not a decoration.
 *
 * The real [GuardedReviewWriter] is used, wrapped in AV-018's journal exactly as the
 * composition root wraps it, so "the write was accepted" here means the shipped guard
 * accepted it and the durable record says who authorized it.
 */
class AutomaticGradingTest {
    private val collection = demoCollection()
    private val provider = FakeCardProvider(collection)
    private val transport = FakeReviewTransport(collection)
    private val capabilities = Capabilities(maxReviewTimeMs = collection.maxReviewTimeMs)
    private val speechOutput = FakeSpeechOutput()
    private val speechInput = FakeSpeechInput()
    private val grader = FakeGrader()
    private val clock = FakeClock()
    private val journal = ReviewJournal(FakeJournalStore())

    private val session = ReviewSession(
        provider = provider,
        speechOutput = speechOutput,
        speechInput = speechInput,
        grader = grader,
        writer = JournaledReviewWriter(
            GuardedReviewWriter(provider, transport, capabilities),
            journal,
            "automatic",
        ),
        capabilities = capabilities,
        clock = clock,
        sessionId = "automatic",
    )
    private val router = CommandRouter(session, speechInput)
    private val exchange = PrecommitExchange(session, speechOutput, AutomaticGrading.ON)

    private val wroteNothing: Boolean get() = transport.calls.isEmpty() && collection.reviews.isEmpty()

    private val announcements: List<String>
        get() = speechOutput.spoken.filter { it.purpose == UtterancePurpose.ANNOUNCEMENT }.map { it.text }

    /** Drive one turn to a settled, gradable answer. Nothing is graded and nothing proposed. */
    private fun answered(text: String = "Five blocks.") {
        if (session.visited.size == 1) session.start()
        session.offerCard()
        session.ask()
        clock.advance(12_345)
        session.startAnswer()
        val token = checkNotNull(session.answerTurn?.token)
        session.acceptCapture(CaptureEvent.Transcript(token, text, confidence = Confidence.SUFFICIENT))
        assertEquals(SessionState.GRADING, session.state)
    }

    /** Open Announced from a grader label exactly as the composition root does. */
    private fun announceGrade(
        label: GradeLabel,
        source: RatingSource = RatingSource.RULE,
        on: PrecommitExchange = exchange,
    ): ExchangeStep {
        grader.script.addLast(FakeGrader.Answer(GradingResult(label, "fixture")))
        val graded = session.grade()
        assertTrue(graded is SessionResult.Produced, "the fixture grade did not land: $graded")
        val result = (graded as SessionResult.Produced).value
        val rating = result.proposedRating(checkNotNull(session.card).permittedRatings)
            ?: return on.abstain("the grader answered ${result.label.specName}")
        return on.openWithProposal(rating, source)
    }

    // -- off: nothing at all changes ------------------------------------------- //

    @Test
    fun `with the option off a grader proposal arms nothing and commits nothing`() {
        val manual = PrecommitExchange(session, speechOutput)
        answered()
        announceGrade(GradeLabel.CORRECT, on = manual)

        assertNull(manual.armed, "an option that is off armed an automatic commit")
        assertEquals(SessionState.PROPOSING, session.state)

        val step = manual.commitAutomatically()
        assertTrue(step is ExchangeStep.Untouched, "a disarmed exchange committed: $step")
        assertTrue(wroteNothing, "a session with the option off wrote a review")
        assertEquals(ReviewState.PENDING, session.intent?.state)
        assertTrue(journal.entries().isEmpty(), "a disarmed exchange journalled an intent")
    }

    @Test
    fun `with the option off the announcement says nothing about saving on its own`() {
        val manual = PrecommitExchange(session, speechOutput)
        answered()
        announceGrade(GradeLabel.CORRECT, on = manual)

        val said = announcements.last()
        assertTrue("Nothing is saved yet." in said, said)
        assertFalse("Automatic" in said, said)
    }

    // -- on: the two grader sources ------------------------------------------- //

    @Test
    fun `a rule-matched rating commits with no learner gesture at all`() {
        answered()
        announceGrade(GradeLabel.CORRECT, RatingSource.RULE)

        val armed = checkNotNull(exchange.armed) { "a rule match did not arm the window" }
        assertEquals(3, armed.rating)
        assertEquals(RatingSource.RULE, armed.source)
        assertEquals(AutomaticGrading.DEFAULT_CANCEL_WINDOW_MS, armed.cancelWindowMs)

        val step = exchange.commitAutomatically()
        assertTrue(step is ExchangeStep.Committed, "the armed window did not commit: $step")
        assertEquals(ReviewState.CONFIRMED, (step as ExchangeStep.Committed).outcome.state)
        assertEquals(1, transport.calls.size, "the single write did not run exactly once")
        assertEquals(3, collection.reviews.single().rating)
    }

    @Test
    fun `an AI-proposed rating commits the same way and says which source proposed it`() {
        answered()
        announceGrade(GradeLabel.INCORRECT, RatingSource.AI)

        assertEquals(RatingSource.AI, checkNotNull(exchange.armed).source)
        assertTrue(exchange.commitAutomatically() is ExchangeStep.Committed)
        assertEquals(1, collection.reviews.single().rating, "Again is what `incorrect` proposes")
        assertEquals(ConfirmationSource.AUTO, session.intent?.confirmation?.source)
    }

    @Test
    fun `the write is authorized by a bound auto confirmation and by no forged gesture`() {
        answered()
        announceGrade(GradeLabel.CORRECT)
        val intent = checkNotNull(session.intent)
        val card = checkNotNull(session.card)

        exchange.commitAutomatically()

        val event = checkNotNull(intent.confirmation)
        assertEquals(ConfirmationSource.AUTO, event.source)
        assertEquals(card.identity, event.identity)
        assertEquals(3, event.rating)
        assertEquals(intent.transcriptRevision, event.transcriptRevision)
        assertTrue(event.final)
        assertEquals(Confidence.ABSENT, event.confidence, "an automatic commit is not a recognition event")
    }

    @Test
    fun `the guard still rejects an auto confirmation that is not bound to this intent`() {
        answered()
        announceGrade(GradeLabel.CORRECT)
        val intent = checkNotNull(session.intent)
        val token = checkNotNull(intent.token)
        val identity = intent.cardSnapshot.identity
        val good = RatingConfirmation(token, identity, intent.rating, intent.transcriptRevision, ConfirmationSource.AUTO)

        val rejected = listOf(
            good.copy(rating = intent.rating + 1),
            good.copy(transcriptRevision = intent.transcriptRevision + 1),
            good.copy(token = OperationToken("other", 9, 9)),
            good.copy(final = false),
        )
        for (event in rejected) {
            assertFalse(intent.confirm(event), "the guard accepted an unbound auto confirmation: $event")
            assertFalse(intent.hasConfirmation())
        }
        assertTrue(intent.confirm(good), "the guard refused the bound auto confirmation")
    }

    @Test
    fun `the journal records an automatic commit as auto and a confirmed one as touch`() {
        answered()
        announceGrade(GradeLabel.CORRECT)
        exchange.commitAutomatically()

        val entry = journal.entries().single()
        assertEquals(ConfirmationSource.AUTO, entry.confirmationSource)
        assertEquals(JournalPhase.SETTLED, entry.phase)
        assertEquals(ReviewState.CONFIRMED, entry.outcomeState)
        assertTrue("confirmed by auto" in entry.summary(), entry.summary())

        session.advance()
        answered("Ten blocks.")
        announceGrade(GradeLabel.CORRECT)
        exchange.cancelAutomatic()
        exchange.onCommand(router.touch(VoiceCommand.CONFIRM))

        val second = journal.entries().last()
        assertEquals(ConfirmationSource.TOUCH, second.confirmationSource)
        assertEquals(ReviewState.CONFIRMED, second.outcomeState)
    }

    @Test
    fun `the announcement says a write is coming, how long there is and what undo costs`() {
        answered()
        announceGrade(GradeLabel.CORRECT)

        val said = announcements.last()
        assertTrue("Good is waiting" in said, said)
        assertTrue("from an exact rule match" in said, said)
        assertTrue("saved in 5 seconds unless you stop it" in said, said)
        assertTrue("Keep it manual" in said, said)
        assertTrue("AnkiDroid's own Undo" in said, said)
    }

    // -- what stays manual whatever the option says ---------------------------- //

    @Test
    fun `an abstention never arms and never writes`() {
        answered()
        val step = announceGrade(GradeLabel.UNCERTAIN)

        assertTrue(step is ExchangeStep.Announced)
        assertTrue((step as ExchangeStep.Announced).announcement.isAbstention)
        assertNull(exchange.armed, "an abstention armed an automatic commit")
        assertTrue(exchange.commitAutomatically() is ExchangeStep.Untouched)
        assertTrue(wroteNothing)
    }

    @Test
    fun `a grading failure never arms and never writes`() {
        answered()
        grader.script.addLast(FakeGrader.Answer(Failure(GraderFailure.PROVIDER_ERROR, "no route")))
        val graded = session.grade()
        assertTrue(graded is SessionResult.Halted, "the fixture failure did not halt the turn: $graded")
        exchange.abstain("no grade: ${(graded as SessionResult.Halted).halt.reason}")

        assertNull(exchange.armed, "a grading failure armed an automatic commit")
        assertTrue(exchange.commitAutomatically() is ExchangeStep.Untouched)
        assertTrue(wroteNothing)
    }

    @Test
    fun `a rating the learner named is theirs to confirm and is never armed`() {
        answered()
        announceGrade(GradeLabel.CORRECT)
        assertNotNull(exchange.armed, "the grader proposal did not arm")

        // A correction: the same exchange, a rating the learner chose.
        exchange.onCommand(router.touch(VoiceCommand.RATE_EASY))

        assertEquals(RatingSource.LEARNER, exchange.position?.source)
        assertNull(exchange.armed, "a correction left the automatic commit armed")
        assertTrue(exchange.commitAutomatically() is ExchangeStep.Untouched)
        assertTrue(wroteNothing, "a rating the learner named was written without a confirmation")
        assertEquals(4, session.intent?.rating)
        assertEquals(ReviewState.PENDING, session.intent?.state)
    }

    @Test
    fun `a self-grade after an abstention is never armed`() {
        answered()
        announceGrade(GradeLabel.PARTIAL)
        session.selfGrade(2)
        exchange.announceLearnerRating()

        assertEquals(RatingSource.LEARNER, exchange.position?.source)
        assertNull(exchange.armed)
        assertTrue(exchange.commitAutomatically() is ExchangeStep.Untouched)
        assertTrue(wroteNothing)
    }

    @Test
    fun `a transcript edit inside the window withdraws the rating and writes nothing`() {
        answered()
        announceGrade(GradeLabel.CORRECT)
        val revision = session.transcriptRevision
        assertNotNull(exchange.armed)

        session.correctTranscript("Five city blocks.")
        assertEquals(revision + 1, session.transcriptRevision)

        assertNull(exchange.armed, "an edit left the old revision's window armed")
        assertTrue(exchange.commitAutomatically() is ExchangeStep.Untouched)
        assertTrue(wroteNothing, "an edited answer's old rating was written")
        assertNull(session.intent, "the edit did not discard the pending rating")
    }

    @Test
    fun `an interruption inside the window leaves the card unwritten`() {
        answered()
        announceGrade(GradeLabel.CORRECT)
        session.interrupt(Interruption.APP_SWITCH)

        assertNull(exchange.armed)
        assertTrue(exchange.commitAutomatically() is ExchangeStep.Untouched)
        assertTrue(wroteNothing)
    }

    // -- cancelling ------------------------------------------------------------ //

    @Test
    fun `cancelling writes nothing and leaves the pending rating correctable`() {
        answered()
        announceGrade(GradeLabel.CORRECT)

        val step = exchange.cancelAutomatic()
        assertTrue(step is ExchangeStep.KeptManual, "cancelling did not report what it kept: $step")
        assertEquals(3, (step as ExchangeStep.KeptManual).announcement.rating)
        assertTrue("nothing was written" in step.notice, step.notice)

        assertNull(exchange.armed)
        assertTrue(wroteNothing)
        assertEquals(SessionState.PROPOSING, session.state)
        assertEquals(ReviewState.PENDING, session.intent?.state)
        assertEquals(3, exchange.position?.rating, "the pending rating left the screen")

        // Still correctable, and a manual confirmation still works afterwards.
        exchange.onCommand(router.touch(VoiceCommand.RATE_HARD))
        assertEquals(2, session.intent?.rating)
        exchange.onCommand(router.touch(VoiceCommand.CONFIRM))
        assertEquals(2, collection.reviews.single().rating)
        assertEquals(ConfirmationSource.TOUCH, journal.entries().single().confirmationSource)
    }

    @Test
    fun `a window that expires after a cancellation still writes nothing`() {
        answered()
        announceGrade(GradeLabel.CORRECT)
        exchange.cancelAutomatic()

        // The timer fired anyway: this is the call a released task makes.
        val step = exchange.commitAutomatically()

        assertTrue(step is ExchangeStep.Untouched, "a cancelled window committed: $step")
        assertTrue(wroteNothing)
        assertEquals(ReviewState.PENDING, session.intent?.state)
    }

    @Test
    fun `cancelling twice is harmless and the second call reports there was nothing to stop`() {
        answered()
        announceGrade(GradeLabel.CORRECT)
        exchange.cancelAutomatic()

        val again = exchange.cancelAutomatic()
        assertTrue(again is ExchangeStep.Untouched, again.notice)
        assertTrue("nothing to stop" in again.notice, again.notice)
        assertTrue(wroteNothing)
    }

    @Test
    fun `an automatic commit is refused after the attempt already committed`() {
        answered()
        announceGrade(GradeLabel.CORRECT)
        assertTrue(exchange.commitAutomatically() is ExchangeStep.Committed)

        val again = exchange.commitAutomatically()
        assertTrue(again is ExchangeStep.Untouched, "a committed attempt committed twice: $again")
        assertEquals(1, transport.calls.size, "the write ran more than once")
    }

    // -- the guard is not weakened -------------------------------------------- //

    @Test
    fun `an intent with an auto confirmation that was cleared is still refused by the writer`() {
        answered()
        announceGrade(GradeLabel.CORRECT)
        val intent = checkNotNull(session.intent)
        val token = checkNotNull(intent.token)
        intent.confirm(
            RatingConfirmation(token, intent.cardSnapshot.identity, intent.rating, intent.transcriptRevision, ConfirmationSource.AUTO),
        )
        // A correction clears it, exactly as it clears a spoken or touched one.
        session.correct(2)

        val outcome = GuardedReviewWriter(provider, transport, capabilities).commit(intent)

        assertEquals(ReviewState.FAILED, outcome.state)
        assertEquals(ReviewWriterFailure.CONFIRMATION_REQUIRED, outcome.failure?.mode)
        assertTrue(wroteNothing)
    }

    @Test
    fun `a journal line written before the option reads back with no confirmation source`() {
        val store = FakeJournalStore()
        val earlier = ReviewJournal(store)
        val card = checkNotNull(provider.nextCard() as? ScheduledCard)
        earlier.record(
            JournalRequest(
                sessionId = "before-av047",
                token = OperationToken("before-av047", 1, 1),
                identity = card.identity,
                rating = 3,
                elapsedMs = 1_000,
                transcriptRevision = 1,
                transcript = "Five blocks.",
                preState = card.state,
            ),
        )
        // What a line from before this field looked like: the key is absent, not null.
        store.lines[0] = store.lines[0].replace(",\"confirmation\":null", "")
        assertFalse("confirmation" in store.lines[0], store.lines[0])

        val entry = ReviewJournal(store).entries().single()

        assertNull(entry.confirmationSource, "an older line was read as a confirmation it never recorded")
        assertEquals(3, entry.rating, "the rest of the older line no longer folds")
        assertEquals(JournalPhase.DISPATCHING, entry.phase)
        assertFalse("confirmed by" in entry.summary(), entry.summary())
    }

    @Test
    fun `the option's window must be a positive duration`() {
        val thrown = runCatching { AutomaticGrading(enabled = true, cancelWindowMs = 0) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "a zero-length window was accepted: $thrown")
    }

    @Test
    fun `a one-second window is announced in the singular`() {
        val brief = PrecommitExchange(session, speechOutput, AutomaticGrading(enabled = true, cancelWindowMs = 1_000))
        answered()
        announceGrade(GradeLabel.CORRECT, on = brief)

        assertTrue("saved in 1 second unless" in announcements.last(), announcements.last())
    }
}
