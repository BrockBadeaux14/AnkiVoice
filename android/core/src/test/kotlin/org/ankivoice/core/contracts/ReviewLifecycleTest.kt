package org.ankivoice.core.contracts

import org.ankivoice.core.fakes.demoCollection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Review states, time-cap helpers, and confirmations bound to their operation token. */
class ReviewLifecycleTest {
    private val card = demoCollection().scheduled(1_789_414_083_106)
    private val token = OperationToken("session-a", turn = 1, sequence = 4)

    private fun pendingIntent(rating: Int = 3) = ReviewIntent(card, rating, 12_345, token = token, transcriptRevision = 1)

    private fun confirmation(
        source: ConfirmationSource = ConfirmationSource.SPOKEN,
        confidence: Confidence = Confidence.SUFFICIENT,
    ) = RatingConfirmation(token, card.identity, 3, transcriptRevision = 1, source = source, confidence = confidence)

    @Test
    fun `the five review states in lifecycle order`() {
        assertEquals(
            listOf("pending", "submitting", "confirmed", "failed", "outcome-unknown"),
            ReviewState.entries.map { it.specName },
        )
        assertEquals(setOf(ReviewState.CONFIRMED, ReviewState.FAILED, ReviewState.OUTCOME_UNKNOWN), TERMINAL_REVIEW_STATES)
    }

    @Test
    fun `the cap truncates the expected stored time without being an error`() {
        val capped = ReviewOutcome(ReviewState.CONFIRMED, "ok", submittedTimeMs = 98_765, expectedStoredTimeMs = 60_000)
        assertTrue(capped.timeWasCapped)
        assertTrue(capped.storedTimeIsExpected(60_000))
        assertFalse(capped.storedTimeIsExpected(98_765))
    }

    @Test
    fun `an uncapped time is expected unchanged`() {
        val uncapped = ReviewOutcome(ReviewState.CONFIRMED, "ok", submittedTimeMs = 12_345, expectedStoredTimeMs = 12_345)
        assertFalse(uncapped.timeWasCapped)
        assertTrue(uncapped.storedTimeIsExpected(12_345))
    }

    @Test
    fun `a zero cap stores zero, and zero elapsed is never capped`() {
        val zeroCap = ReviewOutcome(ReviewState.CONFIRMED, "ok", submittedTimeMs = 5_000, expectedStoredTimeMs = 0)
        assertTrue(zeroCap.timeWasCapped)
        assertTrue(zeroCap.storedTimeIsExpected(0))
        val zeroElapsed = ReviewOutcome(ReviewState.CONFIRMED, "ok", submittedTimeMs = 0, expectedStoredTimeMs = 0)
        assertFalse(zeroElapsed.timeWasCapped)
    }

    @Test
    fun `without a submitted write there is no cap and no expected stored time`() {
        val rejected = ReviewOutcome(ReviewState.FAILED, "no write", submittedTimeMs = 12_345)
        assertFalse(rejected.timeWasCapped)
        assertFalse(rejected.storedTimeIsExpected(12_345))
        assertFalse(ReviewOutcome(ReviewState.FAILED, "no write").timeWasCapped)
    }

    @Test
    fun `a new intent is pending with no confirmation`() {
        val intent = pendingIntent()
        assertEquals(ReviewState.PENDING, intent.state)
        assertEquals(emptyList<Int>(), intent.corrections)
        assertNull(intent.confirmation)
        assertFalse(intent.interrupted)
        assertFalse(intent.hasConfirmation())
    }

    @Test
    fun `correction is open while pending and clears the confirmation`() {
        val intent = pendingIntent(rating = 1)
        intent.correct(2)
        intent.correct(4)
        assertEquals(4, intent.rating)
        assertEquals(listOf(1, 2), intent.corrections)

        val confirmed = pendingIntent()
        confirmed.confirmation = confirmation()
        assertTrue(confirmed.hasConfirmation())
        confirmed.correct(2)
        confirmed.correct(3) // changed back to the confirmed rating
        assertNull(confirmed.confirmation)
        assertFalse(confirmed.hasConfirmation())
    }

    @Test
    fun `correction is closed once the intent leaves pending`() {
        for (state in ReviewState.entries - ReviewState.PENDING) {
            val intent = pendingIntent()
            intent.state = state
            assertThrows<IllegalStateException>(state.specName) { intent.correct(1) }
            assertEquals(3, intent.rating)
        }
    }

    @Test
    fun `tokens match on session, turn and sequence together`() {
        assertEquals(token, OperationToken("session-a", 1, 4))
        for (other in listOf(token.copy(sessionId = "session-b"), token.copy(turn = 2), token.copy(sequence = 5))) {
            assertNotEquals(token, other)
        }
    }

