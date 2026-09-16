"""Derive single-eligible-card packages from newly generated AV002 fixtures.

Other eligible cards are suspended only in fresh host copies, never in a user's
collection or through the product adapter. This isolates each state/rating case
without preparatory reviews contaminating the one-write assertion.
"""
from pathlib import Path
import sys
import json
import shutil
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from tools.voiceqa_fixtures import Collection, export_package, snapshot
root = Path(__file__).resolve().parents[2]
base = root / 'build/av024/fixtures/baseline'
manifest = json.loads((base / 'av002-manifest.json').read_text())
for name in ('new', 'learning', 'relearning', 'mature', 'deleted-card', 'deleted-deck'):
    directory = root / 'build/av024/isolated' / name
    if directory.exists():
        if (directory / 'collection.colpkg').exists():
            continue  # Preserve completed fixtures; never overwrite an evidence input.
        raise FileExistsError(directory)
    directory.mkdir(parents=True, exist_ok=False)
    path = directory / 'collection.anki2'
    shutil.copy2(base / 'collection.anki2', path)
    target = next(c for c in manifest['cards'] if c['fixture_id'] == (name if not name.startswith('deleted-') else 'new'))
    col = Collection(str(path))
    try:
        others = [cid for cid in col.find_cards('tag:av002') if cid != target['card_id']]
        col.sched.suspend_cards(others)
        if name == 'deleted-card':
            col.remove_notes([target['note_id']])
        if name == 'deleted-deck':
            col.decks.remove([manifest['deck']['id']])
        (directory / 'manifest.json').write_text(json.dumps(dict(target=target, snapshot=snapshot(col)), indent=2))
        export_package(col, directory / 'collection.colpkg')
    finally:
        col.close()
