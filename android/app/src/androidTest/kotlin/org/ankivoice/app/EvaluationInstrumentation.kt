package org.ankivoice.app

import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.ankivoice.core.answer.AnswerPhase
import org.ankivoice.core.answer.AnswerStatus
import org.ankivoice.core.answer.AnswerTurn
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.SystemMonotonicClock
import org.ankivoice.core.contracts.Utterance
import org.ankivoice.core.contracts.UtterancePurpose
import org.ankivoice.core.grading.RuleGrader
import org.ankivoice.speech.AndroidSpeechPlatform
import org.ankivoice.speech.SpeechPins
import org.ankivoice.speech.SpeechReadiness
import org.ankivoice.speech.SpeechTransport
import org.json.JSONObject

/**
 * AV-017's live STT capture on the pinned AVD: one bounded answer turn through the
 * shipped #13 + #26 path, so the corpus's STT-mistake answers are real recognizer output
 * rather than something this harness invented.
 *
 * It constructs no card provider and no writer, so it is structurally incapable of
 * touching a card: the prompt and the phrase to speak arrive as arguments, and the only
 * output is the transcript the recognizer returned plus the operator's own attestation of
 * what they actually said.
 *
 * A capture whose transcript matches the attested phrase is **not** an STT mistake and
 * cannot fill a corpus slot. Every attempt is recorded either way, including the ones
 * that come back correct and the ones the environment breaks.
 *
 * Reproduce with docs/testing/av017/runbook.md.
 */
class EvaluationInstrumentation : Instrumentation() {
    private lateinit var args: Bundle

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        args = arguments
        start()
    }

    override fun onStart() {
        val output = JSONObject()
        val capture = Executors.newSingleThreadExecutor()
        var transport: SpeechTransport? = null
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) {
                "The live capture runs on the pinned emulator only"
            }
            check(args.getString("confirm") == CONFIRMATION) { "Explicit confirmation required" }

            val slot = checkNotNull(args.getString("slot")) { "A corpus slot id is required" }
            val prompt = checkNotNull(args.getString("prompt")) { "The card's Prompt is required" }
            val expect = checkNotNull(args.getString("expect")) { "The phrase to speak is required" }
            val language = args.getString("language") ?: SpeechPins.LANGUAGE
            val speakMs = args.getString("speakMs")?.toLong() ?: 12_000L

            val platform = AndroidSpeechPlatform(targetContext)
            // Optional: copy the actual MIC frames, unaltered, so an attempt that comes
            // back wrong can be told apart from an emulator that never opened the host
            // device. AV-040/AV-042's 220 Hz fallback is visible here and nowhere else.
            val diagnostics = if (args.getString("diagnose") == "true") CaptureDiagnostics(platform) else null
            val speech = SpeechTransport(diagnostics ?: platform)
            transport = speech
            output.put("slot", slot)
            output.put("answerId", args.getString("answerId"))
            output.put("fixtureCard", args.getString("fixtureCard"))
            output.put("engine", SpeechPins.TTS_ENGINE)
            output.put("service", SpeechPins.RECOGNITION_SERVICE)
            output.put("preferOffline", SpeechPins.PREFER_OFFLINE)
            output.put("microphonePermission", platform.microphonePermissionGranted())
            output.put("readiness", SpeechReadiness(platform).check(language)?.toString() ?: "ready")
            output.put("expectedPhrase", expect)
            output.put("prompt", prompt)

            // AV-012's bounded window over AV-025's transport: the shipped #13 + #26 path.
            val turn = AnswerTurn(speech, SystemMonotonicClock, "av017-$slot", turn = 1, language = language)
            val ui = LiveVerificationUi(this)

            check(ui.choose("AV-017 capture · $slot", prompt, "Play prompt") == "Play prompt") {
                "No operator start; no capture opened"
            }
            ui.show("Listen to the prompt", prompt)
            val playback = speech.speak(PROMPT_TOKEN, Utterance(UtterancePurpose.QUESTION, prompt, language))
            output.put("playback", playback.javaClass.simpleName)

            check(
                ui.choose(
                    "Ready to answer",
                    "Say: $expect\n\nTap Start answer when ready. Speak it once, naturally.",
                    "Start answer",
                ) == "Start answer",
            ) { "No operator Start answer; no capture opened" }

            val token = turn.startAnswer()
            val listening = capture.submit<CaptureEvent> { speech.listen(token, language) }
            ui.show("Opening microphone", "Wait one second…")
            SystemClock.sleep(1_000)
            val action = ui.choose(
                "Speak now",
                "$expect\n\nTap Done when finished. This window ends automatically after " +
                    "${speakMs / 1_000} seconds.",
                "Done",
                "Cancel",
                timeoutMs = speakMs,
            )
            output.put("operatorAction", action ?: "window-expired")

            if (action == "Cancel") {
                turn.cancel()
                output.put("answerStatus", turn.answer?.status?.specName)
                finish(output)
                return
            }

            val doneAt = SystemClock.elapsedRealtime()
            if (turn.phase == AnswerPhase.CAPTURING) turn.done()
            speech.finishAnswer(token)
            val event = listening.get(30, TimeUnit.SECONDS)
            output.put("doneToFinalMs", SystemClock.elapsedRealtime() - doneAt)
            val answer = turn.deliver(event) ?: turn.poll()
            val transcript = answer?.takeIf { it.status == AnswerStatus.FINAL }?.text.orEmpty()
            output.put("answerStatus", answer?.status?.specName)
            output.put("confidence", answer?.confidence?.specName)
            output.put("transcript", answer?.text)
            output.put("gradable", answer?.gradable ?: false)
            output.put("needsLearnerReview", answer?.needsLearnerReview ?: false)
            output.put("failure", answer?.failure?.toString())
            output.put("lastPartial", turn.partialText)
            output.put("staleCallbacks", speech.staleCallbackLog().size)
            diagnostics?.let {
                output.put("microphoneDiagnostics", it.save(File(targetContext.filesDir, "av017-input.pcm")))
            }

            // The operator, not this harness, says what was spoken. A transcript that
            // equals the attested phrase is a correct recognition, not an STT mistake.
            val attestation = ui.attest(
                "Transcript: ${answer?.text?.ifBlank { null } ?: answer?.status?.specName ?: "none"}",
                expect,
            )
            output.put("attestation", attestation ?: JSONObject().put("source", "none"))
            output.put(
                "usableAsSttMistake",
                attestation?.optBoolean("spokeExpectedPhrase") == true &&
                    transcript.isNotBlank() &&
                    !matches(transcript, expect),
            )
        } catch (error: Throwable) {
            output.put("error", "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { transport?.releaseAll() }
            capture.shutdownNow()
        }
        finish(output)
    }

    private fun finish(output: JSONObject) {
        finish(0, Bundle().apply { putString("av017", output.toString()) })
    }

    /**
     * Whether the recognizer returned what the operator says they said, ignoring case and
     * the punctuation the recognizer never emits. A match is a correct recognition, so it
     * cannot fill an STT-mistake slot; only a real difference can.
     */
    private fun matches(transcript: String, expected: String): Boolean =
        RuleGrader.words(transcript) == RuleGrader.words(expected)

    private companion object {
        const val CONFIRMATION = "AV017_LIVE_CAPTURE"

        /** Playback happens before any attempt exists, so it carries its own token. */
        val PROMPT_TOKEN = OperationToken("av017-prompt", 1, 0)
    }
}
