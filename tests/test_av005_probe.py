"""Tests for the AV-005 turn corpus and evidence validator.

These use the real repository fixtures and the real retained evidence; nothing
here mocks the probe, and none of it needs an emulator or credentials.
"""

import copy
import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "docs" / "testing" / "av005" / "evidence" / "matrix"
ASSET = ROOT / "tools" / "av005-probe" / "app" / "src" / "main" / "assets" / "av005-turns.json"


def load(name, relative):
    specification = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


corpus_builder = load("av005_build_corpus", "tools/av005-probe/build_corpus.py")
validator = load("av005_validate", "tools/av005-probe/validate_evidence.py")


class TurnCorpusTest(unittest.TestCase):
    """The spoken turns must stay derived from the merged AV-002 fixtures."""

    @classmethod
    def setUpClass(cls):
        cls.built = corpus_builder.build()
        cls.note_type = json.loads(
            (ROOT / "fixtures" / "voiceqa" / "note-type.json").read_text(encoding="utf-8")
        )

    def test_three_rounds_of_every_example(self):
        examples = [example["id"] for example in self.note_type["examples"]]
        self.assertEqual(len(self.built["turns"]), 3 * len(examples))
        for round_number in (1, 2, 3):
            spoken = [t["example_id"] for t in self.built["turns"] if t["round"] == round_number]
            self.assertEqual(spoken, examples)

    def test_prompts_come_from_the_fixture(self):
        prompts = {e["id"]: e["fields"]["Prompt"] for e in self.note_type["examples"]}
        for turn in self.built["turns"]:
            self.assertEqual(turn["prompt"], prompts[turn["example_id"]])

    def test_answers_come_from_the_fixture(self):
        answers = {
            e["id"]: [c["answer"] for c in e["expected_cases"]] for e in self.note_type["examples"]
        }
        for turn in self.built["turns"]:
            self.assertIn(turn["expected_answer"], answers[turn["example_id"]])

    def test_rounds_vary_the_answer_where_the_fixture_allows(self):
        by_example = {}
        for turn in self.built["turns"]:
            by_example.setdefault(turn["example_id"], []).append(turn["expected_answer"])
        for example in self.note_type["examples"]:
            spoken = by_example[example["id"]]
            expected_distinct = min(3, len(example["expected_cases"]))
            self.assertEqual(len(set(spoken)), expected_distinct)

    def test_checked_in_asset_matches_the_generator(self):
        """Guards against the Android asset drifting away from the fixtures."""
        self.assertEqual(json.loads(ASSET.read_text(encoding="utf-8")), self.built)


