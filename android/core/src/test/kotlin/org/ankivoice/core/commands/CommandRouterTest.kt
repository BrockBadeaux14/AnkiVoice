package org.ankivoice.core.commands

import org.ankivoice.core.answer.AnswerPhase
import org.ankivoice.core.answer.AnswerStatus
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.ConfirmationSource
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.RatingConfirmation
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.TranscriptKind
import org.ankivoice.core.contracts.UtterancePurpose
import org.ankivoice.core.fakes.FakeSpeechInput
import org.ankivoice.core.fakes.WriteAnomaly
import org.ankivoice.core.session.ANSWER
import org.ankivoice.core.session.Harness
import org.ankivoice.core.session.ProposalOutcome
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionResult
import org.ankivoice.core.session.SessionState
import org.ankivoice.core.session.askListenGrade
import org.ankivoice.core.session.build
import org.ankivoice.core.session.confirmAndCommit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AV-014's router against the AV-041 fakes: no emulator, no network and no real clock.
 *
 * Two properties are asserted everywhere rather than in one place, because they are the
 * ones a defect would quietly break: **no command path writes a review**, and a command
 * word spoken inside AV-012's answer window is graded as the answer text it is.
 */
class CommandRouterTest {

    /**
     * A recognizer that answers whatever token it is handed, at a chosen confidence.
     *
     * [FakeSpeechInput.Say] always reports sufficient confidence, and a command capture's
     * token is minted inside the router, so a scripted `Deliver` cannot name it in advance.
     */
    private class ConfidenceMic(
        private val text: String,
        private val confidence: Confidence,
    ) : FakeSpeechInput() {
        override fun listen(token: OperationToken, language: String): CaptureEvent {
            languages += language
            return CaptureEvent.Transcript(token, text, confidence = confidence)
        }
    }

    /** A session, its command microphone and the router over both. */
    private class Rig(
        val harness: Harness,
        commands: List<FakeSpeechInput.Step> = emptyList(),
        /**
         * The command capture's own transport. The app passes the one AV-025 transport for
         * both; keeping them apart here scripts commands independently of answers.
         */
        val mic: FakeSpeechInput = FakeSpeechInput(*commands.toTypedArray()),
    ) {
        val session: ReviewSession get() = harness.session
        val router = CommandRouter(session, mic)

        /** Offer a card and play the Prompt, leaving AV-012 waiting for a Start answer. */
        fun thinking(): Rig {
            check(session.offerCard() is SessionResult.Produced) { "no card was offered" }
            check(session.ask() is SessionResult.Produced) { "the prompt did not play" }
            return this
        }

        /** Through to a gradable transcript and an advisory suggestion. */
        fun grading(): Rig {
            check(askListenGrade(harness).gradeOrNull != null) { "the scripted turn did not grade" }
            return this
        }

        /** Through to the pre-commit exchange with a pending rating. */
        fun proposing(rating: Int = 3): Rig {
            grading()
            check(session.propose(rating) is ProposalOutcome.Proposed) { "rating $rating was refused" }
            return this
        }

        val wroteNothing: Boolean get() = harness.wroteNothing && harness.collection.reviews.isEmpty()
    }

    private fun rig(
        transcripts: List<FakeSpeechInput.Step> = listOf(FakeSpeechInput.Say(ANSWER)),
        commands: List<FakeSpeechInput.Step> = emptyList(),
        mic: FakeSpeechInput = FakeSpeechInput(*commands.toTypedArray()),
    ) = Rig(build(transcripts = transcripts), commands, mic)

    /** Command words inside ordinary answers, replayed through a real turn. */
    private val falseTriggers = listOf(
        "repeat the experiment three times",
        "pause the reaction before adding the acid",
        "skip a generation, so the trait reappears in the grandchildren",
        "again",
        "good",
        "skip",
        "yes",
    )

    // -- the context rule ------------------------------------------------------- //

