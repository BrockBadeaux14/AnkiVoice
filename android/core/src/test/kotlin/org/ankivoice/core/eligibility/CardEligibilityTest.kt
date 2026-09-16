package org.ankivoice.core.eligibility

import java.io.File
import org.ankivoice.core.contracts.CardIdentity
import org.ankivoice.core.contracts.CardState
import org.ankivoice.core.contracts.ScheduledCard
import org.ankivoice.core.contracts.UtterancePurpose
import org.ankivoice.core.contracts.VOICEQA_MODEL
import org.ankivoice.core.contracts.VoiceQAFields
import org.ankivoice.core.contracts.elaborationUtterance
import org.ankivoice.core.contracts.gradingContext
import org.ankivoice.core.contracts.questionUtterance
import org.ankivoice.core.contracts.revealUtterance
import org.ankivoice.core.grading.MiniJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AV-010: classify already-read cards. Rejection fixtures come from AV-002; the
 * malformed-Language case is not in those fixtures and is constructed here.
 */
class CardEligibilityTest {
    private val examples: Map<String, Map<String, String>> = run {
        val path = checkNotNull(System.getProperty("ankivoice.voiceqaNoteType"))
        val root = MiniJson.parse(File(path).readText()) as Map<*, *>
        (root["examples"] as List<*>).associate { entry ->
            val example = entry as Map<*, *>
            val fields = (example["fields"] as Map<*, *>).entries.associate { (key, value) ->
                key as String to value as String
            }
            example["id"] as String to fields
        }
    }
    private val rejection: List<FixtureCard> = run {
        val path = checkNotNull(System.getProperty("ankivoice.voiceqaScenarios"))
        val root = MiniJson.parse(File(path).readText()) as Map<*, *>
        val profile = ((root["profiles"] as Map<*, *>)["rejection"] as Map<*, *>)
        (profile["cards"] as List<*>).mapIndexed { index, item ->
            val card = item as Map<*, *>
            val id = card["id"] as String
            val exampleId = card["example"] as String?
            val overrides = ((card["fields"] as Map<*, *>?) ?: emptyMap<Any?, Any?>()).entries
                .associate { (key, value) -> key as String to value as String }
            val model = card["note_type"] as String? ?: VOICEQA_MODEL
            val fields = when {
                exampleId != null -> examples.getValue(exampleId) + overrides
                else -> overrides
            }
            FixtureCard(id, index + 1L, model, fields, card["expected_rejection"] as String?)
        }
    }

    private data class FixtureCard(
        val fixtureId: String,
        val cardId: Long,
        val model: String,
        val fields: Map<String, String>,
        val expectedRejection: String?,
    ) {
        val observed: ObservedCard
            get() = ObservedCard(
                identity = CardIdentity(cardId, cardId, 1, 0, model),
                fields = VoiceQAFields(
                    prompt = fields["Prompt"] ?: fields["Front"] ?: fields["Text"].orEmpty(),
                    referenceAnswer = fields["ReferenceAnswer"] ?: fields["Back"].orEmpty(),
                    requiredConcepts = fields["RequiredConcepts"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() },
                    acceptedAnswers = fields["AcceptedAnswers"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() },
                    language = fields["Language"].orEmpty(),
                    extra = fields["Extra"].orEmpty(),
                ),
                fieldNames = fields.keys.toList(),
            )
    }

    private fun fixture(id: String) = rejection.single { it.fixtureId == id }

    @Test
    fun `valid-control from the rejection fixtures is studiable`() {
        val result = classify(fixture("valid-control").observed, "de-DE")
        val studiable = assertInstanceOf(Eligibility.Studiable::class.java, result)
        assertEquals("en-US", studiable.language)
    }

    @Test
    fun `basic and cloze are unsupported note types`() {
        for (id in listOf("basic", "cloze")) {
            val card = fixture(id)
            val result = classify(card.observed, "en-US")
            val ineligible = assertInstanceOf(Eligibility.Ineligible::class.java, result, id)
            assertEquals(IneligibleReason.UNSUPPORTED_NOTE_TYPE, ineligible.reason, id)
            assertEquals("unsupported_note_type", card.expectedRejection, id)
            assertEquals(UtterancePurpose.ANNOUNCEMENT, ineligible.announcement.purpose)
            assertTrue(ineligible.announcement.text.contains(card.model), ineligible.announcement.text)
            assertTrue(ineligible.announcement.text.contains("AnkiDroid"), ineligible.announcement.text)
        }
    }

    @Test
    fun `missing-reference is a blank required field`() {
        val card = fixture("missing-reference")
        val result = classify(card.observed, "en-US")
        val ineligible = assertInstanceOf(Eligibility.Ineligible::class.java, result)
        assertEquals(IneligibleReason.BLANK_REQUIRED_FIELD, ineligible.reason)
        assertEquals("missing_reference_answer", card.expectedRejection)
        assertTrue(ineligible.announcement.text.contains("ReferenceAnswer"), ineligible.announcement.text)
        assertTrue(ineligible.announcement.text.contains("AnkiDroid"), ineligible.announcement.text)
    }

