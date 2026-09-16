package org.ankivoice.speech

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A scripted stand-in for [AndroidSpeechPlatform]. It reproduces the shape of the real
 * route — playback completes, capture streams frames, the segmented session ends when the
 * pipe closes — so every ordering, deadline and stale-callback rule is checked without an
 * emulator. It deliberately does not simulate recognition quality; that is live evidence.
 */
class FakeSpeechPlatform : SpeechPlatform {

    sealed interface Playback {
        data object Done : Playback
        data class Error(val detail: String) : Playback

        /** Never calls back, so the playback deadline can expire. */
        data object Silent : Playback
    }

    sealed interface Recognition {
        data class Final(val text: String, val confidence: Float? = null) : Recognition
        data class Error(val code: Int) : Recognition

        /** Never calls back, so the finalization deadline can expire. */
        data object Silent : Recognition
    }

    var voice: VoiceResolution = VoiceResolution.Resolved(SpeechPins.TTS_VOICE)
    var recognizer: RecognizerResolution = RecognizerResolution.Resolved
    var permissionGranted = true
    var playbackEnqueues = true
    var playback: Playback = Playback.Done
    var captureOpens = true

    /** Delivered when the capture pipe closes, as the real segmented session does. */
    var recognition: Recognition = Recognition.Final("green blue red")

    val playbackStarts = AtomicInteger()
    val captureStarts = AtomicInteger()
    val recognizerStops = AtomicInteger()
    val releases = AtomicInteger()
    val spokenText = CopyOnWriteArrayList<String>()
    val languages = CopyOnWriteArrayList<String>()

    /** Frames the microphone yields before Done; each is a distinct non-zero sample. */
    var microphoneFrames = 3

    @Volatile
    var lastStream: FakeCaptureStream? = null

    /** Opened so a test can act exactly when capture is live. */
    val captureOpened = CountDownLatch(1)

    private var listener: RecognitionListener? = null
    private var generation = 0L

    override fun microphonePermissionGranted(): Boolean = permissionGranted

    override fun resolveVoice(language: String): VoiceResolution = voice

    override fun resolveRecognizer(language: String): RecognizerResolution = recognizer

    override fun startPlayback(generation: Long, text: String, listener: PlaybackListener): Boolean {
        playbackStarts.incrementAndGet()
        spokenText += text
        if (!playbackEnqueues) return false
        when (val step = playback) {
            Playback.Done -> listener.onPlaybackDone(generation)
            is Playback.Error -> listener.onPlaybackError(generation, step.detail)
            Playback.Silent -> Unit
        }
        return true
    }

    override fun stopPlayback() = Unit

    override fun startCapture(
        generation: Long,
        language: String,
        listener: RecognitionListener,
    ): CaptureStream? {
        captureStarts.incrementAndGet()
        languages += language
        if (!captureOpens) return null
        this.listener = listener
        this.generation = generation
        val stream = FakeCaptureStream(microphoneFrames) { deliver(generation, listener) }
        lastStream = stream
        captureOpened.countDown()
        return stream
    }

    private fun deliver(generation: Long, listener: RecognitionListener) {
        when (val step = recognition) {
            is Recognition.Final -> listener.onFinal(generation, step.text, step.confidence)
            is Recognition.Error -> listener.onRecognizerError(generation, step.code)
            Recognition.Silent -> Unit
        }
    }

    /** The microphone stops being ours mid-capture: route gone, or client silenced. */
    fun loseCapture(detail: String) {
        listener?.onCaptureLost(generation, detail)
    }

    /** Replays a callback for a generation the transport has already finished with. */
    fun replayFinal(generation: Long, text: String) {
        listener?.onFinal(generation, text, null)
    }

    fun emitPartial(text: String) {
        listener?.onPartial(generation, text)
    }

    override fun stopRecognizer() {
        recognizerStops.incrementAndGet()
    }

    override fun release() {
        releases.incrementAndGet()
    }
}

/** Records every byte the transport pumps, and what it did at the end of the stream. */
class FakeCaptureStream(
    private val frames: Int,
    private val onClose: () -> Unit,
) : CaptureStream {
    private val recording = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)
    private var produced = 0

    val written = CopyOnWriteArrayList<ByteArray>()

    @Volatile
    var microphoneStopped = false
        private set

    @Volatile
    var closeCount = 0
        private set

    override fun readFrame(samples: ShortArray): Int {
        while (recording.get()) {
            if (produced >= frames) {
                // A live microphone blocks here until it is stopped; mimic that cheaply.
                Thread.sleep(1)
                continue
            }
            produced += 1
            samples.fill(produced.toShort())
            return samples.size
        }
        return -1
    }

    override fun write(bytes: ByteArray, length: Int) {
        written += bytes.copyOf(length)
    }

    override fun stopMicrophone() {
        microphoneStopped = true
        recording.set(false)
    }

    override fun close() {
        stopMicrophone()
        closeCount += 1
        if (closed.compareAndSet(false, true)) onClose()
    }

    /** Frames written after the microphone stopped: the pinned trailing silence. */
    fun trailingSilenceFrames(): Int = written.count { frame -> frame.all { it.toInt() == 0 } }

    fun microphoneFramesWritten(): Int = written.count { frame -> frame.any { it.toInt() != 0 } }
}
