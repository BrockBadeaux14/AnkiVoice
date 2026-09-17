package org.ankivoice.app

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.ankivoice.core.answer.AnswerPhase
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
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.QueueExhausted
import org.ankivoice.core.contracts.ReviewOutcome
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.session.Event
import org.ankivoice.core.contracts.SpeechInput
import org.ankivoice.core.session.Interruption
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionResult

/**
 * One debug session: the turn loop, the command layer over it, the grader behind it, and
 * how to let the platform objects go again.
 *
 * Built on the session's own thread, because [ReviewSession] confines every transition to
 * the thread that constructed it.
 *
 * [grader] is the same grader the session was built with. The controller calls it on the
 * grading worker rather than through [ReviewSession.grade], which would block the session
 * thread for the whole provider round trip. [revision] mirrors the session's transcript
 * revision for that worker, which may not read the session directly.
 */
internal class CommandSession(
    val session: ReviewSession,
    val router: CommandRouter,
    val grader: Grader,
    val revision: AtomicInteger = AtomicInteger(),
    /**
     * The same transport the session holds. Done crosses threads to reach it, because the
     * session is confined to the thread the capture is blocking.
     */
    val speech: SpeechInput,
    val language: String,
    /**
     * What the recognizer has heard so far in the open attempt, or null when it has offered
     * nothing yet. Read from the main thread while the session thread is inside the capture,
     * so the transport keeps it as an observation and never as an answer.
     */
    val partial: () -> String?,
    val release: () -> Unit,
)

/** How the composition root supplies one. Null when the deck cannot open a session. */
internal fun interface CommandSessionFactory {
    /** Called on the session's own thread. [Failure] explains why no session opened. */
    fun create(): Result<CommandSession>
}

/** What the command surface shows. Everything here is derived, never a second copy of the turn. */
internal data class CommandState(
    val running: Boolean = false,
    val busy: Boolean = false,
    /** AV-013's state name, or null before a session is opened. */
    val sessionState: String? = null,
    /** AV-012's phase, so the answer window is visible while it is open. */
    val answerPhase: String? = null,
    val context: CommandContext = CommandContext.UNAVAILABLE,
    /** The commands an on-screen control may run right now, in vocabulary order. */
    val available: List<VoiceCommand> = emptyList(),
    /** Those a spoken command could run right now. Empty inside the answer window. */
    val spokenAvailable: List<VoiceCommand> = emptyList(),
    val cardId: Long? = null,
    /**
     * True while the transport holds an attempt. Done is the control that ends it, so Done
     * stays enabled on this snapshot even though the session thread is busy inside it.
     */
    val answering: Boolean = false,
    /**
     * What the recognizer returned for the last attempt, spelled as it returned it. It is
     * the learner's own words and never card text, and an attempt that produced no
     * transcript leaves it null rather than empty.
     */
    val heard: String? = null,
    /** What the last command told the learner, in AV-014's own words. */
    val notice: String? = null,
    val failure: Failure? = null,
    /** AV-045: a grading request is on the grading worker. */
    val grading: Boolean = false,
    /** AV-018 notices that are blocking the start, in reconciliation's own words. */
    val journalNotices: List<String> = emptyList(),
    /** The journal entries those notices belong to, so the learner can acknowledge them. */
    val journalOutstanding: List<Long> = emptyList(),
) {
    /** True only while AV-012 has an attempt in flight. */
    val capturing: Boolean get() = context == CommandContext.ANSWER

    /** True while the session holds a settled answer that has not been graded yet. */
    val gradable: Boolean get() = sessionState == "grading" && !grading
}

/**
 * What a session has recorded so far, for #27's surface and #29's evidence.
 *
 * It carries study content — the prompt and what the learner said — so it is handed to
 * the caller that asked and nowhere else: it is never recorded in AV-020's diagnostics.
 */
internal data class SessionEvidence(
    val sessionId: String,
    val events: List<Event>,
    val outcomes: List<ReviewOutcome>,
)

