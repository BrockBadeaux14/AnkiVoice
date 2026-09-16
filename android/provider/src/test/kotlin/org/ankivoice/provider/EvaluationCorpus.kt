package org.ankivoice.provider

import org.ankivoice.core.contracts.GradingContext
import java.io.File
import java.security.MessageDigest

/**
 * AV-017's labeled corpus, read from `fixtures/grading/av017-corpus.json` exactly as it
 * is committed.
 *
 * The corpus is the source of the split: nothing here re-splits, re-labels or reorders
 * it. An answer still awaiting its live capture carries no text and no label, and is
 * skipped rather than filled in.
 */
internal class CorpusAnswer(
    val id: String,
    val split: String,
    val category: String,
    val fixtureCard: String,
    val exampleId: String,
    val answer: String?,
    val label: String?,
    val provenance: String,
) {
    /** True once the answer has text and a human label, so a grader may be run on it. */
    val scoreable: Boolean get() = answer != null && label != null

    val heldOut: Boolean get() = split == HELD_OUT

    companion object {
        const val TUNING: String = "tuning"
        const val HELD_OUT: String = "held-out"
    }
}

internal class EvaluationCorpus private constructor(
    val language: String,
    val sha256: String,
    private val examples: Map<String, Map<String, String>>,
    val answers: List<CorpusAnswer>,
) {
    /**
     * The context the app builds for this answer.
     *
     * The multi-line fields are split the way `AnkiDroidCardProvider` splits them, so the
     * graders see what a real card produces rather than a harness-specific shape.
     */
    fun context(answer: CorpusAnswer): GradingContext {
        val fields = examples[answer.exampleId] ?: error("${answer.id} names unknown example ${answer.exampleId}")
        return GradingContext(
            prompt = fields.getValue("Prompt"),
            referenceAnswer = fields.getValue("ReferenceAnswer"),
            requiredConcepts = lines(fields["RequiredConcepts"]),
            acceptedAnswers = lines(fields["AcceptedAnswers"]),
            learnerAnswer = checkNotNull(answer.answer) { "${answer.id} has no captured answer" },
            language = fields["Language"].orEmpty().ifEmpty { language },
        )
    }

    private fun lines(field: String?): List<String> =
        field.orEmpty().lines().map(String::trim).filter(String::isNotEmpty)

    companion object {
        fun read(file: File): EvaluationCorpus {
            val bytes = file.readBytes()
            val root = Json.parse(String(bytes, Charsets.UTF_8)).asObject()
                ?: error("${file.name} is not a JSON object")
            val examples = root.child("examples").asObject().orEmpty().entries.associate { (id, fields) ->
                id as String to fields.asObject().orEmpty().entries
                    .associate { (name, value) -> name as String to value.asText().orEmpty() }
            }
            val answers = root.child("answers").asList().orEmpty().map { entry ->
                val item = entry.asObject() ?: error("an answer is not a JSON object")
                CorpusAnswer(
                    id = item.child("id").asText() ?: error("an answer has no id"),
                    split = item.child("split").asText() ?: error("an answer has no split"),
                    category = item.child("category").asText() ?: error("an answer has no category"),
                    fixtureCard = item.child("fixture_card").asText() ?: error("an answer has no fixture card"),
                    exampleId = item.child("example_id").asText() ?: error("an answer has no example"),
                    answer = item.child("answer").asText(),
                    label = item.child("label").asText(),
                    provenance = item.child("provenance").asObject()?.child("kind").asText() ?: "unknown",
                )
            }
            return EvaluationCorpus(
                language = root.child("language").asText() ?: "en-US",
                sha256 = sha256(bytes),
                examples = examples,
                answers = answers,
            )
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
    }
}
