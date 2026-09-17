"""AV-017 drift guard: the corpus table, the split discipline and what the harness may do.

The evaluation measures the shipped graders and changes neither. These checks fail if the
corpus stops matching the table the card fixed, if a scored path gains a threshold, if the
harness acquires a way to write a review, or if an STT-mistake answer stops being a real
capture. No Gradle run, no emulator, no network.
"""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / "fixtures" / "grading" / "av017-corpus.json"
QA = ROOT / "tools" / "av017-qa"
PROVIDER_TEST = ROOT / "android" / "provider" / "src" / "test" / "kotlin" / "org" / "ankivoice" / "provider"
HARNESS = PROVIDER_TEST / "GradingEvaluationTest.kt"
TRANSPORTS = PROVIDER_TEST / "EvaluationTransports.kt"
CORPUS_READER = PROVIDER_TEST / "EvaluationCorpus.kt"
INSTRUMENTATION = (
    ROOT / "android" / "app" / "src" / "androidTest" / "kotlin" / "org" / "ankivoice" / "app" /
    "EvaluationInstrumentation.kt"
)

# The table AV-017 fixed on September 15, 2026: category -> (tuning, held-out).
TABLE = {
    "paraphrase": (3, 6),
    "negation": (3, 5),
    "number-or-unit": (3, 5),
    "incomplete": (3, 6),
    "stt-mistake": (3, 6),
    "correct-short": (3, 6),
    "incorrect-short": (2, 6),
}


def corpus():
    return json.loads(CORPUS.read_text(encoding="utf-8"))


def code(path):
    """A Kotlin source with its comments removed, so prose about a symbol is not a use of it."""
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class CorpusTable(unittest.TestCase):
    def setUp(self):
        self.corpus = corpus()
        self.answers = self.corpus["answers"]

    def test_sixty_answers_split_twenty_forty(self):
        self.assertEqual(60, len(self.answers))
        self.assertEqual(20, sum(1 for a in self.answers if a["split"] == "tuning"))
        self.assertEqual(40, sum(1 for a in self.answers if a["split"] == "held-out"))

    def test_every_category_matches_the_fixed_table(self):
        for category, (tuning, held) in TABLE.items():
            inside = [a for a in self.answers if a["category"] == category]
            self.assertEqual(tuning, sum(1 for a in inside if a["split"] == "tuning"), category)
            self.assertEqual(held, sum(1 for a in inside if a["split"] == "held-out"), category)
        self.assertEqual(set(TABLE), {a["category"] for a in self.answers})

    def test_the_split_is_fixed_in_the_corpus_file_itself(self):
        """Nothing outside the corpus decides which answers are held out."""
        for answer in self.answers:
            self.assertIn(answer["split"], ("tuning", "held-out"), answer["id"])

    def test_every_baseline_card_carries_an_answer(self):
        self.assertEqual(
            set(self.corpus["baseline_cards"]),
            {a["fixture_card"] for a in self.answers},
        )

    def test_labels_are_human_judgements_with_a_written_rationale(self):
        for answer in self.answers:
            if answer["provenance"]["kind"] == "live-pending":
                self.assertIsNone(answer["label"], answer["id"])
                self.assertIsNone(answer["answer"], answer["id"])
                continue
            self.assertIn(answer["label"], ("correct", "partial", "incorrect"), answer["id"])
            self.assertTrue(answer["label_rationale"], answer["id"])

    def test_the_label_rule_grades_the_transcript_not_the_intent(self):
        rule = self.corpus["label_rule"]
        self.assertIn("transcript", rule["note"])
        for name in ("correct", "partial", "incorrect"):
            self.assertTrue(rule[name])


class SttSourcing(unittest.TestCase):
    def setUp(self):
        self.stt = [a for a in corpus()["answers"] if a["category"] == "stt-mistake"]

    def test_nine_stt_answers_and_none_is_authored(self):
        self.assertEqual(9, len(self.stt))
        for answer in self.stt:
            self.assertIn(
                answer["provenance"]["kind"],
                ("recorded", "live", "live-pending"),
                f"{answer['id']}: synthetic corruption is not an acceptable substitute",
            )

    def test_reused_recordings_are_never_held_out_evidence(self):
        """AV-006/AV-040/AV-042 output is regression context, so it stays in tuning."""
        for answer in self.stt:
            if answer["provenance"]["kind"] == "recorded":
                self.assertEqual("tuning", answer["split"], answer["id"])

    def test_every_reused_recording_names_its_evidence(self):
        for answer in self.stt:
            if answer["provenance"]["kind"] in ("recorded", "live"):
                self.assertTrue(answer["provenance"].get("evidence"), answer["id"])

    def test_the_sourcing_rule_is_recorded_in_the_corpus(self):
        rule = corpus()["stt_sourcing"]
        self.assertEqual(6, rule["required_live"])
        self.assertIn("Synthetic", rule["rule"])


