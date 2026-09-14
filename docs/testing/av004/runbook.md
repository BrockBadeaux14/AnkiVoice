# Reproduce the AV-004 emulator experiment

Read the [findings and constraints](../av004-ankidroid-review-access.md) first.
This is a disposable diagnostic probe, not the AnkiVoice product adapter.
Only the explicitly named emulator is accepted by the host helper. The Android
probe also refuses non-emulator hardware and non-AV002 collections before writes.
It requests the AnkiDroid permission, has no internet permission, and logs only
the synthetic experiment to its private `files/result.json`.

## Setup

The tested machine already had Android Studio, the SDK, image revision 7,
platform 36 and build tools 36.0.0 installed. Install those prerequisites through
Android Studio if missing; record any different image/build as a new environment.
The existing AVD must not be reused or wiped. From the repository root:

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"

"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager" create avd \
  --name AnkiVoice_AV004 \
  --package 'system-images;android-36;google_apis_playstore;arm64-v8a' \
  --device medium_phone

# Run in a separate terminal. -no-window was used in the recorded run;
# omit it to watch the emulator. Do not sign in to AnkiWeb or Google.
"$ANDROID_SDK_ROOT/emulator/emulator" -avd AnkiVoice_AV004 -port 5584 \
  -no-window -no-audio -no-boot-anim -no-snapshot -timezone America/Chicago
```

In the working terminal, wait until `adb -s emulator-5584 shell getprop
sys.boot_completed` returns `1`. Confirm its timezone and AVD name:

```sh
adb -s emulator-5584 emu avd name
adb -s emulator-5584 shell getprop persist.sys.timezone
mkdir -p build/av004
curl -fL https://github.com/ankidroid/Anki-Android/releases/download/v2.24.1/AnkiDroid-2.24.1-arm64-v8a.apk \
  -o build/av004/AnkiDroid-2.24.1-arm64-v8a.apk
shasum -a 256 build/av004/AnkiDroid-2.24.1-arm64-v8a.apk
```

Check the SHA-256 against the report **before installing**. Then:

```sh
adb -s emulator-5584 install --no-incremental build/av004/AnkiDroid-2.24.1-arm64-v8a.apk
bash tools/av004-probe/build.sh
adb -s emulator-5584 install --no-incremental build/av004/probe/av004-probe.apk
```

The build uses `javac`, `d8`, `aapt2`, `zipalign` and `apksigner` directly, with
no Gradle/project-framework commitment. Its disposable signing key stays in
ignored `build/av004/probe/`; it is not an application credential. Reinstall a
rebuilt probe using `install --no-incremental -r`.

## Generate and import fresh fixtures

Use the [AV-002 setup](../voiceqa-fixtures.md) to prepare `.venv`. Generate on the
target test day and timezone. Existing output is refused; preserve/move a prior
`build/av004/` before starting a fresh experiment. The retained evidence is a
historical run, not a package to reuse on later days.

```sh
TZ=America/Chicago .venv/bin/python tools/voiceqa_fixtures.py build --output build/av004/fixtures
adb -s emulator-5584 push build/av004/fixtures/baseline/collection.colpkg /sdcard/Download/av004-baseline.colpkg
adb -s emulator-5584 push build/av004/fixtures/limits/collection.colpkg /sdcard/Download/av004-limits.colpkg
adb -s emulator-5584 shell am start -n com.ichi2.anki/.IntentHandler
```

On first launch choose **Get Started**, grant **All files access**, and confirm
the collection is empty. Use **More options → Import → Collection package
(.colpkg) → Downloads → av004-baseline.colpkg → Replace**. This replaces the
entire synthetic collection. Confirm **AV002 Baseline: 1 new / 2 learning /
1 review**. The tested full-release APK keeps its database at
`/sdcard/AnkiDroid/collection.anki2`; no root was used.

Use native collection import, rather than private activities. Direct launch of
`.DeckPicker` was rejected because it is not exported, and the tested shell
`file://` VIEW attempt did not import; neither was used as the integration route.

For repeat resets, with the file picker already on Downloads:

```sh
python3 tools/av004-probe/run.py reset baseline-reset
python3 tools/av004-probe/run.py reset limits-reset --profile limits
```

The helper verifies that the current notes are AV002 before replacing them.
It reads a fresh UI tree and taps exact visible labels; if a label is missing,
it stops. Open Downloads manually if the picker is elsewhere. On this run the
pristine states were also saved using `adb -s emulator-5584 emu avd snapshot save`
with names `av004-baseline-20260914` and `av004-limits-20260914`. Snapshots retain
old dates: rebuild after a scheduler rollover or timezone change.

## Permission and normal API runs

Request permission through the native Android dialog:

```sh
adb -s emulator-5584 shell am start -S -n org.ankivoice.av004/.ProbeActivity --es op permission
# Tap Don't allow for the denial experiment; capture before starting another probe.
adb -s emulator-5584 exec-out run-as org.ankivoice.av004 cat files/result.json
# Request again, choose Allow, then run the normal experiment below.
```

Each helper invocation captures JSON under `build/av004/evidence/`. Use unique
labels: `probe` refuses to overwrite a result. Run calls serially, waiting for
each result; do not start another while a permission dialog is pending.
`probe --op permission` can wait briefly for an interactive grant, but for a
human-paced permission test use the direct commands above.

```sh
python3 tools/av004-probe/run.py database baseline-before
python3 tools/av004-probe/run.py probe baseline-initial
python3 tools/av004-probe/run.py probe learning-answer --op answer --fixture learning
python3 tools/av004-probe/run.py probe relearning-answer --op answer --fixture relearning
python3 tools/av004-probe/run.py probe mature-answer --op answer --fixture mature
python3 tools/av004-probe/run.py probe new-answer --op answer --fixture new
python3 tools/av004-probe/run.py database baseline-after
```

