package org.ankivoice.core.session

import java.util.UUID
import org.ankivoice.core.answer.Answer
import org.ankivoice.core.answer.AnswerLimits
import org.ankivoice.core.answer.AnswerPhase
import org.ankivoice.core.answer.AnswerRecovery
import org.ankivoice.core.answer.AnswerStatus
import org.ankivoice.core.answer.AnswerTurn
import org.ankivoice.core.contracts.Capabilities
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.CardProvider
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.Grader
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.MonotonicClock
import org.ankivoice.core.contracts.NextCardResult
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.PlaybackResult
import org.ankivoice.core.contracts.QueueExhausted
import org.ankivoice.core.contracts.RatingConfirmation
import org.ankivoice.core.contracts.ReviewIntent
import org.ankivoice.core.contracts.ReviewOutcome
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.ReviewWriter
import org.ankivoice.core.contracts.ReviewWriterFailure
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.contracts.SpeechInput
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.SpeechOutput
import org.ankivoice.core.contracts.SpeechOutputFailure
import org.ankivoice.core.contracts.SystemMonotonicClock
import org.ankivoice.core.contracts.Utterance
import org.ankivoice.core.contracts.UtterancePurpose
import org.ankivoice.core.contracts.cardLanguage
import org.ankivoice.core.contracts.elaborationUtterance
import org.ankivoice.core.contracts.gradingContext
import org.ankivoice.core.contracts.questionUtterance
import org.ankivoice.core.contracts.revealUtterance

/**
 * AV-013: the deterministic turn loop. Kotlin port of `ReviewSession` in
 * `tools/av007_contracts.py`, extended with the states AV-013 requires and with AV-012's
 * answer policy in place of the binding's inline capture handling.
 *
 * It owns no scheduling and no persistence. #20 owns the journal that survives process
 * loss and #28 owns sync handoff and stale-session validation; this class deliberately
 * keeps nothing across a restart. It reaches the recognizer, the synthesizer, the
 * collection and the grader only through AV-007's contracts, so no platform type
 * appears here and :core stays pure Kotlin/JVM.
 *
 * **Confinement.** Every transition runs on the thread that constructed the session —
 * the main thread in the app. A callback delivered from a recognizer, synthesizer or
 * grader thread must be posted to that thread first; calling in from another one fails
 * loudly rather than corrupting the turn.
 *
 * **Invalidate then clean up.** Teardown invalidates this turn's tokens *before* it
 * cancels anything, because cancellation itself produces late callbacks. A playback,
 * capture or grading result that arrives mid-teardown is dropped, never delivered.
 *
 * **Nothing here writes a review.** A rating reaches [ReviewWriter] only from [commit],
 * only for an intent that carries an explicit, current [RatingConfirmation]. Silence, a
 * timeout, a speech failure, a grading failure and a confident model are all not
 * confirmations, and a transcript edit invalidates any pending suggestion and its
 * confirmation.
 */
