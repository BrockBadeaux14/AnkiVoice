package org.ankivoice.app

import android.app.Instrumentation
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionPart
import android.speech.SpeechRecognizer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.PlaybackResult
import org.ankivoice.core.contracts.Utterance
import org.ankivoice.core.contracts.UtterancePurpose
import org.ankivoice.speech.AndroidSpeechPlatform
import org.ankivoice.speech.SpeechPins
import org.ankivoice.speech.SpeechReadiness
import org.ankivoice.speech.SpeechTransport
import org.json.JSONArray
import org.json.JSONObject

/**
 * AV-044's discovery on the pinned AVD: one capture through the shipped transport with
 * every raw recognizer callback logged — the keys of each bundle, the text of each
 * segment, and whether `CONFIDENCE_SCORES` was there and what it held.
 *
 * The transport is exactly the shipped one and decides exactly what it ships; the
 * observer only records what the engine sent. It constructs no card provider and no
 * writer, so it is structurally incapable of touching a card. What was spoken arrives as
 * an argument and is recorded verbatim; the harness never claims the phrase was said.
 *
 * Two modes. Unattended (`-e source macos-say`), the host plays the phrase once the
 * recognizer reports ready. Interactive (`-e interactive true`), an operator speaks and
 * attests, exactly as AV-017's harness does.
 *
 * Reproduce with docs/testing/av044/runbook.md.
 */