These are the recorded baseline identities/order. The helper resolves note and
deck IDs from the **current generated manifest**. Each ordinary answer checks
that the selected note/ordinal still matches a fresh schedule query. If it
reports a stale identity, inspect the new queue and start a fresh attempt; do not
force the old answer through. Diagnostic command completion is not proof that
the review succeeded: inspect its error, update count, and before/after state.

After a separate limits reset, capture a database baseline and run:

```sh
python3 tools/av004-probe/run.py probe limits-initial --profile limits
python3 tools/av004-probe/run.py probe limits-review-1 --profile limits --op answer --fixture review-1
python3 tools/av004-probe/run.py probe limits-new-1 --profile limits --op answer --fixture new-1
python3 tools/av004-probe/run.py probe limits-review-2 --profile limits --op answer --fixture review-2
python3 tools/av004-probe/run.py database limits-after
python3 tools/av004-probe/run.py probe limits-reopened --profile limits
```

The last two schedule results should be empty. Inspect the original manifest and
captured card state to confirm new-2, new-3 and review-3 are unchanged.

The `database` command **force-stops AnkiDroid**, copies its DB and WAL into a new
host directory, and reads them in SQLite read-only mode. It neither changes the
device DB nor answers cards through the Python Anki backend. It can rebuild the
queue on the next launch, so never place it between choosing a card and relying
on that choice. Use API before/after snapshots within an uninterrupted queue run;
use offline snapshots around the run for exact review-history assertions.

### API calls made by the probe

All paths below are relative to `content://com.ichi2.anki.flashcards/`:

- Query `decks`, `notes`, `models`, and `cards` (`tag:av002`) for identity/content.
- Update `selected_deck` with `deck_id` from the manifest.
- Query `schedule` with `limit=?,deckID=?`, arguments `1` and the selected deck.
- Update `schedule` with `note_id`, `ord`, `answer_ease`, and `time_taken`.
- Re-query `cards` and `schedule` and record the raw results.

The declared Android permission is
`com.ichi2.anki.permission.READ_WRITE_DATABASE`. Manifest package/provider
queries make the cross-app provider visible. Rating values are 1=Again, 2=Hard,
3=Good, 4=Easy. All core submissions in this experiment used Easy; other valid
rating transitions were not individually tested.

## Native comparisons and bounded negative experiments

For each native comparison, re-import the pristine matching package, open the
deck, and use **Show answer → Easy** until exhausted. Capture UI trees at each
answer and a DB snapshot at the end. The recorded baseline had four reviews and
the limits run had three. Compare persisted `cid`, `ease`, `ivl`, `lastIvl`,
`factor`, and review type with the API run, ignoring wall-clock IDs and UI time.

```sh
python3 tools/av004-probe/run.py ui step-name
python3 tools/av004-probe/run.py tap 'Show answer'
python3 tools/av004-probe/run.py tap Easy
adb -s emulator-5584 exec-out screencap -p > build/av004/evidence/step-name.png
```

Use new labels and a fresh baseline for these cases:

- **Invalid rating:** `probe invalid-0 --op raw-answer --fixture learning --ease 0`
  and similarly `--ease 5`; compare database history/state before and after.
- **Revocation:** `adb -s emulator-5584 shell pm revoke org.ankivoice.av004
  com.ichi2.anki.permission.READ_WRITE_DATABASE`, then request a probe answer.
  Its permission preflight must fail before writing. Restore permission through
  the dialog (or `pm grant` for repeat tests).
- **API disabled:** Settings → Advanced → scroll → Enable AnkiDroid API, turn it
  off; run a probe answer and compare DB state. Re-enable using the same setting.
- **Provider unavailable:** disable the synthetic device's package using
  `adb -s emulator-5584 shell pm disable-user --user 0 com.ichi2.anki`, run the
  probe, inspect unchanged DB state, then restore with `pm enable com.ichi2.anki`.
- **Stale raw answer:** reset limits, answer review-1, observe new-1 offered, then
  use `database` to stop/copy AnkiDroid. Reopen/query: review-2 was now offered on
  this run. `probe stale --profile limits --op raw-answer --fixture new-1`
  reproduces the intentionally bypassed identity check. The captured historical
  attempt is `limits-api-new-1.json`; it predated the ordinary path's added guard.
  It returned `1` without adding a review. Capture `logcat -d -s AnkiDroid:E` and
  compare the offline state. This is a negative experiment, not a retry strategy.
- **Time cap:** on baseline, answer learning with `--elapsed 98765`; the captured
  revlog time was 60000 because the fixture deck's maximum is 60 seconds.
- **Native Undo:** after one confirmed API submission, open AnkiDroid without
  force-stopping it, open the deck, and tap the native toolbar Undo arrow. This
  release exposes that button as an enabled, unlabeled ImageButton in the UI
  tree; the saved screenshot identifies it. Compare state/history with the
  preceding capture. Do not force-stop between the answer and undo.

Raw negative mode is deliberately restricted to the named synthetic emulator
and AV002 notes. It exists to observe what the provider actually does. Normal
`answer` validates the offered identity, rating range and nonnegative time.
Neither mode implements a production commit/recovery protocol.

## Verify the retained evidence

```sh
python3 tools/av004-probe/validate_evidence.py
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -m pip check
git diff --check
```

The evidence validator uses files in `docs/testing/av004/evidence/`; pass
`--evidence PATH` for an identically named captured run. Its 161 assertions check
the historical experiment and do not launch an emulator. Generated DBs, APKs,
keystore, upstream source copies and temporary logs stay ignored under `build/`.
Keep the AVD and packages synthetic-only and signed out of AnkiWeb throughout.