    /**
     * The false-trigger set, through the session rather than the parser: an answer that
     * contains a command word must reach the grader verbatim and execute nothing.
     */
    @Test
    fun `a command word spoken inside the answer window is graded as answer text`() {
        for (phrase in falseTriggers) {
            val rig = rig(transcripts = listOf(FakeSpeechInput.Say(phrase))).thinking()
            val token = rig.session.startAnswer()
            assertEquals(CommandContext.ANSWER, rig.router.context(), phrase)
            assertEquals(emptyList<VoiceCommand>(), rig.router.spokenAvailable(), phrase)

            val heard = rig.router.spoken(phrase, Confidence.SUFFICIENT)
            assertEquals(phrase, assertIs<CommandOutcome.AnswerText>(heard, phrase).text)

            rig.session.acceptCapture(CaptureEvent.Transcript(token, phrase, confidence = Confidence.SUFFICIENT))
            assertEquals(SessionState.GRADING, rig.session.state, phrase)
            assertEquals(phrase, rig.session.answer?.text, phrase)

            rig.session.grade()
            assertEquals(phrase, rig.harness.grader.seen.single().learnerAnswer, phrase)
            assertNull(rig.session.halt, phrase)
            assertTrue(
                rig.harness.speechOutput.spoken.none { it.purpose != UtterancePurpose.QUESTION },
                "nothing was revealed for \"$phrase\"",
            )
            assertTrue(rig.wroteNothing, phrase)
        }
    }

    /** A second capture inside the answer window would be the overlap AV-025 forbids. */
    @Test
    fun `a command capture is refused while an attempt is in flight`() {
        val rig = rig(commands = listOf(FakeSpeechInput.Say("skip"))).thinking()
        rig.session.startAnswer()

        val refused = assertIs<CommandOutcome.Refused>(rig.router.listenForCommand())
        assertEquals(CommandRefusal.IN_ANSWER_WINDOW, refused.reason)
        assertEquals(emptyList<String>(), rig.mic.languages, "the command microphone never opened")
        assertEquals(SessionState.LISTENING, rig.session.state)
        assertTrue(rig.wroteNothing)
    }

    /** An outcome-unknown session owes a reconciliation, and no command may leave it. */
    @Test
    fun `no command resolves while a write outcome is unknown`() {
        val rig = rig().proposing()
        rig.harness.transport.anomalies.addLast(WriteAnomaly.NULL_RESPONSE)
        val outcome = confirmAndCommit(rig.session)
        assertEquals(ReviewState.OUTCOME_UNKNOWN, outcome.state)
        assertEquals(SessionState.OUTCOME_UNKNOWN, rig.session.state)

        assertEquals(CommandContext.UNAVAILABLE, rig.router.context())
        assertEquals(emptyList<VoiceCommand>(), rig.router.available())
        for (command in VoiceCommand.entries) {
            val refused = assertIs<CommandOutcome.Refused>(rig.router.touch(command), command.specName)
            assertEquals(CommandRefusal.UNAVAILABLE_HERE, refused.reason, command.specName)
        }
        assertEquals(CommandRefusal.UNAVAILABLE_HERE, assertIs<CommandOutcome.Refused>(rig.router.listenForCommand()).reason)
        // The single write was the commit's, and no command added another.
        assertEquals(1, rig.harness.transport.calls.size)
        assertTrue(rig.session.halt?.reconciliationRequired == true)
    }

    // -- each command ------------------------------------------------------------ //

    @Test
    fun `repeat replays the prompt and nothing else`() {
        val rig = rig(commands = listOf(FakeSpeechInput.Say("repeat the question"))).thinking()
        val prompt = checkNotNull(rig.session.card).fields.prompt

        val executed = assertIs<CommandOutcome.Executed>(rig.router.listenForCommand())
        assertEquals(VoiceCommand.REPEAT, executed.command)
        assertEquals(ConfirmationSource.SPOKEN, executed.source)
        assertEquals(
            listOf(prompt, prompt),
            rig.harness.speechOutput.spoken.map { it.text },
            "the prompt was spoken once by ask and once by repeat",
        )
        assertTrue(rig.harness.speechOutput.spoken.all { it.purpose == UtterancePurpose.QUESTION })
        // Transient playback: the turn is back where it was, with no attempt opened.
        assertEquals(SessionState.LISTENING, rig.session.state)
        assertEquals(AnswerPhase.THINKING, rig.session.answerTurn?.phase)
        assertEquals(0, rig.session.answerTurn?.attempt)
        assertTrue(rig.wroteNothing)
    }

    @Test
    fun `reveal speaks the reference answer only after an answer exists`() {
        val waiting = rig().thinking()
        assertFalse(VoiceCommand.REVEAL in waiting.router.available(), "nothing to reveal yet")
        assertEquals(
            CommandRefusal.UNAVAILABLE_HERE,
            assertIs<CommandOutcome.Refused>(waiting.router.touch(VoiceCommand.REVEAL)).reason,
        )
        assertTrue(waiting.wroteNothing)

        val rig = rig().grading()
        assertTrue(VoiceCommand.REVEAL in rig.router.available())
        assertIs<CommandOutcome.Executed>(rig.router.touch(VoiceCommand.REVEAL))
        assertEquals(
            listOf(UtterancePurpose.REVEAL),
            rig.harness.speechOutput.spoken.filter { it.purpose != UtterancePurpose.QUESTION }.map { it.purpose },
        )
        assertEquals(SessionState.GRADING, rig.session.state, "reveal returns to the state it was asked from")
        assertTrue(rig.wroteNothing)
    }