class HarnessCannotWrite(unittest.TestCase):
    """The evaluation is advisory quality evidence; nothing in it can submit a review."""

    FORBIDDEN = ("GuardedReviewWriter", "ReviewTransport", "JournaledReviewWriter", "submitReview")

    def test_no_evaluation_source_can_reach_a_writer(self):
        for path in (HARNESS, TRANSPORTS, CORPUS_READER, INSTRUMENTATION):
            text = code(path)
            for name in self.FORBIDDEN:
                self.assertNotIn(name, text, f"{path.name} names {name}")

    def test_the_live_capture_builds_no_card_provider(self):
        text = code(INSTRUMENTATION)
        self.assertNotIn("CardProvider", text)
        self.assertNotIn("AndroidAccessPlatform", text)

    def test_the_harness_grades_through_the_shipped_graders_only(self):
        text = code(HARNESS)
        self.assertIn("SemanticGrader(provider", text)
        self.assertIn(".suggest(request, permitted)", text)
        # It must not decide a label or a rating of its own. Comments are stripped first,
        # so the KDoc may name the shipped mapping without counting as a use of it.
        self.assertNotIn("GradeLabel", text)
        self.assertNotIn("automaticProposal", text)

    def test_only_the_socket_is_substituted(self):
        """Both transports implement the shipped seam rather than replacing a grader."""
        text = code(TRANSPORTS)
        self.assertEqual(2, text.count(": HttpTransport {"))
        self.assertNotIn("RuleGrader", text)
        self.assertNotIn("GradingInstruction", text)


class QuotaAndGates(unittest.TestCase):
    def test_the_run_stays_inside_the_shipped_session_cap(self):
        """Requests per session plus #18's one retry must fit in QuotaLedger.SESSION_LIMIT."""
        harness = HARNESS.read_text(encoding="utf-8")
        per_session = int(re.search(r"REQUESTS_PER_SESSION = (\d+)", harness).group(1))
        ledger = (
            ROOT / "android" / "provider" / "src" / "main" / "kotlin" / "org" / "ankivoice" /
            "provider" / "QuotaLedger.kt"
        ).read_text(encoding="utf-8")
        session_limit = int(re.search(r"SESSION_LIMIT: Int = (\d+)", ledger).group(1))
        attempts = int(
            re.search(
                r"ATTEMPTS: Int = (\d+)",
                (
                    ROOT / "android" / "provider" / "src" / "main" / "kotlin" / "org" / "ankivoice" /
                    "provider" / "SemanticGrader.kt"
                ).read_text(encoding="utf-8"),
            ).group(1)
        )
        # AV-043: a turn may dispatch #18's attempt and retry on each route in order.
        routes = (
            ROOT / "android" / "provider" / "src" / "main" / "kotlin" / "org" / "ankivoice" /
            "provider" / "GradingRoute.kt"
        ).read_text(encoding="utf-8")
        order = re.search(r"val ORDER: List<GradingRoute> = listOf\(([^)]*)\)", routes).group(1)
        route_count = len([name for name in order.split(",") if name.strip()])
        self.assertEqual(2, route_count)
        self.assertLessEqual(per_session * attempts * route_count, session_limit)

    def test_the_harness_uses_the_shipped_durable_ledger(self):
        self.assertIn("QuotaLedger(ledgerFile)", HARNESS.read_text(encoding="utf-8"))

    def test_the_scorer_defines_no_target_error_rate(self):
        score = (QA / "score.py").read_text(encoding="utf-8")
        self.assertIn('"target_error_rate": None', score)
        self.assertIn('"pass_fail": None', score)
        self.assertIn("defines no target error rate and gates nothing", score)

    def test_the_rule_and_ai_paths_are_never_merged(self):
        score = (QA / "score.py").read_text(encoding="utf-8")
        self.assertIn('paths = {"rule-only": measure(rule, "rule-only")}', score)
        self.assertIn('"status": "not-run"', score)

    def test_an_unrun_ai_path_is_not_reported_as_abstention(self):
        score = (QA / "score.py").read_text(encoding="utf-8")
        self.assertIn("This is not an ", score)
        self.assertIn("abstention and is not scored as one", score)
        self.assertIn("never attempted", score)


class Completeness(unittest.TestCase):
    def test_fewer_than_six_live_captures_is_reported_incomplete(self):
        score = (QA / "score.py").read_text(encoding="utf-8")
        self.assertIn("live_stt >= 6", score)
        self.assertIn("INCOMPLETE", score)

    def test_an_environment_fault_is_never_a_recognition_result(self):
        capture = (QA / "capture.py").read_text(encoding="utf-8")
        self.assertIn('result["usableAsSttMistake"] = False', capture)
        microphone = (QA / "microphone.py").read_text(encoding="utf-8")
        self.assertIn("FALLBACK_HZ = 220", microphone)

    def test_a_correct_recognition_cannot_fill_an_stt_slot(self):
        self.assertIn("!matches(transcript, expect)", code(INSTRUMENTATION))


if __name__ == "__main__":
    unittest.main()
