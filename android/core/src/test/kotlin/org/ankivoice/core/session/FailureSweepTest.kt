package org.ankivoice.core.session

import org.ankivoice.core.contracts.ALL_FAILURE_MODES
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.FailureMode
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.ReviewIntent
import org.ankivoice.core.contracts.ReviewState
import org.ankivoice.core.contracts.ReviewWriterFailure
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.SpeechOutputFailure
import org.ankivoice.core.fakes.FakeGrader
import org.ankivoice.core.fakes.FakeSpeechInput
import org.ankivoice.core.fakes.FakeSpeechOutput
import org.ankivoice.core.fakes.ReviewSource
import org.ankivoice.core.fakes.WriteAnomaly
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The failure sweep of `tools/av007_scenarios.py`: one scripted session per enumerated
 * failure mode, all 34 of them.
 *
 * Every case asserts the same two invariants the Python sweep records — the session
 * halts into a state the learner can act from, and a fault never becomes a rating or a
 * review. A fault is a property of the transport or provider, never evidence about the
 * learner's answer.
 */
class FailureSweepTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("allFailureModes")
    fun `every enumerated failure halts without inventing a rating`(mode: FailureMode) {
        when (mode) {
            is CardProviderFailure -> providerFailure(mode)
            is SpeechOutputFailure -> speechOutputFailure(mode)
            is SpeechInputFailure -> speechInputFailure(mode)
            is GraderFailure -> graderFailure(mode)
            is ReviewWriterFailure -> writerFailure(mode)
        }
    }

    @Test
    fun `the sweep covers the whole taxonomy exactly once`() {
        assertEquals(34, ALL_FAILURE_MODES.size)
        assertEquals(ALL_FAILURE_MODES.size, ALL_FAILURE_MODES.toSet().size)
    }

    /** Provider faults: the offer fails, or the pre-commit read does. */
    private fun providerFailure(mode: CardProviderFailure) {
        val harness = build()
        val session = harness.session
        val failure = Failure(mode, "scripted ${mode.specName}")

        if (mode == CardProviderFailure.CARD_NOT_FOUND) {
            proposeSuggested(harness, askListenGrade(harness))
            harness.provider.readCardScript.addLast(failure)
            val outcome = confirmAndCommit(session)
            assertEquals(ReviewState.FAILED, outcome.state)
            assertEquals(ReviewWriterFailure.PRECOMMIT_READ_FAILED, outcome.failure?.mode)
            assertEquals(mode, outcome.failure?.cause?.mode)
            assertFalse(outcome.writeAttempted, "a failed pre-commit read never dispatches")
            assertTrue(harness.wroteNothing)
            assertTrue(session.halted)
            return
        }

        harness.provider.nextCardScript.addLast(failure)
        val halt = checkNotNull(session.offerCard().haltOrNull) { "$mode must halt the session" }
        assertEquals(mode.specName, halt.reason)
        assertEquals(expectedBinding(mode), session.bindingState, mode.specName)
        if (mode == CardProviderFailure.UNSUPPORTED_NOTE_TYPE) {
            assertEquals(SessionState.UNSUPPORTED, session.state, "an unusable card has its own state")
        }
        assertNull(session.intent, "a provider fault proposes no rating")
        assertTrue(harness.wroteNothing)
    }

    /** Playback faults: the question never reached the learner, so nothing may be graded. */
    private fun speechOutputFailure(mode: SpeechOutputFailure) {
        val harness = build()
        harness.speechOutput.script.addLast(FakeSpeechOutput.Fail(Failure(mode, "scripted ${mode.specName}")))
        val halt = checkNotNull(askListenGrade(harness).haltOrNull) { "$mode must halt the session" }

        assertEquals(mode.specName, halt.reason)
        assertEquals(SessionState.PAUSED, harness.session.state)
        assertTrue(halt.resumable)
        assertNull(harness.session.intent)
        assertTrue(harness.grader.seen.isEmpty(), "an unheard question is never graded")
        assertTrue(harness.wroteNothing)
    }

    /** Capture faults: never an incorrect answer, and never a transcript. */
    private fun speechInputFailure(mode: SpeechInputFailure) {
        val harness = build(
            transcripts = listOf(FakeSpeechInput.Fail(Failure(mode, "scripted ${mode.specName}"))),
        )
        val session = harness.session
        val halt = checkNotNull(askListenGrade(harness).haltOrNull) { "$mode must halt the session" }

        assertEquals(mode.specName, halt.reason)
        assertEquals(SessionState.PAUSED, session.state)
        assertTrue(halt.resumable)
        assertFalse(halt.reconciliationRequired)
        assertFalse(checkNotNull(session.answer).gradable, "a fault is never a gradable transcript")
        assertTrue(harness.grader.seen.isEmpty())
        assertNull(session.intent)
        assertTrue(harness.wroteNothing)
        // The card is preserved and an explicit retry is offered.
        assertTrue(org.ankivoice.core.answer.AnswerRecovery.TRY_AGAIN in session.recoveryOptions)
    }

    /** Grading faults: the transcript survives, and the learner supplies the rating. */
    private fun graderFailure(mode: GraderFailure) {
        val harness = build(
            grades = listOf(FakeGrader.Answer(Failure(mode, "scripted ${mode.specName}"))),
        )
        val session = harness.session
        val halt = checkNotNull(askListenGrade(harness).haltOrNull) { "$mode must halt the session" }

        assertEquals(mode.specName, halt.reason)
        assertEquals(SessionState.PAUSED, session.state)
        assertTrue(halt.resumable)
        assertNull(session.suggestion, "a fault leaves no advisory label behind")
        assertNull(session.intent)
        assertTrue(harness.wroteNothing)
        assertTrue(checkNotNull(session.answer).gradable, "the learner's answer is unaffected")
        assertTrue(org.ankivoice.core.answer.AnswerRecovery.SELF_GRADE in session.recoveryOptions)
    }

    /** Write refusals and rejections: at most one dispatch, and never a silent Again. */
    private fun writerFailure(mode: ReviewWriterFailure) {
        val harness = build()
        val session = harness.session
        val turn = askListenGrade(harness)
        val suggested = checkNotNull(turn.proposedRating(session))

        val outcome = when (mode) {
            // Constructed directly, the way AV-004's raw-answer path bypassed the session guard.
            ReviewWriterFailure.RATING_REJECTED ->
                session.writer.commit(ReviewIntent(checkNotNull(session.card), 5, 12_345))
            ReviewWriterFailure.INVALID_REVIEW_TIME ->
                session.writer.commit(ReviewIntent(checkNotNull(session.card), suggested, -1))
            ReviewWriterFailure.CONFIRMATION_REQUIRED ->
                session.writer.commit(ReviewIntent(checkNotNull(session.card), suggested, 12_345))
            ReviewWriterFailure.STALE_IDENTITY -> {
                proposeSuggested(harness, turn)
                harness.collection.nativeAnswer(checkNotNull(session.card).identity.cardId)
                confirmAndCommit(session)
            }
            ReviewWriterFailure.PRECOMMIT_READ_FAILED -> {
                proposeSuggested(harness, turn)
                harness.provider.readCardScript.addLast(
                    Failure(CardProviderFailure.PACKAGE_UNAVAILABLE, "AnkiDroid disabled"),
                )
                confirmAndCommit(session)
            }
            ReviewWriterFailure.WRITE_REJECTED -> {
                proposeSuggested(harness, turn)
                harness.transport.anomalies.addLast(WriteAnomaly.REJECT_WITH_ZERO)
                confirmAndCommit(session)
            }
        }

        assertEquals(ReviewState.FAILED, outcome.state, mode.specName)
        assertEquals(mode, outcome.failure?.mode, mode.specName)
        val dispatches = if (mode == ReviewWriterFailure.WRITE_REJECTED) 1 else 0
        assertEquals(dispatches, harness.transport.calls.size, mode.specName)
        assertEquals(dispatches == 1, outcome.writeAttempted, mode.specName)
        // A competing native writer may have recorded one; this caller never did.
        assertTrue(harness.collection.reviews.none { it.source == ReviewSource.API })
    }

    private fun expectedBinding(mode: CardProviderFailure): BindingSessionState = when (mode) {
        CardProviderFailure.UNSUPPORTED_NOTE_TYPE,
        CardProviderFailure.COLLECTION_CHANGED,
        CardProviderFailure.DECK_MISSING,
        -> BindingSessionState.STOPPED
        else -> BindingSessionState.PAUSED
    }

    private companion object {
        @JvmStatic
        fun allFailureModes(): List<FailureMode> = ALL_FAILURE_MODES
    }
}
