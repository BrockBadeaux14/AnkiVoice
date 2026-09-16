"""AV-014 guard: the command vocabulary and the rules the card actually decided.

The `:core` tests prove behaviour against the fakes. This one parses the Kotlin source and
fails if the decisions behind that behaviour drift: the vocabulary itself, the touch-only
resume, which commands the confidence gate protects, the context rule, and the fact that
no command path can reach the writer. No Gradle run, no emulator, no network.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "android" / "core" / "src"
COMMANDS = CORE / "main" / "kotlin" / "org" / "ankivoice" / "core" / "commands"
VOCABULARY = COMMANDS / "VoiceCommands.kt"
ROUTER = COMMANDS / "CommandRouter.kt"
PARSER_TEST = CORE / "test" / "kotlin" / "org" / "ankivoice" / "core" / "commands" / "VoiceCommandParserTest.kt"
ROUTER_TEST = CORE / "test" / "kotlin" / "org" / "ankivoice" / "core" / "commands" / "CommandRouterTest.kt"

#: The vocabulary the card names, in the order the card names it.
VOCABULARY_SPEC_NAMES = [
    "repeat", "reveal", "pause", "resume", "finish-session", "skip",
    "rate-again", "rate-hard", "rate-good", "rate-easy", "confirm", "change",
]

#: The commands that advance past the card, reveal the answer, or propose or confirm a
#: rating. Only these may be blocked by the confidence gate.
GUARDED = {
    "reveal", "finish-session", "skip",
    "rate-again", "rate-hard", "rate-good", "rate-easy", "confirm",
}

ENTRY = re.compile(
    r"^\s{4}([A-Z_]+)\(\"([a-z-]+)\", CommandScope\.([A-Z_]+), guarded = (true|false)(.*?)\),$",
    re.MULTILINE,
)


class Vocabulary(unittest.TestCase):
    def setUp(self):
        self.source = VOCABULARY.read_text(encoding="utf-8")
        self.entries = ENTRY.findall(self.source)

    def test_the_vocabulary_is_the_one_the_card_names(self):
        self.assertEqual(VOCABULARY_SPEC_NAMES, [spec for _, spec, _, _, _ in self.entries])

    def test_resume_is_the_only_touch_only_command(self):
        touch_only = [spec for _, spec, _, _, rest in self.entries if "touchOnly = true" in rest]
        self.assertEqual(["resume"], touch_only)

    def test_the_confidence_gate_protects_exactly_the_commands_that_advance_reveal_or_rate(self):
        guarded = {spec for _, spec, _, flag, _ in self.entries if flag == "true"}
        self.assertEqual(GUARDED, guarded)

    def test_the_four_ratings_are_bound_to_again_hard_good_easy(self):
        ratings = {
            spec: int(re.search(r"rating = (\d)", rest).group(1))
            for _, spec, _, _, rest in self.entries
            if "rating = " in rest
        }
        self.assertEqual({"rate-again": 1, "rate-hard": 2, "rate-good": 3, "rate-easy": 4}, ratings)

    def test_no_command_resolves_inside_the_answer_window(self):
        # The scopes a command may carry, and none of them contains ANSWER.
        scopes = {scope for _, _, scope, _, _ in self.entries}
        self.assertLessEqual(scopes, {"OUTSIDE_ANSWER", "COMMAND_ONLY", "CONFIRMATION_ONLY"})
        declared = re.search(
            r"enum class CommandScope\(val contexts: Set<CommandContext>\) \{(.*?)\n\}",
            self.source,
            re.DOTALL,
        )
        self.assertIsNotNone(declared)
        self.assertNotIn("CommandContext.ANSWER", declared.group(1))

    def test_confirm_and_change_belong_to_the_pre_commit_exchange_only(self):
        scopes = {spec: scope for _, spec, scope, _, _ in self.entries}
        self.assertEqual("CONFIRMATION_ONLY", scopes["confirm"])
        self.assertEqual("CONFIRMATION_ONLY", scopes["change"])
        self.assertEqual("COMMAND_ONLY", scopes["resume"])

    def test_again_stays_ambiguous_between_repeat_and_the_rating(self):
        phrases = dict(
            re.findall(r"VoiceCommand\.([A-Z_]+) to\s+?listOf\((.*?)\),\n", self.source, re.DOTALL)
        )
        for command in ("REPEAT", "RATE_AGAIN"):
            self.assertIn('"again"', phrases[command], command)


class NoCommandWritesAReview(unittest.TestCase):
    """The card states it twice, so it is guarded statically as well as by the sweep."""

    def test_the_router_has_no_path_to_the_writer(self):
        source = ROUTER.read_text(encoding="utf-8")
        for forbidden in (".commit(", "ReviewWriter", "writer."):
            self.assertNotIn(forbidden, source, f"{forbidden} appears in CommandRouter.kt")

    def test_the_sweep_asserts_it_from_every_position(self):
        source = ROUTER_TEST.read_text(encoding="utf-8")
        self.assertIn("fun `no command path writes a review`", source)
        # Every position the sweep visits, so a position cannot be dropped silently.
        for position in ("idle", "thinking", "capturing", "grading", "proposing", "confirmed", "paused", "stopped"):
            self.assertIn(f'"{position}" to', source, position)


class ContextRule(unittest.TestCase):
    """Command words spoken as answers must be graded as answers."""

    #: Each contains a command phrase and is a plausible VoiceQA answer.
    CORPUS = (
        "repeat the experiment",
        "pause the reaction",
        "skip a generation",
    )

    def test_the_false_trigger_corpus_is_replayed_in_both_suites(self):
        parser = PARSER_TEST.read_text(encoding="utf-8")
        router = ROUTER_TEST.read_text(encoding="utf-8")
        for phrase in self.CORPUS:
            self.assertIn(phrase, parser, phrase)
            self.assertIn(phrase, router, phrase)

    def test_the_bare_command_words_are_in_the_corpus_too(self):
        # The hard case: an answer that is *exactly* a command phrase.
        router = ROUTER_TEST.read_text(encoding="utf-8")
        corpus = re.search(r"falseTriggers = listOf\((.*?)\n    \)", router, re.DOTALL)
        self.assertIsNotNone(corpus)
        for word in ('"again"', '"good"', '"skip"', '"yes"'):
            self.assertIn(word, corpus.group(1), word)

    def test_the_parser_returns_the_answer_window_text_unchanged(self):
        source = VOCABULARY.read_text(encoding="utf-8")
        self.assertIn(
            "if (context == CommandContext.ANSWER) return CommandRecognition.AnswerText(text)",
            source,
            "the context rule must return the utterance before any matching happens",
        )


if __name__ == "__main__":
    unittest.main()
