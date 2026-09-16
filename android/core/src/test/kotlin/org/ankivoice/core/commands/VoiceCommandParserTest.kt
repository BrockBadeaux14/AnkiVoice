package org.ankivoice.core.commands

import org.ankivoice.core.contracts.Confidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AV-014's parser, on its own: the vocabulary, the context rule and the false-trigger set.
 *
 * No session, no fakes, no clock. Every test here is about what one utterance *means*;
 * [CommandRouterTest] covers what running it does.
 */
class VoiceCommandParserTest {

    /**
     * Command words inside ordinary answers. Each is a plausible VoiceQA answer that
     * contains a command phrase, and none of them may ever execute one.
     */
    private val falseTriggers = listOf(
        "repeat the experiment three times",
        "pause the reaction before adding the acid",
        "skip a generation, so the trait reappears in the grandchildren",
        "show the answer key to the second question",
        "again",
        "good",
        "hard",
        "easy",
        "yes",
        "no",
        "continue the sequence with green",
        "repeat",
        "skip",
    )

    @Test
    fun `the vocabulary covers every command the card names`() {
        assertEquals(
            listOf(
                "repeat", "reveal", "pause", "resume", "finish-session", "skip",
                "rate-again", "rate-hard", "rate-good", "rate-easy", "confirm", "change",
            ),
            VoiceCommand.entries.map { it.specName },
        )
        assertEquals(
            listOf(1, 2, 3, 4),
            VoiceCommand.entries.mapNotNull { it.rating },
            "Again, Hard, Good and Easy each propose exactly one rating",
        )
        for (command in VoiceCommand.entries) {
            assertTrue(
                COMMAND_PHRASES.any { (_, candidates) -> command in candidates },
                "${command.specName} has no spoken phrase",
            )
            assertFalse(
                CommandContext.ANSWER in command.contexts,
                "${command.specName} must not resolve inside the answer window",
            )
        }
    }

    /** Resume is the one command an on-screen control alone may run, and the only one. */
    @Test
    fun `resume is the only touch-only command`() {
        assertEquals(
            listOf(VoiceCommand.RESUME),
            VoiceCommand.entries.filter { it.touchOnly },
        )
    }

    @Test
    fun `the guarded set is exactly the commands that advance, reveal or rate`() {
        assertEquals(
            setOf(
                VoiceCommand.REVEAL, VoiceCommand.FINISH_SESSION, VoiceCommand.SKIP,
                VoiceCommand.RATE_AGAIN, VoiceCommand.RATE_HARD, VoiceCommand.RATE_GOOD,
                VoiceCommand.RATE_EASY, VoiceCommand.CONFIRM,
            ),
            VoiceCommand.entries.filter { it.guarded }.toSet(),
        )
    }

    // -- the context rule ----------------------------------------------------- //

    /**
     * The whole point of context-only disambiguation: inside AV-012's answer window the
     * vocabulary does not exist, so an answer that happens to contain a command word is
     * graded as the answer it is.
     */
    @Test
    fun `inside the answer window every utterance is answer text, unchanged`() {
        val spoken = COMMAND_PHRASES.keys + falseTriggers
        for (text in spoken) {
            for (confidence in Confidence.entries) {
                val parsed = CommandVocabulary.parse(text, CommandContext.ANSWER, confidence)
                val answer = assertIs<CommandRecognition.AnswerText>(parsed, text)
                assertSame(text, answer.text, "no token may be stripped from \"$text\"")
            }
        }
    }

    @Test
    fun `nothing resolves while the context is unavailable`() {
        for (phrase in COMMAND_PHRASES.keys) {
            val parsed = CommandVocabulary.parse(phrase, CommandContext.UNAVAILABLE, Confidence.SUFFICIENT)
            assertTrue(
                parsed is CommandRecognition.OutOfContext || parsed is CommandRecognition.Ambiguous,
                "\"$phrase\" resolved to $parsed while the session offered no command context",
            )
        }
    }

    @Test
    fun `confirm and change belong to the pre-commit exchange only`() {
        for (phrase in listOf("confirm", "yes", "change", "no")) {
            assertIs<CommandRecognition.OutOfContext>(
                CommandVocabulary.parse(phrase, CommandContext.COMMAND, Confidence.SUFFICIENT),
                phrase,
            )
            assertIs<CommandRecognition.Recognized>(
                CommandVocabulary.parse(phrase, CommandContext.CONFIRMATION, Confidence.SUFFICIENT),
                phrase,
            )
        }
    }

