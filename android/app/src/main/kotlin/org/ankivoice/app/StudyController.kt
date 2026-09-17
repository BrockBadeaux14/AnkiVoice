package org.ankivoice.app

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.ankivoice.core.answer.AnswerPhase
import org.ankivoice.core.answer.AnswerRecovery
import org.ankivoice.core.answer.AnswerStatus
import org.ankivoice.core.commands.CommandContext
import org.ankivoice.core.commands.CommandOutcome
import org.ankivoice.core.commands.CommandRouter
import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.CardProvider
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.ForegroundEvent
import org.ankivoice.core.contracts.ForegroundEventPort
import org.ankivoice.core.contracts.Grader
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.QueueExhausted
import org.ankivoice.core.contracts.ReviewIntent
import org.ankivoice.core.contracts.ReviewOutcome
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.contracts.SpeechInput
import org.ankivoice.core.exchange.ExchangeStep
import org.ankivoice.core.exchange.PrecommitExchange
import org.ankivoice.core.exchange.RatingSource
import org.ankivoice.core.exchange.ratingName
import org.ankivoice.core.grading.GradingSource
import org.ankivoice.core.journal.JournalEntry
import org.ankivoice.core.session.Event
import org.ankivoice.core.session.Halt
import org.ankivoice.core.session.Interruption
import org.ankivoice.core.session.ProposalOutcome
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionResult
import org.ankivoice.core.session.SessionState
import org.ankivoice.provider.GradingRoute

/**
 * One study session as the composition root supplies it: the turn loop, the grader behind
 * it, AV-019's exchange over it, and how to let the platform objects go again.
 *
 * Built on the session's own thread, because [ReviewSession] confines every transition to
 * the thread that constructed it. The controller builds AV-014's router itself, over
 * [speech], so it can see every capture the router opens.
 *
 * [grader] is the same grader the session was built with. The controller calls it on the
 * grading worker rather than through [ReviewSession.grade], which would block the session
 * thread for the whole provider round trip. [revision] mirrors the session's transcript
 * revision for that worker, which may not read the session directly. [gradingSource] and
 * [gradingRoute] name the policy and the route that answered one request, which only the
 * grader that chose them can state; both are read on the grading worker in the same step
 * that took the reply, and a grader that will not say reports null.
 */
internal class StudySession(
    val session: ReviewSession,
    val grader: Grader,
    val exchange: PrecommitExchange,
    val revision: AtomicInteger = AtomicInteger(),
    val gradingSource: (GradingRequest) -> GradingSource? = { null },
    val gradingRoute: (GradingRequest) -> GradingRoute? = { null },
    /**
     * The same transport the session holds. Done and Cancel cross threads to reach it,
     * because the session is confined to the thread the capture is blocking.
     */
    val speech: SpeechInput,
    val language: String,
    /**
     * What the recognizer has heard so far in the open attempt, or null when it has offered
     * nothing yet. Read from the main thread while the session thread is inside the capture,
     * so the transport keeps it as an observation and never as an answer.
     */
    val partial: () -> String?,
    /**
     * The raw recognizer score behind the last delivered transcript, or null where the
     * engine supplied none. Evidence only: `:core`'s classification is what the session sees.
     */
    val rawConfidence: () -> Float? = { null },
    /** AV-010's skipped cards for this session, read on the session thread. */
    val skips: () -> SkipReport = { SkipReport() },
    val release: () -> Unit,
)

/** How the composition root supplies one. [Failure] explains why no session opened. */
internal fun interface StudySessionFactory {
    /** Called on the session's own thread. */
    fun create(): Result<StudySession>
}

/** One AV-047 cancel window that has not fired yet. */
internal fun interface ScheduledWindow {
    /** Stop it if it has not fired. Idempotent, and it never blocks the caller. */
    fun cancel()
}

/**
 * AV-047's cancel window, as the surface measures it.
 *
 * A seam rather than a timer of the controller's own, so a JVM test can run a window to
 * its end, or leave it running across an edit or an interruption, without waiting on a
 * real clock. Stopping a window is best-effort by design: a scheduler cannot recall a task
 * it has already released, so [StudyController] also refuses a released task that no longer
 * applies rather than trusting the cancellation alone.
 */
internal fun interface DelayScheduler {
    /** Run [task] after [delayMs]. It may run on any thread; the task itself hops to the worker. */
    fun schedule(delayMs: Long, task: Runnable): ScheduledWindow
}

/** Why no session could open. Carries AV-007's failure rather than a message alone. */
internal class StudySessionUnavailable(val failure: Failure) : IllegalStateException(failure.toString())

/**
 * The controls the study screen may offer right now. Every one of them is reachable by
 * touch, and none of them writes except [CONFIRM], which reaches the writer only through
 * AV-019's exchange for a confirmation AV-013 accepted.
 *
 * AV-047 adds one control that stops a write rather than making one: [CANCEL_AUTOMATIC]
 * is offered only while an automatic commit is armed, and it writes nothing.
 */
internal enum class StudyControl {
    PLAY_PROMPT, START_ANSWER, DONE, CANCEL_ANSWER, TRY_AGAIN, EDIT_TRANSCRIPT,
    /** Name a rating: a correction while one is pending, a self-grade when none is. */
    RATE,
    REPEAT, REVEAL, PAUSE, RESUME, SKIP, FINISH, CONFIRM, CHANGE,
    NEXT_CARD, UNDO_HANDOFF, REPORT_RECONCILED, RELOAD, SPEAK_COMMAND,

    /** AV-047: stop the automatic commit and keep this card's turn manual. */
    CANCEL_AUTOMATIC,
}

/**
 * One grading reply, bound to the transcript revision it graded. Shown only while that
 * revision is still the session's, and kept per turn for #29's evidence.
 */
internal data class GradingRecord(
    val revision: Int,
    /** [RULE] or [AI]. */
    val source: String,
    /** `free` or `paid` for an AI reply, null for a rule match or an unnamed route. */
    val route: String?,
    val label: String?,
    val proposedRating: Int?,
    val reason: String?,
    /** The grader failure's name when there was no label at all. */
    val failure: String?,
) {
    /** `rule`, `ai-free`, `ai-paid`, `abstain` or `unavailable`: the path the turn's grading took. */
    val path: String
        get() = when {
            failure != null -> UNAVAILABLE
            proposedRating == null -> ABSTAIN
            source == RULE -> RULE
            route == GradingRoute.PAID.specName -> AI_PAID
            else -> AI_FREE
        }

    val status: String get() = StudyWords.grading(this)

    companion object {
        const val RULE = "rule"
        const val AI = "ai"
        const val AI_FREE = "ai-free"
        const val AI_PAID = "ai-paid"
        const val ABSTAIN = "abstain"
        const val UNAVAILABLE = "unavailable"
    }
}

/** A halt, in the learner's words, with the two facts the controls depend on. */
internal data class HaltView(
    /** The session state's name: `paused`, `outcome-unknown`, `interrupted`, `stopped`, `unsupported`, `exhausted`. */
    val kind: String,
    val reason: String,
    val explanation: String,
    val resumable: Boolean,
    val reconciliationRequired: Boolean,
)

