"""Mutation checks keep evidence integrity separate from speech capability."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('av042_evidence', ROOT / 'tools/av042-probe/validate_evidence.py')
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class AV042EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.ledger = json.loads((validator.DEFAULT / 'ledger.json').read_text())

    def test_retained_evidence_is_intact_but_does_not_unblock_speech(self):
        result = validator.validate(ledger=self.ledger)
        self.assertTrue(result['integrity_passed'], result['failures'])
        self.assertEqual('no-go', result['verdict'])
        self.assertFalse(result['unblocks_13_26'])
        self.assertEqual(0, result['human_attested_transcripts'])

    def test_duplicate_attempt_ids_rejected(self):
        self.ledger['turns'][1]['id'] = 1
        self.assertFalse(validator.validate(ledger=self.ledger)['integrity_passed'])

    def test_fabricated_human_transcript_rejected(self):
        turn = self.ledger['turns'][1]
        turn['operator_spoke_expected'] = True
        turn['transcript'] = 'Five'
        self.assertFalse(validator.validate(ledger=self.ledger)['integrity_passed'])

    def test_fabricated_call_detection_rejected(self):
        turn = self.ledger['turns'][1]
        turn['events'].insert(2, {'event': 'interruption', 'elapsed_ms': 16, 'value': 'call', 'phase': 'CAPTURE'})
        self.assertFalse(validator.validate(ledger=self.ledger)['integrity_passed'])

    def test_missing_cleanup_rejected(self):
        self.ledger['turns'][1]['events'][-1]['value']['recorder_released'] = False
        self.assertFalse(validator.validate(ledger=self.ledger)['integrity_passed'])

    def test_shortened_deadline_rejected(self):
        for event in self.ledger['turns'][0]['events']:
            if event['event'] == 'capture_deadline':
                event['elapsed_ms'] = 1500
        self.assertFalse(validator.validate(ledger=self.ledger)['integrity_passed'])

    def test_attempt_after_no_go_rejected(self):
        extra = copy.deepcopy(self.ledger['turns'][0])
        extra['id'] = 3
        self.ledger['turns'].append(extra)
        self.assertFalse(validator.validate(ledger=self.ledger)['integrity_passed'])


live_spec = importlib.util.spec_from_file_location('av042_live', ROOT / 'tools/av042-probe/validate_live_evidence.py')
live = importlib.util.module_from_spec(live_spec)
live_spec.loader.exec_module(live)


class AV042LiveEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.ledger = json.loads((live.DEFAULT / 'live-ledger.json').read_text())
        self.notes = json.loads((live.DEFAULT / 'scope-and-operator-notes.json').read_text())

    def test_real_operator_route_meets_narrowed_scope(self):
        result = live.validate(ledger=self.ledger, notes=self.notes)
        self.assertTrue(result['integrity_passed'], result['failures'])
        self.assertTrue(result['human_voice_verified'])

    def test_a_matching_string_without_operator_confirmation_is_not_proof(self):
        self.notes['operator_replies'] = []
        for t in self.ledger['turns']:
            t.pop('operator_spoke_expected', None)
        self.assertFalse(live.validate(ledger=self.ledger, notes=self.notes)['human_voice_verified'])

    def test_replacing_raw_result_with_expected_answer_is_rejected(self):
        turn = self.ledger['turns'][3]
        turn['transcript'] = turn['expected_answer']
        self.assertFalse(live.validate(ledger=self.ledger, notes=self.notes)['integrity_passed'])

    def test_old_diagnostic_failures_cannot_be_erased(self):
        self.ledger['turns'][1]['status'] = 'success'
        self.assertFalse(live.validate(ledger=self.ledger, notes=self.notes)['integrity_passed'])
