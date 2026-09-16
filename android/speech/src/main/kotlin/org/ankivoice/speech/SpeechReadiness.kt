package org.ankivoice.speech

import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.SpeechInputFailure

/**
 * The speech half of #23's support matrix: can this device actually run a turn?
 *
 * #26 requires the pinned engine, service and locale to be resolved at runtime and bound
 * to that matrix, so the shell does not report a learner ready to study and then fail in
 * the middle of a card. It resolves the real objects rather than trusting an availability
 * Boolean — an engine that reports present and then will not initialize is a **failed
 * capability**, and is reported as one.
 *
 * Every call touches the platform and may block while the engine initializes, so it runs
 * on a worker thread, never on the main thread.
 */
class SpeechReadiness(private val platform: SpeechPlatform) {

    /** Null when a turn can run. Otherwise the first thing that would stop it. */
    fun check(language: String): Failure? {
        if (!platform.microphonePermissionGranted()) {
            return Failure(SpeechInputFailure.PERMISSION_DENIED, SpeechTransport.PERMISSION_MISSING)
        }
        when (val voice = platform.resolveVoice(language)) {
            is VoiceResolution.Resolved -> Unit
            is VoiceResolution.EngineUnavailable ->
                return Failure(
                    org.ankivoice.core.contracts.SpeechOutputFailure.ENGINE_UNAVAILABLE,
                    voice.detail,
                )
            is VoiceResolution.LanguageUnsupported ->
                return Failure(
                    org.ankivoice.core.contracts.SpeechOutputFailure.LANGUAGE_UNSUPPORTED,
                    voice.detail,
                )
        }
        return when (val recognizer = platform.resolveRecognizer(language)) {
            RecognizerResolution.Resolved -> null
            is RecognizerResolution.Unavailable ->
                Failure(SpeechInputFailure.RECOGNIZER_UNAVAILABLE, recognizer.detail)
        }
    }
}
