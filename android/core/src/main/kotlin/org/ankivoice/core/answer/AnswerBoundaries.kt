package org.ankivoice.core.answer

import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.MonotonicClock
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.SpeechInput
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.TranscriptKind
import org.ankivoice.core.grading.GradingSource
import org.ankivoice.core.grading.GradingSuggestion
import org.ankivoice.core.grading.TurnGrading
import org.ankivoice.core.grading.bindSuggestion

/**
 * AV-012: one learner-answer policy above the Android speech transport.
 *
 * The limits are AV-042's selected MVP values, accepted with #51, plus AV-050's
 * [prerollMs]. Every one of them is a selected engineering bound, not an optimized or
 * validated recall duration: no measurement says that 15 seconds is long enough to recall
 * an answer, and none says that 15 seconds is long enough to say one.
 *
 * **Amended by AV-050 (#81), September 17, 2026.** Capture no longer opens on a tap, so
 * the unbounded thinking time the explicit Start answer used to buy is gone. The owner's
 * decision was to keep the 15-second answer window honest as a *speaking* budget and put
 * the recall time in front of it: [prerollMs] runs from the microphone opening until the
 * learner is first heard, and only then does [windowMs] start counting. A learner who
 * never speaks gets the pre-roll and then the window, and the expiry preserves the card.
 *
 * [prerollMs] is recall time inside an open microphone; it ends early the moment speech
 * begins. [windowMs] is the maximum *speaking* time, measured from speech onset, or from
 * the pre-roll running out when no speech was ever heard. [finalizationMs] is a separate
 * deadline from Done, an endpoint or window expiry, inclusive of the 500 ms of trailing
 * silence #26 supplies; expiry of it is a timeout, not an answer. [attemptMs] is the
 * longest a recognizer attempt can live, which is the three in sequence — and the clocks
 * stay distinct.
 */
data class AnswerLimits(
    val prerollMs: Long = 15_000,
    val windowMs: Long = 15_000,
    val finalizationMs: Long = 5_000,
) {
    init {
        require(prerollMs > 0) { "The pre-roll must be a positive duration" }
        require(windowMs > 0) { "The answer window must be a positive duration" }
        require(finalizationMs > 0) { "The finalization deadline must be a positive duration" }
    }

    /** The longest the microphone can be open for one attempt: the pre-roll and the window. */
    val captureMs: Long get() = prerollMs + windowMs

    /** The recognizer attempt lifetime: the whole capture followed by finalization. */
    val attemptMs: Long get() = captureMs + finalizationMs

    companion object {
        /** AV-042's selected values with AV-050's pre-roll, pinned by the accepted #51 handoff. */
        val SELECTED: AnswerLimits = AnswerLimits()

        /**
         * Automatic re-arms inside one attempt. Zero, and AV-050 did not change it: the
         * microphone opens itself **once**, after that attempt's prompt playback settles,
         * and nothing in this file reopens it after a result. An explicit Try again is a
         * new attempt, with a pre-roll and a window of its own.
         */
        const val AUTOMATIC_REARMS: Int = 0
    }
}

/** What the turn is doing now. */
enum class AnswerPhase(val specName: String) {
    /**
     * The attempt has not opened yet.
     *
     * **Amended by AV-050 (#81):** this used to mean "waiting indefinitely for an explicit
     * Start answer". The microphone now opens itself exactly once per attempt, when that
     * attempt's prompt playback settles, so this phase is the gap between the card being
     * offered and that settle. Start answer remains a touch control for a learner who wants
     * to start early and for an automatic open that failed.
     */
    THINKING("thinking"),

    /**
     * Active capture: the pre-roll first, and the answer window from the moment the learner
     * is first heard.
     */
    CAPTURING("capturing"),

    /** The microphone stopped; the finalization deadline is running. */
    FINALIZING("finalizing"),

    /** The attempt is over. [AnswerTurn.answer] says how it ended. */
    SETTLED("settled"),
}

/** Why active capture ended. Expiry is not a learner action, and neither is an answer. */
enum class CaptureStop(val specName: String) {
    DONE("done"),
    WINDOW_EXPIRY("window-expiry"),
    CANCELLED("cancelled"),

    /**
     * AV-050: the learner stopped speaking and the capture ended itself.
     *
     * A reason of its own, beside the three above, so a journal or a runbook can never read
     * an endpoint as a gesture the learner made. It runs the same finalization the learner's
     * own Done runs, and it re-arms nothing.
     */
    ENDPOINT("endpoint"),
}

