# AV-014 runbook: verify voice commands and safe navigation

Issue [#15 — Add voice commands and safe navigation](https://github.com/BrockBadeaux14/AnkiVoice/issues/15).

Three layers, deliberately separate. The offline layer proves the vocabulary, the context
rule and the guards and runs anywhere. The unattended live layer proves every command by
touch against a real AnkiDroid collection. The operator live layer is the only one that
can prove a command works **by voice**, because only a person can speak into the AVD.

Unlike [AV-013's runbook](../av013/runbook.md), **no layer here writes a review**. That is
the property being tested, not a precaution: the real guarded writer is wired in behind a
recording transport, so a command that reached it would both land a review and appear in
the evidence.

## 1. Offline: the vocabulary, the context rule and the guards

```sh
cd android
./gradlew --console=plain checkModuleBoundaries :core:test :app:testDebugUnitTest
```

```sh
.venv/bin/python -m unittest discover -s tests
```

`:core:test` runs `VoiceCommandParserTest` and `CommandRouterTest`; `:app:testDebugUnitTest`
runs `StudyControllerTest` (AV-026 renamed `CommandControllerTest` when the debug surface
became the study screen). `tests/test_av014_commands.py` fails if the Kotlin vocabulary,
the touch-only rule, the guarded set or the false-trigger corpus drift from what the card
decided.

These simulate the **shape** of a command, never recognition quality. No offline test is
evidence that a spoken command is transcribed correctly, and none of them touches a device.

## 2. Unattended: every command by touch, on the pinned AVD

Prerequisites, all from AV-042's accepted configuration:

- The pinned AVD, `AnkiVoice_AV005`, cold-booted on port 5588 **with `-allow-host-audio`**.
  Without that flag the emulator zeroes the microphone, every capture returns `noMatch`,
  and nothing about the command layer is being tested. See
  [Microphone](#microphone-the-flag-that-is-easy-to-miss) below.
- Pinned AnkiDroid 2.24.1 with database access granted and `RECORD_AUDIO` granted.
- A **disposable** AV-002 collection with at least one due VoiceQA card, generated and
  reset as [the fixtures runbook](../voiceqa-fixtures.md) describes. Back it up first.
  The harness refuses a deck whose name does not start with `AV002`, but that check is a
  backstop, not a substitute for using a throwaway collection.

```sh
cd android
./gradlew :app:installDebug :app:installDebugAndroidTest
```

Check `adb -s emulator-5588 shell pm list instrumentation` after installation:
`CommandInstrumentation` must appear alongside the other four. A successful APK build
alone does not prove the entry survived packaging.

Find the disposable deck:

```bash
adb -s emulator-5588 shell am instrument -w -e confirm AV014_LIVE_COMMANDS -e listDecks true org.ankivoice.test/org.ankivoice.app.CommandInstrumentation
```

Then run the touch sweep:

```bash
adb -s emulator-5588 shell am instrument -w -e confirm AV014_LIVE_COMMANDS -e deck AV002_DECK_ID org.ankivoice.test/org.ankivoice.app.CommandInstrumentation
```

It opens one session on the real collection and records, in order:

1. **The context rule.** AV-012's answer window is opened for real; the harness records
   the context, that no spoken command was offered there, that the phrase came back as
   answer text verbatim, and that a command capture was refused. The transcript is then
   supplied as an explicit **typed correction** — recorded as one, never presented as
   something the recognizer returned. Override it with `-e transcript "…"` and the phrase
   with `-e falseTrigger "…"`.
2. **The touch sweep.** Every command in the vocabulary, each entry recording whether the
   surface actually offered it, the outcome, and the session state afterwards.
3. **The card, re-read** from the provider and compared with the snapshot taken at the
   start.

Read the evidence:

```bash
adb -s emulator-5588 shell run-as org.ankivoice cat files/av014-result.json
```

`passed` is true only when `writes` is empty **and** `stateUnchanged` is true. Save the
file before another run overwrites it.

## 3. Operator: every command by voice

```bash
adb -s emulator-5588 shell am instrument -w -e confirm AV014_LIVE_COMMANDS -e deck AV002_DECK_ID -e interactive true org.ankivoice.test/org.ankivoice.app.CommandInstrumentation
```

Host microphone forwarding must be enabled, host output near 60% and guest media volume
9/15, on an AVD **restarted after host audio setup** — a stale boot is the condition that
made the goldfish HAL substitute a 220 Hz tone for the microphone in AV-040/AV-042.

Each command gets its own screen: it names the context, says whether the command is offered
there, and waits for **Listen** or **Skip this one**. Say the command after tapping Listen.
Run a subset with `-e voice repeat,pause,skip` so a long session can be recorded a few
commands at a time. Resume is not in the list: it is a touch control by decision, and the
microphone is released while study is paused.

The context-rule step asks you to speak the false trigger as an answer. Say it as an
ordinary answer; it must be graded as answer text, not executed.

Each operator screen waits at most five minutes. Absence and timeout are never a
confirmation and never an attestation.

## Microphone: the flag that is easy to miss

The emulator zeroes the guest microphone unless it is launched with `-allow-host-audio`.
Its own help says so: *"Allows sending of audio from audio input devices. Otherwise, zeroes
out audio."* Launch it explicitly:

```bash
~/Library/Android/sdk/emulator/emulator -avd AnkiVoice_AV005 -port 5588 -allow-host-audio -no-snapshot -no-boot-anim
```

The Extended Controls → Microphone → *Virtual microphone uses host audio input* toggle is
the runtime equivalent. It defaults to off and does not persist across boots, so prefer the
flag. macOS will also ask for microphone permission for the launching app the first time;
if it was denied, grant it in System Settings → Privacy & Security → Microphone.

Confirm the microphone before spending an operator session on it. This records five seconds
with nobody speaking and needs no interaction:

```bash
adb -s emulator-5588 shell am instrument -w -e confirm AV025_LIVE_SPEECH -e diagnose true -e speakMs 5000 -e prompt Testing org.ankivoice.test/org.ankivoice.app.SpeechInstrumentation
```

Read `microphoneDiagnostics` in the result. Values are PCM16, so full scale is 32,768:

| `peak` / `rms` | Meaning |
| --- | --- |
| `peak` under ~20, roughly half the samples exactly zero | **Zeroed.** `-allow-host-audio` is missing, or macOS denied the microphone |
| Steady non-zero `rms` with a clean periodic waveform | The 220 Hz goldfish tone AV-040/AV-042 diagnosed. Cold-boot after host audio setup |
| `rms` in the hundreds or more, varying with room noise | The microphone is live; a `noMatch` after this is a recognition result worth recording |

An all-`noMatch` sweep with a zeroed microphone tests nothing about the command layer.
**Do not record it as a command result** — fix the microphone and re-run.

## Recording the result

Fill in the tables in [results.md](results.md), one row per **attempted** command:

| Command | Source | Context | Offered | Outcome | Reviews written |
| --- | --- | --- | --- | --- | --- |

Record what actually happened, including a command that came back refused, a phrase the
recognizer heard as something else, and any run that stopped early. **A command that does
not work is a result, not a retry cue.** Do not re-run and report only the run that passed.

Confirm separately, in your own words, that you heard the prompt and what you actually
said. The harness records what the recognizer returned; it cannot attest to what you said
and never does so on your behalf.

After the run, verify in AnkiDroid that **no** review landed on the card, and reset the
disposable collection.

## What a passing live run does and does not establish

A passing unattended run establishes that every command is reachable by touch against a
real collection and that no command path wrote a review. A passing operator run adds that
each spoken command resolved on the pinned route. Neither is a reliability estimate; the
30-turn acceptance run stays in
[#29](https://github.com/BrockBadeaux14/AnkiVoice/issues/29), and nothing here says
anything about physical devices, Bluetooth, real phone calls, backgrounding or screen
lock — all deferred to
[#32](https://github.com/BrockBadeaux14/AnkiVoice/issues/32).

If a run fails in the transport rather than in the command layer, that is a finding against
`:speech`. Reopen [#26](https://github.com/BrockBadeaux14/AnkiVoice/issues/26) or open a
follow-up rather than repairing the transport in the command parser.