/** What the study screen shows. Everything here is derived, never a second copy of the turn. */
internal data class StudyState(
    val running: Boolean = false,
    val busy: Boolean = false,
    /** Where the turn stands, in the learner's words. */
    val status: String = StudyWords.READY,
    /** AV-013's state name, or null before a session is opened. */
    val sessionState: String? = null,
    /** AV-012's phase, so the answer window is visible while it is open. */
    val answerPhase: String? = null,
    val context: CommandContext = CommandContext.UNAVAILABLE,
    /** The commands an on-screen control may run right now, in vocabulary order. */
    val available: List<VoiceCommand> = emptyList(),
    /** Those a spoken command could run right now. Empty inside the answer window. */
    val spokenAvailable: List<VoiceCommand> = emptyList(),
    /** The controls that apply right now, and no others. */
    val controls: Set<StudyControl> = emptySet(),
    val cardId: Long? = null,
    /** The card's Prompt. Never the ReferenceAnswer or the Extra. */
    val prompt: String? = null,
    val permittedRatings: List<Int> = emptyList(),
    /** The ratings the learner may name right now: corrections while one is pending, a self-grade otherwise. */
    val ratings: List<Int> = emptyList(),
    /** True while the transport holds an answer attempt. Done and Cancel stay live on this snapshot. */
    val answering: Boolean = false,
    /** True while a learner-opened command capture is open. */
    val listeningForCommand: Boolean = false,
    /**
     * What the recognizer returned for the last attempt, spelled as it returned it. It is
     * the learner's own words and never card text, and an attempt that produced no
     * transcript leaves it null rather than empty.
     */
    val heard: String? = null,
    /** The settled transcript for this card: a recognizer final or the learner's own edit. */
    val transcript: String? = null,
    /** AV-012's revision of [transcript]: the answer version every suggestion is bound to. */
    val transcriptRevision: Int = 0,
    /** `final` or `user-corrected`, or the failure state of the last attempt. */
    val transcriptKind: String? = null,
    /** A final the recognizer did not vouch for: shown, editable, and not graded as it is. */
    val transcriptNeedsReview: Boolean = false,
    val attempt: Int = 0,
    /** What the last action told the learner. */
    val notice: String? = null,
    val failure: Failure? = null,
    /** A grading request is on the grading worker. */
    val gradingInFlight: Boolean = false,
    /** The grading status for the transcript shown, or null when none applies to it. */
    val grading: GradingRecord? = null,
    /** AV-018 notices that are blocking the start, in reconciliation's own words. */
    val journalNotices: List<String> = emptyList(),
    /** The journal entries those notices belong to, so the learner can acknowledge them. */
    val journalOutstanding: List<Long> = emptyList(),
    /** AV-019: the Announced position, in the words it was spoken in. Null when none is open. */
    val announcement: String? = null,
    /** The pending rating, or null for an abstention and outside the exchange. */
    val pendingRating: Int? = null,
    /** Where [pendingRating] came from: rule, ai, learner, or none for the abstention. */
    val ratingSource: String? = null,
    /** The AV-012 transcript revision the announcement was computed from. */
    val announcedRevision: Int? = null,
    /** True once the pending rating carries a current confirmation, before the write runs. */
    val confirmed: Boolean = false,
    /** AV-047: whether the open session saves grader proposals without a confirmation. */
    val automaticGrading: Boolean = false,
    /**
     * How long the learner has to stop the armed automatic commit, or null when none is
     * armed. Non-null only for a grader proposal, and only while automatic grading is on.
     */
    val autoCommitWindowMs: Long? = null,
    /** True once the learner kept this card's turn manual, while that rating is still waiting. */
    val autoCommitCancelled: Boolean = false,
    /** AV-019: the writer's own outcome for the committed attempt, or null before one. */
    val outcomeState: String? = null,
    /** The writer's own reason, shown as-is for a write that failed or could not be confirmed. */
    val outcomeReason: String? = null,
    /** The halt the session is in, or the one that closed it, in the learner's words. */
    val halt: HaltView? = null,
    /** AV-012's manual controls a paused turn offers. */
    val recovery: List<AnswerRecovery> = emptyList(),
    /** AV-010: what was skipped this session, each in its own announcement. */
    val skipped: List<String> = emptyList(),
    /** AV-010's summary once five consecutive unstudiable cards stopped the session. */
    val skipSummary: String? = null,
    /** Why the last session closed, while none is open: `finished`, `interrupted`, `undo-handoff`, `reconciled`. */
    val closed: String? = null,
) {
    /** True only while AV-012 has an attempt in flight. */
    val capturing: Boolean get() = context == CommandContext.ANSWER

    /** True while the session holds a settled answer that has not been graded yet. */
    val gradable: Boolean get() = sessionState == SessionState.GRADING.specName && !gradingInFlight

    /** AV-019: a confirmed review, so Next card and the native-Undo handoff are offered. */
    val committed: Boolean get() = outcomeState == ReviewState.CONFIRMED.specName &&
        sessionState == SessionState.COMMITTED.specName

    /** AV-019: the write could not be confirmed, so only the learner can say what happened. */
    val reconcileRequired: Boolean get() = sessionState == SessionState.OUTCOME_UNKNOWN.specName

    /** The ratings this card offers while the learner may still name one, kept for the debug-era name. */
    val selfGradable: List<Int> get() = ratings
}

/** One settled attempt, as AV-012 recorded it. */
internal data class RecognitionRecord(
    val attempt: Int,
    val revision: Int,
    val status: String,
    val text: String,
    val confidence: String,
    val failure: String?,
    /** The engine's raw score for this attempt, where the transport reported one. */
    val rawScore: Float? = null,
)

internal data class RatingCorrection(val from: Int, val to: Int)

/**
 * What one turn did, for #29's manual-intervention count. Derived from the session where
 * the session keeps it, and accumulated on the session thread where it does not.
 */
internal data class TurnEvidence(
    val turn: Int,
    val cardId: Long?,
    val transcriptRevision: Int,
    val recognition: List<RecognitionRecord>,
    val retries: Int,
    val transcriptEdits: Int,
    val gradings: List<GradingRecord>,
    /** The last grading's path: `rule`, `ai-free`, `ai-paid`, `abstain` or `unavailable`. */
    val gradingPath: String?,
    /** The rating the learner named when none was pending, or null. */
    val selfGrade: Int?,
    val ratingCorrections: List<RatingCorrection>,
    /** `spoken`, `touch` or AV-047's `auto` once a confirmation was accepted, or null. */
    val confirmationSource: String?,
    /** AV-047: whether the option was on for this turn, whatever the turn then did. */
    val automaticGrading: Boolean,
    /** AV-047: the learner stopped an armed automatic commit on this turn. */
    val automaticCancelled: Boolean,
    /** The writer's outcome for this turn, or null when nothing was committed. */
    val outcome: String?,
    val rating: Int?,
    val touchActions: List<String>,
    val spokenCommands: List<String>,
    val halts: List<String>,
)

/**
 * What a session has recorded, for the study screen's harness and #29's evidence.
 *
 * It carries study content — the prompt and what the learner said — so it is handed to
 * the caller that asked and nowhere else: it is never recorded in AV-020's diagnostics.
 */
internal data class StudyEvidence(
    val sessionId: String,
    val events: List<Event>,
    val outcomes: List<ReviewOutcome>,
    /** AV-018's entries for this session, as the journal holds them. */
    val journal: List<JournalEntry>,
    val turns: List<TurnEvidence>,
    /** Touch actions taken outside any turn: opening, reloading, finishing. */
    val sessionActions: List<String>,
    /** Why the session closed, or null while it is open. */
    val closed: String?,
)