    /** AV-004 found no non-mutating skip, so a skip request halts and leaves the card alone. */
    @Test
    fun `skip writes nothing, rates nothing and leaves scheduling untouched`() {
        val rig = rig().grading()
        val card = checkNotNull(rig.session.card)
        val before = rig.harness.collection.scheduled(card.identity.cardId)

        val executed = assertIs<CommandOutcome.Executed>(rig.router.touch(VoiceCommand.SKIP))
        assertEquals(VoiceCommand.SKIP, executed.command)
        assertEquals(SessionState.PAUSED, rig.session.state)
        assertEquals("skip_requested", rig.session.halt?.reason)
        assertEquals(before, rig.harness.collection.scheduled(card.identity.cardId), "scheduling moved")
        assertEquals(
            listOf(card.identity.cardId, 1_789_414_083_109L),
            rig.harness.collection.order,
            "the queue was reordered",
        )
        assertNull(rig.session.intent, "a skip invented a rating")
        assertTrue(rig.wroteNothing)
    }

    @Test
    fun `finish session halts without a write`() {
        val rig = rig().grading()
        val executed = assertIs<CommandOutcome.Executed>(rig.router.touch(VoiceCommand.FINISH_SESSION))
        assertEquals(VoiceCommand.FINISH_SESSION, executed.command)
        assertEquals(SessionState.STOPPED, rig.session.state)
        assertEquals("session_finished", rig.session.halt?.reason)
        assertFalse(rig.session.halt?.reconciliationRequired == true)
        assertTrue(rig.wroteNothing)
    }

    // -- pause and resume --------------------------------------------------------- //

    @Test
    fun `pause during capture cancels the attempt and keeps partial text out`() {
        val rig = rig().thinking()
        val token = rig.session.startAnswer()
        rig.session.acceptCapture(
            CaptureEvent.Transcript(token, "five blo", TranscriptKind.PARTIAL, Confidence.SUFFICIENT),
        )
        assertEquals("five blo", rig.session.answerTurn?.partialText)

        val executed = assertIs<CommandOutcome.Executed>(rig.router.touch(VoiceCommand.PAUSE))
        assertTrue(executed.notice.contains("cancelled"), executed.notice)
        assertEquals(SessionState.PAUSED, rig.session.state)
        assertEquals(AnswerStatus.CANCELLED, rig.session.answer?.status)
        assertEquals("", rig.session.answer?.text, "partial text became an answer")
        assertFalse(rig.session.answer?.gradable == true)
        assertTrue(token in rig.harness.speechInput.cancelled, "the recognizer was not released")
        assertNotNull(rig.session.card, "the card was dropped")
        assertTrue(rig.wroteNothing)
    }

    @Test
    fun `pause outside capture keeps the card and opens no idle listening`() {
        val rig = rig().thinking()
        assertIs<CommandOutcome.Executed>(rig.router.touch(VoiceCommand.PAUSE))

        assertEquals(SessionState.PAUSED, rig.session.state)
        assertEquals("learner_paused", rig.session.halt?.reason)
        assertTrue(rig.session.halt?.resumable == true)
        assertNull(rig.session.answer)
        assertNotNull(rig.session.card)
        // No re-arm and no idle open recognizer: the only listening this session ever did
        // was the prompt playback, and no capture was opened at all.
        assertEquals(emptyList<String>(), rig.harness.speechInput.languages)
        assertEquals(emptyList<String>(), rig.mic.languages)
        assertTrue(rig.wroteNothing)
    }