class EvidenceValidatorTest(unittest.TestCase):
    """The validator must accept the retained run and reject incoherent evidence."""

    @classmethod
    def setUpClass(cls):
        cls.files = sorted(MATRIX.glob("*.json"))
        cls.document = validator.merge(cls.files)

    def validate(self, document):
        return validator.validate(document)[1]

    def mutate(self, change):
        """Applies a change to the twelve-turn loop scenario and revalidates."""
        document = copy.deepcopy(self.document)
        target = next(s for s in document["scenarios"] if s["scenario"] == "loop")
        change(target)
        return self.validate(document)

    def test_retained_matrix_passes(self):
        assertions, failures, summary = validator.validate(self.document)
        self.assertEqual(failures, [])
        self.assertGreater(len(assertions), 100)
        self.assertGreaterEqual(summary["turn_count"], 30)

    def test_every_scenario_records_who_operated_it(self):
        for scenario in self.document["scenarios"]:
            self.assertIn(scenario.get("operated_by"), {"human", "investigator_adb"})

    def test_no_adb_run_claims_human_speech(self):
        summary = validator.validate(self.document)[2]
        self.assertEqual(summary["human_transcripts"], 0,
                         "no retained run was operated by a person speaking")

    def test_matrix_covers_the_fixed_failure_cases(self):
        recorded = {s["scenario"] for s in self.document["scenarios"]}
        for required in ("loop", "pause2", "pause5", "silence", "repeat", "cancel",
                         "late_callback", "busy", "unavailable", "permission",
                         "network", "background", "lock", "focus"):
            self.assertIn(required, recorded)

    def test_summary_reports_median_capture(self):
        summary = validator.validate(self.document)[2]
        rows = {row["scenario"]: row for row in summary["scenarios"]}
        self.assertIsNotNone(rows["loop"]["median_capture_ms"])
        self.assertIn("ERROR_NO_MATCH", rows["loop"]["errors"])

    def test_adb_run_claiming_a_transcript_is_rejected(self):
        """An adb-driven run has no voice, so a transcript there is not evidence."""
        def change(scenario):
            scenario["operated_by"] = "investigator_adb"
            scenario["turns"][0].update(
                status="success", transcript="five blocks", error_name=None,
                operator_attestation=None,
            )
        failures = self.mutate(change)
        self.assertTrue(any("human-operated run" in f for f in failures), failures)

    def test_unlabelled_run_is_rejected(self):
        failures = self.mutate(lambda s: s.pop("operated_by", None))
        self.assertTrue(any("who operated" in f for f in failures), failures)

    def test_error_carrying_a_transcript_is_rejected(self):
        failures = self.mutate(lambda s: s["turns"][0].update(transcript="five blocks"))
        self.assertTrue(any("carries no transcript" in f for f in failures), failures)

    def test_success_without_a_transcript_is_rejected(self):
        def change(scenario):
            scenario["turns"][0].update(status="success", transcript=None, error_name=None)
        failures = self.mutate(change)
        self.assertTrue(any("carries a transcript" in f for f in failures), failures)

    def test_success_still_carrying_an_error_is_rejected(self):
        def change(scenario):
            scenario["turns"][0].update(
                status="success", transcript="five", operator_attestation="spoke_answer"
            )
        failures = self.mutate(change)
        self.assertTrue(any("carries no error" in f for f in failures), failures)

    def test_capture_before_playback_is_rejected(self):
        def change(scenario):
            turn = scenario["turns"][0]
            turn["listen_requested_ms"] = turn["playback_done_ms"] - 500
        failures = self.mutate(change)
        self.assertTrue(any("after playback finished" in f for f in failures), failures)

    def test_short_settling_interval_is_rejected(self):
        def change(scenario):
            scenario["turns"][0]["measured_settle_ms"] = 10
        failures = self.mutate(change)
        self.assertTrue(any("settling interval" in f for f in failures), failures)

    def test_prompt_echo_is_rejected(self):
        failures = self.mutate(lambda s: s["turns"][0].update(echo_suspected=True))
        self.assertTrue(any("prompt echoed back" in f for f in failures), failures)

    def test_stale_callback_that_advanced_a_turn_is_rejected(self):
        def change(scenario):
            scenario["stale_callbacks"] = [{"callback": "onResults", "advanced_turn": True}]
        failures = self.mutate(change)
        self.assertTrue(any("did not advance a turn" in f for f in failures), failures)

    def test_transcript_not_attested_as_spoken_is_rejected(self):
        """A turn nobody spoke must never be counted as a spoken turn."""
        def change(scenario):
            scenario["turns"][0].update(
                status="success",
                transcript="five blocks",
                error_name=None,
                operator_attestation="stayed_silent",
            )
        failures = self.mutate(change)
        self.assertTrue(any("operator spoke" in f for f in failures), failures)

    def test_silent_scenario_attested_as_spoken_is_rejected(self):
        document = copy.deepcopy(self.document)
        target = next(s for s in document["scenarios"] if s["scenario"] == "silence")
        target["turns"][0]["operator_attestation"] = "spoke_answer"
        failures = self.validate(document)
        self.assertTrue(any("not attested as spoken" in f for f in failures), failures)

    def test_prompt_not_matching_the_fixture_is_rejected(self):
        failures = self.mutate(lambda s: s["turns"][0].update(prompt="Something else entirely."))
        self.assertTrue(any("matches the AV-002 fixture" in f for f in failures), failures)

    def test_non_emulator_evidence_is_rejected(self):
        document = copy.deepcopy(self.document)
        document["environment"]["fingerprint"] = "google/pixel9/tokay:16/BP2A/1:user/release-keys"
        self.assertTrue(any("emulator image" in f for f in self.validate(document)))

    def test_anki_contact_is_rejected(self):
        document = copy.deepcopy(self.document)
        document["anki_touched"] = True
        self.assertTrue(any("no Anki collection" in f for f in self.validate(document)))

    def test_rating_production_is_rejected(self):
        document = copy.deepcopy(self.document)
        document["rating_produced"] = True
        self.assertTrue(any("no rating" in f for f in self.validate(document)))


if __name__ == "__main__":
    unittest.main()
