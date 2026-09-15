package org.ankivoice.core.contracts

import org.ankivoice.core.fakes.FakeGrader
import org.ankivoice.core.fakes.demoCollection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** The question side never exposes the reference answer; Extra never reaches the grader. */
class QuestionAndGradingContextTest {
    private val cards: List<ScheduledCard> = demoCollection().let { collection -> collection.order.map(collection::scheduled) }

    /** A card whose every non-Prompt field is replaced. */
    private fun ScheduledCard.withAnswerKey(tag: String) = copy(
        fields = fields.copy(
            referenceAnswer = "reference $tag",
            requiredConcepts = listOf("concept $tag"),
            acceptedAnswers = listOf("accepted $tag"),
            extra = "extra $tag",
        ),
    )

    @Test
    fun `question audio carries the prompt and nothing else`() {
        for (card in cards) {
            val utterance = questionUtterance(card, "en-US")
            assertEquals(UtterancePurpose.QUESTION, utterance.purpose)
            assertEquals(card.fields.prompt, utterance.text)
            assertFalse(utterance.text.lowercase().contains(card.fields.referenceAnswer.lowercase()))
            assertFalse(utterance.text.lowercase().contains(card.fields.extra.lowercase()))
        }
    }

    @Test
    fun `question audio is built from Prompt only`() {
        val card = cards.first()
        assertEquals(questionUtterance(card, "en-US"), questionUtterance(card.withAnswerKey("changed"), "en-US"))
        val reworded = card.copy(fields = card.fields.copy(prompt = "A different prompt"))
        assertNotEquals(questionUtterance(card, "en-US"), questionUtterance(reworded, "en-US"))
    }

    @Test
    fun `reveal and elaboration are separate purposes`() {
        val card = cards.first()
        assertEquals(Utterance(UtterancePurpose.REVEAL, "Five blocks.", "en-US"), revealUtterance(card, "de-DE"))
        assertEquals(
            Utterance(UtterancePurpose.ELABORATION, "Three plus two equals five.", "en-US"),
            elaborationUtterance(card, "de-DE"),
        )
        assertNull(elaborationUtterance(card.copy(fields = card.fields.copy(extra = "")), "en-US"))
    }

    @Test
    fun `the card language falls back to the session language`() {
        val card = cards.first()
        assertEquals("en-US", cardLanguage(card, "de-DE"))
        val unlabelled = card.copy(fields = card.fields.copy(language = ""))
        assertEquals("de-DE", cardLanguage(unlabelled, "de-DE"))
        assertEquals("de-DE", questionUtterance(unlabelled, "de-DE").language)
        assertEquals("de-DE", gradingContext(unlabelled, "Five", "de-DE").language)
    }

    @Test
    fun `the grading context is closed over exactly six fields`() {
        val expected = listOf("prompt", "referenceAnswer", "requiredConcepts", "acceptedAnswers", "learnerAnswer", "language")
        assertEquals(expected, GradingContext::class.primaryConstructor!!.parameters.map { it.name })
        assertEquals(expected.toSet(), GradingContext::class.memberProperties.map { it.name }.toSet())
    }

    @Test
    fun `Extra is never part of the grading context`() {
        for (card in cards) {
            val context = gradingContext(card, "Five blocks.", "en-US")
            assertFalse(context.toString().contains(card.fields.extra), card.fields.extra)
            assertEquals("Five blocks.", context.learnerAnswer)
            assertEquals(card.fields.prompt, context.prompt)
            assertEquals(card.fields.referenceAnswer, context.referenceAnswer)
            assertEquals(card.fields.requiredConcepts, context.requiredConcepts)
            assertEquals(card.fields.acceptedAnswers, context.acceptedAnswers)
            val otherExtra = card.copy(fields = card.fields.copy(extra = "an unrelated elaboration"))
            assertEquals(context, gradingContext(otherExtra, "Five blocks.", "en-US"))
        }
    }

    @Test
    fun `the fake grader sees only the grading context`() {
        val grader = FakeGrader(FakeGrader.Answer(GradingResult(GradeLabel.CORRECT, "ok")))
        val card = cards.first()
        val token = OperationToken("session", 1, 3)
        grader.grade(GradingRequest(token, 1, gradingContext(card, "Five", "en-US")))
        assertEquals(1, grader.seen.size)
        assertFalse(grader.seen.single().toString().contains(card.fields.extra))
    }
}