/**
 * The real study surface's controller: one screen's worth of state derived from AV-013's
 * session, AV-019's exchange and AV-014's router, published as immutable snapshots.
 *
 * The session runs on [worker], a thread of its own, because AV-025's transport blocks the
 * caller for the whole of playback and capture. Grading runs on [gradingWorker], because
 * #18's route can block for two provider attempts; its reply crosses back to [worker] and
 * goes through [ReviewSession.acceptGrade], so a reply for a superseded revision is
 * dropped by the session. State crosses back on [delivery] — the main thread in the app —
 * and only immutable snapshots make the trip. Nothing on the screen is a second copy of
 * the turn: every snapshot is read from the session on [worker] and published whole.
 *
 * AV-045: no session opens before [gate] has reconciled the journal in this process, and
 * none opens while an unknown outcome is unacknowledged. [cards] is what reconciliation
 * re-reads the journalled card through. Reload after an interruption goes through the
 * same gate, so a session is restored only after pending writes are reconciled.
 *
 * AV-019: an accepted confirmation submits, here, on [worker]. Every command goes through
 * [PrecommitExchange], which announces the pending rating and its source, applies the
 * re-prompt rule, and runs [ReviewSession.commit] exactly once for an attempt whose
 * confirmation #14 accepted. There is no other path to the writer: a rating, a correction,
 * a self-grade, a transcript edit, a retry, a pause, a skip, a reload and an interruption
 * all leave the collection untouched, and the only announcement of a saved review is made
 * from the [ReviewOutcome] the writer returned.
 *
 * Grading is automatic once an answer settles as gradable, and again after every
 * transcript edit: rules on device first, the AI route only on a rule miss and only when
 * the learner has configured it. A suggestion is advisory; it proposes a rating the
 * learner still confirms, and a failure pauses the turn with the card kept for a self-grade.
 */
