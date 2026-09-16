# AV-039 provisioning verification

Issue [#10](https://github.com/BrockBadeaux14/AnkiVoice/issues/10).
See [results](results.md) for the actual run and its limits.

Nothing in this runbook submits a review, syncs, or writes to an AV-002 generated
deck. Every write is one of the four AV-039 names its policy: a `models` insert
with its template update, a `decks` insert, `notes` inserts, and a move of the
cards that run just created.

## Build and local checks

From the repository root on the pinned macOS ARM64 host:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
android/gradlew -p android --console=plain checkModuleBoundaries :core:test assembleDebug
android/gradlew -p android --console=plain :ankidroid:testDebugUnitTest :app:testDebugUnitTest :app:assembleRelease :app:lintDebug
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -m pip check
.venv/bin/python tools/av006-probe/validate_evidence.py
python3 tools/av023-qa/validate.py
python3 tools/av039-qa/validate.py
```

The Android workflow runs both Gradle commands; the fixtures workflow runs the
Python ones. The AV-039 validator checks the retained evidence only; it never
operates an emulator. GitHub Actions runs after a future push: these are local
executions of its commands, not a claim of a remote CI run.

After an intentional change to `fixtures/voiceqa/note-type.json`:

```sh
python tools/av039_note_type.py --write
.venv/bin/python -m unittest tests.test_av039_note_type -v
android/gradlew -p android :ankidroid:testDebugUnitTest
```

## Dedicated emulator

Use the [AV-022 baseline](../../decisions/0022-android-implementation.md): macOS
26.6.2 ARM64, image `system-images;android-36;google_apis_playstore;arm64-v8a`,
medium_phone at 1080×2400 / 420 dpi, emulator 37.1.11.0 and AnkiDroid 2.24.1
ARM64. Do not reuse or wipe a personal AVD, and do not reuse the AV-004, AV-005,
AV-006 or AV-023 AVDs. This run created a new one:

```sh
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
  --name AnkiVoice_AV039 \
  --package 'system-images;android-36;google_apis_playstore;arm64-v8a' \
  --device medium_phone
"$ANDROID_HOME/emulator/emulator" -avd AnkiVoice_AV039 -port 5584 \
  -no-window -no-audio -no-boot-anim -no-snapshot -timezone America/Chicago
```

Wait for `adb -s emulator-5584 shell getprop sys.boot_completed` to report `1`.
Keep it signed out of Google and AnkiWeb. `tools/av039-qa/ui.py` refuses any
other AVD name, captures a fresh UI tree before locating a tap, and refuses to
overwrite an existing capture; `capture LABEL` saves XML and PNG under this
directory's `evidence/`, `show` lists labels and bounds, and `tap 'Exact label'`
taps the matching element. The setup card sits below the first screenful: swipe
up before looking for **Check setup**. A transient empty UI dump should be
retried after the screen has appeared; do not infer state from it.

`tools/av039-qa/snapshot.py pull LABEL` force-stops AnkiDroid, pulls
`collection.anki2` and its WAL into an ignored `build/av039/` directory, and
records note types, fields, templates, decks, notes, cards and `revlog` from a
read-only copy. `compare BEFORE AFTER` prints what changed. The app never uses
this filesystem access; it exists only to check the rows a run must not touch.

## Install and permissions

1. Install `android/app/build/outputs/apk/debug/app-debug.apk` and launch
   `org.ankivoice/org.ankivoice.app.MainActivity` before installing AnkiDroid.
   The setup card is hidden while the shell reports `packageUnavailable` with no
   collection to read.
2. Install the pinned AnkiDroid APK. Verify SHA-256
   `3012692ca67b856b287430715f99ca6150e471588326f6c8c45f27bbe895afbf` first.
3. Open AnkiDroid, choose **Get Started**, grant **All files access**, and
   confirm its collection is empty.
4. Grant AnkiVoice its permissions. AV-023 holds the native-dialog evidence for
   first grant, denial and re-request; this run uses adb so its captures stay on
   provisioning:

   ```sh
   adb -s emulator-5584 shell pm grant org.ankivoice com.ichi2.anki.permission.READ_WRITE_DATABASE
   adb -s emulator-5584 shell pm grant org.ankivoice android.permission.RECORD_AUDIO
   ```

## Provisioning matrix

Take `snapshot.py pull` before and after every row that could write.

1. **Denied database permission.** Before granting, tap **Check setup**: expect
   `accessDenied` and no write. Do the same after
   `pm revoke org.ankivoice com.ichi2.anki.permission.READ_WRITE_DATABASE`, then
   grant it again.
2. **Disabled API.** In AnkiDroid, **Settings → Advanced → Enable AnkiDroid
   API**, switch it off. Tap **Check setup**: expect `apiDisabled`. Switch it
   back on and confirm setup answers again.
3. **Disabled package.** With AnkiVoice backgrounded,
   `adb -s emulator-5584 shell pm disable-user --user 0 com.ichi2.anki`, return
   and tap **Check setup**: expect `packageUnavailable`. Restore it with
   `pm enable com.ichi2.anki`.
4. **Inspection.** On a collection without VoiceQA, tap **Check setup**: it must
   name both missing pieces and write nothing.
5. **Disclosure declined.** Tap **Set up VoiceQA**, read the full-sync
   disclosure, and choose **Not now**. The collection must be identical.
6. **Fresh install.** Tap **Check setup**, **Set up VoiceQA**, then **Install
   VoiceQA**. Expect one note type, one `VoiceQA Demo` deck, four notes and four
   cards, all in that deck. Compare the stored note type against
   `fixtures/voiceqa/note-type.json` field by field.
7. **Repeat.** Tap **Check setup** again: expect reuse and skipped demo content,
   and an unchanged collection. Once setup is complete the app offers no second
   write; the second `provision` call itself is covered by `ProvisioningTest`.
8. **Conflict.** In AnkiDroid, **Manage note types → VoiceQA → Extra → Rename
   field → Notes**. Tap **Check setup**: expect a named conflict, no write path,
   and an identical collection. Rename the field back to **Extra**.
9. **Existing note type.** Generate fresh AV-002 fixtures and import the baseline
   through AnkiDroid's own UI:

   ```sh
   TZ=America/Chicago .venv/bin/python tools/voiceqa_fixtures.py build --output build/av039/fixtures
   adb -s emulator-5584 push build/av039/fixtures/baseline/collection.colpkg /sdcard/Download/av039-baseline.colpkg
   ```

   Use **More options → Import → Collection package (.colpkg) → Downloads →
   av039-baseline.colpkg → Replace**. Confirm **AV002 Baseline: 1 new / 2
   learning / 1 review**. Snapshot, run setup, snapshot again: the VoiceQA note
   type must be reused unchanged (compare its row's `config` bytes and
   `mtime_secs`), the four sample notes must reach `VoiceQA Demo`, and the eight
   AV-002 cards and eleven reviews must be identical.

A null cursor with no established cause is not manufactured here. AV-023 records
one from a real device state, and `ProvisioningTest` drives it through the
resolver seam along with a refused note insert, a mid-run failure and a
read-back mismatch.

## Evidence and cleanup

Save the environment capture, the AV-002 manifest, every UI dump with its PNG,
the collection snapshots and comparisons, the Gradle and Python logs, the JVM
test summary and an AnkiVoice crash check. Keep collection databases and APKs
under ignored `build/`; retain only the derived JSON. Run
`python3 tools/av039-qa/validate.py`, then record SHA-256 for every retained
file. Shut down only `emulator-5584` and preserve the AVD for review. No sync,
speech, grading, review submission, PR, merge or deployment is part of this run.
