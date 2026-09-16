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
import org.ankivoice.core.commands.CommandContext
import org.ankivoice.core.commands.CommandOutcome
import org.ankivoice.core.commands.CommandRouter
import org.ankivoice.core.commands.VoiceCommand
import org.ankivoice.core.contracts.Capabilities
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.CardIdentity
import org.ankivoice.core.contracts.CardState
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.Grader
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.GuardedReviewWriter
import org.ankivoice.core.contracts.RawAcknowledgement
import org.ankivoice.core.contracts.ReviewTransport
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.grading.RuleGrader
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionResult
import org.ankivoice.core.session.SessionState
import org.ankivoice.core.session.transcriptText
import org.ankivoice.speech.AndroidSpeechPlatform
import org.ankivoice.speech.SpeechPins
import org.ankivoice.speech.SpeechTransport
import org.json.JSONArray
import org.json.JSONObject

/**
 * AV-014's live check on the pinned AVD, on a disposable AV-002 collection.
 *
 * Unlike AV-013's harness this one is **expected to write nothing at all**. The real
 * guarded writer is wired in behind a recording transport, so a command that reached it
 * would both land a review and show up in the evidence; a writer that could not write
 * would prove nothing. The card's stored state is re-read at the end and compared.
 *
 * Two layers, deliberately separate:
 *
 * - the **touch** sweep and the **context rule**, which need no microphone and run
 *   unattended (`-e mode touch`);
 * - the **voice** sweep, which only a person speaking into the AVD can produce
 *   (`-e interactive true`). Nothing here fabricates a transcript or attests on the
 *   operator's behalf.
 *
 * Reproduce with docs/testing/av014/runbook.md.
 */
class CommandInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    private var ui: LiveVerificationUi? = null
    private val interactive: Boolean get() = ui != null

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
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) {
                "The live check runs on the pinned emulator only"
            }
            check(args.getString("confirm") == "AV014_LIVE_COMMANDS") { "Explicit confirmation required" }

            val platform = AndroidAccessPlatform(targetContext)
            // Read-only discovery, so the runbook can name the disposable deck without a
            // second tool. It touches nothing and returns before any session is opened.
            if (args.getString("listDecks") == "true") {
                val decks = JSONArray()
                platform.queryDecks()?.forEach { decks.put(JSONObject().put("id", it.id).put("name", it.name)) }
                output.put("decks", decks)
                output.put("mode", "listDecks")
                return report(output, null, null)
            }
            if (args.getString("interactive") == "true") ui = LiveVerificationUi(this)
            output.put("mode", if (interactive) "interactive" else "touch")

            val deckId = checkNotNull(args.getString("deck")) { "A disposable AV-002 deck is required" }.toLong()
            val deck = if (platform.databasePermissionGranted() && platform.apiEnabled() != false) {
                platform.queryDecks()?.singleOrNull { it.id == deckId }
            } else {
                null
            }
            if (deck != null) check(deck.name.startsWith("AV002")) { "Refusing a deck that is not disposable" }

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

            val open = ReviewSession(
                provider = provider,
                speechOutput = transport,
                speechInput = transport,
                grader = RuleOnlyGrader,
                writer = GuardedReviewWriter(provider, recording, capabilities),
                capabilities = capabilities,
                language = language,
                sessionId = args.getString("session") ?: "av014-live",
            )
            session = open
            val router = CommandRouter(open, transport, language)
            open.start()

            val offered = open.offerCard()
            val card = offered.valueOrNull as? ScheduledCard
            check(card != null) { "No VoiceQA card was offered: $offered" }
            output.put("cardId", card.identity.cardId)
            output.put("before", describe(card.state))
            output.put("permittedRatings", JSONArray(card.permittedRatings))

            // The context rule first, because it leaves a resumable pause behind it.
            output.put("contextRule", contextRule(open, router, card, transport, language))
            if (open.halted) open.resume()
            output.put("voice", voiceSweep(open, router, language, card))
            output.put("touch", touchSweep(open, router, card))

            // Re-read what the provider actually stores, not the snapshot held above.
            val after = provider.readCard(card.identity.cardId) as? ScheduledCard
            output.put("after", after?.let { describe(it.state) } ?: JSONObject.NULL)
            output.put("stateUnchanged", after?.state == card.state)
            return report(output, open, recording)
        } catch (e: Throwable) {
            output.put("error", e.toString())
            return report(output, session, recorder)
        } finally {
            runCatching { speech?.releaseAll() }
            worker.shutdownNow()
        }
    }

    /**
     * The context rule on a real device: while AV-012's answer window is open, a command
     * phrase is answer text and no command capture may open.
     *
     * Interactively the operator speaks the phrase and the recognizer's own result is
     * recorded, wrong or not. Unattended, the window is opened for real and the router is
     * asked what the phrase means — nothing is fabricated as a recognizer result either way.
     */
    private fun contextRule(
        session: ReviewSession,
        router: CommandRouter,
        card: ScheduledCard,
        speech: SpeechTransport,
        language: String,
    ): JSONObject {
        val phrase = args.getString("falseTrigger") ?: "repeat the experiment"
        val result = JSONObject().put("phrase", phrase)
        if (session.state == SessionState.ASKING) session.ask()
        if (interactive) {
            val action = ui?.choose(
                "Speak a command word as an answer",
                "Say: \"$phrase\"\n\nIt must be graded as your answer, not executed as a command.",
                "Start answer", "Skip this one",
            )
            result.put("action", action)
            if (action != "Start answer") return result
        }
        val token = session.startAnswer()
        result.put("contextDuringWindow", router.context().specName)
        result.put("windowOpen", router.context() == CommandContext.ANSWER)
        result.put("spokenCommandsOffered", JSONArray(router.spokenAvailable().map { it.specName }))
        // What the router makes of the phrase while the window is open.
        val parsed = router.spoken(phrase, Confidence.SUFFICIENT)
        result.put("parsed", describe(parsed))
        result.put("keptVerbatim", (parsed as? CommandOutcome.AnswerText)?.text == phrase)
        // A command capture may not open alongside an answer capture.
        result.put("commandCapture", describe(router.listenForCommand()))

        if (interactive) {
            // The attempt is already open, so the capture is delivered into it rather than
            // opened again: ReviewSession.listen() would re-enter startAnswer and throw.
            val event = speech.listen(token, language)
            result.put("captureEvent", describe(event))
            val settled = session.acceptCapture(event)
            result.put("capture", describe(settled))
            result.put("transcript", session.answer?.text)
            result.put("answerStatus", session.answer?.status?.specName)
            result.put("confidence", session.answer?.confidence?.specName)
        } else {
            // An explicit learner-supplied transcript, recorded as the typed correction it
            // is. It is never presented as something the recognizer returned.
            val typed = args.getString("transcript") ?: card.fields.referenceAnswer
            session.correctTranscript(typed)
            result.put("typedCorrection", typed)
            result.put("answerStatus", session.answer?.status?.specName)
        }
        result.put("sessionState", session.state.specName)
        return result
    }

    /**
     * Each spoken command, through a learner-opened command capture.
     *
     * `-e voice repeat,pause` runs a subset, so a long operator session can be recorded a
     * few commands at a time. Resume is excluded: it is a touch control by decision.
     */
    /**
     * Put the session where [command] is actually offered, so what the sweep records is the
     * **recognition** result and not an unreachable context.
     *
     * Reveal, the ratings and the pre-commit pair need a gradable answer. It is supplied as
     * an explicit typed correction and recorded as one, because the answer pipeline is not
     * what this sweep is measuring — #14's live check already covers that.
     */
    private fun position(session: ReviewSession, command: VoiceCommand, card: ScheduledCard): JSONObject {
        val step = JSONObject()
        runCatching {
            if (session.state == SessionState.PAUSED) session.resume()
            if (session.state == SessionState.IDLE) session.offerCard()
            if (session.state == SessionState.ASKING) session.ask()
            val needsAnswer = command == VoiceCommand.REVEAL || command.rating != null ||
                command == VoiceCommand.CONFIRM || command == VoiceCommand.CHANGE
            if (needsAnswer) {
                if (session.state == SessionState.LISTENING && session.answer == null) {
                    session.startAnswer()
                    val typed = args.getString("transcript") ?: card.fields.referenceAnswer
                    session.correctTranscript(typed)
                    step.put("typedCorrection", typed)
                }
                if (session.state == SessionState.GRADING) session.grade()
                val needsPending = command == VoiceCommand.CONFIRM || command == VoiceCommand.CHANGE
                if (needsPending && session.state == SessionState.GRADING) {
                    card.permittedRatings.firstOrNull()?.let { session.propose(it) }
                }
            }
        }.onFailure { step.put("positioningError", it.toString()) }
        return step.put("positionedAs", session.state.specName)
    }

    private fun voiceSweep(
        session: ReviewSession,
        router: CommandRouter,
        language: String,
        card: ScheduledCard,
    ): JSONArray {
        val log = JSONArray()
        if (!interactive) {
            log.put(
                JSONObject()
                    .put("skipped", true)
                    .put(
                        "reason",
                        "the voice sweep needs a person speaking into the AVD; run with -e interactive true",
                    ),
            )
            return log
        }
        val requested = args.getString("voice")?.split(",")?.map(String::trim)?.filter { it.isNotEmpty() }
        val wanted = VoiceCommand.entries.filter { !it.touchOnly && (requested == null || it.specName in requested) }
        for (command in wanted) {
            // Reposition for each attempt, so one command cannot leave the next in a state
            // that would refuse it for a reason other than recognition.
            val positioned = position(session, command, card)
            val offered = command in router.available()
            val action = ui?.choose(
                "Say: ${command.specName.replace('-', ' ')}",
                "Context: ${router.context().specName}\nOffered here: $offered\n\n" +
                    "Tap Listen, then say the command. Nothing here submits a review.",
                "Listen", "Skip this one",
            )
            if (action != "Listen") {
                log.put(JSONObject().put("command", command.specName).put("action", action))
                continue
            }
            log.put(
                JSONObject()
                    .put("command", command.specName)
                    .put("language", language)
                    .put("context", router.context().specName)
                    .put("offered", offered)
                    .put("position", positioned)
                    .put("outcome", describe(router.listenForCommand()))
                    .put("sessionState", session.state.specName),
            )
        }
        return log
    }

    /**
     * Every command in the vocabulary, by touch, in an order that reaches each one.
     *
     * Each entry records whether the surface actually offered the command, so a refusal is
     * evidence rather than a gap.
     */
    private fun touchSweep(session: ReviewSession, router: CommandRouter, card: ScheduledCard): JSONArray {
        val log = JSONArray()
        fun tap(command: VoiceCommand) {
            log.put(
                JSONObject()
                    .put("command", command.specName)
                    .put("context", router.context().specName)
                    .put("offered", command in router.available())
                    .put("outcome", describe(router.touch(command)))
                    .put("sessionState", session.state.specName),
            )
        }
        ui?.show("Touch sweep", "Listen for the prompt, then again when Repeat runs. No review is written.")
        // A gradable transcript already exists from the context-rule step, so the ratings,
        // reveal and the pre-commit exchange are all live here.
        if (session.state == SessionState.GRADING) {
            tap(VoiceCommand.REPEAT)
            tap(VoiceCommand.REVEAL)
            session.grade()
            // All four ratings, so none of them is verified only by its neighbours.
            tap(VoiceCommand.RATE_AGAIN)
            tap(VoiceCommand.RATE_EASY)
            tap(VoiceCommand.RATE_GOOD)
            tap(VoiceCommand.CHANGE)
            tap(VoiceCommand.RATE_HARD)
            tap(VoiceCommand.CONFIRM)
            log.put(
                JSONObject()
                    .put("step", "afterConfirm")
                    .put("pendingRating", session.intent?.rating)
                    .put("confirmed", session.intent?.hasConfirmation())
                    .put("reviewState", session.intent?.state?.specName)
                    .put("comment", "a confirmation authorizes a review; it does not submit one"),
            )
        }
        tap(VoiceCommand.SKIP)
        tap(VoiceCommand.RESUME)
        if (session.state == SessionState.ASKING) session.ask()
        tap(VoiceCommand.PAUSE)
        tap(VoiceCommand.RESUME)
        if (session.state == SessionState.ASKING) session.ask()
        log.put(JSONObject().put("step", "card").put("cardId", session.card?.identity?.cardId ?: card.identity.cardId))
        tap(VoiceCommand.FINISH_SESSION)
        return log
    }

    private fun describe(outcome: CommandOutcome): JSONObject = JSONObject().apply {
        when (outcome) {
            is CommandOutcome.Executed -> put("kind", "executed")
                .put("command", outcome.command.specName)
                .put("source", outcome.source.specName)
            is CommandOutcome.Refused -> put("kind", "refused")
                .put("command", outcome.command?.specName)
                .put("reason", outcome.reason.specName)
            is CommandOutcome.AnswerText -> put("kind", "answer-text").put("text", outcome.text)
        }
        put("notice", outcome.notice)
    }

    private fun describe(event: CaptureEvent): JSONObject = JSONObject().apply {
        when (event) {
            is CaptureEvent.Transcript -> put("kind", "transcript")
                .put("text", event.text)
                .put("confidence", event.confidence.specName)
            is CaptureEvent.Failed -> put("kind", "failed").put("failure", event.failure.toString())
        }
    }

    private fun describe(result: SessionResult<*>): JSONObject = JSONObject().apply {
        when (result) {
            is SessionResult.Produced -> put("kind", "produced").put("value", result.value.toString())
            is SessionResult.Halted -> put("kind", "halted")
                .put("reason", result.halt.reason)
                .put("detail", result.halt.detail)
            SessionResult.Ignored -> put("kind", "ignored")
        }
    }

    private fun describe(state: CardState): JSONObject = JSONObject()
        .put("reps", state.reps)
        .put("cardType", state.cardType)
        .put("queue", state.queue)
        .put("due", state.due)
        .put("intervalDays", state.intervalDays)
        .put("lastReviewTimeSecs", state.lastReviewTimeSecs)

    /** Every attempt is written out, whether or not it came back the way it should. */
    private fun report(output: JSONObject, session: ReviewSession?, recorder: RecordingTransport?) {
        session?.let {
            output.put("sessionState", it.state.specName)
            output.put("events", it.transcriptText())
        }
        val writes = recorder?.calls ?: JSONArray()
        output.put("writes", writes)
        output.put("wroteNothing", writes.length() == 0)
        val completed = !output.has("error")
        // The whole point of this run: every command path left the collection alone. A
        // read-only deck listing opens no session, so it has no card state to compare.
        val listing = output.optString("mode") == "listDecks"
        val passed = completed && writes.length() == 0 && (listing || output.optBoolean("stateUnchanged"))
        output.put("completed", completed)
        output.put("passed", passed)
        File(targetContext.filesDir, "av014-result.json").writeText(output.toString(2))
        finish(
            if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("av014", output.toString()) },
        )
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
