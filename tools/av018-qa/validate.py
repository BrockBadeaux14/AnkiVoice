"""Validate AV018's retained live evidence; no device or network calls.

It re-derives every claim the results page makes from the retained snapshots rather than
trusting the summary: that the kill landed in the intended window, that reconciliation
reached the specified resolution, and — the claim that matters most — that reconciliation
never added a review in either case.
"""
import json
from pathlib import Path

EVIDENCE = Path(__file__).resolve().parents[2] / 'docs/testing/av018/evidence'
checks = 0

EXPECTED = {
    'before': {'resolution': 'failed', 'writesByKill': 0, 'blocked': False},
    'after': {'resolution': 'outcome-unknown', 'writesByKill': 1, 'blocked': True},
}


def read(name):
    return json.loads((EVIDENCE / f'{name}.json').read_text())


def check(value, reason):
    global checks
    assert value, reason
    checks += 1


def added(before, after):
    ids = {row['id'] for row in before['revlog']}
    return [row for row in after['revlog'] if row['id'] not in ids]


def case(window):
    expected = EXPECTED[window]
    label = f'{window}-dispatch'
    before, stranded, after = read(f'{label}-before'), read(f'{label}-stranded'), read(f'{label}-after')
    result = read(f'{label}-reconcile')
    summary = read(f'{label}-summary')

    check(result['passed'], f'{label} instrumentation failed')
    check(before['integrity'] == stranded['integrity'] == after['integrity'] == 'ok', f'{label} DB integrity')

    # The journal survived the kill, unsettled, with the entry the writer flushed first.
    entry = result['entriesBefore'][-1]
    check(entry['phase'] == 'dispatching', f'{label} the entry was not left unsettled')
    check(entry['transcript'] == 'five blocks', f'{label} the transcript did not survive')
    check(entry['rating'] == 3 and entry['transcriptRevision'] == 2, f'{label} entry contents')
    check(entry['session'] == 'av018-live', f'{label} entry session')

    # The kill landed in the intended window.
    by_kill = added(before, stranded)
    check(len(by_kill) == expected['writesByKill'], f'{label} the kill wrote {len(by_kill)} reviews')
    if by_kill:
        check(by_kill[0]['cid'] == entry['cardId'], f'{label} the kill wrote a different card')
        check(by_kill[0]['ease'] == entry['rating'], f'{label} the kill wrote a different rating')

    # Reconciliation reached the specified row and wrote nothing.
    resolution = result['reconciliations'][-1]
    check(resolution['resolution'] == expected['resolution'],
          f"{label} resolved {resolution['resolution']}, not {expected['resolution']}")
    check(resolution['entryId'] == entry['entryId'], f'{label} resolved a different entry')
    check(added(stranded, after) == [], f'{label} reconciliation added a review')
    check(added(before, after) == by_kill, f'{label} the revlog moved during reconciliation')
    untouched = [row for row in after['revlog'] if row not in by_kill]
    check(before['revlog'] == untouched, f'{label} pre-existing history changed')
    check(result['entriesAfter'][-1]['phase'] == 'reconciled', f'{label} the entry was not recorded as reconciled')
    check(result['blocked'] is expected['blocked'], f'{label} blocking state')

    # The notice names the card and rating and never announces success.
    notice = resolution['notice']
    check(str(entry['cardId']) in notice and str(entry['rating']) in notice, f'{label} notice omits card or rating')
    check('Saved' not in notice, f'{label} notice announces success')
    if expected['resolution'] == 'outcome-unknown':
        check('cannot prove' in notice, f'{label} notice overstates what is known')
        check('AnkiDroid' in notice, f'{label} notice does not hand off to AnkiDroid')
    check(summary['resolution'] == resolution['resolution'], f'{label} summary disagrees with the evidence')
    check(entry['transcript'] not in json.dumps(summary), f'{label} transcript text leaked into the summary')


def main():
    environment = read('environment')
    check(environment['deck'].startswith('AV002'), 'the live run used a non-disposable deck')
    check(environment['ankidroid'] == '2.24.1', 'AnkiDroid version drifted from the pinned build')
    check(environment['sdk'] == '36', 'API level drifted from the pinned image')
    for window in EXPECTED:
        case(window)
    summaries = read('summary')
    check([s['window'] for s in summaries] == ['before', 'after'], 'both windows must be recorded')
    print(f'AV018 retained evidence: {checks} checks passed')


if __name__ == '__main__':
    main()
