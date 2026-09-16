package org.ankivoice.provider

import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.grading.GradingSource
import org.ankivoice.core.grading.TurnGrading
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * AV-017's evaluation harness: the labeled corpus replayed through the **shipped**
 * graders, with no emulator.
 *
 * It changes neither grader. [org.ankivoice.core.grading.RuleGrader] runs first inside
 * [SemanticGrader] exactly as it does on a device, a rule match sends no request and
 * reserves no quota, and every AI reply passes through the shipped instruction, the
 * shipped route guard and the shipped two-key validation. The only substitution is the
 * socket, in [RecordingTransport] and [ReplayTransport].
 *
 * Three modes, chosen with `-Pav017.mode`:
 *
 * - `rule-only` (the default, and what CI runs): no credential is supplied, so the AI leg
 *   is never attempted. The rule path is measured in full; the AI path is recorded as not
 *   run, which is not the same as an abstention and is never scored as one.
 * - `record`: one live pass over the pinned free route, reading `OPENROUTER_API_KEY` from
 *   the environment and writing a transcript. This is the only mode that opens a socket.
 * - `replay`: that transcript served back with no network, which is the reproducible
 *   scoring run.
 *
 * Nothing here writes a review, and nothing here proposes a rating outside the shipped
 * `GradeLabel.automaticProposal` mapping. The measurement is advisory quality evidence
 * and gates nothing; explicit learner confirmation is unaffected by anything in this file.
 */
class GradingEvaluationTest {
    private val corpusFile = File(property("ankivoice.av017.corpus"))
    private val corpus = EvaluationCorpus.read(corpusFile)
    private val outputDirectory = File(property("ankivoice.av017.out")).apply { mkdirs() }
    private val mode = System.getProperty("ankivoice.av017.mode").orEmpty().ifEmpty { RULE_ONLY }
    private val dailyLimit = System.getProperty("ankivoice.av017.dailyLimit")?.toInt()
        ?: QuotaLedger.DEFAULT_DAILY_LIMIT

    /**
     * Which split this pass runs, so the tuning 20 can be inspected and adjusted against
     * without spending the held-out 40. `all` is the default.
     */
    private val split = System.getProperty("ankivoice.av017.split").orEmpty().ifEmpty { "all" }

    /** What AV-002's baseline cards offer, as AV-014's live run read them back. */
    private val permitted = listOf(1, 2, 3, 4)

    private class Settings(
        override var dailyLimit: Int,
        override var disclosureAcknowledged: Boolean = true,
    ) : ProviderSettings

    @Test
    fun `the corpus matches the table AV-017 fixed`() {
        val answers = corpus.answers
        assertEquals(60, answers.size, "AV-017 fixes the corpus at 60 answers")
        assertEquals(20, answers.count { it.split == CorpusAnswer.TUNING })
        assertEquals(40, answers.count { it.heldOut })
        val expected = mapOf(
            "paraphrase" to (3 to 6),
            "negation" to (3 to 5),
            "number-or-unit" to (3 to 5),
            "incomplete" to (3 to 6),
            "stt-mistake" to (3 to 6),
            "correct-short" to (3 to 6),
            "incorrect-short" to (2 to 6),
        )
        assertEquals(expected.keys, answers.map { it.category }.toSet())
        for ((category, counts) in expected) {
            val (tuning, held) = counts
            val inCategory = answers.filter { it.category == category }
            assertEquals(tuning, inCategory.count { it.split == CorpusAnswer.TUNING }, "$category tuning")
            assertEquals(held, inCategory.count { it.heldOut }, "$category held-out")
        }
        // A label is a human judgement written before any grader ran; nothing derives one.
        for (answer in answers.filter { it.scoreable }) {
            assertTrue(answer.label in setOf("correct", "partial", "incorrect"), answer.id)
        }
        // Synthetic corruption is not an acceptable substitute for any STT-mistake answer.
        for (answer in answers.filter { it.category == "stt-mistake" }) {
            assertTrue(
                answer.provenance in setOf("recorded", "live", "live-pending"),
                "${answer.id} is ${answer.provenance}; an STT-mistake answer is captured, never authored",
            )
        }
    }

