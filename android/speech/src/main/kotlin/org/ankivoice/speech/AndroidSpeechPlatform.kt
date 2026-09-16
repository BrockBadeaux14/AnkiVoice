package org.ankivoice.speech

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener as AndroidRecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.content.Intent
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only class in AnkiVoice that touches TextToSpeech, SpeechRecognizer and AudioRecord.
 *
 * It is a direct port of the route AV-042 proved live on the pinned AVD: the pinned engine
 * synthesizes the prompt to a file that MediaPlayer plays, and an app-owned 16 kHz PCM16
 * `AudioRecord` streams into the pinned recognition service through its external-audio pipe.
 * Ordering, deadlines and failure classification live in [SpeechTransport], not here.
 *
 * SpeechRecognizer is main-thread-only, so recognizer work is marshalled to the main looper
 * while the transport keeps blocking on its own worker thread.
 */
class AndroidSpeechPlatform(private val context: Context) : SpeechPlatform {

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private var recognizer: SpeechRecognizer? = null
    private var recorder: AudioRecord? = null
    private var pipe: Array<ParcelFileDescriptor>? = null

    override fun microphonePermissionGranted(): Boolean =
        context.checkSelfPermission(SpeechPins.MICROPHONE_PERMISSION) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------- playback

    override fun resolveVoice(language: String): VoiceResolution {
        val engine = ensureEngine() ?: return VoiceResolution.EngineUnavailable(
            "${SpeechPins.TTS_ENGINE} unavailable or failed to initialize",
        )
        // An availability Boolean is not a capability: the voice must actually resolve.
        val locale = try {
            Locale.forLanguageTag(language)
        } catch (_: IllegalArgumentException) {
            return VoiceResolution.LanguageUnsupported("unresolvable language tag $language")
        }
        val voices = try {
            engine.voices.orEmpty()
        } catch (_: RuntimeException) {
            return VoiceResolution.EngineUnavailable("engine refused to list voices")
        }
        val exact = voices.firstOrNull {
            it.name == SpeechPins.TTS_VOICE && !it.isNetworkConnectionRequired
        }
        val match = exact ?: voices.firstOrNull {
            it.locale.language == locale.language && !it.isNetworkConnectionRequired
        } ?: return VoiceResolution.LanguageUnsupported("no local voice for $language")

        return when (engine.setVoice(match)) {
            TextToSpeech.SUCCESS -> {
                engine.setSpeechRate(speechRate)
                engine.setPitch(1f)
                VoiceResolution.Resolved(match.name)
            }
            else -> VoiceResolution.LanguageUnsupported("engine rejected voice ${match.name}")
        }
    }

    /** Configurable playback rate, as #26 requires. Pitch stays at the engine default. */
    @Volatile
    var speechRate: Float = 1f

    private fun ensureEngine(): TextToSpeech? {
        synchronized(this) { if (ttsReady) return tts }
        val ready = CountDownLatch(1)
        val status = AtomicBoolean(false)
        val engine = TextToSpeech(context, { code ->
            status.set(code == TextToSpeech.SUCCESS)
            ready.countDown()
        }, SpeechPins.TTS_ENGINE)
        if (!ready.await(ENGINE_INIT_MS, TimeUnit.MILLISECONDS) || !status.get()) {
            engine.shutdown()
            return null
        }
        synchronized(this) {
            tts = engine
            ttsReady = true
        }
        return engine
    }

