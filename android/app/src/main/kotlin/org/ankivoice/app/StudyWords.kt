package org.ankivoice.app

import org.ankivoice.core.answer.AnswerPhase
import org.ankivoice.core.contracts.ALL_FAILURE_MODES
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.ReviewOutcome
import org.ankivoice.core.contracts.ReviewWriterFailure
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.SpeechOutputFailure
import org.ankivoice.core.session.Halt
import org.ankivoice.core.session.Interruption
import org.ankivoice.core.session.SessionState

/**
 * What the study screen says, in the learner's words.
 *
 * Every halt the session can reach has a sentence here that names what happened, what it
 * did **not** do — nothing here ever announces a write the writer did not confirm — and
 * what the learner can do next. The spelling the contracts use (`noMatch`, `app_switch`)
 * is kept in brackets where it helps a bug report, never on its own.
 */
internal object StudyWords {
    const val READY = "Ready to study."
    const val OPENING = "Opening your deck…"
    const val LISTENING_ANSWER = "Listening. Speak your answer, then tap Done."
    const val LISTENING_COMMAND = "Listening for a command."

    /** The line under the card while a turn is in progress. Halts are described by [explain]. */
    fun status(state: SessionState, phase: AnswerPhase?, gradingInFlight: Boolean): String = when (state) {
        SessionState.IDLE -> OPENING
        SessionState.ASKING -> "Card ready. Play the prompt to hear the question."
        SessionState.LISTENING -> when (phase) {
            AnswerPhase.THINKING, null -> "Take your time. Tap Start answer when you are ready to speak."
            AnswerPhase.CAPTURING -> LISTENING_ANSWER
            AnswerPhase.FINALIZING -> "Finishing up what you said…"
            AnswerPhase.SETTLED -> "Your answer is recorded."
        }
        SessionState.RETRYING -> "Ready to try again. Tap Start answer when you are ready to speak."
        SessionState.GRADING -> if (gradingInFlight) "Checking your answer…" else "Your answer is recorded."
        SessionState.REVEALING -> "Playing…"
        SessionState.PROPOSING -> "A rating is waiting for your confirmation. Confirm it, or change it."
        SessionState.COMMITTING -> "Saving your review…"
        SessionState.COMMITTED -> "Review saved."
        SessionState.PAUSED -> "Paused."
        SessionState.OUTCOME_UNKNOWN -> "The review could not be confirmed."
        SessionState.INTERRUPTED -> "Study was interrupted."
        SessionState.UNSUPPORTED -> "The next card cannot be studied by voice."
        SessionState.STOPPED -> "Study stopped."
        SessionState.EXHAUSTED -> "You have finished this deck's queue."
    }

    /** Why a session ended because the single-active-reviewer precondition broke. */
    fun interruption(kind: Interruption): String {
        val cause = when (kind) {
            Interruption.APP_SWITCH -> "AnkiVoice left the foreground"
            Interruption.LOCK -> "the screen locked"
            Interruption.SYNC -> "the collection was synced"
            Interruption.CONCURRENT_MODIFICATION -> "the collection changed underneath it"
            Interruption.PROCESS_RESUME -> "the app was restarted"
            Interruption.EXTERNAL_AUDIO -> "another app took over the audio"
        }
        return "Study stopped because $cause. The microphone was released and nothing was written. " +
            "Reload to continue; anything left unsettled is checked first."
    }

