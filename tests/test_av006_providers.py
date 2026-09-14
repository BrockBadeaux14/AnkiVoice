"""Cost/boundary and frozen-input regressions; no provider calls or credentials."""
import json
from pathlib import Path
import tempfile
import time
import unittest

from tools import av006_providers as experiment


class ProviderExperimentTests(unittest.TestCase):
    def test_corpus_preserves_every_original_label(self):
        corpus = experiment.load_corpus()
        source = json.loads((experiment.ROOT / corpus["source"]).read_text())
        cases = {(c["example_id"], c["answer"], c["expected_label"]) for c in corpus["cases"]}
        for example in source["examples"]:
            for case in example["expected_cases"]:
                self.assertIn((example["id"], case["answer"], case["result"]), cases)
        self.assertEqual(len(cases), 12)

    def test_nonzero_missing_or_invalid_prices_refuse_dispatch(self):
        model, provider = experiment.CANDIDATES["grade-a"]
        for prices in ({"prompt": "0.1", "completion": "0"}, {"prompt": "0"},
                       {"prompt": "0", "completion": "0", "request": "0.1"},
                       {"prompt": "NaN", "completion": "0"}):
            data = {"id": model, "endpoints": [{"tag": provider, "pricing": prices}]}
            with self.subTest(prices=prices), self.assertRaises(ValueError):
                experiment.validate_endpoint(data, model, provider)

    def test_payload_has_zero_prices_fixed_route_and_no_answer_labels(self):
        corpus = experiment.load_corpus()
        case = corpus["cases"][0]
        fields = corpus["examples"][0]["fields"]
        for kind in ("grade-a", "grade-b"):
            payload = experiment.make_request(kind, case, fields)
            self.assertEqual(payload["provider"]["max_price"], {"prompt": 0, "completion": 0, "request": 0})
            self.assertFalse(payload["provider"]["allow_fallbacks"])
            self.assertEqual(len(payload["provider"]["only"]), 1)
            self.assertTrue(payload["model"].endswith(":free"))
            context = json.loads(payload["messages"][1]["content"])
            self.assertEqual(set(context), {"Prompt", "ReferenceAnswer", "RequiredConcepts", "AcceptedAnswers", "learner_answer"})
            self.assertEqual(context["learner_answer"], case["answer"])

    def test_interrupted_attempts_count_towards_two_pass_limit(self):
        with tempfile.TemporaryFile(mode="w+") as ledger:
            events = []
            experiment.reserve(ledger, events, "grade-a", "demo-rules-1")
            experiment.reserve(ledger, events, "grade-a", "demo-rules-1")
            with self.assertRaisesRegex(ValueError, "Two attempts"):
                experiment.reserve(ledger, events, "grade-a", "demo-rules-1")
            ledger.seek(0)
            self.assertEqual(len(ledger.readlines()), 2)

    def test_global_daily_and_role_ceilings(self):
        day = time.strftime("%Y-%m-%d", time.gmtime())
        events = [{"event": "attempt", "kind": "grade-a", "case_id": str(i), "role": "grading", "utc_day": day} for i in range(48)]
        with tempfile.TemporaryFile(mode="w+") as ledger:
            with self.assertRaisesRegex(ValueError, "role limit"):
                experiment.reserve(ledger, events, "grade-b", "new")
            with self.assertRaisesRegex(ValueError, "daily"):
                experiment.reserve(ledger, events, "stt", "new")

    def test_uncertainty_truncation_malformed_and_missing_cost_are_not_success(self):
        def response(text, stop="stop", cost=0):
            return {"usage": {"cost": cost}, "choices": [{"finish_reason": stop, "message": {"content": text}}]}
        for text in ("Again", "```json\n{}\n```", '{"label":"good","reason":"ok"}'):
            self.assertEqual(experiment.decode_result(response(text), "grade-a")["status"], "malformed")
        text = '{"label":"uncertain","reason":"Ambiguous"}'
        self.assertEqual(experiment.decode_result(response(text), "grade-a")["status"], "uncertain")
        self.assertEqual(experiment.decode_result(response(text, "length"), "grade-a")["status"], "incomplete_or_empty")
        self.assertFalse(experiment.decode_result(response(text, cost=None), "grade-a")["verified_zero_cost"])

    def test_credential_validation_never_echoes_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "key"
            path.write_text("synthetic invalid secret")
            with self.assertRaises(ValueError) as error:
                experiment.read_key(path)
            self.assertNotIn(path.read_text(), str(error.exception))


if __name__ == "__main__":
    unittest.main()
