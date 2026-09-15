package org.ankivoice.core.grading

import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.GradingContext
import org.ankivoice.core.contracts.GradingResult
import java.text.Normalizer
import java.util.Locale

/**
 * AV-015: conservative, deterministic grading that runs before any AI grader.
 *
 * The rules can only conclude [GradeLabel.CORRECT], which proposes Good through the
 * existing mapping. Anything they cannot match is null: no rule-based label and no
 * proposed rating. The caller then asks the AI grader (#18) if it is available, and the
 * learner for an explicit self-grade otherwise. The rules never conclude incorrect,
 * partial or uncertain, and never propose Again, Hard or Easy.
 *
 * Every result is a suggestion. Nothing is written, and the learner confirms before any
 * review is submitted (#14, #25). The prompt and RequiredConcepts are not read; concept
 * coverage belongs to #18.
 */
object RuleGrader {
    /** A fuzzy match may only touch a word with at least this many letters, on both sides. */
    const val MIN_FUZZY_LETTERS: Int = 5

    /**
     * A rule-based `correct`, or null. Deterministic: the same context always gives the
     * same answer. Exact matches win over fuzzy ones; within each step the reference
     * answer is tried first, then the accepted answers in order.
     */
    fun grade(context: GradingContext): GradingResult? {
        val heard = words(context.learnerAnswer)
        if (heard.isEmpty()) return null
        val targets = targets(context)
        targets.firstOrNull { it.words == heard }?.let { target ->
            return GradingResult(GradeLabel.CORRECT, "Exact match with ${target.name}.")
        }
        for (target in targets) {
            val (said, expected) = singleSlip(heard, target.words) ?: continue
            return GradingResult(
                GradeLabel.CORRECT,
                "Fuzzy match with ${target.name}: heard \"$said\" for \"$expected\".",
            )
        }
        return null
    }

    /**
     * NFKC, case folding, punctuation removed, whitespace collapsed and trimmed. Nothing
     * else: no stemming, synonyms, stop words or reordering.
     *
     * An apostrophe between two word characters is kept, written as `'`, so "isn’t" and
     * "isn't" agree. Punctuation between two digits is kept, so "3.5" never becomes "35" or
     * "3 5". Percent, per-mille and prime signs are units and are kept. Every other
     * punctuation mark becomes a space, so "green,blue" and "green blue" agree.
     */
    fun normalize(text: String): String {
        val folded = nfkc(caseFold(nfkc(text)))
        val points = folded.codePoints().toArray()
        val out = StringBuilder(folded.length)
        for ((i, point) in points.withIndex()) {
            val before = points.getOrNull(i - 1)
            val after = points.getOrNull(i + 1)
            when {
                Character.isWhitespace(point) || Character.isSpaceChar(point) -> out.append(' ')
                point in APOSTROPHES -> out.append(if (isWordChar(before) && isWordChar(after)) '\'' else ' ')
                point in UNIT_SIGNS -> out.appendCodePoint(point)
                isPunctuation(point) -> if (isDigit(before) && isDigit(after)) out.appendCodePoint(point) else out.append(' ')
                else -> out.appendCodePoint(point)
            }
        }
        return out.split(' ').filter(String::isNotEmpty).joinToString(" ")
    }

    /** The words of [normalize]'s output; empty for a blank or punctuation-only text. */
    fun words(text: String): List<String> = normalize(text).split(' ').filter(String::isNotEmpty)

    /**
     * True when fuzzy matching may never change [word]: a number word, a negation or a
     * unit. Digit sequences are excluded separately, because a fuzzy word must be
     * alphabetic. Expects a normalized word.
     */
    internal fun isProtected(word: String): Boolean =
        word in NUMBER_WORDS || word in NEGATIONS || word.endsWith("n't") || word in UNITS

    /** One inserted, deleted or substituted letter, or two adjacent letters swapped. */
    internal fun oneSlipApart(a: String, b: String): Boolean {
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        if (x.size == y.size) {
            val differ = x.indices.filter { x[it] != y[it] }
            return when (differ.size) {
                1 -> true
                2 -> differ[1] == differ[0] + 1 && x[differ[0]] == y[differ[1]] && x[differ[1]] == y[differ[0]]
                else -> false
            }
        }
        val (longer, shorter) = if (x.size > y.size) x to y else y to x
        if (longer.size - shorter.size != 1) return false
        val skip = shorter.indices.firstOrNull { shorter[it] != longer[it] } ?: shorter.size
        return (skip until shorter.size).all { shorter[it] == longer[it + 1] }
    }

    private class Target(val name: String, val words: List<String>)

    /** The reference answer, then each nonblank accepted answer, numbered by its line. */
    private fun targets(context: GradingContext): List<Target> = buildList {
        add(Target("the reference answer", words(context.referenceAnswer)))
        context.acceptedAnswers.forEachIndexed { index, line ->
            add(Target("accepted answer ${index + 1}", words(line)))
        }
    }.filter { it.words.isNotEmpty() }

