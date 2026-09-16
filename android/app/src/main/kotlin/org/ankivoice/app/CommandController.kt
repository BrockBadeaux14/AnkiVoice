package org.ankivoice.app

import java.util.concurrent.Executor
import org.ankivoice.core.answer.AnswerPhase
import org.ankivoice.core.commands.CommandContext
import org.ankivoice.core.commands.CommandOutcome
import org.ankivoice.core.commands.CommandRouter
import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.ForegroundEvent
import org.ankivoice.core.contracts.ForegroundEventPort
import org.ankivoice.core.contracts.QueueExhausted
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.session.Interruption
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionResult

/**
 * One debug session: the turn loop, the command layer over it, and how to let the
 * platform objects go again.
 *
 * Built on the session's own thread, because [ReviewSession] confines every transition to
 * the thread that constructed it.
 */
internal class CommandSession(
    val session: ReviewSession,
    val router: CommandRouter,
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
) {
    /** True only while AV-012 has an attempt in flight. */
    val capturing: Boolean get() = context == CommandContext.ANSWER
}

/**
 * AV-014's debug-grade command surface, in the manner of AV-023's shell controls.
 *
 * It exists to exercise every command by touch and by voice on the pinned AVD, and to
 * give #20's reconciliation notice somewhere to live. **#27 owns the real study surface**:
 * there is no card text, no transcript and no grade here, and this class must not grow
 * into one.
 *
 * The session runs on [worker], a thread of its own, because AV-025's transport blocks the
 * caller for the whole of playback and capture. State crosses back on [delivery] — the
 * main thread in the app — and only immutable snapshots make the trip.
 *
 * **Nothing here submits a review.** There is no commit control and no code path from a
 * command to [ReviewSession.commit]; a confirmed rating stays pending until #27 builds the
 * step that submits it.
 */
internal class CommandController(
    private val factory: CommandSessionFactory,
    private val worker: Executor,
    private val delivery: Executor,
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

    @Volatile
    private var foreground = false

    @Volatile
    private var generation = 0L

    // -- the debug-grade context controls ------------------------------------- //

    /**
     * Open a session and offer the first card.
     *
     * These four controls are not commands. They are the smallest set that reaches every
     * command context by touch — #27 replaces them with the real study flow.
     */
    fun start() = act("start") { current ->
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
        if (!current.session.halted) current.session.finishSession()
        current.release()
        open = null
        "Session closed. No review was submitted from this screen."
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
        val snapshot = CommandState(
            running = current != null,
            busy = false,
            sessionState = session?.state?.specName,
            answerPhase = session?.answerTurn?.phase?.specName ?: session?.let { AnswerPhase.THINKING.specName },
            context = router?.context() ?: CommandContext.UNAVAILABLE,
            available = router?.available().orEmpty(),
            spokenAvailable = router?.spokenAvailable().orEmpty(),
            cardId = session?.card?.identity?.cardId,
            notice = notice ?: state.notice,
            failure = session?.lastFailure ?: unavailable,
        )
        delivery.execute {
            if (token != generation) return@execute
            state = snapshot
            observer?.invoke(snapshot)
        }
    }

    private fun publish(next: CommandState) {
        state = next
        observer?.invoke(next)
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
}

/** Why no session could open. Carries AV-007's failure rather than a message alone. */
internal class CommandSessionUnavailable(val failure: Failure) :
    IllegalStateException(failure.toString())
