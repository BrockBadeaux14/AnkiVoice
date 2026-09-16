#!/usr/bin/env python3
"""Check the owner's narrowed live voice acceptance; retain all earlier failures."""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT = ROOT / 'docs/testing/av042/evidence'


def normalize(text):
    value = re.sub(r'[^a-z0-9 ]', '', str(text).lower()).strip()
    return {'5': 'five', '6': 'six'}.get(value, value)


def validate(directory=DEFAULT, ledger=None, notes=None):
    directory = Path(directory)
    ledger = ledger if ledger is not None else json.loads((directory / 'live-ledger.json').read_text())
    notes = notes if notes is not None else json.loads((directory / 'scope-and-operator-notes.json').read_text())
    turns = ledger.get('turns', [])
    failures = []
    successes = []
    replies = {r['after_attempt']: r['verbatim'] for r in notes['operator_replies']}
    if [t.get('id') for t in turns] != list(range(1, len(turns) + 1)):
        failures.append('Cumulative IDs lost, duplicated or reset')
    if not 1 <= len(turns) <= ledger.get('attempt_cap', 0):
        failures.append('Invalid cumulative turn count')
    historical = json.loads((directory / 'ledger.json').read_text())['turns']
    if turns[:len(historical)] != historical:
        failures.append('Historical diagnostic turns were changed')
    for turn in turns:
        events = turn['events']
        if not events or events[0]['event'] != 'reserved':
            failures.append(f"{turn['id']}: no initial reservation")
        if turn.get('status') != 'transcript':
            if turn.get('transcript') is not None:
                failures.append(f"{turn['id']}: failure carrying a transcript")
            continue
        finals = [e['value'] for e in events if e['event'] == 'raw_final']
        if not finals or finals[-1] != turn.get('transcript'):
            failures.append(f"{turn['id']}: displayed transcript differs from raw final")
            continue
        matched = normalize(turn['transcript']) == normalize(turn['expected_answer'])
        attested = turn.get('operator_spoke_expected') is True or normalize(replies.get(turn['id'], '')) == normalize(turn['expected_answer'])
        if not matched or not attested:
            continue
        if turn.get('automated') is not False or turn.get('case') != 'spoken':
            failures.append(f"{turn['id']}: automated trial presented as human success")
            continue
        if turn.get('prefer_offline') is not False:
            failures.append(f"{turn['id']}: successful configuration does not match selected online-permitted route")
        cleanup = [e for e in events if e['event'] == 'cleanup_complete']
        if not cleanup or cleanup[-1]['phase'] != 'CLOSED' or cleanup[-1]['value'].get('recording') is not False:
            failures.append(f"{turn['id']}: input not stopped after final")
        finish = [e for e in events if e['event'] == 'finish_requested']
        final = [e for e in events if e['event'] == 'raw_final']
        if not finish or not 0 <= final[-1]['elapsed_ms'] - finish[-1]['elapsed_ms'] <= 5000:
            failures.append(f"{turn['id']}: final outside bounded finish")
        successes.append({'attempt':turn['id'], 'spoken_test_phrase':turn['expected_answer'], 'transcript':turn['transcript'],
                          'operator_confirmation':'in-app attestation' if turn.get('operator_spoke_expected') else 'user reply to live test instructions',
                          'done_to_final_ms':final[-1]['elapsed_ms']-finish[-1]['elapsed_ms']})
    return {'scope':'human voice input only, per owner instruction', 'integrity_passed':not failures,
            'failures':failures, 'human_voice_verified':bool(successes) and not failures,
            'turns_used':len(turns), 'verified_transcripts':successes,
            'interruption_requirements':'deferred by owner; no passing claim',
            'platform':'MacBook microphone → API 36 ARM64 emulator → pinned native recognizer',
            'production_app_integration':'outside this disposable #51 investigation'}


if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,default=DEFAULT);args=p.parse_args()
    result=validate(args.evidence);print(json.dumps(result,indent=2))
    raise SystemExit(0 if result['integrity_passed'] and result['human_voice_verified'] else 1)