    @Test
    fun `spoken and touch confirmations each authorize the intent they are bound to`() {
        for (event in listOf(confirmation(), confirmation(ConfirmationSource.TOUCH, Confidence.ABSENT))) {
            val intent = pendingIntent()
            intent.confirmation = event
            assertTrue(intent.hasConfirmation(), event.source.specName)
        }
    }

    @Test
    fun `uncertain, partial or mismatched confirmations never authorize`() {
        val base = confirmation()
        val rejected = mapOf(
            "low confidence" to base.copy(confidence = Confidence.LOW),
            "absent confidence" to base.copy(confidence = Confidence.ABSENT),
            "not final" to base.copy(final = false),
            "other rating" to base.copy(rating = 1),
            "other revision" to base.copy(transcriptRevision = 90),
            "other session" to base.copy(token = token.copy(sessionId = "session-b")),
            "other turn" to base.copy(token = token.copy(turn = 2)),
            "earlier operation" to base.copy(token = token.copy(sequence = 3)),
            "other note" to base.copy(identity = card.identity.copy(noteId = 99)),
            "other card" to base.copy(identity = card.identity.copy(cardId = 99)),
        )
        for ((case, event) in rejected) {
            val intent = pendingIntent()
            intent.confirmation = event
            assertFalse(intent.hasConfirmation(), case)
        }
        val untokened = ReviewIntent(card, 3, 12_345, transcriptRevision = 1)
        untokened.confirmation = base
        assertFalse(untokened.hasConfirmation(), "intent without a token")
    }

    @Test
    fun `a raw acknowledgement is exhaustively matchable and never proof of a save`() {
        val failure = Failure(CardProviderFailure.API_DISABLED, "switched off")
        val described = listOf(
            RawAcknowledgement.UpdateCount(1),
            RawAcknowledgement.UpdateCount(0),
            RawAcknowledgement.NullResponse,
            RawAcknowledgement.ErrorResponse(failure),
        ).map { ack ->
            when (ack) {
                is RawAcknowledgement.UpdateCount -> "count ${ack.updateCount}"
                RawAcknowledgement.NullResponse -> "null"
                is RawAcknowledgement.ErrorResponse -> "error ${ack.failure}"
            } to ack.updateCount
        }
        assertEquals(
            listOf("count 1" to 1, "count 0" to 0, "null" to null, "error CardProvider.apiDisabled: switched off" to null),
            described,
        )
    }

    @Test
    fun `playback and capture results are exhaustively matchable and a failure carries no text`() {
        val failure = Failure(SpeechInputFailure.NO_MATCH, "ERROR_NO_MATCH")
        val events = listOf(
            CaptureEvent.Transcript(token, "Five", TranscriptKind.PARTIAL),
            CaptureEvent.Transcript(token, "Five", confidence = Confidence.SUFFICIENT),
            CaptureEvent.Failed(token, failure),
        )
        val described = events.map { event ->
            when (event) {
                is CaptureEvent.Transcript -> "${event.kind.specName} ${event.text} ${event.confidence.specName}"
                is CaptureEvent.Failed -> "failed ${event.failure}"
            }
        }
        assertEquals(listOf("partial Five absent", "final Five sufficient", "failed SpeechInput.noMatch: ERROR_NO_MATCH"), described)

        val playback = listOf(PlaybackResult.Completed(token), PlaybackResult.Failed(token, Failure(SpeechOutputFailure.AUDIO_FOCUS_LOST)))
        assertEquals(
            listOf("completed", "failed SpeechOutput.audioFocusLost"),
            playback.map { result ->
                when (result) {
                    is PlaybackResult.Completed -> "completed"
                    is PlaybackResult.Failed -> "failed ${result.failure}"
                }
            },
        )
        assertTrue(events.all { it.token == token } && playback.all { it.token == token })
    }

    @Test
    fun `a grading reply holds a label or a failure, never a rating`() {
        val request = GradingRequest(token, 1, gradingContext(card, "Five", "en-US"))
        val replies = listOf(
            GradingReply(request, GradingResult(GradeLabel.PARTIAL, "one concept missing")),
            GradingReply(request, Failure(GraderFailure.QUOTA_EXHAUSTED, "429")),
        )
        assertEquals(
            listOf("partial none", "failure Grader.quotaExhausted: 429"),
            replies.map { reply ->
                when (val result = reply.result) {
                    is GradingResult -> "${result.label.specName} ${result.proposedRating(card.permittedRatings) ?: "none"}"
                    is Failure -> "failure $result"
                }
            },
        )
    }
}
