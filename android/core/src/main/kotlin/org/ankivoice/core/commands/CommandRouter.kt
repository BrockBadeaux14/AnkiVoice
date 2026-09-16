package org.ankivoice.core.commands

import org.ankivoice.core.answer.AnswerPhase
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.ConfirmationSource
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.NextCardResult
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.QueueExhausted
import org.ankivoice.core.contracts.RatingConfirmation
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.contracts.SpeechInput
import org.ankivoice.core.contracts.TranscriptKind
import org.ankivoice.core.session.ProposalOutcome
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionResult
import org.ankivoice.core.session.SessionState

/** Why a command did not run. Every one of them leaves the collection untouched. */
enum class CommandRefusal(val specName: String) {
    /** AV-012's answer window is open. The utterance stays answer text. */
    IN_ANSWER_WINDOW("in-answer-window"),

    /** The whole utterance matched nothing in the vocabulary. */
    NOT_A_COMMAND("not-a-command"),

    /** The phrase means more than one command. */
    AMBIGUOUS("ambiguous"),

    /** A guarded command heard below [Confidence.SUFFICIENT]. */
    LOW_CONFIDENCE("low-confidence"),

    /** In the vocabulary, but not in this context. */
    OUT_OF_CONTEXT("out-of-context"),

    /** Only an on-screen control may run it. */
    TOUCH_ONLY("touch-only"),

    /** The session is not in a state where this command means anything. */
    UNAVAILABLE_HERE("unavailable-here"),

    /** The rating is not one this card offered. It is never converted to Again. */
    RATING_NOT_OFFERED("rating-not-offered"),

    /** The confirmation did not match the pending rating, attempt or revision. */
    CONFIRMATION_REJECTED("confirmation-rejected"),

    /** The recognizer failed. The session pauses with the card preserved. */
    RECOGNITION_FAILED("recognition-failed"),

    /** A late or out-of-turn capture event. It never executes a command. */
    STALE_CAPTURE("stale-capture"),

    /** The command started and the session halted. The card is kept. */
    HALTED("halted"),
}

/** What one command attempt produced. Nothing here reaches [ReviewSession.commit]. */
sealed interface CommandOutcome {
    /** What the learner is told. Never blank. */
    val notice: String

    data class Executed(
        val command: VoiceCommand,
        override val notice: String,
        val source: ConfirmationSource,
        /** The card [VoiceCommand.RESUME] re-queried, or null for every other command. */
        val offered: NextCardResult? = null,
    ) : CommandOutcome

    /** Nothing ran. [command] is null when no command was identified at all. */
    data class Refused(
        val command: VoiceCommand?,
        val reason: CommandRefusal,
        override val notice: String,
    ) : CommandOutcome

    /** Inside the answer window. [text] is the utterance unchanged, on its way to grading. */
    data class AnswerText(val text: String) : CommandOutcome {
        override val notice: String get() = "That was heard as part of your answer, not as a command."
    }
}

/**
 * AV-014: the command layer above AV-013's turn loop.
 *
 * It owns the command **context** rule, dispatch and the touch fallbacks. It owns no
 * session state: every effect is an existing [ReviewSession] call, and there is no code
 * path from here to [ReviewSession.commit], so **no command writes a review**. A spoken
 * rating is a proposal and a spoken confirmation only authorizes one; submitting it is
 * #27's step.
 *
 * Capture is requested through AV-007's [SpeechInput] and nothing else, so no platform
 * type appears here. A command capture is tagged as one by its namespaced token, never
 * produces an AV-012 `Answer` and never reaches grading.
 *
 * Confine it to the session's owning thread: every call reaches straight into
 * [ReviewSession], which fails loudly when called from another one.
 */
