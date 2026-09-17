"""Keep AV-019's live exchange evidence, and its one-write-per-confirmation proof, intact."""
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ExchangeEvidenceTests(unittest.TestCase):
    def test_retained_exchange_evidence(self):
        result = subprocess.run(
            [sys.executable, str(ROOT / 'tools/av019-qa/validate.py')],
            cwd=ROOT, capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
