package org.ankivoice.speech

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.ankivoice.core.answer.CaptureStop
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
 *
 * **AV-050 (#81), September 17, 2026.** A capture now ends itself when the learner stops
 * speaking. There is still exactly one way to stop a microphone — [beginFinalization], the
 * one Done calls — and two ways to decide that it is time: the engine's own endpoint, held
 * for [SpeechTimings.endpointHoldMs] in case the learner was only pausing, and, when the
 * engine reports no endpoint at all, trailing silence measured over the PCM frames the pump
 * already reads. Neither may run before speech has begun or before
 * [SpeechTimings.minCaptureMs], and both lose to Done and Cancel, which take effect at once.
 * [lastCaptureStop] says which of them ended the last capture, so an endpoint is never
 * recorded as a gesture the learner made.
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

    /** What [openCapture] found: a live stream, a refusal, or a platform that never answered. */
    private sealed interface OpenResult {
        data class Opened(val stream: CaptureStream) : OpenResult

        /** The platform declined the open and said so. */
        data object Refused : OpenResult

        /** The open did not return within its deadline. */
        data object TimedOut : OpenResult
    }

    /**
     * Ownership of an open that may complete after its deadline. Exactly one side keeps the
     * stream, and the side that loses the race closes it, so a microphone that opens late is
     * still released.
     */
    private class CaptureHandoff {
        private val lock = Any()
        private val opened = ArrayBlockingQueue<OpenResult>(1)
        private var abandoned = false

        /** Called on the opener thread, with whatever the platform produced. */
        fun deliver(stream: CaptureStream?) {
            val late = synchronized(lock) {
                if (abandoned) {
                    true
                } else {
                    opened.offer(stream?.let { OpenResult.Opened(it) } ?: OpenResult.Refused)
                    false
                }
            }
            if (late) release(stream)
        }

        /** Called on the capturing thread. After it times out, nothing later is kept. */
        fun await(millis: Long): OpenResult {
            opened.poll(millis, TimeUnit.MILLISECONDS)?.let { return it }
            val late = synchronized(lock) {
                abandoned = true
                opened.poll()
            }
            if (late is OpenResult.Opened) release(late.stream)
            return OpenResult.TimedOut
        }

        private fun release(stream: CaptureStream?) {
            stream?.stopMicrophone()
            stream?.close()
        }
    }

    private sealed interface Outcome {
        data object PlaybackDone : Outcome
        data class PlaybackError(val detail: String) : Outcome
        data class Final(val text: String, val confidence: Float?) : Outcome
        data class RecognizerError(val code: Int) : Outcome
        data class CaptureLost(val detail: String) : Outcome
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

    /** AV-050: when the learner was first heard in this capture, or 0 when nobody has been. */
    private var speechStartedMs = 0L

    /** AV-050: when the engine last said speech ended, or 0 when it has not, or took it back. */
    private var endpointAtMs = 0L

    /**
     * AV-050: how the last capture's microphone was stopped.
     *
     * Observability for #13, which turns it into [CaptureStop] on the answer turn so the
     * journal can tell an endpoint from a Done. Null before the first capture, and for one
     * that never opened.
     */
    @Volatile
    var lastCaptureStop: CaptureStop? = null
        private set

    /**
     * AV-050: how long after the microphone opened the learner was first heard, or null
     * when they were not heard at all.
     *
     * #13 needs the offset rather than an instant, because its pre-roll is measured on the
     * session's clock and this is measured on the transport's.
     */
    @Volatile
    var lastSpeechOnsetMs: Long? = null
        private set

    /** Observability for #13 and the live harness. Never used to decide an outcome. */
    @Volatile
    var lastPartial: String? = null
        private set

    /**
     * The raw score behind the last delivered transcript's classification — the minimum
     * over the segments that contributed text, or null when any of them came without one.
     * Observability for the live harness; the classification is what #13 receives.
     */
    @Volatile
    var lastConfidence: Float? = null
        private set

    /** The segments of the active capture: text and score, in delivery order. */
    private val segments = mutableListOf<Pair<String, Float?>>()

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
     * Opens capture for one attempt.
     *
     * **Amended by AV-050 (#81), September 17, 2026.** The rule used to be "capture opens
     * only on an explicit Start answer": #13 called this when the learner asked to speak,
     * never automatically. It is now **the microphone opens itself exactly once per
     * attempt, after that attempt's prompt playback settles** — #13 makes that single call
     * when the settle is over, and the learner may still bring it forward by tapping Start
     * answer. What did not change: one open per attempt, no re-arm after a result, and the
     * settle interval [speak] left behind is honoured whichever way the open arrives.
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

        val opened = when (val open = openCapture(current, language)) {
            is OpenResult.Opened -> open.stream
            OpenResult.Refused -> {
                finishOperation(current)
                return captureFailure(token, SpeechInputFailure.RECOGNIZER_UNAVAILABLE, CAPTURE_UNAVAILABLE)
            }
            OpenResult.TimedOut -> {
                finishOperation(current)
                return captureFailure(token, SpeechInputFailure.RECOGNIZER_UNAVAILABLE, CAPTURE_OPEN_DEADLINE)
            }
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
            // Done can arrive in the window between the microphone opening and the transport
            // recording it. Without this the stop would be dropped and the turn would die on
            // the finalization deadline with the microphone still running.
            if (phase == Phase.FINALIZING) opened.stopMicrophone()
        }
        startPump(current, opened)

        return awaitCapture(token, current, queue)
    }

    /**
     * Opens capture with a deadline of its own.
     *
     * Opening the microphone is a call into the platform's audio server, and a device whose
     * audio input has wedged never returns from it — the AV-017 runbook records exactly that
     * on the pinned AVD. Every other stage of a turn is bounded; without this one a wedged
     * open holds this thread, and with it #13's turn and #14's session, for good, while the
     * surface still reports a live microphone. A stream that arrives after the deadline is
     * released rather than used, so an abandoned attempt never leaves the microphone open.
     */
    private fun openCapture(current: Long, language: String): OpenResult {
        val handoff = CaptureHandoff()
        val opener = Thread({
            val opened = try {
                platform.startCapture(current, language, recognitionListener)
            } catch (_: RuntimeException) {
                null
            }
            handoff.deliver(opened)
        }, "ankivoice-capture-open")
        opener.isDaemon = true
        opener.start()
        return handoff.await(timings.captureOpenMs)
    }

    /**
     * Done: the learner finished speaking, or #13's answer window expired. Stops the
     * microphone, appends the pinned trailing silence and closes the pipe. It is not a
     * verdict about the answer, and it never re-arms capture.
     */
    override fun finishAnswer(token: OperationToken) {
        synchronized(lock) {
            if (activeToken != token || phase != Phase.CAPTURE) return
            beginFinalization(CaptureStop.DONE)
        }
    }

    /**
     * The one way a microphone stops. Done, the backstop and both of AV-050's endpoint
     * routes all arrive here, so there is one finalization deadline and one place that
     * closes the pipe. [stop] is recorded rather than acted on: nothing downstream behaves
     * differently, and the record is what keeps an endpoint out of the learner's gestures.
     *
     * Callers hold [lock].
     */
    private fun beginFinalization(stop: CaptureStop) {
        phase = Phase.FINALIZING
        lastCaptureStop = stop
        finalizationUntilMs = clock.nowMs() + timings.finalizationMs
        // The pump sees the stopped microphone, writes the trailing silence and closes.
        stream?.stopMicrophone()
    }

    /**
     * AV-050: the learner was heard. Callers hold [lock].
     *
     * Recorded once per capture, because the answer window runs from the first onset and not
     * from every pause inside it. It records **only** the onset: taking back an endpoint the
     * engine already offered is a separate decision, made where the signal is strong enough
     * to make it, so the fallback detector's crude amplitude test can never overrule the
     * engine's own opinion about where speech ended.
     */
    private fun speechBegan(now: Long) {
        if (speechStartedMs != 0L) return
        speechStartedMs = now
        lastSpeechOnsetMs = (now - captureStartedMs).coerceAtLeast(0)
    }

    /**
     * AV-050: when, at the earliest, this capture may end itself, or null when it may not.
     *
     * Null until the learner has been heard, so a learner who is still remembering gets the
     * pre-roll and the window rather than an instant timeout, and null when the engine has
     * offered no endpoint — the pump's own trailing silence is the route then.
     * [SpeechTimings.minCaptureMs] is a floor under both, so a false start is never taken
     * for a whole answer. Callers hold [lock].
     */
    private fun endpointDeadlineMs(): Long? {
        if (speechStartedMs == 0L || endpointAtMs == 0L) return null
        return maxOf(endpointAtMs + timings.endpointHoldMs, captureStartedMs + timings.minCaptureMs)
    }

    private fun awaitCapture(
        token: OperationToken,
        current: Long,
        queue: ArrayBlockingQueue<Outcome>,
    ): CaptureEvent {
        while (true) {
            // AV-050: an endpoint the engine offered is a shorter deadline on the same wait,
            // not a second timer. The loop re-reads it every pass, so speech arriving inside
            // the hold — which clears the endpoint — simply restores the backstop.
            val backstop = synchronized(lock) {
                if (generation != current) {
                    return captureFailure(token, SpeechInputFailure.RECOGNIZER_ERROR, CANCELLED)
                }
                when (phase) {
                    // The backstop mirrors #13's pre-roll and window together; it stops the
                    // microphone exactly as Done does, so an in-flight final is still delivered.
                    Phase.CAPTURE -> captureStartedMs + timings.answerWindowMs
                    Phase.FINALIZING -> finalizationUntilMs
                    else -> return captureFailure(token, SpeechInputFailure.RECOGNIZER_ERROR, CANCELLED)
                }
            }
            val endpoint = synchronized(lock) { if (phase == Phase.CAPTURE) endpointDeadlineMs() else null }
            val deadline = endpoint?.let { minOf(it, backstop) } ?: backstop
            val wait = deadline - clock.nowMs()
            val outcome = if (wait > 0) queue.poll(wait, TimeUnit.MILLISECONDS) else null
            if (outcome != null) return deliver(token, current, outcome)

            val expired = synchronized(lock) {
                if (generation != current) {
                    return captureFailure(token, SpeechInputFailure.RECOGNIZER_ERROR, CANCELLED)
                }
                when {
                    clock.nowMs() < deadline -> false
                    phase == Phase.CAPTURE -> {
                        // Whose deadline ran out: the engine's endpoint, still standing after
                        // its hold, or the backstop behind it. Either way one stop, one path.
                        val reached = endpointDeadlineMs()
                        val byEndpoint = reached != null && clock.nowMs() >= reached && reached <= backstop
                        beginFinalization(if (byEndpoint) CaptureStop.ENDPOINT else CaptureStop.WINDOW_EXPIRY)
                        false
                    }
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
                    lastConfidence = outcome.confidence
                    CaptureEvent.Transcript(token, outcome.text, confidence = classify(outcome.confidence))
                }
            is Outcome.RecognizerError -> captureFailure(token, classify(outcome.code), "code ${outcome.code}")
            // Hardware loss, never the learner's silence and never a wrong answer.
            is Outcome.CaptureLost -> captureFailure(token, SpeechInputFailure.EARLY_CLOSURE, outcome.detail)
            is Outcome.Cancelled -> captureFailure(token, SpeechInputFailure.RECOGNIZER_ERROR, outcome.detail)
            else -> captureFailure(token, SpeechInputFailure.EARLY_CLOSURE, UNEXPECTED_OUTCOME)
        }
    }

    /**
     * Streams microphone frames into the recognizer pipe, then the pinned trailing silence.
     * The silence is generated padding that lets the recognizer settle on the final word;
     * it is never presented as captured audio.
     *
     * AV-050 also measures each frame on its way past. This is the **fallback** endpoint
     * route and it runs only when the engine has offered none: the frames are already here,
     * so it needs no second audio path, and it changes nothing about what the recognizer
     * receives — every frame is written exactly as it was read, loud or quiet.
     */
    private fun startPump(current: Long, open: CaptureStream) {
        val thread = Thread({
            val samples = ShortArray(SpeechPins.FRAME_SAMPLES)
            val bytes = ByteArray(SpeechPins.FRAME_SAMPLES * SpeechPins.BYTES_PER_SAMPLE)
            var heardFrame = false
            var lastLoudMs = 0L
            try {
                while (true) {
                    val read = open.readFrame(samples)
                    if (read <= 0) break
                    if (synchronized(lock) { generation != current }) break
                    if (loudEnough(samples, read)) {
                        heardFrame = true
                        lastLoudMs = clock.nowMs()
                        // The onset only, which is what an engine that never reports
                        // onBeginningOfSpeech leaves #13's pre-roll without. An endpoint the
                        // engine offered is left exactly as it is: this test is far too crude
                        // to overrule it.
                        synchronized(lock) { if (generation == current && phase == Phase.CAPTURE) speechBegan(lastLoudMs) }
                    } else if (heardFrame) {
                        endpointFromSilence(current, lastLoudMs)
                    }
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

    /**
     * AV-050's fallback stop, from the pump thread.
     *
     * It refuses to run when the engine has offered an endpoint of its own — that route
     * owns the capture then — and it carries the same two guards the engine route carries:
     * nothing before speech has begun, nothing before [SpeechTimings.minCaptureMs]. Done and
     * Cancel have already left [Phase.CAPTURE] by the time this could win, so both still
     * take precedence.
     */
    private fun endpointFromSilence(current: Long, lastLoudMs: Long) {
        synchronized(lock) {
            if (generation != current || phase != Phase.CAPTURE) return
            if (speechStartedMs == 0L || endpointAtMs != 0L) return
            val now = clock.nowMs()
            if (now - lastLoudMs < timings.silenceHoldMs) return
            if (now - captureStartedMs < timings.minCaptureMs) return
            beginFinalization(CaptureStop.ENDPOINT)
        }
    }

    // --------------------------------------------------------------------- cancel

    /** Explicit Cancel from #13 or #14. Idempotent, and later callbacks are dropped. */
    override fun cancel(token: OperationToken) {
        val active = synchronized(lock) {
            if (activeToken != token) return
            // AV-050: recorded before the outcome, so a cancel that races an endpoint hold
            // is still what the record says stopped this microphone.
            if (phase == Phase.CAPTURE || phase == Phase.FINALIZING) lastCaptureStop = CaptureStop.CANCELLED
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
        // The last capture's score survives a playback that follows it (a spoken notice),
        // so a harness can still read it; only the next capture replaces it.
        if (next == Phase.CAPTURE) {
            lastConfidence = null
            // AV-050: a fresh capture has heard nothing and has not been stopped. The two
            // observables are cleared with it, so #13 can never read the previous attempt's
            // endpoint as this one's.
            speechStartedMs = 0
            endpointAtMs = 0
            lastCaptureStop = null
            lastSpeechOnsetMs = null
            // Provisional, so a callback that arrives while the microphone is still opening
            // is measured against this capture and not the previous one. The open refines it.
            captureStartedMs = clock.nowMs()
        }
        segments.clear()
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
                if (generation == this@SpeechTransport.generation) {
                    lastPartial = text
                    // AV-050: text in flight means the learner has been heard, which is what
                    // ends #13's pre-roll. It does **not** take back an endpoint: engines
                    // flush a last partial as speech ends, and treating that as more speech
                    // would stop the primary route ever firing. Only [onSpeechStarted] does.
                    if (phase == Phase.CAPTURE && text.isNotBlank()) speechBegan(clock.nowMs())
                } else {
                    staleCallbacks += "partial for generation $generation"
                }
            }
        }

        /**
         * AV-050: the engine heard the learner begin. It starts #13's answer window, and it
         * is the one signal that takes back an endpoint the engine offered earlier — a
         * learner who turns out to have been mid-pause.
         */
        override fun onSpeechStarted(generation: Long) {
            synchronized(lock) {
                if (generation == this@SpeechTransport.generation && phase == Phase.CAPTURE) {
                    speechBegan(clock.nowMs())
                    endpointAtMs = 0
                } else {
                    staleCallbacks += "speech started for generation $generation"
                }
            }
        }

        /**
         * AV-050: the engine's endpoint. Held rather than acted on, and ignored before the
         * learner has been heard at all — an endpoint over silence is not an answer ending.
         */
        override fun onSpeechEnded(generation: Long) {
            synchronized(lock) {
                if (generation != this@SpeechTransport.generation || phase != Phase.CAPTURE) {
                    staleCallbacks += "speech ended for generation $generation"
                    return
                }
                if (speechStartedMs != 0L) endpointAtMs = clock.nowMs()
            }
        }

        /**
         * A segment is kept, never delivered: only the end of the session, or a failure,
         * settles the capture. An empty segment is kept too, so the record is complete,
         * but it contributes neither text nor a score.
         */
        override fun onSegment(generation: Long, text: String, confidence: Float?) {
            synchronized(lock) {
                if (generation == this@SpeechTransport.generation && !settled) {
                    segments += text to confidence
                    // AV-050: a segment the engine closed is a pause it is sure of, so it
                    // counts as an endpoint — the strongest one this route offers. A blank
                    // segment carried no speech and says nothing about where one ended.
                    if (phase == Phase.CAPTURE && text.isNotBlank()) {
                        val now = clock.nowMs()
                        speechBegan(now)
                        endpointAtMs = now
                    }
                } else {
                    staleCallbacks += "segment for generation $generation"
                }
            }
        }

        /**
         * The capture is the segments' text, and its confidence is the **minimum** over the
         * segments that contributed text — unknown when any of those came without a score,
         * because part of the text would then be unverified. AV-012's policy and :core's
         * classification are untouched: they receive one text and one score, as before.
         */
        override fun onEndOfSegments(generation: Long) {
            val (text, confidence) = synchronized(lock) {
                if (generation != this@SpeechTransport.generation || settled) {
                    staleCallbacks += "end of segments for generation $generation"
                    return
                }
                aggregate(segments)
            }
            offer(generation, Outcome.Final(text, confidence), null)
        }

        override fun onFinal(generation: Long, text: String, confidence: Float?) =
            offer(generation, Outcome.Final(text, confidence), null)

        override fun onRecognizerError(generation: Long, code: Int) =
            offer(generation, Outcome.RecognizerError(code), null)

        override fun onCaptureLost(generation: Long, detail: String) =
            offer(generation, Outcome.CaptureLost("$DEVICE_LOST: $detail"), null)
    }

    private fun playbackFailure(token: OperationToken, mode: SpeechOutputFailure, detail: String) =
        PlaybackResult.Failed(token, Failure(mode, detail))

    private fun captureFailure(token: OperationToken, mode: SpeechInputFailure, detail: String) =
        CaptureEvent.Failed(token, Failure(mode, detail))

    companion object {
        private const val SILENCE_FRAME_MS = 20L

        /**
         * AV-050: whether one 20 ms frame carries speech, by mean absolute amplitude.
         *
         * Deliberately the crudest test that works. It decides when a capture stops and
         * nothing else — never what was said, never whether an answer was right — and it is
         * only ever consulted when the engine has offered no endpoint of its own.
         */
        fun loudEnough(samples: ShortArray, read: Int): Boolean {
            if (read <= 0) return false
            var total = 0L
            for (i in 0 until read) total += Math.abs(samples[i].toInt()).toLong()
            return total / read >= SpeechPins.SPEECH_FRAME_AMPLITUDE
        }

        // Several platform conditions share one contract failure mode, so the detail carries
        // the distinction #26 requires. :core's taxonomy is AV-007's and is not extended here.
        const val BUSY = "another speech operation is active"
        const val ENQUEUE_FAILED = "engine refused the utterance"
        const val PLAYBACK_DEADLINE = "playback did not complete"
        const val DURING_PLAYBACK = "capture requested during playback"
        const val OVERLAPPING_CAPTURE = "capture already active"
        const val PERMISSION_MISSING = "RECORD_AUDIO not granted"
        const val CAPTURE_UNAVAILABLE = "microphone or recognizer would not open"
        const val CAPTURE_OPEN_DEADLINE = "the microphone did not open within the deadline"
        const val CANCELLED = "cancelled"
        const val RELEASED = "transport released"
        const val EMPTY_RESULT = "empty result"
        const val FINALIZATION_DEADLINE = "no final within the finalization deadline"
        const val UNEXPECTED_OUTCOME = "capture ended without a final"
        const val DEVICE_LOST = "capture device or route lost"

        /**
         * Confidence is a policy classification. An absent score stays unknown: it is never
         * read as zero and never as certainty.
         */
        fun classify(confidence: Float?): Confidence = when {
            confidence == null -> Confidence.ABSENT
            confidence <= 0f -> Confidence.LOW
            else -> Confidence.SUFFICIENT
        }

        /**
         * One capture from its segments: the non-blank texts joined, and the minimum score
         * over exactly those segments, or null when any of them carried none. Blank
         * segments contribute neither, and no segments at all is an empty result.
         */
        fun aggregate(segments: List<Pair<String, Float?>>): Pair<String, Float?> {
            val contributing = segments.filter { (text, _) -> text.isNotBlank() }
            val text = contributing.joinToString(" ") { (text, _) -> text.trim() }
            val scores = contributing.map { (_, score) -> score }
            val confidence = if (scores.isEmpty() || scores.any { it == null }) null else scores.filterNotNull().min()
            return text to confidence
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