/**
 * AV-014's debug-grade command surface, in the manner of AV-023's shell controls.
 *
 * It exists to exercise every command by touch and by voice on the pinned AVD, and to
 * give #20's reconciliation notice somewhere to live. **#27 owns the real study surface**:
 * there is no card text and no grade here, and this class must not grow into one. It does
 * show the transcript — the learner's own words, read back so a misrecognition is visible
 * where it happens rather than only in an evidence file afterwards.
 *
 * The session runs on [worker], a thread of its own, because AV-025's transport blocks the
 * caller for the whole of playback and capture. Grading runs on [gradingWorker], because
 * #18's route can block for two provider attempts; its reply crosses back to [worker] and
 * goes through [ReviewSession.acceptGrade], so a reply for a superseded revision is
 * dropped by the session. State crosses back on [delivery] — the main thread in the app —
 * and only immutable snapshots make the trip.
 *
 * AV-045: no session opens before [gate] has reconciled the journal in this process, and
 * none opens while an unknown outcome is unacknowledged. [cards] is what reconciliation
 * re-reads the journalled card through.
 *
 * **Nothing here submits a review.** There is no commit control and no code path from a
 * command to [ReviewSession.commit]; a confirmed rating stays pending until #21 builds the
 * step that submits it.
 */
internal class CommandController(
    private val factory: CommandSessionFactory,
    private val worker: Executor,
    private val delivery: Executor,
    private val gradingWorker: Executor,
    private val gate: ReconciliationGate? = null,
    private val cards: () -> CardProvider? = { null },
) : ForegroundEventPort {
    /** Written on [delivery], read on [worker] when an action carries the last notice forward. */
    @Volatile
    var state = CommandState()
        private set
    var observer: ((CommandState) -> Unit)? = null

    /** Touched only on [worker]. */
    private var open: CommandSession? = null

    /**
     * The attempt Done stops, or null when none is in flight. Written on [worker] around
     * the blocking capture and read on the main thread, so it is volatile.
     */
    @Volatile
    private var capture: ActiveCapture? = null

    /** Set by a Done on the main thread, read and cleared on [worker] when the capture ends. */
    @Volatile
    private var stoppedByOperator = false

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

    @Volatile
    private var foreground = false

    @Volatile
    private var generation = 0L

    init {
        // An acknowledgement made on the readiness preview clears the notice here too.
        gate?.listen(::publishJournal)
    }

    // -- the debug-grade context controls ------------------------------------- //

    /**
     * Open a session and offer the first card, after AV-018's reconciliation gate.
     *
     * These controls are not commands. They are the smallest set that reaches every
     * command context by touch — #27 replaces them with the real study flow.
     */
    fun start() {
        val gate = gate
        if (gate == null || !foreground || state.busy) return openSession()
        val token = ++generation
        publish(state.copy(busy = true))
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
                        notice = "Study is blocked: an earlier review is unresolved. Check AnkiDroid, " +
                            "then acknowledge it before starting.",
                    ),
                )
            } else {
                publish(notices)
                openSession()
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
        val session = factory.create().getOrElse { error ->
            val failure = (error as? CommandSessionUnavailable)?.failure
            unavailable = failure
            return@act failure?.let { "Study is unavailable: ${it.mode.specName}." }
                ?: "Study is unavailable: ${error.message}"
        }
        open = session
        session.session.start()
        describeOffer(session.session.offerCard())
    }

    /** Play the Prompt, which is what opens AV-012's answer phase. */
    fun ask() = act("ask") { current ->
        val session = current?.session ?: return@act "Start a session first."
        when (val spoken = session.ask()) {
            is SessionResult.Produced -> "The prompt was played."
            is SessionResult.Halted -> "Paused: ${spoken.halt.reason}."
            SessionResult.Ignored -> "That playback belonged to an earlier turn."
        }
    }

    /**
     * The explicit Start answer. Thinking time before it is unbounded.
     *
     * It opens AV-012's window **and** the microphone: AV-025 defines the call to `listen`
     * as this touch, and opening the window alone left the surface saying it was capturing
     * while nothing had asked the transport for anything. The capture then blocks [worker]
     * for the whole attempt, exactly as playback does, so the open window is published
     * before it blocks and [finishAnswer] reaches the transport without queueing behind it.
     */
    fun startAnswer() = act("startAnswer") { current ->
        val open = current ?: return@act "Start a session first."
        val token = open.session.startAnswer()
        // The previous attempt's transcript is not this one's, so it goes before the
        // microphone opens rather than being left on screen beside a live capture.
        heard = null
        capture = ActiveCapture(open.speech, token, open.partial)
        publish(open, actionToken, LISTENING_NOTICE, busy = true)
        val event = try {
            open.speech.listen(token, open.language)
        } finally {
            capture = null
        }
        heard = (event as? CaptureEvent.Transcript)?.text?.takeIf { it.isNotBlank() }
        // An operator Done is recorded as AV-012's Done rather than as a window expiry.
        if (stoppedByOperator) {
            stoppedByOperator = false
            open.session.finishAnswer()
        }
        when (val settled = open.session.acceptCapture(event)) {
            is SessionResult.Produced -> "Answer settled as ${settled.value.status.specName}."
            is SessionResult.Halted -> "Paused: ${settled.halt.reason}."
            SessionResult.Ignored -> "That capture belonged to an earlier attempt."
        }
    }

    /**
     * AV-045: grade the settled answer — rules on device, the route only on a rule miss.
     *
     * The request is opened on the session thread, graded on [gradingWorker] and handed
     * back through [ReviewSession.acceptGrade]. A suggestion is advisory: it proposes
     * nothing on its own, and a failure pauses the turn with the card kept for a self-grade.
     */
    fun grade() = act("grade") { current ->
        val turn = current ?: return@act "Start a session first."
        if (grading) return@act "This answer is already being graded."
        val request = turn.session.beginGrade()
        turn.revision.set(turn.session.transcriptRevision)
        grading = true
        gradingWorker.execute {
            val reply = try {
                turn.grader.grade(request)
            } catch (e: RuntimeException) {
                // A grader that throws has not graded. It is a failure, never a label.
                GradingReply(request, Failure(GraderFailure.PROVIDER_ERROR, "grading could not run: ${e.javaClass.simpleName}"))
            }
            worker.execute {
                // A session closed or replaced while grading was in flight gets nothing.
                if (open !== turn) return@execute
                grading = false
                val notice = describeGrade(turn.session.acceptGrade(reply), turn)
                publish(open, generation, notice)
            }
        }
        "Grading this answer: on-device rules first, the AI route only if no rule matches."
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

    /**
     * The explicit Done. It stops the microphone; it is not a verdict about the answer.
     *
     * With an attempt in flight it goes straight to the transport, which accepts a stop
     * from any thread. Queued behind the capture it would be dropped by the busy gate and
     * do nothing at all, which is what made Done look broken. The turn settles on [worker]
     * as soon as the capture returns, and that is what repaints the surface.
     */
    fun finishAnswer() {
        capture?.let { active ->
            stoppedByOperator = true
            active.speech.finishAnswer(active.token)
            return
        }
        act("finishAnswer") { current ->
            val session = current?.session ?: return@act "Start a session first."
            when (val settled = session.finishAnswer()) {
                is SessionResult.Produced -> "Answer settled as ${settled.value.status.specName}."
                is SessionResult.Halted -> "Paused: ${settled.halt.reason}."
                SessionResult.Ignored -> "That capture belonged to an earlier attempt."
            }
        }
    }

    // -- the commands ----------------------------------------------------------- //

    /** The touch equivalent for [command]. Every command in the vocabulary has one. */
    fun run(command: VoiceCommand) = act("run ${command.specName}") { current ->
        val router = current?.router ?: return@act "Start a session first."
        router.touch(command).notice
    }

    /** One learner-opened command capture, through AV-025's transport. */
    fun listenForCommand() = act("listenForCommand") { current ->
        val router = current?.router ?: return@act "Start a session first."
        when (val outcome = router.listenForCommand()) {
            is CommandOutcome.Executed -> outcome.notice
            is CommandOutcome.Refused -> outcome.notice
            // Unreachable: a command capture is refused inside the answer window.
            is CommandOutcome.AnswerText -> outcome.notice
        }
    }

    /** End the debug session and let the recognizer and synthesizer go. */
    fun stop() = act("stop") { current ->
        if (current == null) return@act "No session is open."
        heard = null
        if (!current.session.halted) current.session.finishSession()
        current.release()
        open = null
        grading = false
        "Session closed. No review was submitted from this screen."
    }

    /**
     * The open session's events and outcomes, copied on the session thread and delivered
     * on [delivery]. Null when no session is open. The copy goes to [callback] only.
     */
    fun evidence(callback: (SessionEvidence?) -> Unit) {
        worker.execute {
            val session = open?.session
            val evidence = session?.let { SessionEvidence(it.sessionId, it.events.toList(), it.outcomes.toList()) }
            delivery.execute { callback(evidence) }
        }
    }

    override fun onForegroundEvent(event: ForegroundEvent) {
        when (event) {
            ForegroundEvent.RESUME -> foreground = true
            ForegroundEvent.PAUSE, ForegroundEvent.STOP -> {
                foreground = false
                // AV-007: leaving the foreground breaks the single-active-reviewer
                // precondition. The session stops and the microphone is released; it is
                // never left open behind another app.
                val token = ++generation
                worker.execute {
                    val current = open ?: return@execute
                    if (!current.session.halted) current.session.interrupt(Interruption.APP_SWITCH)
                    current.release()
                    open = null
                    grading = false
                    heard = null
                    publish(open, token, "Study stopped because AnkiVoice left the foreground.")
                }
            }
        }
    }

    // -- internals ---------------------------------------------------------------- //

    /**
     * Run one action on the session thread and publish what the turn looks like afterwards.
     *
     * A reply from an older generation is dropped, so an action that was in flight when the
     * app left the foreground can never repaint the surface behind a newer one.
     */
    private fun act(label: String, action: (CommandSession?) -> String?) {
        if (state.busy) return
        val token = ++generation
        publish(state.copy(busy = true))
        worker.execute {
            actionToken = token
            val notice = try {
                action(open)
            } catch (e: IllegalStateException) {
                // A guard the surface should not have offered. Report it rather than
                // crashing the debug screen, and leave the turn exactly as it was.
                "$label is not available here: ${e.message}"
            }
            publish(open, token, notice)
        }
    }

    /**
     * Read the turn on the session thread; publish the immutable snapshot on the main one.
     *
     * [busy] stays true for a snapshot published from inside an action that has not
     * finished — an open capture is the one that matters — so the surface reports the turn
     * without offering controls the blocked session thread could not run.
     */
    private fun publish(current: CommandSession?, token: Long, notice: String?, busy: Boolean = false) {
        val session = current?.session
        val router = current?.router
        // The grading worker reads this to abandon a retry across an edit.
        session?.let { current.revision.set(it.transcriptRevision) }
        val turn = CommandState(
            running = current != null,
            busy = busy,
            answering = capture != null,
            heard = heard,
            sessionState = session?.state?.specName,
            answerPhase = session?.answerTurn?.phase?.specName ?: session?.let { AnswerPhase.THINKING.specName },
            context = router?.context() ?: CommandContext.UNAVAILABLE,
            available = router?.available().orEmpty(),
            spokenAvailable = router?.spokenAvailable().orEmpty(),
            cardId = session?.card?.identity?.cardId,
            notice = notice,
            failure = session?.lastFailure ?: unavailable,
            grading = grading,
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

    private fun publish(next: CommandState) {
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

    private fun describeOffer(offered: SessionResult<*>): String = when (offered) {
        is SessionResult.Produced -> when (val value = offered.value) {
            is ScheduledCard -> "Card ${value.identity.cardId} is ready. Play the prompt to begin."
            QueueExhausted -> "The queue is finished."
            else -> "Nothing was offered."
        }
        is SessionResult.Halted -> "Paused: ${offered.halt.reason} — ${offered.halt.detail}"
        SessionResult.Ignored -> "Nothing was offered."
    }

    /** The label and what it proposes. Never the grader's reason, which may quote the answer. */
    private fun describeGrade(graded: SessionResult<GradingResult>, turn: CommandSession): String = when (graded) {
        is SessionResult.Produced -> {
            val permitted = turn.session.card?.permittedRatings.orEmpty()
            val rating = graded.value.proposedRating(permitted)
            "Suggested ${graded.value.label.specName}. " + (
                rating?.let { "It proposes rating $it; nothing is proposed until you rate and confirm." }
                    ?: "It proposes no rating: choose one yourself."
                )
        }
        is SessionResult.Halted ->
            "No grade (${graded.halt.reason}). The card is kept; choose a rating yourself."
        SessionResult.Ignored -> "That grade belonged to an earlier answer, so it was dropped."
    }
}

/** What the surface says while the microphone is open, before the attempt settles. */
private const val LISTENING_NOTICE =
    "Listening for your answer. Command words spoken now are part of the answer."

/** One capture in flight: what to stop, and which attempt to stop. */
private class ActiveCapture(
    val speech: SpeechInput,
    val token: OperationToken,
    val partial: () -> String?,
)

/** Why no session could open. Carries AV-007's failure rather than a message alone. */
internal class CommandSessionUnavailable(val failure: Failure) :
    IllegalStateException(failure.toString())
