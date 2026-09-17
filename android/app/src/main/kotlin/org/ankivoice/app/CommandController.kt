package org.ankivoice.app

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.ankivoice.core.answer.AnswerPhase
import org.ankivoice.core.answer.AnswerRecovery
import org.ankivoice.core.commands.CommandContext
import org.ankivoice.core.commands.CommandRouter
import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.contracts.CardProvider
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.ForegroundEvent
import org.ankivoice.core.contracts.ForegroundEventPort
import org.ankivoice.core.contracts.Grader
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.QueueExhausted
import org.ankivoice.core.contracts.ReviewOutcome
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.exchange.PrecommitExchange
import org.ankivoice.core.exchange.RatingSource
import org.ankivoice.core.exchange.ratingName
import org.ankivoice.core.grading.GradingSource
import org.ankivoice.core.session.Event
import org.ankivoice.core.session.Interruption
import org.ankivoice.core.session.ProposalOutcome
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionResult
import org.ankivoice.core.session.SessionState

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
 *
 * [exchange] is AV-019's pre-commit exchange over the same session, built on the same
 * thread. [gradingSource] names the policy that answered one grading request, which only
 * the grader that chose the route can state; it is read on the grading worker in the same
 * step that took the reply, and a grader that will not say reports null.
 */
internal class CommandSession(
    val session: ReviewSession,
    val router: CommandRouter,
    val grader: Grader,
    val exchange: PrecommitExchange,
    val revision: AtomicInteger = AtomicInteger(),
    val gradingSource: (GradingRequest) -> GradingSource? = { null },
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
    /** What the last command told the learner, in AV-014's own words. */
    val notice: String? = null,
    val failure: Failure? = null,
    /** AV-045: a grading request is on the grading worker. */
    val grading: Boolean = false,
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
    /** The ratings this card offers while the learner may still name one. Empty otherwise. */
    val selfGradable: List<Int> = emptyList(),
    /** AV-019: the writer's own outcome for the committed attempt, or null before one. */
    val outcomeState: String? = null,
    /** The writer's own reason, shown as-is for a write that failed or could not be confirmed. */
    val outcomeReason: String? = null,
) {
    /** True only while AV-012 has an attempt in flight. */
    val capturing: Boolean get() = context == CommandContext.ANSWER

    /** True while the session holds a settled answer that has not been graded yet. */
    val gradable: Boolean get() = sessionState == "grading" && !grading

    /** AV-019: a confirmed review, so Next card and the native-Undo handoff are offered. */
    val committed: Boolean get() = outcomeState == ReviewState.CONFIRMED.specName &&
        sessionState == SessionState.COMMITTED.specName

    /** AV-019: the write could not be confirmed, so only the learner can say what happened. */
    val reconcileRequired: Boolean get() = sessionState == SessionState.OUTCOME_UNKNOWN.specName
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
 * there is no card text, no transcript and no grade here, and this class must not grow
 * into one.
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
 * AV-019: an accepted confirmation **does** submit, here, on [worker]. Every command goes
 * through [PrecommitExchange], which announces the pending rating and its source, applies
 * the re-prompt rule, and runs [ReviewSession.commit] exactly once for an attempt whose
 * confirmation #14 accepted. There is still no other path to the writer: a rating command,
 * a correction, a self-grade, a pause and a skip all leave the collection untouched, and
 * the only announcement of a saved review is made from the [ReviewOutcome] the writer
 * returned.
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

    /** The explicit Start answer. Thinking time before it is unbounded. */
    fun startAnswer() = act("startAnswer") { current ->
        val session = current?.session ?: return@act "Start a session first."
        session.startAnswer()
        "Listening for your answer. Command words spoken now are part of the answer."
    }

    /** The explicit Done. It stops the microphone; it is not a verdict about the answer. */
    fun finishAnswer() = act("finishAnswer") { current ->
        val session = current?.session ?: return@act "Start a session first."
        when (val settled = session.finishAnswer()) {
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
            // Read in the same step that took the reply, on the worker that owns the grader.
            val source = turn.gradingSource(request)
            worker.execute {
                // A session closed or replaced while grading was in flight gets nothing.
                if (open !== turn) return@execute
                grading = false
                val notice = openExchange(turn, turn.session.acceptGrade(reply), source)
                publish(open, generation, notice)
            }
        }
        "Grading this answer: on-device rules first, the AI route only if no rule matches."
    }

    // -- the commands ----------------------------------------------------------- //

    /**
     * The touch equivalent for [command]. Every command in the vocabulary has one.
     *
     * AV-019: the outcome goes through the exchange, which re-announces a corrected rating
     * and commits an accepted confirmation. An explicit gesture carries no recognition
     * confidence, so a touched Confirm is the route that always works.
     */
    fun run(command: VoiceCommand) = act("run ${command.specName}") { current ->
        val turn = current ?: return@act "Start a session first."
        turn.exchange.onCommand(turn.router.touch(command)).notice
    }

    /** One learner-opened command capture, through AV-025's transport. */
    fun listenForCommand() = act("listenForCommand") { current ->
        val turn = current ?: return@act "Start a session first."
        turn.exchange.onCommand(turn.router.listenForCommand()).notice
    }

    // -- AV-019: the pre-commit exchange's own controls ------------------------ //

    /**
     * The learner's own rating when no grader offered one, or after a grading fault.
     *
     * It is the abstain path: naming a rating opens Announced with it announced as
     * learner-named, and a separate explicit confirmation is still required.
     */
    fun selfGrade(rating: Int) = act("selfGrade $rating") { current ->
        val turn = current ?: return@act "Start a session first."
        when (turn.session.selfGrade(rating)) {
            is ProposalOutcome.Proposed -> turn.exchange.announceLearnerRating().notice
            is ProposalOutcome.Rejected ->
                "This card did not offer ${ratingName(rating)}, so nothing was proposed."
        }
    }

    /** Only a confirmed review advances, and the next card is read from AnkiDroid afresh. */
    fun nextCard() = act("nextCard") { current ->
        val turn = current ?: return@act "Start a session first."
        turn.session.advance()
        describeOffer(turn.session.offerCard())
    }

    /**
     * AV-007's post-commit correction: AnkiDroid's own Undo, and nothing else.
     *
     * The session stops and is released, so the way back in is a fresh one that re-reads
     * the card and its scheduling. Nothing here undoes, re-rates or compensates for the
     * review that was written.
     */
    fun handOffToUndo() = act("undoHandoff") { current ->
        val turn = current ?: return@act "No session is open."
        turn.session.requestCorrectionAfterCommit()
        turn.release()
        open = null
        grading = false
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
    fun reportReconciled(saved: Boolean) = act("reconcile") { current ->
        val turn = current ?: return@act "No session is open."
        turn.session.reconcile(saved)
        turn.release()
        open = null
        grading = false
        val seen = if (saved) {
            "Recorded: you saw the review in AnkiDroid."
        } else {
            "Recorded: the review is not in AnkiDroid. AnkiVoice will not send it again on its " +
                "own; rate the card in AnkiDroid, or study it here again."
        }
        "$seen This session is closed: start again to reload the collection."
    }

    /** End the debug session and let the recognizer and synthesizer go. */
    fun stop() = act("stop") { current ->
        if (current == null) return@act "No session is open."
        if (!current.session.halted) current.session.finishSession()
        current.release()
        open = null
        grading = false
        "Session closed. Closing wrote nothing: a review you confirmed was already saved, and " +
            "a rating you left waiting was discarded unwritten."
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

    /** Read the turn on the session thread; publish the immutable snapshot on the main one. */
    private fun publish(current: CommandSession?, token: Long, notice: String?) {
        val session = current?.session
        val router = current?.router
        // The grading worker reads this to abandon a retry across an edit.
        session?.let { current.revision.set(it.transcriptRevision) }
        // AV-019: the Announced position as the exchange derives it right now. A superseded
        // revision, a cancelled intent or a committed attempt all leave it null, so the
        // surface can never show a rating that is no longer waiting.
        val position = current?.exchange?.position
        val committed = current?.exchange?.settled
        val turn = CommandState(
            running = current != null,
            busy = false,
            sessionState = session?.state?.specName,
            answerPhase = session?.answerTurn?.phase?.specName ?: session?.let { AnswerPhase.THINKING.specName },
            context = router?.context() ?: CommandContext.UNAVAILABLE,
            available = router?.available().orEmpty(),
            spokenAvailable = router?.spokenAvailable().orEmpty(),
            cardId = session?.card?.identity?.cardId,
            notice = notice,
            failure = session?.lastFailure ?: unavailable,
            grading = grading,
            announcement = position?.text,
            pendingRating = position?.rating,
            ratingSource = position?.source?.specName,
            announcedRevision = position?.transcriptRevision,
            confirmed = session?.intent?.hasConfirmation() == true,
            selfGradable = selfGradable(session),
            outcomeState = committed?.state?.specName,
            outcomeReason = committed?.reason,
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

    /**
     * AV-019: open the pre-commit exchange from what the grader answered.
     *
     * A label that proposes a rating this card offers opens Announced with that rating and
     * the policy that produced it. Everything else — `partial`, `uncertain`, a rating the
     * card withdrew, a grading fault, an exhausted quota — opens Announced with **no**
     * pending rating, which is announced as an abstention and never as a rating. A grader
     * that will not name its route is treated the same way: AV-019 shows no rating whose
     * source it cannot state.
     *
     * The grader's own reason is never announced or shown, because it may quote the answer.
     */
    private fun openExchange(
        turn: CommandSession,
        graded: SessionResult<GradingResult>,
        source: GradingSource?,
    ): String = when (graded) {
        is SessionResult.Produced -> {
            val permitted = turn.session.card?.permittedRatings.orEmpty()
            val rating = graded.value.proposedRating(permitted)
            val label = graded.value.label.specName
            when {
                rating == null -> turn.exchange.abstain("the grader answered $label")
                source == null -> turn.exchange.abstain("the grader did not say which policy answered")
                else -> turn.exchange.openWithProposal(rating, source.asRatingSource())
            }.notice
        }
        is SessionResult.Halted -> turn.exchange.abstain("no grade: ${graded.halt.reason}").notice
        SessionResult.Ignored -> "That grade belonged to an earlier answer, so it was dropped."
    }

    /**
     * The ratings the learner may name right now.
     *
     * Empty unless the turn holds a gradable answer and no rating is pending: after a
     * grading fault [ReviewSession.selfGrade] is the only way to name one, because #15's
     * rating commands are not executable from a pause.
     */
    private fun selfGradable(session: ReviewSession?): List<Int> {
        if (session == null || session.answer?.gradable != true) return emptyList()
        if (session.intent?.state == ReviewState.PENDING) return emptyList()
        val namable = session.state == SessionState.GRADING ||
            AnswerRecovery.SELF_GRADE in session.recoveryOptions
        return if (namable) session.card?.permittedRatings.orEmpty() else emptyList()
    }
}

/** AV-016's policy names, in AV-019's words. The two enums are deliberately separate. */
private fun GradingSource.asRatingSource(): RatingSource = when (this) {
    GradingSource.RULE -> RatingSource.RULE
    GradingSource.AI -> RatingSource.AI
}

/** Why no session could open. Carries AV-007's failure rather than a message alone. */
internal class CommandSessionUnavailable(val failure: Failure) :
    IllegalStateException(failure.toString())
