package org.ankivoice.core.grading

import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.ConfirmationSource
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.GradingContext
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.RatingConfirmation
import org.ankivoice.core.contracts.ReviewIntent
import org.ankivoice.core.fakes.demoCollection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AV-016's binding: a label belongs to one transcript revision, and proposes at most a rating. */
class SuggestionsTest {
    private val card = demoCollection().scheduled(1_789_414_083_106)
    private val token = OperationToken("session-a", turn = 1, sequence = 4)
    private val permitted = listOf(1, 2, 3, 4)

    private fun request(revision: Int = 1) = GradingRequest(
        token,
        revision,
        GradingContext("Name the colours.", "Green, blue, red.", emptyList(), emptyList(), "Green blue red", "en-US"),
    )

    private fun bind(
        label: GradeLabel,
        revision: Int = 1,
        current: Int = 1,
        ratings: List<Int> = permitted,
        source: GradingSource = GradingSource.AI,
    ) = bindSuggestion(
        GradingReply(request(revision), GradingResult(label, "because of what was said")),
        source,
        ratings,
        current,
    )

    @Test
    fun `correct proposes Good and incorrect proposes Again`() {
        val correct = assertGraded(bind(GradeLabel.CORRECT))
        assertEquals(3, correct.proposedRating)
        assertFalse(correct.requiresSelfGrade)
        assertEquals(1, assertGraded(bind(GradeLabel.INCORRECT)).proposedRating)
    }

    @Test
    fun `partial and uncertain propose nothing and ask for a self-grade`() {
        for (label in listOf(GradeLabel.PARTIAL, GradeLabel.UNCERTAIN)) {
            val suggestion = assertGraded(bind(label))
            assertNull(suggestion.proposedRating, label.specName)
            assertTrue(suggestion.requiresSelfGrade, label.specName)
            // The label and its reason are still shown; only the rating is the learner's.
            assertEquals(label, suggestion.result.label)
        }
    }

    @Test
    fun `a proposal the card does not permit is dropped, never substituted`() {
        val suggestion = assertGraded(bind(GradeLabel.CORRECT, ratings = listOf(1, 2)))
        assertNull(suggestion.proposedRating)
        assertTrue(suggestion.requiresSelfGrade)
        assertEquals(GradeLabel.CORRECT, suggestion.result.label)
    }

    @Test
    fun `an empty permitted list proposes nothing at all`() {
        for (label in GradeLabel.entries) {
            assertNull(assertGraded(bind(label, ratings = emptyList())).proposedRating, label.specName)
        }
    }

    @Test
    fun `a suggestion is bound to the revision it graded`() {
        val suggestion = assertGraded(bind(GradeLabel.CORRECT, revision = 2, current = 2))
        assertEquals(2, suggestion.transcriptRevision)
        assertTrue(suggestion.appliesTo(2))
        assertFalse(suggestion.appliesTo(3))
    }

    @Test
    fun `a reply for a superseded revision is discarded, not displayed`() {
        assertEquals(TurnGrading.Superseded, bind(GradeLabel.CORRECT, revision = 1, current = 2))
    }

    @Test
    fun `a failure keeps the card and never becomes a label or a rating`() {
        val failure = Failure(GraderFailure.GRADER_TIMEOUT, "The grader did not answer in time.")
        val ungraded = bindSuggestion(GradingReply(request(), failure), GradingSource.AI, permitted, 1)
        assertEquals(TurnGrading.Ungraded("The grader did not answer in time.", failure), ungraded)
    }

    @Test
    fun `a rule label and an AI label carry their own source`() {
        assertEquals(GradingSource.RULE, assertGraded(bind(GradeLabel.CORRECT, source = GradingSource.RULE)).source)
        assertEquals(GradingSource.AI, assertGraded(bind(GradeLabel.CORRECT)).source)
        assertEquals(listOf("rule", "ai"), GradingSource.entries.map { it.specName })
    }

    @Test
    fun `editing the transcript invalidates the suggestion and any pending confirmation`() {
        val suggestion = assertGraded(bind(GradeLabel.CORRECT, revision = 1, current = 1))
        val intent = ReviewIntent(card, checkNotNull(suggestion.proposedRating), 12_345, token, transcriptRevision = 1)
        assertTrue(intent.confirm(confirmation(revision = 1)))
        // The learner edits the transcript: revision 2 supersedes both.
        assertFalse(suggestion.appliesTo(2))
        assertEquals(TurnGrading.Superseded, bind(GradeLabel.CORRECT, revision = 1, current = 2))
        assertFalse(intent.confirm(confirmation(revision = 2)))
        assertFalse(intent.hasConfirmation())
    }

    private fun confirmation(revision: Int) = RatingConfirmation(
        token,
        card.identity,
        rating = 3,
        transcriptRevision = revision,
        source = ConfirmationSource.TOUCH,
        confidence = Confidence.ABSENT,
    )

    private fun assertGraded(grading: TurnGrading): GradingSuggestion {
        assertTrue(grading is TurnGrading.Graded, "expected a label, got $grading")
        return (grading as TurnGrading.Graded).suggestion
    }
}
