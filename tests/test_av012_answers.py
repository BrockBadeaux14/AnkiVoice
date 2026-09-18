"""AV-012 guard: the pinned answer limits and the recognition fixtures behind them.

The `:core` policy tests stand in for live audio, so the transcripts they replay must be
the ones AV-006 actually recorded and the deadlines must be the ones AV-042's accepted
handoff selected. If either drifts, the fake-driven tests stop standing for anything.
No Gradle run, no emulator, no network.
"""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "android" / "core" / "src"
POLICY = CORE / "main" / "kotlin" / "org" / "ankivoice" / "core" / "answer" / "AnswerBoundaries.kt"
POLICY_TEST = CORE / "test" / "kotlin" / "org" / "ankivoice" / "core" / "answer" / "AnswerBoundariesTest.kt"
AV006 = ROOT / "docs" / "testing" / "av006" / "evidence"
AV042 = ROOT / "docs" / "testing" / "av042" / "results.md"

#: ERROR_NO_MATCH, the code AV-006 recorded for the two one-word answers it missed.
NO_MATCH_CODE = 7


def recognitions():
    """Every AV-006 native-STT attempt, keyed by its corpus case id."""
    attempts = {}
    for name in ("native-stt-online-permitted.json", "native-stt-online-remainder.json"):
        for attempt in json.loads((AV006 / name).read_text(encoding="utf-8"))["attempts"]:
            attempts[attempt["id"]] = attempt
    return attempts


class RecognitionFixtures(unittest.TestCase):
    def setUp(self):
        self.attempts = recognitions()
        self.test_source = POLICY_TEST.read_text(encoding="utf-8")
        self.policy = POLICY.read_text(encoding="utf-8")

    def test_the_short_number_answers_are_the_ones_AV_006_missed(self):
        for case in ("demo-arithmetic-1", "demo-arithmetic-2"):
            attempt = self.attempts[case]
            self.assertEqual("error", attempt["status"], case)
            self.assertEqual(NO_MATCH_CODE, attempt["error_code"], case)
            self.assertIsNone(attempt["transcript"], case)
            # The policy test replays the spoken text the recognizer never returned.
            self.assertIn(f'"{attempt["expected_text"]}"', self.test_source, case)

    def test_the_two_you_substitution_is_replayed_verbatim(self):
        attempt = self.attempts["demo-explanation-1"]
        self.assertEqual("it puts you first then five then seven", attempt["transcript"])
        self.assertIn("two", attempt["expected_text"])
        self.assertNotIn("two", attempt["transcript"])
        self.assertIn(f'"{attempt["transcript"]}"', self.test_source)
        self.assertIn(f'"{attempt["expected_text"]}"', self.test_source)

    def test_the_negation_transcripts_are_replayed_verbatim(self):
        for case in ("demo-rules-1", "demo-rules-3", "demo-rules-4"):
            transcript = self.attempts[case]["transcript"]
            self.assertIsNotNone(transcript, case)
            self.assertIn(f'"{transcript}"', self.test_source, case)

    def test_the_policy_never_rewrites_a_transcript(self):
        """No normalization, spell-fixing or substitution may live in the answer policy."""
        for banned in ("lowercase(", "uppercase(", "replace(", "Normalizer", "RuleGrader"):
            self.assertNotIn(banned, self.policy, banned)


class PinnedLimits(unittest.TestCase):
    def setUp(self):
        self.policy = POLICY.read_text(encoding="utf-8")
        self.handoff = AV042.read_text(encoding="utf-8")

    def kotlin_number(self, name):
        found = re.search(rf"val {name}: Long = ([\d_]+)", self.policy)
        self.assertIsNotNone(found, f"{name} is not declared with a literal default")
        return int(found.group(1).replace("_", ""))

    def test_the_window_and_finalization_deadlines_match_the_accepted_handoff(self):
        """AV-042 selected these; AV-050 amended two of them, and the handoff records it.

        The point of this guard is that the code and the accepted handoff agree — not that
        the numbers never move. AV-050 replaced the explicit Start answer with a microphone
        that opens itself, so the unbounded thinking time the tap bought became a 15,000 ms
        recall pre-roll, and the window — which now bounds speaking alone — was cut to
        5,000 ms. Finalization is untouched.
        """
        self.assertEqual(15_000, self.kotlin_number("prerollMs"))
        self.assertEqual(5_000, self.kotlin_number("windowMs"))
        self.assertEqual(5_000, self.kotlin_number("finalizationMs"))
        self.assertIn("15,000 ms from Start answer", self.handoff)
        self.assertIn("5,000 ms deadline from Done/expiry", self.handoff)
        self.assertIn("AV-050", self.handoff, "the handoff does not record the amendment")

    def test_no_automatic_rearms_are_permitted(self):
        self.assertIn("const val AUTOMATIC_REARMS: Int = 0", self.policy)
        self.assertIn("Zero automatic re-arms", self.handoff)

    def test_the_six_answer_states_stay_separate(self):
        states = re.findall(r'^\s{4}([A-Z_]+)\("([a-z-]+)"\),$', self.policy, re.M)
        names = {name for _, name in states}
        for required in ("partial", "final", "user-corrected", "cancelled", "timed-out", "failed"):
            self.assertIn(required, names, required)


if __name__ == "__main__":
    unittest.main()
