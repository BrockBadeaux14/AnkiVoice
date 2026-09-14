# AV-005: Foreground speech on an Android emulator

- Issue: [#5 — Investigate foreground speech on an Android emulator](https://github.com/BrockBadeaux14/AnkiVoice/issues/5).
- Result: **harness delivered and mechanically verified; the measurement run is
  pending an operator.** This document does not yet claim go, constrained go or
  no-go. See [Exit decision](#exit-decision).
- Status: ready for review; this report does not accept the issue or advance
  dependent work.
- Investigated September 14, 2026. Dependency #1 is closed; the issue had no
  discussion comments.

The deliverable is an operator-driven test suite that runs from Android Studio,
plus the evidence format, validator and runbook around it. The suite speaks a
VoiceQA prompt through Android text to speech, waits a recorded settling
interval, opens the **live microphone**, and requires a person to answer and then
attest what they did. It never reads or writes an Anki collection and never
produces a rating.

**A person is required by construction.** #5 states that injected or synthetic
input alone does not prove the live demo path, so the suite has no unattended
mode and no synthesised stand-in for the learner's voice. Every turn is gated on
a tap, and a transcript is only counted as a spoken turn when the operator
attested to speaking it. Follow the [runbook](av005/runbook.md) to produce the
measurements.

## Pinned environment

| Component | Tested value |
| --- | --- |
| Host | macOS 26.6.2 (25G83), ARM64 |
| Disposable AVD | `AnkiVoice_AV005`, `medium_phone`, 1080×2400 at 420 dpi |
| Android image | API 36 / Android 16, `google_apis_playstore;arm64-v8a` |
| Android build | `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:user/release-keys` |
| Emulator / adb | 37.1.11.0 (15917651) / 37.0.1 (15733141) |
| Text to speech | `com.google.android.tts`, `googletts.google-speech-apk_20241125.02_p2.702443970`, voice `en-US-language`, local, 9 local en-US voices |
| Recognition services | `com.google.android.as/…AiAiSpeechRecognitionService` and `com.google.android.tts/…GoogleTTSRecognitionService` |
| Default recognizer | `com.google.android.tts/…GoogleTTSRecognitionService` (`settings get secure voice_recognition_service`) |
| Locale | en-US, matching the AV-002 fixtures |
| Probe build | Android Gradle plugin 9.1.0, Gradle 9.3.1, compile SDK 36, target SDK 35, JBR 25.0.2 |
| Probe package | `org.ankivoice.av005`, debug build, `RECORD_AUDIO` and `INTERNET` only |

The AVD was created for this spike, used no Anki collection, and stayed signed
out of Google. `SpeechRecognizer.isRecognitionAvailable` and
`isOnDeviceRecognitionAvailable` both reported `true`. Exact versions, hashes and
the audio note are pinned in
[environment.json](av005/evidence/environment.json).

## What the suite covers

Fifteen scenarios, run in order from a dropdown. Scenario 2 is the twelve-turn
loop the issue asks for: the four synthetic VoiceQA prompts for three rounds,
using **only the `Prompt` field** for question audio. The turn corpus is derived
from [the merged AV-002 fixtures](../../fixtures/voiceqa/note-type.json) at build
time rather than restated, so the two cannot drift; the validator re-checks every
recorded prompt against the fixture.

| Criterion from #5 | Covered by |
| --- | --- |
| Environment, audio setup, engine/recognizer versions, locale, reproduction | Scenario 1 plus the pinned table above and the [runbook](av005/runbook.md) |
| Twelve-turn loop with per-turn timings and transcripts | Scenario 2 |
| Repeat, cancel, 2 s and 5 s thinking pauses, no-speech timeout, endpoint behaviour | Scenarios 3–5, 7, 8 |
| Prompt echo must not be taken as the answer | Scenario 6, plus an `echo_suspected` check on every turn in every scenario |
| Microphone deny/revoke, recognizer unavailable/busy, network loss, cancellation with a late callback | Scenarios 9–12 |
| Background and lock during playback and capture, explicit resume, stale callbacks | Scenarios 13 and 14 |
| Audio focus and route interruption | Scenario 15 |

Capture never opens before playback has finished plus a recorded settling
interval, except in scenario 6 where opening early is the experiment. Errors are
kept strictly distinct from transcripts: an error path leaves `transcript` null,
and the validator fails if an error ever carries one.

## Measured so far

These are mechanical verification results from driving the suite over adb with
no one speaking. They establish that the harness works; they are **not** the
twelve-turn measurement the issue asks for.

<!-- av005:results:begin -->

Measured over 2 recorded turns, 0 of which returned a transcript.

| Scenario | Turns | Transcripts | Median capture | Median finalisation after end of speech | Errors seen | Stale callbacks |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| 5. No-speech timeout | 2 | 0 | 1244 ms | — | ERROR_NO_MATCH | 0 |

Self-inflicted audio-focus losses during the app's own prompt playback: 2 recorded, 1045–1211 ms.

<!-- av005:results:end -->

Raw evidence: [mechanical-silence-run.json](av005/evidence/mechanical-silence-run.json).

What this already establishes on this image:

- **Text to speech works locally.** Prompts synthesised and played through the
  `en-US-language` voice with no network dependency; measured playback was 2,743
  ms and 7,406 ms for two fixture prompts.
- **The settling interval is honoured.** Requested 400 ms, measured 404 ms and
  405 ms between `onDone` and `startListening`.
- **The live microphone path is real.** With the emulator's host-microphone
  forwarding enabled, capture opened (`onReadyForSpeech` at 3,421 ms and 43,161
  ms into the runs) and the recognizer reported non-zero input level
  (`rms_peak` 10). With forwarding **off**, the same scenario ends in
  `ERROR_NO_MATCH` within about a second with no input level at all — so the
  forwarding toggle is load-bearing and is the first thing to check.
- **Silence ends the turn in about 1.2 seconds.** Both silent turns returned
  `ERROR_NO_MATCH` after 1,156 ms and 1,331 ms of capture — not
  `ERROR_SPEECH_TIMEOUT`, and far sooner than the requested 1,200 ms complete
  silence and 1,000 ms minimum length. Scenarios 3 and 4 exist to quantify this
  against a real learner pause; on this evidence a learner who thinks for two
  seconds will lose the turn.
- **No stale callback advanced a turn**, and no transcript was ever the prompt
  echoed back.

## Findings that already constrain #13, #23 and #26

**Audio focus cannot be used as an interruption signal.** Both halves of a turn
take audio focus for themselves: the Google text-to-speech engine takes it per
utterance, and `SpeechRecognizer` takes it the moment capture opens. An app that
also holds its own focus request therefore receives a transient loss caused by
its own work. The observed blip is unbounded — 34 ms, 1,045 ms, 1,060 ms, 1,121
ms and 1,211 ms across runs — so it cannot be filtered by a timing window. An
earlier revision of this probe cancelled turns on that signal and the loop
cancelled itself. The suite now records focus events and never acts on them;
interruptions are detected through the activity lifecycle (`onPause`, which a
call or the lock screen triggers) and through the recognizer's own errors.
**#13 and #26 must not treat audio-focus loss as a learner interruption.**

**The recognizer's silence settings are requests, not guarantees.**
`EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`,
`…POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS` and
`…MINIMUM_LENGTH_MILLIS` were all set, and the measured end-of-turn behaviour did
not match them. This report does not claim these settings take effect. Proposed
starting values for #13 are in [Settings to carry forward](#settings-to-carry-forward),
and they are proposals to be measured, not established behaviour.

**Changing the host audio device while the emulator runs kills the emulator.**
Switching the macOS default input mid-run produced
`coreaudio: Could not initialize record`, `kAudioHardwareIllegalOperationError`
and `Failed to create voice 'virtio-snd-mic0'`, after which the emulator exited.
See [emulator-audio-crash.log](av005/evidence/emulator-audio-crash.log). Choose
the host input before launching, and treat this as an environment constraint for
anyone reproducing the run, not as a property of Android.

**The on-device recognition model was not available.** An early attempt returned
`ERROR_LANGUAGE_UNAVAILABLE` (13), matching what #6 recorded independently on the
same image. Recognition subsequently worked with `EXTRA_PREFER_OFFLINE` false,
which the suite sets. Offline speech is out of scope here and belongs to #33.

## Settings to carry forward

Proposals for #13, to be confirmed by the operator run. They are starting points
derived from the measured behaviour above, not verified settings.

| Setting | Proposed | Why |
| --- | --- | --- |
| Settling interval after playback | 400 ms | Measured as honoured to within 5 ms, and enough to keep the prompt out of the capture window. |
| Complete silence length | 1,500 ms | The observed ~1.2 s end-of-turn is too aggressive for a learner mid-answer; request more and measure what is actually granted. |
| Possibly-complete silence length | 1,000 ms | Kept below the complete value so a pause inside an answer does not finalise it. |
| Minimum response length | 2,000 ms | Must exceed the thinking pause the learner is allowed before speaking. |
| Maximum response | Enforced by the app | The recognizer did not honour the length hints; #13 should own its own ceiling with a monotonic clock, as #4 concluded for review timing. |
| Interruption signal | `onPause` and recognizer errors | Audio focus is unusable, per the finding above. |

## Limits of this evidence

- **The twelve-turn loop has not been run.** No transcript of human speech has
  been recorded, so nothing here establishes recognition accuracy, real
  endpointing against a speaking learner, or the repeat/cancel, permission,
  network, background, lock and focus scenarios in practice.
- Emulator evidence does not establish physical-device behaviour. Physical
  speaker and microphone characteristics, Bluetooth routing and real phone-call
  interruption are **unverified**; scenario 15 uses an emulated call or another
  app's playback, which is not the same thing. These are evidence limits, not
  prerequisites for this spike.
- One AVD and one system image were used, as the issue bounds. The alternative
  recognizer allowance was not needed.
- The default recognition service on this image is provided by the text-to-speech
  package. A different image, or a device where `com.google.android.as` is the
  default, may behave differently.
- No provider selection is implied. Provider choice and cost belong to #6, and
  the application framework to #23; a disposable probe does not decide either.

## Exit decision

**Deferred.** The issue's exit criterion is a report with measured timing and
success counts from the fixed matrix. The matrix cannot be run without a person
speaking, and it has not been run. Calling this a go or a constrained go now
would describe unavailable speech as proven, which #5 explicitly forbids.

The harness is ready and mechanically verified. Run the
[runbook](av005/runbook.md), pull the evidence, and then:

```sh
python3 tools/av005-probe/validate_evidence.py docs/testing/av005/evidence/operator-run.json --write
```

That refreshes the measured table above from the operator's own run, after which
the exit decision can be recorded against real numbers.

## Validation and evidence

```sh
cd tools/av005-probe && ./gradlew :app:assembleDebug
python3 tools/av005-probe/validate_evidence.py docs/testing/av005/evidence/mechanical-silence-run.json
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -m pip check
git diff --check
```

The validator checks fixture-prompt identity, that capture followed playback plus
the settling interval, that an error never carries a transcript and a success
never carries an error, that no transcript was the prompt echoed back, that no
stale callback advanced a turn, and that a transcript is only counted as spoken
when the operator attested to speaking it. It re-checks captured evidence; it
does not rerun the emulator.
