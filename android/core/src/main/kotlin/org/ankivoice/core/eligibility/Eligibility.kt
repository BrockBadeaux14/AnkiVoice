package org.ankivoice.core.eligibility

import java.util.IllformedLocaleException
import java.util.Locale
import org.ankivoice.core.contracts.CardIdentity
import org.ankivoice.core.contracts.CardProvider
import org.ankivoice.core.contracts.CardProviderFailure
import org.ankivoice.core.contracts.CardState
import org.ankivoice.core.contracts.Failure
import org.ankivoice.core.contracts.QueueExhausted
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.contracts.Utterance
import org.ankivoice.core.contracts.UtterancePurpose
import org.ankivoice.core.contracts.VOICEQA_MODEL
import org.ankivoice.core.contracts.VoiceQAFields
import org.ankivoice.core.contracts.cardLanguage

/** The VoiceQA field order from fixtures/voiceqa/note-type.json. */
val VOICEQA_FIELD_NAMES: List<String> = listOf(
    "Prompt",
    "ReferenceAnswer",
    "RequiredConcepts",
    "AcceptedAnswers",
    "Language",
    "Extra",
)

/** Consecutive ineligible cards that end the session. Recorded on #11. */
const val CONSECUTIVE_INELIGIBLE_LIMIT: Int = 5

enum class IneligibleReason {
    UNSUPPORTED_NOTE_TYPE,
    BLANK_REQUIRED_FIELD,
    FIELD_LAYOUT_MISMATCH,
    MALFORMED_LANGUAGE,
    UNSUPPORTED_TEMPLATE,
}

/** An already-read card, including ones the adapter would refuse to expose as VoiceQA. */
data class ObservedCard(
    val identity: CardIdentity,
    val fields: VoiceQAFields,
    val fieldNames: List<String> = VOICEQA_FIELD_NAMES,
    val fieldValueCount: Int = fieldNames.size,
) {
    constructor(card: ScheduledCard, fieldNames: List<String> = VOICEQA_FIELD_NAMES) :
        this(card.identity, card.fields, fieldNames)
}

sealed interface Eligibility {
    data class Studiable(val language: String) : Eligibility
    data class Ineligible(
        val identity: CardIdentity,
        val reason: IneligibleReason,
        val announcement: Utterance,
    ) : Eligibility
}

/**
 * Session-facing eligibility. Parsing stays in the adapter; this only classifies an
 * already-read card. A well-formed but unavailable locale is still [Eligibility.Studiable]
 * — voice resolution belongs to #26.
 */
fun classify(card: ObservedCard, sessionLanguage: String): Eligibility {
    val identity = card.identity
    val fields = card.fields
    if (identity.model != VOICEQA_MODEL) {
        return ineligible(IneligibleReason.UNSUPPORTED_NOTE_TYPE, identity, fields, sessionLanguage)
    }
    if (card.fieldNames != VOICEQA_FIELD_NAMES || card.fieldValueCount != card.fieldNames.size) {
        return ineligible(IneligibleReason.FIELD_LAYOUT_MISMATCH, identity, fields, sessionLanguage)
    }
    if (identity.ordinal != 0) {
        return ineligible(IneligibleReason.UNSUPPORTED_TEMPLATE, identity, fields, sessionLanguage)
    }
    val blankField = when {
        fields.prompt.isBlank() -> "Prompt"
        fields.referenceAnswer.isBlank() -> "ReferenceAnswer"
        else -> null
    }
    if (blankField != null) {
        return ineligible(IneligibleReason.BLANK_REQUIRED_FIELD, identity, fields, sessionLanguage, blankField)
    }
    if (fields.language.isNotEmpty() && !isWellFormedBcp47(fields.language)) {
        return ineligible(IneligibleReason.MALFORMED_LANGUAGE, identity, fields, sessionLanguage)
    }
    val scheduled = ScheduledCard(identity, CardState(0, 0, 0, 0, 0), fields, emptyList())
    return Eligibility.Studiable(cardLanguage(scheduled, sessionLanguage))
}

fun classify(card: ScheduledCard, sessionLanguage: String): Eligibility =
    classify(ObservedCard(card), sessionLanguage)

internal fun isWellFormedBcp47(tag: String): Boolean = try {
    Locale.Builder().setLanguageTag(tag).build()
    true
} catch (_: IllformedLocaleException) {
    false
}

internal fun ineligible(
    reason: IneligibleReason,
    identity: CardIdentity,
    fields: VoiceQAFields,
    sessionLanguage: String,
    blankField: String? = null,
): Eligibility.Ineligible {
    val card = "card ${identity.cardId}"
    val text = when (reason) {
        IneligibleReason.UNSUPPORTED_NOTE_TYPE ->
            "Skipped $card: note type ${identity.model} cannot be studied by voice. VoiceQA is required. Fix the note type in AnkiDroid."
        IneligibleReason.BLANK_REQUIRED_FIELD ->
            "Skipped $card: ${blankField ?: "a required field"} is blank. Add it in AnkiDroid."
        IneligibleReason.FIELD_LAYOUT_MISMATCH ->
            "Skipped $card: VoiceQA field layout does not match. Fix the note type fields in AnkiDroid."
        IneligibleReason.UNSUPPORTED_TEMPLATE ->
            "Skipped $card: VoiceQA has an unsupported extra card template. Keep only its Voice recall template in AnkiDroid."
        IneligibleReason.MALFORMED_LANGUAGE ->
            "Skipped $card: Language '${fields.language}' is not a valid BCP 47 tag. Fix Language in AnkiDroid."
    }
    return Eligibility.Ineligible(identity, reason, Utterance(UtterancePurpose.ANNOUNCEMENT, text, sessionLanguage))
}

