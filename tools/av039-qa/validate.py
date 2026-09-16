#!/usr/bin/env python3
"""Verify the retained AV-039 provisioning evidence. No device or network access."""
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / 'docs/testing/av039/evidence'
sys.path.insert(0, str(ROOT))
from tools import av039_note_type as generator  # noqa: E402

SPECIFICATION = json.loads(generator.FIXTURE.read_text(encoding='utf-8'))
FIELDS = [field['name'] for field in SPECIFICATION['fields']]
DEMO_DECK = 'VoiceQA Demo'


def load(name):
    return json.loads((EVIDENCE / name).read_text())


def ui(name):
    return ET.parse(EVIDENCE / f'{name}.xml').getroot()


def contains(name, text):
    assert any(text in (node.get('text') or '') for node in ui(name).iter('node')), (name, text)
    assert (EVIDENCE / f'{name}.png').is_file(), name


def unchanged(comparison, allow_notetypes=(), allow_decks=(), allow_notes=0):
    """Nothing outside what AV-039 is allowed to add may differ."""
    result = load(comparison)
    assert list(result['notetypes_added']) == list(allow_notetypes), comparison
    assert result['notetypes_changed'] == [], comparison
    assert result['decks_renamed'] == [], comparison
    assert list(result['decks_added']) == list(allow_decks), (comparison, result['decks_added'])
    assert len(result['notes_added']) == allow_notes, comparison
    assert result['notes_changed'] == [], comparison
    assert result['existing_cards_changed'] == [], comparison
    assert result['reviews_added_or_altered'] is False, comparison
    assert result['cards_after'] - result['cards_before'] == allow_notes, comparison
    return result


def voiceqa(collection):
    return next(v for v in load(collection)['notetypes'].values() if v['name'] == 'VoiceQA')


