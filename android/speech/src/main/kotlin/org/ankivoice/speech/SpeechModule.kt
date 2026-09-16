package org.ankivoice.speech

import android.content.Context

/**
 * The module's composition point. AV-022 gives :speech the TextToSpeech and
 * SpeechRecognizer instances, the microphone stream and cleanup (#26); AV-025 implements
 * them as [AndroidSpeechPlatform] behind [SpeechTransport].
 *
 * The returned transport is both AV-007 speech contracts, so :app hands the same object to
 * #13 as its `SpeechOutput` and `SpeechInput`.
 */
object SpeechModule {
    /** One transport per study session. Call [SpeechTransport.releaseAll] when it ends. */
    fun create(context: Context, timings: SpeechTimings = SpeechTimings()): SpeechTransport =
        SpeechTransport(AndroidSpeechPlatform(context.applicationContext), timings)
}
