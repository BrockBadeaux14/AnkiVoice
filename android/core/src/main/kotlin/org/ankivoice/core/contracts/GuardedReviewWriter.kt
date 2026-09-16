package org.ankivoice.core.contracts

/** Structural evidence only: Anki owns all scheduling and due units. */
fun isOneReviewTransition(pre: CardState, post: CardState): Boolean =
    pre.reps < Int.MAX_VALUE && post.reps == pre.reps + 1 &&
        post.lastReviewTimeSecs != null && post.lastReviewTimeSecs > 0 &&
        (pre.lastReviewTimeSecs == null || post.lastReviewTimeSecs >= pre.lastReviewTimeSecs) &&
        (post.cardType to post.queue) in setOf(1 to 1, 1 to 3, 2 to 2, 3 to 1, 3 to 3) &&
        post.intervalDays >= 0 &&
        (post.cardType != pre.cardType || post.queue != pre.queue ||
            post.due != pre.due || post.intervalDays != pre.intervalDays)

/** One dispatch, no replay. Serialize calls on the session's owned worker. */
open class GuardedReviewWriter(
    private val provider: CardProvider,
    private val transport: ReviewTransport,
    private val capabilities: Capabilities,
) : ReviewWriter {
    override fun commit(intent: ReviewIntent): ReviewOutcome {
        check(intent.state == ReviewState.PENDING) { "Only a pending review may be committed" }
        val snapshot = intent.cardSnapshot
        fun reject(mode: ReviewWriterFailure, reason: String, cause: Failure? = null): ReviewOutcome =
            settle(intent, ReviewOutcome(ReviewState.FAILED, reason, Failure(mode, reason, cause),
                preState = snapshot.state, submittedTimeMs = intent.elapsedMs))
        if (intent.rating !in snapshot.permittedRatings)
            return reject(ReviewWriterFailure.RATING_REJECTED, "Rating was not offered")
        if (intent.elapsedMs < 0)
            return reject(ReviewWriterFailure.INVALID_REVIEW_TIME, "Negative elapsed milliseconds")
        if (!intent.hasConfirmation())
            return reject(ReviewWriterFailure.CONFIRMATION_REQUIRED, "Current explicit confirmation required")
        val offered = provider.nextCard()
        if (offered is Failure)
            return reject(ReviewWriterFailure.PRECOMMIT_READ_FAILED, "Scheduled read failed", offered)
        if (offered !is ScheduledCard || offered.identity != snapshot.identity ||
            offered.state != snapshot.state || offered.fields != snapshot.fields)
            return reject(ReviewWriterFailure.STALE_IDENTITY, "Scheduled snapshot changed or was withdrawn")
        val fresh = provider.readCard(snapshot.identity.cardId)
        if (fresh is Failure)
            return reject(ReviewWriterFailure.PRECOMMIT_READ_FAILED, "Card read failed", fresh)
        fresh as ScheduledCard
        if (fresh.identity != snapshot.identity || fresh.state != snapshot.state || fresh.fields != snapshot.fields)
            return reject(ReviewWriterFailure.STALE_IDENTITY, "Identity, state or content changed")
        if (intent.rating !in fresh.permittedRatings || intent.rating !in offered.permittedRatings)
            return reject(ReviewWriterFailure.RATING_REJECTED, "Rating was withdrawn")
        if (intent.state != ReviewState.PENDING || intent.interrupted || !intent.hasConfirmation())
            return reject(ReviewWriterFailure.CONFIRMATION_REQUIRED, "Confirmation cancelled during reads")

        intent.state = ReviewState.SUBMITTING
        val ack = try { transport.answerCard(fresh.identity, intent.rating, intent.elapsedMs) }
        catch (_: RuntimeException) {
            RawAcknowledgement.ErrorResponse(Failure(CardProviderFailure.NULL_CURSOR, "Write response unavailable"))
        }
        val base = ReviewOutcome(ReviewState.OUTCOME_UNKNOWN, "Unverified write",
            acknowledgement = ack.updateCount, preState = fresh.state,
            submittedTimeMs = intent.elapsedMs,
            expectedStoredTimeMs = minOf(intent.elapsedMs, capabilities.maxReviewTimeMs), writeAttempted = true)
        fun unknown(reason: String, failure: Failure? = null, post: CardState? = null) =
            settle(intent, base.copy(reason = reason, failure = failure, postState = post))
        if (intent.interrupted) return unknown("Interrupted during submission")
        if (!capabilities.supportsPostWriteVerification) return unknown("Verification unavailable")
        val after = try { provider.readCard(fresh.identity.cardId) }
        catch (_: RuntimeException) { Failure(CardProviderFailure.NULL_CURSOR, "Post-write read failed") }
        if (intent.interrupted) return unknown("Interrupted during verification")
        if (after is Failure) return unknown("Post-state unavailable", after)
        after as ScheduledCard
        if (ack !is RawAcknowledgement.UpdateCount)
            return unknown("Null or error write response", (ack as? RawAcknowledgement.ErrorResponse)?.failure, after.state)
        if (after.identity != fresh.identity || after.fields != fresh.fields)
            return unknown("Post-write identity or content changed", post = after.state)
        if (ack.updateCount == 0 && after.state == fresh.state)
            return settle(intent, base.copy(state = ReviewState.FAILED,
                reason = "Explicit zero with unchanged card", postState = after.state,
                failure = Failure(ReviewWriterFailure.WRITE_REJECTED)))
        if (ack.updateCount != 1 || !isOneReviewTransition(fresh.state, after.state))
            return unknown("No consistent acknowledged one-review transition", post = after.state)
        return settle(intent, base.copy(state = ReviewState.CONFIRMED,
            reason = "Consistent one-review transition", postState = after.state))
    }

    private fun settle(intent: ReviewIntent, outcome: ReviewOutcome): ReviewOutcome {
        intent.state = outcome.state
        return outcome
    }
}
