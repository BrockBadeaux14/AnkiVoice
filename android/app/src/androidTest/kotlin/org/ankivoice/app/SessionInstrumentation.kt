package org.ankivoice.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
 * confirms on the learner's behalf: the explicit confirmation is an instrumentation
 * argument the operator supplies after hearing the prompt and saying the answer, and
 * without it the session refuses to submit. Every attempted turn is recorded, including
 * the ones that come back wrong.
 *
 * Reproduce with docs/testing/av013/runbook.md.
 */
class SessionInstrumentation : Instrumentation() {
    private lateinit var args: Bundle

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

            val platform = AndroidAccessPlatform(targetContext)
            val deckId = checkNotNull(args.getString("deck")) { "A disposable AV-002 deck is required" }.toLong()
            val deck = if (platform.databasePermissionGranted() && platform.apiEnabled() != false) {
                platform.queryDecks()?.singleOrNull { it.id == deckId }
            } else {
                null
            }
            if (deck != null) check(deck.name.startsWith("AV002")) { "Refusing a deck that is not disposable" }

            val speechPlatform = AndroidSpeechPlatform(targetContext)
            val speech = SpeechTransport(speechPlatform)
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

            val asked = session.ask()
            output.put("playback", if (asked is SessionResult.Produced) "completed" else describe(asked))
            if (asked !is SessionResult.Produced) return report(output, session)

            // The explicit Start answer. The operator is told to speak only after this.
            val token = session.startAnswer()
            val pending = capture.submit<CaptureEvent> { speech.listen(token, session.language) }
            SystemClock.sleep(args.getString("speakMs")?.toLong() ?: 6_000L)

            if (args.getString("mode") == "cancel") {
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
            output.put("lastPartial", speech.lastPartial)
            output.put("staleCallbacks", speech.staleCallbackLog().size)

            val accepted = session.acceptCapture(event)
            output.put("answer", describe(accepted))
            if (accepted !is SessionResult.Produced) return report(output, session)
            output.put("transcript", accepted.value.text)

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

            // The learner's explicit confirmation. Its absence is what refuses the write.
            if (args.getString("confirmRating") != proposal.toString()) {
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
        val passed = !output.has("error")
        output.put("passed", passed)
        File(targetContext.filesDir, "av013-result.json").writeText(output.toString(2))
        finish(
            if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("av013", output.toString()) },
        )
    }
}
