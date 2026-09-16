#!/usr/bin/env python3
"""Validate the frozen pre-scope-change diagnostics; never confuse integrity with capability."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT = ROOT / 'docs/testing/av042/evidence'


def validate(directory=DEFAULT, ledger=None):
    directory = Path(directory)
    ledger = ledger if ledger is not None else json.loads((directory / 'ledger.json').read_text())
    failures = []
    checks = 0

    def check(condition, message):
        nonlocal checks
        checks += 1
        if not condition:
            failures.append(message)

    turns = ledger.get('turns', [])
    check(ledger.get('issue') == 51, 'Wrong issue ledger')
    check(ledger.get('attempt_cap') == 30, 'Attempt cap changed')
    check(0 < len(turns) <= 30, 'Missing turns or exceeded budget')
    check([t.get('id') for t in turns] == list(range(1, len(turns) + 1)), 'Turn IDs must be unique, contiguous and never reset')
    check(len(turns) == 2, 'Frozen two-turn diagnostic snapshot changed; later trials belong in live-ledger.json')
    check(ledger.get('permission_design') == 'RECORD_AUDIO only', 'Permission scope changed')
    # The generated corpus is independently covered by tests/test_av005_probe.py.
    corpus = json.loads((ROOT / 'tools/av005-probe/app/src/main/assets/av005-turns.json').read_text())['turns']
    metrics = []
    for turn in turns:
        ident = turn.get('id')
        events = turn.get('events', [])
        times = [e['elapsed_ms'] for e in events]
        check(times == sorted(times), f'{ident}: non-monotonic event ledger')
        check(bool(events) and events[0]['event'] == 'reserved', f'{ident}: audio before persistent reservation')
        check(turn.get('audio_source') == 'live_microphone', f'{ident}: wrong audio source')
        check(turn.get('automated') is True, f'{ident}: automated origin missing')
        check(not turn.get('operator_spoke_expected') and not turn.get('operator_stayed_silent') and not turn.get('prompt_audible'), f'{ident}: unattested automated turn claims human evidence')
        check(turn.get('transcript') is None, f'{ident}: failed/diagnostic turn fabricated transcript')
        check(turn.get('status') in ('diagnostic_complete', 'recognizer_error_7'), f'{ident}: failed outcome relabelled')
        index = turn['corpus_index']
        check(turn['expected_answer'] == corpus[index]['expected_answer'] and turn['example_id'] == corpus[index]['example_id'], f'{ident}: fixture text drift')
        by_event = {e['event']: e for e in events}
        for needed in ('capture_start', 'recorder_started', 'capture_deadline', 'finish_requested', 'closing', 'cleanup_complete'):
            check(needed in by_event, f'{ident}: missing {needed}')
        if all(k in by_event for k in ('capture_start', 'capture_deadline', 'cleanup_complete')):
            elapsed = by_event['capture_deadline']['elapsed_ms'] - by_event['capture_start']['elapsed_ms']
            check(15000 <= elapsed <= 15250, f'{ident}: measured capture deadline outside bound')
            cleanup = by_event['cleanup_complete']['value']
            check(cleanup.get('recording') is False, f'{ident}: capture still running')
            check(all(cleanup.get(k) is True for k in ('recorder_released', 'recognizer_released', 'player_released', 'pipe_closed')), f'{ident}: native resource cleanup incomplete')
            check(by_event['cleanup_complete']['phase'] == 'CLOSED', f'{ident}: cleanup preceded token invalidation')
        levels = [e['value'] for e in events if e['event'] == 'pcm_level']
        check(bool(levels), f'{ident}: no independent PCM observations')
        for level in levels:
            check(0 <= level['nonzero'] <= level['samples'] and 0 <= level['rms'] <= level['peak'] <= 32768, f'{ident}: impossible PCM statistic')
        metrics.append({'id': ident, 'case': turn['case'], 'status': turn['status'], 'total_samples_in_reported_blocks': sum(v['samples'] for v in levels), 'nonzero_samples': sum(v['nonzero'] for v in levels), 'peak': max((v['peak'] for v in levels), default=0)})
    formal = [t for t in turns if t.get('case') == 'call_capture']
    check(len(formal) == 1, 'Expected one controlled capture call')
    telecom = (directory / 'attempt-02-call-capture/telecom.txt').read_text()
    audio = (directory / 'attempt-02-call-capture/audio.txt').read_text()
    check('Call id=TC@2, state=RINGING' in telecom, 'No independent active ringing confirmation')
    check('pack: org.ankivoice.av042' in audio and 'loss: none' in audio, 'Missing app-owned unchanged focus')
    check('Actual mode = MODE_NORMAL' in audio, 'Missing independent normal audio mode')
    check('pack:org.ankivoice.av042' in audio and 'silenced:false' in audio, 'Missing independent unsilenced recorder')
    if formal:
        turn = formal[0]
        kinds = [e['event'] for e in turn['events']]
        check(not any(k in kinds for k in ('interruption', 'onPause', 'audio_mode', 'focus')), 'Missed-call evidence contradicts no interruption finding')
        controller = json.loads((directory / 'attempt-02-call-capture/controller.json').read_text())
        trigger_ms = controller['trigger_epoch_ms'] - turn['started_epoch_ms']
        begin = next(e['elapsed_ms'] for e in turn['events'] if e['event'] == 'recorder_started')
        end = next(e['elapsed_ms'] for e in turn['events'] if e['event'] == 'finish_requested')
        check(begin < trigger_ms < trigger_ms + 3000 < end, 'Call did not overlap active capture')
        check('recognizer_ready' in kinds and 'audio_pipe_closed' in kinds, 'Pipe route not exercised')
    check('name="av040_attempts" value="28"' in (directory / 'av040-counter-preserved.xml').read_text(), 'AV-040 attempt counter changed')
    return {'scope': 'historical diagnostics before the owner narrowed acceptance', 'integrity_passed': not failures, 'checks': checks, 'failures': failures,
            'verdict': 'no-go', 'turns_used': len(turns), 'budget_remaining_but_stopped': 30-len(turns),
            'human_attested_transcripts': 0, 'controlled_capture_calls_detected': 0,
            'controlled_capture_calls_observed': len(formal), 'unblocks_13_26': False, 'metrics': metrics}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=Path, default=DEFAULT)
    args = parser.parse_args()
    result = validate(args.evidence)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['integrity_passed'] else 1)
