package org.ankivoice.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * AV-046's reference capture: the smallest `AudioRecord` that can read the guest
 * microphone, on its own thread, with nothing of AV-025's between it and the audio server.
 * No pipe, no recognizer, no transport, and no `:speech` type at all. It asks for the same
 * source, rate and format as the shipped recorder, so that a difference between what the
 * two receive is a difference in the app's pipe rather than in the request.
 *
 * It records what it read and when, and decides nothing. Timestamps are milliseconds since
 * the harness's [origin], the same clock `PumpDiagnostics` uses, so the two can be laid
 * side by side.
 */
internal class ReferenceMicrophone(private val origin: Long) {
    private val lock = Any()
    private val pcm = ByteArrayOutputStream()
    private val running = AtomicBoolean(false)
    private val firstFrame = CountDownLatch(1)
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    private var bufferBytes = 0
    private var openError: String? = null
    private var openedMs: Long? = null
    private var startedMs: Long? = null
    private var firstFrameMs: Long? = null
    private var firstNonZeroMs: Long? = null
    private var firstLoudMs: Long? = null
    private var lastFrameMs: Long? = null
    private var stoppedMs: Long? = null
    private var reads = 0L
    private var emptyReads = 0L
    private val failedReads = mutableListOf<Int>()
    private var maxReadMs = 0L
    private var slowReads = 0L
    private var count = 0L
    private var zeros = 0L
    private var squareSum = 0.0
    private var peak = 0
    private val seconds = mutableListOf<Second>()
    private var device: JSONObject? = null
    private var recordingState: Int? = null

    private class Second {
        var samples = 0L
        var zeros = 0L
        var peak = 0
        var squareSum = 0.0
    }

    private fun now() = SystemClock.elapsedRealtime() - origin

    /**
     * Builds and starts the recorder on its own thread. Returns false when it could not be
     * opened; [save] then carries the reason. The permission is the harness's to check
     * first, exactly as the shipped platform checks it before its own open.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        bufferBytes = maxOf(minimum * 2, MIN_BUFFER_BYTES)
        val built = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: RuntimeException) {
            synchronized(lock) { openError = "${e.javaClass.simpleName}: ${e.message}" }
            return false
        }
        if (built.state != AudioRecord.STATE_INITIALIZED) {
            synchronized(lock) { openError = "AudioRecord state ${built.state}" }
            built.release()
            return false
        }
        synchronized(lock) {
            record = built
            openedMs = now()
        }
        try {
            built.startRecording()
        } catch (e: IllegalStateException) {
            synchronized(lock) { openError = "startRecording: ${e.message}" }
            built.release()
            synchronized(lock) { record = null }
            return false
        }
        synchronized(lock) {
            startedMs = now()
            recordingState = built.recordingState
            device = describeDevice(built)
        }
        running.set(true)
        val reader = Thread({ pump(built) }, "av046-reference-microphone")
        reader.isDaemon = true
        reader.start()
        thread = reader
        return true
    }

    /** True once the first frame has arrived, or false when [millis] passed without one. */
    fun awaitFirstFrame(millis: Long): Boolean = firstFrame.await(millis, TimeUnit.MILLISECONDS)

    private fun pump(built: AudioRecord) {
        val samples = ShortArray(FRAME)
        while (running.get()) {
            val before = now()
            val read = built.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
            val after = now()
            synchronized(lock) {
                reads++
                val latency = after - before
                if (latency > maxReadMs) maxReadMs = latency
                if (latency > SLOW_READ_MS) slowReads++
                when {
                    read < 0 -> failedReads += read
                    read == 0 -> emptyReads++
                    else -> {
                        if (firstFrameMs == null) firstFrameMs = after
                        lastFrameMs = after
                        for (i in 0 until read) {
                            val value = samples[i].toInt()
                            pcm.write(value and 255)
                            pcm.write((value shr 8) and 255)
                            val second = (count / RATE).toInt()
                            while (seconds.size <= second) seconds += Second()
                            val bucket = seconds[second]
                            count++
                            bucket.samples++
                            val magnitude = abs(value)
                            if (value == 0) {
                                zeros++
                                bucket.zeros++
                            } else if (firstNonZeroMs == null) {
                                firstNonZeroMs = after
                            }
                            if (magnitude >= LOUD && firstLoudMs == null) firstLoudMs = after
                            squareSum += value.toDouble() * value
                            bucket.squareSum += value.toDouble() * value
                            if (magnitude > peak) peak = magnitude
                            if (magnitude > bucket.peak) bucket.peak = magnitude
                        }
                    }
                }
            }
            if (read > 0) firstFrame.countDown()
            // A stopped recorder reports the stop; a dead one reports an error. Either ends this.
            if (read < 0 && !running.get()) break
            if (read < 0 && synchronized(lock) { failedReads.size } >= MAX_FAILED_READS) break
        }
    }

