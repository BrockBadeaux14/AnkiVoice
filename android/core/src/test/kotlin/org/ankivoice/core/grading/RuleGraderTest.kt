package org.ankivoice.core.grading

import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.GradingContext
import org.ankivoice.core.contracts.GradingResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AV-015: rules conclude `correct` on an exact or one-slip match, and nothing else. */
class RuleGraderTest {
    private fun context(answer: String, reference: String, accepted: List<String> = emptyList(), concepts: List<String> = emptyList()) =
        GradingContext("A prompt", reference, concepts, accepted, answer, "en-US")

    private fun grade(answer: String, reference: String, accepted: List<String> = emptyList()): GradingResult? =
        RuleGrader.grade(context(answer, reference, accepted))

    private fun assertNoLabel(answer: String, reference: String, accepted: List<String> = emptyList()) =
        assertNull(grade(answer, reference, accepted), "\"$answer\" against \"$reference\" $accepted")

    private fun assertMatch(answer: String, reference: String, reason: String, accepted: List<String> = emptyList()) =
        assertEquals(GradingResult(GradeLabel.CORRECT, reason), grade(answer, reference, accepted), "\"$answer\" against \"$reference\"")

    // Normalization

    @Test
    fun `punctuation, case and spacing variants normalize to the same text`() {
        assertEquals("green blue red", RuleGrader.normalize("Green, blue, red."))
        assertEquals("green blue red", RuleGrader.normalize("green blue red"))
        assertEquals("green blue red", RuleGrader.normalize("  GREEN;blue —  red!? "))
        assertEquals("green blue red", RuleGrader.normalize("\"Green\"\t(blue)\n[red]"))
    }

    @Test
    fun `normalization applies NFKC and full case folding`() {
        assertEquals("five", RuleGrader.normalize("ＦＩＶＥ"))
        assertEquals("five blocks", RuleGrader.normalize("ﬁve blocks"))
        assertEquals(RuleGrader.normalize("STRASSE"), RuleGrader.normalize("Straße"))
        assertEquals(RuleGrader.normalize("Café"), RuleGrader.normalize("Café"))
    }

    @Test
    fun `apostrophes inside words are kept and unified`() {
        assertEquals("it isn't valid", RuleGrader.normalize("It isn’t valid."))
        assertEquals("it isn't valid", RuleGrader.normalize("It isnʼt valid."))
        assertEquals("don't", RuleGrader.normalize("'Don't'"))
        assertEquals("the students work", RuleGrader.normalize("The students' work"))
    }

    @Test
    fun `negations, digits, number words and units survive`() {
        assertEquals("no it is not 5 km it's five miles", RuleGrader.normalize("No, it is not 5 km; it's five miles."))
        assertEquals("it never weighs 3.5 kg or 1,000 g", RuleGrader.normalize("It never weighs 3.5 kg or 1,000 g."))
        assertEquals("50% at 5 °c", RuleGrader.normalize("50% at 5 ℃."))
        assertEquals("50%", RuleGrader.normalize("50%"))
        assertEquals("cannot", RuleGrader.normalize("Cannot!"))
    }

    @Test
    fun `nothing else changes`() {
        assertEquals("the blocks are running", RuleGrader.normalize("The blocks are running."))
        assertEquals("red blue green", RuleGrader.normalize("red, blue, green"))
        assertEquals("", RuleGrader.normalize(" … ?! "))
        assertEquals(emptyList<String>(), RuleGrader.words(" … ?! "))
    }

    // Exact matches

    @Test
    fun `an exact normalized match with the reference answer suggests Good`() {
        val result = grade("green blue red", "Green, blue, red.")
        assertEquals(GradingResult(GradeLabel.CORRECT, "Exact match with the reference answer."), result)
        assertEquals(3, result!!.proposedRating(listOf(1, 2, 3, 4)))
        assertNull(result.proposedRating(listOf(1, 2, 4)), "no proposal when Good is not offered")
    }

    @Test
    fun `an accepted answer match names its line`() {
        assertMatch("Two, five, seven.", "It returns two, five, seven.", "Exact match with accepted answer 2.", listOf("", "Two five seven"))
        assertMatch("5", "Five blocks.", "Exact match with accepted answer 2.", listOf("Five", "5", "There are five blocks"))
    }

    @Test
    fun `the reference answer wins when several targets match`() {
        assertMatch("Five", "five", "Exact match with the reference answer.", listOf("Five"))
        assertMatch("5", "Five blocks.", "Exact match with accepted answer 1.", listOf("5", "5"))
    }

    @Test
    fun `required concepts are not matched by the rules`() {
        assertNull(RuleGrader.grade(context("Green first", "Green, blue, red.", concepts = listOf("Green first"))))
    }

    // Fuzzy matches

    @Test
    fun `one substituted, inserted or deleted letter in a long word matches`() {
        val reference = "The capital is Canberra."
        assertMatch("The capital is Canberro", reference, "Fuzzy match with the reference answer: heard \"canberro\" for \"canberra\".")
        assertMatch("The capital is Canberrra", reference, "Fuzzy match with the reference answer: heard \"canberrra\" for \"canberra\".")
        assertMatch("The capital is Canbera", reference, "Fuzzy match with the reference answer: heard \"canbera\" for \"canberra\".")
        assertMatch("It is Pariz", "It is Paris", "Fuzzy match with the reference answer: heard \"pariz\" for \"paris\".")
    }

    @Test
    fun `one adjacent swap matches`() {
        assertMatch("You recieve it", "You receive it.", "Fuzzy match with the reference answer: heard \"recieve\" for \"receive\".")
    }

    @Test
    fun `a fuzzy match can name an accepted answer`() {
        assertMatch("Mitochondira", "The powerhouse of the cell", "Fuzzy match with accepted answer 1: heard \"mitochondira\" for \"mitochondria\".", listOf("Mitochondria"))
    }