class ConfidenceInstrumentation : Instrumentation() {
    private lateinit var args: Bundle

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        args = arguments
        start()
    }

    override fun onStart() {
        val output = JSONObject()
        val worker = Executors.newSingleThreadExecutor()
        var transport: SpeechTransport? = null
        val callbacks = JSONArray()
        val ready = CountDownLatch(1)
        val origin = SystemClock.elapsedRealtime()
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) {
                "The discovery runs on the pinned emulator only"
            }
            check(args.getString("confirm") == CONFIRMATION) { "Explicit confirmation required" }

            val attempt = checkNotNull(args.getString("attempt")) { "An attempt id is required" }
            val say = checkNotNull(args.getString("say")) { "The phrase to be spoken is required" }
            val source = args.getString("source") ?: "operator"
            val language = args.getString("language") ?: SpeechPins.LANGUAGE
            val speakMs = args.getString("speakMs")?.toLong() ?: 8_000L
            val prompt = args.getString("prompt")
            val interactive = args.getString("interactive") == "true"
            // An instruction for the operator, shown with the phrase and recorded with it.
            val note = args.getString("note")

            val platform = AndroidSpeechPlatform(targetContext)
            val diagnostics = if (args.getString("diagnose") != "false") CaptureDiagnostics(platform) else null
            val speech = SpeechTransport(diagnostics ?: platform)
            transport = speech

            // The observer records; nothing it does reaches the transport.
            platform.recognizerObserver = AndroidSpeechPlatform.RecognizerObserver { generation, callback, bundle ->
                val entry = JSONObject()
                    .put("atMs", SystemClock.elapsedRealtime() - origin)
                    .put("generation", generation)
                    .put("callback", callback)
                    .put("bundle", describe(bundle))
                synchronized(callbacks) { callbacks.put(entry) }
                if (callback == "onReadyForSpeech") {
                    ready.countDown()
                    sendStatus(1, Bundle().apply { putString("av044Phase", "ready-for-speech") })
                }
            }

            output.put("attempt", attempt)
            output.put("say", say)
            output.put("source", source)
            output.put("language", language)
            output.put("engine", SpeechPins.TTS_ENGINE)
            output.put("engineVersion", engineVersion())
            output.put("pinnedEngineVersion", SpeechPins.TTS_ENGINE_VERSION)
            output.put("service", SpeechPins.RECOGNITION_SERVICE)
            output.put("preferOffline", SpeechPins.PREFER_OFFLINE)
            output.put("fingerprint", Build.FINGERPRINT)
            output.put("sdk", Build.VERSION.SDK_INT)
            output.put("microphonePermission", platform.microphonePermissionGranted())
            output.put("readiness", SpeechReadiness(platform).check(language)?.toString() ?: "ready")
            output.put("prompt", prompt ?: JSONObject.NULL)
            output.put("note", note ?: JSONObject.NULL)
            output.put("speakMs", speakMs)

            val ui = if (interactive) LiveVerificationUi(this) else null

            if (prompt != null) {
                if (ui != null) {
                    check(ui.choose("AV-044 discovery · $attempt", prompt, "Play prompt") == "Play prompt") {
                        "No operator start; no capture opened"
                    }
                    ui.show("Listen to the prompt", prompt)
                }
                // The pinned engine can refuse synthesis for a while after a cold boot; each
                // attempt is recorded, and the capture below never opens on a failed prompt.
                val playbackAttempts = JSONArray()
                var playback: PlaybackResult
                var tries = 0
                do {
                    if (tries > 0) SystemClock.sleep(PROMPT_RETRY_MS)
                    tries += 1
                    playback = speech.speak(PROMPT_TOKEN, Utterance(UtterancePurpose.QUESTION, prompt, language))
                    playbackAttempts.put(when (playback) {
                        is PlaybackResult.Completed -> "completed"
                        is PlaybackResult.Failed -> playback.failure.toString()
                    })
                } while (playback !is PlaybackResult.Completed && tries < PROMPT_TRIES)
                output.put("playbackAttempts", playbackAttempts)
                output.put("playback", playbackAttempts.getString(playbackAttempts.length() - 1))
                if (playback !is PlaybackResult.Completed) {
                    sendStatus(1, Bundle().apply { putString("av044Phase", "playback-failed") })
                    finish(output, callbacks)
                    return
                }
            }

            if (ui != null) {
                check(
                    ui.choose(
                        "Ready to answer · $attempt",
                        "Say: $say\n\n${note ?: "Speak it once, naturally."}\n\nTap Start answer when ready.",
                        "Start answer",
                    ) == "Start answer",
                ) { "No operator Start answer; no capture opened" }
            }

            val token = OperationToken("av044-$attempt", 1, 1)
            val listenRequestedAt = SystemClock.elapsedRealtime()
            val listening = worker.submit<CaptureEvent> { speech.listen(token, language) }
            sendStatus(1, Bundle().apply { putString("av044Phase", "listen-requested") })

            // The host (or the operator screen) waits for the recognizer to report ready, so
            // the phrase is never played into a microphone that has not opened yet.
            val becameReady = ready.await(READY_MS, TimeUnit.MILLISECONDS)
            output.put("readyForSpeechMs", if (becameReady) SystemClock.elapsedRealtime() - listenRequestedAt else JSONObject.NULL)
            if (!becameReady) sendStatus(1, Bundle().apply { putString("av044Phase", "not-ready") })

            val action = if (ui != null) {
                ui.choose(
                    "Speak now",
                    "$say\n\n${note ?: ""}\n\nTap Done when finished. This window ends automatically after " +
                        "${speakMs / 1_000} seconds.",
                    "Done",
                    "Cancel",
                    timeoutMs = speakMs,
                )
            } else {
                if (becameReady) SystemClock.sleep(speakMs) else listening.get(READY_MS, TimeUnit.MILLISECONDS).let { null }
                null
            }
            output.put("operatorAction", action ?: if (ui != null) "window-expired" else "timed-harness")

            if (action == "Cancel") {
                speech.cancel(token)
                output.put("capture", describe(listening.get(30, TimeUnit.SECONDS)))
                finish(output, callbacks)
                return
            }

            val doneAt = SystemClock.elapsedRealtime()
            speech.finishAnswer(token)
            val event = listening.get(30, TimeUnit.SECONDS)
            output.put("doneToFinalMs", SystemClock.elapsedRealtime() - doneAt)
            output.put("capture", describe(event))
            output.put("lastPartial", speech.lastPartial ?: JSONObject.NULL)
            output.put("staleCallbacks", speech.staleCallbackLog().size)
            diagnostics?.let {
                output.put("microphoneDiagnostics", it.save(File(targetContext.filesDir, "av044-input.pcm")))
            }

            ui?.let {
                val shown = when (event) {
                    is CaptureEvent.Transcript -> "Transcript: ${event.text} (confidence ${event.confidence.specName})"
                    is CaptureEvent.Failed -> "Capture failed: ${event.failure}"
                }
                output.put("attestation", it.attest(shown, say) ?: JSONObject().put("source", "none"))
            }
        } catch (error: Throwable) {
            output.put("error", "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { transport?.releaseAll() }
            worker.shutdownNow()
        }
        finish(output, callbacks)
    }

    private fun finish(output: JSONObject, callbacks: JSONArray) {
        val snapshot = synchronized(callbacks) { JSONArray(callbacks.toString()) }
        output.put("callbacks", snapshot)
        // The per-segment view the card asks for: text, and whether scores came with it.
        val segments = JSONArray()
        for (i in 0 until snapshot.length()) {
            val entry = snapshot.getJSONObject(i)
            if (entry.getString("callback") != "onSegmentResults") continue
            val bundle = entry.getJSONObject("bundle")
            segments.put(
                JSONObject()
                    .put("atMs", entry.getLong("atMs"))
                    .put("text", bundle.optJSONArray("text") ?: JSONArray())
                    .put("hasConfidenceScores", bundle.optBoolean("hasConfidenceScores"))
                    .put("confidenceScores", bundle.opt("confidenceScores") ?: JSONObject.NULL),
            )
        }
        output.put("segments", segments)
        output.put("segmentCount", segments.length())
        output.put(
            "segmentsWithConfidenceScores",
            (0 until segments.length()).count { segments.getJSONObject(it).getBoolean("hasConfidenceScores") },
        )
        finish(0, Bundle().apply { putString("av044", output.toString()) })
    }

    private fun engineVersion(): String? = try {
        targetContext.packageManager.getPackageInfo(SpeechPins.TTS_ENGINE, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** Everything in the bundle, by key, with the two keys this card is about read exactly. */
    private fun describe(bundle: Bundle?): JSONObject {
        val out = JSONObject()
        if (bundle == null) return out.put("present", false)
        out.put("present", true)
        val keys = bundle.keySet().sorted()
        out.put("keys", JSONArray(keys))
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let { out.put("text", JSONArray(it)) }
        out.put("hasConfidenceScores", bundle.containsKey(SpeechRecognizer.CONFIDENCE_SCORES))
        val scores = bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        out.put("confidenceScores", scores?.let { JSONArray(it.map { v -> v.toDouble() }) } ?: JSONObject.NULL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            bundle.containsKey(SpeechRecognizer.RECOGNITION_PARTS)
        ) {
            val parts = bundle.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, RecognitionPart::class.java)
            out.put(
                "recognitionParts",
                JSONArray(
                    parts.orEmpty().map { part ->
                        JSONObject()
                            .put("rawText", part.rawText)
                            .put("formattedText", part.formattedText ?: JSONObject.NULL)
                            .put("timestampMillis", part.timestampMillis)
                            .put("confidenceLevel", part.confidenceLevel)
                    },
                ),
            )
        }
        val other = JSONObject()
        for (key in keys) {
            if (key in HANDLED) continue
            @Suppress("DEPRECATION")
            val value = bundle.get(key)
            other.put(
                key,
                when (value) {
                    null -> JSONObject.NULL
                    is FloatArray -> JSONArray(value.map { it.toDouble() })
                    is IntArray -> JSONArray(value.toList())
                    is LongArray -> JSONArray(value.toList())
                    is Array<*> -> JSONArray(value.map { it.toString() })
                    is Collection<*> -> JSONArray(value.map { it.toString() })
                    is Number, is Boolean, is String -> value
                    else -> value.toString()
                },
            )
        }
        if (other.length() > 0) out.put("other", other)
        return out
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

    private companion object {
        const val CONFIRMATION = "AV044_LIVE_CONFIDENCE"
        const val READY_MS = 10_000L
        const val PROMPT_TRIES = 4
        const val PROMPT_RETRY_MS = 4_000L
        val HANDLED = setOf(
            SpeechRecognizer.RESULTS_RECOGNITION,
            SpeechRecognizer.CONFIDENCE_SCORES,
            SpeechRecognizer.RECOGNITION_PARTS,
        )

        /** Playback happens before any attempt exists, so it carries its own token. */
        val PROMPT_TOKEN = OperationToken("av044-prompt", 1, 0)
    }
}
