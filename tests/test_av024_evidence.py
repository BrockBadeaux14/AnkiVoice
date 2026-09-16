"""Keep the adapter's required device matrix and independent review-log proof intact."""
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AdapterEvidenceTests(unittest.TestCase):
    def test_retained_instrumented_matrix_and_review_logs(self):
        result = subprocess.run(
            [sys.executable, str(ROOT / 'tools/av024-qa/validate.py')],
            cwd=ROOT, capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
