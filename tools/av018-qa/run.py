"""AV-018 (#20) live check: strand a journalled review by force-stopping the process
mid-submission, then reconcile it in a new process against the real collection.

Two cases, the two rows startup reconciliation can reach from a real kill:

  before  the process dies between the durable journal write and the single dispatch.
          The card must be byte-for-byte unchanged, so reconciliation resolves `failed`
          and the revlog gains nothing.

  after   the process dies between the dispatch and the settle. The card shows one more
          review, which AV-004 proved this app cannot attribute to itself, so
          reconciliation resolves `outcome-unknown` — and still adds no second review.

Synthetic only. It refuses any deck whose name does not start with AV002, and the
instrumentation refuses it again on the device. Back the collection up first; see the
runbook.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('av004', ROOT / 'tools/av004-probe/run.py')
av = importlib.util.module_from_spec(spec)
spec.loader.exec_module(av)

# The probe helper pins AV-004's own emulator. This card runs on whichever pinned AVD is
# attached, so the serial is taken from the device list rather than assumed.
_attached = [line.split()[0] for line in
             subprocess.run([str(av.ADB), 'devices'], capture_output=True, check=True)
             .stdout.decode().splitlines()[1:] if line.strip().endswith('device')]
if len(_attached) != 1:
    raise SystemExit(f'Exactly one attached device is required; found {_attached}')
av.SERIAL = _attached[0]

OUT = ROOT / 'docs/testing/av018/evidence'
OUT.mkdir(exist_ok=True, parents=True)
BUILD = ROOT / 'build/av018'
BUILD.mkdir(exist_ok=True, parents=True)
APP = 'org.ankivoice'
TEST = 'org.ankivoice.test'
ENTRY = f'{TEST}/org.ankivoice.app.JournalInstrumentation'
DEBUG_APK = ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'
TEST_APK = ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'


def database(label):
    """Read-only collection snapshot. Full DBs stay under ignored build/."""
    previous = av.OUT
    av.OUT = BUILD / 'databases'
    av.OUT.mkdir(exist_ok=True, parents=True)
    shutil.rmtree(av.OUT / label, ignore_errors=True)
    try:
        result = av.database(label)
    finally:
        av.OUT = previous
    (OUT / f'{label}.json').write_text(json.dumps(
        {'integrity': result['integrity'],
         'cards': [{k: row[k] for k in ('id', 'nid', 'did', 'ord', 'type', 'queue', 'due', 'ivl', 'reps', 'lapses', 'mod')}
                   for row in result['cards']],
         'revlog': result['revlog']}, indent=2) + '\n')
    return result


def app_file(name):
    return av.adb('exec-out', 'run-as', APP, 'cat', f'files/{name}', check=False).decode(errors='replace')


def clear(deck):
    av.adb('shell', 'am', 'instrument', '-w',
           '-e', 'confirm', 'AV018_SYNTHETIC_ONLY', '-e', 'mode', 'clear',
           '-e', 'deck', str(deck), ENTRY)
    result = json.loads(app_file('av018-result.json'))
    assert result['passed'], result
    av.adb('shell', 'run-as', APP, 'rm', '-f', 'files/av018-ready', check=False)
    return result


def offered(deck):
    """The card the real provider would offer next. Reads only; writes nothing."""
    av.adb('shell', 'am', 'instrument', '-w',
           '-e', 'confirm', 'AV024_SYNTHETIC_ONLY', '-e', 'mode', 'peek',
           '-e', 'deck', str(deck), f'{TEST}/org.ankivoice.app.ReviewInstrumentation')
    result = json.loads(app_file('av024-result.json'))
    assert result['passed'], result
    return result['next']


def strand(deck, card, window, rating=3):
    """Run the strand mode and force-stop the process inside the named window."""
    av.adb('shell', 'run-as', APP, 'rm', '-f', 'files/av018-ready', check=False)
    process = subprocess.Popen(
        [str(av.ADB), '-s', av.SERIAL, 'shell', 'am', 'instrument', '-w',
         '-e', 'confirm', 'AV018_SYNTHETIC_ONLY', '-e', 'mode', 'strand',
         '-e', 'window', window, '-e', 'deck', str(deck), '-e', 'card', str(card),
         '-e', 'rating', str(rating), ENTRY],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    expected = f'{window}-dispatch'
    deadline = time.time() + 120
    while time.time() < deadline:
        marker = app_file('av018-ready').strip()
        if marker == expected:
            break
        time.sleep(0.2)
    else:
        process.kill()
        raise TimeoutError(f'The {window} window was never reached')
    # The process is blocked inside the window. This is the process loss under test.
    av.adb('shell', 'am', 'force-stop', APP)
    av.adb('shell', 'am', 'force-stop', TEST)
    try:
        output = process.communicate(timeout=60)[0].decode(errors='replace')
    except subprocess.TimeoutExpired:
        process.kill()
        output = 'instrumentation did not return after force-stop'
    (BUILD / f'strand-{window}.txt').write_text(output)
    return output


def reconcile(deck, label):
    av.adb('shell', 'am', 'instrument', '-w',
           '-e', 'confirm', 'AV018_SYNTHETIC_ONLY', '-e', 'mode', 'reconcile',
           '-e', 'deck', str(deck), ENTRY)
    result = json.loads(app_file('av018-result.json'))
    (OUT / f'{label}.json').write_text(json.dumps(result, indent=2) + '\n')
    assert result['passed'], result
    return result


def added_revlog(before, after):
    known = {row['id'] for row in before['revlog']}
    return [row for row in after['revlog'] if row['id'] not in known]


def environment(deck_name):
    def sha(path):
        return hashlib.sha256(Path(path).read_bytes()).hexdigest() if Path(path).is_file() else None
    record = {
        'avd': av.adb('emu', 'avd', 'name').decode().splitlines()[0],
        'fingerprint': av.adb('shell', 'getprop', 'ro.build.fingerprint').decode().strip(),
        'sdk': av.adb('shell', 'getprop', 'ro.build.version.sdk').decode().strip(),
        'ankidroid': av.adb('shell', 'dumpsys', 'package', 'com.ichi2.anki').decode().split('versionName=')[1].split()[0],
        'deck': deck_name,
        'appApkSha256': sha(DEBUG_APK),
        'testApkSha256': sha(TEST_APK),
        'capturedUtc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
    }
    (OUT / 'environment.json').write_text(json.dumps(record, indent=2) + '\n')
    return record


def case(deck, window, expected):
    label = f'{window}-dispatch'
    clear(deck)
    card = offered(deck)
    before = database(f'{label}-before')
    strand(deck, card['cardId'], window)
    stranded = database(f'{label}-stranded')
    journal_added = added_revlog(before, stranded)
    result = reconcile(deck, f'{label}-reconcile')
    after = database(f'{label}-after')

    entry = result['entriesBefore'][-1]
    assert entry['phase'] == 'dispatching', entry
    assert entry['cardId'] == card['cardId'], entry
    assert entry['transcript'] == 'five blocks', entry
    resolution = result['reconciliations'][-1]
    assert resolution['resolution'] == expected, resolution
    assert len(journal_added) == (1 if window == 'after' else 0), journal_added
    assert added_revlog(stranded, after) == [], 'reconciliation added a review'
    assert added_revlog(before, after) == journal_added, 'the revlog moved during reconciliation'
    summary = {
        'window': window,
        'card': card['cardId'],
        'rating': entry['rating'],
        'journalledPreState': entry['preState'],
        'observedState': resolution['observedState'],
        'resolution': resolution['resolution'],
        'reason': resolution['reason'],
        'notice': resolution['notice'],
        'revlogAddedByStrand': len(journal_added),
        'revlogAddedByReconciliation': 0,
        'outstandingNotices': result['outstanding'],
        'blocked': result['blocked'],
    }
    (OUT / f'{label}-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(f"{window}-dispatch: {resolution['resolution']}, "
          f"reviews added by the kill {len(journal_added)}, by reconciliation 0", flush=True)
    return summary


if __name__ == '__main__':
    decks = json.loads(Path(sys.argv[1]).read_text()) if len(sys.argv) > 1 else None
    deck_id, deck_name = (decks or [None, None])
    if deck_id is None:
        raise SystemExit('Pass a JSON file holding [deckId, deckName]; see the runbook.')
    if not deck_name.startswith('AV002'):
        raise SystemExit('Refusing a deck that is not a disposable AV002 fixture')
    environment(deck_name)
    summaries = [case(deck_id, 'before', 'failed'), case(deck_id, 'after', 'outcome-unknown')]
    (OUT / 'summary.json').write_text(json.dumps(summaries, indent=2) + '\n')
    print('AV-018 live check complete')