    /**
     * #14's shipped `resume()` discards the turn because a paused snapshot may have been
     * overtaken by a native or sync write. AV-014 is aligned to that and says so out loud.
     */
    @Test
    fun `resume discards the turn, re-queries and says so, without writing`() {
        val rig = rig().grading()
        val before = checkNotNull(rig.session.card)
        assertIs<CommandOutcome.Executed>(rig.router.touch(VoiceCommand.PAUSE))

        val executed = assertIs<CommandOutcome.Executed>(rig.router.touch(VoiceCommand.RESUME))
        assertEquals(VoiceCommand.RESUME, executed.command)
        assertTrue(executed.notice.contains("discarded"), executed.notice)
        assertTrue(executed.notice.contains("read again"), executed.notice)

        val offered = assertIs<ScheduledCard>(executed.offered)
        assertEquals(before.identity, offered.identity, "the still-due card came back")
        assertNull(rig.session.answer, "the discarded transcript survived")
        assertNull(rig.session.suggestion, "the discarded suggestion survived")
        assertEquals(0, rig.session.transcriptRevision)
        assertEquals(SessionState.ASKING, rig.session.state)
        assertNull(rig.session.halt)
        assertTrue(rig.wroteNothing)
    }

    @Test
    fun `resume is a touch control, and a spoken resume runs nothing`() {
        val rig = rig(commands = listOf(FakeSpeechInput.Say("resume"))).thinking()
        assertIs<CommandOutcome.Executed>(rig.router.touch(VoiceCommand.PAUSE))

        val refused = assertIs<CommandOutcome.Refused>(rig.router.listenForCommand())
        assertEquals(CommandRefusal.TOUCH_ONLY, refused.reason)
        assertEquals(VoiceCommand.RESUME, refused.command)
        assertEquals(SessionState.PAUSED, rig.session.state, "a spoken resume resumed the session")

        assertIs<CommandOutcome.Executed>(rig.router.touch(VoiceCommand.RESUME))
        assertEquals(SessionState.ASKING, rig.session.state)
        assertTrue(rig.wroteNothing)
    }

    // -- ratings and confirmation --------------------------------------------------- //

    @Test
    fun `a spoken rating is a proposal, never a commit`() {
        val rig = rig(commands = listOf(FakeSpeechInput.Say("rate good"))).grading()

        val executed = assertIs<CommandOutcome.Executed>(rig.router.listenForCommand())
        assertEquals(VoiceCommand.RATE_GOOD, executed.command)
        assertEquals(SessionState.PROPOSING, rig.session.state)

        val intent = checkNotNull(rig.session.intent)
        assertEquals(3, intent.rating)
        assertEquals(ReviewState.PENDING, intent.state)
        assertFalse(intent.hasConfirmation(), "a spoken rating confirmed itself")
        assertTrue(rig.wroteNothing)
    }

    @Test
    fun `a rating the card never offered is refused rather than converted`() {
        val rig = rig()
        // Narrow the stored card before it is offered, so the snapshot the router reads
        // carries the ratings this card actually permits.
        rig.harness.collection.cards.values.forEach { it.permittedRatings = listOf(1, 3) }
        rig.grading()
        assertEquals(listOf(1, 3), rig.session.card?.permittedRatings)

        assertFalse(VoiceCommand.RATE_HARD in rig.router.available())
        val refused = assertIs<CommandOutcome.Refused>(rig.router.touch(VoiceCommand.RATE_HARD))
        assertEquals(CommandRefusal.UNAVAILABLE_HERE, refused.reason)
        assertNull(rig.session.intent, "a refused rating opened a review anyway")
        assertTrue(rig.wroteNothing)
    }

    @Test
    fun `confirm authorizes the pending rating and still writes nothing`() {
        val rig = rig(commands = listOf(FakeSpeechInput.Say("confirm"))).proposing()
        assertEquals(CommandContext.CONFIRMATION, rig.router.context())

        val executed = assertIs<CommandOutcome.Executed>(rig.router.listenForCommand())
        assertEquals(VoiceCommand.CONFIRM, executed.command)
        val intent = checkNotNull(rig.session.intent)
        assertTrue(intent.hasConfirmation())
        assertEquals(ConfirmationSource.SPOKEN, intent.confirmation?.source)
        assertEquals(ReviewState.PENDING, intent.state, "confirming submitted the review")
        assertTrue(rig.wroteNothing)
    }

    @Test
    fun `a spoken confirmation below sufficient confidence is not a confirmation`() {
        val rig = rig(commands = listOf(FakeSpeechInput.Say("confirm"))).proposing()
        val intent = checkNotNull(rig.session.intent)

        for (confidence in listOf(Confidence.LOW, Confidence.ABSENT)) {
            val refused = assertIs<CommandOutcome.Refused>(rig.router.spoken("confirm", confidence))
            assertEquals(CommandRefusal.LOW_CONFIDENCE, refused.reason, confidence.specName)
            assertFalse(intent.hasConfirmation(), confidence.specName)
        }
        // Defence in depth: even a hand-built spoken event below sufficient is rejected by
        // the intent itself, not only by this router's gate.
        assertFalse(
            rig.session.confirm(
                RatingConfirmation(
                    token = checkNotNull(intent.token),
                    identity = intent.cardSnapshot.identity,
                    rating = intent.rating,
                    transcriptRevision = intent.transcriptRevision,
                    source = ConfirmationSource.SPOKEN,
                    confidence = Confidence.LOW,
                ),
            ),
        )
        assertTrue(rig.wroteNothing)
    }

