package org.ankivoice.speech

/**
 * The AV-042 route, pinned. Every value here is an engineering selection accepted with
 * PR #57, not a measured optimum: changing one invalidates the live evidence behind it,
 * so android/README.md records them alongside this file.
 */
object SpeechPins {
    /** AV-006's engine, re-confirmed live in AV-042 attempts 7/8. */
    const val TTS_ENGINE = "com.google.android.tts"
    const val TTS_ENGINE_VERSION = "googletts.google-speech-apk_20241125.02_p2.702443970"

    /** The local voice; a network voice would make question audio depend on connectivity. */
    const val TTS_VOICE = "en-US-language"

    const val RECOGNITION_PACKAGE = TTS_ENGINE
    const val RECOGNITION_SERVICE =
        "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"

    /** The only evaluated locale. AV-001 keeps English as the initial language. */
    const val LANGUAGE = "en-US"

    /**
     * AV-006's accepted online-permitted mode. The AV-042 probe's first attempts set this
     * to true and produced no-match; the corrected value is part of the proven route.
     */
    const val PREFER_OFFLINE = false

    /** Capture format fed into the recognizer's external-audio pipe. */
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_COUNT = 1
    const val BYTES_PER_SAMPLE = 2

    /** 20 ms of audio. Small enough that Done stops the microphone promptly. */
    const val FRAME_SAMPLES = 320

    /**
     * AV-050: the frame amplitude at or above which a 20 ms frame counts as speech, for the
     * trailing-silence detector that ends a capture when the engine reports no endpoint.
     *
     * A **selected engineering bound**, not a measured noise floor. It is a mean absolute
     * sample value on the 16-bit scale — roughly 1.5% of full scale — chosen to sit above
     * an idle emulator's microphone floor and below ordinary speech. It decides only when a
     * capture *stops*; it never decides what was said, and a frame below it is still written
     * to the recognizer unchanged.
     */
    const val SPEECH_FRAME_AMPLITUDE = 500

    /** The one permission this module needs. AV-020 forbids READ_PHONE_STATE outright. */
    const val MICROPHONE_PERMISSION = "android.permission.RECORD_AUDIO"
}

/**
 * Transport timings. The defaults are the pinned AV-042 values; tests shrink them so a
 * deadline can be exercised without a five-second wait.
 *
 * [answerWindowMs] is **not** this module's policy. #13 (AV-012) owns the answer window
 * and normally stops capture first; this copy is only a backstop that keeps the
 * microphone from running forever if that stop never arrives. Reaching it behaves
 * exactly like Done, so it never converts a turn into a failure verdict of its own.
 * AV-050 added [prerollMs] beside it so the backstop mirrors `AnswerLimits` **as #13
 * measures it**: the window runs from the moment the learner is first heard, and only a
 * capture nobody spoke into gets the whole pre-roll and then the whole window. Anchoring
 * the backstop to the open instead would let this microphone outlive #13's own window by
 * the length of the pre-roll, and a final that arrives after that is past #13's
 * finalization deadline before it is even delivered.
 *
 * [endpointHoldMs], [silenceHoldMs] and [minCaptureMs] are AV-050's endpointing bounds.
 * Every one of them is a **selected engineering bound**, pinned in the same terms AV-042
 * pinned its own: nothing here was measured against a learner's speech.
 */
data class SpeechTimings(
    val settleMs: Long = 400,
    val trailingSilenceMs: Long = 500,
    val finalizationMs: Long = 5_000,
    val answerWindowMs: Long = 15_000,
    val playbackMs: Long = 30_000,
    /**
     * AV-050: AV-012's recall pre-roll, mirrored here for the backstop alone. It is the
     * grace a capture gets before [answerWindowMs] starts counting, and it ends the moment
     * the learner is first heard. #13 owns the policy; this copy only bounds the microphone.
     */
    val prerollMs: Long = 15_000,
    /**
     * AV-050: how long the capture waits after the engine says speech ended before it
     * finalizes itself.
     *
     * The engine's endpoint is the primary route. The hold exists because a learner who
     * pauses mid-answer produces an endpoint the engine then takes back, and finalizing on
     * the first one would cut them off; any further speech, segment or partial inside the
     * hold cancels it.
     */
    val endpointHoldMs: Long = 800,
    /**
     * AV-050: the fallback route's trailing silence, measured over the PCM frames the pump
     * already reads.
     *
     * Longer than [endpointHoldMs] on purpose. This one is our own judgement about audio
     * rather than the engine's about speech, so it is the more cautious of the two and only
     * ever runs when the engine reported no endpoint at all.
     */
    val silenceHoldMs: Long = 1_500,
    /**
     * AV-050: the shortest a capture may be before anything is allowed to end it on its
     * own. It protects a false start — a click, a breath, a first syllable the learner
     * restarts — from being taken for a whole answer. Done and Cancel are not bound by it.
     */
    val minCaptureMs: Long = 1_200,
    /**
     * How long the microphone and recognizer have to open before the attempt is abandoned.
     *
     * [AndroidSpeechPlatform] already guards the recognizer start with five seconds of its
     * own; this bounds the whole open, including the audio server call that an emulator or
     * device with wedged audio input never returns from. It is generous on purpose — a slow
     * open is still a usable turn — and short enough that a dead microphone is reported to
     * the learner rather than waited on.
     */
    val captureOpenMs: Long = 8_000,
) {
    init {
        require(settleMs >= 0 && trailingSilenceMs >= 0) { "Negative settle or trailing silence" }
        require(finalizationMs > 0 && answerWindowMs > 0 && playbackMs > 0) { "Non-positive deadline" }
        require(captureOpenMs > 0) { "Non-positive capture-open deadline" }
        require(prerollMs > 0) { "Non-positive pre-roll" }
        require(endpointHoldMs > 0 && silenceHoldMs > 0 && minCaptureMs > 0) {
            "Non-positive endpointing bound"
        }
        require(endpointHoldMs < answerWindowMs && silenceHoldMs < answerWindowMs) {
            "An endpoint hold that outlasts the backstop could never end a capture early"
        }
        require(trailingSilenceMs < finalizationMs) {
            "Finalization must include the trailing silence, not start after it"
        }
    }
}