    /** Stops the recorder and waits for the reading thread. Idempotent. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val built = synchronized(lock) { record }
        runCatching { built?.stop() }
        thread?.join(2_000)
        synchronized(lock) {
            stoppedMs = now()
            recordingState = runCatching { built?.recordingState }.getOrNull()
        }
        runCatching { built?.release() }
        synchronized(lock) { record = null }
    }

    /** Writes the raw PCM to [file] and returns everything this recorder observed. */
    fun save(file: File): JSONObject {
        stop()
        synchronized(lock) {
            file.writeBytes(pcm.toByteArray())
            val profile = JSONArray()
            seconds.forEachIndexed { index, second ->
                profile.put(
                    JSONObject()
                        .put("second", index)
                        .put("samples", second.samples)
                        .put("peak", second.peak)
                        .put("rms", if (second.samples == 0L) 0.0 else sqrt(second.squareSum / second.samples))
                        .put("zeroFraction", if (second.samples == 0L) 0.0 else second.zeros.toDouble() / second.samples),
                )
            }
            return JSONObject()
                .put("file", file.name)
                .put("route", "AudioRecord(MIC, 16000 Hz, mono, PCM16) read on its own thread; no pipe, no recognizer")
                .put("bufferBytes", bufferBytes)
                .put("openError", openError ?: JSONObject.NULL)
                .put("openedMs", openedMs ?: JSONObject.NULL)
                .put("startedMs", startedMs ?: JSONObject.NULL)
                .put("firstFrameMs", firstFrameMs ?: JSONObject.NULL)
                .put("firstNonZeroMs", firstNonZeroMs ?: JSONObject.NULL)
                .put("firstLoudMs", firstLoudMs ?: JSONObject.NULL)
                .put("lastFrameMs", lastFrameMs ?: JSONObject.NULL)
                .put("stoppedMs", stoppedMs ?: JSONObject.NULL)
                .put("reads", reads)
                .put("emptyReads", emptyReads)
                .put("failedReads", JSONArray(failedReads))
                .put("maxReadMs", maxReadMs)
                .put("slowReads", slowReads)
                .put("sampleRateHz", RATE)
                .put("sampleCount", count)
                .put("zeroSamples", zeros)
                .put("peak", peak)
                .put("rms", if (count == 0L) 0.0 else sqrt(squareSum / count))
                .put("loudThreshold", LOUD)
                .put("perSecond", profile)
                .put("device", device ?: JSONObject.NULL)
                .put("recordingState", recordingState ?: JSONObject.NULL)
                .put("format", "mono PCM16 little-endian; every sample the reference read")
        }
    }

    private fun describeDevice(built: AudioRecord): JSONObject {
        val out = JSONObject()
            .put("audioSessionId", built.audioSessionId)
            .put("audioSource", built.audioSource)
            .put("sampleRate", built.sampleRate)
            .put("channelCount", built.channelCount)
            .put("bufferSizeInFrames", runCatching { built.bufferSizeInFrames }.getOrNull() ?: JSONObject.NULL)
        val routed = runCatching { built.routedDevice }.getOrNull()
        out.put(
            "routedDevice",
            routed?.let {
                JSONObject().put("id", it.id).put("type", it.type).put("productName", it.productName.toString())
            } ?: JSONObject.NULL,
        )
        val microphones = runCatching { built.activeMicrophones }.getOrNull()
        out.put(
            "activeMicrophones",
            JSONArray(
                microphones.orEmpty().map {
                    JSONObject().put("id", it.id).put("description", it.description).put("type", it.type)
                        .put("location", it.location).put("address", it.address)
                },
            ),
        )
        return out
    }

    private companion object {
        const val RATE = 16_000
        const val FRAME = 320
        const val MIN_BUFFER_BYTES = 6_400
        const val LOUD = 1_000
        const val SLOW_READ_MS = 100L
        const val MAX_FAILED_READS = 20
    }
}
