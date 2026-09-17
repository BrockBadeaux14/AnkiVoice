# AV-046: which layer drops the guest microphone audio — diagnostic prepared, live run pending

Issue [#74 — Establish which layer drops guest microphone audio on the pinned AVD](https://github.com/BrockBadeaux14/AnkiVoice/issues/74).
Branch `codex/av-046-microphone-layers`. Reproduce with [the runbook](runbook.md).

**The diagnostic is built and verified offline; the live run has not happened.** It needs
the owner speaking six short phrases at the emulator over three cold boots, and nothing in
it synthesizes a voice, so the question the card asks is still open. This page records what
was built, the rule the answer will be read with, what the earlier evidence already says,
and it will record the exit criterion once the run is in.

| Criterion | Status |
| --- | --- |
| Per attempt: the microphone-open index within the boot, the recorder's PCM classification, the transport outcome and the emulator's audio-backend log lines, every attempt kept under `evidence/` | **Prepared** — `CaptureLayerInstrumentation` records the first three from inside the process; `tools/av046-qa/run.py` adds the index, the emulator log, the guest HAL's logcat and an audio-server snapshot, and appends every attempt |
| A reference capture outside AV-025's pipe in the same boot as at least one dropped transport capture | **Prepared** — `ReferenceMicrophone`, a bare `AudioRecord` from the test APK, runs concurrently through every capture's window |
| `results.md` states which exit criterion was met, the boots and captures spent, and the mitigation for #27 and #29 | **Pending the run** — see [the live layer](#live-layer-not-yet-run) |
| A runbook reproduces the diagnostic capture; it needs the owner to speak and injects no audio | **Done** — [the runbook](runbook.md) |
| No review written, no shipped transport, policy or session code changed, drift guards untouched | **Done** — every file is under `androidTest`, `tools/av046-qa/`, `tests/` or `docs/`; `tests/test_av046_layers.py` holds it there |

## What was built

| File | What it does |
| --- | --- |
| `app/androidTest/…/ReferenceMicrophone.kt` | The reference capture: `AudioRecord(MIC, 16 kHz, mono, PCM16)` read on its own thread, with no `:speech` type, no pipe and no recognizer. Records every sample, a per-second peak and RMS profile, the first frame and the first non-silent one, read latencies, the routed device and the active microphones |
| `app/androidTest/…/PumpDiagnostics.kt` | A pass-through observer on the shipped `CaptureStream`: when the open was asked for and answered, the first frame read and the first non-silent one, read and pipe-write latencies, the bytes written to the recognizer's pipe before Done and the generated padding after it, and when the microphone stopped and the pipe closed. Every call goes straight through; a failing write is recorded and thrown on |
| `app/androidTest/…/CaptureLayerInstrumentation.kt` | The harness: one capture through `SpeechTransport(PumpDiagnostics(CaptureDiagnostics(AndroidSpeechPlatform)))` — the shipped pipe with two observers on it — with the reference open through the same window, the recognizer's raw callbacks through AV-044's inert observer, and the audio server's list of recording clients (and whether it silenced any) taken at Speak now. It plays no prompt; the phrase is on the screen and only the operator attests to having said it. It constructs no card provider and no writer |
| `app/androidTest/…/LiveVerificationUi.kt` | Gains `attestSpoken`, a one-statement attestation for a capture with no prompt |
| `tools/av046-qa/run.py` | Cold-boots the pinned AVD within the budget, turns the host microphone on, installs, runs the harness once per phrase, and adds what the process cannot see: the open index within the boot, the emulator's own log lines for its audio backend, the guest HAL's logcat for the window, and `dumpsys media.audio_flinger` while both recorders are open. Appends every attempt to `attempts.jsonl` and reads it with the validator's rule as it lands. `--smoke` runs one unattended silent capture under `build/` as a harness check |
| `tools/av046-qa/validate.py` | The one attribution rule, the consistency checks, the summary with the exit criterion, and the results table |
| `tests/test_av046_layers.py` | The drift guard and the rule's unit cases; runs the validator over the retained evidence |

Nothing under `speech/src/main`, `core/src/main` or `app/src/main` changed. The transport
under observation is the one that ships, constructed the way `SpeechModule.create` constructs
it, with observers that return exactly what they were given.

## The rule the answer is read with

Per attempt, from the record alone, in this order:

| Reading | Layer | Exit criterion |
| --- | --- | --- |
| The capture returned a transcript | `no-drop` | — |
| The emulator logged `coreaudio: Could not …` / `Failed to create voice` in the window, or exited | `emulator-or-host` | 2 |
| The guest HAL logged `pcm_readi` read failures in the window | `emulator-or-host` | 2 |
| The reference received speech; the transport's recorder read nothing, zeros or room noise | `app-recorder` | 1 |
| Both recorders received speech; the pump did not write every sample to the pipe, or a write failed, or the pipe never closed | `app-pipe` | 1 |
| Both recorders received speech and the pump carried all of it | `after-the-pipe`: the audio reached the recognizer, so the no-match is a recognition result and not a dropped capture | informs 3 |
| Neither recorder received speech, and the owner attests to speaking | `emulator-or-host` | 2 |
| Neither recorder received speech, and nobody attests to speaking | `no-speech` | — |
| The transport's recorder received more than the reference | `reference-anomaly` | — |

"Speech" is a PCM16 peak at or above 2,000 (room noise on this host read in the low
hundreds in AV-017 and AV-044; spoken phrases peaked in the thousands, and AV-042's clipped
at full scale). "Zeroed" is AV-017's rule: a peak under 20 with at least half the samples
zero. The rule never infers from the audio that a phrase was spoken; only the operator's
ticked attestation says that, and a capture without it counts as no spoken capture.

## What the earlier evidence already says, re-read for this card

- **The AV-019 drops were never classified.** `ExchangeInstrumentation` recorded
  `transcript: ""`, `status: failed` for each empty attempt and did not copy the recorder's
  PCM, so "no audio at all" in the card's summary means *no transcript*; whether the
  recorder received audio is exactly what this diagnostic adds.
- **In the AV-019 session the coreaudio fault appeared once, right after the first boot
  completed** (`build/av019/emulator-boot-00.log`, retained on the host and not in the
  repository): the three `coreaudio:` lines, twice, then `Failed to create voice
  'virtio-snd-mic0'`, directly after `Boot completed in 16761 ms`. The emulator's log does
  not say which guest client opened the microphone, and none of the three later boots
  logged the fault. AV-005 recorded the same lines, followed by an exit, when the host's
  default input device changed while the emulator ran.
- **The host's default input device is not a constant.** On September 17, 2026 it was a
  Bluetooth headset (`WH-1000XM5`); AV-044's clean twelve-capture discovery recorded the
  MacBook Pro microphone. A Bluetooth input switches profile and sample rate when a client
  opens it, which is the kind of format change the emulator's coreaudio backend registers
  a listener for. Not established as a cause; recorded per boot by the driver from now on.
- **This emulator offers no other host audio backend.** The `qemu-system-aarch64` in
  37.1.11.0 names `coreaudio`, `noaudio` and `spice` as backends and `QEMU_AUDIO_DRV=wav`
  in its help; AV-017 found `-audio <backend>` accepts any value silently and surfaced no
  alternative. The guest side is `virtio-snd` (`virtio-snd-mic0`) behind the ranchu HAL.
  `hostmicon`/`hostmicoff` and `hw.audioInput` are the only other switches, and the run
  already uses them.

## Offline results — September 17, 2026

```
./gradlew --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
.venv/bin/python -m unittest discover -s tests
```

| Check | Result |
| --- | --- |
| `:app:assembleDebugAndroidTest` | builds; `CaptureLayerInstrumentation` is declared in the packaged manifest after `ReviewInstrumentation` |
| `tests/test_av046_layers.py` | 23 tests: the guard, the rule over one fabricated attempt per layer, the consistency checks rejecting an edited record, the summary naming the exit criterion, and the validator over the retained evidence |
| Python suite in full | 317 tests, 0 failures |
| `tools/av046-qa/validate.py` | nothing to validate yet |

## The harness check, September 17, 2026: the guest HAL could not read the microphone at all

Before any of the owner's budget was spent, the driver's `--smoke` mode ran the harness
with **nobody speaking**, under ignored `build/av046/`. These are harness checks, not
attempts, and they carry no attestation; they are recorded here because what they found
bears directly on the card's question.

| Probe | Boot · open | Host input | Reference | Transport recorder | Pipe | Recognizer | Emulator log | Guest HAL (logcat) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 · 1 | `WH-1000XM5` (Bluetooth) | zeroed: 144,384 samples, 144,066 zero, peak 2 | zeroed: 131,840 samples, 131,569 zero, peak 2 | carried 263,680 bytes + 16,000 padding, closed | empty segment, then `noMatch` (7) | clean | not captured (a driver bug, fixed) |
| 2 | 1 · 2, `hostmicon` re-issued | same | zeroed: 141,568 samples, peak 2 | zeroed: 128,640 samples, peak 2 | carried, closed | empty segment, then `noMatch` (7) | clean | **166 `pcm_readi` I/O errors, 544 silence inserts** in the window |
| 3 (control) | 2 · 1, cold boot, **no reference** | same | none | quiet: 131,840 samples, 83,874 zero, peak 284, RMS 28 | carried 263,680 bytes + 16,000 padding, closed | empty segment, then `noMatch` (7) | clean | **86 `pcm_readi` I/O errors, 288 silence inserts** |

What the second probe's guest log shows, on the guest clock: AudioFlinger's input thread
started at 12:42:04.268; from 12:42:04.425 `android.hardware.audio@7.1-impl.ranchu` logged
`pcm_readi was late delivering frames, inserting 16000 us of silence` every few
milliseconds; from 12:42:04.693 `pcm_readi failed with 'cannot read/write stream data:
I/O error' (-1)`, repeated until the stream stopped at 12:42:13.250. The audio server's own
dump, taken while both recorders were open, showed **one** input thread (`AudioIn_2E`,
16 kHz mono, device `AUDIO_DEVICE_IN_BUILTIN_MIC`, not in standby) with both recorders as
its two active tracks, neither silenced. The emulator's log carried no `coreaudio:` line and
the emulator did not exit.

So on that boot the loss was **below the guest's audio server**: the virtual sound device
(`virtio-snd`) delivered no frames to the guest HAL, every `AudioRecord` in the guest read
zeros, the app's pump carried those zeros faithfully to the recognizer, and the recognizer
answered with an empty segment and a no-match. Nothing in AV-025's pipe was involved; the
same zeros reached the reference recorder that has no pipe. That is the shape of exit
criterion 2, seen without speech: with a live host input a silent room still reads as a
noise floor of a few units, not as exact zeros with the HAL reporting read errors.

The control (probe 3) ran on a fresh boot with **no reference recorder**, and the HAL
failed the same way, so the concurrent reference is not what starves the stream. That
probe's recorder did receive a sparse noise floor — a peak of 284 with two thirds of the
samples zero, the zeros being the HAL's own 16 ms silence inserts — so the path was
stuttering rather than dead, which is also what an intermittently dropped spoken capture
would look like from the app's side.

What it does not yet say is **why** the host path delivered nothing on that boot. The
host's default input device was the Bluetooth headset for both probes, whereas every
earlier run that received audio used the MacBook Pro microphone; `-allow-host-audio` was
in effect (the emulator logged `Allowing host microphone input.`) and `hostmicon` was
acknowledged both times. The cheapest control is one silent probe on a cold boot with the
built-in microphone as the host input, which needs the owner to change the input device
first; the live run then needs the owner's voice on whichever input the probe shows to be
alive.

## Live layer — not yet run

Six attempts over three cold boots, `tools/av046-qa/run.py`, the owner's voice, the
concurrent reference. To be recorded here as the table `validate.py --table` prints, one
row per attempt including the inconclusive ones.

| # | Attempt | Say | Status |
| --- | --- | --- | --- |
| 1 | `01-colors` | green blue red | not yet run |
| 2 | `02-five` | five | not yet run |
| 3 | `03-confirm` | confirm | not yet run |
| 4 | `04-colors` | green blue red | not yet run |
| 5 | `05-good` | good | not yet run |
| 6 | `06-five` | five | not yet run |

**Exit criterion met: none yet.** Boots spent 0 of 3; captures spent 0 of 6.

### Mitigation for #27 and #29, as far as the record already supports it

Until the run says more, the mitigation is the one AV-019 and AV-044 arrived at, restated:

- **Captures per boot:** plan two, and cold-boot after them or after any coreaudio line in
  the emulator's log; the backend has exited on the second or third open of a boot.
- **Retry policy:** a capture that comes back empty costs a Try again through AV-012's
  `retry`, never a wrong answer and never a rating; expect answer versions above 1.
- **Confirmations:** tap them unless the spoken path is the point of the turn, because a
  spoken confirmation is a second microphone open.
- **Emulator audio configuration:** nothing beyond `-allow-host-audio` and `hostmicon` is
  available in this emulator; the host-side variable worth controlling is the default
  input device, chosen before launch and left alone.

## What this does not establish

Nothing yet about the layer: the run has not happened. When it has, six captures from one
speaker on one host on one day will show which side of the app's `AudioRecord` each drop
was on and whether the emulator logged a fault for it; they will not give a rate, and the
30-turn run stays in [#29](https://github.com/BrockBadeaux14/AnkiVoice/issues/29). Emulator
results say nothing about physical devices, Bluetooth audio inside the guest, real calls,
backgrounding or screen lock.
