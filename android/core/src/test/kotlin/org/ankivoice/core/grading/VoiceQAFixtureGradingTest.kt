package org.ankivoice.core.grading

import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.GradingContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every expected case in fixtures/voiceqa/note-type.json, read from that file. The build
 * passes its path (see core/build.gradle.kts), so a fixture edit reruns this test.
 */
class VoiceQAFixtureGradingTest {
    private class Case(val example: String, val fields: Map<String, String>, val answer: String, val expected: String) {
        val context: GradingContext
            get() = GradingContext(
                prompt = fields.getValue("Prompt"),
                referenceAnswer = fields.getValue("ReferenceAnswer"),
                requiredConcepts = fields["RequiredConcepts"].orEmpty().lines(),
                acceptedAnswers = fields["AcceptedAnswers"].orEmpty().lines(),
                learnerAnswer = answer,
                language = fields["Language"].orEmpty(),
            )

        override fun toString() = "$example: \"$answer\" (fixture expects $expected)"
    }

    private val cases: List<Case> = run {
        val path = checkNotNull(System.getProperty("ankivoice.voiceqaNoteType")) { "ankivoice.voiceqaNoteType is not set" }
        val root = MiniJson.parse(File(path).readText()) as Map<*, *>
        (root["examples"] as List<*>).flatMap { entry ->
            val example = entry as Map<*, *>
            val fields = (example["fields"] as Map<*, *>).entries.associate { (key, value) -> key as String to value as String }
            (example["expected_cases"] as List<*>).map { item ->
                val case = item as Map<*, *>
                Case(example["id"] as String, fields, case["answer"] as String, case["result"] as String)
            }
        }
    }

    @Test
    fun `the fixture holds nine expected cases across four examples`() {
        assertEquals(9, cases.size)
        assertEquals(4, cases.map { it.example }.distinct().size)
        assertTrue(cases.any { it.expected == "incorrect" } && cases.any { it.expected == "partial" })
    }

    @Test
    fun `the rules label exactly the two cases they can prove`() {
        val labelled = cases.mapNotNull { case -> RuleGrader.grade(case.context)?.let { case.answer to it.reason } }
        assertEquals(
            listOf(
                "Green, blue, red." to "Exact match with the reference answer.",
                "Five." to "Exact match with accepted answer 1.",
            ),
            labelled,
        )
    }

    @Test
    fun `the rules never contradict the fixture`() {
        for (case in cases) {
            val result = RuleGrader.grade(case.context) ?: continue
            assertEquals(GradeLabel.CORRECT, result.label, case.toString())
            assertEquals("correct", case.expected, case.toString())
            assertEquals(3, result.proposedRating(listOf(1, 2, 3, 4)), case.toString())
        }
    }

    @Test
    fun `incorrect and partial cases get no rule-based label`() {
        val unlabelled = cases.filter { RuleGrader.grade(it.context) == null }
        assertEquals(7, unlabelled.size)
        for (case in cases.filter { it.expected != "correct" }) {
            assertTrue(case in unlabelled, case.toString())
        }
    }
}
