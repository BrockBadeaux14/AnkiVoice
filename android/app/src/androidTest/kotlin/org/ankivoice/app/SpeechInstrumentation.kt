package org.ankivoice.app

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.PlaybackResult
import org.ankivoice.core.contracts.Utterance
import org.ankivoice.core.contracts.UtterancePurpose
import org.ankivoice.speech.AndroidSpeechPlatform
import org.ankivoice.speech.SpeechPins
import org.ankivoice.speech.SpeechTransport
import org.json.JSONObject

/**
 * AV-025's live check on the pinned AVD. It drives one real turn through the real
 * transport: pinned TTS plays the prompt, an explicit Start answer opens capture, and
 * Done produces a final transcript whose Done-to-final duration is recorded.
 *
 * It reads no collection, grades nothing and writes no review, so a live run cannot
 * touch a card. The operator supplies the expected phrase and confirms what they heard
 * and said; this harness never attests on their behalf.
 *
 * Reproduce with docs/testing/av025/runbook.md.
 */
class SpeechInstrumentation : Instrumentation() {
    private lateinit var args: Bundle

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        args = arguments
        start()
    }

    override fun onStart() {
        val output = JSONObject()
        val worker = Executors.newSingleThreadExecutor()
        val platform = AndroidSpeechPlatform(targetContext)
        val diagnostics = if (args.getString("diagnose") == "true") CaptureDiagnostics(platform) else null
        val transport = SpeechTransport(diagnostics ?: platform)
        try {
            check(args.getString("confirm") == "AV025_LIVE_SPEECH") { "Explicit confirmation required" }
            val token = OperationToken(args.getString("session") ?: "av025", 1, 1)
            val language = args.getString("language") ?: SpeechPins.LANGUAGE
            val prompt = args.getString("prompt") ?: "Name the three colors."
            val mode = args.getString("mode") ?: "turn"

            output.put("engine", SpeechPins.TTS_ENGINE)
            output.put("service", SpeechPins.RECOGNITION_SERVICE)
            output.put("preferOffline", SpeechPins.PREFER_OFFLINE)
            output.put("microphonePermission", platform.microphonePermissionGranted())
            output.put("expectedPhrase", args.getString("expect"))

            // Permission-denied path: revoke RECORD_AUDIO before running this mode.
            if (mode == "permission") {
                val event = transport.listen(token, language)
                output.put("capture", describe(event))
                finish(output)
                return
            }

            val ui = if (args.getString("interactive") == "true") LiveVerificationUi(this) else null
            if (ui != null) {
                check(ui.choose("Ready for the speech check", prompt, "Play prompt") == "Play prompt") {
                    "No operator start; no capture opened"
                }
                ui.show("Listen to the prompt", prompt)
            }

            val playback = transport.speak(token, Utterance(UtterancePurpose.QUESTION, prompt, language))
            output.put("playback", when (playback) {
                is PlaybackResult.Completed -> "completed"
                is PlaybackResult.Failed -> playback.failure.toString()
            })
            if (playback !is PlaybackResult.Completed) {
                finish(output)
                return
            }

            if (ui != null) {
                check(ui.choose("Ready to answer", "Say: ${args.getString("expect") ?: "your answer"}\n\nTap Start answer when ready.",
                    "Start answer") == "Start answer") { "No operator Start answer; no capture opened" }
            }
            // In interactive mode, this is the operator's actual Start answer touch.
            val capture = worker.submit<CaptureEvent> { transport.listen(token, language) }
            val speakMs = args.getString("speakMs")?.toLong() ?: 6_000L
            val action = if (ui != null) {
                ui.show("Opening microphone", "Wait one second…")
                SystemClock.sleep(1_000)
                ui.choose("Speak now", "${args.getString("expect") ?: "Say your answer"}\n\nTap Done when finished. This window ends automatically after ${speakMs / 1_000} seconds.",
                    "Done", "Cancel", timeoutMs = speakMs)
            } else {
                SystemClock.sleep(speakMs)
                null
            }
            output.put("operatorAction", action ?: if (ui != null) "window-expired" else "timed-harness")

            if (mode == "cancel" || action == "Cancel") {
                transport.cancel(token)
                output.put("capture", describe(capture.get(30, TimeUnit.SECONDS)))
                output.put("staleCallbacks", transport.staleCallbackLog().size)
                finish(output)
                return
            }

            val doneAt = SystemClock.elapsedRealtime()
            transport.finishAnswer(token)
            val event = capture.get(30, TimeUnit.SECONDS)
            output.put("doneToFinalMs", SystemClock.elapsedRealtime() - doneAt)
            output.put("capture", describe(event))
            diagnostics?.let { output.put("microphoneDiagnostics", it.save(File(targetContext.filesDir, "av025-input.pcm"))) }
            output.put("lastPartial", transport.lastPartial)
            output.put("staleCallbacks", transport.staleCallbackLog().size)
            ui?.let {
                val result = when (event) {
                    is CaptureEvent.Transcript -> "Transcript: ${event.text}"
                    is CaptureEvent.Failed -> "Capture failed: ${event.failure}"
                }
                sendStatus(1, Bundle().apply { putString("av025Capture", output.toString()) })
                output.put("operatorAttestation", it.attest(result, args.getString("expect") ?: "the answer"))
            }
        } catch (e: Exception) {
            output.put("error", e.toString())
        } finally {
            runCatching { transport.releaseAll() }
            worker.shutdownNow()
        }
        finish(output)
    }

    /** A failure is reported as a failure; it never becomes a transcript. */
    private fun describe(event: CaptureEvent): JSONObject = JSONObject().apply {
        when (event) {
            is CaptureEvent.Transcript -> {
                put("kind", "transcript")
                put("text", event.text)
                put("confidence", event.confidence.specName)
            }
            is CaptureEvent.Failed -> {
                put("kind", "failed")
                put("failure", event.failure.toString())
            }
        }
        put("token", event.token.toString())
    }

    private fun finish(output: JSONObject) {
        val results = Bundle()
        results.putString("av025", output.toString())
        finish(0, results)
    }
}