/** The six answer states, kept separate so none can stand in for another. */
enum class AnswerStatus(val specName: String) {
    /** In-flight recognizer text. Never starts grading and never becomes a revision. */
    PARTIAL("partial"),

    /** A recognizer final. Whether it may be graded still depends on its confidence. */
    FINAL("final"),

    /** Written or accepted by the learner. Only a learner action creates one. */
    USER_CORRECTED("user-corrected"),

    /** Explicit Cancel. */
    CANCELLED("cancelled"),

    /** The finalization deadline expired. A timeout is not an answer. */
    TIMED_OUT("timed-out"),

    /** The recognizer reported a failure. A failure is never a wrong answer. */
    FAILED("failed"),
}

/** The explicit learner actions every non-gradable outcome offers. All reachable by touch. */
enum class AnswerRecovery(val specName: String) {
    TRY_AGAIN("try-again"),
    TYPED_CORRECTION("typed-correction"),
    SELF_GRADE("self-grade"),
}

/**
 * The answer as it stands, bound to the attempt and transcript revision that produced it.
 *
 * Carries no rating and no label. A transcript — however confident, however final — never
 * submits a review: #18 may propose from it and #21 must confirm before #25 writes.
 */
data class Answer(
    val status: AnswerStatus,
    val text: String,
    val attempt: Int,
    val transcriptRevision: Int,
    val token: OperationToken,
    val confidence: Confidence = Confidence.ABSENT,
    val stoppedBy: CaptureStop? = null,
    val failure: Failure? = null,
) {
    /**
     * True only for a transcript the session may hand to a grader or self-grade against.
     *
     * A recognizer final counts only when the adapter classified its confidence as
     * sufficient. Absent confidence is unknown — not zero and not certainty — so the text
     * is kept and shown, and it is the learner who makes it gradable.
     */
    val gradable: Boolean
        get() = when (status) {
            AnswerStatus.FINAL -> text.isNotBlank() && confidence == Confidence.SUFFICIENT
            AnswerStatus.USER_CORRECTED -> text.isNotBlank()
            else -> false
        }

    /** A final the recognizer did not vouch for: shown, editable, and not graded as is. */
    val needsLearnerReview: Boolean
        get() = status == AnswerStatus.FINAL && confidence != Confidence.SUFFICIENT

    /** Empty once the answer is gradable; otherwise the card is kept and waits for one. */
    val recovery: List<AnswerRecovery>
        get() = if (gradable) emptyList() else AnswerRecovery.entries
}

/**
 * One card's bounded answer policy: window, finalization, transcript state and revision
 * identity, above #7's [SpeechInput] contract.
 *
 * This owns *when* capture may run and *what* the transcript currently is. #26 owns the
 * recognizer, the capture stream, platform callbacks and cleanup; #14 owns session
 * orchestration; #15/#27 own voice commands. Nothing here plays audio, reads a card or
 * writes a review.
 *
 * Four clocks stay distinct on [clock]: AV-050's pre-roll from the microphone opening, the
 * app-owned answer window from speech onset, the recognizer attempt lifetime
 * ([AnswerLimits.attemptMs]) and the finalization deadline from Done, an endpoint or
 * expiry. No deadline runs before the attempt opens.
 *
 * Not thread-safe. Confine it to the session's owned execution context, like
 * [org.ankivoice.core.contracts.ReviewIntent].
 */
