# AV-014 results: voice commands and safe navigation

Issue [#15 — Add voice commands and safe navigation](https://github.com/BrockBadeaux14/AnkiVoice/issues/15).
Reproduce with [the runbook](runbook.md).

**Status: all three layers ran on September 16, 2026.** Ten of the eleven spoken commands
were recognized on the pinned AVD; `reveal` has not yet had an attempt free of an emulator
audio fault. No review was written in any run.

## What ran

| Layer | Status | Evidence |
| --- | --- | --- |
| Offline: vocabulary, context rule, guards | **Passed** — 48 JVM tests, 12 Python guards | `:core:test`, `:app:testDebugUnitTest`, `tests/test_av014_commands.py` |
| Unattended live: every command by touch, on the pinned AVD | **Passed** — no review written | [`evidence/touch-20260916/`](evidence/touch-20260916/) |
| Operator live: every command by **voice** | **10 of 11 recognized**, `reveal` outstanding | [`evidence/voice-20260916/`](evidence/voice-20260916/) |

## Offline

`:core` carries 38 tests across `VoiceCommandParserTest` and `CommandRouterTest`, and
`:app` carries 10 in `CommandControllerTest`, all against the AV-041 fakes with no emulator,
no network and no real clock.

They cover each command in a command context; the **false-trigger set**, driven through a
real turn, where command words spoken inside the answer window are graded as answer text;
low-confidence and ambiguous rejection; skip writing nothing; pause during and outside
capture; resume discarding the turn and re-querying; and a sweep of every command from
every position a session can reach, by touch and by voice, asserting the transport is never
called.

`tests/test_av014_commands.py` guards the decisions behind that behaviour — the vocabulary,
the touch-only resume, which commands the confidence gate protects, the context rule, and
statically that `CommandRouter` contains no path to the writer.

## Unattended live run, September 16, 2026

Pinned AVD `AnkiVoice_AV005` (`emulator-5588`), Android 16 / API 36, AnkiDroid 2.24.1,
app 0.1.0, on a freshly reset disposable `AV002 Baseline` collection (deck
`1789580769973`). The real `GuardedReviewWriter` was wired in behind a recording
transport, so a command that reached it would have both landed a review and appeared in
the evidence.

Card `1789580769983`, offered with ratings `[1, 2, 3, 4]`.

### The context rule, with AV-012's answer window open

| Check | Result |
| --- | --- |
| Context reported while the window was open | `answer` |
| Spoken commands offered there | none |
| `"repeat the experiment"` returned verbatim as answer text | yes |
| A learner-opened command capture | refused, `in-answer-window` |

The transcript was then supplied as an explicit **typed correction**
(`"Green, blue, red."`, status `user-corrected`) — recorded as one, never presented as
something the recognizer returned.

### Every command, by touch

| # | Command | Context | Offered | Outcome | Session after | Reviews written |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `repeat` | command | yes | executed | grading | 0 |
| 2 | `reveal` | command | yes | executed | grading | 0 |
| 3 | `rate-again` | command | yes | executed | proposing | 0 |
| 4 | `rate-easy` | confirmation | yes | executed | proposing | 0 |
| 5 | `rate-good` | confirmation | yes | executed | proposing | 0 |
| 6 | `change` | confirmation | yes | executed | proposing | 0 |
| 7 | `rate-hard` | confirmation | yes | executed | proposing | 0 |
| 8 | `confirm` | confirmation | yes | executed | proposing | 0 |
| 9 | `skip` | confirmation | yes | executed | paused | 0 |
| 10 | `resume` | command | yes | executed | asking | 0 |
| 11 | `pause` | command | yes | executed | paused | 0 |
| 12 | `resume` | command | yes | executed | asking | 0 |
| 13 | `finish-session` | command | yes | executed | stopped | 0 |

All twelve commands in the vocabulary were offered and ran. After `confirm`, the review
state stayed `pending` with `hasConfirmation()` true: **a confirmation authorized a review
and did not submit one.** `skip` then ran from that state and still wrote nothing.

Both `resume` runs re-queried and were handed the same still-due card on a fresh snapshot,
which is what the card's decision 2 predicted.

### The collection afterwards

| | reps | cardType | queue | due | intervalDays |
| --- | --- | --- | --- | --- | --- |
| Before | 1 | 1 | 1 | 1789580709 | 0 |
| After | 1 | 1 | 1 | 1789580709 | 0 |

`writes: []`, `wroteNothing: true`, `stateUnchanged: true`, `passed: true`. The card was
re-read from the provider, not compared against the snapshot held in memory.

## Operator voice sweep, September 16, 2026

Three sessions on the same AVD, all with `writes: []` and `stateUnchanged: true`. Every
attempt is kept, including the confounded first run and the environment faults.

### What each command did

| Command | Guarded | Recognized on the AVD | Outcome when recognized |
| --- | --- | --- | --- |
| `repeat` | no | yes | **executed** |
| `pause` | no | yes | **executed** |
| `change` | no | yes | **executed** |
| `reveal` | yes | **not yet** | — |
| `skip` | yes | yes | refused, `low-confidence` |
| `finish-session` | yes | yes | refused, `low-confidence` |
| `rate-again` | yes | yes | refused, `low-confidence` |
| `rate-hard` | yes | yes | refused, `low-confidence` |
| `rate-good` | yes | yes (as `"good"`) | refused, `low-confidence` |
| `rate-easy` | yes | yes | refused, `low-confidence` |
| `confirm` | yes | yes | refused, `low-confidence` |

**All three unguarded commands executed by voice. Every guarded command that the recognizer
matched was refused by the confidence gate and by nothing else** — the vocabulary resolved,
and criterion 7's rule is what stopped it. That is the designed behaviour measured on a
device rather than predicted.

The context rule held live on a real capture: `contextDuringWindow` was `answer`, no spoken
command was offered there, the phrase came back verbatim, and a command capture was refused
`in-answer-window`.

### Why confidence is always absent

Not a property of the engine. `RecognizerBridge.onEndOfSegmentedSession` in
[`AndroidSpeechPlatform.kt`](../../../android/speech/src/main/kotlin/org/ankivoice/speech/AndroidSpeechPlatform.kt)
passes `null` confidence unconditionally, while `onResults` reads `confidence(results)`.
The pinned route always sets `EXTRA_SEGMENTED_SESSION`, so every recognition on it reports
`ABSENT` regardless of what the engine supplied per segment. Whether the engine supplies
per-segment scores at all is **untested**. This is an AV-025 (#26) question, not an AV-014
one, and it is recorded here rather than acted on.

### Findings about the vocabulary

- `"rate good"` and `"rate easy"` were misheard as `"great good"` and `"great easy"` on
  separate attempts. The bare forms `"good"` and `"easy"` are already unambiguous phrases
  and matched on the first try. The `rate X` forms are the weaker ones on this route.
- Saying a command **twice** produces `"pause  pause"`, which correctly fails
  whole-utterance matching and is refused as `not-a-command`. That is the parser working,
  not a defect; the first session is kept as evidence of it.

### Environment faults, recorded as such

Two sessions logged, on the host:

```
coreaudio: Could not initialize record
coreaudio: Reason: kAudioHardwareIllegalOperationError
Failed to create voice `virtio-snd-mic0'
```

The emulator's audio backend failed to open the macOS input device; no microphone opened
and no prompt played. Both occurrences coincide with a `reveal` attempt, which is why that
command has no clean measurement. This is the same family as AV-042's `pcm_prepare`
failures and is **an environment fault, not a command result**. The emulator was also
launched initially without `-allow-host-audio`, which zeroes the microphone outright and
produced an all-`noMatch` session; see the runbook's microphone section.

## What this does not establish

- **No command has been verified by voice on a device.** The whole vocabulary was driven
  by touch; the spoken path was exercised only against the fakes. Section 3 of the runbook
  is the outstanding work, and until it runs, nothing here says a spoken command is
  recognized on the pinned route.
- AV-013's live check recorded **absent** recognizer confidence throughout. If that holds,
  the guarded commands — reveal, finish session, skip, the four ratings and confirm — will
  be refused by voice and need their touch controls, while repeat, pause and change will
  still run. That is the designed behaviour, not a defect, but it is a prediction until the
  operator run measures it.
- One unattended run is not a reliability estimate. The 30-turn acceptance run stays in
  [#29](https://github.com/BrockBadeaux14/AnkiVoice/issues/29).
- Emulator evidence says nothing about physical devices, Bluetooth, phone calls,
  backgrounding or screen lock — all deferred to
  [#32](https://github.com/BrockBadeaux14/AnkiVoice/issues/32).
- The debug-grade command surface in `:app` is not the study screen. It shows no card text,
  no transcript and no grade; [#27](https://github.com/BrockBadeaux14/AnkiVoice/issues/27)
  replaces it.

## Collection handling

The disposable `AV002 Baseline` collection on the AVD had no due cards left after the
AV-013, AV-018 and AV-024 runs, so it was regenerated with
`tools/voiceqa_fixtures.py build` and the baseline `collection.anki2` was pushed to
`/sdcard/AnkiDroid/` with AnkiDroid stopped, replacing the previous disposable collection
with the owner's agreement. The previous file and its write-ahead log were backed up first.
Deck and card IDs therefore differ from the ones in AV-013's evidence; the
[fixture manifest](evidence/touch-20260916/fixture-manifest.json) records the new ones.
