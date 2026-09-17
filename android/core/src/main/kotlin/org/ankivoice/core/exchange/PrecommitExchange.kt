package org.ankivoice.core.exchange

import org.ankivoice.core.answer.AnswerPhase
import org.ankivoice.core.answer.AnswerRecovery
import org.ankivoice.core.commands.CommandOutcome
import org.ankivoice.core.commands.CommandRefusal
import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.PlaybackResult
import org.ankivoice.core.contracts.ReviewIntent
import org.ankivoice.core.contracts.ReviewOutcome
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.SpeechOutput
import org.ankivoice.core.contracts.Utterance
import org.ankivoice.core.contracts.UtterancePurpose
import org.ankivoice.core.session.ProposalOutcome
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionState

/**
 * Where a pending rating came from. Announced with every rating, and never inferred.
 *
 * AV-019 decision 4: a grader proposal carries the policy that produced it, a rating the
 * learner named carries [LEARNER], and [NONE] is the abstention — it is a statement that
 * no rating is pending, never a rating of its own.
 */
enum class RatingSource(val specName: String) {
    /** #16's deterministic rules matched on device, with no request. */
    RULE("rule"),

    /** #18's grader suggested it over #17's route, after the rules matched nothing. */
    AI("ai"),

    /** The learner named it, by a rating command or by an explicit self-grade. */
    LEARNER("learner"),

    /** Nobody proposed a rating. The exchange is open with nothing pending. */
    NONE("none"),
}

/** Anki's own names for the four ratings, for talkback. Never a rating the card refused. */
fun ratingName(rating: Int): String = when (rating) {
    1 -> "Again"
    2 -> "Hard"
    3 -> "Good"
    4 -> "Easy"
    else -> "rating $rating"
}

/**
 * One Announced position, as it was announced.
 *
 * [rating] is null for the abstention. [spoken] records whether the synthesizer actually
 * played it: an announcement that could not be spoken was still shown, and the pending
 * rating survives either way.
 */
data class RatingAnnouncement(
    val rating: Int?,
    val source: RatingSource,
    val transcriptRevision: Int,
    val utterance: Utterance,
    val spoken: Boolean,
) {
    /** True when the exchange is open with nothing pending and the learner must name one. */
    val isAbstention: Boolean get() = rating == null

    val text: String get() = utterance.text
}

/** What one step of the pre-commit exchange did. */
sealed interface ExchangeStep {
    /** What the learner is told. Never blank. */
    val notice: String

    /** Announced: a pending rating, or an abstention, is on screen and was spoken. */
    data class Announced(
        val announcement: RatingAnnouncement,
        override val notice: String,
    ) : ExchangeStep

    /**
     * A spoken confirmation was not usable. The pending rating is untouched and nothing
     * was written. [useTouch] is the second refusal in the same Announced position, which
     * stops re-prompting and points at the on-screen control instead.
     */
    data class Reprompted(
        /** How many confirmations have been refused in this Announced position, including this one. */
        val refusal: Int,
        val useTouch: Boolean,
        override val notice: String,
    ) : ExchangeStep

    /**
     * The single write ran, once. Everything the learner is told comes from [outcome] and
     * from nothing else — there is no success message before the final state is known.
     */
    data class Committed(
        val outcome: ReviewOutcome,
        val announcement: Utterance?,
        override val notice: String,
    ) : ExchangeStep

    /** The command was not part of the exchange. [notice] is the command layer's own. */
    data class Untouched(override val notice: String) : ExchangeStep
}

/**
 * AV-019: the pre-commit exchange as a state of its own, over AV-013's session.
 *
 * It owns what the **Announced** position says, what a correction re-announces, what a
 * confirmation that cannot be used gets told, the commit step that an accepted
 * confirmation runs, and the announcement made from the outcome the writer returned.
 *
 * It owns none of the things three closed cards already ship. It never parses an
 * utterance — #15's [CommandOutcome] arrives already decided — it never mints a rating,
 * never relaxes #14's confidence gate or its token and revision binding, and it performs
 * no write of its own: the single write is [ReviewSession.commit], which reaches the
 * journaled writer the composition root supplies.
 *
 * **Two positions, and a correction is not a commit.** *Announced* holds a pending rating
 * (or none) open for correction; *Confirmed* is an explicit confirm intent for this
 * attempt and this revision, and only it commits. Correcting is unlimited and free. There
 * is no exchange timeout: a pending rating waits for an explicit action and never expires
 * into a write or a discard.
 *
 * **Confinement.** Every call reaches straight into [ReviewSession], which fails loudly
 * when called from a thread other than the one that built it. Build this on that thread.
 *
 * [speechOutput] must be the session's own synthesizer. Announcements are spoken on a
 * token namespaced away from the session's playback, so an announcement is never mistaken
 * for question or reveal audio and never resolves the session's own playback; and they are
 * refused outright while AV-012 has an attempt in flight, because AV-025 forbids playing
 * into an open microphone.
 */
