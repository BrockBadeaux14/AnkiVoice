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


class LiveFollowUpValidatorTest(unittest.TestCase):
    """Mutated fixtures test AV-040 evidence rejection; these are not emulator runs."""

    def setUp(self):
        self.document = validator.merge([MATRIX / "loop.json"])
        scenario = self.document["scenarios"][0]
        scenario.update(scenario="av040_pause2", follow_up="AV-040",
                        capture_policy="explicit_start_done_v1", interruption_rule="candidate_v1",
                        capture_limit_ms=15000, finalization_limit_ms=5000, automatic_rearms=0,
                        operated_by="human", voice_source="human_microphone")
        scenario["turns"] = [scenario["turns"][0]]
        self.scenario = scenario
        self.turn = scenario["turns"][0]
        self.turn.update(av040_attempt=1, token=1, terminal_token=2,
                         operator_attestation="spoke_answer", operator_thinking_ms=2000,
                         measured_thinking_ms=2100)

    def failures(self):
        return validator.validate(self.document)[1]

    def test_honest_failed_attempt_remains_valid_evidence(self):
        _, failures, summary = validator.validate(self.document)
        self.assertEqual(failures, [])
        self.assertEqual(summary["human_transcripts"], 0)
        self.assertEqual(len(summary["capability_findings"]), 1)

    def test_attempt_bound(self):
        self.turn["av040_attempt"] = 25
        self.assertTrue(any("24-turn bound" in f for f in self.failures()))

    def test_extension_requires_explicit_validation_and_evidence(self):
        self.turn["av040_attempt"] = 25
        self.assertTrue(validator.validate(self.document, 28)[1])
        self.scenario["authorized_four_turn_extension"] = True
        self.assertEqual(validator.validate(self.document, 28)[1], [])
        self.assertTrue(any("24-turn bound" in f for f in self.failures()))

    def test_extension_stops_at_28(self):
        self.scenario["authorized_four_turn_extension"] = True
        self.turn["av040_attempt"] = 29
        self.assertTrue(any("28-turn bound" in f for f in validator.validate(self.document, 28)[1]))
        with self.assertRaises(ValueError):
            validator.validate(self.document, 29)

    def test_duplicate_cumulative_snapshot(self):
        self.document["scenarios"].append(copy.deepcopy(self.scenario))
        self.assertTrue(any("double-counted" in f for f in self.failures()))

    def test_missing_attestation(self):
        self.turn.pop("operator_attestation")
        self.assertTrue(any("explicit operator attestation" in f for f in self.failures()))

    def test_token_must_be_invalidated(self):
        self.turn["terminal_token"] = self.turn["token"]
        self.assertTrue(any("invalidates" in f for f in self.failures()))

    def test_interrupted_turn_cannot_retain_a_transcript(self):
        for status in ("halted", "cancelled", "timeout"):
            with self.subTest(status=status):
                self.turn.update(status=status, transcript="stale recognized answer")
                self.assertTrue(any("carries no transcript" in f for f in self.failures()))

    def test_stale_callback_must_retain_its_old_token(self):
        entry = dict(callback="onResults", reason="invalidated_token",
                     expected_token=1, current_token=2, advanced_turn=False)
        self.scenario["stale_callbacks"] = [entry]
        self.assertEqual(self.failures(), [])
        entry["expected_token"] = 2
        self.assertTrue(any("older token" in f for f in self.failures()))

    def test_thinking_is_before_capture(self):
        self.turn["measured_thinking_ms"] = 1000
        self.assertTrue(any("thinking interval" in f for f in self.failures()))

    def test_first_finalization_deadline_wins(self):
        self.turn.update(done_requested_ms=1000, end_of_speech_ms=4000, final_ms=8000)
        self.assertTrue(any("first finalization deadline" in f for f in self.failures()))

    def test_segmented_speech_pause_does_not_start_finalization(self):
        self.scenario["segmented_session_requested"] = True
        self.turn.update(end_of_speech_ms=1000, finish_requested_ms=7000, final_ms=9000)
        self.assertEqual(self.failures(), [])

    def test_segmented_done_has_a_bounded_finalization_wait(self):
        self.scenario["segmented_session_requested"] = True
        self.turn.update(end_of_speech_ms=1000, finish_requested_ms=2000, final_ms=8000)
        self.assertTrue(any("first finalization deadline" in f for f in self.failures()))

    def test_echo_is_retained_as_a_capability_failure(self):
        self.scenario["scenario"] = "av040_echo"
        self.turn.update(status="success", transcript=self.turn["prompt"], error_name=None,
                         echo_suspected=True, operator_attestation="stayed_silent")
        _, failures, summary = validator.validate(self.document)
        self.assertEqual(failures, [])
        self.assertEqual(summary["human_transcripts"], 0)
        self.assertIn("contamination", summary["capability_findings"][0])

    def test_spoken_echo_deviation_is_retained_without_claiming_echo_coverage(self):
        self.scenario["scenario"] = "av040_echo"
        self.turn["operator_attestation"] = "spoke_answer"
        _, failures, summary = validator.validate(self.document)
        self.assertEqual(failures, [])
        self.assertTrue(any("silent protocol not confirmed" in f
                            for f in summary["capability_findings"]))


if __name__ == "__main__":
    unittest.main()
