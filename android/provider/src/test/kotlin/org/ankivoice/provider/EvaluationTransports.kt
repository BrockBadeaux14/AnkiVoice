package org.ankivoice.provider

import java.io.File

/**
 * AV-017's two transports. Neither changes a grader: they sit under [GradingProvider] in
 * the one place the app opens a socket, so the rule policy (#16), the instruction, the
 * reply validation and the label policy (#18) are the shipped ones in both modes.
 *
 * The recording transport is the only one that reaches the network, and it is used once
 * per frozen configuration. The replay transport reproduces that pass with no network at
 * all, and refuses to serve a reply whose request no longer matches the one that was
 * recorded — so an instruction or route change fails the replay instead of quietly
 * re-scoring against stale evidence.
 */
internal class TranscriptLine(
    val answerId: String,
    val attempt: Int,
    val kind: String,
    val requestSha256: String,
    val status: Int,
    val body: String,
    val elapsedMs: Long,
) {
    fun write(): String = Json.write(
        linkedMapOf(
            "answerId" to answerId,
            "attempt" to attempt,
            "kind" to kind,
            "requestSha256" to requestSha256,
            "status" to status,
            "body" to body,
            "elapsedMs" to elapsedMs,
        ),
    )

    companion object {
        const val PRICE_CHECK: String = "price-check"
        const val COMPLETION: String = "completion"
        const val TIMEOUT: Int = -1
        const val REFUSED: Int = -2

        fun read(line: String): TranscriptLine {
            val item = Json.parse(line).asObject() ?: error("a transcript line is not a JSON object")
            return TranscriptLine(
                answerId = item.child("answerId").asText().orEmpty(),
                attempt = (item.child("attempt") as? JsonText)?.value?.toInt() ?: 0,
                kind = item.child("kind").asText().orEmpty(),
                requestSha256 = item.child("requestSha256").asText().orEmpty(),
                status = (item.child("status") as? JsonText)?.value?.toInt() ?: 0,
                body = item.child("body").asText().orEmpty(),
                elapsedMs = (item.child("elapsedMs") as? JsonText)?.value?.toLong() ?: 0L,
            )
        }

        fun result(status: Int, body: String): HttpResult = when (status) {
            TIMEOUT -> HttpResult.Timeout
            REFUSED -> HttpResult.Refused(body)
            else -> HttpResult.Response(status, body)
        }

        fun status(result: HttpResult): Pair<Int, String> = when (result) {
            is HttpResult.Response -> result.status to result.body
            HttpResult.Timeout -> TIMEOUT to ""
            is HttpResult.Refused -> REFUSED to result.reason
        }
    }
}

/**
 * The live pass: the shipped [HttpsUrlTransport], with every request and reply written to
 * a transcript so the scoring run can be repeated offline.
 *
 * The key is a parameter of the call, never of the record: nothing written here carries
 * it, and the request body is stored only as a digest.
 */
internal class RecordingTransport(
    private val transcript: File,
    private val inner: HttpTransport = HttpsUrlTransport(),
    private val elapsed: () -> Long = System::nanoTime,
) : HttpTransport {
    /** Set by the harness before each answer so the transcript can be replayed in order. */
    var answerId: String = ""
    private var attempt: Int = 0

    fun startAnswer(id: String) {
        answerId = id
        attempt = 0
    }

    override fun get(url: String, key: String, timeoutMs: Int): HttpResult =
        record(TranscriptLine.PRICE_CHECK, "", timeoutMs) { inner.get(url, key, timeoutMs) }

    override fun post(url: String, key: String, body: String, timeoutMs: Int): HttpResult =
        record(TranscriptLine.COMPLETION, body, timeoutMs) { inner.post(url, key, body, timeoutMs) }

    private fun record(kind: String, body: String, timeoutMs: Int, call: () -> HttpResult): HttpResult {
        check(timeoutMs > 0) { "a recorded attempt must carry the caller's deadline" }
        val started = elapsed()
        val result = call()
        val took = (elapsed() - started) / 1_000_000
        val (status, text) = TranscriptLine.status(result)
        val line = TranscriptLine(
            answerId = if (kind == TranscriptLine.PRICE_CHECK) "" else answerId,
            attempt = if (kind == TranscriptLine.PRICE_CHECK) 0 else ++attempt,
            kind = kind,
            requestSha256 = EvaluationCorpus.sha256(body),
            status = status,
            body = text,
            elapsedMs = took,
        )
        transcript.appendText(line.write() + "\n")
        return result
    }
}

/**
 * The offline pass: the recorded replies, served in the order they were recorded, and
 * only to a request that hashes to the one that produced them.
 */
internal class ReplayTransport(transcript: File) : HttpTransport {
    private val priceChecks = ArrayDeque<TranscriptLine>()
    private val completions = LinkedHashMap<String, ArrayDeque<TranscriptLine>>()

    /** Replayed request latencies, by answer, for the measurements the live pass produced. */
    val latencies = LinkedHashMap<String, MutableList<Long>>()

    var answerId: String = ""

    init {
        transcript.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val entry = TranscriptLine.read(line)
            when (entry.kind) {
                TranscriptLine.PRICE_CHECK -> priceChecks.addLast(entry)
                TranscriptLine.COMPLETION -> completions.getOrPut(entry.answerId) { ArrayDeque() }.addLast(entry)
                else -> error("unknown transcript kind ${entry.kind}")
            }
        }
    }

    fun startAnswer(id: String) {
        answerId = id
    }

    override fun get(url: String, key: String, timeoutMs: Int): HttpResult {
        val entry = priceChecks.removeFirstOrNull() ?: error("the transcript holds no further price check")
        return TranscriptLine.result(entry.status, entry.body)
    }

    override fun post(url: String, key: String, body: String, timeoutMs: Int): HttpResult {
        val queue = completions[answerId] ?: error("the transcript holds no request for $answerId")
        val entry = queue.removeFirstOrNull() ?: error("the transcript holds no further request for $answerId")
        val digest = EvaluationCorpus.sha256(body)
        check(entry.requestSha256 == digest) {
            "$answerId now builds a different request than the one recorded: " +
                "expected ${entry.requestSha256}, built $digest. Re-record the live pass; " +
                "the recorded replies no longer describe what this build sends."
        }
        latencies.getOrPut(answerId) { mutableListOf() } += entry.elapsedMs
        return TranscriptLine.result(entry.status, entry.body)
    }

    /** Every recorded request was replayed; nothing was left unscored. */
    fun exhausted(): Boolean = completions.values.all { it.isEmpty() }
}
