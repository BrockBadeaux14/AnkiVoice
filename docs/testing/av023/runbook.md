# AV-023 shell verification

Issue [#24](https://github.com/BrockBadeaux14/AnkiVoice/issues/24).
See [results](results.md) for the actual run and its limits.

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
```

The Android workflow runs both Gradle commands. The evidence validator checks the
retained run; it does not operate an emulator. GitHub Actions itself runs after a
future push; this task validates its commands locally.

## Dedicated emulator

Use the [AV-022 baseline](../../decisions/0022-android-implementation.md): macOS
26.6.2 ARM64, image `system-images;android-36;google_apis_playstore;arm64-v8a`
revision 7, medium_phone at 1080×2400 / 420 dpi, emulator 37.1.11.0 and AnkiDroid
2.24.1 ARM64. Do not reuse or wipe a personal AVD. This run created a new one:

```sh
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
  --name AnkiVoice_AV023 \
  --package 'system-images;android-36;google_apis_playstore;arm64-v8a' \
  --device medium_phone
"$ANDROID_HOME/emulator/emulator" -avd AnkiVoice_AV023 -port 5582 \
  -no-window -no-audio -no-boot-anim -no-snapshot -timezone America/Chicago
```

Wait for `adb -s emulator-5582 shell getprop sys.boot_completed` to report `1`.
Keep it signed out of Google and AnkiWeb. `tools/av023-qa/ui.py` refuses any other
AVD name and captures fresh UI trees before locating a tap. It refuses to overwrite
an existing capture. Use distinct labels for a new run. `capture LABEL` saves XML
and PNG under this directory's `evidence/`; `show` lists labels and bounds, and
`tap 'Exact label'` taps the matching element. If an element is offscreen, scroll
and inspect again. A transient empty UI dump should be retried after the screen
has appeared; do not infer state from it.

## Onboarding matrix

1. Install `android/app/build/outputs/apk/debug/app-debug.apk` and launch
   `org.ankivoice/org.ankivoice.app.MainActivity` before installing AnkiDroid.
   Expect `packageUnavailable`, installation guidance and disabled Start.
2. Install the pinned AnkiDroid APK. Verify SHA-256
   `3012692ca67b856b287430715f99ca6150e471588326f6c8c45f27bbe895afbf` first.
   Return to AnkiVoice. Expect `accessDenied`.
3. Tap **Allow AnkiDroid access**. Capture the Android dialog. Choose **Don't
   allow** (the device uses a curly apostrophe), verify denial, request again,
   then choose **Allow**. Before AnkiDroid's first-run setup is complete, this
   image returns unusable provider information: the shell reports `nullCursor`.
4. Open AnkiDroid. Choose **Get Started**, grant **All files access**, and confirm
   its collection is empty. Generate fresh AV-002 fixtures:

   ```sh
   TZ=America/Chicago .venv/bin/python tools/voiceqa_fixtures.py build --output build/av023/fixtures
   adb -s emulator-5582 push build/av023/fixtures/baseline/collection.colpkg /sdcard/Download/av023-baseline.colpkg
   ```

   Use native **More options → Import → Collection package (.colpkg) → Downloads
   → av023-baseline.colpkg → Replace**. Only replace the new synthetic collection.
   Confirm **AV002 Baseline: 1 new / 2 learning / 1 review**. This is the AV-004
   import procedure, using the new AVD and fresh output path.
5. Return to AnkiVoice. Expect `permissionDenied`. Tap **Allow microphone** and
   deny the native dialog, verify the explanation, then request again and allow
   **While using the app**. The deck list must appear.
6. Put AnkiVoice in the background using Home. Revoke each permission separately:

   ```sh
   adb -s emulator-5582 shell pm revoke org.ankivoice android.permission.RECORD_AUDIO
   adb -s emulator-5582 shell pm revoke org.ankivoice com.ichi2.anki.permission.READ_WRITE_DATABASE
   ```

   Return and verify the corresponding failure; restore each through its native
   runtime request before moving on. Android may kill the process on revocation;
   the saved deck/language still persist and study never restarts automatically.
7. Open AnkiDroid's **Settings → Advanced**, scroll to **Enable AnkiDroid API**,
   and switch it off. Return to AnkiVoice: expect `apiDisabled`. Use its **Open
   AnkiDroid** action and the same native setting to restore the API. Return:
   expect successful access. Do not conflate a disabled component with an
   unexplained null cursor.
8. With AnkiVoice backgrounded, disable the synthetic package:

   ```sh
   adb -s emulator-5582 shell pm disable-user --user 0 com.ichi2.anki
   ```

   Return: expect `packageUnavailable`. Its corrective action opens AnkiDroid's
   app-info screen. This image does not offer **Enable** for this sideloaded,
   shell-disabled app; restore it as the AV-004 runbook does:

   ```sh
   adb -s emulator-5582 shell pm enable com.ichi2.anki
   ```

   Press Back from app info and verify successful access. Do not uninstall the
   fixture collection to work around this test-only package state.

## Deck, session and settings matrix

> Since AV-026 the session panel's **Start**, **Stop** and **Resume** buttons belong to the
> study screen. The read-only readiness check below is the setup screen's **Check deck**
> button, and its status lines read *Deck check: …*. The steps record the surface as it was
> when this evidence was taken.

- Before selecting a deck, capture the notice that selection changes AnkiDroid's
  current deck without submitting a review. Choose **AV002 Baseline**. The adapter
  updates only `selected_deck`, checks the deck still exists, and reads back the
  selected ID. Capture the selected label and private `shell.xml` using `run-as`
  on the debug build.
- Tap **Start**: expect **Card ready** from AV-041's in-memory `FakeCardProvider`.
  This does not read the real card queue. Tap **Stop** and capture **Stopped**.
- Start, press Home, return: expect **Paused** and require explicit **Resume**.
  Stop, press Home, return: expect **Stopped**.
- Start and rotate with `settings put system accelerometer_rotation 0`, then
  `settings put system user_rotation 1`. Scroll to the session panel in landscape:
  it stays **Card ready**. Restore `user_rotation 0`. Configuration changes must
  not emit pause/stop to the foreground port.
- Create an empty **AV023-Temporary** deck through AnkiDroid's **+ → Create deck**.
  Select it in AnkiVoice. In AnkiDroid, long-press that empty deck and delete it.
  Return to AnkiVoice: expect `deckMissing`, disabled Start and the remaining deck
  choices. Select **AV002 Baseline** to recover. Do not delete the baseline deck.
- Change the language to `en-GB`, save, force-stop/relaunch AnkiVoice and confirm
  it persisted with the selected deck. Restore `en-US` and save.
- Sign the unsigned release APK with the local disposable Android debug key solely
  for emulator installation; install over the debug build. Onboarding and deck
  selection remain available. Start reports **Study unavailable**. Inspect release
  DEX files to confirm there are no `org/ankivoice/core/fakes` classes. Reinstall the
  debug APK afterwards. Do not publish either APK.

Queue exhaustion, fake-provider failure, selection races, and late callbacks after
Stop are tested with controlled JVM fakes. This run does not manufacture a live
AnkiDroid empty queue or add hidden UI controls for those cases.

## Evidence and cleanup

Use the AV-004 offline snapshot method before/after the shell run: force-stop only
AnkiDroid, pull `collection.anki2` and any WAL into separate ignored directories,
open copies read-only, verify all notes carry `av002`, and compare exact `cards`
and `revlog` rows. The app does not use this filesystem access. Keep DBs and APKs
under ignored `build/`; retain only synthetic row snapshots and verification JSON.

Save environment versions, fixture manifest, UI dumps/PNGs, test summaries, merged
manifests, APK hashes and an app crash check. Restore the API, permissions, baseline
deck, `en-US`, and portrait orientation. Stop the session. Shut down only
`emulator-5582`; preserve the AVD for review. No sync, real speech, grading, review
submission, PR, merge or deployment is part of this run.
