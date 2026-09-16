package org.ankivoice.speech

/**
 * The platform seam. `AndroidSpeechPlatform` is the only implementation that touches
 * TextToSpeech, SpeechRecognizer and AudioRecord, so [SpeechTransport]'s ordering,
 * serialization and failure rules stay verifiable on the JVM with no device.
 *
 * Every call is made from the session's worker thread. Callbacks arrive on whatever
 * thread the platform chooses; the transport tags and filters them by generation.
 */
interface SpeechPlatform {
    fun microphonePermissionGranted(): Boolean

    /** Resolve the pinned engine and a local voice for [language]. */
    fun resolveVoice(language: String): VoiceResolution

    /** Resolve the pinned recognition service for [language]. */
    fun resolveRecognizer(language: String): RecognizerResolution

    /**
     * Begin playback. Returns false when the utterance could not even be enqueued.
     * Completion or error arrives through [listener].
     */
    fun startPlayback(generation: Long, text: String, listener: PlaybackListener): Boolean

    /** Stop playback now. Idempotent; a later completion for a stopped generation is ignored. */
    fun stopPlayback()

    /**
     * Open the microphone and hand the recognizer the read end of a fresh pipe. Returns
     * the write end, or null when capture could not start.
     */
    fun startCapture(generation: Long, language: String, listener: RecognitionListener): CaptureStream?

    /** Cancel and release the recognizer. Idempotent. */
    fun stopRecognizer()

    /** Release every retained object. Called on explicit cleanup and on foreground loss. */
    fun release()
}

sealed interface VoiceResolution {
    /** The pinned engine and a local voice are both present. */
    data class Resolved(val voiceName: String) : VoiceResolution

    /** The engine is missing, disabled, or failed to initialize. */
    data class EngineUnavailable(val detail: String) : VoiceResolution

    /** A well-formed tag the engine has no voice for. Malformed tags belong to #11. */
    data class LanguageUnsupported(val detail: String) : VoiceResolution
}

sealed interface RecognizerResolution {
    data object Resolved : RecognizerResolution

    data class Unavailable(val detail: String) : RecognizerResolution
}

/**
 * One open capture. The transport pumps microphone frames through [write], appends the
 * pinned trailing silence, then [close]s to end the segmented session.
 */
interface CaptureStream {
    /** Block for the next microphone frame. Returns the sample count, or -1 at end of input. */
    fun readFrame(samples: ShortArray): Int

    fun write(bytes: ByteArray, length: Int)

    /** Stop the microphone. Further [readFrame] calls return -1. Idempotent. */
    fun stopMicrophone()

    /** Close the write end, ending the recognizer's segmented session. Idempotent. */
    fun close()
}

interface PlaybackListener {
    fun onPlaybackDone(generation: Long)

    fun onPlaybackError(generation: Long, detail: String)
}

/**
 * The recognizer callbacks the transport acts on. Partial results are reported so #13 can
 * show progress; this module never turns one into an answer.
 */
interface RecognitionListener {
    fun onPartial(generation: Long, text: String)

    fun onFinal(generation: Long, text: String, confidence: Float?)

    /** [code] is the raw `SpeechRecognizer.ERROR_*` value, classified by the transport. */
    fun onRecognizerError(generation: Long, code: Int)
}

/**
 * The raw `SpeechRecognizer.ERROR_*` constants, restated so [SpeechTransport] can classify
 * them without an Android dependency. Names and values match android.speech.SpeechRecognizer.
 */
object RecognizerErrors {
    const val NETWORK_TIMEOUT = 1
    const val NETWORK = 2
    const val AUDIO = 3
    const val SERVER = 4
    const val CLIENT = 5
    const val SPEECH_TIMEOUT = 6
    const val NO_MATCH = 7
    const val RECOGNIZER_BUSY = 8
    const val INSUFFICIENT_PERMISSIONS = 9
    const val TOO_MANY_REQUESTS = 10
    const val SERVER_DISCONNECTED = 11
    const val LANGUAGE_NOT_SUPPORTED = 12
    const val LANGUAGE_UNAVAILABLE = 13
    const val CANNOT_CHECK_SUPPORT = 14
    const val CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15
}
