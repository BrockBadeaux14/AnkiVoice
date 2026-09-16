# AV-014 results: voice commands and safe navigation

Issue [#15 — Add voice commands and safe navigation](https://github.com/BrockBadeaux14/AnkiVoice/issues/15).
Reproduce with [the runbook](runbook.md).

**Status: the offline layer and the unattended live layer passed on September 16, 2026.
The operator voice sweep has not been run.** It needs a person speaking into the AVD, and
nothing here attests on an operator's behalf.

## What ran

| Layer | Status | Evidence |
| --- | --- | --- |
| Offline: vocabulary, context rule, guards | **Passed** — 48 JVM tests, 12 Python guards | `:core:test`, `:app:testDebugUnitTest`, `tests/test_av014_commands.py` |
| Unattended live: every command by touch, on the pinned AVD | **Passed** — no review written | [`evidence/touch-20260916/`](evidence/touch-20260916/) |
| Operator live: every command by **voice** | **Not run** | — |

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
