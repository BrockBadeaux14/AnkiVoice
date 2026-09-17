package org.ankivoice.app

import android.os.SystemClock
import org.ankivoice.speech.CaptureStream
import org.ankivoice.speech.RecognitionListener
import org.ankivoice.speech.SpeechPlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * AV-046's second observer on the shipped pipe. `CaptureDiagnostics` keeps the samples the
 * transport's recorder delivered; this one keeps **when**: when the open was asked for and
 * answered, when the first frame came back and the first one that was not silence, how
 * long reads and pipe writes blocked, how many bytes reached the recognizer's pipe before
 * Done and how many of generated padding after it, and when the microphone was stopped and
 * the pipe closed.
 *
 * Every call is passed straight through. Nothing here changes a sample, a return value, an
 * exception or the order of anything; a write that fails is recorded and then thrown on,
 * exactly as it would have been.
 */
internal class PumpDiagnostics(
    private val platform: SpeechPlatform,
    private val origin: Long,
) : SpeechPlatform by platform {
    private val lock = Any()
    private var openRequestedMs: Long? = null
    private var openReturnedMs: Long? = null
    private var opened: Boolean? = null
    private var openError: String? = null

    private var firstReadMs: Long? = null
    private var firstNonZeroMs: Long? = null
    private var firstLoudMs: Long? = null
    private var lastReadMs: Long? = null
    private var reads = 0L
    private var emptyReads = 0L
    private val failedReads = mutableListOf<Int>()
    private var readSamples = 0L
    private var maxReadMs = 0L
    private var slowReads = 0L

    private var firstWriteMs: Long? = null
    private var lastWriteMs: Long? = null
    private var writes = 0L
    private var micBytes = 0L
    private var paddingWrites = 0L
    private var paddingBytes = 0L
    private var maxWriteMs = 0L
    private var slowWrites = 0L
    private val writeErrors = JSONArray()

    private var stopMs: Long? = null
    private var endOfInputMs: Long? = null
    private var closeMs: Long? = null
    private var micStopped = false

    private fun now() = SystemClock.elapsedRealtime() - origin

    override fun startCapture(generation: Long, language: String, listener: RecognitionListener): CaptureStream? {
        synchronized(lock) { openRequestedMs = now() }
        val original = try {
            platform.startCapture(generation, language, listener)
        } catch (e: RuntimeException) {
            synchronized(lock) {
                openReturnedMs = now()
                opened = false
                openError = "${e.javaClass.simpleName}: ${e.message}"
            }
            throw e
        }
        synchronized(lock) {
            openReturnedMs = now()
            opened = original != null
        }
        if (original == null) return null
        return object : CaptureStream {
            override fun readFrame(samples: ShortArray): Int {
                val before = now()
                val read = original.readFrame(samples)
                val after = now()
                synchronized(lock) {
                    reads++
                    val latency = after - before
                    if (latency > maxReadMs) maxReadMs = latency
                    if (latency > SLOW_MS) slowReads++
                    when {
                        // After Done the stream reports end of input with -1; that is the
                        // stop working, not a failed read.
                        read < 0 && micStopped -> if (endOfInputMs == null) endOfInputMs = after
                        read < 0 -> failedReads += read
                        read == 0 -> emptyReads++
                        else -> {
                            if (firstReadMs == null) firstReadMs = after
                            lastReadMs = after
                            readSamples += read
                            if (firstNonZeroMs == null || firstLoudMs == null) {
                                for (i in 0 until read) {
                                    val value = samples[i].toInt()
                                    if (value != 0 && firstNonZeroMs == null) firstNonZeroMs = after
                                    if ((value >= LOUD || value <= -LOUD) && firstLoudMs == null) firstLoudMs = after
                                }
                            }
                        }
                    }
                }
                return read
            }

            override fun write(bytes: ByteArray, length: Int) {
                val before = now()
                try {
                    original.write(bytes, length)
                } catch (e: Throwable) {
                    synchronized(lock) {
                        writeErrors.put(JSONObject().put("atMs", now()).put("error", "${e.javaClass.simpleName}: ${e.message}"))
                    }
                    throw e
                }
                val after = now()
                synchronized(lock) {
                    val latency = after - before
                    if (latency > maxWriteMs) maxWriteMs = latency
                    if (latency > SLOW_MS) slowWrites++
                    if (micStopped) {
                        paddingWrites++
                        paddingBytes += length
                    } else {
                        if (firstWriteMs == null) firstWriteMs = after
                        writes++
                        micBytes += length
                    }
                    lastWriteMs = after
                }
            }

            override fun stopMicrophone() {
                synchronized(lock) {
                    if (stopMs == null) stopMs = now()
                    micStopped = true
                }
                original.stopMicrophone()
            }

            override fun close() {
                synchronized(lock) { if (closeMs == null) closeMs = now() }
                original.close()
            }
        }
    }

    fun report(): JSONObject = synchronized(lock) {
        JSONObject()
            .put("openRequestedMs", openRequestedMs ?: JSONObject.NULL)
            .put("openReturnedMs", openReturnedMs ?: JSONObject.NULL)
            .put("opened", opened ?: JSONObject.NULL)
            .put("openError", openError ?: JSONObject.NULL)
            .put("firstReadMs", firstReadMs ?: JSONObject.NULL)
            .put("firstNonZeroMs", firstNonZeroMs ?: JSONObject.NULL)
            .put("firstLoudMs", firstLoudMs ?: JSONObject.NULL)
            .put("lastReadMs", lastReadMs ?: JSONObject.NULL)
            .put("reads", reads)
            .put("emptyReads", emptyReads)
            .put("failedReads", JSONArray(failedReads))
            .put("readSamples", readSamples)
            .put("maxReadMs", maxReadMs)
            .put("slowReads", slowReads)
            .put("firstWriteMs", firstWriteMs ?: JSONObject.NULL)
            .put("lastWriteMs", lastWriteMs ?: JSONObject.NULL)
            .put("writes", writes)
            .put("micBytes", micBytes)
            .put("paddingWrites", paddingWrites)
            .put("paddingBytes", paddingBytes)
            .put("maxWriteMs", maxWriteMs)
            .put("slowWrites", slowWrites)
            .put("writeErrors", writeErrors)
            .put("stopMicrophoneMs", stopMs ?: JSONObject.NULL)
            .put("endOfInputMs", endOfInputMs ?: JSONObject.NULL)
            .put("closeMs", closeMs ?: JSONObject.NULL)
            .put("loudThreshold", LOUD)
            .put("slowThresholdMs", SLOW_MS)
    }

    private companion object {
        const val LOUD = 1_000
        const val SLOW_MS = 100L
    }
}
