"""Additional AV024 cases; run after the 16-case matrix."""
import concurrent.futures
import json
import time
from pathlib import Path
import run
av, ROOT = run.av, run.ROOT

for name in ('deleted-card', 'deleted-deck'):
    av.adb('push', ROOT / f'build/av024/isolated/{name}/collection.colpkg', f'/sdcard/Download/av024-{name}.colpkg')
av.adb('push', ROOT / 'build/av024/fixtures/limits/collection.colpkg', '/sdcard/Download/av024-limits.colpkg')

for rating in (0, 5):
    run.case('new', rating, f'invalid-{rating}')
run.case('learning', 3, 'elapsed-cap', 98765)

baseline = json.loads((ROOT / 'build/av024/fixtures/baseline/av002-manifest.json').read_text())
for name, mode in (('deleted-card', 'read'), ('deleted-deck', 'snapshot')):
    run.reset(name, name)
    run.database(name + '-before')
    result = run.instrument(name, name, mode=mode, deck=baseline['deck']['id'])
    run.database(name + '-after')
    assert result['passed'], result
    print(name, result, flush=True)
    if name == 'deleted-deck':
        (run.OUT / 'deleted-deck-decks.json').write_text(json.dumps({
            stage: run.deck_rows(name + '-' + stage) for stage in ('before', 'after')
        }, indent=2) + '\n')

def toggle_api():
    av.adb('shell', 'am', 'start', '-n', 'com.ichi2.anki/.IntentHandler')
    av.tap('Open drawer'); av.tap('Settings'); av.tap('Advanced')
    for _ in range(4):
        tree = av.ui()
        if any(n.get('text') == 'Enable AnkiDroid API' for n in tree.iter('node')): break
        av.adb('shell', 'input', 'swipe', '540', '2000', '540', '600', '350')
    av.tap('Enable AnkiDroid API')
    av.adb('shell', 'input', 'keyevent', '4')
    av.adb('shell', 'input', 'keyevent', '4')

for label in ('api-disabled', 'permission-revoked'):
    run.reset('new', label)
    run.database(label + '-before')
    if label == 'api-disabled': toggle_api()
    else: av.adb('shell', 'pm', 'revoke', 'org.ankivoice', 'com.ichi2.anki.permission.READ_WRITE_DATABASE')
    try:
        result = run.instrument(label, 'new', mode='snapshot')
        run.database(label + '-after')
        assert result['passed'], result
        print(label, result, flush=True)
    finally:
        if label == 'api-disabled': toggle_api()
        else: av.adb('shell', 'pm', 'grant', 'org.ankivoice', 'com.ichi2.anki.permission.READ_WRITE_DATABASE')

run.reset('new', 'async-read')
result = run.instrument('async-read', 'new', mode='async')
assert result['passed'], result
print('async-read', result, flush=True)

# One preparatory review changes limits queue order, then a force-stop rebuild
# withdraws the offered new card. Keep the actual pending intent alive in the app.
run.reset('limits', 'stale-rebuild')
manifest = json.loads((ROOT / 'build/av024/fixtures/limits/av002-manifest.json').read_text())
av.adb('shell', 'run-as', 'org.ankivoice', 'rm', '-f', 'files/av024-held.json', 'files/av024-resume')
with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
    pending = executor.submit(run.instrument, 'stale-rebuild', 'new', 3, 'stale', 12345, manifest['deck']['id'])
    for _ in range(100):
        held = av.adb('exec-out', 'run-as', 'org.ankivoice', 'cat', 'files/av024-held.json', check=False)
        try:
            held = json.loads(held)
            break
        except (ValueError, UnicodeError):
            if pending.done(): raise RuntimeError(pending.result())
            time.sleep(.2)
    else:
        raise TimeoutError('No held intent')
    (run.OUT / 'stale-held.json').write_text(json.dumps(held, indent=2) + '\n')
    run.database('stale-rebuild-before')
    av.adb('shell', 'run-as', 'org.ankivoice', 'touch', 'files/av024-resume')
    result = pending.result(timeout=40)
    run.database('stale-rebuild-after')
    assert result['passed'], result
    print('stale-rebuild', result, flush=True)
