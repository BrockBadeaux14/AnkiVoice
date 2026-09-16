# AV-025 runbook: verify the speech transport

Issue [#26 — Integrate mobile speech and audio routing](https://github.com/BrockBadeaux14/AnkiVoice/issues/26).

Two layers, deliberately separate. The offline layer proves the transport's rules and
runs anywhere. The live layer proves the pinned route still transcribes real speech and
can only be produced by a person speaking into the pinned AVD.

Nothing here reads a collection, grades an answer or writes a review. A live run cannot
alter a card.

## 1. Offline: transport rules, no emulator

```sh
cd android
./gradlew --console=plain checkModuleBoundaries :speech:testDebugUnitTest
```

These drive `SpeechTransport` against `FakeSpeechPlatform` and cover playback ordering,
the settle interval, capture serialization, Done with trailing silence, cancellation,
both deadlines, stale and duplicate callbacks, and every recognizer error class. They
simulate the **shape** of the route, never recognition quality: no offline test is
evidence that speech is transcribed correctly.

## 2. Live: the pinned AVD

Prerequisites, all from AV-042's accepted configuration:

- The pinned AVD, restarted after host audio setup. A stale boot is the condition that
  made the goldfish HAL substitute a 220 Hz tone for the microphone in AV-040/AV-042.
- Host microphone forwarding enabled, host output near 60% and guest media volume 9/15.
  The prompt was inaudible below that in AV-042 attempts 3 and 4.
- `com.google.android.tts` present at the pinned version with the `en-US-language` local
  voice, and `RECORD_AUDIO` granted.

Install and run one turn:

```sh
cd android
./gradlew :app:installDebug :app:installDebugAndroidTest
adb -s emulator-5584 shell am instrument -w \
  -e confirm AV025_LIVE_SPEECH \
  -e prompt "Name the three colors." -e expect "green blue red" -e speakMs 6000 \
  org.ankivoice.test/org.ankivoice.app.SpeechInstrumentation
```

The harness plays the prompt through the pinned voice, settles, opens capture on its own
explicit Start answer, waits `speakMs` for you to speak, then sends Done and records the
returned event with its Done-to-final duration.

**Speak only after the prompt finishes.** Capture does not open during playback, so
anything said over the prompt is not recorded.

Run the same command with `-e prompt "What is two plus three?" -e expect "five"` for the
second AV-042 phrase, then the two control paths:

```sh
# Explicit Cancel mid-capture: expect a failure, no transcript.
adb -s emulator-5584 shell am instrument -w -e confirm AV025_LIVE_SPEECH \
  -e mode cancel org.ankivoice.test/org.ankivoice.app.SpeechInstrumentation

# Permission denied: revoke first, expect SpeechInput.permissionDenied.
adb -s emulator-5584 shell pm revoke org.ankivoice android.permission.RECORD_AUDIO
adb -s emulator-5584 shell am instrument -w -e confirm AV025_LIVE_SPEECH \
  -e mode permission org.ankivoice.test/org.ankivoice.app.SpeechInstrumentation
adb -s emulator-5584 shell pm grant org.ankivoice android.permission.RECORD_AUDIO
```

## Recording the result

For each turn record the requested phrase, the returned `capture` object verbatim, and
`doneToFinalMs`. Record what actually happened, including a wrong transcript or a
no-match: AV-042's ledger keeps every failed attempt, and this one does the same.

An expected phrase that comes back wrong is a result, not a retry cue. Do not re-run a
turn and report only the run that passed.

Confirm separately, in your own words, that the prompt was audible and that you spoke
the expected phrase. The harness records what the recognizer returned; it cannot attest
to what you said, and it never does so on your behalf.

## What a passing live run does and does not establish

Two confirmed phrases establish that the pinned route works end to end through the
production transport. They are not a reliability estimate, and they say nothing about
physical devices, Bluetooth, real phone calls, backgrounding or screen lock — all
deferred to [#32](https://github.com/BrockBadeaux14/AnkiVoice/issues/32).
