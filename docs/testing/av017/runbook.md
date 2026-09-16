# AV-017 runbook: evaluate advisory grading on held-out answers

Issue [#19 — Evaluate advisory grading on held-out answers](https://github.com/BrockBadeaux14/AnkiVoice/issues/19).

Four layers, deliberately separate:

| Layer | Needs | What it establishes |
| --- | --- | --- |
| 1. Offline scoring | nothing | The rule path, measured in full, with no credential and no socket |
| 2. Live STT capture | the pinned AVD and your voice | The 6 genuine misrecognitions the corpus's STT slots require |
| 3. Live AI pass | your OpenRouter key | One recorded pass over the pinned free route |
| 4. Replay and score | the recorded transcript | The reproducible held-out scoring run |

**This card gates nothing.** It defines no target error rate, authorises no automatic
acceptance, and changes neither grader. Explicit learner confirmation stays on throughout.

## 0. The corpus

[`fixtures/grading/av017-corpus.json`](../../../fixtures/grading/av017-corpus.json) holds
all 60 answers, each with a human label written against the card's `ReferenceAnswer`,
`RequiredConcepts` and `AcceptedAnswers` **before any grader was run**, and the
tuning/held-out split fixed in the file itself.

The label describes **what the transcript says**, not what the speaker meant. An STT
mistake that garbles an answer is labeled for the garbled text, because that is what the
graders receive.

```sh
.venv/bin/python -m unittest tests.test_av017_evaluation
.venv/bin/python tools/av017-qa/validate.py
```

The first fails if the corpus drifts from the card's table, if a scored path gains a
threshold, if the harness acquires a route to a writer, or if an STT-mistake answer stops
being a real capture. The second checks the evidence directory as well.

## 1. Offline: the rule path, with no credential and no socket

```sh
cd android
./gradlew --console=plain :provider:testDebugUnitTest --tests '*GradingEvaluationTest*'
```

This is what CI runs. It replays every scoreable answer through the shipped
`SemanticGrader`, which runs `RuleGrader` first exactly as it does on a device. With no
credential the AI leg is never attempted, and the run records that as **not run** — which
is not an abstention and is never scored as one.

Inspect the tuning 20 without spending the held-out 40:

```sh
./gradlew --console=plain :provider:testDebugUnitTest --tests '*GradingEvaluationTest*' -Pav017.split=tuning
```

Score it:

```sh
.venv/bin/python tools/av017-qa/score.py \
  android/provider/build/av017/run-rule-only-tuning.json \
  --evidence docs/testing/av017/evidence/<run> --label tuning-inspection
```

`score.py` **refuses** to score any held-out answer until a configuration is frozen. That
is the discipline, enforced rather than described.

## 2. Live: capture the STT-mistake answers

### Prerequisites

- The pinned AVD `AnkiVoice_AV005`, cold-booted on port 5588 **with `-allow-host-audio`**:

  ```sh
  ~/Library/Android/sdk/emulator/emulator -avd AnkiVoice_AV005 -port 5588 -allow-host-audio -no-snapshot -no-boot-anim
  ```

- `RECORD_AUDIO` granted, guest media volume 9/15, host output near 60%.

  ```sh
  adb -s emulator-5588 shell pm grant org.ankivoice android.permission.RECORD_AUDIO
  adb -s emulator-5588 shell cmd media_session volume --stream 3 --set 9
  ```

- The app and test APK installed, with the entry point surviving packaging:

  ```sh
  cd android && ./gradlew :app:installDebug :app:installDebugAndroidTest
  adb -s emulator-5588 shell pm list instrumentation | grep EvaluationInstrumentation
  ```

### The microphone, which is the thing that actually goes wrong

`capture.py` runs an unattended five-second check first and **refuses to capture** unless
the microphone reads live. Values are PCM16, full scale 32,768:

| Reading | Verdict | Meaning |
| --- | --- | --- |
| `rms` ≈ 23,000, `dominant_hz` ≈ 220, near-zero silence | `goldfish-220hz-tone` | The HAL substituted a generated tone. AV-040, AV-042 and AV-014 all lost attempts to this |
| `peak` under ~20, half the samples exactly zero | `zeroed` | `-allow-host-audio` is missing, or macOS denied the input device |
| Varying `rms` with real silence gaps | `live` | Capture away |

The remedy is a cold boot, and you will need one **every two to four microphone opens**.
Measured on the AV-017 evidence host with an unattended re-open test: 2 live opens at a
1-second gap, 4 at 8 seconds, 3 at 30 seconds — the budget does not depend on how long you
wait, so no settle delay helps. The emulator logs the fault as

```
coreaudio: Could not initialize record
coreaudio: Could not set audio format change listener
coreaudio: Reason: kAudioHardwareIllegalOperationError
Failed to create voice `virtio-snd-mic0'
```

and never recovers within that boot. The cause is in the emulator's coreaudio backend
(`audio/coreaudio.c` in `platform/external/qemu`): `coreaudio_init_base` registers a
sample-rate-change listener on the input device for every voice it opens, and
`coreaudio_fini_base` never removes it, so each capture leaks a listener whose client
pointer is a voice the emulator then frees. The failing call on a later open is that
registration. Our transport releases the microphone correctly and the guest HAL is
behaving; nothing on the app side of the seam can fix this. Emulator 37.1.11.0.

**Every open counts, including `capture.py`'s own five-second preflight.** For an
attended session, prefer to launch the emulator with its output captured to a file, run
`capture.py --no-preflight`, treat a `Failed to create voice` line in that file as the
fault, and cold-boot proactively after every two captures — that spends the whole budget
on the operator's speech and never asks them to speak into the attempt that would have
failed. The PCM preflight stays the default because it needs no access to the emulator's
log and is exact. Watch the macOS microphone indicator too: when it does not light, the
host device did not open.

A capture taken on a dead microphone is an **environment fault, not a recognition
result**, and `capture.py` records it as one. It never fills a corpus slot.

### Capture

The tools find `adb` on `PATH`, then under `ANDROID_HOME`, `ANDROID_SDK_ROOT` or the
default `~/Library/Android/sdk`; a shell without the SDK on `PATH` is fine.

```sh
.venv/bin/python tools/av017-qa/capture.py --list
.venv/bin/python tools/av017-qa/capture.py --slot stt-live-1 --evidence docs/testing/av017/evidence/<run>
```

Each capture drives one bounded answer turn through the shipped **#13 + #26** path:
AV-012's `AnswerTurn` over AV-025's `SpeechTransport`. It constructs no card provider and
no writer, so it cannot touch a collection — there is nothing to back up or reset.

On the device: **Play prompt** → **Start answer** → speak → **Done** → tick only what is
actually true → **Save result**. Speak at your normal pace. A deliberately degraded
reading is not evidence of what the route does in use, and the card excludes manufactured
corruption for the same reason.

**Every attempt is appended to `captures.jsonl`, including the ones that come back
correct.** A correct recognition is a result; it simply cannot fill an STT-*mistake* slot.
Do not re-run and keep only the attempt that produced an error.

### Fill a slot

```sh
.venv/bin/python tools/av017-qa/apply_captures.py --slot stt-live-6 \
  --label partial --rationale "written against the card's answer key" \
  --evidence docs/testing/av017/evidence/<run>
```

It refuses an environment fault, a correct recognition, and a missing rationale. **Write
the label before running any grader on the filled answer.** A label written after seeing a
grader's output is not the label this evaluation measures against.

## 3. Live: one recorded AI pass

> **Never put the key in a file, a shell argument, a screenshot or an evidence JSON.**
> Export it in the shell that starts Gradle. `validate.py` fails if anything key-shaped
> appears anywhere in the evidence.

The harness reads `OPENROUTER_API_KEY` from the environment. Gradle's daemon may not carry
a variable exported after it started, so run this pass with `--no-daemon`:

```sh
cd android
export OPENROUTER_API_KEY=...     # you type this; it is never written down
./gradlew --no-daemon --console=plain :provider:testDebugUnitTest \
  --tests '*GradingEvaluationTest*' \
  -Pav017.mode=record \
  -Pav017.out=$PWD/../docs/testing/av017/evidence/<run> \
  -Pav017.dailyLimit=200
```

**Quota.** The run reserves one request per answer the rules do not match, through the
shipped `QuotaLedger`, into `av017-quota-ledger-record.jsonl` in the evidence directory.
The harness opens a fresh grading session every 14 requests so it stays inside the shipped
30-request session cap rather than raising it. The **daily** limit is the one you must
decide: either spread the run across more than one UTC day, or raise
`-Pav017.dailyLimit` explicitly. Record which you did in `results.md`. Free route only;
never raise a cap to make a run succeed, and never enable a paid fallback.

## 4. Freeze, replay and score once

Freeze the graded configuration **before** the held-out 40 is scored:

```sh
.venv/bin/python tools/av017-qa/score.py \
  docs/testing/av017/evidence/<run>/run-record.json \
  --evidence docs/testing/av017/evidence/<run> --freeze --label held-out-1
```

The configuration hash covers the corpus and the shipped sources that decide a label:
`RuleGrader.kt`, `Suggestions.kt`, `Grading.kt`, `GradingInstruction.kt`,
`SemanticGrader.kt` and `FreeRoute.kt`. Change any of them and the hash changes, and
`score.py` refuses to score the held-out answers against the old freeze.

Reproduce the scoring offline, with no network, from the recorded transcript:

```sh
cd android
./gradlew --console=plain :provider:testDebugUnitTest --tests '*GradingEvaluationTest*' \
  -Pav017.mode=replay -Pav017.out=$PWD/../docs/testing/av017/evidence/<run>
```

The replay transport refuses to serve a reply whose request no longer hashes to the one
that produced it, so an instruction or route change fails the replay instead of quietly
re-scoring against stale evidence.

**Every held-out scoring appends to `held-out-runs.jsonl` with its configuration hash,
including runs you later discard.** Silently re-scoring after a tweak is a failed run, not
a better one. If you must change the configuration, start a new evidence directory.

## What a completed run does and does not establish

A completed run is advisory quality evidence for the grading path on one emulator. It is
**not** a pass, a threshold, or authorisation for anything:

- 40 held-out answers over 8 synthetic cards in English on one emulator is a small sample.
  It establishes nothing about physical devices, other languages or real decks.
- Model confidence is an uncalibrated signal and is treated as one.
- Automatic acceptance stays out of the MVP. Any future automation proposal needs its own
  scope and error-target decision as a separate card.

**A scored run that used fewer than 6 genuine live misrecognitions is reported as
incomplete rather than passed.** `score.py` computes that verdict itself and writes it
into `measurements.json`; it is not a judgement call at reporting time.
