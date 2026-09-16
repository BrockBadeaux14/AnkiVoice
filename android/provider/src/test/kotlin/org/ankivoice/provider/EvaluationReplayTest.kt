package org.ankivoice.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * AV-017's reproducibility mechanism, proven with no network and no emulator.
 *
 * The scoring run has to be repeatable from the transcript the one live pass recorded,
 * and it has to *fail* rather than quietly re-score when the request the shipped code
 * builds no longer matches what produced those replies. Both are checked here against a
 * scripted inner transport, so neither depends on a live route.
 */
class EvaluationReplayTest {
    @TempDir
    lateinit var directory: File

    /** A stand-in for the network, so the recording transport has something to record. */
    private class ScriptedTransport(private val replies: MutableList<String>) : HttpTransport {
        var posts = 0
            private set

        override fun get(url: String, key: String, timeoutMs: Int): HttpResult =
            HttpResult.Response(200, ENDPOINTS)

        override fun post(url: String, key: String, body: String, timeoutMs: Int): HttpResult {
            posts++
            return HttpResult.Response(200, if (replies.size > 1) replies.removeAt(0) else replies.first())
        }
    }

    private fun transcript() = File(directory, "transcript.jsonl").apply { writeText("") }

    @Test
    fun `a recorded pass replays to the same replies with no network`() {
        val file = transcript()
        val scripted = ScriptedTransport(mutableListOf(reply("correct"), reply("incorrect")))
        val recording = RecordingTransport(file, scripted)

        recording.startAnswer("answer-1")
        val first = recording.post(FreeRoute.completionsUrl, KEY, REQUEST_A, 20_000)
        recording.startAnswer("answer-2")
        val second = recording.post(FreeRoute.completionsUrl, KEY, REQUEST_B, 20_000)
        assertEquals(2, scripted.posts)

        val replay = ReplayTransport(file)
        replay.startAnswer("answer-1")
        assertEquals(first, replay.post(FreeRoute.completionsUrl, KEY, REQUEST_A, 20_000))
        replay.startAnswer("answer-2")
        assertEquals(second, replay.post(FreeRoute.completionsUrl, KEY, REQUEST_B, 20_000))
        assertTrue(replay.exhausted())
    }

    @Test
    fun `a replay refuses a request the recorded replies did not answer`() {
        val file = transcript()
        val recording = RecordingTransport(file, ScriptedTransport(mutableListOf(reply("correct"))))
        recording.startAnswer("answer-1")
        recording.post(FreeRoute.completionsUrl, KEY, REQUEST_A, 20_000)

        val replay = ReplayTransport(file)
        replay.startAnswer("answer-1")
        // The instruction, the route envelope or the corpus changed: the recorded reply no
        // longer describes what this build sends, so scoring must stop rather than reuse it.
        val refused = assertThrows<IllegalStateException> {
            replay.post(FreeRoute.completionsUrl, KEY, REQUEST_B, 20_000)
        }
        assertTrue(refused.message.orEmpty().contains("different request"), refused.message)
    }

    @Test
    fun `an unreplayed request leaves the transcript unexhausted`() {
        val file = transcript()
        val recording = RecordingTransport(file, ScriptedTransport(mutableListOf(reply("correct"))))
        recording.startAnswer("answer-1")
        recording.post(FreeRoute.completionsUrl, KEY, REQUEST_A, 20_000)
        assertFalse(ReplayTransport(file).exhausted())
    }

    @Test
    fun `the transcript carries a digest of the request and never the request itself`() {
        val file = transcript()
        val recording = RecordingTransport(file, ScriptedTransport(mutableListOf(reply("correct"))))
        recording.startAnswer("answer-1")
        recording.post(FreeRoute.completionsUrl, KEY, REQUEST_A, 20_000)

        val line = TranscriptLine.read(file.readLines().single())
        assertEquals(EvaluationCorpus.sha256(REQUEST_A), line.requestSha256)
        val text = file.readText()
        // The key is a header, never a record; the prompt travels only as a digest.
        assertFalse(text.contains(KEY), "the transcript carries the credential")
        assertFalse(text.contains("learner_answer"), "the transcript carries the raw request")
    }

    @Test
    fun `a recorded timeout and refusal replay as themselves, not as replies`() {
        val file = transcript()
        val failing = object : HttpTransport {
            override fun get(url: String, key: String, timeoutMs: Int): HttpResult =
                HttpResult.Response(200, ENDPOINTS)

            override fun post(url: String, key: String, body: String, timeoutMs: Int): HttpResult =
                HttpResult.Timeout
        }
        val recording = RecordingTransport(file, failing)
        recording.startAnswer("answer-1")
        assertEquals(HttpResult.Timeout, recording.post(FreeRoute.completionsUrl, KEY, REQUEST_A, 20_000))

        val replay = ReplayTransport(file)
        replay.startAnswer("answer-1")
        assertEquals(HttpResult.Timeout, replay.post(FreeRoute.completionsUrl, KEY, REQUEST_A, 20_000))
    }

    private companion object {
        const val KEY = "sk-or-v1-0123456789abcdef0123456789abcdef"
        val REQUEST_A: String = Json.write(mapOf("learner_answer" to "five blocks"))
        val REQUEST_B: String = Json.write(mapOf("learner_answer" to "green blue red"))

        val ENDPOINTS = """{"data":{"id":"${FreeRoute.MODEL}","endpoints":[
            {"name":"Liquid | fp8","tag":"${FreeRoute.PROVIDER}","pricing":{"prompt":"0","completion":"0"}}]}}"""

        fun reply(label: String): String {
            val content = Json.write(linkedMapOf("label" to label, "reason" to "a scripted reason"))
            return """{"model":"${FreeRoute.MODEL}","provider":"${FreeRoute.PROVIDER}","usage":{"cost":0},
                "choices":[{"finish_reason":"stop","message":{"content":${Json.write(content)}}}]}"""
        }
    }
}
