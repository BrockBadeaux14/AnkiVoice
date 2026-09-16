package org.ankivoice.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.SpeechOutputFailure

/**
 * The speech half of #23's support matrix. A device is ready only when the real objects
 * resolve, so the shell cannot report a learner ready and then fail mid-card.
 */
class SpeechReadinessTest {

    private val platform = FakeSpeechPlatform()
    private val readiness = SpeechReadiness(platform)

    @Test fun `a resolvable engine, voice and recognizer is ready`() {
        assertNull(readiness.check("en-US"))
    }

    @Test fun `a missing microphone permission is not ready`() {
        platform.permissionGranted = false

        assertEquals(SpeechInputFailure.PERMISSION_DENIED, readiness.check("en-US")?.mode)
    }

    @Test fun `an engine that reports present but will not initialize is a failed capability`() {
        platform.voice = VoiceResolution.EngineUnavailable("engine failed to initialize")

        val failure = readiness.check("en-US")
        assertEquals(SpeechOutputFailure.ENGINE_UNAVAILABLE, failure?.mode)
        // Actionable, not a bare Boolean.
        assertEquals("engine failed to initialize", failure?.detail)
    }

    @Test fun `a locale with no local voice is not ready`() {
        platform.voice = VoiceResolution.LanguageUnsupported("no local voice for cy-GB")

        assertEquals(SpeechOutputFailure.LANGUAGE_UNSUPPORTED, readiness.check("cy-GB")?.mode)
    }

    @Test fun `an unresolvable recognition service is not ready`() {
        platform.recognizer = RecognizerResolution.Unavailable("no recognition service")

        assertEquals(SpeechInputFailure.RECOGNIZER_UNAVAILABLE, readiness.check("en-US")?.mode)
    }

    @Test fun `the permission is checked before the engine is initialized`() {
        platform.permissionGranted = false
        platform.voice = VoiceResolution.EngineUnavailable("should not be reached")

        // The cheapest and most actionable failure is the one the learner sees.
        assertEquals(SpeechInputFailure.PERMISSION_DENIED, readiness.check("en-US")?.mode)
    }
}