class CommandRouter(
    private val session: ReviewSession,
    private val speech: SpeechInput,
    private val language: String = session.language,
) {
    /**
     * Command captures are namespaced the way AV-012 namespaces answer captures, so a
     * command token can never equal a playback, capture, grading or proposal token. They
     * are not turn-scoped: a command capture belongs to the session, not to one card.
     */
    private val captureSessionId: String = "${session.sessionId}/command"
    private var sequence = 0

    /** The tokens this router minted, oldest first, for evidence and debugging. */
    private val mintedTokens = mutableListOf<OperationToken>()
    val captures: List<OperationToken> get() = mintedTokens

    // -- the context rule ---------------------------------------------------- //

    /**
     * Where a command may resolve right now.
     *
     * [CommandContext.ANSWER] whenever AV-012 reports an attempt in flight; the two
     * command contexts are disjoint from it by construction.
     */
    fun context(): CommandContext {
        val turn = session.answerTurn
        if (session.state == SessionState.LISTENING && turn != null &&
            (turn.phase == AnswerPhase.CAPTURING || turn.phase == AnswerPhase.FINALIZING)
        ) {
            return CommandContext.ANSWER
        }
        return when (session.state) {
            SessionState.PROPOSING -> CommandContext.CONFIRMATION
            SessionState.ASKING, SessionState.REVEALING, SessionState.COMMITTING,
            SessionState.OUTCOME_UNKNOWN, SessionState.INTERRUPTED, SessionState.UNSUPPORTED,
            SessionState.STOPPED, SessionState.EXHAUSTED,
            -> CommandContext.UNAVAILABLE
            else -> CommandContext.COMMAND
        }
    }

    /**
     * The commands an on-screen control may run right now, in vocabulary order.
     *
     * Touch is the fallback for everything, so this is not filtered by [context]: tapping
     * Pause or Skip during an attempt is exactly the escape hatch AV-014 requires.
     */
    fun available(): List<VoiceCommand> = VoiceCommand.entries.filter(::executable)

    /** The commands a spoken utterance could run right now. Empty inside the answer window. */
    fun spokenAvailable(): List<VoiceCommand> {
        val context = context()
        if (context == CommandContext.ANSWER || context == CommandContext.UNAVAILABLE) return emptyList()
        return available().filter { !it.touchOnly && it.resolvesIn(context) }
    }

    // -- entry points --------------------------------------------------------- //

    /**
     * The touch equivalent. An explicit gesture carries no recognition confidence, so the
     * confidence gate does not apply and no command is voice-only.
     */
    fun touch(command: VoiceCommand): CommandOutcome =
        dispatch(command, ConfirmationSource.TOUCH, Confidence.ABSENT)

    /**
     * One learner-opened command capture.
     *
     * Refused outright inside the answer window: AV-025 permits one active capture, and a
     * second one would be the overlapping capture AV-012 forbids.
     */
    fun listenForCommand(): CommandOutcome {
        when (context()) {
            CommandContext.ANSWER -> return refuse(
                null,
                CommandRefusal.IN_ANSWER_WINDOW,
                "The microphone is capturing your answer. Tap Done first, or use the on-screen controls.",
            )
            CommandContext.UNAVAILABLE -> return refuse(
                null,
                CommandRefusal.UNAVAILABLE_HERE,
                "No command can be spoken while the session is ${session.state.specName}.",
            )
            CommandContext.COMMAND, CommandContext.CONFIRMATION -> Unit
        }
        sequence += 1
        val token = OperationToken(captureSessionId, 0, sequence)
        mintedTokens += token
        return when (val event = speech.listen(token, language)) {
            is CaptureEvent.Failed -> recognitionFailed(event.failure)
            is CaptureEvent.Transcript -> when {
                event.token != token -> refuse(
                    null,
                    CommandRefusal.STALE_CAPTURE,
                    "A late result arrived from an earlier operation, so nothing was run.",
                )
                // A command capture never settles on partial text, and a recognizer may not
                // produce a learner-corrected transcript.
                event.kind != TranscriptKind.FINAL -> refuse(
                    null,
                    CommandRefusal.NOT_A_COMMAND,
                    "That was not a finished command. Say it again, or use the on-screen controls.",
                )
                else -> spoken(event.text, event.confidence)
            }
        }
    }

    /**
     * Act on one already-captured utterance.
     *
     * Inside the answer window it returns the text unchanged and runs nothing at all: no
     * command word is stripped, and the transcript reaches grading exactly as spoken.
     */
    fun spoken(text: String, confidence: Confidence): CommandOutcome {
        val context = context()
        return when (val recognized = CommandVocabulary.parse(text, context, confidence)) {
            is CommandRecognition.AnswerText -> CommandOutcome.AnswerText(recognized.text)
            is CommandRecognition.Unrecognized -> refuse(
                null,
                CommandRefusal.NOT_A_COMMAND,
                "\"${recognized.text}\" is not a command. Say it again, or use the on-screen controls.",
            )
            is CommandRecognition.Ambiguous -> refuse(
                null,
                CommandRefusal.AMBIGUOUS,
                "\"${recognized.phrase}\" could mean " +
                    "${recognized.candidates.joinToString(" or ") { it.specName }}. " +
                    "Say which one, or use the on-screen controls.",
            )
            is CommandRecognition.Uncertain -> refuse(
                recognized.command,
                CommandRefusal.LOW_CONFIDENCE,
                "That sounded like ${recognized.command.specName}, but not clearly enough to run it " +
                    "(confidence ${recognized.confidence.specName}). Say it again, or tap it.",
            )
            is CommandRecognition.OutOfContext -> refuse(
                recognized.command,
                CommandRefusal.OUT_OF_CONTEXT,
                "${recognized.command.specName} is not available in the " +
                    "${recognized.context.specName} context.",
            )
            is CommandRecognition.Recognized ->
                if (recognized.command.touchOnly) {
                    refuse(
                        recognized.command,
                        CommandRefusal.TOUCH_ONLY,
                        "Resume is an on-screen control. The microphone is released while study is " +
                            "paused, so nothing is listening for it.",
                    )
                } else {
                    dispatch(recognized.command, ConfirmationSource.SPOKEN, confidence)
                }
        }
    }

    /**
     * A recognizer fault during a command capture. It pauses with the card preserved, and
     * it is never treated as an answer, a rating or a command.
     */
    fun recognitionFailed(failure: Failure): CommandOutcome {
        if (!session.halted) session.requestPause(failure)
        return refuse(
            null,
            CommandRefusal.RECOGNITION_FAILED,
            "The command was not heard (${failure.mode.specName}). Study is paused and the card is " +
                "kept. Use the on-screen controls to continue.",
        )
    }

    // -- dispatch -------------------------------------------------------------- //

    private fun dispatch(
        command: VoiceCommand,
        source: ConfirmationSource,
        confidence: Confidence,
    ): CommandOutcome {
        if (!executable(command)) {
            return refuse(
                command,
                CommandRefusal.UNAVAILABLE_HERE,
                "${command.specName} is not available while the session is ${session.state.specName}.",
            )
        }
        return when (command) {
            VoiceCommand.REPEAT -> playback(command, source, session.replayQuestion(), "The question was read again.")
            VoiceCommand.REVEAL -> playback(command, source, session.reveal(), "The answer was read out.")
            VoiceCommand.PAUSE -> pause(source)
            VoiceCommand.RESUME -> resume(source)
            VoiceCommand.FINISH_SESSION -> {
                session.finishSession()
                executed(command, source, "Session finished. No review was written.")
            }
            VoiceCommand.SKIP -> {
                session.requestSkip()
                executed(
                    command,
                    source,
                    "Skipped. AnkiDroid offers no skip that leaves scheduling alone, so this card was " +
                        "left exactly as it was: nothing was rated, buried, suspended or reordered. " +
                        "Tap Resume for the next card.",
                )
            }
            VoiceCommand.CONFIRM -> confirm(source, confidence)
            VoiceCommand.CHANGE -> executed(
                command,
                source,
                "The pending rating was not submitted. Say or tap Again, Hard, Good or Easy to " +
                    "replace it.",
            )
            VoiceCommand.RATE_AGAIN, VoiceCommand.RATE_HARD,
            VoiceCommand.RATE_GOOD, VoiceCommand.RATE_EASY,
            -> propose(command, source)
        }
    }

    private fun playback(
        command: VoiceCommand,
        source: ConfirmationSource,
        result: SessionResult<*>,
        notice: String,
    ): CommandOutcome = when (result) {
        is SessionResult.Produced -> executed(command, source, notice)
        is SessionResult.Halted -> refuse(
            command,
            CommandRefusal.HALTED,
            "Playback failed (${result.halt.reason}). Study is paused and the card is kept.",
        )
        SessionResult.Ignored -> refuse(
            command,
            CommandRefusal.STALE_CAPTURE,
            "That playback belonged to an earlier turn, so nothing was played.",
        )
    }

    private fun pause(source: ConfirmationSource): CommandOutcome {
        // Read the context before pausing: afterwards the attempt is already settled.
        val duringCapture = context() == CommandContext.ANSWER
        session.requestPause()
        val notice = if (duringCapture) {
            "Paused. The microphone stopped and that attempt was cancelled, so what it had heard so " +
                "far is not an answer. Nothing was written."
        } else {
            "Paused. The microphone is released and the card is kept. Nothing was written."
        }
        return executed(VoiceCommand.PAUSE, source, notice)
    }

    private fun resume(source: ConfirmationSource): CommandOutcome {
        session.resume()
        val offered = session.offerCard()
        val detail = when (offered) {
            is SessionResult.Produced -> when (val value = offered.value) {
                is ScheduledCard -> "The next card is ready."
                QueueExhausted -> "The queue is finished."
                is Failure -> "AnkiDroid could not supply a card: ${value.mode.specName}."
            }
            is SessionResult.Halted -> "AnkiDroid could not supply a card: ${offered.halt.reason}."
            SessionResult.Ignored -> "No card was offered."
        }
        return CommandOutcome.Executed(
            VoiceCommand.RESUME,
            "Resumed. The previous attempt was discarded and the card was read again from the " +
                "collection, because a paused snapshot may have been overtaken by AnkiDroid or a " +
                "sync. $detail",
            source,
            offered.valueOrNull,
        )
    }

    private fun propose(command: VoiceCommand, source: ConfirmationSource): CommandOutcome {
        val rating = checkNotNull(command.rating) { "${command.specName} proposes no rating" }
        // Inside the pre-commit exchange the pending rating is replaced through #14's own
        // correction path, which discards the confirmation bound to the rating it replaces.
        val outcome = if (session.state == SessionState.PROPOSING) {
            session.correct(rating)
        } else {
            session.propose(rating)
        }
        return when (outcome) {
            is ProposalOutcome.Proposed -> executed(
                command,
                source,
                "Rating $rating is proposed for this card. It is not submitted: confirm it first.",
            )
            is ProposalOutcome.Rejected -> refuse(
                command,
                CommandRefusal.RATING_NOT_OFFERED,
                "This card did not offer rating $rating, so nothing was proposed.",
            )
        }
    }

    /**
     * Authorize the pending rating for this attempt and revision.
     *
     * A spoken confirmation below [Confidence.SUFFICIENT] is not a confirmation, which
     * [org.ankivoice.core.contracts.ReviewIntent.hasConfirmation] enforces independently of
     * this router's own gate. Authorizing is not submitting: no command path commits.
     */
    private fun confirm(source: ConfirmationSource, confidence: Confidence): CommandOutcome {
        val intent = session.intent
        val token = intent?.token
        if (intent == null || token == null) {
            return refuse(
                VoiceCommand.CONFIRM,
                CommandRefusal.UNAVAILABLE_HERE,
                "There is no pending rating to confirm.",
            )
        }
        val accepted = session.confirm(
            RatingConfirmation(
                token = token,
                identity = intent.cardSnapshot.identity,
                rating = intent.rating,
                transcriptRevision = intent.transcriptRevision,
                source = source,
                final = true,
                confidence = if (source == ConfirmationSource.TOUCH) Confidence.ABSENT else confidence,
            ),
        )
        return if (accepted) {
            executed(
                VoiceCommand.CONFIRM,
                source,
                "Rating ${intent.rating} is confirmed for this card. Nothing is written until the " +
                    "review is submitted.",
            )
        } else {
            refuse(
                VoiceCommand.CONFIRM,
                CommandRefusal.CONFIRMATION_REJECTED,
                "That confirmation did not match the pending rating, so nothing was confirmed.",
            )
        }
    }

    // -- what each command needs ------------------------------------------------ //

    /** True when [command] means something in the session as it stands right now. */
    private fun executable(command: VoiceCommand): Boolean {
        val state = session.state
        val phase = session.answerTurn?.phase
        val attemptInFlight = phase == AnswerPhase.CAPTURING || phase == AnswerPhase.FINALIZING
        return when (command) {
            VoiceCommand.REPEAT -> session.card != null && !attemptInFlight &&
                (
                    state == SessionState.LISTENING || state == SessionState.RETRYING ||
                        state == SessionState.GRADING || state == SessionState.PROPOSING
                    )
            VoiceCommand.REVEAL -> session.answer != null &&
                (state == SessionState.GRADING || state == SessionState.PROPOSING)
            VoiceCommand.PAUSE -> !session.halted && state != SessionState.COMMITTING &&
                state != SessionState.COMMITTED
            // Only a resumable pause, and never one still owing a reconciliation.
            VoiceCommand.RESUME -> state == SessionState.PAUSED &&
                session.halt?.let { it.resumable && !it.reconciliationRequired } == true
            VoiceCommand.FINISH_SESSION -> state != SessionState.COMMITTING &&
                (!session.halted || state == SessionState.PAUSED)
            // A real skip operation would need its own contract, so this halts instead.
            VoiceCommand.SKIP -> session.card != null && !session.halted &&
                !session.capabilities.supportsSkip &&
                state != SessionState.COMMITTING && state != SessionState.COMMITTED
            // Nothing to re-confirm: a rating stays confirmed until a different one replaces it.
            VoiceCommand.CONFIRM -> pendingRating() != null && session.intent?.confirmation == null
            VoiceCommand.CHANGE -> pendingRating() != null
            VoiceCommand.RATE_AGAIN, VoiceCommand.RATE_HARD,
            VoiceCommand.RATE_GOOD, VoiceCommand.RATE_EASY,
            -> ratable(checkNotNull(command.rating))
        }
    }

    private fun pendingRating(): Int? = session.intent
        ?.takeIf { session.state == SessionState.PROPOSING && it.state == ReviewState.PENDING }
        ?.rating

    private fun ratable(rating: Int): Boolean {
        val card = session.card ?: return false
        if (session.answer?.gradable != true) return false
        if (session.state != SessionState.GRADING && session.state != SessionState.PROPOSING) return false
        return rating in card.permittedRatings
    }

    private fun executed(command: VoiceCommand, source: ConfirmationSource, notice: String) =
        CommandOutcome.Executed(command, notice, source)

    private fun refuse(command: VoiceCommand?, reason: CommandRefusal, notice: String) =
        CommandOutcome.Refused(command, reason, notice)
}
