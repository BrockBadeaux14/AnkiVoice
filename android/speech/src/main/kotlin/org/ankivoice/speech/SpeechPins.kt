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
 */
data class SpeechTimings(
    val settleMs: Long = 400,
    val trailingSilenceMs: Long = 500,
    val finalizationMs: Long = 5_000,
    val answerWindowMs: Long = 15_000,
    val playbackMs: Long = 30_000,
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
        require(trailingSilenceMs < finalizationMs) {
            "Finalization must include the trailing silence, not start after it"
        }
    }
}