    @Test
    fun `a malformed Language tag is its own ineligible reason`() {
        val base = fixture("valid-control").observed
        val malformed = base.copy(fields = base.fields.copy(language = "en_US"))
        val ineligible = assertInstanceOf(Eligibility.Ineligible::class.java, classify(malformed, "en-US"))
        assertEquals(IneligibleReason.MALFORMED_LANGUAGE, ineligible.reason)
        assertTrue(ineligible.announcement.text.contains("en_US"), ineligible.announcement.text)
        assertTrue(ineligible.announcement.text.contains("AnkiDroid"), ineligible.announcement.text)
    }

    @Test
    fun `an empty Language uses the session language and stays studiable`() {
        val base = fixture("valid-control").observed
        val unlabeled = base.copy(fields = base.fields.copy(language = ""))
        val result = classify(unlabeled, "de-DE")
        assertEquals(Eligibility.Studiable("de-DE"), result)
    }

    @Test
    fun `a well-formed but unavailable locale is still studiable`() {
        val base = fixture("valid-control").observed
        val localUse = base.copy(fields = base.fields.copy(language = "qaa"))
        assertEquals(Eligibility.Studiable("qaa"), classify(localUse, "en-US"))
    }

    @Test
    fun `a VoiceQA field-layout mismatch is distinct from a blank field`() {
        val base = fixture("valid-control").observed
        val mismatched = base.copy(fieldNames = listOf("Prompt", "Answer"))
        val ineligible = assertInstanceOf(Eligibility.Ineligible::class.java, classify(mismatched, "en-US"))
        assertEquals(IneligibleReason.FIELD_LAYOUT_MISMATCH, ineligible.reason)
        assertTrue(ineligible.announcement.text.contains("field"), ineligible.announcement.text.lowercase())
    }

    @Test
    fun `duplicate VoiceQA field names are a layout mismatch`() {
        val base = fixture("valid-control").observed
        val duplicates = base.copy(fieldNames = VOICEQA_FIELD_NAMES + "Prompt")
        val ineligible = assertInstanceOf(Eligibility.Ineligible::class.java, classify(duplicates, "en-US"))
        assertEquals(IneligibleReason.FIELD_LAYOUT_MISMATCH, ineligible.reason)
    }

    @Test
    fun `a blank Prompt is named as the required field at fault`() {
        val base = fixture("valid-control").observed
        val blank = base.copy(fields = base.fields.copy(prompt = "  "))
        val ineligible = assertInstanceOf(Eligibility.Ineligible::class.java, classify(blank, "en-US"))
        assertEquals(IneligibleReason.BLANK_REQUIRED_FIELD, ineligible.reason)
        assertTrue(ineligible.announcement.text.contains("Prompt"), ineligible.announcement.text)
    }

    @Test
    fun `schema names match the canonical installable note type`() {
        val path = checkNotNull(System.getProperty("ankivoice.voiceqaNoteType"))
        val root = MiniJson.parse(File(path).readText()) as Map<*, *>
        val names = (root["fields"] as List<*>).map { (it as Map<*, *>)["name"] }
        assertEquals(names, VOICEQA_FIELD_NAMES)
    }

    @Test
    fun `BCP 47 syntax accepts scripts extensions and private tags without requiring a voice`() {
        val base = fixture("valid-control").observed
        for (language in listOf("en", "zh-Hant-TW", "es-419", "en-US-u-ca-gregory", "x-private", "i-klingon", "und")) {
            assertEquals(Eligibility.Studiable(language), classify(base.copy(fields = base.fields.copy(language = language)), "en-US"), language)
        }
        for (language in listOf("en_US", "not a tag", "en-", "en--US", "en-u", "en-US ", " en-US", " ")) {
            val rejected = assertInstanceOf(Eligibility.Ineligible::class.java,
                classify(base.copy(fields = base.fields.copy(language = language)), "en-US"), language)
            assertEquals(IneligibleReason.MALFORMED_LANGUAGE, rejected.reason, language)
        }
    }

    @Test
    fun `value count mismatch and reordered fields remain layout failures`() {
        val base = fixture("valid-control").observed
        for (card in listOf(base.copy(fieldValueCount = 5), base.copy(fieldValueCount = 7),
            base.copy(fieldNames = VOICEQA_FIELD_NAMES.reversed()))) {
            assertEquals(IneligibleReason.FIELD_LAYOUT_MISMATCH,
                assertInstanceOf(Eligibility.Ineligible::class.java, classify(card, "en-US")).reason)
        }
    }

    @Test
    fun `question audio is Prompt alone and Extra never enters grading`() {
        val card = ScheduledCard(
            fixture("valid-control").observed.identity,
            CardState(0, 0, 0, 1, 0),
            fixture("valid-control").observed.fields,
            listOf(1, 2, 3, 4),
        )
        val question = questionUtterance(card, "en-US")
        assertEquals(card.fields.prompt, question.text)
        assertFalse(question.text.contains(card.fields.referenceAnswer))
        assertFalse(question.text.contains(card.fields.extra))
        assertEquals(card.fields.referenceAnswer, revealUtterance(card, "en-US").text)
        assertEquals(card.fields.extra, elaborationUtterance(card, "en-US")!!.text)
        val context = gradingContext(card, "Five.", "en-US")
        assertFalse(context.toString().contains(card.fields.extra))
        assertEquals(card.fields.prompt, context.prompt)
        assertEquals(card.fields.referenceAnswer, context.referenceAnswer)
    }
}
