#!/usr/bin/env python3
"""Verify retained AV-023 UI, package, test and no-review evidence; no device access."""
from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / 'docs/testing/av023/evidence'
NS = '{http://schemas.android.com/apk/res/android}'


def load(name):
    return json.loads((EVIDENCE / name).read_text())


def ui(name):
    return ET.parse(EVIDENCE / f'{name}.xml').getroot()


def contains(name, text):
    assert any(n.get('text') == text for n in ui(name).iter('node')), (name, text)


def disabled_start(name):
    root = ui(name)
    parents = {child: parent for parent in root.iter() for child in parent}
    node = next(n for n in root.iter('node') if n.get('text') == 'Start')
    while node.get('clickable') != 'true' and node in parents:
        node = parents[node]
    assert node.get('enabled') == 'false', name


def main():
    failures = {
        'package-missing': 'packageUnavailable',
        'package-disabled': 'packageUnavailable',
        'database-never-granted': 'accessDenied',
        'database-denied': 'accessDenied',
        'database-revoked': 'accessDenied',
        'microphone-never-granted': 'permissionDenied',
        'microphone-denied': 'permissionDenied',
        'microphone-revoked': 'permissionDenied',
        'provider-before-setup': 'nullCursor',
        'api-disabled-fixed': 'apiDisabled',
        'deck-missing': 'deckMissing',
    }
    for name, failure in failures.items():
        contains(name, failure)
        if name != 'deck-missing':  # Its Start button is below the captured viewport.
            disabled_start(name)
    for name, text in {
        'deck-selected': 'Selected: AV002 Baseline',
        'card-ready': 'Card ready',
        'rotation-session': 'Card ready',
        'home-return-paused': 'Paused · Tap Resume to continue',
        'stopped': 'Stopped',
        'stopped-after-resume': 'Stopped',
        'release-unavailable': 'Study unavailable',
        'settings-restored-verified': 'en-GB',
        'language-restored': 'en-US',
        'api-restored': 'AnkiDroid connected · Microphone allowed',
        'package-reenabled': 'AnkiDroid connected · Microphone allowed',
    }.items():
        contains(name, text)
    contains('deck-choices-final', 'Choosing a deck changes AnkiDroid’s current deck. This does not submit a review.')
    contains('microphone-permission-dialog', 'While using the app')
    contains('database-permission-dialog', 'Allow')

    before, after = load('database-before.json'), load('database-after.json')
    assert before == after
    assert len(after['cards']) == 8 and len(after['revlog']) == 11
    assert all('av002' in tags.split() for _, tags in after['notes'])
    for file, language in [('settings-en-gb.xml', 'en-GB'), ('settings-final.xml', 'en-US')]:
        settings = ET.parse(EVIDENCE / file).getroot()
        assert settings.find("string[@name='language']").text == language
        deck = int(settings.find("long[@name='selected_deck']").attrib['value'])
        assert deck == load('database-verification.json')['selected_deck']

    environment = load('environment.json')
    baseline = load('fixture-manifest.json')
    assert baseline  # The full generated manifest is retained with the run.
    assert environment['arch'] == 'arm64' and '26.6.2' in environment['host']
    assert 'Pkg.Revision=7' in environment['image_properties']
    assert 'BE2A.250530.026.D1/13818094' in environment['fingerprint']
    assert 'versionName=2.24.1' in environment['ankidroid']
    assert environment['accounts'] == 'Accounts: 0'
    for variant, apk in load('apk-verification.json').items():
        assert apk['fake_classes_present'] == (variant == 'debug')
        assert apk['target_sdk'] == 35 and apk['activities'] == 1
        manifest = ET.parse(EVIDENCE / f'{variant}-manifest.xml').getroot()
        assert manifest.find('uses-sdk').attrib[NS + 'targetSdkVersion'] == '35'
        perms = {n.attrib[NS + 'name'] for n in manifest.findall('uses-permission')}
        assert {'android.permission.RECORD_AUDIO', 'com.ichi2.anki.permission.READ_WRITE_DATABASE'} <= perms
        assert not {'android.permission.INTERNET', 'android.permission.READ_PHONE_STATE'} & perms
        assert manifest.find("queries/provider").attrib[NS + 'authorities'] == 'com.ichi2.anki.flashcards'
    suites = load('jvm-tests.json')
    assert sum(int(s['tests']) for s in suites) == 90
    assert all(s[k] == '0' for s in suites for k in ['failures', 'errors', 'skipped'])
    assert 'BUILD SUCCESSFUL' in (EVIDENCE / 'gradle-checks.txt').read_text()
    assert 'Ran 182 tests' in (EVIDENCE / 'python-tests.txt').read_text()
    assert 'Process: org.ankivoice' not in (EVIDENCE / 'crash-buffer.txt').read_text()
    hashes = load('sha256.json')
    assert set(hashes) == {p.name for p in EVIDENCE.iterdir() if p.is_file() and p.name != 'sha256.json'}
    for name, expected in hashes.items():
        assert hashlib.sha256((EVIDENCE / name).read_bytes()).hexdigest() == expected, name
    print('AV023 evidence verified: onboarding, deck/session/lifecycle, settings, release boundary, 90 JVM / 182 Python tests, 8 unchanged cards and 11 unchanged reviews.')


if __name__ == '__main__':
    main()
