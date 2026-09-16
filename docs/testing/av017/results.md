# AV-017: advisory grading evaluation — corpus and harness complete, measurement INCOMPLETE

Issue [#19 — Evaluate advisory grading on held-out answers](https://github.com/BrockBadeaux14/AnkiVoice/issues/19).
Branch `codex/av-017-advisory-grading-eval`. September 16, 2026, America/Chicago.
Reproduce with [the runbook](runbook.md).

**Status: the corpus, the harness, the rubric and the discipline are complete and
reproducible. The measurement is not.** The rule path is measured in full over the 57
answers that are complete. The AI path has not been run, and **4 of the required 6 live
STT misrecognitions are in hand** after 62 live attempts across three sessions; the owner
stopped capture there on September 16, 2026, so **this run is reported as incomplete
rather than passed**. That is AV-017's own rule, computed by `score.py` rather than
asserted here.

This card gates nothing. It defines no target error rate, authorises no automatic
acceptance, and changed neither grader.

## What is complete

| Deliverable | Status |
| --- | --- |
| 60-answer labeled corpus, split fixed in the file | **Complete** — [`fixtures/grading/av017-corpus.json`](../../../fixtures/grading/av017-corpus.json) |
| Human label + written rationale per answer, before any grader ran | **Complete** for all 57 filled answers |
| Offline harness over the shipped graders, no emulator | **Complete** — `GradingEvaluationTest`, 3 modes |
| Record/replay reproducibility | **Complete** — 5 round-trip tests, no network |
| Scoring rubric and rate definitions | **Complete** — `tools/av017-qa/score.py` |
| Frozen-configuration and held-out discipline, enforced | **Complete** — `score.py` refuses to score held-out unfrozen |
| Quota accounting through AV-020's ledger | **Complete** — wired; 0 reservations so far, because the AI path has not run |
| Rule-path measurement | **Complete** over 57 answers |
| **6 live STT misrecognitions** | **Not met — 4 captured** in 62 attempts; owner stopped at 4 |
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

### STT sourcing: 6 of 9 filled, 4 of them live

| Answer | Split | Spoken | Recognizer returned | Label | Source |
| --- | --- | --- | --- | --- | --- |
| `av017-stt-mistake-t1` | tuning | "It puts two first, then five, then seven." | `it puts you first then five then seven` | partial | [AV-006 `native-stt-online-remainder.json`](../av006/evidence/native-stt-online-remainder.json) |
| `av017-stt-mistake-t2` | tuning | "Green, blue, red." | `You red` | incorrect | [AV-042 attempt 4](../av042/results.md) |
| `av017-stt-mistake-h3` | held-out | "No, round does not satisfy square" | `no route does not satisfy Square` | correct | **live**, session 3 attempt 24 |
| `av017-stt-mistake-h4` | held-out | "No, it is blue but not square, and both conditions are required" | `no it is blue but not Square in both conditions are required` | correct | **live**, session 3 attempt 20 |
| `av017-stt-mistake-h5` | held-out | "It puts two first, then five, then seven" | `it puts two first then` | partial | **live**, session 2 attempt 16 |
| `av017-stt-mistake-h6` | held-out | "Two five seven" | `257` | partial | **live**, session 2 attempt 12 |

All live rows are in [`captures.jsonl`](evidence/live-20260916/captures.jsonl). The two
reused recordings are placed in the **tuning** split: the card keeps AV-006's results as
regression context only, and putting previously inspected output in the held-out 40 would
make it held-out evidence in name alone. `validate.py` fails if a `recorded` answer ever
appears in the held-out split.

Three of the labels are judgements and are recorded as such. **`257`** is the recognizer
collapsing three spoken number words into a digit string: read as digits it carries the
right values in the right order, read as one number it expresses no ordering at all. It
contradicts nothing, so it is partial rather than incorrect. **`it puts two first then`**
is a truncation after the first "then"; it names only the first element of the required
order and is labeled exactly as the authored "It puts two first." is. **`no route does not
satisfy Square`** still answers no and gives not satisfying square as the reason, so both
required concepts are present and it is labeled correct — a strict reader could call the
reason unintelligible, and the AI path's verdict on it is one of the things worth reading
when that path runs. `no … in both conditions are required` is a harmless garble of the
reference answer and is labeled correct.

The remaining 3 slots — `t3` (new, arithmetic), `h1` (suspended, arithmetic) and `h2`
(buried-sibling, reversal) — are `live-pending`: they carry no text and no label, and the
harness skips them rather than filling them. **Synthetic character-level corruption was
not used for any of the nine**, which the Python guard enforces structurally.

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

57 of 60 answers scored (19 tuning, 38 held-out). Configuration `4b093a06dd10`, frozen
before the held-out answers were scored and recorded in
[`held-out-runs.jsonl`](evidence/rule-only-20260916-3/held-out-runs.jsonl). Two earlier
interim runs — `d502cd71abdf` over 53 answers and `d2df08447ce5` over 55, each before a
live capture changed the corpus hash — are kept in
[their](evidence/rule-only-20260916/held-out-runs.jsonl)
[own](evidence/rule-only-20260916-2/held-out-runs.jsonl) directories, as the card
requires of every held-out run.

| Split | Path | n | False acceptance | False rejection | Abstention | p50 | p95 |
| --- | --- | ---: | --- | --- | --- | ---: | ---: |
| Tuning | rule-only | 2 | 0 / 0 (n/a) | 0 / 2 (0.0) | 0 / 2 (0.0) | 0.041 ms | 0.234 ms |
| Tuning | AI | — | **not run** | — | — | — | — |
| Held-out | rule-only | 5 | 0 / 0 (n/a) | 0 / 5 (0.0) | 0 / 5 (0.0) | 0.046 ms | 0.085 ms |
| Held-out | AI | — | **not run** | — | — | — | — |

A denominator of 0 is reported as `n/a`, never as a rate of 0: **no answer labeled
incorrect ever reached a rule match**, so the rule path's false-acceptance rate is
undefined on this corpus rather than measured at zero. That is the honest reading.

### What the rule path actually did

**It fired on 7 of 57 answers (12%) and left 50 (88%) to the AI grader or an explicit
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

None of the four live STT answers reached a rule. Three observations, recorded rather
than acted on:

- **The one-letter fuzzy rule was reached in shape once and declined by design.**
  `av017-stt-mistake-h4` differs from the reference answer at exactly one of twelve words
  (`in` for `and`), which is the shape the rule accepts — but both words are shorter than
  `MIN_FUZZY_LETTERS`, so it returned null and the answer fell through to the AI path.
  That is the documented policy working. No answer in the corpus matches the rule's full
  shape, so its accepting behaviour remains **untested**, not clean.
- **`av017-correct-short-h2`** ("Green blue red") matched *the reference answer*, not the
  accepted answer it was authored against, because normalization removes the reference
  answer's punctuation. Correct behaviour; recorded because the reason string names a
  target the author did not expect.
- Rule-path latency is local computation on the JVM at microsecond resolution. It is not
  a device measurement and says nothing about an Android runtime.

## Live STT capture, September 16, 2026

Pinned AVD `AnkiVoice_AV005` on `emulator-5588`, API 36, fingerprint
`google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:user/release-keys`,
emulator 37.1.11.0, launched with `-allow-host-audio`. Captures drive one bounded answer
turn through the shipped **#13 + #26** path — AV-012's `AnswerTurn` over AV-025's
`SpeechTransport`. The harness constructs no card provider and no writer, so **no
collection was opened, read or written at any point**; there was nothing to back up or
reset.

Three sessions, 62 attempts, all in
[`captures.jsonl`](evidence/live-20260916/captures.jsonl) in order, including the ones
that produced nothing. Every phrase spoken was an accepted answer or the reference answer
of the slot's card, at the operator's normal pace; nothing was read badly on purpose.

| Session | Attempts | Clean captures | Misrecognitions | Environment faults | Other |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 (records 1–8) | 8 | 1 | 0 | 6 | 1 operator cancel |
| 2 (records 9–17) | 9 | 8 | 2 | 1 (a colliding second invocation crashed the process) | — |
| 3 (records 18–62) | 45 | 38 | 2 | 2 | 4 `noMatch` (all on the bare word "Five"), 1 first-screen timeout |
| **Total** | **62** | **47** | **4** | **9** | |

Two of session 3's clean captures (attempts 8 and 18, the long reversal paraphrase) came
back with text that differs from the phrase, and the operator **did not attest** to having
said the phrase; the tool refused both, as it must — a misspoken phrase is not a
recognizer error. They are in the ledger and count as nothing.

Every clean capture reported confidence `absent` and a Done-to-final time between 620 and
902 ms, consistent with AV-013's measured band. On this route, every one of them would
have needed the learner's explicit acceptance before grading.

**A capture on a dead microphone is an environment fault, not a recognition result**, and
none of the nine is recorded as a failure of the pinned route. Session 1's six were the
operator speaking into the 220 Hz tone before any microphone check existed; that effort
was lost to the host, and `capture.py` now refuses to capture when the microphone does not
read live. Session 3 spent its whole budget on captures instead (see below) and still met
the tone twice, on the second open of a fresh boot.

### The audio failure, diagnosed

The owner asked why the microphone dies after a capture. It is the emulator's host audio
backend, and it is not fixable from this side of the seam.

**What happens.** Each time the guest opens the microphone the emulator creates a host
input voice. After a small number of opens the backend logs

```
coreaudio: Could not initialize record
coreaudio: Could not set audio format change listener
coreaudio: Reason: kAudioHardwareIllegalOperationError
Failed to create voice `virtio-snd-mic0'
```

and the guest receives a generated full-scale 220 Hz sine — the goldfish HAL's fallback,
the same one AV-040 and AV-042 diagnosed — for the rest of that boot.

**How often.** An unattended re-open test, ten opens per boot with nobody speaking:

| Gap between opens | Live opens before failure |
| ---: | ---: |
| 1 s | 2 |
| 8 s | 4 |
| 30 s | 3 |

The budget does not depend on the gap, so no settle delay helps. Across the attended
sessions it ranged from **1 to 6** opens per boot.

**Where.** In `audio/coreaudio.c` of `platform/external/qemu` (branch `emu-master-dev`),
`coreaudio_init_base` calls `AudioObjectAddPropertyListener` on the input device for every
voice it creates, to follow sample-rate changes; the two log lines above are its
`coreaudio_logerr2` on that call failing. `coreaudio_fini_base` stops the device, destroys
the IOProc and marks the device unknown — and **never calls
`AudioObjectRemovePropertyListener`**; the string does not occur in the file. Every open
therefore leaks a listener whose client pointer is a voice struct the emulator then frees,
and a later registration fails. Our transport releases the microphone correctly and the
guest HAL behaves; the default input device is the built-in microphone, so device
switching is not involved. `-audio <backend>` accepts any value silently and surfaced no
alternative backend. The fix is a teardown change in the emulator, which is not this
project's code.

**What was done about it.** The per-attempt PCM preflight in `capture.py` is exact but
costs an open, so session 3's driver read the fault from the emulator's own log instead
and cold-booted the AVD proactively after every two captures: 45 attempts cost 23 cold
boots and the operator was asked to speak into a dead microphone twice rather than
nine times. The runbook records both the budget and the procedure.

### Finding: the 6-live-misrecognition criterion is costly on this route

Recorded as evidence for the owner, not acted on. This card's acceptance criteria are
unchanged; the owner chose on September 16, 2026 to stop capture at 4 and report the run
incomplete.

A misrecognition is the recognizer returning **wrong words**. A correct transcript is not
one, and `noMatch` — an empty result — is not one either. Pooling every attested spoken
capture this project has recorded on a live microphone:

| Source | Successful captures | Genuine misrecognitions |
| --- | ---: | ---: |
| AV-006 native STT | 10 | 1 — "two" → "you" |
| AV-013 live run | 4 | 0 |
| AV-042 probe | 3 | 1 — "green blue red" → "You red" |
| AV-017 session 1 | 1 | 0 |
| AV-017 session 2 | 8 | 2 — `257`, a truncation |
| AV-017 session 3 | 36 | 2 — `route` for "round", `in` for "and" |
| **Pooled** | **62** | **6 (9.7%)** |

The rate is not uniform across cards. The two arithmetic phrasings and "green blue red"
produced **zero** misrecognitions in more than forty attempts between them — the three
slots still open are exactly those cards — while the longer rules and sorting sentences
produced all four. At the pooled rate the two answers still needed take roughly twenty
more clean captures, at one to three per cold boot; on the cards actually open it is
plausibly far more.

The criterion is not unreasonable in principle. It is what makes the STT category real
evidence rather than invention, and the four mistakes it produced are exactly the
interesting ones — a digit collapse, a truncation, and two single-word substitutions,
none of which the fuzzy rule or a synthetic corruption would have supplied. But on this
route it is bounded by operator time and an emulator defect, not by engineering.

Options for the owner, in the order they seem worth considering:

1. **Accept 4 live plus 2 recorded for this card** and say so in the report, keeping the
   three open slots `live-pending` for a later session on a fixed emulator.
2. **Redefine the open slots' cards.** The criterion is per-corpus, not per-card; moving
   the three open slots to the sorting and rules cards would fill them in a fraction of the
   attempts, at the cost of the STT category no longer covering all four cards.
3. **Run another session** once the emulator's audio teardown is fixed upstream or a
   different host is available, where the per-boot budget is not the limiting cost.

Changing an acceptance criterion is the owner's call and was not made here.

## Quota

Zero reservations consumed. The AI path has not run, so nothing was spent, and the
ledger — the shipped `QuotaLedger`, writing
`av017-quota-ledger-rule-only.jsonl` — records `reservedTotal: 0`.

When the live pass runs it will reserve one request per answer the rules do not match,
which on the current corpus is **50 of 57** — exactly the default 50-per-UTC-day limit,
with no headroom for #18's retries. The harness opens a fresh grading session every 14
requests so it stays inside the shipped 30-request session cap **rather than raising
it**. The daily limit is the decision to record when the pass runs: raise
`-Pav017.dailyLimit` explicitly or spread the run across two UTC days, and say which.
Free route only; no paid fallback.

## What this does not establish

- **Nothing about the AI path.** It has not been run. The report shows it as `not-run`,
  which is deliberately distinct from an abstention and is never scored as one.
- **Nothing about grading quality overall.** The rule path carries 12% of this corpus; the
  88% that matters most is unmeasured.
- **Nothing about recognition reliability.** Forty-seven clean captures on one host in one
  afternoon are a planning estimate, not a rate for the route.
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

1. Run one recorded AI pass on this corpus and score the held-out answers once against a
   fresh frozen configuration, in a new evidence directory.
2. Decide the criterion question above; the three open slots stay `live-pending` until it
   is decided or captured.

All three interim runs are kept in their ledgers rather than discarded, as the card
requires of every held-out run.
