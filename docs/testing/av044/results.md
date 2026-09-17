# AV-044: the pinned recognizer scores every segment, and the transport now carries it

Issue [#67 — Carry recognizer confidence through the segmented capture route](https://github.com/BrockBadeaux14/AnkiVoice/issues/67).
Branch `codex/av-044-recognizer-confidence`. September 16, 2026, America/Chicago.
Reproduce with [the runbook](runbook.md).

**Finding: scores are present.** On the pinned route — `GoogleTTSRecognitionService` at
the pinned version, `EXTRA_SEGMENTED_SESSION`, `EXTRA_PREFER_OFFLINE=false`, 16 kHz mono
PCM16 through `EXTRA_AUDIO_SOURCE` — every `onSegmentResults` bundle that carried text
also carried `CONFIDENCE_SCORES`, one value for the one hypothesis returned, measured with
the owner's voice on the recreated pinned AVD. The bundle the engine delivers before a
no-match carries empty text and no score. AV-014's diagnosis was exact: the scores were
there and the bridge discarded them.

**What shipped.** `AndroidSpeechPlatform` hands each segment to the transport with its own
score, and `SpeechTransport` makes the capture's confidence the **minimum** across the
segments that contributed text — unknown when any of them came without a score. Partial
results carry none, an empty segment contributes nothing, a fault still wins over any
score, and `:core`'s classification (`null` → `ABSENT`, `<= 0` → `LOW`, otherwise
`SUFFICIENT`) and AV-012's answer policy are untouched. A score decides only whether a
guarded spoken command may run; the explicit confirm intent for the current attempt and
revision is still required, and nothing here writes a review.