    /** One halt, explained. [failure] is the session's last failure and [outcome] the last write's. */
    fun explain(halt: Halt, failure: Failure?, outcome: ReviewOutcome?): String {
        // A write failure halts under the failure's name without setting the session's last
        // failure, so the reason is resolved by name when the last failure does not match it.
        val mode = failure?.mode?.takeIf { it.specName == halt.reason }
            ?: ALL_FAILURE_MODES.firstOrNull { it.specName == halt.reason }
            ?: failure?.mode
        val interruption = Interruption.entries.firstOrNull { it.specName == halt.reason }
        return when {
            halt.state == SessionState.OUTCOME_UNKNOWN || halt.reconciliationRequired ->
                "AnkiVoice cannot tell whether that review was saved" +
                    (outcome?.reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                    ". It will not send it again. Open AnkiDroid, look at this card, then report what you saw."
            halt.reason == "queue_exhausted" -> "You have finished this deck's queue. Nothing more is due right now."
            halt.reason == "session_finished" -> "Session finished. Nothing was written by finishing."
            halt.reason == "native_undo_handoff" ->
                "This session closed so you can use AnkiDroid's own Undo. AnkiVoice cannot take a review " +
                    "back. Start again to read the card afresh."
            halt.reason == "learner_paused" ->
                "Paused. The microphone is released and the card is kept. Nothing was written."
            halt.reason == "skip_requested" ->
                "Skipped without a write. AnkiDroid has no skip that leaves scheduling alone, so this card " +
                    "is exactly as it was and will be offered again. Resume to continue, or finish."
            halt.reason == "answer_cancelled" ->
                "You cancelled that answer. The card is kept and nothing was inferred from it. Try again, " +
                    "or type your answer."
            interruption != null -> interruption(interruption)
            mode is SpeechInputFailure -> speechInput(mode)
            mode is SpeechOutputFailure ->
                "The question could not be played (${mode.specName}). Resume to read the card again, or finish."
            mode is GraderFailure ->
                "No grade was available (${mode.specName}). Rate the answer yourself, or try again. " +
                    "Nothing was written."
            mode is ReviewWriterFailure ->
                "Nothing was saved (${outcome?.reason?.takeIf { it.isNotBlank() } ?: halt.detail}). The card is " +
                    "exactly as it was: no review was written, nothing was buried, suspended or reordered. " +
                    if (halt.resumable) "Resume to read it again." else "Reload to read it again."
            mode is CardProviderFailure -> cardProvider(mode, halt)
            halt.state == SessionState.UNSUPPORTED ->
                halt.detail.ifBlank { "The next card cannot be studied by voice." } +
                    " Fix it in AnkiDroid, then reload."
            halt.state == SessionState.STOPPED ->
                "Study stopped (${halt.reason}). ${halt.detail} Reload to read the collection again.".trim()
            else -> "Paused (${halt.reason}). ${halt.detail}".trim()
        }
    }

    /** Why no session could open at all, before a card was read. */
    fun unavailable(failure: Failure): String = when (val mode = failure.mode) {
        CardProviderFailure.DECK_MISSING -> "Study is unavailable: no deck is selected, or the deck is gone. Go back to setup and choose one."
        is CardProviderFailure -> "Study is unavailable: AnkiDroid could not be reached (${mode.specName}). Check access in setup, then reload."
        else -> "Study is unavailable (${mode.specName}). ${failure.detail}".trim()
    }

    /** The grading status line, bound to the revision it graded. */
    fun grading(record: GradingRecord): String = when {
        record.failure != null ->
            "No suggestion: the grader was unavailable (${record.failure}). Rate it yourself."
        record.proposedRating == null ->
            "No suggestion: ${if (record.source == GradingRecord.RULE) "the rules" else "the AI grader"} " +
                "answered ${record.label ?: "nothing"}. Rate it yourself."
        record.source == GradingRecord.RULE -> "Rule match: ${record.reason.orEmpty()}".trimEnd(' ', ':')
        else -> "AI suggestion (${record.label}, ${record.route ?: "ai"} route): ${record.reason.orEmpty()}".trimEnd(' ', ':')
    }

    private fun speechInput(mode: SpeechInputFailure): String = when (mode) {
        SpeechInputFailure.PERMISSION_DENIED ->
            "Microphone access is denied. Allow it in Android's settings, then resume."
        SpeechInputFailure.NO_MATCH, SpeechInputFailure.NO_SPEECH_DETECTED ->
            "Nothing usable was heard. The card is kept. Try again, or type your answer."
        SpeechInputFailure.LISTEN_TIMEOUT ->
            "The recognizer did not finish in time. The card is kept. Try again, or type your answer."
        SpeechInputFailure.LOW_CONFIDENCE ->
            "The recognizer was not sure it heard you right. Check the transcript: edit it, use it as it is, " +
                "or try again."
        SpeechInputFailure.EARLY_CLOSURE ->
            "The recording ended before an answer was recognized. Try again, or type your answer."
        SpeechInputFailure.RECOGNIZER_UNAVAILABLE, SpeechInputFailure.RECOGNIZER_ERROR,
        SpeechInputFailure.NETWORK_UNAVAILABLE, SpeechInputFailure.QUOTA_EXHAUSTED,
        ->
            "Speech recognition is not available right now (${mode.specName}). Try again, or type your answer."
    }

    private fun cardProvider(mode: CardProviderFailure, halt: Halt): String = when (mode) {
        CardProviderFailure.DECK_MISSING ->
            "This deck is no longer available. Go back to setup and choose a deck."
        CardProviderFailure.COLLECTION_CHANGED ->
            "The collection changed underneath the session. Reload to read it again."
        CardProviderFailure.CARD_NOT_FOUND ->
            "That card is no longer in the collection. Resume to read the next one."
        CardProviderFailure.UNSUPPORTED_NOTE_TYPE, CardProviderFailure.MALFORMED_CARD ->
            halt.detail.ifBlank { "The next card cannot be studied by voice." } + " Fix it in AnkiDroid, then reload."
        CardProviderFailure.ACCESS_DENIED, CardProviderFailure.API_DISABLED,
        CardProviderFailure.PACKAGE_UNAVAILABLE, CardProviderFailure.NULL_CURSOR,
        ->
            "AnkiDroid could not be reached (${mode.specName}). Check access in setup, then " +
                if (halt.resumable) "resume." else "reload."
    }
}
