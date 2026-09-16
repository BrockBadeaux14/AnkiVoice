# AV-013 runbook: verify the session state machine

Issue [#14 — Build the deterministic session state machine](https://github.com/BrockBadeaux14/AnkiVoice/issues/14).

Two layers, deliberately separate. The offline layer proves the turn's ordering, tokens
and guards and runs anywhere. The live layer proves one real turn on the pinned AVD and
can only be produced by a person speaking into it.

The offline layer reads no collection and writes no review. **The live layer does write
one review**, which is why it refuses any deck whose name does not start with `AV002`.

## 1. Offline: the port, the 53 scenarios and the drift guard

```sh
cd android
./gradlew --console=plain checkModuleBoundaries :core:test
```

```sh
python -m unittest discover -s tests
```

`:core:test` runs the 19 named scenarios, the 34-mode failure sweep, the 17 guard tests
and `ScenarioDriftTest`. The Python suite runs `tests/test_av013_session.py`, which fails
if the checked-in manifest is stale or a named scenario is unported.

After changing `tools/av007_scenarios.py` or the failure taxonomy, regenerate:

```sh
python tools/av013_scenarios.py --write
```

These simulate the **shape** of a turn, never recognition quality: no offline test is
evidence that speech is transcribed correctly, and none of them touches a device.

## 2. Live: one turn on the pinned AVD

This run also discharges AV-025's outstanding live check, which
[#14 absorbed](https://github.com/BrockBadeaux14/AnkiVoice/issues/14). Do both parts.

Prerequisites, all from AV-042's accepted configuration:

- The pinned AVD, **restarted after host audio setup**. A stale boot is the condition that
  made the goldfish HAL substitute a 220 Hz tone for the microphone in AV-040/AV-042.
- Host microphone forwarding enabled, host output near 60% and guest media volume 9/15.
- `com.google.android.tts` present at the pinned version with the `en-US-language` local
  voice, and `RECORD_AUDIO` granted.
- A **disposable** AV-002 collection, generated and reset as
  [the fixtures runbook](../voiceqa-fixtures.md) describes. Back it up first. The harness
  refuses a deck whose name does not start with `AV002`, but that check is a backstop, not
  a substitute for using a throwaway collection.

### 2a. The transport, through AV-025's harness

Run [AV-025's live section](../av025/runbook.md#2-live-the-pinned-avd) in full: both
AV-042 phrases, one explicit Cancel during capture, and one permission-denied start.
Record `doneToFinalMs` for each successful turn.

### 2b. The session, end to end

```sh
cd android
./gradlew :app:installDebug :app:installDebugAndroidTest
adb -s emulator-5584 shell am instrument -w \
  -e confirm AV013_LIVE_SESSION \
  -e deck <AV002_DECK_ID> \
  -e expect "five blocks" -e speakMs 6000 \
  -e confirmRating 3 \
  org.ankivoice.test/org.ankivoice.app.SessionInstrumentation
adb -s emulator-5584 shell run-as org.ankivoice cat files/av013-result.json
```

The harness offers the next VoiceQA card, plays its Prompt through the pinned voice,
settles, opens capture on an explicit Start answer, waits `speakMs` for you to speak,
sends Done, grades with AV-015's rules, proposes, and submits **only** if `confirmRating`
matches the proposed rating. Omit `confirmRating` to watch the session refuse to write.

- **Speak only after the prompt finishes.** Capture does not open during playback.
- If the rules match nothing, the suggestion is `uncertain` and proposes no rating; pass
  `-e selfGrade <1-4>` to supply an explicit self-grade instead.
- The explicit Cancel path at the session level:

```sh
adb -s emulator-5584 shell am instrument -w -e confirm AV013_LIVE_SESSION \
  -e deck <AV002_DECK_ID> -e mode cancel \
  org.ankivoice.test/org.ankivoice.app.SessionInstrumentation
```

Expect `halt.reason` `answer_cancelled`, `recoveryOptions` of `try-again` and
`typed-correction`, `wroteNothing` true, and the card unchanged.

## Recording the result

Fill in this table in [results.md](results.md), one row per **attempted** turn:

| # | Mode | Phrase requested | `capture` returned verbatim | `doneToFinalMs` | `outcome.state` | Reviews written |
| --- | --- | --- | --- | --- | --- | --- |

Record what actually happened, including a wrong transcript, a no-match or an
`outcome-unknown`. AV-042's ledger keeps every failed attempt and this one does the same.
**An expected phrase that comes back wrong is a result, not a retry cue.** Do not re-run a
turn and report only the run that passed.

Confirm separately, in your own words, that the prompt was audible and that you spoke the
expected phrase. The harness records what the recognizer returned; it cannot attest to
what you said, and it never does so on your behalf.

After the run, verify in AnkiDroid that exactly one review landed on the expected card,
and reset the disposable collection.

## What a passing live run does and does not establish

One confirmed turn establishes that the pinned route, the session and the guarded writer
work together end to end. It is not a reliability estimate. The 30-turn acceptance run
stays in [#29](https://github.com/BrockBadeaux14/AnkiVoice/issues/29), and nothing here
says anything about physical devices, Bluetooth, real phone calls, backgrounding or
screen lock — all deferred to
[#32](https://github.com/BrockBadeaux14/AnkiVoice/issues/32).

If the live run fails in the transport, that is a finding against `:speech`. Reopen
[#26](https://github.com/BrockBadeaux14/AnkiVoice/issues/26) or open a follow-up rather
than repairing the transport in the state machine.
