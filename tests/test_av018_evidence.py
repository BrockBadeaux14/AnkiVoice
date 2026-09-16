"""Keep the journal's live process-loss evidence, and its no-second-write proof, intact."""
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]


class JournalEvidenceTests(unittest.TestCase):
    def test_retained_process_loss_evidence(self):
        result = subprocess.run(
            [sys.executable, str(ROOT / 'tools/av018-qa/validate.py')],
            cwd=ROOT, capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
