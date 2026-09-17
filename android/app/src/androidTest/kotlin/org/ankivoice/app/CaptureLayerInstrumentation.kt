package org.ankivoice.app

import android.app.Instrumentation
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.SpeechRecognizer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.speech.AndroidSpeechPlatform
import org.ankivoice.speech.SpeechPins
import org.ankivoice.speech.SpeechReadiness
import org.ankivoice.speech.SpeechTransport
import org.json.JSONArray
import org.json.JSONObject

/**
 * AV-046's diagnostic on the pinned AVD: one capture through the shipped transport, with
 * three independent views of the same window.
 *
 * - **The transport's recorder**, as the shipped `SpeechTransport` over
 *   `AndroidSpeechPlatform` read it: `CaptureDiagnostics` keeps every sample, and
 *   `PumpDiagnostics` keeps when they arrived and what the pump wrote to the recognizer's
 *   pipe. Both are pass-through observers; the pipe is exactly the one that ships.
 * - **A reference recorder** that uses nothing of that pipe: `ReferenceMicrophone`, a bare
 *   `AudioRecord` from this APK, running concurrently through the same window by default
 *   (`-e reference concurrent`), or on its own before or after the transport's capture
 *   (`before`, `after`), or not at all (`none`).
 * - **The recognizer's own callbacks**, through the inert `recognizerObserver` AV-044 added,
 *   and the audio server's view of every recording client while the microphone is open.
 *
 * The driver on the host adds what this process cannot see: the microphone-open index
 * within the boot, the emulator's own audio-backend log and the guest HAL's logcat.
 *
 * It constructs no card provider and no writer, so it is structurally incapable of touching
 * a card. It plays no prompt: the phrase is shown on the screen, the operator speaks it, and
 * only the operator attests to having done so. Nothing here synthesizes or injects audio.
 *
 * Reproduce with docs/testing/av046/runbook.md.
 */
