"""Run the production adapter instrumentation on the guarded synthetic AVD."""
from pathlib import Path
import json
import sqlite3
import sys
import time
import ui
av = ui.av
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/testing/av024/evidence'
OUT.mkdir(exist_ok=True, parents=True)

def database(label):
    # Full DBs remain ignored; only synthetic JSON evidence is retained.
    previous = av.OUT
    av.OUT = ROOT / 'build/av024/databases'
    av.OUT.mkdir(exist_ok=True)
    try:
        result = av.database(label)
        (OUT / f'{label}.json').write_text(json.dumps(result, indent=2) + '\n')
        return result
    finally:
        av.OUT = previous

def deck_rows(label):
    path = ROOT / f'build/av024/databases/{label}/collection.anki2'
    with sqlite3.connect(f'file:{path}?mode=ro', uri=True) as db:
        db.row_factory = sqlite3.Row
        return [dict(row) for row in db.execute('select id, name from decks')]


def reset(name, label):
    before = database(label + '-before-reset')
    if any(' av002 ' not in row['tags'] for row in before['notes']):
        raise RuntimeError('Non-AV002 notes: refusing destructive replacement')
    av.adb('shell', 'am', 'start', '-n', 'com.ichi2.anki/.IntentHandler')
    av.tap('More options'); av.tap('Import'); av.tap('Collection package (.colpkg)')
    tree = av.ui()
    if any(n.get('content-desc') == 'List view' for n in tree.iter('node')): av.tap('List view')
    for _ in range(4):
        tree = av.ui()
        if any(n.get('text') == f'av024-{name}.colpkg' for n in tree.iter('node')): break
        av.adb('shell', 'input', 'swipe', '540', '2000', '540', '600', '350')
    av.tap(f'av024-{name}.colpkg'); av.tap('Replace')
    for _ in range(10):
        tree = av.ui()
        expected = 'Collection is empty' if name == 'deleted-deck' else ('AV002 Limits' if name == 'limits' else 'AV002 Baseline')
        if any(n.get('text') == expected for n in tree.iter('node')):
            return
    raise TimeoutError('Import did not complete')

def instrument(label, name, rating=3, mode='commit', elapsed=12345, deck=None, card=None):
    manifest = json.loads((ROOT / f'build/av024/isolated/{name}/manifest.json').read_text())
    target = manifest['target']
    deck = deck if deck is not None else manifest['snapshot']['deck']['id']
    card = card if card is not None else target['card_id']
    args = ['shell', 'am', 'instrument', '-w', '-e', 'confirm', 'AV024_SYNTHETIC_ONLY']
    for k, v in dict(deck=deck, card=card, rating=rating, mode=mode, elapsed=elapsed).items():
        args += ['-e', k, str(v)]
    args += ['org.ankivoice.test/org.ankivoice.app.ReviewInstrumentation']
    path = OUT / f'{label}.json'
    if path.exists():
        raise FileExistsError(path)
    result = av.adb(*args).decode()
    (ROOT / f'build/av024/{label}-instrument.txt').write_text(result)
    value = json.loads(av.adb('exec-out', 'run-as', 'org.ankivoice', 'cat', 'files/av024-result.json'))
    path.write_text(json.dumps(value, indent=2) + '\n')
    return value

def case(name, rating, label=None, elapsed=12345):
    label = label or f'{name}-rating-{rating}'
    reset(name, label)
    before = database(label + '-before')
    result = instrument(label, name, rating, 'invalid' if rating in (0, 5) else 'commit', elapsed)
    after = database(label + '-after')
    assert result['passed'], result
    added = [row for row in after['revlog'] if row['id'] not in {r['id'] for r in before['revlog']}]
    assert len(added) == (0 if rating in (0, 5) else 1), added
    if added:
        assert added[0]['cid'] == result['next']['cardId'] and added[0]['ease'] == rating
        cap = result['capabilities']['maxReviewTimeMs']
        assert min(elapsed, cap) <= added[0]['time'] <= min(elapsed + result['outcome']['dispatchDurationMs'], cap)
    print(label, result['outcome']['state'], 'revlog additions', len(added), flush=True)

if __name__ == '__main__':
    if sys.argv[1] == 'matrix':
        for name in ('new', 'learning', 'relearning', 'mature'):
            for rating in range(1, 5): case(name, rating)
    elif sys.argv[1] == 'case':
        case(sys.argv[2], int(sys.argv[3]))
