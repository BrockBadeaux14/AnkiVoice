package org.ankivoice.core.session

/**
 * The state names the Python binding (`tools/av007_contracts.py`) uses.
 *
 * The Kotlin session (AV-013) models pause, interruption, retry, unsupported card,
 * reveal and outcome-unknown as states of their own, which the binding folds into
 * `paused` and `stopped`. Keeping the binding's spelling here lets the 53-scenario
 * conformance suite assert against the source it was ported from without freezing the
 * Kotlin model to the coarser one.
 */
enum class BindingSessionState(val specName: String) {
    IDLE("idle"),
    ASKING("asking"),
    LISTENING("listening"),
    GRADING("grading"),
    PROPOSING("proposing"),
    COMMITTING("committing"),
    COMMITTED("committed"),
    PAUSED("paused"),
    STOPPED("stopped"),
    EXHAUSTED("exhausted"),
}

/**
 * Where one turn stands. Every transition is explicit and confined to the session's
 * owning thread; nothing here writes a review.
 *
 * [binding] is the coarser state the Python binding reports, or null for a state the
 * binding does not separate at all.
 */
enum class SessionState(
    val specName: String,
    val binding: BindingSessionState?,
    val isHalted: Boolean = false,
) {
    /** No card is open. The only state [ReviewSession.offerCard] may be called from. */
    IDLE("idle", BindingSessionState.IDLE),

    /** Question playback is in flight. Capture may not open. */
    ASKING("asking", BindingSessionState.ASKING),

    /**
     * The answer phase for this attempt. AV-012 owns the finer detail: the turn waits in
     * [org.ankivoice.core.answer.AnswerPhase.THINKING] until an explicit Start answer,
     * then runs the bounded window and the finalization deadline.
     */
    LISTENING("listening", BindingSessionState.LISTENING),

    /**
     * An explicit Try again for the same card: a new revision is open and the next
     * bounded window waits on the next explicit Start answer, never on a re-arm.
     */
    RETRYING("retrying", BindingSessionState.LISTENING),

    /** A gradable transcript exists for this revision. */
    GRADING("grading", BindingSessionState.GRADING),

    /**
     * Reveal or elaboration playback is in flight. Transient: the session returns to the
     * state reveal was requested from, which is why the binding has no separate state.
     */
    REVEALING("revealing", null),

    /** A pending review is open for correction. Nothing is written. */
    PROPOSING("proposing", BindingSessionState.PROPOSING),

    /** The single write is in flight. */
    COMMITTING("committing", BindingSessionState.COMMITTING),

    /** Confirmed; awaiting advance or a native-undo handoff. */
    COMMITTED("committed", BindingSessionState.COMMITTED),

    /** Halted; the learner may resolve the cause and resume. */
    PAUSED("paused", BindingSessionState.PAUSED, isHalted = true),

    /**
     * A write may or may not have landed. A pause that is never resumable until the
     * learner reports what AnkiDroid shows: the write is never retried blindly.
     */
    OUTCOME_UNKNOWN("outcome-unknown", BindingSessionState.PAUSED, isHalted = true),

    /** The single-active-reviewer precondition broke. Reload before studying again. */
    INTERRUPTED("interrupted", BindingSessionState.STOPPED, isHalted = true),

    /** The offered card is not a usable VoiceQA card. */
    UNSUPPORTED("unsupported", BindingSessionState.STOPPED, isHalted = true),

    /** Halted; reload the collection to continue. */
    STOPPED("stopped", BindingSessionState.STOPPED, isHalted = true),

    /** The queue emptied normally. */
    EXHAUSTED("exhausted", BindingSessionState.EXHAUSTED, isHalted = true),
    ;

    /** True while a card is open and the turn may still progress. */
    val isActive: Boolean get() = !isHalted && this != IDLE
}

val HALTED_STATES: Set<SessionState> = SessionState.entries.filter { it.isHalted }.toSet()

/** Events that break the single-active-reviewer precondition. */
enum class Interruption(val specName: String) {
    APP_SWITCH("app_switch"),
    PROCESS_RESUME("process_resume"),
    SYNC("sync"),
    CONCURRENT_MODIFICATION("concurrent_modification"),
    LOCK("lock"),
    EXTERNAL_AUDIO("external_audio"),
}

/**
 * A halted session.
 *
 * [resumable] says the learner may fix the cause and continue; it never says a review
 * did or did not land. [reconciliationRequired] says a write may have landed and the
 * learner must check AnkiDroid first. No later halt may clear it.
 */
data class Halt(
    val state: SessionState,
    val reason: String,
    val detail: String,
    val resumable: Boolean,
    val reconciliationRequired: Boolean,
)

/** One line of the session's ordered, content-bearing transcript. */
data class Event(val step: String, val detail: String = "")

/**
 * What one session operation produced.
 *
 * [Ignored] is a stale, duplicate or out-of-turn callback that was dropped rather than
 * applied; it is not a failure and it never advances the session.
 */
sealed interface SessionResult<out T> {
    data class Produced<T>(val value: T) : SessionResult<T>

    data class Halted(val halt: Halt) : SessionResult<Nothing>

    data object Ignored : SessionResult<Nothing>

    /** The produced value, or null when the session halted or dropped the callback. */
    val valueOrNull: T? get() = (this as? Produced)?.value

    /** The halt, or null when the operation produced a value or was ignored. */
    val haltOrNull: Halt? get() = (this as? Halted)?.halt
}

/** A proposal either opens the correction window or is refused, leaving it open. */
sealed interface ProposalOutcome {
    data class Proposed(val intent: org.ankivoice.core.contracts.ReviewIntent) : ProposalOutcome

    /** The rating was never offered for this card. It is never converted to Again. */
    data class Rejected(val failure: org.ankivoice.core.contracts.Failure) : ProposalOutcome
}