internal class StudyController(
    private val factory: StudySessionFactory,
    private val worker: Executor,
    private val delivery: Executor,
    private val gradingWorker: Executor,
    private val gate: ReconciliationGate? = null,
    private val cards: () -> CardProvider? = { null },
    /**
     * AV-047: the clock behind the cancel window. Nothing here measures time itself, so a
     * test can run a window out, or leave it running, without a real delay.
     */
    private val scheduler: DelayScheduler,
    /** AV-018's entries for one session, read on [worker] when evidence is exported. */
    private val journalEntries: (sessionId: String) -> List<JournalEntry> = { emptyList() },
) : ForegroundEventPort {
    /** Written on [delivery], read on [worker] when an action carries the last notice forward. */
    @Volatile
    var state = StudyState()
        private set
    var observer: ((StudyState) -> Unit)? = null

    /** The open session and the router built over it. Touched only on [worker]. */
    private var open: OpenSession? = null

    /**
     * The capture in flight, or null when none is. Written on [worker] around the blocking
     * capture and read on the main thread, so it is volatile.
     */
    @Volatile
    private var capture: ActiveCapture? = null

    /** Set by Done or Cancel on the main thread, read and cleared on [worker] when the capture ends. */
    @Volatile
    private var stoppedByOperator = false

    @Volatile
    private var cancelledByOperator = false

    /**
     * An interruption that arrived while [worker] was blocked inside a capture. The capture
     * is cancelled at once from the main thread; the blocked action applies the interruption
     * as soon as the transport returns, and the queued task behind it finds nothing open.
     */
    @Volatile
    private var pendingInterrupt: Interruption? = null

    /**
     * AV-047: the automatic-commit window in flight, or null.
     *
     * Written on [worker] when one is armed and cleared from either thread when one is
     * stopped, so the fired task can tell a window that still applies from one that was
     * cancelled or superseded while its timer was running.
     */
    @Volatile
    private var autoWindow: AutoWindow? = null

    /**
     * The pending rating the learner kept manual, or null.
     *
     * Held by identity rather than as a flag, so it cannot outlive the rating it describes:
     * a retry, an advance and a fresh proposal all mint a **new** intent, and the surface
     * then stops claiming the learner stopped anything. Touched only on [worker].
     */
    private var keptManual: ReviewIntent? = null

    /** The generation of the action [worker] is running, so it can publish mid-action. */
    private var actionToken = 0L

    /** The last attempt's transcript, shown until another attempt replaces it. Touched on [worker]. */
    private var heard: String? = null

    /**
     * Why the last [start] opened no session. Touched only on [worker], and cleared by the
     * next start, so a stale reason never outlives the attempt that produced it.
     */
    private var unavailable: Failure? = null

    /** The grading request on [gradingWorker], if any. Touched only on [worker]. */
    private var grading = false

    /** The last grading reply, with the card it graded. Shown only while its revision is current. */
    private var lastGrade: Pair<Long?, GradingRecord>? = null

    /** How the last session ended, and the halt it ended in. Touched only on [worker]. */
    private var closed: String? = null
    private var lastHalt: Halt? = null
    private var lastOutcome: ReviewOutcome? = null
    private var lastFailure: Failure? = null
    private var lastEvidence: StudyEvidence? = null

    /** Per-turn evidence for the open session. Touched only on [worker]. */
    private val turns = LinkedHashMap<Int, TurnRecord>()
    private var sessionActions = mutableListOf<String>()
    private var lastSeenHalt: Halt? = null

    @Volatile
    private var foreground = false

    @Volatile
    private var generation = 0L

    init {
        // An acknowledgement made on the setup screen clears the notice here too.
        gate?.listen(::publishJournal)
    }

    // -- opening and closing -------------------------------------------------- //

    /**
     * Open a session and offer the first card, after AV-018's reconciliation gate.
     *
     * Reload after an interruption is this same call: the gate is consulted before any card
     * is offered, so a session is restored only after pending writes are reconciled.
     */
    fun start() {
        val gate = gate
        if (gate == null || !foreground || state.busy) return openSession()
        val token = ++generation
        publish(state.copy(busy = true, status = StudyWords.OPENING))
        gate.open(cards) { report ->
            if (token != generation) return@open
            val notices = state.copy(
                busy = false,
                journalNotices = report.notices,
                journalOutstanding = report.outstanding.map { it.entryId },
            )
            if (report.blocking) {
                publish(
                    notices.copy(
                        status = "Study is blocked until an earlier review is checked.",
                        notice = "Study is blocked: an earlier review is unresolved. Check AnkiDroid, " +
                            "then acknowledge it before starting.",
                        // Acknowledging clears the notice; the way in is then the same reload.
                        controls = setOf(StudyControl.RELOAD),
                    ),
                )
            } else {
                publish(notices)
                openSession()
            }
        }
    }

    /**
     * Close whatever is open and start again through the gate. After an interruption there
     * is nothing open and this is a plain [start]; after a stop that left the session open —
     * a missing deck, a moved card, an exhausted queue — it releases that session first.
     */
    fun reload() {
        if (state.busy) return
        val token = ++generation
        publish(state.copy(busy = true, status = StudyWords.OPENING))
        worker.execute {
            open?.let { current ->
                sessionActions += "reload"
                if (!current.session.halted) current.session.finishSession()
                closeSession(current, "reloading")
            }
            delivery.execute {
                if (token != generation) return@execute
                publish(state.copy(busy = false))
                start()
            }
        }
    }

    /** The learner has checked AnkiDroid for [entryId]. Clearing it never resubmits the review. */
    fun acknowledgeJournalNotice(entryId: Long) {
        gate?.acknowledge(entryId)
    }

    private fun openSession() = act("start") { current ->
        if (!foreground) return@act "AnkiVoice must be in the foreground to study."
        if (current != null) return@act "A session is already open."
        unavailable = null
        closed = null
        lastHalt = null
        lastOutcome = null
        lastFailure = null
        lastGrade = null
        lastSeenHalt = null
        keptManual = null
        turns.clear()
        sessionActions = mutableListOf("start")
        val study = factory.create().getOrElse { error ->
            val failure = (error as? StudySessionUnavailable)?.failure
            unavailable = failure
            return@act failure?.let { StudyWords.unavailable(it) } ?: "Study is unavailable: ${error.message}"
        }
        val opened = OpenSession(study, ObservedSpeech(study.speech, study.partial))
        open = opened
        study.session.start()
        describeOffer(study.session.offerCard())
    }

    /** End the session and let the recognizer and synthesizer go. Finishing writes nothing. */
    fun stop() = act("finish") { current ->
        val opened = current ?: return@act "No session is open."
        if (!opened.session.halted) opened.session.finishSession()
        closeSession(opened, "finished")
        "Session closed. Closing wrote nothing: a review you confirmed was already saved, and " +
            "a rating you left waiting was discarded unwritten."
    }

    // -- the answer ------------------------------------------------------------ //

    /** Play the Prompt, which is what opens AV-012's answer phase. */
    fun ask() = act("play prompt") { current ->
        val session = current?.session ?: return@act NO_SESSION
        when (val spoken = session.ask()) {
            is SessionResult.Produced -> "The prompt was played. Tap Start answer when you are ready."
            is SessionResult.Halted -> "Paused: ${spoken.halt.reason}."
            SessionResult.Ignored -> "That playback belonged to an earlier turn."
        }
    }

    /**
     * The explicit Start answer. Thinking time before it is unbounded.
     *
     * It opens AV-012's window **and** the microphone: AV-025 defines the call to `listen`
     * as this touch. The capture then blocks [worker] for the whole attempt, exactly as
     * playback does, so the open window is published before it blocks and [finishAnswer],
     * [cancelAnswer] and an interruption reach the transport without queueing behind it.
     * Once the answer settles as gradable it is graded at once.
     */
    fun startAnswer() = act("start answer") { current ->
        val opened = current ?: return@act NO_SESSION
        val session = opened.session
        val token = session.startAnswer()
        // The previous attempt's transcript is not this one's, so it goes before the
        // microphone opens rather than being left on screen beside a live capture.
        heard = null
        // Published before the transport blocks, so the surface reports the open window —
        // and offers Done and Cancel — for exactly as long as a capture is running.
        capture = ActiveCapture(opened.study.speech, token, opened.study.partial, CaptureKind.ANSWER)
        opened.observed.answerNext = true
        publish(opened, actionToken, LISTENING_NOTICE, busy = true)
        val event = opened.observed.listen(token, opened.study.language)
        val cancelled = cancelledByOperator
        val stopped = stoppedByOperator
        cancelledByOperator = false
        stoppedByOperator = false
        heard = (event as? CaptureEvent.Transcript)?.text?.takeIf { it.isNotBlank() }
        // Torn down while the microphone was open: the late result is dropped, never applied.
        if (open !== opened) return@act null
        session.answerTurn?.let { turn -> currentTurn(opened).rawScores[turn.attempt] = opened.study.rawConfidence() }
        pendingInterrupt?.let { kind ->
            pendingInterrupt = null
            return@act interruptNow(opened, kind)
        }
        if (cancelled) {
            currentTurn(opened).touchActions += "cancel"
            val halt = session.cancelAnswer()
            return@act "Cancelled: ${halt.detail}."
        }
        if (stopped) {
            currentTurn(opened).touchActions += "done"
            // An operator Done is recorded as AV-012's Done rather than as a window expiry.
            session.finishAnswer()
        }
        val notice = when (val settled = session.acceptCapture(event)) {
            is SessionResult.Produced -> "Answer settled as ${settled.value.status.specName}."
            is SessionResult.Halted -> "Paused: ${settled.halt.reason}."
            SessionResult.Ignored -> "That capture belonged to an earlier attempt."
        }
        if (session.state == SessionState.GRADING) startGrading(opened)
        notice
    }

    /**
     * The explicit Done. It stops the microphone; it is not a verdict about the answer.
     *
     * With an attempt in flight it goes straight to the transport, which accepts a stop
     * from any thread. Queued behind the capture it would be dropped by the busy gate and
     * do nothing at all. The turn settles on [worker] as soon as the capture returns.
     */
    fun finishAnswer() {
        capture?.takeIf { it.kind == CaptureKind.ANSWER }?.let { active ->
            stoppedByOperator = true
            active.speech.finishAnswer(active.token)
            return
        }
        act("done") { current ->
            val session = current?.session ?: return@act NO_SESSION
            when (val settled = session.finishAnswer()) {
                is SessionResult.Produced -> "Answer settled as ${settled.value.status.specName}."
                is SessionResult.Halted -> "Paused: ${settled.halt.reason}."
                SessionResult.Ignored -> "That capture belonged to an earlier attempt."
            }
        }
    }

    /** The explicit Cancel. The card is kept and nothing is inferred from a cancelled answer. */
    fun cancelAnswer() {
        capture?.takeIf { it.kind == CaptureKind.ANSWER }?.let { active ->
            cancelledByOperator = true
            active.speech.cancel(active.token)
            return
        }
        act("cancel") { current ->
            val session = current?.session ?: return@act NO_SESSION
            "Cancelled: ${session.cancelAnswer().detail}."
        }
    }

    /** AV-012's Try again: a new revision for the same card, waiting on the next Start answer. */
    fun retry() = act("try again") { current ->
        val session = current?.session ?: return@act NO_SESSION
        heard = null
        session.retry()
        "Ready to try again. Tap Start answer when you are ready to speak."
    }

    /**
     * A typed correction, or the learner accepting a transcript the recognizer did not
     * vouch for. It creates a new revision, which retires any suggestion, pending rating and
     * confirmation bound to the old one, and the new revision is graded at once.
     */
    fun editTranscript(text: String) = act("edit transcript") { current ->
        val opened = current ?: return@act NO_SESSION
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@act "Type the answer first; an empty transcript cannot be graded."
        opened.session.correctTranscript(trimmed)
        startGrading(opened)
        "Transcript updated to answer version ${opened.session.transcriptRevision}. Any earlier " +
            "suggestion or pending rating no longer applies."
    }

    /**
     * Grade the settled answer — rules on device, the route only on a rule miss. Automatic
     * after every settled answer and edit; exposed for a harness that drives the turn.
     */
    fun grade() = act("grade") { current ->
        val opened = current ?: return@act NO_SESSION
        startGrading(opened) ?: "This answer is already being graded."
    }

    /**
     * What the recognizer has heard so far in the open attempt, or null when there is no
     * attempt or it has offered nothing yet.
     *
     * Read on the main thread while [worker] is inside the capture. It is progress to show,
     * never a result: AV-012 settles on a final alone, and a partial that no final follows
     * settles nothing.
     */
    fun hearing(): String? = capture?.partial?.invoke()?.takeIf { it.isNotBlank() }

    // -- the commands ----------------------------------------------------------- //

    /**
     * The touch equivalent for [command]. Every command in the vocabulary has one.
     *
     * AV-019: the outcome goes through the exchange, which re-announces a corrected rating
     * and commits an accepted confirmation. An explicit gesture carries no recognition
     * confidence, so a touched Confirm is the route that always works.
     */
    fun run(command: VoiceCommand) = act(command.specName) { current ->
        val opened = current ?: return@act NO_SESSION
        val pending = pendingRating(opened.session)
        val notice = opened.exchange.onCommand(opened.router.touch(command)).notice
        command.rating?.let { recordRating(opened, pending, it) }
        notice
    }

    /** One learner-opened command capture, through AV-025's transport. */
    fun listenForCommand() = act("speak a command") { current ->
        val opened = current ?: return@act NO_SESSION
        val pending = pendingRating(opened.session)
        val outcome = opened.router.listenForCommand()
        if (open !== opened) return@act null
        val score = opened.study.rawConfidence()?.let { " score $it" } ?: ""
        currentTurn(opened).spokenCommands += describe(outcome) + score
        pendingInterrupt?.let { kind ->
            pendingInterrupt = null
            return@act interruptNow(opened, kind)
        }
        val notice = opened.exchange.onCommand(outcome).notice
        (outcome as? CommandOutcome.Executed)?.command?.rating?.let { recordRating(opened, pending, it) }
        notice
    }

    /**
     * Name a rating by touch. While one is pending it is a correction through #14's own
     * path; when none is — after an abstention or a grading fault — it is the self-grade,
     * announced as learner-named. Either way a separate explicit confirmation is still
     * required, and nothing is written here.
     */
    fun rate(rating: Int) = act("rate ${ratingName(rating).lowercase()}") { current ->
        val opened = current ?: return@act NO_SESSION
        val command = VoiceCommand.entries.firstOrNull { it.rating == rating }
            ?: return@act "There is no rating $rating."
        val pending = pendingRating(opened.session)
        val notice = if (command in opened.router.available()) {
            opened.exchange.onCommand(opened.router.touch(command)).notice
        } else {
            when (opened.session.selfGrade(rating)) {
                is ProposalOutcome.Proposed -> opened.exchange.announceLearnerRating().notice
                is ProposalOutcome.Rejected ->
                    "This card did not offer ${ratingName(rating)}, so nothing was proposed."
            }
        }
        recordRating(opened, pending, rating)
        notice
    }

    /** The learner's own rating when no grader offered one. Kept for the debug-era name; see [rate]. */
    fun selfGrade(rating: Int) = rate(rating)

    /**
     * AV-047's Keep it manual: stop the automatic commit and hand this card back.
     *
     * The timer is stopped from the calling thread **first**, so a window it has not
     * released yet can no longer run at all. The window it may already have released is
     * stopped by the same call marking it, and that mark is what the released task reads
     * before it reaches the writer — so a cancel that arrives after the timer fired, but
     * before its queued task runs, still writes nothing.
     *
     * Nothing about the option itself changes: the pending rating stays on screen, stays
     * correctable, and the next card is offered automatically again.
     */
    fun cancelAutomaticCommit() {
        stopAutomaticWindow()
        act("keep it manual") { current ->
            val opened = current ?: return@act NO_SESSION
            val step = opened.exchange.cancelAutomatic()
            if (step is ExchangeStep.KeptManual) {
                keptManual = opened.session.intent
                currentTurn(opened).automaticCancelled = true
            }
            step.notice
        }
    }

    /** Only a confirmed review advances, and the next card is read from AnkiDroid afresh. */
    fun nextCard() = act("next card") { current ->
        val opened = current ?: return@act NO_SESSION
        captureTurn(opened)
        opened.session.advance()
        lastGrade = null
        heard = null
        describeOffer(opened.session.offerCard())
    }

    /**
     * AV-007's post-commit correction: AnkiDroid's own Undo, and nothing else.
     *
     * The session stops and is released, so the way back in is a fresh one that re-reads
     * the card and its scheduling. Nothing here undoes, re-rates or compensates for the
     * review that was written.
     */
    fun handOffToUndo() = act("undo in ankidroid") { current ->
        val opened = current ?: return@act "No session is open."
        opened.session.requestCorrectionAfterCommit()
        closeSession(opened, "undo-handoff")
        "Open AnkiDroid and use its own Undo. AnkiVoice cannot take a review back and will not " +
            "re-rate it or write a correcting review. AnkiDroid's Undo may no longer offer this " +
            "review after other activity in AnkiDroid, or after either app's process is closed " +
            "or replaced. This session is closed: starting again reads the card and its " +
            "scheduling from AnkiDroid afresh, and nothing resumes the stopped one."
    }

    /**
     * The learner reports what AnkiDroid shows after an unknown outcome.
     *
     * It resolves nothing on its own and never resubmits: it records what the learner saw
     * and closes the session so the collection is read again from scratch. AV-018's
     * journal entry stays outstanding until it is acknowledged on a surface.
     */
    fun reportReconciled(saved: Boolean) = act(if (saved) "report saved" else "report not saved") { current ->
        val opened = current ?: return@act "No session is open."
        opened.session.reconcile(saved)
        closeSession(opened, "reconciled")
        val seen = if (saved) {
            "Recorded: you saw the review in AnkiDroid."
        } else {
            "Recorded: the review is not in AnkiDroid. AnkiVoice will not send it again on its " +
                "own; rate the card in AnkiDroid, or study it here again."
        }
        "$seen This session is closed: start again to reload the collection."
    }

    // -- evidence ------------------------------------------------------------------ //

    /**
     * The session's events, outcomes, journal entries and per-turn record, copied on the
     * session thread and delivered on [delivery]. While a session is open it is that
     * session's; afterwards it is the last session's, so a harness can export a turn that
     * ended by closing. Null before any session opened. The copy goes to [callback] only.
     */
    fun evidence(callback: (StudyEvidence?) -> Unit) {
        worker.execute {
            val evidence = open?.let { buildEvidence(it) } ?: lastEvidence
            delivery.execute { callback(evidence) }
        }
    }

    // -- the foreground ------------------------------------------------------------ //

    override fun onForegroundEvent(event: ForegroundEvent) {
        when (event) {
            ForegroundEvent.RESUME -> foreground = true
            ForegroundEvent.PAUSE, ForegroundEvent.STOP -> {
                foreground = false
                // AV-007: leaving the foreground breaks the single-active-reviewer
                // precondition. The session stops and the microphone is released; it is
                // never left open behind another app.
                interrupt(Interruption.APP_SWITCH)
            }
        }
    }

    /** The screen locked. The same halt as an app switch, recorded as what it was. */
    fun onScreenLocked() {
        foreground = false
        interrupt(Interruption.LOCK)
    }

    /**
     * Interrupt the open session. A capture in flight is cancelled from here, so the
     * microphone is released now rather than when the answer window would have expired;
     * the blocked action applies the interruption when the transport returns, and the
     * task queued behind it then finds nothing open. The first interruption to arrive is
     * the one recorded.
     */
    private fun interrupt(kind: Interruption) {
        if (pendingInterrupt == null) pendingInterrupt = kind
        capture?.let { it.speech.cancel(it.token) }
        // AV-047: the microphone and the cancel window are both released from here, so an
        // interruption cannot be followed by an automatic write into a stopped session.
        stopAutomaticWindow()
        val token = ++generation
        worker.execute {
            val current = open ?: run {
                pendingInterrupt = null
                return@execute
            }
            pendingInterrupt = null
            interruptNow(current, kind)
            publish(open, token, StudyWords.interruption(kind))
        }
    }

    /** Runs on [worker]. A paused turn is still interrupted; a halt no command may leave stands. */
    private fun interruptNow(current: OpenSession, kind: Interruption): String {
        val session = current.session
        if (!session.halted || session.state == SessionState.PAUSED) session.interrupt(kind)
        closeSession(current, "interrupted")
        return StudyWords.interruption(kind)
    }

    // -- internals ------------------------------------------------------------------ //

    /**
     * Run one action on the session thread and publish what the turn looks like afterwards.
     *
     * A reply from an older generation is dropped, so an action that was in flight when the
     * app left the foreground can never repaint the surface behind a newer one. Every action
     * is a touch on the study screen, and is recorded as one for #29.
     */
    private fun act(label: String, action: (OpenSession?) -> String?) {
        if (state.busy) return
        val token = ++generation
        publish(state.copy(busy = true))
        worker.execute {
            actionToken = token
            val opened = open
            if (opened != null) currentTurn(opened).touchActions += label else sessionActions += label
            val notice = try {
                action(open)
            } catch (e: IllegalStateException) {
                // A guard the surface should not have offered. Report it rather than
                // crashing the screen, and leave the turn exactly as it was.
                "$label is not available here: ${e.message}"
            } catch (e: IllegalArgumentException) {
                "$label is not available here: ${e.message}"
            }
            publish(open, token, notice)
        }
    }

    /**
     * Open one grading request for the current revision on [gradingWorker]. Null when one
     * is already in flight. Runs on [worker].
     */
    private fun startGrading(opened: OpenSession): String? {
        if (grading) return null
        val session = opened.session
        val request = session.beginGrade()
        opened.study.revision.set(session.transcriptRevision)
        grading = true
        gradingWorker.execute {
            val reply = try {
                opened.study.grader.grade(request)
            } catch (e: RuntimeException) {
                // A grader that throws has not graded. It is a failure, never a label.
                GradingReply(request, Failure(GraderFailure.PROVIDER_ERROR, "grading could not run: ${e.javaClass.simpleName}"))
            }
            // Read in the same step that took the reply, on the worker that owns the grader.
            val source = opened.study.gradingSource(request)
            val route = opened.study.gradingRoute(request)
            worker.execute {
                // A session closed or replaced while grading was in flight gets nothing.
                if (open !== opened) return@execute
                grading = false
                val accepted = session.acceptGrade(reply)
                val notice = openExchange(opened, request, accepted, source, route)
                // AV-047: a grader proposal is the only thing that arms a window, so this
                // is the one place that starts one.
                armAutomatic(opened)
                // A reply the session dropped as stale means the transcript moved on while
                // it was in flight; the revision that replaced it is graded now.
                if (accepted is SessionResult.Ignored && session.state == SessionState.GRADING) startGrading(opened)
                publish(open, generation, notice)
            }
        }
        return "Checking your answer: on-device rules first, the AI route only if no rule matches."
    }

    /**
     * AV-047: start the cancel window for whatever the exchange armed. Runs on [worker].
     *
     * The controller owns the clock and the exchange owns whether there is anything left
     * to save, so the fired task asks it again rather than acting on what was true when
     * the window opened. Three things must all still hold when it runs: this window is
     * still the current one, nothing stopped it, and the session is the one it was armed
     * for. The exchange then re-derives the armed commit a fourth time.
     */
    private fun armAutomatic(opened: OpenSession) {
        stopAutomaticWindow()
        val armed = opened.exchange.armed ?: return
        keptManual = null
        val window = AutoWindow()
        autoWindow = window
        window.handle = scheduler.schedule(armed.cancelWindowMs) {
            worker.execute {
                if (autoWindow !== window || window.stopped || open !== opened) return@execute
                autoWindow = null
                // A guard the window should not have reached. Report it and leave the turn
                // as it was, rather than letting it take the session's own thread down and
                // the surface with it: this task runs outside [act]'s reporting.
                val notice = try {
                    opened.exchange.commitAutomatically().notice
                } catch (e: IllegalStateException) {
                    "Automatic grading did not run here: ${e.message}"
                } catch (e: IllegalArgumentException) {
                    "Automatic grading did not run here: ${e.message}"
                }
                publish(open, generation, notice)
            }
        }
    }

    /** Stop the armed window, from either thread. Safe to call when none is armed. */
    private fun stopAutomaticWindow() {
        val window = autoWindow ?: return
        window.stopped = true
        autoWindow = null
        window.handle?.cancel()
    }

    /**
     * AV-019: open the pre-commit exchange from what the grader answered, and record it.
     *
     * A label that proposes a rating this card offers opens Announced with that rating and
     * the policy that produced it. Everything else — `partial`, `uncertain`, a rating the
     * card withdrew, a grading fault, an exhausted quota — opens Announced with **no**
     * pending rating, which is announced as an abstention and never as a rating. A grader
     * that will not name its route is treated the same way: AV-019 shows no rating whose
     * source it cannot state. The grader's reason is shown on this screen and never spoken.
     */
    private fun openExchange(
        opened: OpenSession,
        request: GradingRequest,
        graded: SessionResult<GradingResult>,
        source: GradingSource?,
        route: GradingRoute?,
    ): String {
        val session = opened.session
        val sourceName = when (source) {
            GradingSource.RULE -> GradingRecord.RULE
            GradingSource.AI, null -> GradingRecord.AI
        }
        val routeName = route?.specName?.takeIf { source == GradingSource.AI }
        return when (graded) {
            is SessionResult.Produced -> {
                val permitted = session.card?.permittedRatings.orEmpty()
                val rating = graded.value.proposedRating(permitted)
                val label = graded.value.label.specName
                record(opened, GradingRecord(request.transcriptRevision, sourceName, routeName, label, rating, graded.value.reason, null))
                when {
                    rating == null -> opened.exchange.abstain("the grader answered $label")
                    source == null -> opened.exchange.abstain("the grader did not say which policy answered")
                    else -> opened.exchange.openWithProposal(rating, source.asRatingSource())
                }.notice
            }
            is SessionResult.Halted -> {
                val failure = session.lastFailure?.mode?.specName ?: graded.halt.reason
                record(opened, GradingRecord(request.transcriptRevision, sourceName, routeName, null, null, null, failure))
                opened.exchange.abstain("no grade: ${graded.halt.reason}").notice
            }
            SessionResult.Ignored -> "That grade belonged to an earlier answer, so it was dropped."
        }
    }

    private fun record(opened: OpenSession, grade: GradingRecord) {
        lastGrade = opened.session.card?.identity?.cardId to grade
        currentTurn(opened).gradings += grade
    }

    /** A rating the learner named: a correction of the one pending, or a self-grade when none was. */
    private fun recordRating(opened: OpenSession, pendingBefore: Int?, rating: Int) {
        val now = pendingRating(opened.session) ?: return
        if (now != rating) return
        val turn = currentTurn(opened)
        if (pendingBefore == null) turn.selfGrade = rating
        else if (pendingBefore != rating) turn.ratingCorrections += RatingCorrection(pendingBefore, rating)
    }

    private fun pendingRating(session: ReviewSession): Int? = session.intent
        ?.takeIf { session.state == SessionState.PROPOSING && it.state == ReviewState.PENDING }
        ?.rating

    /** Runs on [worker]. Builds the evidence, releases the platform objects and forgets the session. */
    private fun closeSession(current: OpenSession, reason: String) {
        // AV-047: a window that outlives its session would find nothing to write, but it
        // is stopped here anyway rather than left running behind a released synthesizer.
        stopAutomaticWindow()
        val session = current.session
        lastHalt = session.halt
        lastOutcome = current.exchange.outcome
        lastFailure = session.lastFailure
        closed = reason
        lastEvidence = buildEvidence(current).copy(closed = reason)
        current.study.release()
        open = null
        grading = false
        heard = null
        lastGrade = null
        keptManual = null
        capture = null
    }

    private fun buildEvidence(current: OpenSession): StudyEvidence {
        captureTurn(current)
        val session = current.session
        return StudyEvidence(
            sessionId = session.sessionId,
            events = session.events.toList(),
            outcomes = session.outcomes.toList(),
            journal = journalEntries(session.sessionId),
            turns = turns.values.map { it.snapshot() },
            sessionActions = sessionActions.toList(),
            closed = null,
        )
    }

    private fun currentTurn(current: OpenSession): TurnRecord {
        val turn = current.session.answerTurn?.turn ?: (turns.keys.maxOrNull() ?: 0)
        return turns.getOrPut(turn) { TurnRecord(turn) }
    }

    /** Copy what the session knows about the open turn into its record. Runs on [worker]. */
    private fun captureTurn(current: OpenSession?) {
        val session = current?.session ?: return
        val turn = session.answerTurn ?: return
        val record = turns.getOrPut(turn.turn) { TurnRecord(turn.turn) }
        session.card?.identity?.cardId?.let { record.cardId = it }
        record.transcriptRevision = session.transcriptRevision
        record.recognition = turn.history.map {
            RecognitionRecord(
                it.attempt, it.transcriptRevision, it.status.specName, it.text, it.confidence.specName,
                it.failure?.mode?.specName, record.rawScores[it.attempt],
            )
        }
        record.retries = turn.retries
        record.transcriptEdits = turn.corrections
        // AV-047: recorded per turn, whatever the turn then did, so a run can be read back
        // and an automatic commit told from one the learner confirmed.
        record.automaticGrading = current.exchange.automatic.enabled
        session.intent?.let { intent ->
            intent.confirmation?.let { record.confirmationSource = it.source.specName }
            if (intent.state != ReviewState.PENDING) record.rating = intent.rating
        }
        current.exchange.settled?.let { record.outcome = it.state.specName }
        val halt = session.halt
        if (halt != null && halt !== lastSeenHalt) {
            record.halts += halt.reason
            lastSeenHalt = halt
        }
    }

    /**
     * Read the turn on the session thread; publish the immutable snapshot on the main one.
     *
     * [busy] stays true for a snapshot published from inside an action that has not
     * finished — an open capture is the one that matters — so the surface reports the turn
     * without offering controls the blocked session thread could not run.
     */
    private fun publish(current: OpenSession?, token: Long, notice: String?, busy: Boolean = false) {
        val study = current?.study
        val session = study?.session
        val router = current?.router
        // The grading worker reads this to abandon a retry across an edit.
        session?.let { study.revision.set(it.transcriptRevision) }
        captureTurn(current)
        // AV-019: the Announced position as the exchange derives it right now. A superseded
        // revision, a cancelled intent or a committed attempt all leave it null, so the
        // surface can never show a rating that is no longer waiting.
        val position = study?.exchange?.position
        // AV-047: re-derived here, like the position it belongs to. A window whose rating
        // is gone is stopped rather than left to fire into an exchange that would refuse it.
        val armed = study?.exchange?.armed
        if (current != null && armed == null) stopAutomaticWindow()
        val committed = study?.exchange?.settled
        val active = capture
        val answer = session?.answer
        val halt = haltView(session, study?.exchange?.outcome)
        val grade = lastGrade?.takeIf { (cardId, record) ->
            session != null && cardId == session.card?.identity?.cardId && record.revision == session.transcriptRevision
        }?.second
        val skips = study?.skips?.invoke() ?: SkipReport()
        val ratings = if (session != null && router != null) ratings(session, router) else emptyList()
        val turn = StudyState(
            running = current != null,
            busy = busy,
            status = status(session, active, halt),
            sessionState = session?.state?.specName,
            answerPhase = session?.answerTurn?.phase?.specName ?: session?.let { AnswerPhase.THINKING.specName },
            context = router?.context() ?: CommandContext.UNAVAILABLE,
            available = router?.available().orEmpty(),
            spokenAvailable = router?.spokenAvailable().orEmpty(),
            controls = controls(current, active, ratings),
            cardId = session?.card?.identity?.cardId,
            prompt = session?.card?.fields?.prompt,
            permittedRatings = session?.card?.permittedRatings.orEmpty(),
            ratings = ratings,
            answering = active?.kind == CaptureKind.ANSWER,
            listeningForCommand = active?.kind == CaptureKind.COMMAND,
            heard = heard,
            transcript = answer?.takeIf { it.status == AnswerStatus.FINAL || it.status == AnswerStatus.USER_CORRECTED }?.text,
            transcriptRevision = session?.transcriptRevision ?: 0,
            transcriptKind = answer?.status?.specName,
            transcriptNeedsReview = answer?.needsLearnerReview == true,
            attempt = session?.answerTurn?.attempt ?: 0,
            notice = notice,
            failure = session?.lastFailure ?: unavailable ?: (if (current == null) lastFailure else null),
            gradingInFlight = grading,
            grading = grade,
            announcement = position?.text,
            pendingRating = position?.rating,
            ratingSource = position?.source?.specName,
            announcedRevision = position?.transcriptRevision,
            confirmed = session?.intent?.hasConfirmation() == true,
            automaticGrading = study?.exchange?.automatic?.enabled == true,
            autoCommitWindowMs = armed?.cancelWindowMs,
            // Only while the rating the learner kept manual is the one still pending.
            autoCommitCancelled = keptManual != null && keptManual === session?.intent && armed == null,
            outcomeState = committed?.state?.specName,
            outcomeReason = committed?.reason,
            halt = halt,
            recovery = session?.recoveryOptions.orEmpty(),
            skipped = skips.skipped.map { it.announcement.text },
            skipSummary = skips.summary,
            closed = if (current == null) closed else null,
        )
        delivery.execute {
            if (token != generation) return@execute
            // The journal notices belong to the gate, not the turn, so they carry forward.
            val snapshot = turn.copy(
                notice = turn.notice ?: state.notice,
                journalNotices = state.journalNotices,
                journalOutstanding = state.journalOutstanding,
            )
            state = snapshot
            observer?.invoke(snapshot)
        }
    }

    private fun publish(next: StudyState) {
        state = next
        observer?.invoke(next)
    }

    private fun publishJournal(report: JournalReport) {
        val remaining = report.outstanding.map { it.entryId }
        publish(
            state.copy(
                journalOutstanding = remaining,
                journalNotices = if (remaining.isEmpty()) emptyList() else state.journalNotices,
            ),
        )
    }

    /** The halt the open session is in, or the one that closed the last session. */
    private fun haltView(session: ReviewSession?, outcome: ReviewOutcome?): HaltView? {
        if (session != null) {
            if (!session.halted) return null
            val halt = session.halt ?: return null
            return HaltView(
                session.state.specName, halt.reason,
                StudyWords.explain(halt, session.lastFailure, outcome),
                halt.resumable, halt.reconciliationRequired,
            )
        }
        val ended = lastHalt ?: return null
        return HaltView(
            ended.state.specName, ended.reason,
            StudyWords.explain(ended, lastFailure, lastOutcome),
            resumable = false, reconciliationRequired = ended.reconciliationRequired,
        )
    }

    private fun status(session: ReviewSession?, active: ActiveCapture?, halt: HaltView?): String {
        if (session == null) {
            unavailable?.let { return StudyWords.unavailable(it) }
            return halt?.explanation ?: if (closed == null) StudyWords.READY else "Session closed."
        }
        if (active != null) {
            return if (active.kind == CaptureKind.ANSWER) StudyWords.LISTENING_ANSWER else StudyWords.LISTENING_COMMAND
        }
        halt?.let { return it.explanation }
        return StudyWords.status(session.state, session.answerTurn?.phase, grading)
    }

    /**
     * The ratings the learner may name right now: through #14's rating commands where the
     * router offers them, otherwise through the self-grade a grading fault leaves as the
     * only route. Empty while a rating is pending and confirmed, and outside a turn.
     */
    private fun ratings(session: ReviewSession, router: CommandRouter): List<Int> {
        val card = session.card ?: return emptyList()
        val available = router.available()
        val commands = card.permittedRatings.filter { rating -> ratingCommand(rating) in available }
        if (commands.isNotEmpty()) return commands
        if (session.answer?.gradable != true) return emptyList()
        if (session.intent?.state == ReviewState.PENDING) return emptyList()
        val namable = session.state == SessionState.GRADING || AnswerRecovery.SELF_GRADE in session.recoveryOptions
        return if (namable) card.permittedRatings else emptyList()
    }

    /** Every control that applies to the turn as it stands, and no other. Runs on [worker]. */
    private fun controls(current: OpenSession?, active: ActiveCapture?, ratings: List<Int>): Set<StudyControl> {
        val session = current?.session
            ?: return if (closed != null || unavailable != null) setOf(StudyControl.RELOAD) else emptySet()
        val out = linkedSetOf<StudyControl>()
        if (active != null) {
            if (active.kind == CaptureKind.ANSWER) {
                out += StudyControl.DONE
                out += StudyControl.CANCEL_ANSWER
            }
            return out
        }
        val router = current.router
        val turn = session.answerTurn
        when (session.state) {
            SessionState.ASKING -> out += StudyControl.PLAY_PROMPT
            SessionState.LISTENING -> if (turn?.phase == AnswerPhase.THINKING) out += StudyControl.START_ANSWER
            SessionState.RETRYING -> out += StudyControl.START_ANSWER
            SessionState.COMMITTED -> {
                out += StudyControl.NEXT_CARD
                out += StudyControl.UNDO_HANDOFF
            }
            SessionState.OUTCOME_UNKNOWN -> {
                out += StudyControl.REPORT_RECONCILED
                return out
            }
            else -> Unit
        }
        val attempted = (turn?.attempt ?: 0) > 0
        val recovery = session.recoveryOptions
        val retryable = session.state == SessionState.GRADING || session.state == SessionState.PROPOSING ||
            AnswerRecovery.TRY_AGAIN in recovery
        if (attempted && retryable) out += StudyControl.TRY_AGAIN
        val editable = session.state == SessionState.GRADING || session.state == SessionState.PROPOSING ||
            session.state == SessionState.RETRYING || AnswerRecovery.TYPED_CORRECTION in recovery
        if (attempted && editable) out += StudyControl.EDIT_TRANSCRIPT
        if (ratings.isNotEmpty()) out += StudyControl.RATE
        for (command in router.available()) {
            when (command) {
                VoiceCommand.REPEAT -> out += StudyControl.REPEAT
                VoiceCommand.REVEAL -> out += StudyControl.REVEAL
                VoiceCommand.PAUSE -> out += StudyControl.PAUSE
                VoiceCommand.RESUME -> out += StudyControl.RESUME
                VoiceCommand.FINISH_SESSION -> out += StudyControl.FINISH
                VoiceCommand.SKIP -> out += StudyControl.SKIP
                VoiceCommand.CONFIRM -> out += StudyControl.CONFIRM
                VoiceCommand.CHANGE -> out += StudyControl.CHANGE
                VoiceCommand.RATE_AGAIN, VoiceCommand.RATE_HARD,
                VoiceCommand.RATE_GOOD, VoiceCommand.RATE_EASY,
                -> Unit
            }
        }
        // AV-047: offered only while a window is actually armed, so it never appears as a
        // control that would do nothing, and it never appears while the option is off.
        if (current.exchange.armed != null) out += StudyControl.CANCEL_AUTOMATIC
        if (router.spokenAvailable().isNotEmpty()) out += StudyControl.SPEAK_COMMAND
        // A stop that left the session open — a missing deck, a moved card, an exhausted
        // queue — offers the way out and the way back in, and nothing that writes.
        if (session.halted && session.halt?.resumable == false) {
            out += StudyControl.RELOAD
            out += StudyControl.FINISH
        }
        return out
    }

    private fun describeOffer(offered: SessionResult<*>): String = when (offered) {
        is SessionResult.Produced -> when (val value = offered.value) {
            is ScheduledCard -> "Card ${value.identity.cardId} is ready. Play the prompt to begin."
            QueueExhausted -> "The queue is finished."
            else -> "Nothing was offered."
        }
        is SessionResult.Halted -> "Paused: ${offered.halt.reason} — ${offered.halt.detail}"
        SessionResult.Ignored -> "Nothing was offered."
    }

    private fun describe(outcome: CommandOutcome): String = when (outcome) {
        is CommandOutcome.Executed -> "${outcome.command.specName}: executed (${outcome.source.specName})"
        is CommandOutcome.Refused -> "${outcome.command?.specName ?: "none"}: refused (${outcome.reason.specName})"
        is CommandOutcome.AnswerText -> "answer text"
    }

    /**
     * AV-047: one armed cancel window.
     *
     * Identity is what makes it safe: [autoWindow] holds the current one, so a task whose
     * window was replaced or cleared can see that it is no longer the one to act on, and
     * [stopped] closes the remaining gap where a timer fired before the learner's cancel
     * reached it.
     */
    private class AutoWindow {
        @Volatile
        var stopped = false

        /** Set on [worker] immediately after scheduling, read when the window is stopped. */
        @Volatile
        var handle: ScheduledWindow? = null
    }

    /** One capture in flight: what to stop or cancel, which attempt, and whose it is. */
    private class ActiveCapture(
        val speech: SpeechInput,
        val token: OperationToken,
        val partial: () -> String?,
        val kind: CaptureKind,
    )

    private enum class CaptureKind { ANSWER, COMMAND }

    /**
     * The transport as the controller sees it: every capture the session or the router
     * opens is tracked while it blocks, so Done, Cancel and an interruption can reach it
     * from the main thread. Nothing is changed on the way through.
     */
    private inner class ObservedSpeech(
        private val delegate: SpeechInput,
        private val partialText: () -> String?,
    ) : SpeechInput {
        /** Set by [startAnswer] before it calls listen; every other listen is a command capture. */
        var answerNext = false

        override fun listen(token: OperationToken, language: String): CaptureEvent {
            val kind = if (answerNext) CaptureKind.ANSWER else CaptureKind.COMMAND
            answerNext = false
            capture = ActiveCapture(delegate, token, partialText, kind)
            return try {
                delegate.listen(token, language)
            } finally {
                capture = null
            }
        }

        override fun finishAnswer(token: OperationToken) = delegate.finishAnswer(token)

        override fun cancel(token: OperationToken) = delegate.cancel(token)
    }

    /** One open session with the router the controller built over its observed transport. */
    private class OpenSession(val study: StudySession, val observed: ObservedSpeech) {
        val session: ReviewSession get() = study.session
        val exchange: PrecommitExchange get() = study.exchange
        val router: CommandRouter = CommandRouter(study.session, observed, study.language)
    }

    private class TurnRecord(val turn: Int) {
        var cardId: Long? = null
        var transcriptRevision = 0
        var recognition: List<RecognitionRecord> = emptyList()
        val rawScores = mutableMapOf<Int, Float?>()
        var retries = 0
        var transcriptEdits = 0
        val gradings = mutableListOf<GradingRecord>()
        var selfGrade: Int? = null
        val ratingCorrections = mutableListOf<RatingCorrection>()
        var confirmationSource: String? = null
        var automaticGrading = false
        var automaticCancelled = false
        var outcome: String? = null
        var rating: Int? = null
        val touchActions = mutableListOf<String>()
        val spokenCommands = mutableListOf<String>()
        val halts = mutableListOf<String>()

        fun snapshot() = TurnEvidence(
            turn, cardId, transcriptRevision, recognition, retries, transcriptEdits,
            gradings.toList(), gradings.lastOrNull()?.path, selfGrade, ratingCorrections.toList(),
            confirmationSource, automaticGrading, automaticCancelled,
            outcome, rating, touchActions.toList(), spokenCommands.toList(), halts.toList(),
        )
    }

    private companion object {
        const val NO_SESSION = "Start a session first."

        /** What the surface says while the microphone is open, before the attempt settles. */
        const val LISTENING_NOTICE = "Listening for your answer. Command words spoken now are part of the answer."

        fun ratingCommand(rating: Int): VoiceCommand? = VoiceCommand.entries.firstOrNull { it.rating == rating }
    }
}

/** AV-016's policy names, in AV-019's words. The two enums are deliberately separate. */
private fun GradingSource.asRatingSource(): RatingSource = when (this) {
    GradingSource.RULE -> RatingSource.RULE
    GradingSource.AI -> RatingSource.AI
}
