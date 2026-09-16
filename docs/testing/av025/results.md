# AV-025: The speech transport is implemented; its live check is not yet run

Issue [#26 — Integrate mobile speech and audio routing](https://github.com/BrockBadeaux14/AnkiVoice/issues/26).
Branch `codex/av-025-mobile-speech-routing`.

**Result: the transport is implemented and its rules are verified offline. The live
verification on the pinned AVD has not been performed.** That run needs a person speaking
into a running emulator, so it cannot be produced from this session. Until it exists, this
card has no evidence that the production transport transcribes real speech — only that it
orders, bounds and classifies the route correctly.

AV-042 proved the *route* live through a disposable probe. This card moves that route into
`:speech`. Those are not the same artifact, and the probe's evidence does not transfer to
this code.

## What was implemented

`:speech` was a marker object. It now holds AV-025's transport behind AV-007's
`SpeechOutput` and `SpeechInput`, with the split the issue requires: `AndroidSpeechPlatform`
is the only class that touches `TextToSpeech`, `SpeechRecognizer` and `AudioRecord`, and
`SpeechTransport` owns every decision. `SpeechModule.create` returns one transport per
session, so #13 holds a single object implementing both contracts.

The pinned values from the [AV-042 handoff](../av042/results.md#implementation-handoff)
live in `SpeechPins` and `SpeechTimings`: the engine, local voice, recognition service,
`EXTRA_PREFER_OFFLINE=false`, 16 kHz mono PCM16 through `EXTRA_AUDIO_SOURCE`, the 400 ms
settle, 500 ms trailing silence and 5,000 ms finalization deadline. `RECORD_AUDIO` is the
only permission; no provider, credential or phone-state permission was added.

Ordering is as specified. Playback completes, the settle opens, and capture opens only
when #13 calls `listen` — that call is the explicit Start answer. Capture never opens
during playback, at most one capture is active, and nothing re-arms inside a turn.
`finishAnswer` is Done: it stops the microphone, lets the pump append the trailing silence
and close the pipe, then starts the finalization deadline.

The transport receives an `Utterance` and never a `ScheduledCard`, so Extra has no path
into question audio through this module.

## What was verified, and how

```
./gradlew checkModuleBoundaries :core:test :speech:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
```

- **52 `:speech` unit tests, 0 failures**, driving `SpeechTransport` against
  `FakeSpeechPlatform` with no emulator and no network. They cover playback ordering and
  the settle interval, capture rejected during playback, overlapping capture and playback
  rejection, Done with trailing silence and pipe closure, both deadlines, explicit Cancel
  during playback and during capture, release, stale and duplicate callbacks, partial text
  that never settles a capture, capture-device and route loss, and every recognizer error
  class. `SpeechReadinessTest` covers the capability preflight.
- **`checkModuleBoundaries` passes.** `:speech` still depends only on `:core`, declares no
  `INTERNET` permission and no `READ_PHONE_STATE`.
- **351 unit tests across all five modules, 0 failures**: `:core` 124, `:provider` 63,
  `:ankidroid` 61, `:speech` 52 and `:app` 51.
- `:app:assembleRelease`, `:app:assembleDebugAndroidTest` and `:app:lintDebug` all pass, so
  the release build and both instrumentation harnesses compile.
- CI now runs `:speech:testDebugUnitTest` and compiles `:app:assembleDebugAndroidTest`, so
  the AV-024 and AV-025 live harnesses cannot silently stop compiling.

Two early failures were defects in the new tests, which raced the transport's own threads;
they were fixed by waiting for observable state rather than by weakening an assertion.

A third failure was a **real defect in the transport**, found by the capture-loss test. A
Done arriving in the window between the microphone opening and the transport recording the
stream was dropped: the microphone kept running and the turn died on the finalization
deadline instead of returning the learner's answer. `listen` now stops a microphone that
opened into an already-finalizing turn, and a regression test holds that window open.

## What was **not** verified

- **The live run on the pinned AVD has not happened.** No turn of real speech has gone
  through this code. `doneToFinalMs` is unmeasured for the production transport; AV-042's
  690 ms and 687 ms belong to the probe.
- **The permission-denied and explicit-Cancel live paths** are covered offline only.
- **Device and route loss is classified but not exercised on hardware.** Unplugging a
  headset mid-answer, and the platform silencing the capture client, are wired to
  `AudioRecord`'s routing listener and `AudioRecordingCallback` and reported as
  `EARLY_CLOSURE` with a distinct detail. Only the transport's half of that is tested; the
  Android callbacks themselves have not been triggered on a device.
- **The playback path differs from the probe in one respect worth stating**:
  `AndroidSpeechPlatform` synthesizes to a file and plays it through `MediaPlayer`, which
  is what AV-042 measured as audible, but that has not been re-confirmed from this module.
  The engine, voice and rate are the pinned ones.
- **Recognition quality is not simulated and cannot be.** Every offline test uses scripted
  results. A green suite says the transport handles a transcript correctly, never that a
  transcript is correct.
- **Nothing about interruption safety changed.** AV-040 recorded that no interruption
  signal reaches the app while the recognizer holds the microphone. Backgrounding, screen
  lock, audio-route changes, Bluetooth and real calls stay out of scope here and remain
  with [#32](https://github.com/BrockBadeaux14/AnkiVoice/issues/32). No safe-call-handling
  claim is authorized.
- **Emulator results establish nothing about physical devices.**

## Reproduce

[The runbook](runbook.md) separates the two layers: the offline suite runs anywhere, and
the live section gives the exact `am instrument` commands, the AVD and host-audio
prerequisites, and the rule that every attempted turn is recorded — including the ones
that come back wrong.

## Next

The live section of the runbook is the remaining work on this card. Run it on the pinned
AVD with the two AV-042 phrases plus the Cancel and permission-denied paths, record each
turn and its Done-to-final duration here, and only then is the card's verification bar
met.
