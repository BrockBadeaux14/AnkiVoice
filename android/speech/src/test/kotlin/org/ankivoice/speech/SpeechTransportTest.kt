package org.ankivoice.speech

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.CardIdentity
import org.ankivoice.core.contracts.CardState
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.PlaybackResult
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.SpeechOutputFailure
import org.ankivoice.core.contracts.Utterance
import org.ankivoice.core.contracts.UtterancePurpose
import org.ankivoice.core.contracts.VOICEQA_MODEL
import org.ankivoice.core.contracts.VoiceQAFields
import org.ankivoice.core.contracts.questionUtterance

/**
 * AV-025's transport rules against a fake platform: ordering, serialization, cancellation,
 * deadlines, stale callbacks and the failure taxonomy. Recognition accuracy is not
 * simulated here — that is the live check on the pinned AVD.
 */
class SpeechTransportTest {

    private val platform = FakeSpeechPlatform()

    /** Short deadlines so a timeout test costs milliseconds, not five seconds. */
    private val timings = SpeechTimings(
        settleMs = 40,
        trailingSilenceMs = 40,
        finalizationMs = 300,
        answerWindowMs = 400,
        playbackMs = 300,
    )
    private val slept = mutableListOf<Long>()
    private val transport = SpeechTransport(
        platform,
        timings,
        sleeper = { millis -> slept += millis; if (millis > 0) Thread.sleep(minOf(millis, 50)) },
    )
    private val worker = Executors.newSingleThreadExecutor()
    private val token = OperationToken("s1", turn = 1, sequence = 1)

    @AfterEach fun tearDown() = worker.shutdownNow().let { }

    private fun listenAsync(at: OperationToken = token): Future<CaptureEvent> =
        worker.submit<CaptureEvent> { transport.listen(at, "en-US") }

    private fun <T> Future<T>.value(): T = get(5, TimeUnit.SECONDS)

    private fun speakQuestion(): PlaybackResult =
        transport.speak(token, Utterance(UtterancePurpose.QUESTION, "What is two plus three?", "en-US"))

    private fun awaitCaptureOpen() =
        assertTrue(platform.captureOpened.await(5, TimeUnit.SECONDS), "capture never opened")

