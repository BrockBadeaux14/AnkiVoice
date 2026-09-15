package org.ankivoice.core.fakes

import org.ankivoice.core.contracts.CaptureEvent
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.CardState
import org.ankivoice.core.contracts.Confidence
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.GraderFailure
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.contracts.GradingResult
import org.ankivoice.core.contracts.OperationToken
import org.ankivoice.core.contracts.PlaybackResult
import org.ankivoice.core.contracts.RawAcknowledgement
import org.ankivoice.core.contracts.SpeechInputFailure
import org.ankivoice.core.contracts.SpeechOutputFailure
import org.ankivoice.core.contracts.TranscriptKind
import org.ankivoice.core.contracts.VOICEQA_MODEL
import org.ankivoice.core.contracts.gradingContext
import org.ankivoice.core.contracts.questionUtterance
import org.ankivoice.core.contracts.revealUtterance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The fakes behave like tools/av007_fakes.py. Expected values come from the Python reference. */
class FakesTest {
    private val blocks = 1_789_414_083_106
    private val sequence = 1_789_414_083_109
    private val token = OperationToken("session", 1, 1)

    @Test
    fun `the demo collection holds the two AV-002 fixture cards`() {
        val collection = demoCollection()
        assertEquals(listOf(blocks, sequence), collection.order)
        for (card in collection.cards.values) {
            assertEquals(DEMO_DECK_ID, card.identity.deckId)
            assertEquals(card.identity.cardId, card.identity.noteId)
            assertEquals(0, card.identity.ordinal)
            assertEquals(VOICEQA_MODEL, card.identity.model)
            assertEquals("en-US", card.fields.language)
            assertEquals(listOf(1, 2, 3, 4), card.permittedRatings)
        }
        val first = collection.scheduled(blocks)
        assertEquals("Five blocks.", first.fields.referenceAnswer)
        assertEquals(listOf("Five", "5", "There are five blocks"), first.fields.acceptedAnswers)
        assertEquals(CardState(0, 0, 0, 1, 0), first.state)
        assertEquals(CardState(3, 2, 2, 90, 30, 1_789_400_000), collection.scheduled(sequence).state)
        assertEquals(listOf("Green first", "Blue second", "Red last"), collection.scheduled(sequence).fields.requiredConcepts)
    }

    @Test
    fun `stand-in scheduling matches the Python reference, ties rounding to even`() {
        // (card, rating) -> (due, interval) from tools/av007_fakes.py.
        val expected = mapOf(
            (blocks to 1) to (2L to 0), (blocks to 2) to (3L to 1),
            (blocks to 3) to (4L to 2), (blocks to 4) to (5L to 3),
            (sequence to 1) to (91L to 0), (sequence to 2) to (127L to 36),
            (sequence to 3) to (166L to 75), (sequence to 4) to (189L to 98),
        )
        for ((key, value) in expected) {
            val (cardId, rating) = key
            val collection = demoCollection()
            val before = collection.scheduled(cardId).state
            collection.applyReview(cardId, rating, 98_765)
            val after = collection.scheduled(cardId).state
            assertEquals(CardState(before.reps + 1, 2, 2, value.first, value.second, 1_789_414_083), after, "$key")
            assertEquals(listOf(RecordedReview(cardId, rating, ReviewSource.API, 60_000)), collection.reviews)
            assertEquals(listOf(if (cardId == blocks) sequence else blocks), collection.order)
        }
        val doubled = demoCollection()
        doubled.applyReview(sequence, 4, 1_000, repetitions = 2)
        assertEquals(CardState(5, 2, 2, 508, 318, 1_789_414_143), doubled.scheduled(sequence).state)
        assertEquals(2, doubled.reviews.size)
    }

    @Test
    fun `the review log records the capped time and the writer`() {
        val collection = FakeCollection(demoCollection().cards.values.toList(), maxReviewTimeMs = 0)
        collection.applyReview(blocks, 3, 7_500)
        collection.nativeAnswer(sequence)
        assertEquals(
            listOf(RecordedReview(blocks, 3, ReviewSource.API, 0), RecordedReview(sequence, 3, ReviewSource.NATIVE, 0)),
            collection.reviews,
        )
        collection.mutateState(blocks) { it.copy(queue = -1) }
        assertEquals(-1, collection.scheduled(blocks).state.queue)
    }