    @Test
    fun `every corpus answer runs through the shipped graders`() {
        val transcript = File(outputDirectory, "transcript.jsonl")
        // Only the record pass consumes a real allowance; a replay must not add to its ledger.
        val ledgerFile = File(outputDirectory, "av017-quota-ledger-$mode.jsonl")
        val settings = Settings(dailyLimit)
        val ledger = QuotaLedger(ledgerFile)
        val diagnostics = Diagnostics(capacity = 4_000)

        val recording = if (mode == RECORD) RecordingTransport(transcript.also { it.writeText("") }) else null
        val replay = if (mode == REPLAY) ReplayTransport(transcript) else null
        val transport: HttpTransport = recording ?: replay ?: RefusingTransport
        val credentials = InMemoryCredentialStore(
            when (mode) {
                // The live pass is the only one that needs a key, and it never stores it.
                RECORD -> checkNotNull(System.getenv(KEY_VARIABLE)) {
                    "$KEY_VARIABLE is not set. Export it in the shell that starts Gradle and run the " +
                        "record pass with --no-daemon; the key is never written to a file."
                }
                REPLAY -> REPLAY_KEY
                else -> null
            },
        )
        val provider = GradingProvider(credentials, ledger, settings, transport, diagnostics)

        val scoreable = corpus.answers.filter { it.scoreable && (split == "all" || it.split == split) }
        check(scoreable.isNotEmpty()) { "no scoreable answer is in split '$split'" }
        val results = mutableListOf<Map<String, Any?>>()
        val sessions = mutableListOf<Map<String, Any?>>()
        var session = newSession(provider, 0, mode).also { sessions += describe(it) }
        var requestsInSession = 0

        for ((index, answer) in scoreable.withIndex()) {
            if (mode != RULE_ONLY && requestsInSession >= REQUESTS_PER_SESSION) {
                session = newSession(provider, index, mode).also { sessions += describe(it) }
                requestsInSession = 0
            }
            recording?.startAnswer(answer.id)
            replay?.startAnswer(answer.id)
            val request = GradingRequest(
                operationToken = OperationToken(session.id, turn = index + 1, sequence = 1),
                transcriptRevision = REVISION,
                context = corpus.context(answer),
            )
            val started = System.nanoTime()
            val grading = session.grader.suggest(request, permitted)
            // Microsecond resolution: a rule match is well under a millisecond, and
            // rounding it to 0 would hide the very thing the rule path is measured for.
            val wallMs = Math.round((System.nanoTime() - started) / 1_000.0) / 1_000.0
            val source = (grading as? TurnGrading.Graded)?.suggestion?.source
            if (source == GradingSource.AI) requestsInSession++
            results += describe(answer, grading, wallMs, replay)
        }

        replay?.let { assertTrue(it.exhausted(), "the transcript holds requests this run never replayed") }
        writeRun(results, sessions, settings, ledger, diagnostics)

        // A rule match must never have reached the transport: that is #16's whole point.
        val ruleMatched = results.filter { it["source"] == GradingSource.RULE.specName }
        for (entry in ruleMatched) {
            assertEquals(0, entry["aiRequests"], "${entry["id"]} matched a rule and still sent a request")
        }
    }

    private class Session(val id: String, val grader: SemanticGrader, val start: SessionStart)

    /** What the pre-session check said, so the report can show the route was verified. */
    private fun describe(session: Session): Map<String, Any?> = linkedMapOf(
        "sessionId" to session.id,
        "start" to when (val start = session.start) {
            is SessionStart.Ready -> "ready"
            is SessionStart.Unavailable -> start.cause.name
        },
        "endpoint" to (session.start as? SessionStart.Ready)?.endpointName,
        "sessionRemaining" to (session.start as? SessionStart.Ready)?.allowance?.sessionRemaining,
        "dailyRemaining" to (session.start as? SessionStart.Ready)?.allowance?.dailyRemaining,
    )

    /**
     * A fresh grading session, so the run stays inside the shipped 30-request session cap
     * rather than raising it. The pre-session price check is not a grading request.
     */
    private fun newSession(provider: GradingProvider, index: Int, mode: String): Session {
        val id = "av017-eval-${(index / REQUESTS_PER_SESSION) + 1}"
        val start = if (mode == RULE_ONLY) SessionStart.Unavailable(GradingUnavailable.NO_KEY) else provider.startSession(id)
        return Session(id, SemanticGrader(provider, id) { REVISION }, start)
    }

