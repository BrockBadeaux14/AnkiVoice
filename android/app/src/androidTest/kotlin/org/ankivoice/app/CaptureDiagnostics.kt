package org.ankivoice.app

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import org.ankivoice.speech.CaptureStream
import org.ankivoice.speech.RecognitionListener
import org.ankivoice.speech.SpeechPlatform
import org.json.JSONObject

/** Optional test-only observation of actual MIC frames; never replaces or edits a sample. */
internal class CaptureDiagnostics(private val platform: SpeechPlatform) : SpeechPlatform by platform {
    private val pcm = ByteArrayOutputStream()
    private var count = 0L
    private var zeros = 0L
    private var squareSum = 0.0
    private var peak = 0

    override fun startCapture(generation: Long, language: String, listener: RecognitionListener): CaptureStream? {
        val original = platform.startCapture(generation, language, listener) ?: return null
        return object : CaptureStream by original {
            override fun readFrame(samples: ShortArray): Int {
                val read = original.readFrame(samples)
                synchronized(this@CaptureDiagnostics) {
                    for (i in 0 until read.coerceAtLeast(0)) {
                        val value = samples[i].toInt()
                        pcm.write(value and 255)
                        pcm.write((value shr 8) and 255)
                        count++
                        if (value == 0) zeros++
                        squareSum += value.toDouble() * value
                        peak = maxOf(peak, abs(value))
                    }
                }
                return read
            }
        }
    }

    @Synchronized
    fun save(file: File): JSONObject {
        file.writeBytes(pcm.toByteArray())
        return JSONObject().put("file", file.name).put("sampleRateHz", 16_000)
            .put("sampleCount", count).put("zeroSamples", zeros).put("peak", peak)
            .put("rms", if (count == 0L) 0.0 else sqrt(squareSum / count))
            .put("format", "mono PCM16 little-endian; microphone only, before trailing silence")
    }
}