    override fun startPlayback(generation: Long, text: String, listener: PlaybackListener): Boolean {
        val engine = synchronized(this) { tts } ?: return false
        val file = File(context.cacheDir, PROMPT_FILE)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId != generation.toString()) return
                main.post { play(generation, file, listener) }
            }

            @Deprecated("Required by UtteranceProgressListener", ReplaceWith("onError(utteranceId, errorCode)"))
            override fun onError(utteranceId: String?) = onError(utteranceId, -1)

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId != generation.toString()) return
                listener.onPlaybackError(generation, "synthesis error $errorCode")
            }
        })
        // Synthesize then play, which is the path AV-042 measured as audible on the AVD.
        return engine.synthesizeToFile(text, Bundle(), file, generation.toString()) == TextToSpeech.SUCCESS
    }

    private fun play(generation: Long, file: File, listener: PlaybackListener) {
        try {
            val media = MediaPlayer()
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            media.setDataSource(file.path)
            media.prepare()
            media.setOnCompletionListener { listener.onPlaybackDone(generation) }
            media.setOnErrorListener { _, what, extra ->
                listener.onPlaybackError(generation, "playback error $what/$extra")
                true
            }
            synchronized(this) { player = media }
            media.start()
        } catch (e: RuntimeException) {
            listener.onPlaybackError(generation, "playback failed: ${e.message}")
        } catch (e: java.io.IOException) {
            listener.onPlaybackError(generation, "playback failed: ${e.message}")
        }
    }

    override fun stopPlayback() {
        val media = synchronized(this) { player.also { player = null } }
        media?.let {
            runCatching { it.stop() }
            it.release()
        }
        synchronized(this) { tts }?.let { runCatching { it.stop() } }
    }

    // -------------------------------------------------------------------- capture

    override fun resolveRecognizer(language: String): RecognizerResolution {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return RecognizerResolution.Unavailable("no recognition service on this device")
        }
        val installed = try {
            context.packageManager.getPackageInfo(SpeechPins.RECOGNITION_PACKAGE, 0) != null
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) {
            return RecognizerResolution.Unavailable("${SpeechPins.RECOGNITION_PACKAGE} not installed")
        }
        if (audio?.mode != AudioManager.MODE_NORMAL) {
            // Another audio mode means the microphone is not ours to take.
            return RecognizerResolution.Unavailable("audio mode ${audio?.mode} is not MODE_NORMAL")
        }
        return RecognizerResolution.Resolved
    }

    override fun startCapture(
        generation: Long,
        language: String,
        listener: RecognitionListener,
    ): CaptureStream? {
        val record = try {
            val minimum = AudioRecord.getMinBufferSize(
                SpeechPins.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SpeechPins.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(MIN_BUFFER_BYTES, minimum * 2))
                .build()
        } catch (_: RuntimeException) {
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }

        val channel = try {
            ParcelFileDescriptor.createPipe()
        } catch (_: java.io.IOException) {
            record.release()
            return null
        }
        synchronized(this) {
            recorder = record
            pipe = channel
        }

        val started = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        main.post {
            try {
                val engine = SpeechRecognizer.createSpeechRecognizer(
                    context,
                    ComponentName(SpeechPins.RECOGNITION_PACKAGE, SpeechPins.RECOGNITION_SERVICE),
                )
                engine.setRecognitionListener(RecognizerBridge(generation, listener))
                synchronized(this) { recognizer = engine }
                engine.startListening(recognitionIntent(language, channel[0]))
                ok.set(true)
            } catch (e: RuntimeException) {
                listener.onRecognizerError(generation, RecognizerErrors.CLIENT)
            } finally {
                started.countDown()
            }
        }
        if (!started.await(RECOGNIZER_START_MS, TimeUnit.MILLISECONDS) || !ok.get()) {
            record.release()
            channel.forEach { runCatching { it.close() } }
            synchronized(this) {
                recorder = null
                pipe = null
            }
            return null
        }

        record.startRecording()
        return AudioRecordStream(record, ParcelFileDescriptor.AutoCloseOutputStream(channel[1]))
    }

    private fun recognitionIntent(language: String, readEnd: ParcelFileDescriptor): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // AV-006's accepted online-permitted mode; AV-042 showed true produces no-match here.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, SpeechPins.PREFER_OFFLINE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, SpeechPins.CHANNEL_COUNT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SpeechPins.SAMPLE_RATE_HZ)
            // The segmented session ends when the transport closes the write end.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

    /** Translates the platform callbacks into the transport's generation-tagged ones. */
    private inner class RecognizerBridge(
        private val generation: Long,
        private val listener: RecognitionListener,
    ) : AndroidRecognitionListener {
        private val segments = mutableListOf<String>()

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            listener.onPartial(generation, text(partialResults))
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            segments += text(segmentResults)
        }

        override fun onEndOfSegmentedSession() {
            listener.onFinal(generation, segments.joinToString(" ").trim(), null)
        }

        override fun onResults(results: Bundle?) {
            listener.onFinal(generation, text(results), confidence(results))
        }

        override fun onError(error: Int) = listener.onRecognizerError(generation, error)

        private fun text(bundle: Bundle?): String =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

        /** Null when the engine supplied no score, so the transport reports it as unknown. */
        private fun confidence(bundle: Bundle?): Float? =
            bundle?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
    }

    /** One capture: microphone frames in, PCM16 bytes out to the recognizer pipe. */
    private class AudioRecordStream(
        private val record: AudioRecord,
        private val sink: OutputStream,
    ) : CaptureStream {
        private val recording = AtomicBoolean(true)
        private val closed = AtomicBoolean(false)

        override fun readFrame(samples: ShortArray): Int {
            if (!recording.get()) return -1
            val read = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
            return if (!recording.get() || read < 0) -1 else read
        }

        override fun write(bytes: ByteArray, length: Int) {
            if (closed.get()) return
            sink.write(bytes, 0, length)
        }

        override fun stopMicrophone() {
            if (!recording.compareAndSet(true, false)) return
            runCatching { record.stop() }
        }

        override fun close() {
            stopMicrophone()
            if (!closed.compareAndSet(false, true)) return
            // Closing the write end is what ends the recognizer's segmented session.
            runCatching { sink.close() }
            runCatching { record.release() }
        }
    }

    override fun stopRecognizer() {
        val engine = synchronized(this) { recognizer.also { recognizer = null } }
        if (engine != null) {
            main.post {
                runCatching { engine.cancel() }
                runCatching { engine.destroy() }
            }
        }
        val channel = synchronized(this) { pipe.also { pipe = null } }
        channel?.forEach { runCatching { it.close() } }
        val record = synchronized(this) { recorder.also { recorder = null } }
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
    }

    override fun release() {
        stopPlayback()
        stopRecognizer()
        val engine = synchronized(this) {
            ttsReady = false
            tts.also { tts = null }
        }
        engine?.shutdown()
    }

    private companion object {
        const val PROMPT_FILE = "ankivoice-prompt.wav"
        const val ENGINE_INIT_MS = 10_000L
        const val RECOGNIZER_START_MS = 5_000L
        const val MIN_BUFFER_BYTES = 6_400
    }
}
