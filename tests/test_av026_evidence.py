"""Keep AV-026's live study-screen evidence, and its one-write-per-confirmation proof, intact."""
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]


class StudyEvidenceTests(unittest.TestCase):
    def test_retained_study_evidence(self):
        result = subprocess.run(
            [sys.executable, str(ROOT / 'tools/av026-qa/validate.py')],
            cwd=ROOT, capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_validator_and_driver_agree_on_the_turns(self):
        """The validator judges the same ten turns the driver runs, with the same write counts."""
        driver = (ROOT / 'tools/av026-qa/run.py').read_text()
        validator = (ROOT / 'tools/av026-qa/validate.py').read_text()
        for turn, writes in {
            "rule-match": 1, "ai-labelled": 1, "abstain-self-grade": 1, "corrected-confirmed": 1,
            "transcript-edit": 1, "skip": 0, "pause-resume": 0, "interruption-reload": 0,
            "undo-handoff": 1, "route-refused-self-grade": 1,
        }.items():
            self.assertIn(f'"{turn}": {writes}', driver, turn)
            self.assertIn(f'"{turn}": {writes}', validator, turn)
