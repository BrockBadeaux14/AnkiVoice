# AV-017: advisory grading evaluation — corpus and harness complete, measurement INCOMPLETE

Issue [#19 — Evaluate advisory grading on held-out answers](https://github.com/BrockBadeaux14/AnkiVoice/issues/19).
Branch `codex/av-017-advisory-grading-eval`. September 16, 2026, America/Chicago.
Reproduce with [the runbook](runbook.md).

**Status: the corpus, the harness, the rubric and the discipline are complete and
reproducible. The measurement is not.** The rule path is measured in full over the 53
answers that are complete. The AI path has not been run, and 7 of the 9 STT-mistake
answers are still awaiting live capture, so **this run is reported as incomplete rather
than passed** — which is AV-017's own rule, computed by `score.py` rather than asserted
here.

This card gates nothing. It defines no target error rate, authorises no automatic
acceptance, and changed neither grader.

## What is complete

| Deliverable | Status |
| --- | --- |
| 60-answer labeled corpus, split fixed in the file | **Complete** — [`fixtures/grading/av017-corpus.json`](../../../fixtures/grading/av017-corpus.json) |
| Human label + written rationale per answer, before any grader ran | **Complete** for all 53 filled answers |
| Offline harness over the shipped graders, no emulator | **Complete** — `GradingEvaluationTest`, 3 modes |
| Record/replay reproducibility | **Complete** — 5 round-trip tests, no network |
| Scoring rubric and rate definitions | **Complete** — `tools/av017-qa/score.py` |
| Frozen-configuration and held-out discipline, enforced | **Complete** — `score.py` refuses to score held-out unfrozen |
| Quota accounting through AV-020's ledger | **Complete** — wired; 0 reservations so far, because the AI path has not run |
| Rule-path measurement | **Complete** over 53 answers |
| **6 live STT misrecognitions** | **Not met — 0 captured.** See below |
| **AI-path measurement** | **Not run** — needs one recorded pass with an OpenRouter key |

## The corpus

60 answers over the 8 cards of the AV-002 baseline package, 20 tuning / 40 held-out, to
the card's table exactly:

| Category | Tuning | Held-out | Human label |
| --- | ---: | ---: | --- |
| Paraphrase of the reference answer | 3 | 6 | correct |
| Negation | 3 | 5 | incorrect |
| Incorrect number or unit | 3 | 5 | incorrect |
| Incomplete concept | 3 | 6 | partial |
| STT mistake | 3 | 6 | per transcript |
| Correct short answer | 3 | 6 | correct |
| Incorrect short answer | 2 | 6 | incorrect |
| **Total** | **20** | **40** | |

All eight baseline fixture cards carry answers. Every label was written against the card's
`ReferenceAnswer`, `RequiredConcepts` and `AcceptedAnswers` **before any grader was run**,
each with a one-sentence rationale recorded beside it in the corpus file.

**The label rule, written down because the partial/incorrect boundary is a judgement:**

- **correct** — every required concept expressed, nothing contradicting the key.
- **partial** — relevant and consistent with the key, but at least one required concept
  omitted and nothing contradicted.
- **incorrect** — contradicts the key, states a wrong value or order, or is not a usable
  answer to the prompt at all.

An STT-mistake answer is labeled **for what the transcript says, not what the speaker
meant**. The graders receive text, so the text is what is judged.

### STT sourcing, and why two answers are in tuning

Only two genuine misrecognitions exist anywhere in the recorded evidence:

| Answer | Spoken | Recognizer returned | Source |
| --- | --- | --- | --- |
| `av017-stt-mistake-t1` | "It puts two first, then five, then seven." | `it puts you first then five then seven` | [AV-006 `native-stt-online-remainder.json`](../av006/evidence/native-stt-online-remainder.json) |
| `av017-stt-mistake-t2` | "Green, blue, red." | `You red` | [AV-042 attempt 4](../av042/results.md) |

Both are placed in the **tuning** split. The card keeps AV-006's results as regression
context only, and putting previously inspected output in the held-out 40 would make it
held-out evidence in name alone. `validate.py` fails if a `recorded` answer ever appears
in the held-out split.

The remaining 7 slots are `live-pending`: they carry no text and no label, and the harness
skips them rather than filling them. **Synthetic character-level corruption was not used
for any of the nine**, which the Python guard enforces structurally.

## The harness

`GradingEvaluationTest` replays the corpus through the **shipped** graders. It changes
neither: `RuleGrader` runs first inside the shipped `SemanticGrader` exactly as it does on
a device, a rule match sends no request and reserves no quota, and every reply passes
through the shipped instruction, route guard and two-key validation. **The only
substitution is the socket.**

| Mode | Network | Purpose |
| --- | --- | --- |
| `rule-only` (default, CI) | none | No credential, so the AI leg is never attempted |
| `record` | one pass | Live pinned free route; writes a transcript |
| `replay` | none | That transcript served back — the reproducible scoring run |

The replay transport refuses to serve a reply whose request no longer hashes to the one
that produced it, so an instruction, route or corpus change **fails the replay** instead
of quietly re-scoring against stale evidence. Five round-trip tests prove record → replay
equality, the refusal, unexhausted transcripts, that a recorded timeout replays as a
timeout, and that the transcript carries a request digest rather than the request or the
key.

`tests/test_av017_evaluation.py` adds 22 static guards: the corpus table, the split
discipline, the STT sourcing rule, that no evaluation source can reach a writer, that the
live capture builds no card provider, that the harness decides no label or rating of its
own, that requests-per-session times #18's retry fits inside the shipped 30-request
session cap, and that the scorer defines no threshold.

## Measurement: the rule path

53 of 60 answers scored (19 tuning, 34 held-out). Configuration `d502cd71abdf`, frozen
before the held-out answers were scored and recorded in
[`held-out-runs.jsonl`](evidence/rule-only-20260916/held-out-runs.jsonl).

| Split | Path | n | False acceptance | False rejection | Abstention | p50 | p95 |
| --- | --- | ---: | --- | --- | --- | ---: | ---: |
| Tuning | rule-only | 2 | 0 / 0 (n/a) | 0 / 2 (0.0) | 0 / 2 (0.0) | 0.079 ms | 0.294 ms |
| Tuning | AI | — | **not run** | — | — | — | — |
| Held-out | rule-only | 5 | 0 / 0 (n/a) | 0 / 5 (0.0) | 0 / 5 (0.0) | 0.096 ms | 0.156 ms |
| Held-out | AI | — | **not run** | — | — | — | — |

A denominator of 0 is reported as `n/a`, never as a rate of 0: **no answer labeled
incorrect ever reached a rule match**, so the rule path's false-acceptance rate is
undefined on this corpus rather than measured at zero. That is the honest reading.

### What the rule path actually did

**It fired on 7 of 53 answers (13%) and left 46 (87%) to the AI grader or an explicit
self-grade.** Every one of the 7 was an exact normalized match, and every one was
human-labeled correct:

| Answer | Split | Rule reason |
| --- | --- | --- |
| `av017-correct-short-t1` | tuning | Exact match with accepted answer 1 |
| `av017-correct-short-t2` | tuning | Exact match with the reference answer |
| `av017-correct-short-h1` | held-out | Exact match with the reference answer |
| `av017-correct-short-h2` | held-out | Exact match with the reference answer |
| `av017-correct-short-h3` | held-out | Exact match with accepted answer 1 |
| `av017-correct-short-h4` | held-out | Exact match with accepted answer 2 |
| `av017-correct-short-h5` | held-out | Exact match with accepted answer 1 |

Two observations, recorded rather than acted on:

- **The one-letter fuzzy rule never fired.** Not one answer in the corpus differed from a
  target by a single slip in a long word. That rule is reachable only from a
  misrecognition of the right shape, which is precisely what the unfilled STT slots would
  supply. Its behaviour on this corpus is therefore **untested**, not clean.
- **`av017-correct-short-h2`** ("Green blue red") matched *the reference answer*, not the
  accepted answer it was authored against, because normalization removes the reference
  answer's punctuation. Correct behaviour; recorded because the reason string names a
  target the author did not expect.

Rule-path latency is local computation on the JVM at microsecond resolution. It is not a
device measurement and says nothing about an Android runtime.

## Live STT capture, September 16, 2026

Pinned AVD `AnkiVoice_AV005` on `emulator-5588`, API 36, fingerprint
`google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:user/release-keys`,
launched with `-allow-host-audio`. Captures drive one bounded answer turn through the
shipped **#13 + #26** path — AV-012's `AnswerTurn` over AV-025's `SpeechTransport`. The
harness constructs no card provider and no writer, so **no collection was opened, read or
written at any point**; there was nothing to back up or reset.

Every attempt, including the ones that produced nothing:

| # | Slot | Operator action | Result | Microphone |
| ---: | --- | --- | --- | --- |
| 1 | `stt-live-1` | Cancel | `cancelled`, no transcript | not captured |
| 2 | `stt-live-1` | Done | **`there are five blocks`**, 678 ms, confidence absent | **live** (peak 14,812 / rms 1,275) |
| 3 | `stt-live-2` | Done | `noMatch` code 7, 641 ms | **220 Hz tone** (rms 23,179) |
| 4 | `stt-live-3` | Done | `noMatch` code 7, 629 ms | **220 Hz tone** (rms 23,180) |
| 5 | `stt-live-4` | Done | `noMatch` code 7, 667 ms | **220 Hz tone** (rms 23,179) |
| 6 | `stt-live-5` | Done | `noMatch` code 7, 665 ms | **220 Hz tone** (rms 23,180) |
| 7 | `stt-live-6` | Done | `noMatch` code 7, 632 ms | **220 Hz tone** (rms 23,180) |
| 8 | `stt-live-7` | Done | `noMatch` code 7, 632 ms | **220 Hz tone** (rms 23,181) |

The operator attested "prompt audible" and "I said the expected phrase" on attempts 2–8.

**Attempts 3–8 are environment faults, not recognition results.** Their captured PCM is a
full-scale 220 Hz sine — the goldfish HAL's generated tone, matching AV-040 and AV-042's
diagnosis to within 2 parts in 23,000 — and the host log shows the cause:

```
coreaudio: Could not initialize record
coreaudio: Could not set audio format change listener
coreaudio: Reason: kAudioHardwareIllegalOperationError
Failed to create voice `virtio-snd-mic0'
```

AV-014's runbook is explicit that an all-`noMatch` sweep on a dead microphone is not a
result, so **none of the six is recorded as a recognition failure of the pinned route**.
`capture.py` now classifies this automatically and refuses to capture at all when the
microphone does not read live, so no future operator session can spend attempts this way.

**Attempt 2 is a real, clean capture and it came back verbatim correct.** It therefore
fills no slot: a correct recognition is not an STT mistake. It is kept as evidence that
the #13 + #26 path transcribes real speech on this host, with `doneToFinalMs` 678 inside
AV-013's measured 625–685 ms band and confidence `absent`, as AV-013 and AV-014 both
recorded for this route.

New in this session, and worth naming: **the host audio device failed mid-session**, after
the cold boot had fixed it and one capture had succeeded. AV-040 and AV-042 saw the tone
from a stale boot; here it returned spontaneously. Cold-booting restores it.

### Finding: the 6-live-misrecognition criterion is impractical on this route

Recorded as evidence for the owner, not acted on. This card's acceptance criteria are
unchanged.

A misrecognition is the recognizer returning **wrong words**. A correct transcript is not
one, and `noMatch` — an empty result — is not one either. Pooling every attested spoken
capture this project has recorded:

| Source | Successful captures | Genuine misrecognitions |
| --- | ---: | ---: |
| AV-006 native STT | 10 | 1 — "two" → "you" |
| AV-013 live run | 4 | 0 |
| AV-042 probe | 3 | 1 — "green blue red" → "You red" |
| AV-017 (attempt 2) | 1 | 0 |
| **Pooled** | **18** | **2 (11.1%)** |

At that rate, six misrecognitions need roughly **54 successful captures** in expectation.
Compounding it, the host audio device on this evidence host survives only two or three
captures before needing a ~90-second cold boot. The criterion is not unreasonable in
principle — it is what makes the STT category real evidence rather than invention — but on
this route it is a multi-hour operator task with a long tail, and it is the only thing
standing between this card and a complete measurement.

Options for the owner, in the order they seem worth considering:

1. **Run it.** A dedicated capture session, budgeting for ~50 captures and ~20 cold boots.
   The runbook and the preflight make it mechanical.
2. **Lower the live floor** from 6 to the number the recorded evidence can actually supply
   (2 today), and say so in the report. That weakens the STT category to near-anecdote.
3. **Widen what counts.** `noMatch` is currently not a misrecognition. If the point of the
   category is "what the grading path does with degraded speech", a `noMatch` is arguably
   the commonest degradation on this route and is trivially available — but it produces no
   text to grade, so it tests AV-012's failure path, not grading.

Changing an acceptance criterion is the owner's call and was not made here.

## Quota

Zero reservations consumed. The AI path has not run, so nothing was spent, and the
ledger — the shipped `QuotaLedger`, writing
`av017-quota-ledger-rule-only.jsonl` — records `reservedTotal: 0`.

When the live pass runs it will reserve one request per answer the rules do not match,
which on the current corpus is **46 of 53**. The harness opens a fresh grading session
every 14 requests so it stays inside the shipped 30-request session cap **rather than
raising it**. The daily limit is the decision to record: 46 requests exceeds the default
50-per-UTC-day limit only in combination with retries, so either spread the run across two
UTC days or raise `-Pav017.dailyLimit` explicitly, and record which was done. Free route
only; no paid fallback.

## What this does not establish

- **Nothing about the AI path.** It has not been run. The report shows it as `not-run`,
  which is deliberately distinct from an abstention and is never scored as one.
- **Nothing about grading quality overall.** The rule path carries 13% of this corpus; the
  87% that matters most is unmeasured.
- **Nothing about recognition reliability.** One clean capture is not a rate.
- 40 held-out answers over 8 synthetic cards in English on one emulator is a small sample
  and would establish nothing about physical devices, other languages or real decks even
  when complete.
- Model confidence is an uncalibrated signal and is treated as one throughout.
- **Automatic acceptance stays out of the MVP.** Nothing here authorises submission
  without explicit learner confirmation, and no future automation follows from these
  numbers; that would need its own scope and error-target decision as a separate card.

## Reproduce

[The runbook](runbook.md) separates the four layers. Layer 1 runs anywhere and is what CI
runs. Layers 2 and 3 need the pinned AVD and your own credential respectively, and layer 4
replays layer 3 offline.

## Next

1. Capture the remaining 7 STT slots, or decide the criterion question above.
2. Run one recorded AI pass and score the held-out 40 once against a fresh frozen
   configuration, in a new evidence directory — the corpus hash changes when the STT slots
   are filled, so the current freeze `d502cd71abdf` will not apply to it.

The interim run is kept in the ledger rather than discarded, as the card requires of every
held-out run.
