package org.ankivoice.speech

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.MonotonicClock
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.PlaybackResult
import org.ankivoice.core.contracts.SpeechInput
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.SpeechOutput
import org.ankivoice.core.contracts.SpeechOutputFailure
import org.ankivoice.core.contracts.SystemMonotonicClock
import org.ankivoice.core.contracts.Utterance

/**
 * AV-025's speech transport: the AV-042 route behind AV-007's `SpeechOutput`/`SpeechInput`.
 *
 * It owns platform ordering and nothing above it. Thinking time, the answer window, the
 * transcript and every retry decision belong to #13; session orchestration to #14. This
 * class receives an [Utterance] and never a card, so Extra cannot reach question audio
 * through it, and the Prompt-only rule stays where `core/contracts/Utterances.kt` defines it.
 *
 * Both contract calls are synchronous, so each blocks the calling worker thread until the
 * platform resolves or a deadline expires. Callbacks are filtered by generation, so a late
 * one from an abandoned turn is recorded and dropped rather than delivered.
 */
class SpeechTransport(
    private val platform: SpeechPlatform,
    private val timings: SpeechTimings = SpeechTimings(),
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val sleeper: Sleeper = Sleeper.Default,
) : SpeechOutput, SpeechInput {

    /** Blocking pauses the transport controls itself, made injectable so tests stay fast. */
    fun interface Sleeper {
        fun sleepMs(millis: Long)

        companion object {
            val Default = Sleeper { millis -> if (millis > 0) Thread.sleep(millis) }
        }
    }

    private enum class Phase { IDLE, PLAYBACK, CAPTURE, FINALIZING }

    private sealed interface Outcome {
        data object PlaybackDone : Outcome
        data class PlaybackError(val detail: String) : Outcome
        data class Final(val text: String, val confidence: Float?) : Outcome
        data class RecognizerError(val code: Int) : Outcome
        data class Cancelled(val detail: String) : Outcome
    }

    private val lock = Any()

    /** Bumped for every operation and every teardown, so tokens never alias. */
    private var generation = 0L
    private var phase = Phase.IDLE
    private var activeToken: OperationToken? = null
    private var outcomes: ArrayBlockingQueue<Outcome>? = null

    /** At-most-once delivery: the first outcome for a generation wins. */
    private var settled = false
    private var settleUntilMs = 0L
    private var captureStartedMs = 0L
    private var finalizationUntilMs = 0L
    private var stream: CaptureStream? = null

    /** Observability for #13 and the live harness. Never used to decide an outcome. */
    @Volatile
    var lastPartial: String? = null
        private set

    private val staleCallbacks = mutableListOf<String>()

    /** Callbacks rejected because their generation had already moved on. */
    fun staleCallbackLog(): List<String> = synchronized(lock) { staleCallbacks.toList() }

    // ---------------------------------------------------------------- SpeechOutput

    override fun speak(token: OperationToken, utterance: Utterance): PlaybackResult {
        val queue: ArrayBlockingQueue<Outcome>
        val current: Long
        synchronized(lock) {
            if (phase != Phase.IDLE) {
                return playbackFailure(token, SpeechOutputFailure.PLAYBACK_INTERRUPTED, BUSY)
            }
            queue = begin(token, Phase.PLAYBACK)
            current = generation
        }

        when (val voice = platform.resolveVoice(utterance.language)) {
            is VoiceResolution.Resolved -> Unit
            is VoiceResolution.EngineUnavailable -> {
                finishOperation(current)
                return playbackFailure(token, SpeechOutputFailure.ENGINE_UNAVAILABLE, voice.detail)
            }
            // A well-formed tag with no voice is surfaced here, as #26 requires. A malformed
            // tag is #11's card-eligibility rejection and never reaches this call.
            is VoiceResolution.LanguageUnsupported -> {
                finishOperation(current)
                return playbackFailure(token, SpeechOutputFailure.LANGUAGE_UNSUPPORTED, voice.detail)
            }
        }

        if (!platform.startPlayback(current, utterance.text, playbackListener)) {
            finishOperation(current)
            return playbackFailure(token, SpeechOutputFailure.ENGINE_UNAVAILABLE, ENQUEUE_FAILED)
        }

        val outcome = queue.poll(timings.playbackMs, TimeUnit.MILLISECONDS)
        platform.stopPlayback()
        return when (outcome) {
            Outcome.PlaybackDone -> {
                // Playback completed, so the settle interval starts now and capture stays
                // shut until an explicit Start answer arrives after it.
                synchronized(lock) {
                    if (generation == current) {
                        settleUntilMs = clock.nowMs() + timings.settleMs
                        close(current)
                    }
                }
                PlaybackResult.Completed(token)
            }
            is Outcome.PlaybackError -> {
                finishOperation(current)
                playbackFailure(token, SpeechOutputFailure.PLAYBACK_INTERRUPTED, outcome.detail)
            }
            is Outcome.Cancelled -> {
                finishOperation(current)
                playbackFailure(token, SpeechOutputFailure.PLAYBACK_INTERRUPTED, outcome.detail)
            }
            else -> {
                finishOperation(current)
                playbackFailure(token, SpeechOutputFailure.PLAYBACK_INTERRUPTED, PLAYBACK_DEADLINE)
            }
        }
    }

    // ----------------------------------------------------------------- SpeechInput

    /**
     * Opens capture for one attempt. Calling this **is** the explicit Start answer: #13
     * calls it when the learner asks to speak, never automatically, and this class never
     * re-arms after a result.
     */
    override fun listen(token: OperationToken, language: String): CaptureEvent {
        val queue: ArrayBlockingQueue<Outcome>
        val current: Long
        synchronized(lock) {
            when (phase) {
                // Capture must never open while the question is still playing.
                Phase.PLAYBACK ->
                    return captureFailure(token, SpeechInputFailure.RECOGNIZER_ERROR, DURING_PLAYBACK)
                Phase.CAPTURE, Phase.FINALIZING ->
                    return captureFailure(token, SpeechInputFailure.RECOGNIZER_ERROR, OVERLAPPING_CAPTURE)
                Phase.IDLE -> Unit
            }
            queue = begin(token, Phase.CAPTURE)
            current = generation
        }

        if (!platform.microphonePermissionGranted()) {
            finishOperation(current)
            return captureFailure(token, SpeechInputFailure.PERMISSION_DENIED, PERMISSION_MISSING)
        }
        when (val recognizer = platform.resolveRecognizer(language)) {
            RecognizerResolution.Resolved -> Unit
            is RecognizerResolution.Unavailable -> {
                finishOperation(current)
                return captureFailure(token, SpeechInputFailure.RECOGNIZER_UNAVAILABLE, recognizer.detail)
            }
        }

        // Honor the remaining settle even when the learner starts answering immediately.
        val remaining = synchronized(lock) { settleUntilMs - clock.nowMs() }
        if (remaining > 0) sleeper.sleepMs(remaining)

        val opened = platform.startCapture(current, language, recognitionListener)
        if (opened == null) {
            finishOperation(current)
            return captureFailure(token, SpeechInputFailure.RECOGNIZER_UNAVAILABLE, CAPTURE_UNAVAILABLE)
        }
        synchronized(lock) {
            if (generation != current) {
                // Cancelled while the microphone was opening.
                opened.stopMicrophone()
                opened.close()
                return captureFailure(token, SpeechInputFailure.RECOGNIZER_ERROR, CANCELLED)
            }
            stream = opened
            captureStartedMs = clock.nowMs()
        }
        startPump(current, opened)

        return awaitCapture(token, current, queue)
    }

    /**
     * Done: the learner finished speaking, or #13's answer window expired. Stops the
     * microphone, appends the pinned trailing silence and closes the pipe. It is not a
     * verdict about the answer, and it never re-arms capture.
     */
    fun finishAnswer(token: OperationToken) {
        synchronized(lock) {
            if (activeToken != token || phase != Phase.CAPTURE) return
            beginFinalization()
        }
    }

    private fun beginFinalization() {
        phase = Phase.FINALIZING
        finalizationUntilMs = clock.nowMs() + timings.finalizationMs
        // The pump sees the stopped microphone, writes the trailing silence and closes.
        stream?.stopMicrophone()
    }

    private fun awaitCapture(
        token: OperationToken,
        current: Long,
        queue: ArrayBlockingQueue<Outcome>,
    ): CaptureEvent {
        while (true) {
            val deadline = synchronized(lock) {
                if (generation != current) {
                    return captureFailure(token, SpeechInputFailure.RECOGNIZER_ERROR, CANCELLED)
                }
                when (phase) {
                    // The backstop mirrors #13's window; it stops the microphone exactly as
                    // Done does, so an in-flight final is still delivered.
                    Phase.CAPTURE -> captureStartedMs + timings.answerWindowMs
                    Phase.FINALIZING -> finalizationUntilMs
                    else -> return captureFailure(token, SpeechInputFailure.RECOGNIZER_ERROR, CANCELLED)
                }
            }
            val wait = deadline - clock.nowMs()
            val outcome = if (wait > 0) queue.poll(wait, TimeUnit.MILLISECONDS) else null
            if (outcome != null) return deliver(token, current, outcome)

            val expired = synchronized(lock) {
                if (generation != current) {
                    return captureFailure(token, SpeechInputFailure.RECOGNIZER_ERROR, CANCELLED)
                }
                when {
                    clock.nowMs() < deadline -> false
                    phase == Phase.CAPTURE -> { beginFinalization(); false }
                    else -> true
                }
            }
            if (expired) {
                finishOperation(current)
                // Finalization expiry is a timeout, never an answer and never a rating.
                return captureFailure(token, SpeechInputFailure.LISTEN_TIMEOUT, FINALIZATION_DEADLINE)
            }
        }
    }

    private fun deliver(token: OperationToken, current: Long, outcome: Outcome): CaptureEvent {
        finishOperation(current)
        return when (outcome) {
            is Outcome.Final ->
                if (outcome.text.isBlank()) {
                    captureFailure(token, SpeechInputFailure.NO_MATCH, EMPTY_RESULT)
                } else {
                    CaptureEvent.Transcript(token, outcome.text, confidence = classify(outcome.confidence))
                }
            is Outcome.RecognizerError -> captureFailure(token, classify(outcome.code), "code ${outcome.code}")
            is Outcome.Cancelled -> captureFailure(token, SpeechInputFailure.RECOGNIZER_ERROR, outcome.detail)
            else -> captureFailure(token, SpeechInputFailure.EARLY_CLOSURE, UNEXPECTED_OUTCOME)
        }
    }

    /**
     * Streams microphone frames into the recognizer pipe, then the pinned trailing silence.
     * The silence is generated padding that lets the recognizer settle on the final word;
     * it is never presented as captured audio.
     */
    private fun startPump(current: Long, open: CaptureStream) {
        val thread = Thread({
            val samples = ShortArray(SpeechPins.FRAME_SAMPLES)
            val bytes = ByteArray(SpeechPins.FRAME_SAMPLES * SpeechPins.BYTES_PER_SAMPLE)
            try {
                while (true) {
                    val read = open.readFrame(samples)
                    if (read <= 0) break
                    if (synchronized(lock) { generation != current }) break
                    for (i in 0 until read) {
                        val sample = samples[i].toInt()
                        bytes[i * 2] = sample.toByte()
                        bytes[i * 2 + 1] = (sample shr 8).toByte()
                    }
                    open.write(bytes, read * SpeechPins.BYTES_PER_SAMPLE)
                }
                if (synchronized(lock) { generation == current }) {
                    val silence = ByteArray(SpeechPins.FRAME_SAMPLES * SpeechPins.BYTES_PER_SAMPLE)
                    repeat((timings.trailingSilenceMs / SILENCE_FRAME_MS).toInt()) {
                        open.write(silence, silence.size)
                        sleeper.sleepMs(SILENCE_FRAME_MS)
                    }
                }
            } catch (e: RuntimeException) {
                offer(current, Outcome.RecognizerError(RecognizerErrors.AUDIO), "pump: ${e.message}")
            } finally {
                open.close()
            }
        }, "ankivoice-capture-pump")
        thread.isDaemon = true
        thread.start()
    }

    // --------------------------------------------------------------------- cancel

    /** Explicit Cancel from #13 or #14. Idempotent, and later callbacks are dropped. */
    override fun cancel(token: OperationToken) {
        val active = synchronized(lock) {
            if (activeToken != token) return
            generation
        }
        offer(active, Outcome.Cancelled(CANCELLED), null)
    }

    /** Foreground loss or shutdown: invalidate the turn and release every platform object. */
    fun releaseAll() {
        val active = synchronized(lock) { generation }
        offer(active, Outcome.Cancelled(RELEASED), null)
        finishOperation(active)
        platform.release()
    }

    // ------------------------------------------------------------------- internals

    private fun begin(token: OperationToken, next: Phase): ArrayBlockingQueue<Outcome> {
        generation += 1
        phase = next
        activeToken = token
        settled = false
        lastPartial = null
        val queue = ArrayBlockingQueue<Outcome>(1)
        outcomes = queue
        return queue
    }

    /** Tears the operation down and releases the platform objects it opened. */
    private fun finishOperation(current: Long) {
        val open = synchronized(lock) {
            if (generation != current) return
            val open = stream
            close(current)
            open
        }
        open?.stopMicrophone()
        open?.close()
        platform.stopRecognizer()
        platform.stopPlayback()
    }

    private fun close(current: Long) {
        if (generation != current) return
        // Bump first, so any callback already in flight is stale before cleanup runs.
        generation += 1
        phase = Phase.IDLE
        activeToken = null
        outcomes = null
        stream = null
    }

    /** Records the first outcome for [target] and ignores every later one. */
    private fun offer(target: Long, outcome: Outcome, note: String?) {
        synchronized(lock) {
            if (generation != target || settled) {
                staleCallbacks += note ?: "${outcome::class.simpleName} for generation $target"
                return
            }
            settled = true
            outcomes?.offer(outcome)
        }
    }

    private val playbackListener = object : PlaybackListener {
        override fun onPlaybackDone(generation: Long) = offer(generation, Outcome.PlaybackDone, null)

        override fun onPlaybackError(generation: Long, detail: String) =
            offer(generation, Outcome.PlaybackError(detail), null)
    }

    private val recognitionListener = object : RecognitionListener {
        /** Partial text is progress for #13 to display. It never settles the capture. */
        override fun onPartial(generation: Long, text: String) {
            synchronized(lock) {
                if (generation == this@SpeechTransport.generation) lastPartial = text
                else staleCallbacks += "partial for generation $generation"
            }
        }

        override fun onFinal(generation: Long, text: String, confidence: Float?) =
            offer(generation, Outcome.Final(text, confidence), null)

        override fun onRecognizerError(generation: Long, code: Int) =
            offer(generation, Outcome.RecognizerError(code), null)
    }

    private fun playbackFailure(token: OperationToken, mode: SpeechOutputFailure, detail: String) =
        PlaybackResult.Failed(token, Failure(mode, detail))

    private fun captureFailure(token: OperationToken, mode: SpeechInputFailure, detail: String) =
        CaptureEvent.Failed(token, Failure(mode, detail))

    companion object {
        private const val SILENCE_FRAME_MS = 20L

        // Several platform conditions share one contract failure mode, so the detail carries
        // the distinction #26 requires. :core's taxonomy is AV-007's and is not extended here.
        const val BUSY = "another speech operation is active"
        const val ENQUEUE_FAILED = "engine refused the utterance"
        const val PLAYBACK_DEADLINE = "playback did not complete"
        const val DURING_PLAYBACK = "capture requested during playback"
        const val OVERLAPPING_CAPTURE = "capture already active"
        const val PERMISSION_MISSING = "RECORD_AUDIO not granted"
        const val CAPTURE_UNAVAILABLE = "microphone or recognizer would not open"
        const val CANCELLED = "cancelled"
        const val RELEASED = "transport released"
        const val EMPTY_RESULT = "empty result"
        const val FINALIZATION_DEADLINE = "no final within the finalization deadline"
        const val UNEXPECTED_OUTCOME = "capture ended without a final"

        /**
         * Confidence is a policy classification. An absent score stays unknown: it is never
         * read as zero and never as certainty.
         */
        fun classify(confidence: Float?): Confidence = when {
            confidence == null -> Confidence.ABSENT
            confidence <= 0f -> Confidence.LOW
            else -> Confidence.SUFFICIENT
        }

        /** Maps a raw recognizer error onto AV-007's SpeechInput taxonomy. */
        fun classify(code: Int): SpeechInputFailure = when (code) {
            // Overloaded upstream: its cause stays unknown rather than becoming a wrong answer.
            RecognizerErrors.NO_MATCH -> SpeechInputFailure.NO_MATCH
            RecognizerErrors.SPEECH_TIMEOUT -> SpeechInputFailure.NO_SPEECH_DETECTED
            RecognizerErrors.NETWORK, RecognizerErrors.NETWORK_TIMEOUT,
            RecognizerErrors.SERVER, RecognizerErrors.SERVER_DISCONNECTED,
            -> SpeechInputFailure.NETWORK_UNAVAILABLE
            RecognizerErrors.INSUFFICIENT_PERMISSIONS -> SpeechInputFailure.PERMISSION_DENIED
            RecognizerErrors.TOO_MANY_REQUESTS -> SpeechInputFailure.QUOTA_EXHAUSTED
            RecognizerErrors.LANGUAGE_NOT_SUPPORTED, RecognizerErrors.LANGUAGE_UNAVAILABLE,
            RecognizerErrors.CANNOT_CHECK_SUPPORT, RecognizerErrors.CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
            -> SpeechInputFailure.RECOGNIZER_UNAVAILABLE
            RecognizerErrors.AUDIO -> SpeechInputFailure.EARLY_CLOSURE
            else -> SpeechInputFailure.RECOGNIZER_ERROR
        }
    }
}