def main():
    environment = load('environment.json')
    assert environment['avd']['name'] == 'AnkiVoice_AV039', environment
    assert environment['avd']['reused_existing_avd'] is False
    assert environment['ankidroid']['versionName'] == '2.24.1', environment
    assert environment['ankidroid']['apk_sha256'] == (
        '3012692ca67b856b287430715f99ca6150e471588326f6c8c45f27bbe895afbf')
    assert environment['device']['sdk'] == '36' and environment['device']['abi'] == 'arm64-v8a'
    assert environment['sync_performed'] is False and environment['ankiweb_signed_in'] is False

    # Each failure is named with AV-007's spelling, and none of them is a successful setup.
    for capture, failure in {
        'setup-access-denied': 'accessDenied',
        'setup-access-revoked': 'accessDenied',
        'setup-api-disabled': 'apiDisabled',
        'setup-package-unavailable': 'packageUnavailable',
    }.items():
        contains(capture, failure)
        contains(capture, 'AnkiDroid did not answer, so nothing was changed.')

    # Disclosure before the first write, and a decline that changed nothing.
    contains('setup-disclosure', 'one-way full sync')
    contains('setup-disclosure', 'Install VoiceQA')
    contains('setup-disclosure', 'Not now')
    contains('setup-declined', 'Setup cancelled. Nothing in your collection was changed.')
    unchanged('comparison-declined.json')

    # A collection without VoiceQA: installed once, with the fixture's shape.
    assert 'VoiceQA' not in [v['name'] for v in load('collection-fresh-before.json')['notetypes'].values()]
    contains('setup-needed-fresh', 'Install the VoiceQA note type.')
    contains('setup-installed-fresh', 'VoiceQA installed')
    contains('setup-installed-fresh', '4 sample notes added to VoiceQA Demo')
    unchanged('comparison-fresh-install.json', allow_notetypes=['VoiceQA'],
              allow_decks=[DEMO_DECK], allow_notes=4)
    installed = voiceqa('collection-fresh-after.json')
    assert installed['fields'] == FIELDS, installed
    assert installed['templates'] == [SPECIFICATION['templates'][0]['name']], installed
    fresh = load('collection-fresh-after.json')
    assert {c['deck'] for c in fresh['cards']} == {DEMO_DECK}, fresh['cards']
    assert len(fresh['cards']) == 4 and {c['ord'] for c in fresh['cards']} == {0}
    examples = {e['id']: e for e in SPECIFICATION['examples']}
    for note in fresh['notes']:
        identifier = note['tags'].split()[-1]
        assert note['fields'] == [examples[identifier]['fields'].get(f, '') for f in FIELDS], note

    # Running setup again reports reuse and duplicates nothing.
    contains('setup-repeat-run', 'VoiceQA already installed')
    contains('setup-repeat-run', 'sample notes were skipped')
    unchanged('comparison-repeat.json')

    # A VoiceQA note type whose fields differ stops with a named conflict.
    assert voiceqa('collection-conflict-before.json')['fields'] != FIELDS
    contains('setup-field-conflict', 'A different VoiceQA note type is in the way.')
    contains('setup-field-conflict', 'Language, Notes.')
    contains('setup-field-conflict', ', '.join(FIELDS) + ', in that order.')
    contains('setup-field-conflict', 'Nothing was changed.')
    unchanged('comparison-conflict.json')

    # A collection that already has VoiceQA: reused byte for byte, demo content still added.
    before = voiceqa('collection-existing-before.json')
    assert before['fields'] == FIELDS and before['templates'] == ['Voice recall'], before
    contains('setup-existing-notetype', 'Add the VoiceQA Demo deck and its 4 sample notes.')
    contains('setup-reused-notetype', 'VoiceQA already installed · 4 sample notes added to VoiceQA Demo.')
    result = unchanged('comparison-existing-notetype.json', allow_decks=[DEMO_DECK], allow_notes=4)
    assert result['reviews_before'] == 11 and result['reviews_after'] == 11, result
    assert result['cards_before'] == 8 and result['cards_after'] == 12, result
    after = load('collection-existing-after.json')
    demo = [c for c in after['cards'] if c['deck'] == DEMO_DECK]
    assert len(demo) == 4 and all(c['reps'] == 0 for c in demo), demo
    baseline = [c for c in after['cards'] if c['deck'] == 'AV002 Baseline']
    assert len(baseline) == 8, baseline
    assert all(n['tags'].startswith('av002') for n in after['notes']
               if not n['tags'].startswith(generator.DEMO_TAG)), 'AV-002 notes were rewritten'
    unchanged('comparison-existing-repeat.json')
    contains('setup-existing-repeat', 'sample notes were skipped')
    contains('setup-final-complete', 'VoiceQA already installed')

    # The synthetic collection this ran against, and a clean crash buffer.
    manifest = load('fixture-manifest.json')
    assert manifest['issue'] == 2 and manifest['profile'] == 'baseline', manifest
    assert manifest['initial_counts_new_learning_review'] == [1, 2, 1], manifest
    assert (EVIDENCE / 'crash-buffer.txt').read_text().strip() == '', 'crash buffer is not empty'

    tests = load('jvm-tests.json')
    assert tests['failures'] == 0, tests
    provisioning = tests['ankidroid']['org.ankivoice.ankidroid.ProvisioningTest']['tests']
    note_type = tests['ankidroid']['org.ankivoice.ankidroid.VoiceQaNoteTypeTest']['tests']
    shell = tests['app']['org.ankivoice.app.ShellControllerTest']['tests']
    print(f'AV039 evidence verified: four named failures, disclosure and decline, fresh install, '
          f'reuse, conflict and repeat runs; 8 unchanged cards and 11 unchanged reviews; '
          f'{tests["total_tests"]} JVM tests ({provisioning} provisioning, {note_type} note type, '
          f'{shell} shell).')
    return 0


if __name__ == '__main__':
    sys.exit(main())
