# AV-024 adapter verification

Issue [#25](https://github.com/BrockBadeaux14/AnkiVoice/issues/25). The production
adapter uses the same `AndroidAccessPlatform`, serial worker and main delivery
executor as onboarding/provisioning. The instrumentation APK is separate from
the app UI. Never run this harness on a personal collection.

## Build and host

Use AV-022's macOS ARM64 baseline, Android Studio JBR, SDK 36/build-tools 36.0.0,
and AnkiDroid 2.24.1 ARM64 (SHA-256 in `evidence/environment.json`). From the root:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
android/gradlew -p android checkModuleBoundaries :core:test \
  :ankidroid:testDebugUnitTest :app:testDebugUnitTest assembleDebug \
  :app:assembleRelease :app:lintDebug :app:assembleDebugAndroidTest
.venv/bin/python -m unittest discover -s tests
.venv/bin/python -m pip check
.venv/bin/python tools/av006-probe/validate_evidence.py
```

Create a separate `AnkiVoice_AV024` AVD with device `medium_phone` and image
`system-images;android-36;google_apis_playstore;arm64-v8a` revision 7. Start on port
5584 with `-no-window -no-audio -no-boot-anim -no-snapshot -timezone America/Chicago`.
Do not wipe/reuse another AVD. Install AnkiDroid, choose Get Started, grant All
files access, and leave Google and AnkiWeb signed out. Install the app's debug
APK and `app-debug-androidTest.apk`, then grant the app's
`com.ichi2.anki.permission.READ_WRITE_DATABASE` permission.

## Fresh fixtures and isolation

```sh
TZ=America/Chicago .venv/bin/python tools/voiceqa_fixtures.py build --output build/av024/fixtures
TZ=America/Chicago .venv/bin/python tools/av024-qa/fixtures.py
```

The second command copies the generated baseline and suspends every card except
one target in each host copy. The target's identity, content, six stored fields
and seeded history are preserved. New/learning/relearning/mature each get a
package. This is fixture preparation, never a product scheduling operation.
Deleted-card/deleted-deck packages remove the baseline target note/deck using
the Anki backend in fresh host copies. Their original IDs remain in the baseline
manifest, so reads can request the actual deleted IDs.

Push each package to Downloads as `av024-NAME.colpkg`. Also push the unmodified
limits package as `av024-limits.colpkg`. On the initial import, select Downloads
in the system picker. The helper reads a fresh UI tree before every tap and
refuses any AVD other than `AnkiVoice_AV024`.

```sh
python3 tools/av024-qa/run.py matrix
python3 tools/av024-qa/negative.py
```

Every case imports its pristine package through AnkiDroid's native UI, waits for
the deck screen, snapshots the database, runs instrumentation, and snapshots
again. It checks exactly one added revlog row with the intended card/rating,
no replay, and a consistent adapter outcome. Ratings 0/5 use `invalid` mode and
must add no reviews. The cap case passes 98,765 ms and must store 60,000 ms.

`database()` force-stops AnkiDroid and copies the database/WAL to ignored
`build/av024/databases/`. SQLite reads only the copy; synthetic JSON is retained
under `docs/testing/av024/evidence/`. Do not insert a database snapshot between
offer and commit except in the deliberate queue-rebuild case.

The instrumentation entry point is:

```sh
adb -s emulator-5584 shell am instrument -w \
  -e confirm AV024_SYNTHETIC_ONLY -e deck DECK_ID -e card CARD_ID \
  -e mode commit -e rating 3 -e elapsed 12345 \
  org.ankivoice.test/org.ankivoice.app.ReviewInstrumentation
```

Resolve IDs from the freshly generated manifests. Modes `snapshot`, `read`,
`async`, `invalid`, and `stale` cover the additional checks. Async verifies all
three results retain their operation token and arrive on Android's main thread.
The stale mode first commits one explicit setup review on the limits collection,
then holds the next offered card and writes `files/av024-held.json`. The host
snapshots (force-stops) AnkiDroid to rebuild its queue, then creates
`files/av024-resume` through `run-as`. The held intent must fail `staleIdentity`
with zero dispatches. The setup review is reported separately.

For API-off, use AnkiDroid's drawer → Settings → Advanced → Enable AnkiDroid API
and restore the setting afterwards. Android rejects the shell component-state
command on this image; the harness uses native UI-tree-derived taps.
Revoke the app's database permission for the revoked-access case and restore it
afterwards. Each negative case uses a fresh reset and unchanged before/after
revlog assertions. No negative test bypasses the production writer guards.

## Timing and retained evidence

The provider sets `timerStarted = now - time_taken` before calling the scheduler.
The scheduler may save a few additional elapsed milliseconds. Keep the submitted
time unchanged. Uncapped offline time must lie between the submitted value and
that value plus the measured dispatch duration; cap both bounds by `maxTaken`.
The cap test's bounds collapse to exactly 60,000 ms. `expectedStoredTimeMs` remains
the contract's nominal `min(submitted, cap)`; its exact-equality helper can return
false for real uncapped timer overhead. This is not used to confirm a review.

The evidence validator checks retained files without launching a device:

```sh
python3 tools/av024-qa/validate.py
```

Preserve failed attempts. The initial smoke ran before import completed and
returned `deckMissing`; the timing-discovery run found a 1 ms timer increment.
These are retained separately from the completed matrix. Reruns need a new
output directory or archived prior evidence; the harness refuses existing
labels and database directories.

## Release shell smoke

Sign a copy of `app-release-unsigned.apk` with the local Android debug key into
ignored `build/av024/app-release-qa.apk` and install it on the same synthetic AVD.
No release key or distribution is involved. Grant the database and microphone
permissions, launch AnkiVoice, explicitly select AV002 Baseline, scroll to Session
and tap Start. Capture Card ready and compare database snapshots: no review write.
A separate instrumentation Easy review on the isolated new card prepares an empty
queue. Relaunch and tap Start; capture Queue exhausted and again verify unchanged
card state/history. Instrumentation output must be read from `am instrument` on
a non-debuggable release, since `run-as` cannot read its private files.