class ReviewSession(
    private val provider: CardProvider,
    private val speechOutput: SpeechOutput,
    private val speechInput: SpeechInput,
    private val grader: Grader,
    val writer: ReviewWriter,
    val capabilities: Capabilities,
    val language: String = "en-US",
    private val clock: MonotonicClock = SystemMonotonicClock,
    val sessionId: String = UUID.randomUUID().toString(),
    private val limits: AnswerLimits = AnswerLimits.SELECTED,
    private val owner: Thread = Thread.currentThread(),
) {
    /**
     * AV-012 mints its own capture tokens per attempt. Namespacing them keeps a capture
     * token from ever equalling a playback, grading or proposal token that happens to
     * share a turn and sequence number.
     */
    private val answerSessionId: String = "$sessionId/answer"

    var state: SessionState = SessionState.IDLE
        private set

    private val visitedStates = mutableListOf(SessionState.IDLE)

    /** Every state this session entered, in order. */
    val visited: List<SessionState> get() = visitedStates

    /** The coarser state the Python binding reports, or null while revealing. */
    val bindingState: BindingSessionState? get() = state.binding

    var halt: Halt? = null
        private set

    var card: ScheduledCard? = null
        private set

    var intent: ReviewIntent? = null
        private set

    /** AV-012's policy for the open card, or null between cards. */
    var answerTurn: AnswerTurn? = null
        private set

    /** The last settled answer for this card. Never a rating and never a label. */
    var answer: Answer? = null
        private set

    var transcriptRevision: Int = 0
        private set

    /** The current advisory suggestion, cleared by every edit and every teardown. */
    var suggestion: GradingResult? = null
        private set

    var lastFailure: Failure? = null
        private set

    var playbackToken: OperationToken? = null
        private set

    var gradingRequest: GradingRequest? = null
        private set

    private val eventLog = mutableListOf<Event>()
    private val recordedOutcomes = mutableListOf<ReviewOutcome>()

    /**
     * The ordered turn transcript, for evidence and debugging, as the binding's
     * `transcript()` produces it. It carries study content — the prompt and what the
     * learner said — and is **not** an AV-020 diagnostic: it is in-memory only, never
     * persisted by this class and never sent anywhere.
     */
    val events: List<Event> get() = eventLog
    val outcomes: List<ReviewOutcome> get() = recordedOutcomes

    /** Turn-scoped capture validity. Cleared before any cancellation, never after. */
    private var captureValid = false
    private var startedMs: Long? = null
    private var turnNumber = 0
    private var sequence = 0
    private var revealReturnState = SessionState.GRADING

    val halted: Boolean get() = state.isHalted

    /** The capture token of the attempt in flight, or null outside one. */
    val captureToken: OperationToken? get() = if (captureValid) answerTurn?.token else null

    /**
     * The explicit manual controls a paused turn offers, preserving the current card.
     *
     * Empty unless a speech or grading fault, or an explicit Cancel, paused the turn.
     * Self-grade appears only when a gradable transcript exists: a fault never supplies
     * one, so after a speech fault the learner retries or types a correction first.
     */
    val recoveryOptions: List<AnswerRecovery>
        get() {
            if (state != SessionState.PAUSED || card == null || answerTurn == null) return emptyList()
            if (!recoverableInPlace()) return emptyList()
            val gradable = answer?.gradable == true
            return AnswerRecovery.entries.filter { it != AnswerRecovery.SELF_GRADE || gradable }
        }

    // -- lifecycle ---------------------------------------------------------- //

    fun start() {
        confine("start")
        check(state == SessionState.IDLE && eventLog.isEmpty()) { "The session has already started" }
        log("start", "language $language")
    }

    /**
     * Offer the next scheduled card, a valid empty queue, or the halt the provider
     * caused. A snapshot is never a reservation on that card.
     */
    fun offerCard(): SessionResult<NextCardResult> {
        confine("offerCard")
        check(state == SessionState.IDLE) { "The session is ${state.specName}" }
        intent?.let {
            check(it.state != ReviewState.PENDING) { "A pending review must be committed or discarded first" }
        }
        val offered = provider.nextCard()
        halt?.takeIf { halted }?.let { return SessionResult.Halted(it) }
        if (offered is Failure) return SessionResult.Halted(haltOnFailure(offered))
        if (offered is QueueExhausted) {
            enter(SessionState.EXHAUSTED)
            halt = Halt(SessionState.EXHAUSTED, "queue_exhausted", "the queue emptied normally", false, false)
            log("exhausted", "the queue emptied normally")
            return SessionResult.Produced(offered)
        }
        val fresh = offered as ScheduledCard
        card = fresh
        intent = null
        answer = null
        suggestion = null
        lastFailure = null
        transcriptRevision = 0
        startedMs = null
        captureValid = false
        turnNumber += 1
        answerTurn = AnswerTurn(
            speech = speechInput,
            clock = clock,
            sessionId = answerSessionId,
            turn = turnNumber,
            language = cardLanguage(fresh, language),
            limits = limits,
        )
        enter(SessionState.ASKING)
        log("offer_card", "card ${fresh.identity.cardId}, ratings ${fresh.permittedRatings}")
        return SessionResult.Produced(fresh)
    }

    // -- question audio ----------------------------------------------------- //

    /** Speak the Prompt and nothing else. Capture cannot open while playback runs. */
    fun ask(): SessionResult<Utterance> {
        confine("ask")
        check(state == SessionState.ASKING) { "Cannot ask while ${state.specName}" }
        check(playbackToken == null) { "Cancel or complete the current playback before asking again" }
        val open = requireCard()
        val utterance = questionUtterance(open, language)
        val token = nextToken()
        playbackToken = token
        val completion = finishPlayback(speechOutput.speak(token, utterance))
        if (completion is SessionResult.Halted) return completion
        halt?.takeIf { halted }?.let { return SessionResult.Halted(it) }
        return SessionResult.Produced(utterance)
    }

    /**
     * One playback completion. A result for a superseded playback is dropped.
     *
     * Only question playback opens the answer phase; reveal and announcement playback
     * return the session to the state they were requested from.
     */
    fun finishPlayback(result: PlaybackResult): SessionResult<Boolean> {
        confine("finishPlayback")
        if (result.token != playbackToken || halted) {
            log("stale_playback", "ignored")
            return SessionResult.Ignored
        }
        if (result is PlaybackResult.Failed) return SessionResult.Halted(haltOnFailure(result.failure))
        playbackToken = null
        if (state == SessionState.REVEALING) {
            enter(revealReturnState)
            return SessionResult.Produced(true)
        }
        if (state != SessionState.ASKING) return SessionResult.Produced(true)
        startedMs = clock.nowMs()
        captureValid = true
        enter(SessionState.LISTENING)
        log("ask", requireCard().fields.prompt)
        return SessionResult.Produced(true)
    }

    // -- the answer (AV-012's policy, driven through AV-007's contract) ------- //

    /**
     * The explicit Start answer. Opens one bounded window; thinking time before it is
     * unbounded and consumes no budget.
     */
    fun startAnswer(): OperationToken {
        confine("startAnswer")
        check(state == SessionState.LISTENING || state == SessionState.RETRYING) {
            "Start answer needs the answer phase, not ${state.specName}"
        }
        if (state == SessionState.RETRYING) enter(SessionState.LISTENING)
        captureValid = true
        val token = requireTurn().startAnswer()
        log("start_answer", "attempt ${requireTurn().attempt}")
        return token
    }

    /** [startAnswer] followed by one capture, for a synchronous transport. */
    fun listen(): SessionResult<Answer> {
        confine("listen")
        check(state == SessionState.LISTENING || state == SessionState.RETRYING) {
            "Cannot listen while ${state.specName}"
        }
        if (state == SessionState.RETRYING) enter(SessionState.LISTENING)
        captureValid = true
        return settle(requireTurn().listen())
    }

    /** Explicit Done: stop the microphone and start the finalization deadline. */
    fun finishAnswer(): SessionResult<Answer> {
        confine("finishAnswer")
        check(state == SessionState.LISTENING) { "Done needs active capture, not ${state.specName}" }
        return settle(requireTurn().done())
    }

    /**
     * AV-050: the learner was first heard [afterOpenMs] after the microphone opened.
     *
     * Reported by the transport once the capture returns, because the session thread is
     * blocked inside it while it happens. It ends AV-012's recall pre-roll and starts the
     * answer window; it settles nothing and stops nothing.
     */
    fun reportSpeechOnset(afterOpenMs: Long) {
        confine("reportSpeechOnset")
        if (state != SessionState.LISTENING || !captureValid) return
        answerTurn?.speechBegan(afterOpenMs)
    }

    /**
     * AV-050: the capture ended itself because the learner stopped speaking.
     *
     * The same stop [finishAnswer] makes, through the same finalization, with
     * [org.ankivoice.core.answer.CaptureStop.ENDPOINT] recorded instead of a learner's Done.
     * Ignored unless an attempt is genuinely in flight and the learner was heard in it, so a
     * Done or a Cancel that already settled the turn keeps precedence.
     */
    fun endpointAnswer(): SessionResult<Answer> {
        confine("endpointAnswer")
        val turn = answerTurn
        if (state != SessionState.LISTENING || !captureValid || turn == null) return SessionResult.Ignored
        if (turn.phase != AnswerPhase.CAPTURING || !turn.heardSpeech) return SessionResult.Ignored
        log("endpoint", "attempt ${turn.attempt} ended on the learner's own silence")
        return settle(turn.endpoint())
    }

    /** Advance AV-012's deadlines without a callback. Expiry is never an answer. */
    fun poll(): SessionResult<Answer> {
        confine("poll")
        if (state != SessionState.LISTENING || !captureValid) return SessionResult.Ignored
        return settle(requireTurn().poll())
    }

    /**
     * One recognizer callback. A late, duplicate or out-of-attempt event is dropped, so
     * a superseded turn can never advance the current card or produce a second rating.
     */
    fun acceptCapture(event: CaptureEvent): SessionResult<Answer> {
        confine("acceptCapture")
        val turn = answerTurn
        if (!captureValid || turn == null || state != SessionState.LISTENING) {
            log("stale_capture", "ignored")
            return SessionResult.Ignored
        }
        return settle(turn.deliver(event))
    }

    /** Explicit Cancel during capture. The card is kept and nothing is inferred. */
    fun cancelAnswer(): Halt {
        confine("cancelAnswer")
        check(state == SessionState.LISTENING) { "Cancel needs an attempt in flight, not ${state.specName}" }
        val turn = requireTurn()
        check(turn.phase == AnswerPhase.CAPTURING || turn.phase == AnswerPhase.FINALIZING) {
            "Cancel needs an attempt in flight, not ${turn.phase.specName}"
        }
        captureValid = false
        val cancelled = turn.cancel()
        answer = cancelled
        transcriptRevision = cancelled.transcriptRevision
        log("cancel_answer", "attempt ${cancelled.attempt} cancelled by the learner")
        return pause(CANCELLED_ANSWER, "the learner cancelled the answer; the card is kept")
    }

    /**
     * Explicit Try again for the same card. It opens a new revision and returns the turn
     * to thinking; the next bounded window waits for the next explicit Start answer and
     * nothing re-arms on its own.
     */
    fun retry(): SessionState {
        confine("retry")
        val turn = requireTurn()
        val allowed = state == SessionState.LISTENING || state == SessionState.GRADING ||
            state == SessionState.PROPOSING || recoverableInPlace()
        check(allowed) { "Try again is not available while ${state.specName}" }
        invalidatePending()
        turn.tryAgain()
        transcriptRevision = turn.transcriptRevision
        answer = null
        halt = null
        lastFailure = null
        enter(SessionState.RETRYING)
        log("retry", "revision $transcriptRevision; the next window waits for Start answer")
        return state
    }

    /**
     * A typed correction, or the learner accepting a transcript the recognizer did not
     * vouch for. It always creates a new revision, which invalidates any pending
     * suggestion and any confirmation bound to the old one.
     */
    fun correctTranscript(text: String): Answer {
        confine("correctTranscript")
        val turn = requireTurn()
        val recoverable = recoverableInPlace()
        val allowed = state == SessionState.GRADING || state == SessionState.PROPOSING ||
            state == SessionState.LISTENING || state == SessionState.RETRYING || recoverable
        require(allowed && text.isNotBlank()) { "A transcript edit requires an answer turn and nonempty text" }
        invalidatePending()
        check(!halted || recoverable) { "The session stopped during cancellation; reload required" }
        val corrected = turn.correct(text)
        answer = corrected
        transcriptRevision = corrected.transcriptRevision
        halt = null
        lastFailure = null
        enter(SessionState.GRADING)
        log("correct_transcript", "revision $transcriptRevision: ${corrected.text}")
        return corrected
    }

    // -- grading (advisory only) -------------------------------------------- //

    /** Open one grading request for the current revision, cancelling any older one. */
    fun beginGrade(): GradingRequest {
        confine("beginGrade")
        check(state == SessionState.GRADING) { "Cannot grade while ${state.specName}" }
        val current = answer
        require(current != null && current.gradable) {
            "Grading requires a final or learner-corrected transcript"
        }
        gradingRequest?.let { stale ->
            gradingRequest = null
            grader.cancel(stale)
        }
        check(state == SessionState.GRADING) { "The session stopped during grading cancellation" }
        suggestion = null
        val request = GradingRequest(
            nextToken(),
            transcriptRevision,
            gradingContext(requireCard(), current.text, language),
        )
        gradingRequest = request
        return request
    }

    /** [beginGrade] plus one grader call, for a synchronous grader. */
    fun grade(): SessionResult<GradingResult> = acceptGrade(grader.grade(beginGrade()))

    /** One grader reply. A reply for a superseded request or revision is discarded. */
    fun acceptGrade(reply: GradingReply): SessionResult<GradingResult> {
        confine("acceptGrade")
        if (state != SessionState.GRADING || reply.request != gradingRequest ||
            reply.request.transcriptRevision != transcriptRevision
        ) {
            log("stale_grade", "ignored")
            return SessionResult.Ignored
        }
        gradingRequest = null
        return when (val result = reply.result) {
            is Failure -> SessionResult.Halted(haltOnFailure(result))
            is GradingResult -> {
                suggestion = result
                log("grade", "${result.label.specName}: ${result.reason}")
                SessionResult.Produced(result)
            }
        }
    }

    /** Explicit learner fallback. A grading fault never supplies a rating on its own. */
    fun selfGrade(rating: Int): ProposalOutcome {
        confine("selfGrade")
        val pause = halt
        if (state == SessionState.PAUSED && pause != null && !pause.reconciliationRequired &&
            lastFailure?.mode is GraderFailure && answer?.gradable == true
        ) {
            halt = null
            enter(SessionState.GRADING)
        }
        check(state == SessionState.GRADING || state == SessionState.PROPOSING) {
            "Self-grade requires an accepted or corrected answer"
        }
        log("self_grade", "learner selected $rating")
        return propose(rating)
    }

    /**
     * AV-014's repeat: speak the Prompt again, and nothing else.
     *
     * It reuses the transient playback path [reveal] already owns and adds no state. It is
     * refused while an attempt is in flight, because AV-025 forbids playing into an open
     * microphone; AV-014's context rule already keeps a spoken repeat out of that window.
     */
    fun replayQuestion(): SessionResult<Utterance> {
        confine("replayQuestion")
        check(
            state == SessionState.LISTENING || state == SessionState.RETRYING ||
                state == SessionState.GRADING || state == SessionState.PROPOSING,
        ) { "Repeat needs an open card outside the answer window, not ${state.specName}" }
        val turn = answerTurn
        check(turn == null || (turn.phase != AnswerPhase.CAPTURING && turn.phase != AnswerPhase.FINALIZING)) {
            "Repeat cannot play into an open microphone"
        }
        val open = requireCard()
        check(playbackToken == null) { "Cancel or complete the current playback before repeating" }
        val utterance = questionUtterance(open, language)
        revealReturnState = state
        val token = nextToken()
        playbackToken = token
        enter(SessionState.REVEALING)
        log("repeat", open.fields.prompt)
        val completion = finishPlayback(speechOutput.speak(token, utterance))
        if (completion is SessionResult.Halted) return completion
        halt?.takeIf { halted }?.let { return SessionResult.Halted(it) }
        return SessionResult.Produced(utterance)
    }

    /** Speak the ReferenceAnswer, or the Extra. Never before an answer exists. */
    fun reveal(includeExtra: Boolean = false): SessionResult<Utterance> {
        confine("reveal")
        check((state == SessionState.GRADING || state == SessionState.PROPOSING) && answer != null) {
            "Reveal requires an accepted or learner-corrected answer"
        }
        val open = requireCard()
        val utterance = if (includeExtra) elaborationUtterance(open, language) else revealUtterance(open, language)
        checkNotNull(utterance) { "This card has no Extra" }
        check(playbackToken == null) { "Cancel or complete the current playback before reveal" }
        revealReturnState = state
        val token = nextToken()
        playbackToken = token
        enter(SessionState.REVEALING)
        val completion = finishPlayback(speechOutput.speak(token, utterance))
        if (completion is SessionResult.Halted) return completion
        halt?.takeIf { halted }?.let { return SessionResult.Halted(it) }
        return SessionResult.Produced(utterance)
    }

    // -- the pending review -------------------------------------------------- //

    /** Open the correction window. Nothing is written until [commit]. */
    fun propose(rating: Int): ProposalOutcome {
        confine("propose")
        check(state == SessionState.GRADING || state == SessionState.PROPOSING) {
            "Cannot propose while ${state.specName}"
        }
        rejectUnoffered(rating)?.let { return ProposalOutcome.Rejected(it) }
        val current = answer
        require(current != null && current.gradable) {
            "A rating requires an accepted or learner-corrected answer"
        }
        invalidatePending()
        check(!halted) { "The session stopped during cancellation; reload required" }
        val now = clock.nowMs()
        val elapsed = (now - (startedMs ?: now)).coerceAtLeast(0)
        val fresh = ReviewIntent(requireCard(), rating, elapsed, nextToken(), transcriptRevision)
        intent = fresh
        enter(SessionState.PROPOSING)
        log("propose", "rating $rating, $elapsed ms")
        return ProposalOutcome.Proposed(fresh)
    }

    /** Replace the pending rating before commit. The old confirmation no longer applies. */
    fun correct(rating: Int): ProposalOutcome {
        confine("correct")
        check(state == SessionState.PROPOSING) { "Correction is only permitted before commit" }
        rejectUnoffered(rating)?.let { return ProposalOutcome.Rejected(it) }
        val current = requireIntent()
        current.correct(rating)
        current.token = nextToken()
        log("correct", "rating $rating")
        return ProposalOutcome.Proposed(current)
    }

    /**
     * One explicit learner confirmation for the current attempt and revision. Silence, a
     * timeout, a fault and a confident model are never confirmations.
     */
    fun confirm(event: RatingConfirmation): Boolean {
        confine("confirm")
        val current = intent
        if (state != SessionState.PROPOSING || current == null || current.state != ReviewState.PENDING) {
            log("confirmation_rejected", "no pending rating")
            return false
        }
        val accepted = current.confirm(event)
        if (accepted) {
            log("confirm", "${event.source.specName}: rating ${event.rating}")
        } else {
            log("confirmation_rejected", "stale, mismatched or uncertain event")
        }
        return accepted
    }

    /** Hand the single write to the guarded writer, once, and classify what came back. */
    fun commit(): ReviewOutcome {
        confine("commit")
        check(state == SessionState.PROPOSING) { "Cannot commit while ${state.specName}" }
        val current = requireIntent()
        check(current.hasConfirmation()) { "Explicit learner confirmation is required" }
        val now = clock.nowMs()
        current.elapsedMs = (now - (startedMs ?: now)).coerceAtLeast(0)
        enter(SessionState.COMMITTING)
        val outcome = writer.commit(current)
        recordedOutcomes += outcome
        log("commit", "${outcome.state.specName}: ${outcome.reason}")
        // An interruption during the single write cannot be undone by its callback.
        if (state == SessionState.STOPPED || state == SessionState.INTERRUPTED) return outcome
        when (outcome.state) {
            ReviewState.CONFIRMED -> enter(SessionState.COMMITTED)
            ReviewState.FAILED -> {
                val failure = outcome.failure
                val cause = failure?.cause?.mode
                val reason = failure?.mode?.specName ?: "write_failed"
                if (failure?.mode == ReviewWriterFailure.STALE_IDENTITY ||
                    cause == CardProviderFailure.COLLECTION_CHANGED || cause == CardProviderFailure.DECK_MISSING
                ) {
                    stop(reason, outcome.reason)
                } else {
                    pause(reason, outcome.reason)
                }
            }
            // No replay, no advance, no success announcement: the write is never retried
            // blindly. A writer that returns a non-terminal state is unknown, not success.
            ReviewState.OUTCOME_UNKNOWN, ReviewState.PENDING, ReviewState.SUBMITTING ->
                end(SessionState.OUTCOME_UNKNOWN, "outcome_unknown", outcome.reason, true, true)
        }
        return outcome
    }

    /** Only a confirmed review may be announced as saved. */
    fun announceResult(outcome: ReviewOutcome): Utterance {
        confine("announceResult")
        check(
            outcome.state == ReviewState.CONFIRMED && state == SessionState.COMMITTED &&
                recordedOutcomes.lastOrNull() === outcome,
        ) { "Only a confirmed review may be announced as saved" }
        return Utterance(UtterancePurpose.ANNOUNCEMENT, "Saved rating ${requireIntent().rating}.", language)
    }

    /** Permitted only after a confirmed review. */
    fun advance() {
        confine("advance")
        val current = intent
        check(state == SessionState.COMMITTED && current != null && current.state == ReviewState.CONFIRMED) {
            "Advance requires a confirmed review"
        }
        clearTurn()
        enter(SessionState.IDLE)
        log("advance", "confirmed; moving to the next card")
    }

    // -- halts ---------------------------------------------------------------- //

    /**
     * The single-active-reviewer precondition broke. Stop rather than reconcile
     * silently: reps and elapsed time cannot attribute a competing native or sync write
     * to this caller.
     */
    fun interrupt(kind: Interruption): Halt {
        confine("interrupt")
        val current = intent
        val unresolved = current != null &&
            (current.state == ReviewState.SUBMITTING || current.state == ReviewState.OUTCOME_UNKNOWN)
        return end(
            SessionState.INTERRUPTED,
            kind.specName,
            "single active reviewer precondition broken",
            resumable = false,
            reconciliationRequired = unresolved,
        )
    }

    /** No skip exists. Halt without any write; never rate, bury or suspend. */
    fun requestSkip(exitSession: Boolean = false): Halt {
        confine("requestSkip")
        check(!capabilities.supportsSkip) { "A real skip operation would need its own contract" }
        check(!halted && state != SessionState.COMMITTING && state != SessionState.COMMITTED) {
            "Skip is only available before submission in an active turn"
        }
        val detail = "halted without a write; no non-mutating skip operation exists"
        return if (exitSession) stop("skip_requested", detail) else pause("skip_requested", detail)
    }

    /**
     * AV-014's pause: an explicit learner pause, or a command-recognition fault routed
     * into one. The card is kept and nothing is written, rated, buried or suspended.
     *
     * An attempt in flight is settled as AV-012 `cancelled`, so the microphone stops
     * through #26's contract and whatever partial text the recognizer had never becomes an
     * answer. The way back out is [resume], which discards the turn and re-queries.
     */
    fun requestPause(cause: Failure? = null): Halt {
        confine("requestPause")
        check(!halted && state != SessionState.COMMITTING && state != SessionState.COMMITTED) {
            "Pause is only available in an active turn before submission, not ${state.specName}"
        }
        val turn = answerTurn
        if (state == SessionState.LISTENING && turn != null &&
            (turn.phase == AnswerPhase.CAPTURING || turn.phase == AnswerPhase.FINALIZING)
        ) {
            captureValid = false
            val cancelled = turn.cancel()
            answer = cancelled
            transcriptRevision = cancelled.transcriptRevision
            log("pause_capture", "attempt ${cancelled.attempt} cancelled; partial text is not an answer")
        }
        lastFailure = cause
        return pause(
            cause?.mode?.specName ?: LEARNER_PAUSED,
            cause?.detail?.ifEmpty { null } ?: "the learner paused; the card is kept and nothing was written",
        )
    }

    /** AV-014's finish session. Halts without a write; never rate, bury or suspend. */
    fun finishSession(): Halt {
        confine("finishSession")
        check(state != SessionState.COMMITTING) { "The single write is in flight" }
        return stop(SESSION_FINISHED, "the learner finished the session; nothing was written")
    }

    /** Correction is pre-commit only. Hand off to AnkiDroid's native Undo. */
    fun requestCorrectionAfterCommit(): Halt {
        confine("requestCorrectionAfterCommit")
        check(!capabilities.supportsProgrammaticUndo) { "Programmatic undo would need its own contract" }
        val current = intent
        check(current != null && current.state == ReviewState.CONFIRMED) {
            "There is no confirmed review to hand off"
        }
        return stop(
            "native_undo_handoff",
            "use AnkiDroid's Undo, then reload; no programmatic or durable undo is available",
        )
    }

    /** Explicit learner action after a resumable pause. Never automatic. */
    fun resume() {
        confine("resume")
        val pause = halt
        check(state == SessionState.PAUSED && pause != null && pause.resumable) {
            "Only a resumable pause may be resumed"
        }
        check(!pause.reconciliationRequired) { "Reconcile the unconfirmed review before resuming" }
        clearTurn()
        lastFailure = null
        halt = null
        enter(SessionState.IDLE)
        log("resume", "learner resolved the cause; re-querying")
    }

    /** The learner reports what AnkiDroid actually shows. Never inferred, never retried. */
    fun reconcile(learnerConfirmedSaved: Boolean) {
        confine("reconcile")
        val pause = halt
        check(pause != null && pause.reconciliationRequired) { "There is nothing to reconcile" }
        log(
            "reconcile",
            "learner reports the review was ${if (learnerConfirmedSaved) "saved" else "not saved"}; " +
                "reload the collection before studying again",
        )
    }

    // -- internals ------------------------------------------------------------ //

    /**
     * True when the turn is paused by something the learner can act on without losing the
     * card: a speech or grading fault, or an explicit Cancel. A write fault is not one of
     * them — the card snapshot may be stale, so the route out of it is [resume].
     */
    private fun recoverableInPlace(): Boolean {
        val pause = halt ?: return false
        if (state != SessionState.PAUSED || pause.reconciliationRequired) return false
        val mode = lastFailure?.mode
        return mode is SpeechInputFailure || mode is SpeechOutputFailure || mode is GraderFailure ||
            pause.reason == CANCELLED_ANSWER
    }

    private fun confine(operation: String) {
        val current = Thread.currentThread()
        check(current === owner) {
            "$operation must run on the session's owning thread (${owner.name}), not ${current.name}"
        }
    }

    private fun nextToken(): OperationToken {
        sequence += 1
        return OperationToken(sessionId, turnNumber, sequence)
    }

    private fun requireCard(): ScheduledCard = checkNotNull(card) { "No card is open" }

    private fun requireTurn(): AnswerTurn = checkNotNull(answerTurn) { "No answer turn is open" }

    private fun requireIntent(): ReviewIntent = checkNotNull(intent) { "No pending review" }

    private fun log(step: String, detail: String = "") {
        eventLog += Event(step, detail)
    }

    private fun enter(next: SessionState) {
        state = next
        visitedStates += next
    }

    /** The window stays open and the rating is never replaced with Again. */
    private fun rejectUnoffered(rating: Int): Failure? {
        if (rating in requireCard().permittedRatings) return null
        val failure = Failure(
            ReviewWriterFailure.RATING_REJECTED,
            "rating $rating is not in ${requireCard().permittedRatings} for this card",
        )
        log("rating_rejected", failure.detail)
        return failure
    }

    private fun settle(produced: Answer?): SessionResult<Answer> {
        halt?.takeIf { halted }?.let { return SessionResult.Halted(it) }
        if (produced == null) {
            log("stale_capture", "ignored")
            return SessionResult.Ignored
        }
        if (produced.status == AnswerStatus.PARTIAL) {
            log("partial", produced.text)
            return SessionResult.Ignored
        }
        answer = produced
        transcriptRevision = produced.transcriptRevision
        captureValid = false
        produced.failure?.let { return SessionResult.Halted(haltOnFailure(it)) }
        if (produced.gradable) {
            enter(SessionState.GRADING)
            log("listen", produced.text)
            return SessionResult.Produced(produced)
        }
        if (produced.needsLearnerReview) {
            return SessionResult.Halted(
                haltOnFailure(
                    Failure(SpeechInputFailure.LOW_CONFIDENCE, "learner correction or retry required"),
                ),
            )
        }
        if (produced.status == AnswerStatus.CANCELLED) {
            return SessionResult.Halted(pause(CANCELLED_ANSWER, "the answer was cancelled; the card is kept"))
        }
        return SessionResult.Halted(
            haltOnFailure(Failure(SpeechInputFailure.NO_MATCH, "no gradable answer; the cause stays unknown")),
        )
    }

    /** A transport or provider fault. Never a rating, never an answer. */
    private fun haltOnFailure(failure: Failure): Halt {
        lastFailure = failure
        return when (failure.mode) {
            CardProviderFailure.UNSUPPORTED_NOTE_TYPE ->
                end(SessionState.UNSUPPORTED, failure.mode.specName, failure.detail, false, false)
            CardProviderFailure.COLLECTION_CHANGED, CardProviderFailure.DECK_MISSING ->
                stop(failure.mode.specName, failure.detail)
            else -> pause(failure.mode.specName, failure.detail)
        }
    }

    private fun pause(reason: String, detail: String): Halt =
        end(SessionState.PAUSED, reason, detail, resumable = true, reconciliationRequired = false)

    private fun stop(reason: String, detail: String): Halt =
        end(SessionState.STOPPED, reason, detail, resumable = false, reconciliationRequired = false)

    private fun end(
        next: SessionState,
        reason: String,
        detail: String,
        resumable: Boolean,
        reconciliationRequired: Boolean,
    ): Halt {
        val current = intent
        // No later halt may erase the obligation to reconcile an ambiguous write.
        val required = reconciliationRequired || halt?.reconciliationRequired == true ||
            (
                current != null &&
                    (current.state == ReviewState.SUBMITTING || current.state == ReviewState.OUTCOME_UNKNOWN)
                )
        enter(next)
        invalidate()
        val produced = Halt(next, reason, detail, resumable, required)
        halt = produced
        log(next.specName, if (detail.isEmpty()) reason else "$reason: $detail")
        return produced
    }

    /**
     * Full teardown. Every token is invalidated before anything is cancelled, so a
     * callback that arrives mid-teardown is dropped rather than delivered.
     */
    private fun invalidate() {
        val playback = playbackToken
        val request = gradingRequest
        val turn = answerTurn
        playbackToken = null
        gradingRequest = null
        captureValid = false
        suggestion = null
        intent?.takeIf { it.state == ReviewState.PENDING || it.state == ReviewState.SUBMITTING }?.cancel()
        // Only now: cancellation itself can produce late callbacks.
        playback?.let(speechOutput::cancel)
        if (turn != null && (turn.phase == AnswerPhase.CAPTURING || turn.phase == AnswerPhase.FINALIZING)) {
            turn.cancel()
        }
        request?.let(grader::cancel)
    }

    /**
     * Invalidate the suggestion and any pending rating, keeping the card and the answer
     * turn. Used where the learner acts on the same card rather than leaving it.
     */
    private fun invalidatePending() {
        val request = gradingRequest
        gradingRequest = null
        captureValid = false
        suggestion = null
        val current = intent
        if (current != null && current.state == ReviewState.PENDING) {
            current.cancel()
            intent = null
        }
        request?.let(grader::cancel)
    }

    private fun clearTurn() {
        intent = null
        card = null
        answer = null
        answerTurn = null
        suggestion = null
        startedMs = null
        transcriptRevision = 0
        captureValid = false
    }

    private companion object {
        const val CANCELLED_ANSWER = "answer_cancelled"

        /** AV-014's halt reasons. Neither one writes, rates, buries or suspends anything. */
        const val LEARNER_PAUSED = "learner_paused"
        const val SESSION_FINISHED = "session_finished"
    }
}

/** The session's ordered transcript, in the binding's layout, for evidence and debugging. */
fun ReviewSession.transcriptText(): String =
    events.joinToString("\n") { "  ${it.step.padEnd(17)} ${it.detail}".trimEnd() }
