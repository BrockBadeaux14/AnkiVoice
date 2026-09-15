package org.ankivoice.core.contracts

/** Question/answer separation: every utterance carries a purpose. */
enum class UtterancePurpose(val specName: String) {
    /** Prompt only. */
    QUESTION("question"),

    /** ReferenceAnswer, after an accepted or corrected answer. */
    REVEAL("reveal"),

    /** Extra, after the answer, optional. */
    ELABORATION("elaboration"),

    /** Session and rating talkback. */
    ANNOUNCEMENT("announcement"),
}

data class Utterance(
    val purpose: UtterancePurpose,
    val text: String,
    val language: String,
)

/**
 * Everything the grader may see. A closed structure with no Extra field, so Extra
 * cannot reach the grader by accident.
 *
 * [learnerAnswer] is the transcript. The other fields are the answer key, never words
 * the learner said.
 */
data class GradingContext(
    val prompt: String,
    val referenceAnswer: String,
    val requiredConcepts: List<String>,
    val acceptedAnswers: List<String>,
    val learnerAnswer: String,
    val language: String,
)

fun cardLanguage(card: ScheduledCard, sessionLanguage: String): String =
    card.fields.language.ifEmpty { sessionLanguage }

/** Question audio is Prompt and nothing else. */
fun questionUtterance(card: ScheduledCard, sessionLanguage: String): Utterance =
    Utterance(UtterancePurpose.QUESTION, card.fields.prompt, cardLanguage(card, sessionLanguage))

fun revealUtterance(card: ScheduledCard, sessionLanguage: String): Utterance =
    Utterance(UtterancePurpose.REVEAL, card.fields.referenceAnswer, cardLanguage(card, sessionLanguage))

/** Null when the card has no Extra. */
fun elaborationUtterance(card: ScheduledCard, sessionLanguage: String): Utterance? {
    if (card.fields.extra.isEmpty()) return null
    return Utterance(UtterancePurpose.ELABORATION, card.fields.extra, cardLanguage(card, sessionLanguage))
}

/** Grading criteria are Prompt, ReferenceAnswer, RequiredConcepts and AcceptedAnswers. */
fun gradingContext(card: ScheduledCard, transcript: String, sessionLanguage: String): GradingContext =
    GradingContext(
        prompt = card.fields.prompt,
        referenceAnswer = card.fields.referenceAnswer,
        requiredConcepts = card.fields.requiredConcepts,
        acceptedAnswers = card.fields.acceptedAnswers,
        learnerAnswer = transcript,
        language = cardLanguage(card, sessionLanguage),
    )
