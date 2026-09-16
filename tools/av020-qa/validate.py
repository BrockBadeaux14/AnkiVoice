#!/usr/bin/env python3
"""Verify the AV-020 (#17) evidence: one recorded live request, and no leaked key.

Run it after the steps in docs/testing/av020/runbook.md. It reads only the retained
evidence; it makes no provider call and writes nothing.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / 'docs/testing/av020/evidence'
KEY_SHAPED = re.compile(rb'sk-or-[A-Za-z0-9._-]{4,}')
REQUIRED = [
    'environment.json',
    'apk-verification.json',
    'debug-manifest.txt',
    'release-manifest.txt',
    'gradle-checks.txt',
    'ledger-before.jsonl',
    'ledger-after.jsonl',
    'smoke-request.json',
    'sha256.json',
]


def load(name):
    return json.loads((EVIDENCE / name).read_text(encoding='utf-8'))


def reservations(name):
    text = (EVIDENCE / name).read_text(encoding='utf-8')
    rows = [json.loads(line) for line in text.splitlines() if line.strip()]
    return [row for row in rows if row.get('event') == 'reserve']


def main():
    missing = [name for name in REQUIRED if not (EVIDENCE / name).is_file()]
    assert not missing, f'missing evidence: {missing}'

    # The credential must never reach the repository, in any file.
    for path in sorted(EVIDENCE.rglob('*')):
        if path.is_file():
            assert not KEY_SHAPED.search(path.read_bytes()), f'{path.name} contains something key-shaped'

    for variant in ('debug', 'release'):
        manifest = (EVIDENCE / f'{variant}-manifest.txt').read_text(encoding='utf-8')
        assert 'android.permission.INTERNET' in manifest, f'{variant} must carry INTERNET from :provider'
        assert 'android.permission.READ_PHONE_STATE' not in manifest, variant
        assert 'android.permission.RECORD_AUDIO' in manifest, variant

    apk = load('apk-verification.json')
    for variant, checks in apk.items():
        assert checks['internet_permission'] is True, variant
        assert checks['phone_state_permission'] is False, variant
        assert checks['key_strings_present'] is False, variant

    smoke = load('smoke-request.json')
    assert smoke['model'] == 'liquid/lfm-2.5-2.6b:free'
    assert smoke['provider'] == 'liquid/fp8'
    assert smoke['outcome'] in {'content', 'failed'}
    if smoke['outcome'] == 'content':
        # A served reply must have reported a zero cost; anything else stops the ledger.
        assert str(smoke['reported_cost']).strip() in {'0', '0.0', '0.00'}, smoke['reported_cost']
        assert smoke['http_status'] == 200
    else:
        assert smoke.get('message'), 'a refusal must record what the app showed'

    before, after = reservations('ledger-before.jsonl'), reservations('ledger-after.jsonl')
    assert len(after) - len(before) == 1, 'the request must consume exactly one reservation'
    assert smoke['reservations_before'] == len(before) and smoke['reservations_after'] == len(after)
    assert all(row['session'] and row['day'] for row in after), 'each reservation records its session and UTC day'

    environment = load('environment.json')
    assert environment['arch'] == 'arm64', 'the pinned evidence host is macOS ARM64'
    assert 'BUILD SUCCESSFUL' in (EVIDENCE / 'gradle-checks.txt').read_text(encoding='utf-8')

    hashes = load('sha256.json')
    files = {p.name for p in EVIDENCE.iterdir() if p.is_file() and p.name != 'sha256.json'}
    assert set(hashes) == files, f'sha256.json does not list exactly the evidence: {set(hashes) ^ files}'
    for name, expected in hashes.items():
        assert hashlib.sha256((EVIDENCE / name).read_bytes()).hexdigest() == expected, name

    print(
        'AV020 evidence verified: pinned free route, '
        f"{smoke['outcome']} outcome at cost {smoke.get('reported_cost', 'n/a')}, "
        'one ledger reservation, INTERNET only from :provider, no key in the evidence.'
    )


if __name__ == '__main__':
    try:
        main()
    except AssertionError as error:
        sys.exit(f'AV020 evidence check failed: {error}')