    @Test
    fun `change reopens the choice and a new rating replaces the confirmation`() {
        val rig = rig().proposing(rating = 3)
        assertIs<CommandOutcome.Executed>(rig.router.touch(VoiceCommand.CONFIRM))
        assertTrue(checkNotNull(rig.session.intent).hasConfirmation())

        assertIs<CommandOutcome.Executed>(rig.router.touch(VoiceCommand.CHANGE))
        assertEquals(SessionState.PROPOSING, rig.session.state, "change left the exchange")
        assertEquals(3, rig.session.intent?.rating, "change rated the card by itself")

        assertIs<CommandOutcome.Executed>(rig.router.touch(VoiceCommand.RATE_HARD))
        val intent = checkNotNull(rig.session.intent)
        assertEquals(2, intent.rating)
        assertFalse(intent.hasConfirmation(), "the old confirmation still authorized the new rating")
        assertTrue(rig.wroteNothing)
    }

    // -- recognition that goes wrong ------------------------------------------------- //

    @Test
    fun `an unrecognized phrase re-prompts and changes nothing`() {
        val rig = rig(commands = listOf(FakeSpeechInput.Say("what was that about again please"))).grading()
        val state = rig.session.state

        val refused = assertIs<CommandOutcome.Refused>(rig.router.listenForCommand())
        assertEquals(CommandRefusal.NOT_A_COMMAND, refused.reason)
        assertTrue(refused.notice.contains("on-screen controls"), refused.notice)
        assertEquals(state, rig.session.state)
        assertTrue(rig.wroteNothing)
    }

    @Test
    fun `an ambiguous phrase executes neither candidate`() {
        val rig = rig(commands = listOf(FakeSpeechInput.Say("again"))).proposing()

        val refused = assertIs<CommandOutcome.Refused>(rig.router.listenForCommand())
        assertEquals(CommandRefusal.AMBIGUOUS, refused.reason)
        assertNull(refused.command)
        assertEquals(SessionState.PROPOSING, rig.session.state)
        assertEquals(3, rig.session.intent?.rating, "an ambiguous phrase changed the rating")
        assertTrue(rig.wroteNothing)
    }

    @Test
    fun `low confidence never advances, reveals or rates`() {
        for (command in VoiceCommand.entries.filter { it.guarded }) {
            val phrase = COMMAND_PHRASES.entries.first { (_, set) -> set == setOf(command) }.key
            for (confidence in listOf(Confidence.LOW, Confidence.ABSENT)) {
                val label = "$phrase at ${confidence.specName}"
                // Through a real capture, so the confidence the recognizer reported is the
                // one the router gates on.
                val rig = rig(mic = ConfidenceMic(phrase, confidence)).proposing()
                val state = rig.session.state
                val spoken = rig.harness.speechOutput.spoken.size

                val refused = assertIs<CommandOutcome.Refused>(rig.router.listenForCommand(), label)
                assertEquals(CommandRefusal.LOW_CONFIDENCE, refused.reason, label)
                assertEquals(command, refused.command, label)
                assertEquals(state, rig.session.state, label)
                assertEquals(spoken, rig.harness.speechOutput.spoken.size, label)
                assertEquals(3, rig.session.intent?.rating, label)
                assertFalse(rig.session.intent?.hasConfirmation() == true, label)
                assertTrue(rig.wroteNothing, label)
            }
        }
    }

    /** The unguarded three stay usable when the recognizer vouches for nothing. */
    @Test
    fun `an unguarded command still runs at the absent confidence the pinned recognizer reports`() {
        val rig = rig(mic = ConfidenceMic("pause", Confidence.ABSENT)).thinking()
        val executed = assertIs<CommandOutcome.Executed>(rig.router.listenForCommand())
        assertEquals(VoiceCommand.PAUSE, executed.command)
        assertEquals(SessionState.PAUSED, rig.session.state)
        assertTrue(rig.wroteNothing)
    }