class PrecommitExchange(
    private val session: ReviewSession,
    private val speechOutput: SpeechOutput,
    private val owner: Thread = Thread.currentThread(),
) {
    private val announcementSessionId: String = "${session.sessionId}/announcement"
    private var sequence = 0

    /** The last announcement this exchange made, whether or not it still applies. */
    var announced: RatingAnnouncement? = null
        private set

    /** Spoken confirmations refused in the current Announced position. Reset by every announcement. */
    var refusals: Int = 0
        private set

    private val announcementLog = mutableListOf<RatingAnnouncement>()

    /** Every announcement this exchange made, oldest first, for evidence. */
    val announcements: List<RatingAnnouncement> get() = announcementLog

    /** What the writer returned for the last committed attempt, or null before one. */
    var outcome: ReviewOutcome? = null
        private set

    /** The intent [outcome] belongs to. One committed rating per attempt, enforced by identity. */
    private var committedIntent: ReviewIntent? = null

    /**
     * [outcome], but only while the session is still on the attempt it belongs to.
     *
     * Derived, so advancing to the next card cannot leave a previous card's verdict on
     * screen — least of all a "nothing was saved" over a review that was saved.
     */
    val settled: ReviewOutcome? get() = outcome?.takeIf { session.intent === committedIntent }

    /**
     * The Announced position as it stands **now**, or null when there is none.
     *
     * Derived from the session rather than remembered, so nothing here can outlive what it
     * describes. A transcript edit or a retry moves the revision on and #14 discards the
     * pending rating with it; both make the announcement stop applying, which is why an
     * old suggestion can never be re-announced against a new revision. A pause, a skip, a
     * finish or an interruption cancels the intent, and the position goes with it.
     */
    val position: RatingAnnouncement?
        get() {
            val made = announced ?: return null
            if (made.transcriptRevision != session.transcriptRevision) return null
            val pending = session.intent?.takeIf { it.state == ReviewState.PENDING }
            if (session.state == SessionState.PROPOSING && pending != null) {
                return made.takeIf { !it.isAbstention && it.rating == pending.rating }
            }
            // The abstention stands only while the turn could still take a rating at all.
            val ratable = session.state == SessionState.GRADING ||
                AnswerRecovery.SELF_GRADE in session.recoveryOptions
            return made.takeIf { it.isAbstention && pending == null && ratable }
        }

    /** True while the exchange holds a pending rating the learner may confirm or replace. */
    val open: Boolean
        get() = position != null && session.state == SessionState.PROPOSING

    // -- opening the exchange -------------------------------------------------- //

    /**
     * Open Announced with a grader's rating and announce where it came from.
     *
     * [source] is the policy that produced the label, which only the grader that chose the
     * route can state. A rating this card did not offer is not substituted or downgraded:
     * the exchange opens as an abstention instead and the learner names one.
     */
    fun openWithProposal(rating: Int, source: RatingSource): ExchangeStep {
        confine("openWithProposal")
        require(source == RatingSource.RULE || source == RatingSource.AI) {
            "A grader proposal is announced as a rule match or an AI suggestion, not ${source.specName}"
        }
        return when (session.propose(rating)) {
            is ProposalOutcome.Proposed -> announce(rating, source)
            is ProposalOutcome.Rejected -> abstain("this card did not offer ${ratingName(rating)}")
        }
    }

    /**
     * Open Announced with **no** pending rating: a rule miss with an AI abstention, a
     * `partial` or `uncertain` label, a grading failure or an exhausted quota.
     *
     * The absence of a suggestion never shortens the path and is never presented as a
     * rating. The learner names one, and then still confirms it.
     */
    fun abstain(detail: String): ExchangeStep {
        confine("abstain")
        return announce(null, RatingSource.NONE, detail)
    }

    // -- one already-parsed command -------------------------------------------- //

    /**
     * Consume one outcome from #15's router.
     *
     * Nothing here re-parses, re-gates or second-guesses it: a rating command has already
     * proposed or corrected through #14, and a confirmation has already been matched
     * against this attempt, this revision and this rating.
     */
    fun onCommand(outcome: CommandOutcome): ExchangeStep {
        confine("onCommand")
        return when (outcome) {
            // Unreachable from the confirmation context, and never the exchange's business.
            is CommandOutcome.AnswerText -> ExchangeStep.Untouched(outcome.notice)
            is CommandOutcome.Refused -> refused(outcome)
            is CommandOutcome.Executed -> when (outcome.command) {
                VoiceCommand.RATE_AGAIN, VoiceCommand.RATE_HARD,
                VoiceCommand.RATE_GOOD, VoiceCommand.RATE_EASY,
                -> corrected(outcome)
                VoiceCommand.CONFIRM -> commit(outcome)
                // The pending rating stands until a different one replaces it; the learner
                // has simply reopened the choice, so the re-prompt count starts over.
                VoiceCommand.CHANGE -> {
                    refusals = 0
                    ExchangeStep.Untouched(outcome.notice)
                }
                else -> ExchangeStep.Untouched(outcome.notice)
            }
        }
    }

    /**
     * The learner's own rating, from a rating command or from [ReviewSession.selfGrade].
     *
     * Called after the session already holds it, so this only announces. A separate
     * explicit confirmation is still required: naming a rating is not confirming it.
     */
    fun announceLearnerRating(): ExchangeStep {
        confine("announceLearnerRating")
        val pending = session.intent?.takeIf {
            session.state == SessionState.PROPOSING && it.state == ReviewState.PENDING
        } ?: return ExchangeStep.Untouched("There is no pending rating to announce.")
        return announce(pending.rating, RatingSource.LEARNER)
    }

    // -- internals -------------------------------------------------------------- //

    private fun corrected(executed: CommandOutcome.Executed): ExchangeStep {
        val pending = session.intent?.takeIf {
            session.state == SessionState.PROPOSING && it.state == ReviewState.PENDING
        } ?: return ExchangeStep.Untouched(executed.notice)
        return announce(pending.rating, RatingSource.LEARNER)
    }

    /**
     * The re-prompt rule: once, then the touch control.
     *
     * A spoken confirmation below `sufficient`, an ambiguous phrase heard where a
     * confirmation was expected, and a confirmation that did not match this attempt all
     * count the same way, because to the learner they are the same event — they said yes
     * and nothing happened. Neither refusal commits anything and neither clears the
     * pending rating.
     */
    private fun refused(refusal: CommandOutcome.Refused): ExchangeStep {
        val rating = position?.rating?.takeIf { open } ?: return ExchangeStep.Untouched(refusal.notice)
        val aboutConfirming = when (refusal.reason) {
            CommandRefusal.AMBIGUOUS -> true
            CommandRefusal.LOW_CONFIDENCE, CommandRefusal.CONFIRMATION_REJECTED ->
                refusal.command == VoiceCommand.CONFIRM
            else -> false
        }
        if (!aboutConfirming) return ExchangeStep.Untouched(refusal.notice)
        refusals += 1
        val useTouch = refusals >= 2
        val notice = if (useTouch) {
            "${refusal.notice} That is the second confirmation I could not use, so I will stop " +
                "asking: tap Confirm to save ${ratingName(rating)}, or tap a different rating. " +
                "Nothing has been written."
        } else {
            "${refusal.notice} ${ratingName(rating)} is still waiting and nothing was written. " +
                "Say Confirm again, or tap Confirm."
        }
        speak(notice)
        return ExchangeStep.Reprompted(refusals, useTouch, notice)
    }

    /**
     * The single write, from an accepted confirmation, exactly once for this attempt.
     *
     * Everything the learner is then told comes from the [ReviewOutcome] the writer
     * returned: `confirmed` is the only state that is announced as saved, `failed` says
     * plainly that nothing was written, and an unknown outcome says it is unknown and
     * hands the learner AV-018's halt rather than a success or a retry.
     */
    private fun commit(executed: CommandOutcome.Executed): ExchangeStep {
        val pending = session.intent
        if (session.state != SessionState.PROPOSING || pending == null || !pending.hasConfirmation()) {
            return ExchangeStep.Untouched(executed.notice)
        }
        check(pending !== committedIntent) { "This attempt already committed a rating" }
        val rating = pending.rating
        val written = session.commit()
        outcome = written
        committedIntent = pending
        announced = null
        refusals = 0
        return when (written.state) {
            ReviewState.CONFIRMED -> {
                val spoken = session.announceResult(written)
                speak(spoken.text)
                ExchangeStep.Committed(
                    written,
                    spoken,
                    "${spoken.text} Tap Next card to carry on. If ${ratingName(rating)} was the " +
                        "wrong rating, use AnkiDroid's own Undo — AnkiVoice cannot take a review back.",
                )
            }
            ReviewState.FAILED -> {
                val notice = "Nothing was saved. ${written.reason}. The card is exactly as it was: " +
                    "no review was written, nothing was buried, suspended or reordered. " +
                    describeHalt()
                speak(notice)
                ExchangeStep.Committed(written, null, notice)
            }
            // A writer that returns anything but a terminal state is unknown, not success.
            ReviewState.OUTCOME_UNKNOWN, ReviewState.PENDING, ReviewState.SUBMITTING -> {
                val notice = "AnkiVoice could not confirm what happened to ${ratingName(rating)}. " +
                    "${written.reason}. It will not send this review again. Open AnkiDroid, look at " +
                    "this card, then tell AnkiVoice what you saw — it cannot find out on its own."
                speak(notice)
                ExchangeStep.Committed(written, null, notice)
            }
        }
    }

    private fun announce(rating: Int?, source: RatingSource, detail: String = ""): ExchangeStep {
        val revision = session.transcriptRevision
        val text = announcementText(rating, source, revision, detail)
        val utterance = Utterance(UtterancePurpose.ANNOUNCEMENT, text, session.language)
        val played = speak(utterance)
        val made = RatingAnnouncement(rating, source, revision, utterance, played)
        announced = made
        announcementLog += made
        refusals = 0
        // A new Announced position is a new attempt's exchange; the previous outcome is not
        // this turn's and may not block this turn's single commit.
        if (session.intent !== committedIntent) {
            committedIntent = null
            outcome = null
        }
        return ExchangeStep.Announced(made, text)
    }

    private fun announcementText(rating: Int?, source: RatingSource, revision: Int, detail: String): String {
        // AV-012 raises the revision for every settled answer, typed correction and Try
        // again, so the first settled answer is version 1 and the learner's own count of
        // how many times they have answered this card matches it.
        val heard = "what I heard (answer version $revision)"
        if (rating == null) {
            val why = if (detail.isBlank()) "" else " ($detail)"
            return "No rating was suggested for $heard$why. Say or tap Again, Hard, Good or Easy to " +
                "choose one. Nothing is saved until you confirm it."
        }
        val provenance = when (source) {
            RatingSource.RULE -> "from an exact rule match on $heard"
            RatingSource.AI -> "suggested by the AI grader from $heard"
            RatingSource.LEARNER -> "because you chose it for $heard"
            // Unreachable: a rating is never announced without a source.
            RatingSource.NONE -> "for $heard"
        }
        return "${ratingName(rating)} is waiting, $provenance. Say or tap Confirm to save it, or " +
            "choose a different rating. Nothing is saved yet."
    }

    /** #14's halt, in the learner's words, for a write that provably did not land. */
    private fun describeHalt(): String {
        val halt = session.halt ?: return "Choose what to do next."
        return if (halt.resumable) {
            "Study is paused with the card kept: tap Resume to read it again from AnkiDroid, or " +
                "try the rating once more."
        } else {
            "Study stopped, because the card may have moved under it. Close the session and start " +
                "again to reload the card from AnkiDroid."
        }
    }

    private fun speak(text: String): Boolean =
        speak(Utterance(UtterancePurpose.ANNOUNCEMENT, text, session.language))

    /**
     * One announcement, on a token of the exchange's own.
     *
     * A synthesizer fault does not halt the turn and does not discard the pending rating:
     * the announcement is also on screen, and losing a correctable rating to a talkback
     * hiccup would be a worse answer than a silent one. It is recorded as unspoken rather
     * than reported as spoken.
     */
    private fun speak(utterance: Utterance): Boolean {
        val phase = session.answerTurn?.phase
        check(phase != AnswerPhase.CAPTURING && phase != AnswerPhase.FINALIZING) {
            "An announcement may not play into an open microphone"
        }
        sequence += 1
        val token = OperationToken(announcementSessionId, 0, sequence)
        return speechOutput.speak(token, utterance) is PlaybackResult.Completed
    }

    private fun confine(operation: String) {
        val current = Thread.currentThread()
        check(current === owner) {
            "$operation must run on the exchange's owning thread (${owner.name}), not ${current.name}"
        }
    }
}