class AnswerTurn(
    private val speech: SpeechInput,
    private val clock: MonotonicClock,
    private val sessionId: String,
    val turn: Int,
    private val language: String,
    private val limits: AnswerLimits = AnswerLimits.SELECTED,
    startingRevision: Int = 0,
) {
    var phase: AnswerPhase = AnswerPhase.THINKING
        private set

    /** Zero until the first Start answer, then one per explicit attempt. */
    var attempt: Int = 0
        private set

    /** Raised by every settled answer, every typed correction and every Try again. */
    var transcriptRevision: Int = startingRevision
        private set

    /** The last settled answer, or null while thinking or capturing. */
    var answer: Answer? = null
        private set

    /** The latest partial text. Kept for display only; it never starts grading. */
    var partialText: String = ""
        private set

    /** The current attempt's capture token, or null outside an attempt. */
    var token: OperationToken? = null
        private set

    private val settled = mutableListOf<Answer>()
    private val discarded = mutableListOf<CaptureEvent>()

    /** Every settled answer of this turn, oldest first: retries and edits stay separable. */
    val history: List<Answer> get() = settled

    /** Late, duplicate and out-of-attempt callbacks this policy ignored. */
    val ignored: List<CaptureEvent> get() = discarded

    /** Explicit Try again actions, which is one fewer than the attempts made. */
    val retries: Int get() = (attempt - 1).coerceAtLeast(0)

    /** Typed corrections, recorded separately from raw recognition success. */
    val corrections: Int get() = settled.count { it.status == AnswerStatus.USER_CORRECTED }

    /** Recognizer finals, whatever their confidence. */
    val recognitions: Int get() = settled.count { it.status == AnswerStatus.FINAL }

    private var sequence = 0
    private var prerollStartedMs = 0L

    /** When the answer window started counting, or 0 while the pre-roll still is. */
    private var windowStartedMs = 0L
    private var captureEndedMs = 0L
    private var stoppedBy: CaptureStop? = null

    /** The attempt has not opened yet; no deadline is running. */
    val thinking: Boolean get() = phase == AnswerPhase.THINKING

    /**
     * AV-050: true once the learner has been heard in this attempt, which is what started
     * the answer window. False through the pre-roll, and false for a whole attempt in which
     * nobody spoke.
     */
    val heardSpeech: Boolean get() = speechStartedMs != 0L

    private var speechStartedMs = 0L

    /**
     * AV-050: what is left of the recall pre-roll, or null when it is not running.
     *
     * Null once speech has begun, because the window replaced it, and null outside capture.
     */
    fun remainingPrerollMs(): Long? =
        if (phase == AnswerPhase.CAPTURING && windowStartedMs == 0L) {
            (prerollStartedMs + limits.prerollMs - clock.nowMs()).coerceAtLeast(0)
        } else {
            null
        }

    /**
     * What is left of the answer window, or null when it is not running.
     *
     * AV-050: null through the pre-roll as well, because the window has not started
     * counting yet. [remainingPrerollMs] is what is running then.
     */
    fun remainingWindowMs(): Long? =
        if (phase == AnswerPhase.CAPTURING && windowStartedMs != 0L) {
            (windowStartedMs + limits.windowMs - clock.nowMs()).coerceAtLeast(0)
        } else {
            null
        }

    /** What is left of the finalization deadline, or null when it is not running. */
    fun remainingFinalizationMs(): Long? =
        if (phase == AnswerPhase.FINALIZING) {
            (captureEndedMs + limits.finalizationMs - clock.nowMs()).coerceAtLeast(0)
        } else {
            null
        }

    /** How long the recognizer attempt has been alive, or null outside an attempt. */
    fun attemptElapsedMs(): Long? =
        if (phase == AnswerPhase.CAPTURING || phase == AnswerPhase.FINALIZING) clock.nowMs() - prerollStartedMs else null

    /**
     * Open one attempt: AV-050's automatic open after the prompt settled, or the Start
     * answer the learner tapped to begin early.
     *
     * Exactly one per attempt either way. It starts the pre-roll; the answer window waits
     * for [speechBegan], or for the pre-roll to run out with nobody heard.
     */
    fun startAnswer(): OperationToken {
        check(phase == AnswerPhase.THINKING) { "Start answer needs a turn waiting for one, not ${phase.specName}" }
        attempt += 1
        sequence += 1
        val opened = OperationToken(sessionId, turn, sequence)
        token = opened
        prerollStartedMs = clock.nowMs()
        windowStartedMs = 0
        speechStartedMs = 0
        captureEndedMs = 0
        stoppedBy = null
        partialText = ""
        answer = null
        phase = AnswerPhase.CAPTURING
        return opened
    }

    /**
     * AV-050: the learner was heard for the first time in this attempt, [afterOpenMs] after
     * the microphone opened.
     *
     * It ends the pre-roll and starts the answer window, which is what keeps the 15 seconds
     * an honest speaking budget rather than a budget the learner spent remembering. Later
     * calls in the same attempt do nothing: the window is measured from the first onset, not
     * from every pause the learner takes inside it.
     *
     * It takes an **offset** rather than an instant because the transport measures it on a
     * clock of its own while this class is blocked inside the capture, and two monotonic
     * clocks share no origin. An offset past the pre-roll is clamped to it: a window may
     * start late, but never later than a capture that heard nobody at all would have started
     * one.
     */
    fun speechBegan(afterOpenMs: Long) {
        if (phase != AnswerPhase.CAPTURING || speechStartedMs != 0L) return
        // A pre-roll that already handed over to the window keeps that handover: an onset
        // reported afterwards may not wind the window back and shorten it.
        if (windowStartedMs != 0L) {
            speechStartedMs = windowStartedMs
            return
        }
        val onset = prerollStartedMs + afterOpenMs.coerceIn(0, limits.prerollMs)
        speechStartedMs = onset
        windowStartedMs = onset
    }

    /** [startAnswer] plus one capture through the contract, for a synchronous transport. */
    fun listen(): Answer? {
        val opened = startAnswer()
        return deliver(speech.listen(opened, language))
    }

    /** Explicit Done. Stops the microphone and starts the finalization deadline. */
    fun done(): Answer? {
        check(phase == AnswerPhase.CAPTURING) { "Done needs active capture, not ${phase.specName}" }
        stopCapture(CaptureStop.DONE)
        return poll()
    }

    /**
     * AV-050: the capture ended itself because the learner stopped speaking.
     *
     * The same stop [done] makes, with a reason of its own, so the record never reads an
     * endpoint as a gesture. It is refused before speech has begun: a learner who is still
     * thinking gets the pre-roll and then the window, never an instant timeout. Done and
     * Cancel have already taken effect by the time this could run, because both leave
     * [AnswerPhase.CAPTURING] behind them.
     */
    fun endpoint(): Answer? {
        check(phase == AnswerPhase.CAPTURING) { "An endpoint needs active capture, not ${phase.specName}" }
        check(heardSpeech) { "An endpoint needs speech to have begun" }
        stopCapture(CaptureStop.ENDPOINT)
        return poll()
    }

    /**
     * Advance the deadlines without a callback.
     *
     * Window expiry stops the microphone and preserves the card: it produces no answer and
     * never finalizes the learner's recall. Finalization expiry is a timeout.
     */
    fun poll(): Answer? {
        val now = clock.nowMs()
        if (phase == AnswerPhase.CAPTURING) {
            // AV-050: a pre-roll nobody spoke into hands over to the window rather than
            // ending the attempt, so a learner who starts late still gets the full window.
            if (windowStartedMs == 0L && now - prerollStartedMs >= limits.prerollMs) {
                windowStartedMs = prerollStartedMs + limits.prerollMs
            }
            if (windowStartedMs != 0L && now - windowStartedMs >= limits.windowMs) {
                stopCapture(CaptureStop.WINDOW_EXPIRY)
            }
        }
        if (phase == AnswerPhase.FINALIZING && now - captureEndedMs >= limits.finalizationMs) {
            val stop = stoppedBy?.specName.orEmpty()
            return settle(
                AnswerStatus.TIMED_OUT,
                "",
                failure = Failure(
                    SpeechInputFailure.LISTEN_TIMEOUT,
                    "no final within ${limits.finalizationMs} ms of $stop",
                ),
            )
        }
        return null
    }

    /**
     * One recognizer callback.
     *
     * A callback for another attempt, for a settled turn or from before Start answer is
     * ignored, so a delayed result from an older turn, attempt or revision can never
     * replace the current answer. Deadlines are applied before the payload, so a final
     * that arrives too late is a timeout rather than an answer.
     */
    fun deliver(event: CaptureEvent): Answer? {
        if (event.token != token || phase == AnswerPhase.THINKING || phase == AnswerPhase.SETTLED) {
            discarded += event
            return null
        }
        poll()?.let {
            discarded += event
            return it
        }
        return when (event) {
            is CaptureEvent.Failed -> settle(AnswerStatus.FAILED, "", failure = event.failure)
            is CaptureEvent.Transcript -> transcript(event)
        }
    }

    /** Explicit Cancel. The card is kept and nothing is inferred from a cancelled answer. */
    fun cancel(): Answer {
        check(phase == AnswerPhase.CAPTURING || phase == AnswerPhase.FINALIZING) {
            "Cancel needs an attempt in flight, not ${phase.specName}"
        }
        speech.cancel(checkNotNull(token))
        captureEndedMs = clock.nowMs()
        stoppedBy = CaptureStop.CANCELLED
        return settle(AnswerStatus.CANCELLED, "")
    }

    /**
     * A typed correction, or the learner accepting a transcript the recognizer did not
     * vouch for. Reachable at any point after the first Start answer, including after a
     * failure and after a grading suggestion was shown, and it always creates a new
     * revision that invalidates that suggestion.
     */
    fun correct(text: String): Answer {
        check(attempt > 0) { "There is nothing to correct before the first Start answer" }
        require(text.isNotBlank()) { "A corrected transcript must not be blank" }
        if (phase == AnswerPhase.CAPTURING || phase == AnswerPhase.FINALIZING) {
            speech.cancel(checkNotNull(token))
            captureEndedMs = clock.nowMs()
            stoppedBy = CaptureStop.CANCELLED
        }
        return settle(AnswerStatus.USER_CORRECTED, text.trim())
    }

    /**
     * Explicit Try again: a new attempt and a new revision for the same card.
     *
     * It only returns the turn to [AnswerPhase.THINKING]. **Amended by AV-050 (#81):** the
     * next attempt's microphone opens itself once, after that attempt's own prompt playback
     * settles — not on a result, and not from here. Nothing this class does reopens a
     * capture inside an attempt; [AnswerLimits.AUTOMATIC_REARMS] is still zero.
     */
    fun tryAgain() {
        check(attempt > 0) { "Try again needs an attempt to repeat" }
        token?.let { if (phase != AnswerPhase.SETTLED) speech.cancel(it) }
        transcriptRevision += 1
        answer = null
        partialText = ""
        token = null
        stoppedBy = null
        speechStartedMs = 0
        windowStartedMs = 0
        phase = AnswerPhase.THINKING
    }

    /** True only for this attempt's capture token. */
    fun isCurrent(candidate: OperationToken): Boolean = candidate == token

    /** True only for a suggestion that still describes the current transcript. */
    fun isCurrent(suggestion: GradingSuggestion): Boolean = suggestion.appliesTo(transcriptRevision)

    /**
     * #18's reply bound to this turn's current revision. A grade for a superseded
     * revision becomes [TurnGrading.Superseded]: discarded rather than displayed.
     */
    fun bind(reply: GradingReply, source: GradingSource, permittedRatings: List<Int>): TurnGrading =
        bindSuggestion(reply, source, permittedRatings, transcriptRevision)

    /** Done, an endpoint and expiry all stop the microphone; none cancels the pending final. */
    private fun stopCapture(stop: CaptureStop) {
        // Through the pre-roll the window has not started, so the latest this capture could
        // run to is the whole pre-roll followed by the whole window.
        val expiresAtMs = if (windowStartedMs != 0L) {
            windowStartedMs + limits.windowMs
        } else {
            prerollStartedMs + limits.captureMs
        }
        val now = clock.nowMs()
        // A Done that arrives after the window already ran out is recorded as the expiry.
        captureEndedMs = minOf(now, expiresAtMs)
        stoppedBy = if (now >= expiresAtMs) CaptureStop.WINDOW_EXPIRY else stop
        phase = AnswerPhase.FINALIZING
        // Whatever ended the window — Done or its expiry — the microphone ends with it.
        // The attempt stays alive, so this is not a cancel: the recognizer keeps until the
        // finalization deadline to produce its final. Without this the stop never left
        // this class, and a transport blocked inside a capture never heard about Done.
        token?.let { speech.finishAnswer(it) }
    }

    private fun transcript(event: CaptureEvent.Transcript): Answer? = when (event.kind) {
        TranscriptKind.PARTIAL -> {
            partialText = event.text
            Answer(AnswerStatus.PARTIAL, event.text, attempt, transcriptRevision, event.token, event.confidence)
        }
        TranscriptKind.CORRECTED ->
            throw IllegalArgumentException("Only an explicit learner edit may create a corrected transcript")
        TranscriptKind.FINAL -> if (event.text.isBlank()) {
            settle(
                AnswerStatus.FAILED,
                "",
                failure = Failure(SpeechInputFailure.NO_MATCH, "empty final; the cause stays unknown"),
            )
        } else {
            settle(AnswerStatus.FINAL, event.text, confidence = event.confidence)
        }
    }

    private fun settle(
        status: AnswerStatus,
        text: String,
        confidence: Confidence = Confidence.ABSENT,
        failure: Failure? = null,
    ): Answer {
        transcriptRevision += 1
        val result = Answer(status, text, attempt, transcriptRevision, checkNotNull(token), confidence, stoppedBy, failure)
        answer = result
        settled += result
        phase = AnswerPhase.SETTLED
        return result
    }
}