    /** A recognizer fault is never an answer, a rating or a command. */
    @Test
    fun `a command recognition failure pauses with the card preserved`() {
        val failure = Failure(SpeechInputFailure.RECOGNIZER_ERROR, "code 5")
        val rig = rig(commands = listOf(FakeSpeechInput.Fail(failure))).grading()
        val card = checkNotNull(rig.session.card)

        val refused = assertIs<CommandOutcome.Refused>(rig.router.listenForCommand())
        assertEquals(CommandRefusal.RECOGNITION_FAILED, refused.reason)
        assertEquals(SessionState.PAUSED, rig.session.state)
        assertEquals(SpeechInputFailure.RECOGNIZER_ERROR.specName, rig.session.halt?.reason)
        assertEquals(card.identity, rig.session.card?.identity, "the card was dropped")
        assertNull(rig.session.intent)
        assertTrue(rig.wroteNothing)
    }

    /** A late event from another operation must never execute a command. */
    @Test
    fun `a capture event carrying a stale token executes nothing`() {
        val stale = OperationToken("elsewhere", 9, 9)
        val rig = rig(
            commands = listOf(
                FakeSpeechInput.Deliver(
                    CaptureEvent.Transcript(stale, "skip", confidence = Confidence.SUFFICIENT),
                ),
            ),
        ).grading()

        val refused = assertIs<CommandOutcome.Refused>(rig.router.listenForCommand())
        assertEquals(CommandRefusal.STALE_CAPTURE, refused.reason)
        assertEquals(SessionState.GRADING, rig.session.state)
        assertTrue(rig.wroteNothing)
    }

    // -- the whole surface ------------------------------------------------------------ //

    /**
     * A command capture is tagged as one: it mints a namespaced token of its own, opens no
     * AV-012 attempt, raises no revision and never reaches the grader.
     */
    @Test
    fun `a command capture never produces an answer and never reaches grading`() {
        val rig = rig(commands = listOf(FakeSpeechInput.Say("pause"))).thinking()

        assertIs<CommandOutcome.Executed>(rig.router.listenForCommand())
        val token = rig.router.captures.single()
        assertEquals("${rig.session.sessionId}/command", token.sessionId)
        assertNull(rig.session.answer)
        assertEquals(0, rig.session.answerTurn?.attempt)
        assertEquals(0, rig.session.transcriptRevision)
        assertEquals(emptyList<Any>(), rig.harness.grader.requests)
        assertTrue(rig.wroteNothing)
    }

    /** Voice-first with touch fallback: no command is voice-only. */
    @Test
    fun `every command is reachable by touch somewhere in a session`() {
        val offered = mutableSetOf<VoiceCommand>()
        offered += rig().thinking().router.available()
        offered += rig().grading().router.available()
        offered += rig().proposing().router.available()
        val paused = rig().grading()
        paused.router.touch(VoiceCommand.PAUSE)
        offered += paused.router.available()
        val confirmed = rig().proposing()
        confirmed.router.touch(VoiceCommand.CONFIRM)
        offered += confirmed.router.available()

        assertEquals(VoiceCommand.entries.toSet(), offered, "these commands have no touch control")
    }

    /**
     * The sweep. Every command, from every position a session can reach, by touch and by
     * voice — and the transport is never called once.
     */
    @Test
    fun `no command path writes a review`() {
        val positions = listOf<Pair<String, (Rig) -> Unit>>(
            "idle" to { },
            "thinking" to { it.thinking() },
            "capturing" to { it.thinking().session.startAnswer() },
            "grading" to { it.grading() },
            "proposing" to { it.proposing() },
            "confirmed" to {
                it.proposing()
                it.router.touch(VoiceCommand.CONFIRM)
            },
            "paused" to {
                it.grading()
                it.router.touch(VoiceCommand.PAUSE)
            },
            "stopped" to {
                it.grading()
                it.router.touch(VoiceCommand.FINISH_SESSION)
            },
        )
        for ((label, position) in positions) {
            for (command in VoiceCommand.entries) {
                val byTouch = rig()
                position(byTouch)
                byTouch.router.touch(command)
                assertTrue(byTouch.wroteNothing, "$label / ${command.specName} by touch")

                val phrase = COMMAND_PHRASES.entries.first { (_, set) -> command in set }.key
                val bySpeech = rig(commands = listOf(FakeSpeechInput.Say(phrase)))
                position(bySpeech)
                bySpeech.router.listenForCommand()
                assertTrue(bySpeech.wroteNothing, "$label / ${command.specName} by voice")
            }
        }
    }
}
