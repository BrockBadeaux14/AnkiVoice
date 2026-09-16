#!/usr/bin/env python3
"""Offline before/after snapshots of the AV-039 emulator's synthetic collection.

AnkiDroid is force-stopped, its collection and WAL are pulled into an ignored build
directory and opened read-only. The app itself never uses this filesystem access; this
exists so a provisioning run can be checked against the rows it must not touch.
"""
import argparse
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys

ADB = Path.home() / 'Library/Android/sdk/platform-tools/adb'
SERIAL = 'emulator-5584'
AVD = 'AnkiVoice_AV039'
ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'build' / 'av039'
REMOTE = '/storage/emulated/0/AnkiDroid'


def adb(*args):
    return subprocess.check_output([str(ADB), '-s', SERIAL, *args])


def pull(label):
    if adb('emu', 'avd', 'name').decode().splitlines()[0] != AVD:
        raise SystemExit(f'Refusing any AVD other than {AVD}')
    adb('shell', 'am', 'force-stop', 'com.ichi2.anki')
    target = WORK / label
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    for name in ('collection.anki2', 'collection.anki2-wal', 'collection.anki2-shm'):
        try:
            adb('pull', f'{REMOTE}/{name}', str(target / name))
        except subprocess.CalledProcessError:
            pass  # WAL and SHM are not always present.
    return target


def read(directory):
    connection = sqlite3.connect(f'file:{directory / "collection.anki2"}?mode=ro', uri=True)
    try:
        notetypes = {row[0]: row[1] for row in connection.execute('select id, name from notetypes')}
        decks = {row[0]: row[1].replace('\x1f', '::')
                 for row in connection.execute('select id, name from decks')}
        fields = {}
        for mid, ord_, name in connection.execute('select ntid, ord, name from fields order by ntid, ord'):
            fields.setdefault(mid, []).append(name)
        templates = {}
        for mid, ord_, name, config in connection.execute(
                'select ntid, ord, name, config from templates order by ntid, ord'):
            templates.setdefault(mid, []).append(name)
        notes = [{'id': row[0], 'notetype': notetypes.get(row[1], str(row[1])),
                  'tags': row[2].strip(), 'fields': row[3].split('\x1f')}
                 for row in connection.execute('select id, mid, tags, flds from notes order by id')]
        cards = [{'id': row[0], 'note': row[1], 'deck': decks.get(row[2], str(row[2])), 'ord': row[3],
                  'type': row[4], 'queue': row[5], 'due': row[6], 'ivl': row[7], 'reps': row[8],
                  'lapses': row[9]}
                 for row in connection.execute(
                     'select id, nid, did, ord, type, queue, due, ivl, reps, lapses '
                     'from cards order by id')]
        revlog = [dict(zip(('id', 'card', 'ease', 'ivl', 'time', 'type'), row))
                  for row in connection.execute(
                      'select id, cid, ease, ivl, time, type from revlog order by id')]
        return {
            'notetypes': {str(mid): {'name': name, 'fields': fields.get(mid, []),
                                     'templates': templates.get(mid, [])}
                          for mid, name in sorted(notetypes.items())},
            'decks': {str(did): name for did, name in sorted(decks.items())},
            'notes': notes, 'cards': cards, 'revlog': revlog,
        }
    finally:
        connection.close()


def compare(before, after):
    """What a provisioning run is allowed to add, and what it must leave identical."""
    existing = {str(k): v for k, v in before['notetypes'].items()}
    report = {
        'notetypes_added': [v['name'] for k, v in after['notetypes'].items() if k not in existing],
        'notetypes_changed': [v['name'] for k, v in after['notetypes'].items()
                              if k in existing and existing[k] != v],
        'decks_added': [v for k, v in after['decks'].items() if k not in before['decks']],
        'decks_renamed': [v for k, v in after['decks'].items()
                          if k in before['decks'] and before['decks'][k] != v],
        'notes_added': [n['fields'][0][:60] for n in after['notes']
                        if n['id'] not in {m['id'] for m in before['notes']}],
        'notes_changed': [n['id'] for n in after['notes']
                          if n['id'] in {m['id'] for m in before['notes']}
                          and n != next(m for m in before['notes'] if m['id'] == n['id'])],
        'cards_before': len(before['cards']),
        'cards_after': len(after['cards']),
        'existing_cards_changed': [c['id'] for c in after['cards']
                                   if c['id'] in {d['id'] for d in before['cards']}
                                   and c != next(d for d in before['cards'] if d['id'] == c['id'])],
        'reviews_before': len(before['revlog']),
        'reviews_after': len(after['revlog']),
        'reviews_changed': before['revlog'] != after['revlog'][:len(before['revlog'])],
    }
    report['reviews_added_or_altered'] = (
        report['reviews_before'] != report['reviews_after'] or report['reviews_changed']
    )
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['pull', 'compare'])
    parser.add_argument('label')
    parser.add_argument('second', nargs='?')
    args = parser.parse_args()
    if args.command == 'pull':
        data = read(pull(args.label))
        (WORK / f'{args.label}.json').write_text(json.dumps(data, indent=2) + '\n')
        print(json.dumps({'label': args.label, 'notetypes': len(data['notetypes']),
                          'decks': len(data['decks']), 'notes': len(data['notes']),
                          'cards': len(data['cards']), 'reviews': len(data['revlog'])}))
        return 0
    before = json.loads((WORK / f'{args.label}.json').read_text())
    after = json.loads((WORK / f'{args.second}.json').read_text())
    print(json.dumps(compare(before, after), indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
