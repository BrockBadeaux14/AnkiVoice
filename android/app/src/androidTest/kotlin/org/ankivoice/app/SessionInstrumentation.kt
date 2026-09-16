package org.ankivoice.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.ankivoice.ankidroid.AndroidAccessPlatform
import org.ankivoice.ankidroid.AnkiDroidCardProvider
import org.ankivoice.ankidroid.AnkiDroidReviewTransport
import org.ankivoice.core.contracts.Capabilities
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.ConfirmationSource
import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.Grader
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.GuardedReviewWriter
import org.ankivoice.core.contracts.RatingConfirmation
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.grading.RuleGrader
import org.ankivoice.core.session.ProposalOutcome
import org.ankivoice.core.session.ReviewSession
import org.ankivoice.core.session.SessionResult
import org.ankivoice.core.session.SessionState
import org.ankivoice.core.session.transcriptText
import org.ankivoice.speech.AndroidSpeechPlatform
import org.ankivoice.speech.SpeechPins
import org.ankivoice.speech.SpeechReadiness
import org.ankivoice.speech.SpeechTransport
import org.json.JSONArray
import org.json.JSONObject

/**
 * AV-013's live check on the pinned AVD: one foreground turn driven by the real
 * [ReviewSession], through #26's shipped transport and #25's guarded writer, on a
 * disposable AV-002 collection.
 *
 * The operator speaks the answer. Nothing here fabricates a transcript, and nothing
 * confirms on the learner's behalf: the operator sees the actual transcript and proposal
 * before confirming by touch, or by a matching fresh challenge in terminal mode.
 * Without it the session refuses to submit. Every attempted turn is recorded, including
 * the ones that come back wrong.
 *
 * Reproduce with docs/testing/av013/runbook.md.
 */
class SessionInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    private var ui: LiveVerificationUi? = null

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        args = arguments
        start()
    }

    override fun onStart() {
        val output = JSONObject()
        val worker = Executors.newSingleThreadExecutor()
        val capture = Executors.newSingleThreadExecutor()
        var transport: SpeechTransport? = null
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) {
                "The live check runs on the pinned emulator only"
            }
            check(args.getString("confirm") == "AV013_LIVE_SESSION") { "Explicit confirmation required" }
            check(!args.containsKey("confirmRating")) {
                "Use awaitConfirmation and confirm the published transcript/proposal after capture"
            }
            if (args.getString("interactive") == "true") ui = LiveVerificationUi(this)

            val platform = AndroidAccessPlatform(targetContext)
            val deckId = checkNotNull(args.getString("deck")) { "A disposable AV-002 deck is required" }.toLong()
            val deck = if (platform.databasePermissionGranted() && platform.apiEnabled() != false) {
                platform.queryDecks()?.singleOrNull { it.id == deckId }
            } else {
                null
            }
            if (deck != null) check(deck.name.startsWith("AV002")) { "Refusing a deck that is not disposable" }

            val speechPlatform = AndroidSpeechPlatform(targetContext)
            val diagnostics = if (args.getString("diagnose") == "true") CaptureDiagnostics(speechPlatform) else null
            val speech = SpeechTransport(diagnostics ?: speechPlatform)
            transport = speech
            val language = args.getString("language") ?: SpeechPins.LANGUAGE
            // The capability preflight the shell runs before offering to study.
            output.put("microphonePermission", speechPlatform.microphonePermissionGranted())
            output.put("readiness", SpeechReadiness(speechPlatform).check(language)?.toString() ?: "ready")
            val provider = AnkiDroidCardProvider(platform, deckId, worker, targetContext.mainExecutor)
            val capabilities = provider.capabilities()
            check(capabilities is Capabilities) { "Capabilities unavailable: $capabilities" }
            val writer = GuardedReviewWriter(provider, AnkiDroidReviewTransport(platform), capabilities)

            val session = ReviewSession(
                provider = provider,
                speechOutput = speech,
                speechInput = speech,
                grader = RuleOnlyGrader,
                writer = writer,
                capabilities = capabilities,
                language = language,
                sessionId = args.getString("session") ?: "av013-live",
            )
            session.start()

            val offered = session.offerCard()
            output.put("offered", describe(offered))
            val card = offered.valueOrNull as? ScheduledCard
            check(card != null) { "No VoiceQA card was offered: ${describe(offered)}" }
            output.put("cardId", card.identity.cardId)
            output.put("expectedPhrase", args.getString("expect"))
            output.put("permittedRatings", JSONArray(card.permittedRatings))

            ui?.let {
                check(it.choose("Ready for the session check", card.fields.prompt, "Play prompt") == "Play prompt") {
                    "No operator start; no capture opened"
                }
                it.show("Listen to the prompt", card.fields.prompt)
            }

            val asked = session.ask()
            output.put("playback", if (asked is SessionResult.Produced) "completed" else describe(asked))
            if (asked !is SessionResult.Produced) return report(output, session)

            ui?.let {
                check(it.choose("Ready to answer", "Say: ${args.getString("expect") ?: "your answer"}",
                    "Start answer") == "Start answer") { "No operator Start answer; no capture opened" }
            }
            val token = session.startAnswer()
            val pending = capture.submit<CaptureEvent> { speech.listen(token, session.language) }
            val speakMs = args.getString("speakMs")?.toLong() ?: 6_000L
            val action = ui?.let {
                it.show("Opening microphone", "Wait one second…")
                SystemClock.sleep(1_000)
                it.choose("Speak now", "${args.getString("expect") ?: "Say your answer"}\n\nTap Done when finished. This window ends automatically after ${speakMs / 1_000} seconds.",
                    "Done", "Cancel", timeoutMs = speakMs)
            } ?: run {
                if (ui == null) SystemClock.sleep(speakMs)
                null
            }
            output.put("operatorAction", action ?: if (ui != null) "window-expired" else "timed-harness")

            if (args.getString("mode") == "cancel" || action == "Cancel") {
                // An explicit Cancel during capture: the card is kept and nothing is inferred.
                val halt = session.cancelAnswer()
                output.put("cancelledCapture", describe(pending.get(30, TimeUnit.SECONDS)))
                output.put("halt", JSONObject().put("reason", halt.reason).put("resumable", halt.resumable))
                output.put("recoveryOptions", JSONArray(session.recoveryOptions.map { it.specName }))
                output.put("wroteNothing", session.outcomes.isEmpty())
                return report(output, session)
            }

            val doneAt = SystemClock.elapsedRealtime()
            speech.finishAnswer(token)
            val event = pending.get(30, TimeUnit.SECONDS)
            output.put("doneToFinalMs", SystemClock.elapsedRealtime() - doneAt)
            output.put("capture", describe(event))
            diagnostics?.let { output.put("microphoneDiagnostics", it.save(File(targetContext.filesDir, "av013-input.pcm"))) }
            output.put("lastPartial", speech.lastPartial)
            output.put("staleCallbacks", speech.staleCallbackLog().size)
            ui?.let {
                val result = when (event) {
                    is CaptureEvent.Transcript -> "Transcript: ${event.text}"
                    is CaptureEvent.Failed -> "Capture failed: ${event.failure}"
                }
                sendStatus(1, Bundle().apply { putString("av013Capture", output.toString()) })
                output.put("operatorAttestation", it.attest(result, args.getString("expect") ?: "the answer"))
            }

            val accepted = session.acceptCapture(event)
            output.put("answer", describe(accepted))
            val answer = if (accepted is SessionResult.Produced) {
                accepted.value
            } else if (accepted is SessionResult.Halted && accepted.halt.reason == "lowConfidence" &&
                event is CaptureEvent.Transcript && ui != null
            ) {
                val action = ui!!.choose("Review the transcript",
                    "${event.text}\n\nThe recognizer supplied no usable confidence. Check the text before grading. Accepting it does not confirm a rating.",
                    "Use this transcript", "Stop without writing")
                output.put("transcriptReviewAction", action)
                if (action != "Use this transcript") return report(output, session)
                // AV-012 explicitly supports learner acceptance through this correction path.
                // Keep raw capture/confidence above; do not relabel it as confident recognition.
                session.correctTranscript(event.text).also {
                    output.put("acceptedTranscript", describeValue(it))
                    output.put("acceptedTranscriptRevision", it.transcriptRevision)
                }
            } else {
                return report(output, session)
            }
            output.put("transcript", answer.text)

            val graded = session.grade()
            val suggestion = graded.valueOrNull
            output.put("suggestion", suggestion?.let { "${it.label.specName}: ${it.reason}" })
            val proposal = suggestion?.proposedRating(card.permittedRatings)
                ?: checkNotNull(args.getString("selfGrade")) {
                    "The rules proposed nothing; the operator must supply an explicit self-grade"
                }.toInt()
            val opened = session.propose(proposal)
            check(opened is ProposalOutcome.Proposed) { "The rating was refused: $opened" }
            output.put("proposedRating", proposal)

            // Confirm the actual proposal, after capture, without starting another turn.
            // A fresh challenge prevents an old file from confirming a later attempt.
            if (!awaitConfirmation(output, proposal)) {
                output.put("confirmed", false)
                output.put("note", "no explicit confirmation for rating $proposal; nothing was written")
                return report(output, session)
            }
            val intent = checkNotNull(session.intent)
            val accepted2 = session.confirm(
                RatingConfirmation(
                    token = checkNotNull(intent.token),
                    identity = intent.cardSnapshot.identity,
                    rating = intent.rating,
                    transcriptRevision = intent.transcriptRevision,
                    source = ConfirmationSource.TOUCH,
                ),
            )
            output.put("confirmed", accepted2)
            check(accepted2) { "The operator's confirmation was refused" }

            val outcome = session.commit()
            output.put(
                "outcome",
                JSONObject()
                    .put("state", outcome.state.specName)
                    .put("reason", outcome.reason)
                    .put("failure", outcome.failure?.toString())
                    .put("acknowledgement", outcome.acknowledgement)
                    .put("submittedTimeMs", outcome.submittedTimeMs)
                    .put("expectedStoredTimeMs", outcome.expectedStoredTimeMs)
                    .put("writeAttempted", outcome.writeAttempted),
            )
            output.put("sessionState", session.state.specName)
            if (outcome.state == ReviewState.CONFIRMED) {
                output.put("announcement", session.announceResult(outcome).text)
                session.advance()
                output.put("advanced", session.state == SessionState.IDLE)
            }
            return report(output, session)
        } catch (e: Throwable) {
            output.put("error", e.toString())
            return report(output, null)
        } finally {
            runCatching { transport?.releaseAll() }
            worker.shutdownNow()
            capture.shutdownNow()
        }
    }

    private fun awaitConfirmation(output: JSONObject, rating: Int): Boolean {
        ui?.let {
            val name = mapOf(1 to "Again", 2 to "Hard", 3 to "Good", 4 to "Easy").getValue(rating)
            val action = it.choose("Confirm this review",
                "Transcript: ${output.optString("transcript")}\n\nProposed rating: $name ($rating)\n${output.optString("suggestion")}\n\nCard: ${output.optLong("cardId")}\n\nConfirm writes one review to the disposable AV002 collection.",
                "Confirm $name ($rating)", "Do not write")
            output.put("confirmationSource", "operator-touch")
            output.put("confirmationAction", action)
            return action == "Confirm $name ($rating)"
        }
        if (args.getString("awaitConfirmation") != "true") return false
        val response = File(targetContext.filesDir, "av013-confirmation.json")
        response.delete()
        val challenge = UUID.randomUUID().toString()
        val pending = JSONObject(output.toString())
            .put("confirmationChallenge", challenge)
            .put("awaitingConfirmation", true)
        val pendingFile = File(targetContext.filesDir, "av013-pending.json")
        pendingFile.writeText(pending.toString(2))
        sendStatus(1, Bundle().apply { putString("av013Pending", pending.toString()) })
        try {
            val deadline = SystemClock.elapsedRealtime() + 300_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (response.exists()) {
                    val reply = JSONObject(response.readText())
                    val matches = reply.optString("challenge") == challenge &&
                        reply.optInt("rating", -1) == rating
                    output.put("confirmationChallenge", challenge)
                    output.put("confirmationMatched", matches)
                    return matches
                }
                SystemClock.sleep(100)
            }
            output.put("confirmationTimedOut", true)
            return false
        } finally {
            pendingFile.delete()
            response.delete()
        }
    }

    /** AV-015's rules only. An unmatched answer is uncertain, which proposes no rating. */
    private object RuleOnlyGrader : Grader {
        override fun grade(request: GradingRequest): GradingReply = GradingReply(
            request,
            RuleGrader.grade(request.context)
                ?: GradingResult(GradeLabel.UNCERTAIN, "no rule match; the learner supplies the rating"),
        )

        override fun cancel(request: GradingRequest) = Unit
    }

    private fun describe(result: SessionResult<*>): Any = when (result) {
        is SessionResult.Produced -> describeValue(result.value)
        is SessionResult.Halted -> JSONObject()
            .put("halt", result.halt.reason)
            .put("detail", result.halt.detail)
            .put("resumable", result.halt.resumable)
            .put("reconciliationRequired", result.halt.reconciliationRequired)
        SessionResult.Ignored -> "ignored"
    }

    private fun describeValue(value: Any?): Any = when (value) {
        is ScheduledCard -> JSONObject()
            .put("cardId", value.identity.cardId)
            .put("model", value.identity.model)
            .put("reps", value.state.reps)
        is org.ankivoice.core.answer.Answer -> JSONObject()
            .put("status", value.status.specName)
            .put("text", value.text)
            .put("confidence", value.confidence.specName)
            .put("gradable", value.gradable)
        else -> value?.toString() ?: JSONObject.NULL
    }

    private fun describe(event: CaptureEvent): JSONObject = JSONObject().apply {
        when (event) {
            is CaptureEvent.Transcript -> put("kind", "transcript")
                .put("text", event.text)
                .put("confidence", event.confidence.specName)
            is CaptureEvent.Failed -> put("kind", "failed").put("failure", event.failure.toString())
        }
        put("token", event.token.toString())
    }

    /** Every attempted turn is written out, whether or not it came back the way it should. */
    private fun report(output: JSONObject, session: ReviewSession?) {
        session?.let {
            output.put("sessionState", it.state.specName)
            output.put("events", it.transcriptText())
        }
        val completed = !output.has("error")
        val cancelled = args.getString("mode") == "cancel" || output.optString("operatorAction") == "Cancel"
        val passed = completed && if (cancelled) output.optBoolean("wroteNothing") else
            output.optJSONObject("outcome")?.optString("state") == ReviewState.CONFIRMED.specName
        output.put("completed", completed)
        output.put("passed", passed)
        File(targetContext.filesDir, "av013-result.json").writeText(output.toString(2))
        finish(
            if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("av013", output.toString()) },
        )
    }
}