    @Test
    fun `an exact match anywhere wins over a fuzzy match`() {
        assertMatch("Canberra", "Canberro", "Exact match with accepted answer 1.", listOf("Canberra"))
        assertMatch("Canberra", "Canberra", "Exact match with the reference answer.", listOf("Canberro"))
    }

    // No rule-based label

    @Test
    fun `an empty transcript gets no label`() {
        assertNoLabel("", "Five blocks.")
        assertNoLabel("   ", "Five blocks.")
        assertNoLabel("?!", "…", listOf("", "  ", "."))
        assertNoLabel("", "", listOf(""))
    }

    @Test
    fun `number near misses get no label`() {
        assertNoLabel("fifteen", "fifty")
        assertNoLabel("5", "6")
        assertNoLabel("six", "five")
        assertNoLabel("12345", "12346")
        assertNoLabel("3.5", "35")
        // One slip apart, and still refused because they are number words.
        assertNoLabel("the sixth", "the sixty")
        assertNoLabel("the fifth", "the fifty")
        assertNoLabel("eight", "eighth")
        assertNoLabel("there", "three")
    }

    @Test
    fun `negation near misses get no label`() {
        assertNoLabel("valid", "not valid")
        assertNoLabel("is", "isn't")
        assertNoLabel("It is valid", "It isn't valid")
        assertNoLabel("It does not", "It does")
        // One slip apart, and still refused because they are negations.
        assertNoLabel("lever again", "never again")
        assertNoLabel("either one", "neither one")
        assertNoLabel("noting works", "nothing works")
        assertNoLabel("a cannon", "a cannot")
    }

    @Test
    fun `unit near misses get no label`() {
        assertNoLabel("5 km", "5 m")
        assertNoLabel("50", "50%")
        // One slip apart, and still refused because they are units.
        assertNoLabel("5 meter", "5 meters")
        assertNoLabel("2 liters", "2 litres")
        assertNoLabel("ten grass", "ten grams")
        assertNoLabel("three minute", "three minutes")
    }

    @Test
    fun `reordering gets no label`() {
        assertNoLabel("red, blue, green", "Green, blue, red.")
        assertNoLabel("capital Canberra is the", "Canberra is the capital")
    }

    @Test
    fun `more than one slip gets no label`() {
        assertNoLabel("Canberro is the capitel", "Canberra is the capital")
        assertNoLabel("Canbarro", "Canberra")
        assertNoLabel("reveice", "receive")
        assertNoLabel("Canberrraa", "Canberra")
    }

    @Test
    fun `a slip in a short word gets no label`() {
        assertNoLabel("glue", "blue")
        assertNoLabel("blues", "blue")
        assertNoLabel("the cat sat", "the car sat")
    }

    @Test
    fun `a slip that changes the word count gets no label`() {
        assertNoLabel("The capital Canberra", "The capital is Canberra")
        assertNoLabel("Canberra city", "Canberra")
    }

    @Test
    fun `a non-alphabetic word is never fuzzy-matched`() {
        assertNoLabel("Canberra2", "Canberra")
        assertNoLabel("wouldn't", "couldn't")
    }

    // Policy properties

    @Test
    fun `grading is deterministic`() {
        val examples = listOf(context("You recieve it", "You receive it."), context("fifteen", "fifty"), context("5", "Five", listOf("5")))
        for (example in examples) {
            assertEquals(RuleGrader.grade(example), RuleGrader.grade(example.copy()))
        }
    }

    @Test
    fun `every protected word is already normalized`() {
        for (word in RuleGrader.NUMBER_WORDS + RuleGrader.NEGATIONS + RuleGrader.UNITS) {
            assertEquals(word, RuleGrader.normalize(word), word)
            assertTrue(RuleGrader.isProtected(word), word)
        }
        assertTrue(RuleGrader.isProtected("wouldn't"))
        assertFalse(RuleGrader.isProtected("canberra"))
    }

    @Test
    fun `every long protected word refuses a one-letter slip on either side`() {
        // The control shows the same slip is accepted on an unprotected word.
        assertMatch("the canberrq", "the canberra", "Fuzzy match with the reference answer: heard \"canberrq\" for \"canberra\".")
        val long = (RuleGrader.NUMBER_WORDS + RuleGrader.NEGATIONS + RuleGrader.UNITS)
            .filter { word -> word.length >= RuleGrader.MIN_FUZZY_LETTERS && word.all(Char::isLetter) }
        assertTrue(long.size > 100, "expected the long protected words, found ${long.size}")
        for (word in long) {
            val slipped = word.dropLast(1) + if (word.last() == 'q') 'z' else 'q'
            assertNoLabel("the $slipped", "the $word")
            assertNoLabel("the $word", "the $slipped")
        }
    }

    @Test
    fun `one slip means one edit or one adjacent swap`() {
        assertTrue(RuleGrader.oneSlipApart("receive", "recieve"))
        assertTrue(RuleGrader.oneSlipApart("canberra", "canberro"))
        assertTrue(RuleGrader.oneSlipApart("canberra", "canbera"))
        assertTrue(RuleGrader.oneSlipApart("canbera", "canberra"))
        assertTrue(RuleGrader.oneSlipApart("canberra", "xcanberra"))
        assertFalse(RuleGrader.oneSlipApart("canberra", "canberra"))
        assertFalse(RuleGrader.oneSlipApart("receive", "reveice"))
        assertFalse(RuleGrader.oneSlipApart("canberra", "canbarro"))
        assertFalse(RuleGrader.oneSlipApart("canberra", "canber"))
        assertFalse(RuleGrader.oneSlipApart("canberra", "acnberar"))
    }
}