| Criterion | Status |
| --- | --- |
| Discovery: ≥10 spoken phrases through the shipped transport, every segment bundle logged, every attempt kept | **Done** — see [the discovery](#the-discovery-september-16-2026) |
| Scores present → propagate the per-capture minimum through the segmented session; partials carry none; `:core` and AV-012 unchanged | **Done** — `AndroidSpeechPlatform`, `SpeechTransport` |
| Offline tests in `:speech` against the fake platform: present → `SUFFICIENT`/`LOW`, absent → `ABSENT`, fault precedence, empty segment contributes no score | **Done** — 15 new `SpeechTransportTest` cases, 67 `:speech` tests in all |
| Live check through the AV-014 harness: a spoken confirm and a spoken rating **executed**, a mumbled guarded command refused, no review written | **Done** — see [the live check](#the-live-check-september-16-2026); `passed` in all three runs, `writes: []` |
| Results and runbook published; a score never confirms on its own | **Done** — this page, [the runbook](runbook.md), the guards |

## The discovery, September 16, 2026

### Environment

The pinned AVD `AnkiVoice_AV005` was **absent from the evidence host** when this card
started (only a `Medium_Phone` AVD existed, with a Play-updated engine). It was recreated
with AV-005's own creation command — the same system image
(`system-images;android-36;google_apis_playstore;arm64-v8a`), the same `medium_phone`
profile, signed out of Google — and its engine reads the pinned
`googletts.google-speech-apk_20241125.02_p2.702443970` on every attempt. Fingerprint
`google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:user/release-keys`,
API 36, emulator 37.1.11.0, launched with `-allow-host-audio -no-snapshot -no-boot-anim`
and the host microphone turned on over the console; MacBook Pro microphone as the host
input. [`environment.json`](evidence/discovery-20260916/environment.json) records the host
and the AVD configuration.

Each capture went through the shipped `SpeechTransport` over `AndroidSpeechPlatform` as
built from `main` plus one inert observer (`recognizerObserver`, null in production) that
recorded every raw recognizer callback. The harness constructs no card provider and no
writer; no collection was opened. The transport in this run was the **pre-change** one, so
every transcript below is classified `absent` — that is the measurement of the build as it
was, and the reason this card exists.

### Every attempt

<!-- av044:discovery:begin -->
| # | Attempt | Spoken (source) | Mic | Segments: text → score | Transport result | Done→final |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `01-colors` | — | — | — | **no-capture**: SpeechOutput.playbackInterrupted: synthesis error -4 | — |
| 2 | `01-colors` | — | — | — | **no-capture**: SpeechOutput.playbackInterrupted: synthesis error -4 | — |
| 3 | `02-five` | — | — | — | **no-capture**: SpeechOutput.playbackInterrupted: synthesis error -4 | — |
| 4 | `01-colors` | “green blue red” (operator) | live | “green blue red” → 0.972 | transcript “green blue red”, `absent` | 709 ms |
| 5 | `02-five` | “five” (operator) | live | “five” → 0.69 | transcript “five”, `absent` | 1067 ms |
| 6 | `03-confirm` | “confirm” (operator) | live | “confirm” → 0.972 | transcript “confirm”, `absent` | 613 ms |
| 7 | `04-yes` | “yes” (operator) | live | “yes” → 0.971 | transcript “yes”, `absent` | 596 ms |
| 8 | `05-good` | “good” (operator) | live | “good” → 0.965 | transcript “good”, `absent` | 714 ms |
| 9 | `06-rate-hard` | “rate hard” (operator) | live | “rate hard” → 0.909 | transcript “rate hard”, `absent` | 611 ms |
| 10 | `07-easy` | “easy” (operator) | live | “easy” → 0.972 | transcript “easy”, `absent` | 693 ms |
| 11 | `08-skip` | “skip” (operator) | live | “skip” → 0.972 | transcript “skip”, `absent` | 634 ms |
| 12 | `09-reveal` | “reveal the answer” (operator) | live | (empty) → no score | failed: `SpeechInput.noMatch: code 7` | 594 ms |
| 13 | `10-finish-session` | “finish session” (operator) | live | “finish session” → 0.947 | transcript “finish session”, `absent` | 664 ms |
| 14 | `12-two-segments` | “green blue red | confirm” (operator) | live | “green blue red confirm” → 0.972 | transcript “green blue red confirm”, `absent` | 727 ms |
| 15 | `13-confirm-mumbled` | “confirm” (operator) | live | “confirm” → 0.972 | transcript “confirm”, `absent` | 728 ms |
<!-- av044:discovery:end -->

Rows 1–3 are the prompt failures described below; rows 4–15 are the twelve operator
captures, over six cold boots, all with `RECORD_AUDIO` granted and the microphone PCM
classified as live. Every phrase was the owner's voice, attested on the device; the source
column says so and nothing else was used.

### What the attempts show

- **Every segment that carried text carried a score: 11 of 11.** The bundle keys were
  `confidence_scores`, `current_locale` and `results_recognition` every time, with one
  hypothesis and one score. No `recognition_parts` and no alternatives were offered.
- **The one no-match carried an empty segment with no score.** `reveal the answer`
  (row 12) came back as `onSegmentResults` with empty text and no `confidence_scores`,
  then `ERROR_NO_MATCH`. AV-014 never got a clean `reveal` measurement either; this is one,
  and it is a no-match, not a refusal.
- **The scores are high and flat.** Ten of the eleven sit between 0.909 and 0.972; the
  outlier is `five` at 0.69. The deliberately **mumbled** `confirm` (row 15) scored
  0.972, the same as the clear one. On this route the score separates "the engine has a
  hypothesis" from "it has none"; it is not a graded measure of how clearly the phrase
  was said, at least for a single short command word.
- **Two phrases with a two-second pause arrived as one segment** (row 14:
  "green blue red confirm", one score). The engine segments on its own end-of-speech
  detection, and a two-second pause did not trigger it. The minimum rule therefore
  matters most for longer captures; on these it reduces to the single score.
- **The shipped transport reported `absent` on all of them**, as AV-014 predicted: the
  score was in the bundle and the bridge dropped it. With the change below, rows 4–11 and
  13–15 classify `SUFFICIENT`.
- Done-to-final stayed in AV-013's band: 594–1067 ms, median 664 ms.

### Environment faults, recorded as such

Two, both the emulator's and neither a recognition result.

**The coreaudio listener leak, again.** AV-017 diagnosed the emulator's macOS audio
backend leaking a property listener on each microphone open. On this host the third open
of a boot logged

```
coreaudio: Could not initialize record
coreaudio: Could not set audio format change listener
coreaudio: Reason: kAudioHardwareIllegalOperationError
Failed to create voice `virtio-snd-mic0'
```

and the emulator **exited** rather than substituting the 220 Hz tone, taking the harness
with it. The driver therefore cold-boots after every two captures and after any fault line
in the emulator's log; the boot logs are beside the ledger.

**The engine refusing synthesis after an unclean stop.** From the first `emu kill`
reboot onward, every `synthesizeToFile` on this AVD returned `ERROR_SERVICE` (`-4`), with
the engine logging `AndroidComposerImpl: Failed to load pipeline definitions!` and
`LocalSynthesizer: Failed initializing controller with voice en-us-x-…-seanet-embedded`,
across cold boots and after `pm clear`. Recognition was unaffected. The first three ledger
rows are prompt failures with no capture — the operator could not speak because the prompt
never played — and are kept as such; the run then continued with `--prompt ''`, capture
only, because the prompt is not what this card measures. The cause turned out to be the
stop, not the engine: on this host `adb emu kill` returned before the guest had flushed its
last writes, and a later boot showed the same loss plainly (an app installed a minute
before the stop was gone, a collection pushed before it was back to empty, while older
installs survived). The engine's freshly written voice data was the first casualty. A
`-wipe-data` boot restored synthesis at once, and every stop after that was preceded by
`adb shell sync`; nothing broke again. The TTS route was verified live under AV-013 and
AV-025 and is not re-measured here.

### Before the recorded run

Two unrecorded smoke captures preceded it, in a scratch directory, while the harness was
being built: one on a boot with the host microphone allowed but not yet switched on
(zeroed PCM, no-match, an empty segment with no score) and one after `hostmicon`, which
picked up ambient speech in the room and returned two segments, each with a score above
0.96. The second is what showed the scores existed; it is not in the evidence because it
was not an attempt at a phrase and because its transcript is room talk, not a result. A
synthesized-voice path was also tried and abandoned at the owner's request; it produced no
usable capture and none of it is in the evidence.

## What changed

| File | Change |
| --- | --- |
| `speech/…/SpeechPlatform.kt` | `RecognitionListener` gains `onSegment(generation, text, confidence)` and `onEndOfSegments(generation)`; `onFinal` remains for a whole-utterance route |
| `speech/…/AndroidSpeechPlatform.kt` | The bridge accumulates nothing: each segment is handed over with the first `CONFIDENCE_SCORES` entry, or null; the inert `recognizerObserver` hook records raw callbacks for the discovery harness |
| `speech/…/SpeechTransport.kt` | Keeps the active capture's segments; `onEndOfSegments` delivers `aggregate(segments)`: the non-blank texts joined and the minimum of their scores, or null when any contributing segment has none; `lastConfidence` exposes the raw minimum for the harnesses; a segment never settles a capture |
| `speech/…/FakeSpeechPlatform.kt` | Scripts a segmented session (`Recognition.Segments`, optionally ending in a fault) and mid-capture segments |
| `speech/…/SpeechTransportTest.kt` | The card's cases, by name, plus stale-segment and no-leak-between-captures checks |
| `app/androidTest/…/ConfidenceInstrumentation.kt` | The discovery harness |
| `app/androidTest/…/CommandInstrumentation.kt` | Each voice-sweep entry records `recognizerConfidence`, the raw score behind the classification the router saw |
| `tools/av044-qa/` | `discover.py` (boots, runs, keeps every attempt), `validate.py` (consistency and the table) |
| `tests/test_av044_confidence.py` | Drift guard: the bridge passes scores, the minimum rule, no score on partials, `:core` and #15 unchanged, the observer inert, the harness has no route to a card |
| `android/README.md`, `README.md`, AV-014 results | The rule and a pointer |

The `Confidence` enum, `CommandVocabulary.parse`'s guard, `CommandRouter.confirm` and
`AnswerTurn`'s `gradable` rule are byte-for-byte what #15, #21 and #13 shipped; the
Python guard asserts each.

## Offline validation

Gradle 9.3.1 on Android Studio's JBR 25.0.2, macOS ARM64, no network, no emulator:

| Check | Result |
| --- | --- |
| `:speech:testDebugUnitTest` | 67 tests, 0 failures (`SpeechTransportTest` 60, `SpeechReadinessTest` 7) |
| `python -m unittest discover -s tests` | 291 tests, including the 17 AV-044 guards |
| `tools/av044-qa/validate.py` | every recorded attempt consistent |
| Full suite | `checkModuleBoundaries :core:test :ankidroid:testDebugUnitTest :provider:testDebugUnitTest :speech:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug` — `BUILD SUCCESSFUL`; 312 `:core`, 61 `:ankidroid`, 113 `:provider`, 67 `:speech`, 79 `:app` tests, 0 failures; module boundaries follow AV-022; debug, androidTest and release APKs assembled; lint clean |

## The live check, September 16, 2026

Through AV-014's `CommandInstrumentation`, interactive, with the **rebuilt transport**
installed, on the recreated AVD after a `-wipe-data` boot, against a disposable
`AV002 Baseline` collection (deck `1789613745782`, card `1789613745789`, offered with
ratings `[1, 2, 3, 4]`) on the pinned AnkiDroid 2.24.1 (SHA-256 verified). The real
`GuardedReviewWriter` was wired in behind the recording transport, as in AV-014, so a
command that reached it would have both written and appeared in the evidence. The
operator skipped the context-rule step on every run — AV-014 measured it, and each
microphone open counts on this emulator — and the touch sweep ran unattended after each
voice sweep. Three runs, three result files, every one `passed: true`, `writes: []`,
`stateUnchanged: true`:

| Run | Spoken | Context | Outcome | Raw score | Evidence |
| --- | --- | --- | --- | --- | --- |
| 1 | `confirm`, clearly | confirmation (rating 1 proposed) | **executed**, source `spoken`: “Rating 1 is confirmed for this card. Nothing is written until the review is submitted.” | not recorded (see below) | [`av014-result-confirm-good.json`](evidence/live-20260916/av014-result-confirm-good.json) |
| 2 | `good`, clearly | command (grading) | refused, `recognition-failed` (`noMatch`) | — | [`av014-result-rate-good.json`](evidence/live-20260916/av014-result-rate-good.json) |
| 3 | `skip`, **mumbled** | command | **refused**, `not-a-command`: the engine heard “skip skip”, which fails whole-utterance matching | 0.972 | [`av014-result-skip-mumbled-rate-good.json`](evidence/live-20260916/av014-result-skip-mumbled-rate-good.json) |
| 3 | `good`, clearly | confirmation (rating proposed) | **executed**, source `spoken`: “Rating 3 is proposed for this card. It is not submitted: confirm it first.” | 0.932 | same file |

The card read back `reps 1, cardType 1, queue 1, due 1789613685, intervalDays 0`
before and after every run.

So: one spoken **confirm** and one spoken **rating** executed rather than refused
`low-confidence` — the two outcomes AV-014 could not produce on this route — and one
deliberately mumbled guarded command was refused. It was refused as **not a command**,
not as low confidence: the mumble came back doubled and the parser's whole-utterance rule
stopped it, while the engine still scored it 0.972. That is the honest result, and the
[threshold note](#what-this-does-not-establish) below is what it means.

Also recorded, in order, so nothing is smoothed over:

- Run 1's sweep offered `rate-good` before `confirm` (vocabulary order), and the operator
  skipped it on that screen; run 2 was its retry, and the clear `good` came back as a
  no-match — a recognition result, with no audio fault in the emulator's log for that boot.
  Run 3's `good` then executed. Two spoken ratings were attempted; one was recognized.
- Run 1 was built before `lastConfidence` survived the router's spoken notice, so its raw
  score is absent from the file; the guard rule it passed requires `SUFFICIENT`, which on
  this classification means a score above zero. Runs 2 and 3 carry the value.
- Before run 1, one AV-014 run on the pre-wipe boot failed at the first prompt: the
  engine's `ERROR_SERVICE` paused the session, and the operator's Start answer raised
  `IllegalStateException: Start answer needs the answer phase, not paused`, with
  `wroteNothing: true`. Its result file was overwritten by run 1's file of the same name
  and it is recorded here from the console.
- The AV-014 harness does not classify the microphone PCM; the emulator log for each boot
  is the fault check, and none of the three boots logged one.

## What this does not establish

- **A threshold.** `:core` classifies any positive score as `SUFFICIENT`, so a recognized
  guarded command with a low score would run. The scores recorded here — including the
  mumbled attempt — are the evidence for whether #15 and #21 want a floor; this card
  changes no classification.
- **Reliability.** Twelve phrases from one speaker on one day. The 30-turn run stays in
  [#29](https://github.com/BrockBadeaux14/AnkiVoice/issues/29).
- **Anything about physical devices**, Bluetooth, real calls, backgrounding or screen lock,
  all deferred to [#32](https://github.com/BrockBadeaux14/AnkiVoice/issues/32).
- **The emulator's audio backend.** The listener leak and the post-crash synthesis
  failure are the emulator's; the transport releases the microphone correctly and the
  guest HAL behaves. Nothing on the app side of the seam can fix either.