    private fun describe(
        answer: CorpusAnswer,
        grading: TurnGrading,
        wallMs: Double,
        replay: ReplayTransport?,
    ): Map<String, Any?> {
        val recorded = replay?.latencies?.get(answer.id).orEmpty()
        val suggestion = (grading as? TurnGrading.Graded)?.suggestion
        val source = suggestion?.source
        val aiRequests = if (source == GradingSource.RULE) 0 else recorded.size
        return linkedMapOf(
            "id" to answer.id,
            "split" to answer.split,
            "category" to answer.category,
            "fixtureCard" to answer.fixtureCard,
            "provenance" to answer.provenance,
            "humanLabel" to answer.label,
            "outcome" to when (grading) {
                is TurnGrading.Graded -> "graded"
                is TurnGrading.Ungraded -> "ungraded"
                TurnGrading.Superseded -> "superseded"
            },
            "source" to source?.specName,
            "graderLabel" to suggestion?.result?.label?.specName,
            "reason" to suggestion?.result?.reason,
            "proposedRating" to suggestion?.proposedRating,
            "requiresSelfGrade" to (suggestion?.requiresSelfGrade ?: true),
            "failure" to (grading as? TurnGrading.Ungraded)?.reason,
            "aiRequests" to aiRequests,
            // Replay reports the live pass's own network time; a rule match is local work.
            "latencyMs" to if (recorded.isEmpty()) wallMs else recorded.sum().toDouble(),
            "latencySource" to if (recorded.isEmpty()) "measured" else "recorded-live-pass",
        )
    }

    private fun writeRun(
        results: List<Map<String, Any?>>,
        sessions: List<Map<String, Any?>>,
        settings: Settings,
        ledger: QuotaLedger,
        diagnostics: Diagnostics,
    ) {
        val pending = corpus.answers.filter { !it.scoreable && (split == "all" || it.split == split) }
        val document = linkedMapOf(
            "schema_version" to 1,
            "card" to "AV-017",
            "mode" to mode,
            "split" to split,
            "corpus" to corpusFile.name,
            "corpus_sha256" to corpus.sha256,
            "pinned_route" to linkedMapOf(
                "model" to FreeRoute.MODEL,
                "provider" to FreeRoute.PROVIDER,
                "temperature" to FreeRoute.TEMPERATURE,
                "max_tokens" to FreeRoute.MAX_TOKENS,
                "deadline_ms" to SemanticGrader.DEADLINE_MS,
                "attempts" to SemanticGrader.ATTEMPTS,
            ),
            "quota" to linkedMapOf(
                "daily_limit" to settings.dailyLimit,
                "session_limit" to QuotaLedger.SESSION_LIMIT,
                "requests_per_session" to REQUESTS_PER_SESSION,
                "utc_day" to ledger.today(),
                "counts" to ledger.counts("av017-eval-1"),
                "reservations" to results.sumOf { it["aiRequests"] as Int },
            ),
            "ai_path_run" to (mode != RULE_ONLY),
            "sessions" to sessions,
            "scored" to results.size,
            "awaiting_live_capture" to pending.map { it.id },
            "diagnostics" to diagnostics.counts(),
            "answers" to results,
        )
        val suffix = if (split == "all") mode else "$mode-$split"
        File(outputDirectory, "run-$suffix.json").writeText(Json.write(document) + "\n")
    }

    /** No key, no socket: the rule-only pass never reaches a transport at all. */
    private object RefusingTransport : HttpTransport {
        override fun get(url: String, key: String, timeoutMs: Int): HttpResult =
            error("the rule-only pass must not open a connection")

        override fun post(url: String, key: String, body: String, timeoutMs: Int): HttpResult =
            error("the rule-only pass must not open a connection")
    }

    private fun property(name: String): String =
        checkNotNull(System.getProperty(name)) { "$name is not set; see docs/testing/av017/runbook.md" }

    private companion object {
        const val RULE_ONLY = "rule-only"
        const val RECORD = "record"
        const val REPLAY = "replay"
        const val KEY_VARIABLE = "OPENROUTER_API_KEY"

        /** The transcript never changes during an evaluation, so every reply is current. */
        const val REVISION = 1

        /** Headroom under the shipped 30-request session cap for #18's one retry. */
        const val REQUESTS_PER_SESSION = 14

        /**
         * Replay needs a key-shaped value to pass [CredentialPolicy], and must never carry
         * a real one: no request leaves the process, and nothing is written from it.
         */
        const val REPLAY_KEY = "sk-or-v1-replay0000000000000000000000000000"
    }
}
