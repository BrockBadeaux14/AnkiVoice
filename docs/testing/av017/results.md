# AV-017: advisory grading evaluation — corpus and harness complete, measurement INCOMPLETE

Issue [#19 — Evaluate advisory grading on held-out answers](https://github.com/BrockBadeaux14/AnkiVoice/issues/19).
Branch `codex/av-017-advisory-grading-eval`. September 16, 2026, America/Chicago.
Reproduce with [the runbook](runbook.md).

**Status: the corpus, the harness, the rubric and the discipline are complete and
reproducible. The measurement is not.** The rule path is measured in full over the 55
answers that are complete. The AI path has not been run, and 5 of the 9 STT-mistake
answers are still awaiting live capture — 2 of the required 6 live misrecognitions are in
hand — so **this run is reported as incomplete rather than passed**. That is AV-017's own
rule, computed by `score.py` rather than asserted here.

This card gates nothing. It defines no target error rate, authorises no automatic
acceptance, and changed neither grader.

## What is complete

| Deliverable | Status |
| --- | --- |
| 60-answer labeled corpus, split fixed in the file | **Complete** — [`fixtures/grading/av017-corpus.json`](../../../fixtures/grading/av017-corpus.json) |
| Human label + written rationale per answer, before any grader ran | **Complete** for all 55 filled answers |
| Offline harness over the shipped graders, no emulator | **Complete** — `GradingEvaluationTest`, 3 modes |
| Record/replay reproducibility | **Complete** — 5 round-trip tests, no network |
| Scoring rubric and rate definitions | **Complete** — `tools/av017-qa/score.py` |
| Frozen-configuration and held-out discipline, enforced | **Complete** — `score.py` refuses to score held-out unfrozen |
| Quota accounting through AV-020's ledger | **Complete** — wired; 0 reservations so far, because the AI path has not run |
| Rule-path measurement | **Complete** over 55 answers |
| **6 live STT misrecognitions** | **Not met — 2 captured**, 17 attempts. See below |
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

### STT sourcing: 4 of 9 filled, 2 of them live

| Answer | Split | Spoken | Recognizer returned | Label | Source |
| --- | --- | --- | --- | --- | --- |
| `av017-stt-mistake-t1` | tuning | "It puts two first, then five, then seven." | `it puts you first then five then seven` | partial | [AV-006 `native-stt-online-remainder.json`](../av006/evidence/native-stt-online-remainder.json) |
| `av017-stt-mistake-t2` | tuning | "Green, blue, red." | `You red` | incorrect | [AV-042 attempt 4](../av042/results.md) |
| `av017-stt-mistake-h5` | held-out | "It puts two first, then five, then seven" | `it puts two first then` | partial | **live**, [attempt 16](evidence/live-20260916/captures.jsonl) |
| `av017-stt-mistake-h6` | held-out | "Two five seven" | `257` | partial | **live**, [attempt 12](evidence/live-20260916/captures.jsonl) |

The two reused recordings are placed in the **tuning** split: the card keeps AV-006's
results as regression context only, and putting previously inspected output in the
held-out 40 would make it held-out evidence in name alone. `validate.py` fails if a
`recorded` answer ever appears in the held-out split.

Two of the labels are judgements and are recorded as such. **`257`** is the recognizer
collapsing three spoken number words into a digit string: read as digits it carries the
right values in the right order, read as one number it expresses no ordering at all, and a
reader cannot tell which. It contradicts nothing, so it is labeled partial rather than
incorrect. **`it puts two first then`** is a truncation after the first "then"; it names
only the first element of the required order and is labeled exactly as the authored
"It puts two first." is.

The remaining 5 slots are `live-pending`: they carry no text and no label, and the harness
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

55 of 60 answers scored (19 tuning, 36 held-out). Configuration `d2df08447ce5`, frozen
before the held-out answers were scored and recorded in
[`held-out-runs.jsonl`](evidence/rule-only-20260916-2/held-out-runs.jsonl). An earlier
interim run against `d502cd71abdf` (53 answers, before the two live captures changed the
corpus hash) is kept in
[its own directory](evidence/rule-only-20260916/held-out-runs.jsonl), as the card
requires of every held-out run.

| Split | Path | n | False acceptance | False rejection | Abstention | p50 | p95 |
| --- | --- | ---: | --- | --- | --- | ---: | ---: |
| Tuning | rule-only | 2 | 0 / 0 (n/a) | 0 / 2 (0.0) | 0 / 2 (0.0) | 0.114 ms | 0.630 ms |
| Tuning | AI | — | **not run** | — | — | — | — |
| Held-out | rule-only | 5 | 0 / 0 (n/a) | 0 / 5 (0.0) | 0 / 5 (0.0) | 0.050 ms | 0.066 ms |
| Held-out | AI | — | **not run** | — | — | — | — |

A denominator of 0 is reported as `n/a`, never as a rate of 0: **no answer labeled
incorrect ever reached a rule match**, so the rule path's false-acceptance rate is
undefined on this corpus rather than measured at zero. That is the honest reading.

### What the rule path actually did

**It fired on 7 of 55 answers (13%) and left 48 (87%) to the AI grader or an explicit
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

Neither live STT answer reached a rule: `257` is one word against a three-word target and
`it puts two first then` matches nothing, so both fell through to the (unrun) AI path.

Two observations, recorded rather than acted on:

- **The one-letter fuzzy rule never fired.** Not one answer in the corpus differs from a
  target by a single slip in a long word — the four real misrecognitions in hand are a
  substitution of a number word, a dropped phrase, a truncation and a digit collapse, none
  of which is the shape the fuzzy rule accepts. Its behaviour on this corpus is therefore
  **untested**, not clean.
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

Two sessions, 17 attempts, all in
[`captures.jsonl`](evidence/live-20260916/captures.jsonl), including the ones that
produced nothing. The operator attested "prompt audible" and "I said the expected phrase"
on every attempt that reached the attestation screen.

**Session 1, 21:01–21:07 UTC** — before the microphone preflight existed:

| # | Slot | Operator action | Result | Microphone |
| ---: | --- | --- | --- | --- |
| 1 | `stt-live-1` | Cancel | `cancelled`, no transcript | not captured |
| 2 | `stt-live-1` | Done | `there are five blocks`, 678 ms | **live** (rms 1,275) |
| 3–8 | `stt-live-2` … `stt-live-7` | Done | `noMatch` code 7, 629–667 ms | **220 Hz tone** (rms 23,179–23,181) |

**Session 2, 21:28–21:34 UTC** — with the preflight, three cold boots, every capture on a
live microphone:

| # | Slot | Spoken | Result | ms | Microphone |
| ---: | --- | --- | --- | ---: | --- |
| 9 | `stt-live-6` | — | **process crashed** before the first screen; no capture | — | stale file — see below |
| 10 | `stt-live-6` | It puts two first, then five, then seven | `it puts two first then five then seven` — correct | 647 | live (rms 2,979) |
| 11 | `stt-live-3` | Green, blue, red | `green blue red` — correct | 661 | live (rms 3,561) |
| 12 | `stt-live-7` | Two five seven | **`257` — misrecognition** | 625 | live (rms 2,697) |
| 13 | `stt-live-2` | Five blocks | `five blocks` — correct | 646 | live (rms 1,692) |
| 14 | `stt-live-4` | No, round does not satisfy square | `no round does not satisfy Square` — correct | 660 | live (rms 3,029) |
| 15 | `stt-live-5` | It is not valid because it is not square | `it is not valid because it is not Square` — correct | 652 | live (rms 2,727) |
| 16 | `stt-live-6` | It puts two first, then five, then seven | **`it puts two first then` — truncated** | 680 | live (rms 1,936) |
| 17 | `stt-live-3` | Green, blue, red | `green blue red` — correct | 664 | live (rms 2,126) |

Every `doneToFinalMs` in session 2 sits in 625–680 ms, inside AV-013's measured band, and
confidence came back `absent` throughout, as AV-013 and AV-014 both recorded for this
route: every one of these transcripts would have needed the learner's explicit acceptance
before grading.

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
The operator did speak into all six; that effort was lost to the host, which is why
`capture.py` now runs an unattended five-second check first and **refuses to capture**
when the microphone does not read live. Session 2 hit the tone three more times, at
attempts 2, 4 and 7, and lost nothing to it: the preflight caught it and the AVD was
cold-booted each time. The host device survives one to three captures per boot.

**Attempt 9** is a second `capture.py` invocation that collided with the batch's own,
crashing the target process before its first screen. It captured nothing. Its microphone
reading is the *previous* attempt's tone, left on the device by attempt 8; `capture.py`
now clears that file before every run so a crashed attempt reads as `no-samples` rather
than inheriting a verdict. Recorded as the environment fault it is.

### Finding: the 6-live-misrecognition criterion is costly on this route

Recorded as evidence for the owner, not acted on. This card's acceptance criteria are
unchanged.

A misrecognition is the recognizer returning **wrong words**. A correct transcript is not
one, and `noMatch` — an empty result — is not one either. Pooling every attested spoken
capture this project has recorded on a live microphone:

| Source | Successful captures | Genuine misrecognitions |
| --- | ---: | ---: |
| AV-006 native STT | 10 | 1 — "two" → "you" |
| AV-013 live run | 4 | 0 |
| AV-042 probe | 3 | 1 — "green blue red" → "You red" |
| AV-017 session 1 | 1 | 0 |
| AV-017 session 2 | 8 | 2 — `257`, truncation |
| **Pooled** | **26** | **4 (15.4%)** |

At that rate the four still needed take roughly **26 more successful captures** in
expectation, at one to three captures per cold boot of the host audio device — a session
on the order of an hour or two, with a long tail. The criterion is not unreasonable in
principle; it is what makes the STT category real evidence rather than invention, and
session 2 shows that the mistakes it produces are exactly the interesting ones (a digit
collapse and a truncation, neither of which the fuzzy rule or a synthetic corruption would
have supplied). But it is the one thing standing between this card and a complete
measurement, and it is operator time rather than engineering.

Options for the owner, in the order they seem worth considering:

1. **Run it.** One more dedicated capture session, budgeting for ~26 successful captures
   and ~10 cold boots. The runbook and the preflight make it mechanical.
2. **Lower the live floor** from 6 to 2, the number in hand, and say so in the report.
   That leaves the held-out STT category at two answers.
3. **Widen what counts.** `noMatch` is currently not a misrecognition. It is the commonest
   degradation on this route and trivially available — but it produces no text to grade,
   so it tests AV-012's failure path, not grading.

Changing an acceptance criterion is the owner's call and was not made here.

## Quota

Zero reservations consumed. The AI path has not run, so nothing was spent, and the
ledger — the shipped `QuotaLedger`, writing
`av017-quota-ledger-rule-only.jsonl` — records `reservedTotal: 0`.

When the live pass runs it will reserve one request per answer the rules do not match,
which on the current corpus is **48 of 55**. The harness opens a fresh grading session
every 14 requests so it stays inside the shipped 30-request session cap **rather than
raising it**. The daily limit is the decision to record: 48 requests fits the default
50-per-UTC-day limit only if at most two of them retry, so either spread the run across
two UTC days or raise `-Pav017.dailyLimit` explicitly, and record which was done. Free
route only; no paid fallback.

## What this does not establish

- **Nothing about the AI path.** It has not been run. The report shows it as `not-run`,
  which is deliberately distinct from an abstention and is never scored as one.
- **Nothing about grading quality overall.** The rule path carries 13% of this corpus; the
  87% that matters most is unmeasured.
- **Nothing about recognition reliability.** Nine clean captures are not a rate for the
  route; the 15.4% above is a planning estimate pooled across four cards' evidence.
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

1. Capture the remaining 5 STT slots, or decide the criterion question above.
2. Run one recorded AI pass and score the held-out 40 once against a fresh frozen
   configuration, in a new evidence directory — the corpus hash changes again when the
   STT slots are filled, so neither current freeze will apply to it.

Both interim runs are kept in their ledgers rather than discarded, as the card requires of
every held-out run.
