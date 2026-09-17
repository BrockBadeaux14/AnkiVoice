package org.ankivoice.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import java.io.File
import java.util.concurrent.Executors
import org.ankivoice.ankidroid.AndroidAccessPlatform
import org.ankivoice.ankidroid.AnkiDroidCardProvider
import org.ankivoice.ankidroid.AnkiDroidReviewTransport
import org.ankivoice.core.commands.CommandOutcome
import org.ankivoice.core.commands.CommandRouter
import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.contracts.Capabilities
import org.ankivoice.core.contracts.CardIdentity
import org.ankivoice.core.contracts.CardState
import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.Grader
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.GuardedReviewWriter
import org.ankivoice.core.contracts.RawAcknowledgement
import org.ankivoice.core.contracts.ReviewTransport
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.exchange.ExchangeStep
import org.ankivoice.core.exchange.PrecommitExchange
import org.ankivoice.core.exchange.RatingSource
import org.ankivoice.core.exchange.ratingName
import org.ankivoice.core.grading.RuleGrader
import org.ankivoice.core.journal.JournalEntry
import org.ankivoice.core.journal.ReviewJournal
import org.ankivoice.core.answer.AnswerRecovery
import org.ankivoice.core.session.ProposalOutcome
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionState
import org.ankivoice.core.session.transcriptText
import org.ankivoice.speech.AndroidSpeechPlatform
import org.ankivoice.speech.SpeechPins
import org.ankivoice.speech.SpeechTransport
import org.json.JSONArray
import org.json.JSONObject

/**
 * AV-019's live check on the pinned AVD, on a disposable AV-002 collection.
 *
 * Unlike AV-014's sweep this one is **expected to write** in three of its five cases, and
 * that is the point: the exchange is the only path to the writer, so a run that wrote
 * nothing would prove nothing about it. Every write goes through the same
 * [studyWriter] the app composes — AV-018's journal around AV-024's guarded writer — so
 * the wiring under test is the wiring that ships, and each case reports the journal entry
 * it left behind as well as the review it did or did not add.
 *
 * One case per invocation, because the emulator's audio backend leaks a listener per
 * microphone open and exits on the second or third of a boot; the runbook's driver
 * cold-boots between cases. Every case needs the operator to speak the answer: nothing
 * here synthesizes a transcript, and nothing attests on their behalf.
 *
 * | Case | What it does | What must happen |
 * | --- | --- | --- |
 * | `confirmed` | announce, confirm as proposed, then try to confirm again | one review, journal settled `confirmed`, the duplicate refused |
 * | `corrected` | announce, correct to another rating, confirm that one | one review at the corrected rating, journal settled `confirmed` |
 * | `correction-only` | announce, correct, finish the session | no review, no journal entry |
 * | `abandoned` | announce, pause, finish the session | no review, no journal entry |
 * | `undo-handoff` | confirm, then hand off to AnkiDroid's Undo | one write, the session stopped, and whatever AnkiDroid's Undo then did, reported by the operator |
 *
 * Reproduce with docs/testing/av019/runbook.md.
 */
class ExchangeInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    private var ui: LiveVerificationUi? = null

    /** Passes every write through to AnkiDroid and records that it happened. */
    private class RecordingTransport(private val delegate: ReviewTransport) : ReviewTransport {
        val calls = JSONArray()

        override fun answerCard(identity: CardIdentity, rating: Int, elapsedMs: Long): RawAcknowledgement {
            calls.put(JSONObject().put("cardId", identity.cardId).put("rating", rating).put("elapsedMs", elapsedMs))
            return delegate.answerCard(identity, rating, elapsedMs)
        }
    }

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        args = arguments
        start()
    }

    override fun onStart() {
        val output = JSONObject()
        val worker = Executors.newSingleThreadExecutor()
        var speech: SpeechTransport? = null
        var recorder: RecordingTransport? = null
        var session: ReviewSession? = null
        var journal: ReviewJournal? = null
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) {
                "The live check runs on the pinned emulator only"
            }
            check(args.getString("confirm") == "AV019_LIVE_EXCHANGE") { "Explicit confirmation required" }
            val case = args.getString("case") ?: "confirmed"
            output.put("case", case)

            val platform = AndroidAccessPlatform(targetContext)
            val deckId = checkNotNull(args.getString("deck")) { "A disposable AV-002 deck is required" }.toLong()
            val deck = if (platform.databasePermissionGranted() && platform.apiEnabled() != false) {
                platform.queryDecks()?.singleOrNull { it.id == deckId }
            } else {
                null
            }
            check(deck != null && deck.name.startsWith("AV002")) { "Refusing a deck that is not disposable" }

            val journalFile = File(targetContext.filesDir, JournalModule.FILE)
            val store = FileJournalStore(journalFile)
            val open = ReviewJournal(store)
            journal = open
            output.put("journalPath", journalFile.absolutePath)
            output.put("journalBefore", entries(open))

            val speechPlatform = AndroidSpeechPlatform(targetContext)
            val transport = SpeechTransport(speechPlatform)
            speech = transport
            val language = args.getString("language") ?: SpeechPins.LANGUAGE
            output.put("microphonePermission", speechPlatform.microphonePermissionGranted())

            val provider = AnkiDroidCardProvider(platform, deckId, worker, targetContext.mainExecutor)
            val capabilities = provider.capabilities()
            check(capabilities is Capabilities) { "Capabilities unavailable: $capabilities" }
            val recording = RecordingTransport(AnkiDroidReviewTransport(platform))
            recorder = recording

            val sessionId = args.getString("session") ?: "av019-live-$case"
            // The shipped wiring, through the composition root's own function.
            var opened: ReviewSession? = null
            val study = ReviewSession(
                provider = provider,
                speechOutput = transport,
                speechInput = transport,
                grader = RuleOnlyGrader,
                writer = studyWriter(
                    GuardedReviewWriter(provider, recording, capabilities),
                    open,
                    sessionId,
                ) { opened },
                capabilities = capabilities,
                language = language,
                sessionId = sessionId,
            )
            opened = study
            session = study
            ui = LiveVerificationUi(this)
            val router = CommandRouter(study, transport, language)
            val exchange = PrecommitExchange(study, transport)
            study.start()

            val card = study.offerCard().valueOrNull as? ScheduledCard
            check(card != null) { "No VoiceQA card was offered" }
            output.put("cardId", card.identity.cardId)
            output.put("before", describe(card.state))
            output.put("permittedRatings", JSONArray(card.permittedRatings))

            output.put("answer", answer(study, transport, language, card))
            output.put("announced", announce(study, exchange, card))
            output.put("steps", run(case, study, router, exchange, transport, card))
            output.put("announcements", announcements(exchange))

            val after = provider.readCard(card.identity.cardId) as? ScheduledCard
            output.put("after", after?.let { describe(it.state) } ?: JSONObject.NULL)
            output.put("stateUnchanged", after?.state == card.state)
            output.put("journalAfter", entries(open))
            return report(output, study, recording, case)
        } catch (e: Throwable) {
            output.put("error", e.toString())
            journal?.let { output.put("journalAfter", entries(it)) }
            return report(output, session, recorder, args.getString("case") ?: "confirmed")
        } finally {
            runCatching { speech?.releaseAll() }
            worker.shutdownNow()
        }
    }

    // -- the shared opening ---------------------------------------------------- //

    /**
     * One spoken answer, from the operator.
     *
     * The prompt is played first, which is what opens AV-012's answer phase; the capture is
     * then delivered into the attempt the harness opened, rather than opened a second time.
     * Whatever the recognizer returned is recorded as it came back, right or wrong.
     */
    private fun answer(
        session: ReviewSession,
        speech: SpeechTransport,
        language: String,
        card: ScheduledCard,
    ): JSONObject {
        val result = JSONObject()
        if (session.state == SessionState.ASKING) session.ask()
        val action = ui?.choose(
            "Answer the card",
            "Listen for the question, then tap Start answer and say your answer out loud.\n\n" +
                "Anything close to \"${card.fields.referenceAnswer}\" is fine; the rating that " +
                "comes back is what this run is measuring, not your recall.",
            "Start answer", "Skip this case",
        )
        result.put("action", action)
        if (action != "Start answer") return result

        // A capture that comes back with nothing costs a Try again, not the whole case.
        // AV-012 already owns that path: `retry` opens a new revision and waits for the
        // next explicit Start answer, so each attempt is a real one and the earlier empty
        // one stays in the turn's own record rather than being overwritten.
        val attempts = JSONArray()
        var settled = false
        for (attempt in 1..MAX_ANSWER_ATTEMPTS) {
            val token = session.startAnswer()
            val event = speech.listen(token, language)
            session.acceptCapture(event)
            val answer = session.answer
            attempts.put(
                JSONObject()
                    .put("attempt", attempt)
                    .put("transcript", answer?.text)
                    .put("status", answer?.status?.specName)
                    .put("confidence", answer?.confidence?.specName)
                    .put("recognizerConfidence", speech.lastConfidence ?: JSONObject.NULL)
                    .put("sessionState", session.state.specName),
            )
            if (answer?.gradable == true) {
                settled = true
                break
            }
            if (attempt == MAX_ANSWER_ATTEMPTS) break
            // Only a fault the learner can act on in place may be retried; a halt that lost
            // the card is not one, and is reported rather than papered over.
            if (AnswerRecovery.TRY_AGAIN !in session.recoveryOptions) break
            val again = ui?.choose(
                "Nothing was heard",
                "The recognizer returned no answer (${session.lastFailure?.mode?.specName ?: "no answer"}). " +
                    "The card is kept and nothing was written.\n\nTap Try again and speak as soon as " +
                    "the button responds — the window is 15 seconds from the tap.",
                "Try again", "Give up on this case",
            )
            if (again != "Try again") break
            session.retry()
        }
        result.put("attempts", attempts)
        result.put("settled", settled)
        result.put("transcript", session.answer?.text)
        result.put("answerStatus", session.answer?.status?.specName)
        result.put("confidence", session.answer?.confidence?.specName)
        result.put("recognizerConfidence", speech.lastConfidence ?: JSONObject.NULL)
        result.put("transcriptRevision", session.transcriptRevision)
        result.put("sessionState", session.state.specName)
        // The operator's own word on what they said, unchecked until they check it.
        result.put("attested", ui?.attest(session.answer?.text ?: "nothing was heard", "an answer to this card"))
        return result
    }

    /**
     * Open AV-019's Announced position from what the rules made of that answer.
     *
     * This run grades on device, so the source is always an exact rule match or the
     * abstention; the AI route is AV-045's and costs the owner money, which a live check of
     * the exchange has no reason to spend.
     */
    private fun announce(session: ReviewSession, exchange: PrecommitExchange, card: ScheduledCard): JSONObject {
        val result = JSONObject()
        if (session.state != SessionState.GRADING) return result.put("skipped", session.state.specName)
        val graded = session.grade()
        val label = graded.valueOrNull?.label
        result.put("label", label?.specName)
        val rating = graded.valueOrNull?.proposedRating(card.permittedRatings)
        val step = when {
            rating != null -> exchange.openWithProposal(rating, RatingSource.RULE)
            else -> exchange.abstain("the rules answered ${label?.specName ?: "nothing"}")
        }
        return result.put("step", describe(step)).put("sessionState", session.state.specName)
    }

    // -- the five cases -------------------------------------------------------- //

    private fun run(
        case: String,
        session: ReviewSession,
        router: CommandRouter,
        exchange: PrecommitExchange,
        speech: SpeechTransport,
        card: ScheduledCard,
    ): JSONArray {
        val log = JSONArray()
        // The rules abstain on plenty of real answers, and AV-019's abstain path is the
        // route a rating gets named at all when they do. Take it rather than skipping the
        // case: the exchange still requires a separate confirmation afterwards, which is
        // the property every write case is here to exercise.
        if (session.state == SessionState.GRADING) log.put(nameRating(session, exchange, card))
        if (session.state != SessionState.PROPOSING && case != "abandoned") {
            return log.put(JSONObject().put("skipped", "no rating is pending: ${session.state.specName}"))
        }
        when (case) {
            "confirmed" -> {
                log.put(confirm(session, router, exchange, speech, "as proposed"))
                // The duplicate: refused by #15 because the session is no longer proposing,
                // so the exchange never sees a second commit at all.
                log.put(
                    JSONObject()
                        .put("step", "duplicate-confirm")
                        .put("offered", VoiceCommand.CONFIRM in router.available())
                        .put("outcome", describe(exchange.onCommand(router.touch(VoiceCommand.CONFIRM)))),
                )
            }
            "corrected" -> {
                log.put(correct(session, router, exchange, card))
                log.put(confirm(session, router, exchange, speech, "after a correction"))
            }
            "correction-only" -> {
                log.put(correct(session, router, exchange, card))
                ui?.show("Leaving it unconfirmed", "The corrected rating was never confirmed. Nothing may be written.")
                log.put(step("finish", exchange.onCommand(router.touch(VoiceCommand.FINISH_SESSION)), session))
            }
            "abandoned" -> {
                ui?.show("Abandoning the exchange", "Pausing and then finishing. Nothing may be written.")
                log.put(step("pause", exchange.onCommand(router.touch(VoiceCommand.PAUSE)), session))
                log.put(step("finish", exchange.onCommand(router.touch(VoiceCommand.FINISH_SESSION)), session))
            }
            "undo-handoff" -> {
                log.put(confirm(session, router, exchange, speech, "as proposed"))
                log.put(handOff(session))
            }
            else -> log.put(JSONObject().put("error", "unknown case $case"))
        }
        return log
    }

    /**
     * AV-019's abstain path, live: the rules offered nothing, so the learner names a rating
     * and the exchange announces it as learner-named. Naming is not confirming — a separate
     * explicit confirmation is still required, and nothing is written here.
     */
    private fun nameRating(
        session: ReviewSession,
        exchange: PrecommitExchange,
        card: ScheduledCard,
    ): JSONObject {
        val result = JSONObject().put("step", "self-grade")
        val chosen = card.permittedRatings.firstOrNull()
            ?: return result.put("skipped", "this card offered no rating")
        ui?.show(
            "No rating was suggested",
            "The rules offered nothing for that answer, so nothing is pending. Naming " +
                "${ratingName(chosen)} as your own rating — it still has to be confirmed " +
                "separately, and nothing is written yet.",
        )
        val proposed = session.selfGrade(chosen)
        result.put("rating", chosen)
        result.put("proposed", proposed is ProposalOutcome.Proposed)
        return result.put("outcome", describe(exchange.announceLearnerRating()))
            .put("sessionState", session.state.specName)
    }

    /** Replace the pending rating with one the operator picks. Nothing is written. */
    private fun correct(
        session: ReviewSession,
        router: CommandRouter,
        exchange: PrecommitExchange,
        card: ScheduledCard,
    ): JSONObject {
        val pending = session.intent?.rating
        val replacement = card.permittedRatings.firstOrNull { it != pending } ?: return JSONObject()
            .put("step", "correct").put("skipped", "this card offers only one rating")
        val command = VoiceCommand.entries.first { it.rating == replacement }
        ui?.show(
            "Correcting the rating",
            "${ratingName(pending ?: 0)} is waiting. Replacing it with ${ratingName(replacement)}.\n\n" +
                "A correction never writes: it re-announces and waits for a separate confirmation.",
        )
        return step("correct", exchange.onCommand(router.touch(command)), session)
            .put("from", pending)
            .put("to", replacement)
    }

    /**
     * The one step that writes. The operator chooses whether to say it or tap it, and the
     * source is recorded as it happened — never as the run would have preferred it.
     */
    private fun confirm(
        session: ReviewSession,
        router: CommandRouter,
        exchange: PrecommitExchange,
        speech: SpeechTransport,
        detail: String,
    ): JSONObject {
        val rating = session.intent?.rating
        val action = ui?.choose(
            "Confirm ${ratingName(rating ?: 0)}?",
            "This is the step that writes the review, $detail.\n\n" +
                "Say \"confirm\" after tapping Speak it, or tap Tap it instead. " +
                "Correcting instead of confirming would write nothing.",
            "Speak it", "Tap it", "Skip this case",
        )
        val result = JSONObject().put("step", "confirm").put("action", action).put("rating", rating)
        val outcome = when (action) {
            "Speak it" -> router.listenForCommand().also {
                result.put("recognizerConfidence", speech.lastConfidence ?: JSONObject.NULL)
            }
            "Tap it" -> router.touch(VoiceCommand.CONFIRM)
            else -> return result
        }
        result.put("command", describe(outcome))
        result.put("confirmationSource", session.intent?.confirmation?.source?.specName)
        result.put("outcome", describe(exchange.onCommand(outcome)))
        result.put("sessionState", session.state.specName)
        return result
    }

    /** AV-007's post-commit correction: AnkiDroid's own Undo, and nothing else. */
    private fun handOff(session: ReviewSession): JSONObject {
        val result = JSONObject().put("step", "undo-handoff")
        if (session.state != SessionState.COMMITTED) {
            return result.put("skipped", session.state.specName)
        }
        val halt = session.requestCorrectionAfterCommit()
        result.put("reason", halt.reason)
        result.put("detail", halt.detail)
        result.put("sessionState", session.state.specName)
        result.put("resumable", halt.resumable)
        val seen = ui?.choose(
            "Undo it in AnkiDroid",
            "${halt.detail}\n\nOpen AnkiDroid, use its own Undo on this card, then come back " +
                "and say what you saw. AnkiVoice will not undo anything itself.",
            "AnkiDroid offered Undo and I used it", "AnkiDroid did not offer Undo", "I did not check",
        )
        return result.put("operatorReport", seen)
    }

    // -- evidence --------------------------------------------------------------- //

    private fun step(name: String, outcome: ExchangeStep, session: ReviewSession): JSONObject =
        JSONObject().put("step", name).put("outcome", describe(outcome))
            .put("sessionState", session.state.specName)

    private fun announcements(exchange: PrecommitExchange): JSONArray {
        val log = JSONArray()
        exchange.announcements.forEach {
            log.put(
                JSONObject()
                    .put("rating", it.rating ?: JSONObject.NULL)
                    .put("source", it.source.specName)
                    .put("transcriptRevision", it.transcriptRevision)
                    .put("spoken", it.spoken)
                    .put("text", it.text),
            )
        }
        return log
    }

    private fun describe(step: ExchangeStep): JSONObject = JSONObject().apply {
        when (step) {
            is ExchangeStep.Announced -> put("kind", "announced")
                .put("rating", step.announcement.rating ?: JSONObject.NULL)
                .put("source", step.announcement.source.specName)
                .put("transcriptRevision", step.announcement.transcriptRevision)
                .put("spoken", step.announcement.spoken)
            is ExchangeStep.Reprompted -> put("kind", "reprompted")
                .put("refusal", step.refusal)
                .put("useTouch", step.useTouch)
            is ExchangeStep.Committed -> put("kind", "committed")
                .put("outcome", step.outcome.state.specName)
                .put("reason", step.outcome.reason)
                .put("acknowledgement", step.outcome.acknowledgement ?: JSONObject.NULL)
                .put("announcement", step.announcement?.text ?: JSONObject.NULL)
            is ExchangeStep.Untouched -> put("kind", "untouched")
        }
        put("notice", step.notice)
    }

    private fun describe(outcome: CommandOutcome): JSONObject = JSONObject().apply {
        when (outcome) {
            is CommandOutcome.Executed -> put("kind", "executed")
                .put("command", outcome.command.specName)
                .put("source", outcome.source.specName)
            is CommandOutcome.Refused -> put("kind", "refused")
                .put("command", outcome.command?.specName)
                .put("reason", outcome.reason.specName)
            is CommandOutcome.AnswerText -> put("kind", "answer-text")
        }
        put("notice", outcome.notice)
    }

    private fun describe(state: CardState): JSONObject = JSONObject()
        .put("reps", state.reps)
        .put("cardType", state.cardType)
        .put("queue", state.queue)
        .put("due", state.due)
        .put("intervalDays", state.intervalDays)
        .put("lastReviewTimeSecs", state.lastReviewTimeSecs)

    /** The transcript is the learner's own text; it stays in the journal, not in a diagnostic. */
    private fun entries(journal: ReviewJournal): JSONArray {
        val log = JSONArray()
        journal.entries().forEach { entry: JournalEntry ->
            log.put(
                JSONObject()
                    .put("entryId", entry.entryId)
                    .put("sessionId", entry.sessionId)
                    .put("cardId", entry.identity.cardId)
                    .put("rating", entry.rating)
                    .put("transcriptRevision", entry.transcriptRevision)
                    .put("transcriptLength", entry.transcript.length)
                    .put("phase", entry.phase.specName)
                    .put("outcomeState", entry.outcomeState?.specName ?: JSONObject.NULL)
                    .put("outcomeReason", entry.outcomeReason)
                    .put("acknowledgement", entry.acknowledgement ?: JSONObject.NULL)
                    .put("noticeOutstanding", entry.noticeOutstanding),
            )
        }
        return log
    }

    /**
     * Every attempt is written out, whether or not it came back the way it should.
     *
     * [passed] is what the case promised, and nothing more generous: the two cases that
     * must not write are checked for having written nothing, and the three that must write
     * are checked for exactly one write with a journal entry settled `confirmed` beside it.
     */
    private fun report(
        output: JSONObject,
        session: ReviewSession?,
        recorder: RecordingTransport?,
        case: String,
    ) {
        session?.let {
            output.put("sessionState", it.state.specName)
            output.put("events", it.transcriptText())
        }
        val writes = recorder?.calls ?: JSONArray()
        output.put("writes", writes)
        val after = output.optJSONArray("journalAfter") ?: JSONArray()
        val entries = (0 until after.length()).map { after.getJSONObject(it) }
        val added = entries.size - (output.optJSONArray("journalBefore")?.length() ?: 0)
        output.put("journalEntriesAdded", added)
        val confirmedEntry = entries.lastOrNull()?.optString("outcomeState") == "confirmed"
        val completed = !output.has("error")
        val passed = completed && when (case) {
            "correction-only", "abandoned" ->
                writes.length() == 0 && added == 0 && output.optBoolean("stateUnchanged")
            // After the handoff the operator uses AnkiDroid's own Undo, which this process
            // cannot see and must not assume either way. The harness therefore judges only
            // what it owns — one write reached the transport and the journal settled it from
            // the writer's outcome — and the driver, which snapshots the collection either
            // side, decides whether a review should have survived.
            "undo-handoff" -> writes.length() == 1 && added == 1 && confirmedEntry
            else -> writes.length() == 1 && added == 1 && confirmedEntry && !output.optBoolean("stateUnchanged")
        }
        output.put("completed", completed)
        output.put("passed", passed)
        File(targetContext.filesDir, "av019-result.json").writeText(output.toString(2))
        finish(
            if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("av019", output.toString()) },
        )
    }

    private companion object {
        /** Three spoken attempts per case; the emulator's audio backend rarely survives more. */
        const val MAX_ANSWER_ATTEMPTS = 3
    }

    /** AV-015's rules only, so this run needs no key, no quota and no network. */
    private object RuleOnlyGrader : Grader {
        override fun grade(request: GradingRequest): GradingReply = GradingReply(
            request,
            RuleGrader.grade(request.context)
                ?: GradingResult(GradeLabel.UNCERTAIN, "no rule match; the learner supplies the rating"),
        )

        override fun cancel(request: GradingRequest) = Unit
    }
}