sealed interface CardOffer {
    val skipped: List<Eligibility.Ineligible>

    data class Ready(
        val card: ScheduledCard,
        val language: String,
        override val skipped: List<Eligibility.Ineligible>,
    ) : CardOffer

    data class Exhausted(override val skipped: List<Eligibility.Ineligible>) : CardOffer

    data class Stopped(
        override val skipped: List<Eligibility.Ineligible>,
        val summary: String,
    ) : CardOffer

    data class Paused(
        val failure: Failure,
        override val skipped: List<Eligibility.Ineligible> = emptyList(),
    ) : CardOffer
}

/**
 * Optional read-only queue traversal for eligibility. Exclusions live only in this
 * provider/session; nextCard and readCard must retain their freshness guarantees.
 * This is not the user-requested scheduler skip represented by supportsSkip.
 */
interface EligibilityCardProvider : CardProvider {
    fun nextCandidate(): CandidateRead
    fun excludeIneligibleCard(identity: CardIdentity)
}

sealed interface CandidateRead {
    data class Card(
        val snapshot: ScheduledCard,
        val fieldNames: List<String> = VOICEQA_FIELD_NAMES,
        val fieldValueCount: Int = fieldNames.size,
    ) : CandidateRead {
        val observed: ObservedCard
            get() = ObservedCard(snapshot.identity, snapshot.fields, fieldNames, fieldValueCount)
    }
    data class Failed(val failure: Failure) : CandidateRead
    data object Exhausted : CandidateRead
}

/**
 * Request the next studiable card, with a bounded number of read-only exclusions.
 * Unclassified provider failures pause: a malformed cursor is not a blank note field.
 * The counter is local to one offer, so a studiable card resets the consecutive cap.
 */
fun offerNextCard(
    provider: CardProvider,
    sessionLanguage: String,
    limit: Int = CONSECUTIVE_INELIGIBLE_LIMIT,
): CardOffer {
    require(limit > 0) { "The consecutive ineligible limit must be positive" }
    val skipped = mutableListOf<Eligibility.Ineligible>()
    while (skipped.size < limit) {
        val candidate = if (provider is EligibilityCardProvider) provider.nextCandidate() else {
            when (val result = provider.nextCard()) {
                QueueExhausted -> CandidateRead.Exhausted
                is Failure -> CandidateRead.Failed(result)
                is ScheduledCard -> CandidateRead.Card(result)
            }
        }
        when (candidate) {
            CandidateRead.Exhausted -> return CardOffer.Exhausted(skipped.toList())
            is CandidateRead.Failed -> return CardOffer.Paused(candidate.failure, skipped.toList())
            is CandidateRead.Card -> {
                if (skipped.any { it.identity == candidate.snapshot.identity }) {
                    return CardOffer.Paused(Failure(CardProviderFailure.MALFORMED_CARD,
                        "Provider repeated an excluded card; queue advancement is unavailable"), skipped.toList())
                }
                when (val eligibility = classify(candidate.observed, sessionLanguage)) {
                    is Eligibility.Studiable ->
                        return CardOffer.Ready(candidate.snapshot, eligibility.language, skipped.toList())
                    is Eligibility.Ineligible -> {
                        skipped += eligibility
                        if (provider is EligibilityCardProvider) provider.excludeIneligibleCard(eligibility.identity)
                    }
                }
            }
        }
    }
    return CardOffer.Stopped(skipped.toList(), skipSummary(skipped))
}

internal fun skipSummary(skipped: List<Eligibility.Ineligible>): String {
    val counts = skipped.groupingBy { it.reason }.eachCount()
    val parts = IneligibleReason.entries.mapNotNull { reason ->
        counts[reason]?.let { n -> "$n ${reasonLabel(reason)}" }
    }
    return "Stopped after ${skipped.size} consecutive unstudiable cards: ${parts.joinToString(", ")}. Fix those notes in AnkiDroid."
}

private fun reasonLabel(reason: IneligibleReason): String = when (reason) {
    IneligibleReason.UNSUPPORTED_NOTE_TYPE -> "unsupported note type"
    IneligibleReason.BLANK_REQUIRED_FIELD -> "blank required field"
    IneligibleReason.FIELD_LAYOUT_MISMATCH -> "field layout mismatch"
    IneligibleReason.MALFORMED_LANGUAGE -> "malformed language tag"
    IneligibleReason.UNSUPPORTED_TEMPLATE -> "unsupported card template"
}