class CaptureLayerInstrumentation : Instrumentation() {
    private lateinit var args: Bundle

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        args = arguments
        start()
    }

    override fun onStart() {
        val output = JSONObject()
        val timeline = JSONObject()
        val callbacks = JSONArray()
        val worker = Executors.newSingleThreadExecutor()
        val ready = CountDownLatch(1)
        val origin = SystemClock.elapsedRealtime()
        var transport: SpeechTransport? = null
        var reference: ReferenceMicrophone? = null

        fun now() = SystemClock.elapsedRealtime() - origin
        fun phase(name: String) {
            timeline.put(name, now())
            sendStatus(1, Bundle().apply { putString("av046Phase", name) })
        }

        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) {
                "The diagnostic runs on the pinned emulator only"
            }
            check(args.getString("confirm") == CONFIRMATION) { "Explicit confirmation required" }

            val attempt = checkNotNull(args.getString("attempt")) { "An attempt id is required" }
            val say = args.getString("say").orEmpty()
            val interactive = args.getString("interactive") != "false"
            val referenceMode = args.getString("reference") ?: CONCURRENT
            check(referenceMode in REFERENCE_MODES) { "reference must be one of $REFERENCE_MODES" }
            val referenceMs = args.getString("referenceMs")?.toLong() ?: 5_000L
            val speakMs = args.getString("speakMs")?.toLong() ?: 8_000L
            val boot = args.getString("boot")
            val openIndex = args.getString("openIndex")
            val note = args.getString("note")
            val language = SpeechPins.LANGUAGE
            check(interactive || say.isEmpty()) { "An unattended run records silence; it cannot be given a phrase" }

            val platform = AndroidSpeechPlatform(targetContext)
            val recorder = CaptureDiagnostics(platform)
            val pump = PumpDiagnostics(recorder, origin)
            val speech = SpeechTransport(pump)
            transport = speech

            // The observer records; nothing it does reaches the transport.
            platform.recognizerObserver = AndroidSpeechPlatform.RecognizerObserver { generation, callback, bundle ->
                val entry = JSONObject()
                    .put("atMs", now())
                    .put("generation", generation)
                    .put("callback", callback)
                    .put("bundle", describe(bundle))
                synchronized(callbacks) { callbacks.put(entry) }
                if (callback == "onReadyForSpeech") {
                    ready.countDown()
                    phase("ready-for-speech")
                }
            }

            output.put("attempt", attempt)
            output.put("say", say)
            output.put("source", if (interactive) "operator" else "nobody: unattended silence")
            output.put("interactive", interactive)
            output.put("referenceMode", referenceMode)
            output.put("reference", referenceMode)
            output.put("boot", boot ?: JSONObject.NULL)
            output.put("openIndex", openIndex ?: JSONObject.NULL)
            output.put("note", note ?: JSONObject.NULL)
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
            output.put("speakMs", speakMs)
            output.put("referenceMs", referenceMs)
            output.put("prompt", JSONObject.NULL)

            // The screen is always shown, so the app is in the foreground for every capture:
            // a background app's recorder is silenced by the platform, which would be a
            // fourth layer and not the one under investigation.
            val ui = LiveVerificationUi(this)
            val heading = listOfNotNull(
                "AV-046 · $attempt",
                boot?.let { "boot $it" },
                openIndex?.let { "open $it" },
            ).joinToString(" · ")

            if (interactive) {
                val started = ui.choose(
                    heading,
                    "Say: $say\n\n${note ?: "Speak it once, naturally, after the screen says Speak now."}" +
                        "\n\nTap Start answer when ready.",
                    "Start answer",
                    "Skip",
                )
                if (started != "Start answer") {
                    output.put("operatorAction", started ?: "no-start")
                    finish(output, callbacks, timeline)
                    return
                }
            } else {
                ui.show(heading, "Unattended silent probe. Nobody speaks; the room is what is recorded.")
            }
            phase("start")

            if (referenceMode == BEFORE) {
                output.put("reference", runReferenceAlone(ui, interactive, say, referenceMs, origin, "before"))
            }

            if (referenceMode == CONCURRENT) {
                val concurrent = ReferenceMicrophone(origin)
                reference = concurrent
                phase("reference-open")
                val opened = concurrent.start()
                output.put("referenceOpened", opened)
                if (opened) output.put("referenceFirstFrameWithinMs", concurrent.awaitFirstFrame(REFERENCE_FIRST_FRAME_MS))
            }

            val token = OperationToken("av046-$attempt", 1, 1)
            phase("listen-requested")
            val listenRequestedAt = now()
            val listening = worker.submit<CaptureEvent> { speech.listen(token, language) }

            val becameReady = ready.await(READY_MS, TimeUnit.MILLISECONDS)
            output.put("readyForSpeechMs", if (becameReady) now() - listenRequestedAt else JSONObject.NULL)
            if (!becameReady) phase("not-ready")
            // The audio server's view of every recording client this app can see, now.
            output.put("recordingClientsAtSpeakNow", recordingClients())
            phase("speak-now")

            val action = if (interactive) {
                ui.choose(
                    "Speak now",
                    "$say\n\n${note ?: ""}\n\nTap Done when finished. This window ends automatically after " +
                        "${speakMs / 1_000} seconds.",
                    "Done",
                    "Cancel",
                    timeoutMs = speakMs,
                )
            } else {
                ui.show("Recording", "Stay quiet. This window ends after ${speakMs / 1_000} seconds.")
                SystemClock.sleep(speakMs)
                null
            }
            output.put("operatorAction", action ?: if (interactive) "window-expired" else "unattended")

            if (action == "Cancel") {
                speech.cancel(token)
                output.put("capture", describe(listening.get(30, TimeUnit.SECONDS)))
                phase("cancelled")
            } else {
                phase("done")
                val doneAt = now()
                speech.finishAnswer(token)
                val event = listening.get(30, TimeUnit.SECONDS)
                phase("outcome")
                output.put("doneToFinalMs", now() - doneAt)
                output.put("capture", describe(event))
            }
            output.put("lastPartial", speech.lastPartial ?: JSONObject.NULL)
            output.put("lastConfidence", speech.lastConfidence?.toDouble() ?: JSONObject.NULL)
            output.put("staleCallbacks", JSONArray(speech.staleCallbackLog()))
            output.put("recordingClientsAfter", recordingClients())

            reference?.let {
                output.put("reference", it.save(File(targetContext.filesDir, REFERENCE_FILE)))
                reference = null
            }
            output.put("transportRecorder", recorder.save(File(targetContext.filesDir, TRANSPORT_FILE)))
            output.put("pump", pump.report())

            if (referenceMode == AFTER) {
                output.put("reference", runReferenceAlone(ui, interactive, say, referenceMs, origin, "after"))
            }

            if (interactive) {
                val capture = output.getJSONObject("capture")
                val shown = if (capture.getString("kind") == "transcript") {
                    "Transcript: ${capture.getString("text")} (confidence ${capture.getString("confidence")})"
                } else {
                    "Capture failed: ${capture.getString("failure")}"
                }
                val recorderPeak = output.getJSONObject("transportRecorder").optInt("peak")
                val referencePeak = output.optJSONObject("reference")?.optInt("peak")
                val levels = "Transport recorder peak $recorderPeak" +
                    (referencePeak?.let { " · reference peak $it" } ?: "")
                phase("attest")
                output.put("attestation", ui.attestSpoken("$shown\n$levels", say) ?: JSONObject().put("source", "none"))
            }
        } catch (error: Throwable) {
            output.put("error", "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { reference?.stop() }
            runCatching { transport?.releaseAll() }
            worker.shutdownNow()
        }
        finish(output, callbacks, timeline)
    }

    /**
     * The sequential reference: the bare recorder alone, with the operator asked to say the
     * phrase once more for it. It costs a microphone open of its own, which is why the
     * concurrent mode is the default.
     */
    private fun runReferenceAlone(
        ui: LiveVerificationUi,
        interactive: Boolean,
        say: String,
        referenceMs: Long,
        origin: Long,
        position: String,
    ): JSONObject {
        val alone = ReferenceMicrophone(origin)
        val opened = alone.start()
        val out: JSONObject
        try {
            if (opened) {
                if (interactive) {
                    ui.choose(
                        "Reference microphone ($position)",
                        "Say: $say\n\nThis recorder uses none of the app's pipe. Tap Done when finished; " +
                            "the window ends after ${referenceMs / 1_000} seconds.",
                        "Done",
                        timeoutMs = referenceMs,
                    )
                } else {
                    ui.show("Reference microphone ($position)", "Stay quiet.")
                    SystemClock.sleep(referenceMs)
                }
            }
        } finally {
            out = alone.save(File(targetContext.filesDir, REFERENCE_FILE))
        }
        return out.put("position", position).put("opened", opened)
    }

    private fun recordingClients(): JSONArray {
        val manager = targetContext.getSystemService(AudioManager::class.java) ?: return JSONArray()
        val configurations = runCatching { manager.activeRecordingConfigurations }.getOrNull().orEmpty()
        return JSONArray(
            configurations.map { configuration ->
                JSONObject()
                    .put("clientAudioSessionId", configuration.clientAudioSessionId)
                    .put("clientAudioSource", configuration.clientAudioSource)
                    .put("audioSource", runCatching { configuration.audioSource }.getOrNull() ?: JSONObject.NULL)
                    .put("clientSilenced", runCatching { configuration.isClientSilenced }.getOrNull() ?: JSONObject.NULL)
                    .put(
                        "clientFormat",
                        configuration.clientFormat?.let { "${it.sampleRate} Hz, channel mask ${it.channelMask}, encoding ${it.encoding}" }
                            ?: JSONObject.NULL,
                    )
                    .put(
                        "deviceFormat",
                        configuration.format?.let { "${it.sampleRate} Hz, channel mask ${it.channelMask}, encoding ${it.encoding}" }
                            ?: JSONObject.NULL,
                    )
                    .put(
                        "device",
                        configuration.audioDevice?.let {
                            JSONObject().put("id", it.id).put("type", it.type).put("productName", it.productName.toString())
                        } ?: JSONObject.NULL,
                    )
            },
        )
    }

    private fun finish(output: JSONObject, callbacks: JSONArray, timeline: JSONObject) {
        val snapshot = synchronized(callbacks) { JSONArray(callbacks.toString()) }
        output.put("callbacks", snapshot)
        output.put("timeline", timeline)
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
        // A truncated instrumentation stream is not lost evidence: the same record is on the device.
        runCatching { File(targetContext.filesDir, RESULT_FILE).writeText(output.toString(2)) }
        finish(0, Bundle().apply { putString("av046", output.toString()) })
    }

    private fun engineVersion(): String? = try {
        targetContext.packageManager.getPackageInfo(SpeechPins.TTS_ENGINE, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** The bundle by key, with the text and the scores read exactly and everything else by string. */
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
        const val CONFIRMATION = "AV046_LIVE_LAYERS"
        const val CONCURRENT = "concurrent"
        const val BEFORE = "before"
        const val AFTER = "after"
        const val NONE = "none"
        val REFERENCE_MODES = setOf(CONCURRENT, BEFORE, AFTER, NONE)
        const val READY_MS = 10_000L
        const val REFERENCE_FIRST_FRAME_MS = 2_000L
        const val TRANSPORT_FILE = "av046-transport.pcm"
        const val REFERENCE_FILE = "av046-reference.pcm"
        const val RESULT_FILE = "av046-result.json"
    }
}
