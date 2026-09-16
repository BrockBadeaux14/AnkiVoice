"""AV-016 drift guard: the pinned grading instruction and decoding settings.

AV-006's latencies, token counts and failure rate describe one instruction and one set
of decoding settings. If the Kotlin grader's copy drifts from tools/av006_providers.py,
those measurements no longer describe what the app sends. No Gradle run, no network.
"""
import ast
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
AV006 = ROOT / "tools" / "av006_providers.py"
PROVIDER = ROOT / "android" / "provider" / "src" / "main" / "kotlin" / "org" / "ankivoice" / "provider"
INSTRUCTION = PROVIDER / "GradingInstruction.kt"
GRADER = PROVIDER / "SemanticGrader.kt"
FREE_ROUTE = PROVIDER / "FreeRoute.kt"

LABELS = ("correct", "partial", "incorrect", "uncertain")


def av006(name):
    """One module-level constant of tools/av006_providers.py, read without importing it.

    The experiment script imports fcntl, which only exists on the POSIX evidence host.
    This guard is about the text it pins, so it parses the source instead of running it.
    """
    module = ast.parse(AV006.read_text(encoding="utf-8"))
    for node in module.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(target, ast.Name) and target.id == name for target in node.targets
        ):
            return ast.literal_eval(node.value)
    raise AssertionError(f"tools/av006_providers.py no longer defines {name}")


def kotlin_constant(text, name):
    """The value of a `const val <name>: String =` declaration, joined across lines."""
    block = re.search(rf'const val {name}: String =(.*?)\n\n', text, re.S)
    if block is None:
        raise AssertionError(f"{name} is not declared as a String constant")
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', block.group(1)))


class PinnedInstruction(unittest.TestCase):
    def setUp(self):
        self.measured = av006("GRADING_INSTRUCTION")
        self.instruction = INSTRUCTION.read_text(encoding="utf-8")
        self.grader = GRADER.read_text(encoding="utf-8")

    def test_instruction_matches_the_measured_pass_two_text(self):
        self.assertEqual(self.measured, kotlin_constant(self.instruction, "PINNED"))

    def test_learner_answer_field_matches_the_measured_boundary(self):
        self.assertEqual("learner_answer", kotlin_constant(self.instruction, "LEARNER_ANSWER_FIELD"))
        self.assertIn("learner_answer", self.measured)

    def test_the_rubric_sentences_name_only_fields_the_note_type_already_has(self):
        """No deck edit and no new note field: the optional rubric is RequiredConcepts."""
        fields = {"Prompt", "ReferenceAnswer", "RequiredConcepts", "AcceptedAnswers", "learner_answer"}
        named = r"[a-z]+_[a-z]+|[A-Z][a-z]+[A-Z][A-Za-z]+"
        for name in ("WITH_CONCEPTS", "WITHOUT_CONCEPTS"):
            sentence = kotlin_constant(self.instruction, name)
            self.assertTrue(sentence.endswith("."), name)
            mentioned = set(re.findall(named, sentence))
            self.assertLessEqual(mentioned, fields, f"{name} names a field the note type does not have")
            self.assertIn("learner_answer", mentioned, name)
        self.assertIn("RequiredConcepts", kotlin_constant(self.instruction, "WITH_CONCEPTS"))

    def test_only_the_four_labels_are_accepted(self):
        labels = re.search(r"GradeLabel\.entries\.associateBy \{ it\.specName \}", self.instruction)
        self.assertIsNotNone(labels, "the accepted labels must come from GradeLabel, not a second list")
        for label in LABELS:
            self.assertIn(label, self.measured)

    def test_the_deadline_and_the_single_retry_are_pinned(self):
        self.assertIn("const val DEADLINE_MS: Int = 20_000", self.grader)
        self.assertIn("const val ATTEMPTS: Int = 2", self.grader)

    def test_the_route_keeps_AV_006_decoding_settings(self):
        route = FREE_ROUTE.read_text(encoding="utf-8")
        model, provider = av006("CANDIDATES")["grade-b"]
        self.assertIn(f'const val MODEL: String = "{model}"', route)
        self.assertIn(f'const val PROVIDER: String = "{provider}"', route)
        self.assertIn("const val MAX_TOKENS: Int = 1024", route)
        self.assertIn("const val TEMPERATURE: Int = 0", route)


if __name__ == "__main__":
    unittest.main()
