# AV-043 runbook: the free-route correction and the paid fallback

Issue [#66 — Fix the free-route reply check and add a budgeted paid grading fallback](https://github.com/BrockBadeaux14/AnkiVoice/issues/66).

Four layers, deliberately separate:

| Layer | Needs | Can spend | What it establishes |
| --- | --- | --- | --- |
| 0. Offline suite | nothing | no | The correction, the route order, the guards and the accounting, against fake transports and the recorded evidence |
| 1. Model spike | your OpenRouter key | yes, cents | Two or three paid candidates on the tuning 20; the pin |
| 2. Re-recorded AV-017 pass | your key | yes, within the cap you pass | The AI path's held-out rates through the corrected free route, with the paid fallback as shipped |
| 3. Pinned-AVD check | your key, typed into the app | yes, cents | One free request, one paid request with the spend shown, one cap refusal, on the device |

> **Never put the key in a file, a shell argument, a screenshot or an evidence JSON.**
> Export it in the shell that starts Gradle for layers 1 and 2, and type it into the app
> for layer 3. `tools/av043-qa/validate.py` and `tools/av017-qa/validate.py` fail if
> anything key-shaped appears anywhere in the evidence.

Every paid request holds its ceiling — $0.0008192 for the pinned model — in the ledger
before it is sent and charges the reply's reported cost afterwards. The runs below record
their spend in `quota.spend_usd` from the ledger's own charges; report it as measured,
never as estimated.

## 0. Offline

```sh
.venv/bin/python -m unittest tests.test_av043_paid_route tests.test_av017_evaluation tests.test_av016_grading
.venv/bin/python tools/av043-qa/validate.py
cd android
./gradlew --console=plain :provider:testDebugUnitTest :app:testDebugUnitTest
```

`FreeRouteRegressionTest` reads AV-017's recorded transcript and AV-006's grading
evidence as committed; `GradingEvaluationTest` in its default rule-only mode opens no
socket and reports the paid route it would use without touching it.

## 1. The model spike, on the tuning 20 only

One candidate per run, `<model>@<endpoint tag>`, each into its own directory under one
spike evidence root. The harness turns the free route off, runs the paid candidate through
the shipped provider and grader, and **refuses any split but `tuning`**. Gradle's daemon
may not carry a variable exported after it started, so use `--no-daemon`.

```sh
cd android
export OPENROUTER_API_KEY=...     # you type this; it is never written down
for candidate in \
  openai/gpt-4.1-nano@openai \
  google/gemini-2.5-flash-lite@google-ai-studio \
  mistralai/ministral-8b-2512@mistral; do
  out="$PWD/../docs/testing/av043/evidence/spike-$(date -u +%Y%m%d)/$(echo "$candidate" | tr '/' '_' | tr '@' '_')"
  ./gradlew --no-daemon --console=plain :provider:testDebugUnitTest \
    --tests '*GradingEvaluationTest*' \
    -Pav017.mode=record -Pav017.split=tuning \
    -Pav043.spike="$candidate" \
    -Pav017.dailyCapUsd=0.25 \
    -Pav017.out="$out"
done
```

The spike's price ceiling per token defaults to $1 / $4 per million
(`-Pav043.spikeMaxPromptUsd`, `-Pav043.spikeMaxCompletionUsd`); a candidate listed above
it is refused by the same price check the app runs, which is a finding, not an error.
`-Pav017.dailyCapUsd=0.25` bounds one candidate's run to a quarter; the expected reported
charge per candidate is under a cent.

Then compare, with no network:

```sh
.venv/bin/python tools/av043-qa/spike.py --evidence docs/testing/av043/evidence/spike-<date> --write
```

It prints the table for [results.md](results.md) — labels returned, label agreement,
AV-017's three rates, cost per request, p50/p95 latency — and names any candidate that
returned no usable two-key label. **The pin is a decision, recorded on the results page
with its reason**: update `PaidRoute.PINNED` to the chosen model, tag and listed prices,
paste the table between the `av043:spike` markers, and run
`tools/av043-qa/validate.py`, which fails if the code and the page disagree. If no
candidate returns a usable label, ship the paid route with the cap default at `$0` and
raise the finding on #27 and #29.

Replay a candidate's run offline afterwards to confirm the transcript reproduces it:

```sh
./gradlew --console=plain :provider:testDebugUnitTest --tests '*GradingEvaluationTest*' \
  -Pav017.mode=replay -Pav017.split=tuning -Pav043.spike="<the same candidate>" \
  -Pav017.out="<the same directory>"
```

## 2. Re-record the AV-017 AI pass

`FreeRoute.kt`, `PaidRoute.kt` and `GradingRoute.kt` are part of AV-017's frozen
configuration, so the corrected build needs a **new evidence directory and a new freeze**;
`ai-20260916` stays as the record of the build as it was. Follow
[AV-017's runbook](../av017/runbook.md#3-live-one-recorded-ai-pass) with a new directory
and the cap the pass should run under — the shipped default `1.00`, or `0` to measure the
free route alone:

```sh
cd android
export OPENROUTER_API_KEY=...
./gradlew --no-daemon --console=plain :provider:testDebugUnitTest \
  --tests '*GradingEvaluationTest*' \
  -Pav017.mode=record \
  -Pav017.out=$PWD/../docs/testing/av017/evidence/ai-<date>-av043 \
  -Pav017.dailyLimit=200 \
  -Pav017.dailyCapUsd=1.00
.venv/bin/python tools/av017-qa/score.py \
  docs/testing/av017/evidence/ai-<date>-av043/run-record.json \
  --evidence docs/testing/av017/evidence/ai-<date>-av043 --freeze --label held-out-av043
```

Then replay and score once, as AV-017 layer 4 says, append the rates per AV-017's rubric
to [AV-017's results](../av017/results.md#re-recording-under-av-043--september-16-2026),
and note the outcome on #19. The held-out 40 is scored once per freeze; the paid model
does not touch it outside that single pass.

## 3. The pinned AVD

Host: the pinned macOS ARM64 evidence host and `AnkiVoice_AV005`. Build and install as
[AV-020's runbook](../av020/runbook.md#1-build-and-install) does, then:

1. Open AnkiVoice, scroll to **AI grading**, type your key, **Save key**, read the
   disclosure — it now has a **What may cost money** section — and acknowledge it. Capture
   `disclosure.png` and confirm no part of the key is on screen.
2. In **Grading allowance**, press **Check the routes**. Capture `routes-check.png`: the
   line names the free endpoint and the paid endpoint, or says which is off and why.
3. Copy the ledger, then press **Send one free test request**. Capture `free-request.png`;
   the line says the free route answered at a verified zero cost.
4. Press **Send one paid test request**. Capture `paid-request.png`: the line says the paid
   route answered and what it cost, and the allowance card's **Paid fallback** line shows
   that spend against the cap. Copy the ledger again; the new `reserve` line carries
   `"route":"paid"` and a `hold`, and the `charge` line the reported cost.
5. Set the **Daily paid budget** to `0.01`, save, and send paid test requests until the
   line says the budget is used up — the smallest nonzero cap is about twelve ceilings —
   or set it to `0` to show the route off. Capture `cap-refusal.png`: no rating is proposed
   anywhere, and the free request beside it still answers.
6. Kill the app, reopen it, and confirm the spend and the stop survived. Copy the ledger a
   last time.

Save the captures and ledger copies under `docs/testing/av043/evidence/avd-<date>/`,
record the total spend from the last ledger copy, and run:

```sh
.venv/bin/python tools/av043-qa/validate.py
```

A graded **study turn** through either route is not part of this check: the study session
grades with on-device rules only until #68 (AV-045) wires `SemanticGrader` in.