    @Test
    fun `each write anomaly returns what AV-004 observed or the contract must survive`() {
        data class Expect(val ack: RawAcknowledgement, val repsDelta: Int, val reviews: Int, val queue: Int? = null)
        val expected = mapOf(
            null to Expect(RawAcknowledgement.UpdateCount(1), 1, 1),
            WriteAnomaly.ACKNOWLEDGE_WITHOUT_WRITE to Expect(RawAcknowledgement.UpdateCount(1), 0, 0),
            WriteAnomaly.REJECT_WITH_ZERO to Expect(RawAcknowledgement.UpdateCount(0), 0, 0),
            WriteAnomaly.NULL_RESPONSE to Expect(RawAcknowledgement.NullResponse, 0, 0),
            WriteAnomaly.ERROR_RESPONSE to Expect(
                RawAcknowledgement.ErrorResponse(Failure(CardProviderFailure.API_DISABLED, "AnkiDroid API switched off mid-write")),
                0,
                0,
            ),
            WriteAnomaly.DOUBLE_APPLY to Expect(RawAcknowledgement.UpdateCount(1), 2, 2),
            WriteAnomaly.SUSPENDED_INSTEAD to Expect(RawAcknowledgement.UpdateCount(1), 1, 1, queue = -1),
            WriteAnomaly.ZERO_BUT_APPLIED to Expect(RawAcknowledgement.UpdateCount(0), 1, 1),
            WriteAnomaly.UNEXPECTED_COUNT to Expect(RawAcknowledgement.UpdateCount(2), 1, 1),
        )
        assertEquals(WriteAnomaly.entries.toSet(), expected.keys.filterNotNull().toSet())
        for ((anomaly, expect) in expected) {
            val collection = demoCollection()
            val transport = FakeReviewTransport(collection)
            anomaly?.let(transport.anomalies::addLast)
            val identity = collection.scheduled(blocks).identity
            val before = collection.scheduled(blocks).state
            val ack = transport.answerCard(identity, 3, 12_345)
            val after = collection.scheduled(blocks).state
            assertEquals(expect.ack, ack, "$anomaly")
            assertEquals(expect.repsDelta, after.reps - before.reps, "$anomaly")
            assertEquals(expect.reviews, collection.reviews.size, "$anomaly")
            expect.queue?.let { assertEquals(it, after.queue, "$anomaly") }
            assertEquals(listOf(TransportCall(identity, 3, 12_345)), transport.calls, "$anomaly")
        }
    }

    @Test
    fun `speech output records attempts and honours scripted results and cancellation`() {
        val card = demoCollection().scheduled(blocks)
        val output = FakeSpeechOutput()
        val started = mutableListOf<OperationToken>()
        output.onSpeak = { started += it }
        val question = questionUtterance(card, "en-US")
        assertEquals(PlaybackResult.Completed(token), output.speak(token, question))

        val lost = Failure(SpeechOutputFailure.AUDIO_FOCUS_LOST, "call")
        output.script.addLast(FakeSpeechOutput.Fail(lost))
        val second = token.copy(sequence = 2)
        assertEquals(PlaybackResult.Failed(second, lost), output.speak(second, revealUtterance(card, "en-US")))

        val stale = PlaybackResult.Completed(token)
        output.script.addLast(FakeSpeechOutput.Deliver(stale))
        assertEquals(stale, output.speak(token.copy(sequence = 3), question))

        val cancelled = token.copy(sequence = 4)
        output.cancel(cancelled)
        output.cancel(cancelled)
        output.speak(cancelled, question)

        assertEquals(4, output.attempted.size)
        assertEquals(listOf(question), output.spoken)
        assertEquals(listOf(cancelled), output.cancelled)
        assertEquals(listOf(1, 2, 3, 4), started.map { it.sequence })
    }

    @Test
    fun `speech input turns script entries into capture events`() {
        val silence = Failure(SpeechInputFailure.NO_SPEECH_DETECTED, "scripted")
        val partial = CaptureEvent.Transcript(token, "Fi", TranscriptKind.PARTIAL)
        val input = FakeSpeechInput(FakeSpeechInput.Say("Five"), FakeSpeechInput.Fail(silence), FakeSpeechInput.Deliver(partial))
        assertEquals(CaptureEvent.Transcript(token, "Five", TranscriptKind.FINAL, Confidence.SUFFICIENT), input.listen(token, "en-US"))
        assertEquals(CaptureEvent.Failed(token, silence), input.listen(token, "en-US"))
        assertEquals(partial, input.listen(token, "en-US"))
        val exhausted = input.listen(token, "de-DE") as CaptureEvent.Failed
        assertEquals(SpeechInputFailure.EARLY_CLOSURE, exhausted.failure.mode)
        assertEquals(listOf("en-US", "en-US", "en-US", "de-DE"), input.languages)
        input.cancel(token)
        input.cancel(token)
        assertEquals(listOf(token), input.cancelled)
    }

    @Test
    fun `the grader answers the current request and fails once its script runs out`() {
        val card = demoCollection().scheduled(blocks)
        val first = GradingRequest(token, 1, gradingContext(card, "Five", "en-US"))
        val second = first.copy(operationToken = token.copy(sequence = 2), transcriptRevision = 2)
        val correct = GradingResult(GradeLabel.CORRECT, "the learner stated the required concept")
        val grader = FakeGrader(FakeGrader.Answer(correct), FakeGrader.Deliver(GradingReply(first, correct)))
        assertEquals(GradingReply(first, correct), grader.grade(first))
        assertEquals(GradingReply(first, correct), grader.grade(second)) // a stale reply, delivered as scripted
        val exhausted = grader.grade(second).result as Failure
        assertEquals(GraderFailure.PROVIDER_ERROR, exhausted.mode)
        assertEquals(listOf(first, second, second), grader.requests)
        assertEquals(grader.requests.map { it.context }, grader.seen)
        grader.cancel(first)
        grader.cancel(first)
        assertEquals(listOf(first), grader.cancelled)
    }

    @Test
    fun `the fake clock is deterministic and needs no real time`() {
        val clock = FakeClock()
        assertEquals(0L, clock.nowMs())
        clock.advance(12_345)
        assertEquals(12_345L, clock.nowMs())
        clock.valueMs -= 50 // a clock that went backwards
        assertEquals(12_295L, clock.nowMs())
        assertEquals(7L, FakeClock(startMs = 7).nowMs())
    }
}
