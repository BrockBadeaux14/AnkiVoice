# AV-044 runbook: recognizer confidence on the segmented capture route

Issue [#67 — Carry recognizer confidence through the segmented capture route](https://github.com/BrockBadeaux14/AnkiVoice/issues/67).

Three layers, deliberately separate:

| Layer | Needs | What it establishes |
| --- | --- | --- |
| 1. Offline | nothing | The transport's segment rule against the fake platform, and the guards on what must not change |
| 2. Discovery | the pinned AVD and your voice | Whether the engine puts `CONFIDENCE_SCORES` in each `onSegmentResults` bundle, with every bundle logged |
| 3. Live check | the pinned AVD, your voice, a disposable collection | A spoken confirm and a spoken rating **executed**, and a mumbled command refused, through the AV-014 harness |

Nothing here turns a confidence score into a confirmation. The explicit confirm intent for
the current attempt and revision is still required; the score decides only whether a
guarded spoken command may run at all. No layer writes a review.

## 1. Offline: the segment rule, with no emulator

```sh
cd android
./gradlew --console=plain checkModuleBoundaries :speech:testDebugUnitTest :app:testDebugUnitTest
```

```sh
.venv/bin/python -m unittest tests.test_av044_confidence
.venv/bin/python tools/av044-qa/validate.py
```

`SpeechTransportTest` drives the shipped transport against `FakeSpeechPlatform`, which now
delivers a segmented session as the engine does: each segment with its own score, or
none, then the end of the session — or a fault instead. The cases the card names are
there by name: a segment score above zero classifies as `SUFFICIENT` and zero as `LOW`; a
segment without a score classifies as `ABSENT`; a fault after scored segments is still a
failure with no transcript; an empty segment contributes neither text nor a score; and the
capture carries the **minimum** over the segments that contributed text.

`tests/test_av044_confidence.py` fails if the bridge goes back to discarding the segment
bundle, if the minimum rule leaves the transport, if a partial result acquires a score, if
`:core`'s three-way classification or #15's guard rule change, or if the discovery
observer gains a way to decide anything. `tools/av044-qa/validate.py` checks every recorded
attempt for internal consistency: the transcript is the segments' text, the classification
follows from the scores, a failure carries no transcript.

These prove the rule, not the engine. Whether the engine supplies scores is layer 2.

## 2. Discovery: what the engine puts in a segment bundle

### The AVD

The pinned AVD is `AnkiVoice_AV005`. If it is missing from the host, recreate it exactly
as [AV-005's runbook](../av005/runbook.md#2-create-the-disposable-avd-if-it-does-not-exist)
does — same system image, same device profile, signed out of Google so the engine stays at
its pinned version:

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
echo no | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager" create avd \
  --name AnkiVoice_AV005 \
  --package 'system-images;android-36;google_apis_playstore;arm64-v8a' \
  --device medium_phone
```

Confirm the engine version after the first boot; the discovery records it per attempt:

```sh
adb -s emulator-5588 shell dumpsys package com.google.android.tts | grep versionName
```

It must be `googletts.google-speech-apk_20241125.02_p2.702443970`, the value in
`SpeechPins`. A Play-updated engine is a different engine, and an AVD signed into Google
will update it.

### Install

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5588 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5588 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5588 shell pm list instrumentation | grep ConfidenceInstrumentation
```

### Run

```sh
.venv/bin/python tools/av044-qa/discover.py --plan
.venv/bin/python tools/av044-qa/discover.py --evidence docs/testing/av044/evidence/discovery-<date>
```

The driver cold-boots the AVD with AV-042's flags (`-allow-host-audio -no-snapshot
-no-boot-anim`), turns the host microphone on over the console, grants `RECORD_AUDIO`,
sets guest media volume 9/15, and runs `ConfidenceInstrumentation` once per planned
phrase. For each attempt the emulator screen shows the phrase and any instruction. Tap
**Start answer**, wait for **Speak now**, say the phrase once, tap **Done**, tick only what
is true, tap **Save result**. Each screen waits at most five minutes, and a timeout is
never an attestation.

**Every attempt is appended to `attempts.jsonl`**, with every raw recognizer callback: the
bundle's keys, the segment text, whether `CONFIDENCE_SCORES` was present and what it
held, the transport's own result, the Done-to-final time, the operator's attestation and
a classification of the microphone PCM the capture actually received. A no-match, a wrong
transcript and an environment fault are all results. Do not re-run a phrase and keep only
the attempt that came back right.

The observer that logs the bundles is `AndroidSpeechPlatform.recognizerObserver`, null in
production and inert by construction: it is told what arrived and the transport never
sees it. The capture itself goes through the shipped transport unchanged.

### Two emulator faults you will meet

**The coreaudio listener leak.** AV-017 diagnosed it: the emulator's macOS audio backend
leaks a property listener on every microphone open and, after two or three opens per
boot, either substitutes a 220 Hz tone or **exits**. On the AV-044 host it exited, taking
the harness with it, so the driver cold-boots proactively after every two captures
(`--per-boot`) and again whenever the emulator's own log shows
`Failed to create voice`. An attempt whose PCM reads as the tone, as zeros, or as nothing
is recorded as an environment fault and the phrase is queued once more on a fresh boot.

**Lost writes on `emu kill`.** On this host `adb emu kill` returned before the guest had
flushed its last writes: an APK installed a minute before the stop was gone on the next
boot, a pushed collection was back to empty, and the engine's freshly written voice data
was corrupted, after which every `synthesizeToFile` returned `ERROR_SERVICE` (`-4`,
`Failed to load pipeline definitions!`) across cold boots and `pm clear`. Recognition was
unaffected, which is why the recorded discovery ran with `--prompt ''`, capture only. The
driver now runs `adb shell sync` before every stop. If the engine is already refusing
synthesis, boot once with `-wipe-data` and reinstall; that restored it at once.

### Read it

```sh
.venv/bin/python tools/av044-qa/validate.py --evidence docs/testing/av044/evidence/discovery-<date> --table
```

The table is what [results.md](results.md) records, one row per attempt. Keep the
`emulator-boot-NN.log` files and `environment.json` beside the ledger; the raw PCM stays
on the device and is not committed.

## 3. Live check: the AV-014 harness, by voice

Only if the transport change shipped, which layer 2 decided. It needs AnkiDroid 2.24.1 with
database access granted and a **disposable** AV-002 collection, exactly as
[AV-014's runbook](../av014/runbook.md#2-unattended-every-command-by-touch-on-the-pinned-avd)
prepares them; nothing here writes a review, and the harness re-reads the card afterwards
to prove it.

```sh
cd android
./gradlew :app:installDebug :app:installDebugAndroidTest
adb -s emulator-5588 shell pm grant org.ankivoice android.permission.RECORD_AUDIO
adb -s emulator-5588 shell pm grant org.ankivoice com.ichi2.anki.permission.READ_WRITE_DATABASE
adb -s emulator-5588 shell sync
adb -s emulator-5588 shell am instrument -w -e confirm AV014_LIVE_COMMANDS -e listDecks true org.ankivoice.test/org.ankivoice.app.CommandInstrumentation
```

Two captures per boot, then `adb shell sync`, `adb emu kill` and a cold boot with the
same flags and `emu avd hostmicon`. The sweep offers commands in **vocabulary order**
(`skip` before the ratings, `confirm` last), and the first screen is AV-014's context-rule
step, which **Skip this one** leaves out without opening the microphone:

```sh
adb -s emulator-5588 shell am instrument -w -e confirm AV014_LIVE_COMMANDS -e deck AV002_DECK_ID -e interactive true -e voice confirm,rate-good org.ankivoice.test/org.ankivoice.app.CommandInstrumentation
# sync, cold boot, then:
adb -s emulator-5588 shell am instrument -w -e confirm AV014_LIVE_COMMANDS -e deck AV002_DECK_ID -e interactive true -e voice skip,rate-good org.ankivoice.test/org.ankivoice.app.CommandInstrumentation
```

Say `confirm` clearly in the confirmation context, say `good` clearly for `rate-good`,
and **mumble** `skip`. Each voice-sweep entry records `recognizerConfidence`, the raw
score behind the classification the router saw. Read `files/av014-result.json` as AV-014's
runbook says and copy it out **before the next run overwrites it**; `passed` is true only
when `writes` is empty and the card's state is unchanged. Check the boot's emulator log
for `Failed to create voice` before reading a no-match as a recognition result.

Record each outcome as it happened. A spoken confirm that executes is a confirmation the
learner gave; it is still not a write. A mumbled command that comes back as a different
phrase, as no-match, or as the command below `SUFFICIENT` is refused for that reason, and
the reason is the result.

## What a passing run does and does not establish

Layer 2 establishes what the pinned engine puts in a segment bundle on this route and
build, on the recreated pinned AVD, for the phrases spoken. Layer 3 establishes that the
score, carried through, lets one clearly spoken confirm and one rating execute. Neither is
a reliability estimate; the 30-turn run stays in
[#29](https://github.com/BrockBadeaux14/AnkiVoice/issues/29). `:core` still classifies any
positive score as `SUFFICIENT`; whether a threshold belongs there is #15's and #21's
question, and the recorded scores are the evidence for it. Emulator results say nothing
about physical devices, Bluetooth, real calls, backgrounding or screen lock.
