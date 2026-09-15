package org.ankivoice.core.fakes

import org.ankivoice.core.contracts.CardIdentity
import org.ankivoice.core.contracts.CardState
import org.ankivoice.core.contracts.MonotonicClock
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.contracts.VOICEQA_MODEL
import org.ankivoice.core.contracts.VoiceQAFields
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

// In-memory fakes for the AV-007 contracts, ported from tools/av007_fakes.py. No
// emulator, no network, no real clock. They are deliberately not a scheduler: the state
// a review produces here is a legible stand-in, and only the shape AV-004 verified
// (reps + 1, a populated last review time, scheduling that moved) is meaningful. The
// real adapter is #25 and the persistent journal is #20.

/** Stand-in scheduling, not Anki's algorithm. Again drops the interval; the others grow it. */
val INTERVAL_FACTORS: Map<Int, Double> = mapOf(1 to 0.0, 2 to 1.2, 3 to 2.5, 4 to 3.25)
const val CARD_TYPE_REVIEW: Int = 2
const val QUEUE_REVIEW: Int = 2

/** Deterministic elapsed time. Advanced explicitly by a scenario. */
class FakeClock(startMs: Long = 0) : MonotonicClock {
    var valueMs: Long = startMs

    override fun nowMs(): Long = valueMs

    fun advance(ms: Long) {
        valueMs += ms
    }
}

class StoredCard(
    var identity: CardIdentity,
    var state: CardState,
    var fields: VoiceQAFields,
    var permittedRatings: List<Int> = listOf(1, 2, 3, 4),
)

/** Who wrote a recorded review: this app through the contracts, or a competing writer. */
enum class ReviewSource { API, NATIVE }

/** One entry of the fake's offline review log. */
data class RecordedReview(
    val cardId: Long,
    val rating: Int,
    val source: ReviewSource,
    val timeTakenMs: Long,
)

/**
 * The store the provider reads and the transport writes.
 *
 * [reviews] is the offline-evidence analogue of the revlog AV-004 inspected outside the
 * app. No contract may read it; tests use it to prove that exactly one review, or none,
 * was recorded.
 */
open class FakeCollection(cards: List<StoredCard>, var maxReviewTimeMs: Long = 60_000) {
    val cards: MutableMap<Long, StoredCard> = cards.associateByTo(LinkedHashMap()) { it.identity.cardId }
    val order: MutableList<Long> = cards.mapTo(mutableListOf()) { it.identity.cardId }
    val reviews: MutableList<RecordedReview> = mutableListOf()
    private var epoch = 1_789_414_023L

    fun scheduled(cardId: Long): ScheduledCard {
        val card = cards.getValue(cardId)
        return ScheduledCard(card.identity, card.state, card.fields, card.permittedRatings)
    }

    /** AV-004: force-stopping AnkiDroid replaced the offered card. */
    fun rebuildQueue(order: List<Long>) {
        this.order.clear()
        this.order.addAll(order)
    }

    open fun applyReview(
        cardId: Long,
        rating: Int,
        elapsedMs: Long,
        repetitions: Int = 1,
        source: ReviewSource = ReviewSource.API,
    ) {
        val card = cards.getValue(cardId)
        repeat(repetitions) {
            epoch += 60
            val state = card.state
            val factor = INTERVAL_FACTORS.getValue(rating)
            // kotlin.math.round ties to even, as Python's round() does in the reference.
            val interval = if (rating == 1) 0 else max(1, round(max(state.intervalDays, 1) * factor).toInt())
            card.state = CardState(
                reps = state.reps + 1,
                cardType = CARD_TYPE_REVIEW,
                queue = QUEUE_REVIEW,
                due = state.due + interval + 1,
                intervalDays = interval,
                lastReviewTimeSecs = epoch,
            )
            // Anki truncates at the deck's maxTaken on store; AV-004 saw 98,765 ms
            // persisted as 60,000 ms.
            reviews += RecordedReview(cardId, rating, source, min(elapsedMs, maxReviewTimeMs))
        }
        order.remove(cardId)
    }

    /** A competing write by the native reviewer or a sync. */
    fun nativeAnswer(cardId: Long, rating: Int = 3) {
        applyReview(cardId, rating, 4_200, source = ReviewSource.NATIVE)
    }

    fun setPermittedRatings(cardId: Long, ratings: List<Int>) {
        cards.getValue(cardId).permittedRatings = ratings
    }

    fun mutateState(cardId: Long, change: (CardState) -> CardState) {
        val card = cards.getValue(cardId)
        card.state = change(card.state)
    }
}

// A small VoiceQA collection built from the AV-002 fixture shapes.

const val DEMO_DECK_ID: Long = 1_789_414_083_100

fun demoCard(
    cardId: Long,
    prompt: String,
    reference: String,
    state: CardState,
    concepts: List<String> = emptyList(),
    accepted: List<String> = emptyList(),
    extra: String = "",
    deckId: Long = DEMO_DECK_ID,
    permitted: List<Int> = listOf(1, 2, 3, 4),
): StoredCard = StoredCard(
    identity = CardIdentity(cardId = cardId, noteId = cardId, deckId = deckId, ordinal = 0, model = VOICEQA_MODEL),
    state = state,
    fields = VoiceQAFields(
        prompt = prompt,
        referenceAnswer = reference,
        requiredConcepts = concepts,
        acceptedAnswers = accepted,
        language = "en-US",
        extra = extra,
    ),
    permittedRatings = permitted,
)

/** Two VoiceQA cards taken from the fixture examples, with distinct states. */
fun demoCollection(): FakeCollection = FakeCollection(
    listOf(
        demoCard(
            1_789_414_083_106,
            "A box has three red blocks and two blue blocks. How many blocks are there in total?",
            "Five blocks.",
            CardState(reps = 0, cardType = 0, queue = 0, due = 1, intervalDays = 0),
            concepts = listOf("Five"),
            accepted = listOf("Five", "5", "There are five blocks"),
            extra = "Three plus two equals five.",
        ),
        demoCard(
            1_789_414_083_109,
            "Reverse the sequence red, blue, green.",
            "Green, blue, red.",
            CardState(reps = 3, cardType = 2, queue = 2, due = 90, intervalDays = 30, lastReviewTimeSecs = 1_789_400_000),
            concepts = listOf("Green first", "Blue second", "Red last"),
            accepted = listOf("Green blue red"),
            extra = "Reversing changes the order, not the items.",
        ),
    ),
)