    /**
     * The (heard, expected) pair when the word lists differ in exactly one position, and
     * that one word is a fuzzy-eligible slip on both sides; otherwise null.
     */
    private fun singleSlip(heard: List<String>, target: List<String>): Pair<String, String>? {
        if (heard.size != target.size) return null
        val position = heard.indices.filter { heard[it] != target[it] }.singleOrNull() ?: return null
        val said = heard[position]
        val expected = target[position]
        if (!fuzzyEligible(said) || !fuzzyEligible(expected)) return null
        return if (oneSlipApart(said, expected)) said to expected else null
    }

    private fun fuzzyEligible(word: String): Boolean {
        val points = word.codePoints().toArray()
        return points.size >= MIN_FUZZY_LETTERS && points.all(Character::isLetter) && !isProtected(word)
    }

    private fun nfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

    /** Full case folding: "Straße" and "STRASSE" agree, which lowercasing alone misses. */
    private fun caseFold(text: String): String = text.uppercase(Locale.ROOT).lowercase(Locale.ROOT)

    private fun isWordChar(point: Int?): Boolean = point != null &&
        (Character.isLetterOrDigit(point) || Character.getType(point).toByte() in MARKS)

    private fun isDigit(point: Int?): Boolean = point != null && Character.isDigit(point)

    private fun isPunctuation(point: Int): Boolean = Character.getType(point).toByte() in PUNCTUATION

    private val PUNCTUATION: Set<Byte> = setOf(
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
    )

    private val MARKS: Set<Byte> = setOf(
        Character.NON_SPACING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.ENCLOSING_MARK,
    )

    /** ASCII apostrophe, right single quotation mark, modifier letter apostrophe. */
    private val APOSTROPHES: Set<Int> = setOf(0x27, 0x2019, 0x2BC)

    /** Unicode classes these as punctuation, but they are units: % ‰ ‱ ′ ″ ‴. */
    private val UNIT_SIGNS: Set<Int> = setOf(0x25, 0x2030, 0x2031, 0x2032, 0x2033, 0x2034)

    /** Zero to twenty, the tens, hundred, thousand, million, their plurals and ordinals. */
    internal val NUMBER_WORDS: Set<String> = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
        "hundred", "thousand", "million", "hundreds", "thousands", "millions",
        "zeroth", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
        "seventeenth", "eighteenth", "nineteenth", "twentieth", "thirtieth", "fortieth", "fiftieth",
        "sixtieth", "seventieth", "eightieth", "ninetieth", "hundredth", "thousandth", "millionth",
    )

    /** Plus any word ending in `n't`, which [isProtected] checks separately. */
    internal val NEGATIONS: Set<String> = setOf("no", "not", "never", "none", "nothing", "nor", "neither", "cannot")

    /**
     * Unit names and letter abbreviations, singular and plural. Words shorter than
     * [MIN_FUZZY_LETTERS] can never be fuzzy-matched anyway; they are listed so the list
     * reads as what it is.
     */
    internal val UNITS: Set<String> = setOf(
        // Length
        "m", "km", "cm", "mm", "nm", "ft", "yd", "mi",
        "meter", "meters", "metre", "metres", "kilometer", "kilometers", "kilometre", "kilometres",
        "centimeter", "centimeters", "centimetre", "centimetres", "millimeter", "millimeters",
        "millimetre", "millimetres", "micrometer", "micrometers", "micrometre", "micrometres",
        "nanometer", "nanometers", "nanometre", "nanometres",
        "inch", "inches", "foot", "feet", "yard", "yards", "mile", "miles",
        // Mass
        "g", "kg", "mg", "lb", "lbs", "oz",
        "gram", "grams", "kilogram", "kilograms", "milligram", "milligrams", "microgram", "micrograms",
        "tonne", "tonnes", "ton", "tons", "pound", "pounds", "ounce", "ounces",
        // Volume and area
        "l", "ml",
        "liter", "liters", "litre", "litres", "milliliter", "milliliters", "millilitre", "millilitres",
        "gallon", "gallons", "quart", "quarts", "pint", "pints", "acre", "acres", "hectare", "hectares",
        // Time
        "s", "ms", "min", "h", "hr", "hrs",
        "second", "seconds", "millisecond", "milliseconds", "minute", "minutes", "hour", "hours",
        "day", "days", "week", "weeks", "month", "months", "year", "years", "decade", "decades",
        "century", "centuries",
        // Speed, temperature and ratio
        "mph", "kph",
        "knot", "knots", "degree", "degrees", "celsius", "fahrenheit", "kelvin", "kelvins",
        "percent", "percentage",
        // Physics and computing
        "v", "w", "kw", "kwh", "hz", "khz", "mhz", "ghz", "j", "kj", "n", "pa", "kpa",
        "cal", "kcal", "mol", "kb", "mb", "gb", "tb",
        "volt", "volts", "watt", "watts", "kilowatt", "kilowatts", "ampere", "amperes", "amp", "amps",
        "ohm", "ohms", "joule", "joules", "kilojoule", "kilojoules", "newton", "newtons",
        "pascal", "pascals", "hertz", "kilohertz", "megahertz", "gigahertz",
        "calorie", "calories", "kilocalorie", "kilocalories", "mole", "moles",
        "bit", "bits", "byte", "bytes", "kilobyte", "kilobytes", "megabyte", "megabytes",
        "gigabyte", "gigabytes", "terabyte", "terabytes",
    )
}