    @Test
    fun `resume resolves outside the pre-commit exchange only`() {
        assertIs<CommandRecognition.Recognized>(
            CommandVocabulary.parse("resume", CommandContext.COMMAND, Confidence.SUFFICIENT),
        )
        assertIs<CommandRecognition.OutOfContext>(
            CommandVocabulary.parse("resume", CommandContext.CONFIRMATION, Confidence.SUFFICIENT),
        )
    }

    // -- matching -------------------------------------------------------------- //

    @Test
    fun `every unambiguous phrase resolves to its command in a command capture`() {
        for ((phrase, candidates) in COMMAND_PHRASES) {
            val command = candidates.singleOrNull() ?: continue
            if (!command.resolvesIn(CommandContext.COMMAND)) continue
            val parsed = CommandVocabulary.parse(phrase, CommandContext.COMMAND, Confidence.SUFFICIENT)
            assertEquals(command, assertIs<CommandRecognition.Recognized>(parsed, phrase).command, phrase)
        }
    }

    @Test
    fun `case and punctuation do not change the match, and no word is rewritten`() {
        val parsed = CommandVocabulary.parse("  Skip This Card! ", CommandContext.COMMAND, Confidence.SUFFICIENT)
        assertEquals(VoiceCommand.SKIP, assertIs<CommandRecognition.Recognized>(parsed).command)
        assertEquals("skip this card", CommandVocabulary.normalize("  Skip This Card! "))
        // Normalization folds case and punctuation and nothing else: no stemming, no
        // synonyms and no spelling correction.
        assertEquals("five blocks", CommandVocabulary.normalize("Five blocks."))
        assertEquals("it puts you first then five then seven", CommandVocabulary.normalize("It puts you first, then five, then seven"))
    }

    /**
     * Only a whole utterance matches. A sentence that merely contains a command word is
     * not a command, even inside a command capture.
     */
    @Test
    fun `a sentence containing a command word is not a command`() {
        for (text in falseTriggers) {
            val parsed = CommandVocabulary.parse(text, CommandContext.COMMAND, Confidence.SUFFICIENT)
            if (CommandVocabulary.normalize(text) in COMMAND_PHRASES) continue
            assertIs<CommandRecognition.Unrecognized>(parsed, text)
        }
    }

    @Test
    fun `an ambiguous phrase names its candidates and resolves to none of them`() {
        val parsed = CommandVocabulary.parse("again", CommandContext.CONFIRMATION, Confidence.SUFFICIENT)
        val ambiguous = assertIs<CommandRecognition.Ambiguous>(parsed)
        assertEquals(
            setOf(VoiceCommand.REPEAT, VoiceCommand.RATE_AGAIN),
            ambiguous.candidates.toSet(),
        )
    }

    // -- confidence -------------------------------------------------------------- //

    @Test
    fun `a guarded command is never recognized below sufficient confidence`() {
        for (command in VoiceCommand.entries.filter { it.guarded }) {
            val phrase = COMMAND_PHRASES.entries.first { (_, set) -> set == setOf(command) }.key
            for (confidence in listOf(Confidence.LOW, Confidence.ABSENT)) {
                val parsed = CommandVocabulary.parse(phrase, CommandContext.CONFIRMATION, confidence)
                val uncertain = assertIs<CommandRecognition.Uncertain>(parsed, "$phrase at $confidence")
                assertEquals(command, uncertain.command)
                assertEquals(confidence, uncertain.confidence)
            }
        }
    }

    /**
     * Repeat, pause and change only replay audio, stop the microphone or reopen a choice.
     * Refusing them at absent confidence would leave nothing usable by voice at all: the
     * pinned recognizer reported absent confidence throughout AV-013's live check.
     */
    @Test
    fun `an unguarded command still runs at absent confidence`() {
        assertIs<CommandRecognition.Recognized>(
            CommandVocabulary.parse("repeat the question", CommandContext.COMMAND, Confidence.ABSENT),
        )
        assertIs<CommandRecognition.Recognized>(
            CommandVocabulary.parse("pause", CommandContext.COMMAND, Confidence.ABSENT),
        )
        assertIs<CommandRecognition.Recognized>(
            CommandVocabulary.parse("change", CommandContext.CONFIRMATION, Confidence.ABSENT),
        )
    }
}

/** JUnit 5 has no typed assertion of its own; this keeps the failure messages readable. */
internal inline fun <reified T> assertIs(value: Any?, label: String = ""): T {
    assertTrue(value is T, "expected ${T::class.simpleName} but was $value${if (label.isEmpty()) "" else " for $label"}")
    return value as T
}