    /** The transport works on its own threads, so a test waits for state instead of guessing. */
    private fun await(what: String, condition: () -> Boolean) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue(System.nanoTime() < limit, "timed out waiting for $what")
            Thread.sleep(1)
        }
    }

    // ------------------------------------------------------------ playback ordering

    @Test fun `question audio carries the prompt only`() {
        // The Prompt-only rule stays in :core; the transport is handed the utterance and
        // never a card, so Extra has no path into question audio through this module.
        val card = ScheduledCard(
            CardIdentity(cardId = 11, noteId = 12, deckId = 13, ordinal = 0, model = VOICEQA_MODEL),
            CardState(reps = 1, cardType = 2, queue = 2, due = 5, intervalDays = 3),
            VoiceQAFields(
                prompt = "What is two plus three?",
                referenceAnswer = "Five",
                extra = "Mnemonic the learner must not hear in the question",
            ),
            permittedRatings = listOf(1, 2, 3, 4),
        )
        transport.speak(token, questionUtterance(card, "en-US"))

        assertEquals(listOf(card.fields.prompt), platform.spokenText.toList())
        assertFalse(platform.spokenText.any { it.contains(card.fields.extra) })
    }

    @Test fun `completed playback opens the settle interval and returns the token`() {
        val result = speakQuestion()

        assertEquals(PlaybackResult.Completed(token), result)
    }

    @Test fun `a capture requested during playback is rejected and never opens the microphone`() {
        platform.playback = FakeSpeechPlatform.Playback.Silent
        val speaking = worker.submit<PlaybackResult> { speakQuestion() }
        // Only once playback has actually started is the transport in PLAYBACK, where it
        // stays until the deadline expires.
        await("playback to start") { platform.playbackStarts.get() > 0 }
        val event = transport.listen(token, "en-US")

        assertEquals(SpeechInputFailure.RECOGNIZER_ERROR, (event as CaptureEvent.Failed).failure.mode)
        assertEquals(SpeechTransport.DURING_PLAYBACK, event.failure.detail)
        assertEquals(0, platform.captureStarts.get())
        speaking.value()
    }

    @Test fun `capture waits out the remaining settle after playback`() {
        speakQuestion()
        platform.recognition = FakeSpeechPlatform.Recognition.Final("five")
        val capture = listenAsync()
        awaitCaptureOpen()
        transport.finishAnswer(token)
        capture.value()

        assertTrue(slept.any { it > 0 }, "the settle interval was not honored")
    }

    @Test fun `a failed playback never opens capture and never produces a transcript`() {
        platform.playback = FakeSpeechPlatform.Playback.Error("engine died")
        val result = speakQuestion()

        assertEquals(SpeechOutputFailure.PLAYBACK_INTERRUPTED, (result as PlaybackResult.Failed).failure.mode)
        assertEquals(0, platform.captureStarts.get())
    }

    @Test fun `an unavailable voice for a well formed tag is a voice resolution failure`() {
        platform.voice = VoiceResolution.LanguageUnsupported("no local voice for cy-GB")
        val result = transport.speak(token, Utterance(UtterancePurpose.QUESTION, "Beth yw dau?", "cy-GB"))

        assertEquals(SpeechOutputFailure.LANGUAGE_UNSUPPORTED, (result as PlaybackResult.Failed).failure.mode)
        assertEquals(0, platform.playbackStarts.get())
    }

    @Test fun `an engine that will not initialize is a failed capability not an availability boolean`() {
        platform.voice = VoiceResolution.EngineUnavailable("engine failed to initialize")
        val result = speakQuestion()

        assertEquals(SpeechOutputFailure.ENGINE_UNAVAILABLE, (result as PlaybackResult.Failed).failure.mode)
    }

    @Test fun `an engine that refuses the utterance is reported, not retried`() {
        platform.playbackEnqueues = false
        val result = speakQuestion()

        assertEquals(SpeechOutputFailure.ENGINE_UNAVAILABLE, (result as PlaybackResult.Failed).failure.mode)
        assertEquals(SpeechTransport.ENQUEUE_FAILED, result.failure.detail)
        assertEquals(1, platform.playbackStarts.get())
    }

    @Test fun `playback that never completes expires instead of hanging the turn`() {
        platform.playback = FakeSpeechPlatform.Playback.Silent
        val result = speakQuestion()

        assertEquals(SpeechOutputFailure.PLAYBACK_INTERRUPTED, (result as PlaybackResult.Failed).failure.mode)
        assertEquals(SpeechTransport.PLAYBACK_DEADLINE, result.failure.detail)
    }

    // --------------------------------------------------------------- capture ordering

    @Test fun `done stops the microphone, appends trailing silence, then closes the pipe`() {
        val capture = listenAsync()
        awaitCaptureOpen()
        val stream = platform.lastStream ?: error("capture never opened")
        // Done must follow the microphone frames, or this asserts against a half-read stream.
        await("the microphone frames") { stream.microphoneFramesWritten() == 3 }
        transport.finishAnswer(token)
        val event = capture.value()

        assertTrue(event is CaptureEvent.Transcript)
        assertTrue(stream.microphoneStopped, "the microphone was not stopped")
        assertEquals(3, stream.microphoneFramesWritten())
        // 40 ms of trailing silence at one 20 ms frame each.
        assertEquals(2, stream.trailingSilenceFrames())
        assertTrue(stream.closeCount >= 1, "the pipe was never closed")
    }

    @Test fun `a final transcript is tagged with the caller token and unknown confidence`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Final("green blue red")
        val capture = listenAsync()
        awaitCaptureOpen()
        transport.finishAnswer(token)
        val event = capture.value() as CaptureEvent.Transcript

        assertEquals(token, event.token)
        assertEquals("green blue red", event.text)
        // No score from the engine means unknown, not zero and not certainty.
        assertEquals(Confidence.ABSENT, event.confidence)
    }

    @Test fun `an empty result is a failure, never an empty transcript`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Final("   ")
        val capture = listenAsync()
        awaitCaptureOpen()
        transport.finishAnswer(token)
        val event = capture.value() as CaptureEvent.Failed

        assertEquals(SpeechInputFailure.NO_MATCH, event.failure.mode)
        assertEquals(SpeechTransport.EMPTY_RESULT, event.failure.detail)
    }

    @Test fun `overlapping capture is rejected without disturbing the active one`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Final("five")
        val capture = listenAsync()
        awaitCaptureOpen()

        val second = transport.listen(OperationToken("s1", 1, 2), "en-US") as CaptureEvent.Failed
        assertEquals(SpeechInputFailure.RECOGNIZER_ERROR, second.failure.mode)
        assertEquals(SpeechTransport.OVERLAPPING_CAPTURE, second.failure.detail)

        transport.finishAnswer(token)
        assertTrue(capture.value() is CaptureEvent.Transcript)
        assertEquals(1, platform.captureStarts.get())
    }

    @Test fun `playback is refused while capture is active`() {
        val capture = listenAsync()
        awaitCaptureOpen()
        val result = speakQuestion()

        assertEquals(SpeechOutputFailure.PLAYBACK_INTERRUPTED, (result as PlaybackResult.Failed).failure.mode)
        assertEquals(SpeechTransport.BUSY, result.failure.detail)
        transport.finishAnswer(token)
        capture.value()
    }

    @Test fun `one listen is one attempt with no automatic re-arm`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Error(RecognizerErrors.NO_MATCH)
        val capture = listenAsync()
        awaitCaptureOpen()
        transport.finishAnswer(token)
        capture.value()

        assertEquals(1, platform.captureStarts.get())
    }

    @Test fun `a missing microphone permission fails before the microphone opens`() {
        platform.permissionGranted = false
        val event = transport.listen(token, "en-US") as CaptureEvent.Failed

        assertEquals(SpeechInputFailure.PERMISSION_DENIED, event.failure.mode)
        assertEquals(0, platform.captureStarts.get())
    }

    @Test fun `an unresolvable recognizer is unavailable, never a substituted provider`() {
        platform.recognizer = RecognizerResolution.Unavailable("no recognition service")
        val event = transport.listen(token, "en-US") as CaptureEvent.Failed

        assertEquals(SpeechInputFailure.RECOGNIZER_UNAVAILABLE, event.failure.mode)
        assertEquals(0, platform.captureStarts.get())
    }

    @Test fun `a capture that will not open is reported as unavailable`() {
        platform.captureOpens = false
        val event = transport.listen(token, "en-US") as CaptureEvent.Failed

        assertEquals(SpeechInputFailure.RECOGNIZER_UNAVAILABLE, event.failure.mode)
        assertEquals(SpeechTransport.CAPTURE_UNAVAILABLE, event.failure.detail)
    }

    // -------------------------------------------------------------------- deadlines

    @Test fun `a recognizer that never finalizes expires as a timeout, not an answer`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Silent
        val capture = listenAsync()
        awaitCaptureOpen()
        transport.finishAnswer(token)
        val event = capture.value() as CaptureEvent.Failed

        assertEquals(SpeechInputFailure.LISTEN_TIMEOUT, event.failure.mode)
        assertEquals(SpeechTransport.FINALIZATION_DEADLINE, event.failure.detail)
    }

    @Test fun `the capture backstop stops the microphone and still delivers the final`() {
        // #13 owns the answer window; if its stop never arrives the transport behaves as
        // Done rather than running the microphone forever.
        platform.recognition = FakeSpeechPlatform.Recognition.Final("five")
        val event = listenAsync().value()

        assertTrue(event is CaptureEvent.Transcript, "backstop discarded a valid final")
        assertTrue((platform.lastStream ?: error("capture never opened")).microphoneStopped)
    }

    // ---------------------------------------------------------------- cancellation

    @Test fun `cancel during capture releases the stream and rejects the turn`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Silent
        val capture = listenAsync()
        awaitCaptureOpen()
        transport.cancel(token)
        val event = capture.value() as CaptureEvent.Failed

        assertEquals(SpeechInputFailure.RECOGNIZER_ERROR, event.failure.mode)
        assertEquals(SpeechTransport.CANCELLED, event.failure.detail)
        assertTrue((platform.lastStream ?: error("capture never opened")).microphoneStopped)
    }

    @Test fun `cancel during playback interrupts it and produces no transcript`() {
        platform.playback = FakeSpeechPlatform.Playback.Silent
        val speaking = worker.submit<PlaybackResult> { speakQuestion() }
        while (platform.playbackStarts.get() == 0) Thread.sleep(1)
        transport.cancel(token)
        val result = speaking.value() as PlaybackResult.Failed

        assertEquals(SpeechOutputFailure.PLAYBACK_INTERRUPTED, result.failure.mode)
        assertEquals(SpeechTransport.CANCELLED, result.failure.detail)
    }

    @Test fun `cancel is idempotent and ignores an unknown token`() {
        transport.cancel(OperationToken("other", 9, 9))
        transport.cancel(token)
        val result = speakQuestion()

        assertEquals(PlaybackResult.Completed(token), result)
    }

    @Test fun `release invalidates the turn and releases every platform object`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Silent
        val capture = listenAsync()
        awaitCaptureOpen()
        transport.releaseAll()
        val event = capture.value() as CaptureEvent.Failed

        assertEquals(SpeechInputFailure.RECOGNIZER_ERROR, event.failure.mode)
        assertEquals(1, platform.releases.get())
    }

    // ------------------------------------------------------------- stale callbacks

    @Test fun `a callback for a finished generation is dropped and recorded`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Final("five")
        val capture = listenAsync()
        awaitCaptureOpen()
        transport.finishAnswer(token)
        val delivered = capture.value() as CaptureEvent.Transcript

        // The same turn answers again after the transport moved on.
        platform.replayFinal(1L, "stale answer")

        assertEquals("five", delivered.text)
        assertTrue(transport.staleCallbackLog().isNotEmpty(), "the stale callback was not recorded")
    }

    @Test fun `each capture delivers exactly one event`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Final("five")
        val capture = listenAsync()
        awaitCaptureOpen()
        transport.finishAnswer(token)
        capture.value()
        val before = transport.staleCallbackLog().size

        platform.replayFinal(1L, "duplicate")
        platform.replayFinal(1L, "duplicate again")

        assertEquals(before + 2, transport.staleCallbackLog().size)
    }

    @Test fun `partial text is exposed for display and never settles the capture`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Final("green blue red")
        val capture = listenAsync()
        awaitCaptureOpen()
        platform.emitPartial("green blue")
        assertFalse(capture.isDone, "a partial result ended the capture")

        transport.finishAnswer(token)
        val event = capture.value() as CaptureEvent.Transcript
        assertEquals("green blue red", event.text)
        assertEquals("green blue", transport.lastPartial)
    }

    // ------------------------------------------------------------- classification

    @ParameterizedTest
    @CsvSource(
        "7, NO_MATCH",
        "6, NO_SPEECH_DETECTED",
        "2, NETWORK_UNAVAILABLE",
        "1, NETWORK_UNAVAILABLE",
        "4, NETWORK_UNAVAILABLE",
        "11, NETWORK_UNAVAILABLE",
        "9, PERMISSION_DENIED",
        "10, QUOTA_EXHAUSTED",
        "12, RECOGNIZER_UNAVAILABLE",
        "13, RECOGNIZER_UNAVAILABLE",
        "3, EARLY_CLOSURE",
        "5, RECOGNIZER_ERROR",
        "8, RECOGNIZER_ERROR",
    )
    fun `each recognizer error maps to its own contract failure`(code: Int, expected: String) {
        assertEquals(SpeechInputFailure.valueOf(expected), SpeechTransport.classify(code))
    }

    @Test fun `a classified recognizer error reaches the caller as a failure, not a transcript`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Error(RecognizerErrors.NETWORK)
        val capture = listenAsync()
        awaitCaptureOpen()
        transport.finishAnswer(token)
        val event = capture.value() as CaptureEvent.Failed

        assertEquals(SpeechInputFailure.NETWORK_UNAVAILABLE, event.failure.mode)
        assertEquals(token, event.token)
    }

    @Test fun `a lost capture device is hardware loss, not the learner staying silent`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Silent
        val capture = listenAsync()
        awaitCaptureOpen()
        platform.loseCapture("routed device lost")
        val event = capture.value() as CaptureEvent.Failed

        // Not NO_SPEECH_DETECTED and not NO_MATCH: the microphone left, the learner did not.
        assertEquals(SpeechInputFailure.EARLY_CLOSURE, event.failure.mode)
        assertTrue(event.failure.detail.startsWith(SpeechTransport.DEVICE_LOST))
        assertTrue(event.failure.detail.contains("routed device lost"))
    }

    @Test fun `a silenced capture client is reported rather than read as silence`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Silent
        val capture = listenAsync()
        awaitCaptureOpen()
        platform.loseCapture("client silenced")
        val event = capture.value() as CaptureEvent.Failed

        assertEquals(SpeechInputFailure.EARLY_CLOSURE, event.failure.mode)
        assertTrue(event.failure.detail.contains("client silenced"))
    }

    @Test fun `done that arrives before the stream is recorded still stops the microphone`() {
        // The window between startCapture returning and the transport storing the stream.
        // A dropped stop here would leave the microphone running until the deadline.
        platform.recognition = FakeSpeechPlatform.Recognition.Final("five")
        val capture = listenAsync()
        awaitCaptureOpen()
        transport.finishAnswer(token)
        val event = capture.value()

        assertTrue(event is CaptureEvent.Transcript, "Done was lost: got $event")
        assertTrue((platform.lastStream ?: error("capture never opened")).microphoneStopped)
    }

    @Test fun `a capture loss after the turn ended is dropped as stale`() {
        platform.recognition = FakeSpeechPlatform.Recognition.Final("five")
        val capture = listenAsync()
        awaitCaptureOpen()
        transport.finishAnswer(token)
        val delivered = capture.value() as CaptureEvent.Transcript
        val before = transport.staleCallbackLog().size

        platform.loseCapture("routed device lost")

        assertEquals("five", delivered.text)
        assertEquals(before + 1, transport.staleCallbackLog().size)
    }

    @Test fun `absent confidence is unknown while an explicit zero is low`() {
        assertEquals(Confidence.ABSENT, SpeechTransport.classify(null))
        assertEquals(Confidence.LOW, SpeechTransport.classify(0f))
        assertEquals(Confidence.SUFFICIENT, SpeechTransport.classify(0.8f))
    }
}
