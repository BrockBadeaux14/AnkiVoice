package org.ankivoice.ankidroid

import java.util.concurrent.Executor
import org.ankivoice.core.contracts.*
import org.ankivoice.core.eligibility.*

data class QueueCard(val noteId: Long, val ordinal: Int, val buttonCount: Int)
data class StoredReviewCard(val cardId: Long, val noteId: Long, val deckId: Long, val ordinal: Int, val state: CardState)
data class ReviewNote(val modelId: Long, val fields: String)

/** Extends the existing resolver seam. Null cursors and valid empty rows stay distinct. */
interface ReviewPlatform : AccessPlatform {
    fun querySchedule(deckId: Long, limit: Int = 1): List<QueueCard>?
    fun queryReviewCards(search: String): List<StoredReviewCard>?
    fun queryReviewNote(noteId: Long): List<ReviewNote>?
    fun queryReviewModels(): List<InstalledNoteType>?
    fun queryDeckTimeCap(deckId: Long): List<Long?>?
    fun updateSchedule(identity: CardIdentity, rating: Int, elapsedMs: Long): Int
}

data class CardReadReply<T>(val token: OperationToken, val result: T)

/** Worker-only synchronous contracts for the writer; token-tagged dispatch for UI clients. */
class AnkiDroidCardProvider(
    private val platform: ReviewPlatform,
    private val deckId: Long,
    private val worker: Executor,
    private val delivery: Executor,
    private val clock: MonotonicClock = SystemMonotonicClock,
) : EligibilityCardProvider {
    // Session-local exclusions; never a provider update or a reordered queue.
    private val excluded = mutableSetOf<Pair<Long, Int>>()
    private var lastCandidate: CardIdentity? = null
    private class ReadFailure(val failure: Failure) : RuntimeException()
    private fun fail(mode: CardProviderFailure, detail: String = ""): Nothing = throw ReadFailure(Failure(mode, detail))
    private fun accessFailure(): Failure? = when {
        !platform.packageAvailable() -> Failure(CardProviderFailure.PACKAGE_UNAVAILABLE)
        platform.apiEnabled() == false -> Failure(CardProviderFailure.API_DISABLED)
        !platform.databasePermissionGranted() -> Failure(CardProviderFailure.ACCESS_DENIED)
        else -> null
    }
    private fun <T> rows(value: List<T>?): List<T> = value ?: throw ReadFailure(
        accessFailure() ?: Failure(CardProviderFailure.NULL_CURSOR))
    private fun deck() {
        accessFailure()?.let { throw ReadFailure(it) }
        if (rows(platform.queryDecks()).none { it.id == deckId }) fail(CardProviderFailure.DECK_MISSING)
    }
    private fun guarded(operation: () -> Any): Any = try { deck(); operation() }
    catch (e: ReadFailure) { e.failure }
    catch (_: SecurityException) { Failure(CardProviderFailure.ACCESS_DENIED) }
    catch (_: RuntimeException) {
        try { accessFailure() ?: Failure(CardProviderFailure.NULL_CURSOR) }
        catch (_: RuntimeException) { Failure(CardProviderFailure.NULL_CURSOR) }
    }
    private fun queue(): QueueCard? {
        val limit = excluded.size + 1
        val rows = rows(platform.querySchedule(deckId, limit))
        if (rows.size > limit || rows.map { it.noteId to it.ordinal }.distinct().size != rows.size)
            fail(CardProviderFailure.MALFORMED_CARD, "Invalid scheduled-card prefix")
        return rows.firstOrNull { (it.noteId to it.ordinal) !in excluded }
    }
    private fun ratings(offered: QueueCard?): List<Int> {
        if (offered == null) return emptyList()
        if (offered.buttonCount !in 0..4) fail(CardProviderFailure.MALFORMED_CARD, "Invalid answer button count")
        return (1..offered.buttonCount).toList()
    }
    override fun capabilities(): CapabilitiesResult = guarded {
        val cap = rows(platform.queryDeckTimeCap(deckId)).singleOrNull()
            ?: fail(CardProviderFailure.MALFORMED_CARD, "Deck maxTaken unavailable or malformed")
        if (cap < 0) fail(CardProviderFailure.MALFORMED_CARD, "Negative deck time cap")
        Capabilities(permittedRatings = ratings(queue()), maxReviewTimeMs = cap)
    } as CapabilitiesResult

    override fun nextCandidate(): CandidateRead {
        lastCandidate = null
        val result = guarded {
            val offered = queue() ?: return@guarded CandidateRead.Exhausted
            val cards = rows(platform.queryReviewCards("nid:${offered.noteId}"))
            val card = cards.singleOrNull { it.ordinal == offered.ordinal }
                ?: fail(CardProviderFailure.CARD_NOT_FOUND)
            if (card.noteId != offered.noteId) fail(CardProviderFailure.COLLECTION_CHANGED)
            parse(card, offered)
        }
        return when (result) {
            is Failure -> CandidateRead.Failed(result)
            is CandidateRead.Card -> result.also { lastCandidate = it.snapshot.identity }
            else -> result as CandidateRead.Exhausted
        }
    }

    override fun excludeIneligibleCard(identity: CardIdentity) {
        check(identity == lastCandidate) { "Only the last observed candidate can be excluded" }
        excluded += identity.noteId to identity.ordinal
        lastCandidate = null
    }

    override fun nextCard(): NextCardResult = when (val result = nextCandidate()) {
        CandidateRead.Exhausted -> QueueExhausted
        is CandidateRead.Failed -> result.failure
        is CandidateRead.Card -> validate(result)
    }

    override fun readCard(cardId: Long): ReadCardResult = guarded {
        val card = rows(platform.queryReviewCards("cid:$cardId")).singleOrNull()
            ?: fail(CardProviderFailure.CARD_NOT_FOUND)
        if (card.cardId != cardId) fail(CardProviderFailure.COLLECTION_CHANGED)
        validate(parse(card, queue()))
    } as ReadCardResult

    private fun parse(card: StoredReviewCard, offered: QueueCard?): CandidateRead.Card {
        if (card.deckId != deckId) fail(CardProviderFailure.COLLECTION_CHANGED, "Card moved from selected deck")
        val note = rows(platform.queryReviewNote(card.noteId)).singleOrNull()
            ?: fail(CardProviderFailure.COLLECTION_CHANGED, "Card note disappeared")
        val model = rows(platform.queryReviewModels()).singleOrNull { it.id == note.modelId }
            ?: fail(CardProviderFailure.COLLECTION_CHANGED, "Note model disappeared")
        val values = note.fields.split('\u001f')
        val fields = model.fieldNames.zip(values).toMap()
        val prompt = fields["Prompt"].orEmpty()
        val reference = fields["ReferenceAnswer"].orEmpty()
        fun lines(name: String) = fields[name].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        return CandidateRead.Card(ScheduledCard(CardIdentity(card.cardId, card.noteId, card.deckId, card.ordinal, model.name),
            card.state, VoiceQAFields(prompt, reference, lines("RequiredConcepts"), lines("AcceptedAnswers"),
                fields["Language"].orEmpty(), fields["Extra"].orEmpty()),
            ratings(offered?.takeIf { it.noteId == card.noteId && it.ordinal == card.ordinal }), clock.nowMs()),
            model.fieldNames, values.size)
    }

    private fun validate(candidate: CandidateRead.Card): ReadCardResult =
        when (val result = classify(candidate.observed, "en-US")) {
            is Eligibility.Studiable -> candidate.snapshot
            is Eligibility.Ineligible -> Failure(
                if (result.reason == IneligibleReason.UNSUPPORTED_NOTE_TYPE) CardProviderFailure.UNSUPPORTED_NOTE_TYPE
                else CardProviderFailure.MALFORMED_CARD,
                result.announcement.text,
            )
        }

    fun capabilities(token: OperationToken, callback: (CardReadReply<CapabilitiesResult>) -> Unit) =
        dispatch(token, callback, ::capabilities)
    fun nextCard(token: OperationToken, callback: (CardReadReply<NextCardResult>) -> Unit) =
        dispatch(token, callback, ::nextCard)
    fun readCard(token: OperationToken, cardId: Long, callback: (CardReadReply<ReadCardResult>) -> Unit) =
        dispatch(token, callback) { readCard(cardId) }
    private fun <T> dispatch(token: OperationToken, callback: (CardReadReply<T>) -> Unit, read: () -> T) {
        worker.execute {
            val reply = CardReadReply(token, read())
            delivery.execute { callback(reply) }
        }
    }
}

class AnkiDroidReviewTransport(private val platform: ReviewPlatform) : ReviewTransport {
    override fun answerCard(identity: CardIdentity, rating: Int, elapsedMs: Long): RawAcknowledgement {
        // Defense in depth. No coercion and no retry, even after an exception.
        require(rating in 1..4 && elapsedMs >= 0)
        return try { RawAcknowledgement.UpdateCount(platform.updateSchedule(identity, rating, elapsedMs)) }
        catch (_: SecurityException) { RawAcknowledgement.ErrorResponse(Failure(CardProviderFailure.ACCESS_DENIED)) }
        catch (_: RuntimeException) { RawAcknowledgement.ErrorResponse(Failure(CardProviderFailure.NULL_CURSOR)) }
    }
}
