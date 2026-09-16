"""Validate AV024 retained evidence; no device or network calls."""
import json
import xml.etree.ElementTree as ET
from pathlib import Path

EVIDENCE = Path(__file__).resolve().parents[2] / 'docs/testing/av024/evidence'
checks = 0

def read(name):
    return json.loads((EVIDENCE / f'{name}.json').read_text())

def check(value, reason):
    global checks
    assert value, reason
    checks += 1

def added(before, after):
    ids = {r['id'] for r in before['revlog']}
    return [r for r in after['revlog'] if r['id'] not in ids]

def write_case(label, expected_count):
    result = read(label)
    before, after = read(label + '-before'), read(label + '-after')
    check(result['passed'], label + ' instrumentation failed')
    check(before['integrity'] == after['integrity'] == 'ok', label + ' DB integrity')
    new = added(before, after)
    check(len(new) == expected_count, label + ' review count')
    old_after = [r for r in after['revlog'] if r not in new]
    check(before['revlog'] == old_after, label + ' pre-existing history changed')
    out = result['outcome']
    check(out['dispatches'] == expected_count, label + ' dispatch count')
    if expected_count:
        row = new[0]
        check(row['cid'] == result['next']['cardId'], label + ' wrong card')
        check(row['ease'] in result['next']['permittedRatings'], label + ' unoffered rating')
        nominal = min(out['submittedTimeMs'], result['capabilities']['maxReviewTimeMs'])
        ceiling = min(out['submittedTimeMs'] + out['dispatchDurationMs'], result['capabilities']['maxReviewTimeMs'])
        check(nominal <= row['time'] <= ceiling, label + ' time exceeds measured dispatch bound')
        check(out['expectedStoredTimeMs'] == nominal, label + ' nominal time')
        check(out['state'] in ('confirmed', 'outcome-unknown'), label + ' invalid settlement')
        if out['state'] == 'confirmed':
            check(out['acknowledgement'] == 1 and out['postState']['reps'] == out['preState']['reps'] + 1,
                  label + ' invalid confirmation')
        unchanged_before = [c for c in before['cards'] if c['id'] != row['cid']]
        unchanged_after = [c for c in after['cards'] if c['id'] != row['cid']]
        check(unchanged_before == unchanged_after, label + ' unrelated cards changed')
    else:
        check(before['cards'] == after['cards'], label + ' card state changed without dispatch')
    return result, new

def validate():
    env = read('environment')
    check(env['architecture'] == 'arm64', 'host architecture')
    check('AnkiVoice_AV024' in env['avd'], 'isolated AVD')
    check('versionName=2.24.1' in env['ankidroid'], 'AnkiDroid pin')
    check('Accounts: 0' in env['accounts'], 'AVD signed in')
    for name in ('new', 'learning', 'relearning', 'mature'):
        target = read(name + '-manifest')['target']
        original = next(c for c in read('baseline-manifest')['cards'] if c['fixture_id'] == name)
        check(target == original, name + ' target changed during isolation')
        for rating in range(1, 5):
            label = f'{name}-rating-{rating}'
            result, rows = write_case(label, 1)
            check(rows[0]['ease'] == rating, label + ' wrong rating')
            check(result['next']['cardId'] == target['card_id'], label + ' wrong target')
    for rating in (0, 5):
        result, _ = write_case(f'invalid-{rating}', 0)
        check(result['outcome']['failure'].startswith('ReviewWriter.ratingRejected'), 'invalid rating not rejected')
    result, rows = write_case('elapsed-cap', 1)
    check(result['outcome']['submittedTimeMs'] == 98765 and rows[0]['time'] == 60000, 'cap transformation')
    for label, field, failure in (
        ('deleted-card', 'read', 'CardProvider.cardNotFound'),
        ('deleted-deck', 'next', 'CardProvider.deckMissing'),
        ('api-disabled', 'next', 'CardProvider.apiDisabled'),
        ('permission-revoked', 'next', 'CardProvider.accessDenied'),
    ):
        result = read(label)
        check(result['passed'] and result[field] == failure, label + ' wrong failure')
        b, a = read(label + '-before'), read(label + '-after')
        check(b['revlog'] == a['revlog'] and b['cards'] == a['cards'], label + ' unexpected write')
    deleted = read('deleted-card-manifest')['target']['card_id']
    check(all(c['id'] != deleted for c in read('deleted-card-before')['cards']), 'card was not actually deleted')
    deck = read('baseline-manifest')['deck']['id']
    check(all(d['id'] != deck for d in read('deleted-deck-decks')['before']), 'deck was not actually deleted')
    result, _ = write_case('stale-rebuild', 0)
    check(result['outcome']['failure'].startswith('ReviewWriter.staleIdentity'), 'stale card not rejected')
    check(len(read('async-read')['asyncMainThreadReplies']) == 3, 'missing main-thread tagged replies')
    for prefix, ui, text in (
        ('release-shell', 'release-card-ready', 'Card ready'),
        ('release-exhausted', 'release-queue-exhausted', 'Queue exhausted'),
    ):
        b, a = read(prefix + '-before'), read(prefix + '-after')
        check(b['cards'] == a['cards'] and b['revlog'] == a['revlog'], prefix + ' UI wrote a review')
        tree = ET.parse(EVIDENCE / (ui + '.xml'))
        check(any(n.get('text') == text for n in tree.iter('node')), prefix + ' incorrect UI status')
    print(f'{checks} AV024 evidence checks passed')

if __name__ == '__main__':
    validate()
