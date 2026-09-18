# AV-043: free-route reply check corrected, paid grading fallback added — offline complete, live evidence PENDING

Issue [#66 — Fix the free-route reply check and add a budgeted paid grading fallback](https://github.com/BrockBadeaux14/AnkiVoice/issues/66).
Branch `codex/av-043-paid-grading-fallback`. September 16, 2026, America/Chicago.
Decision: the dated [AV-006 addendum](../../decisions/0006-speech-and-grading-providers.md#addendum--september-16-2026-av-043-a-paid-grading-fallback-within-a-daily-cap).
Reproduce with [the runbook](runbook.md).

> **Route order reversed on September 17, 2026 by
> [AV-050](https://github.com/BrockBadeaux14/AnkiVoice/issues/81) D.6, at the owner's
> direction.** This page describes free-first, with the paid route reached only on a free
> refusal, which is what AV-043 built and what its evidence covers. The shipped order is now
> **paid first, free as the backup**: an AI grading request spends the owner's credits by
> default. The daily budget and the per-request hold are unchanged and still bound the
> spend; what changed is which route the budget is spent on first, and that a spent budget
> now hands the turn to the free route rather than ending AI grading for the session. This
> page is left as the record of what was built and measured; the current order is in
> `GradingRoute.ORDER` and in [AV-050's results](../av050/results.md).

**Status, stated plainly.** The correction, the paid route, the budget, the settings and
disclosure changes, the harness extension and the offline suite are complete and
reproducible with no network. The three live layers — the bounded model spike, the
re-recorded AV-017 AI pass and the pinned-AVD check — **have not been run**: each needs
the owner's OpenRouter key, which was not present on the host that implemented this card
(no key file, no environment variable), and two of them can spend money. The pinned paid
model below is therefore the **leading candidate from the public endpoints listing, not a
measured choice**, and the card's spike exit criterion is still open. Nothing here
proposes automatic acceptance; every rating still needs explicit confirmation.

| Deliverable | Status |
| --- | --- |
| `FreeRoute.replyCheck` accepts the listing's tag **and** provider name; nothing hard-codes the name | **Complete** — `FreeRouteGuardTest`, `FreeRouteRegressionTest` |
| Regression test over the recorded AV-017 `Liquid` reply and all 24 AV-006 replies | **Complete** — the shipped comparison refuses every one; the corrected one accepts every one |
| Paid route in `:provider` behind the same `Grader` contract, free first, paid only on refusal, unavailability, timeout or failure | **Complete** — `PaidRoute`, `GradingProvider.request(…, route)`, `SemanticGrader` |
| `QuotaLedger`: per-UTC-day spend, holds, charges, `BUDGET_EXHAUSTED`, route-scoped stops, restart durability, day reset | **Complete** — `QuotaLedgerTest` |
| Daily cap (USD, default $1.00, $0 disables) in private settings and the allowance card, with today's spend and the paid stop | **Complete** — `ProviderControllerTest`; screen not yet captured on the AVD |
| Disclosures: what may cost money, the cap, a stop never rates a card; `ProviderModule.routeDescription` | **Complete** |
| Bounded model spike on the tuning 20, one model pinned with measured cost and latency | **Tooling complete; NOT RUN** — needs the owner's key; candidates and commands below |
| Re-recorded AV-017 AI pass under a new freeze | **NOT RUN** — needs the owner's key; commands in the [runbook](runbook.md#3-re-record-the-av-017-ai-pass) |
| AV-006 addendum, `android/README.md`, AV-017 runbook and results updated | **Complete** |
| Offline JVM tests with no network | **Complete** — 113 provider tests, 14 app controller tests; see [Validation](#validation) |
| Pinned-AVD check: one free request, one paid request with the spend shown, one cap refusal | **NOT RUN** — needs the owner's key typed into the app |

## The finding and the correction

AV-017's one live pass recorded the pinned free endpoint as
`Liquid | liquid/lfm-2.5-2.6b-20260811:free` with tag `liquid/fp8` and `provider_name`
`Liquid`, and the first grading reply as `model: liquid/lfm-2.5-2.6b:free`,
`provider: Liquid`, `usage.cost: 0`, `finish_reason: stop`, carrying a well-formed
two-key grade. The shipped check compared `provider` with `FreeRoute.PROVIDER`, the tag,
and refused it as `costNotVerified`; the ledger then stopped the day. AV-006's
`openrouter-grade-b.json` and its pass-2 twin carry `provider: "Liquid"` on all 24
attempts, so the field had read that way since the route was chosen, and AI grading had
been inert in every shipped build.

The correction keeps the design and fixes the comparison. `FreeRoute.priceCheck`, which
already matched the endpoint by tag and passed live, now returns the identities the
listing states for that endpoint — the tag and, when listed, the `provider_name` —
and `GradingProvider` keeps them for the session and passes them to `replyCheck`, which
accepts a reply reporting either. Without a listing only the tag is accepted, which is the
pre-AV-043 behaviour and is asserted as such. A reply from another model or provider, with
a nonzero cost, or with no cost is still refused, and the zero-cost verification is
unchanged. `FreeRouteRegressionTest` reads
[`ai-20260916/transcript.jsonl`](../av017/evidence/ai-20260916/transcript.jsonl),
[`openrouter-grade-b.json`](../av006/evidence/openrouter-grade-b.json) and
[`openrouter-grade-b-pass2.json`](../av006/evidence/openrouter-grade-b-pass2.json) exactly
as committed and drives both comparisons over them.

## The paid route

Free first, paid only afterwards. `GradingRoute.ORDER` is `FREE, PAID`; `SemanticGrader`
tries the routes in that order, giving each one #18's attempt and single retry, and
`GradingProvider.request(…, route)` refuses what a route may not do:

| | Free route | Paid route |
| --- | --- | --- |
| Pin | `liquid/lfm-2.5-2.6b:free` through `liquid/fp8` | **`deepseek/deepseek-v4.1-flash` through `deepseek`** — the owner's choice of September 17, 2026; provisional, see the spike below |
| Listed prices | $0 / $0 | **Pinned at the peak rate:** $0.30 per million prompt tokens, $1.20 per million completion tokens. The endpoint's base rate is half that — $0.15 / $0.60 — and it doubles on weekdays 01:00–04:00 and 06:00–10:00 UTC (public listing, September 17, 2026) |
| Envelope | AV-006's, unchanged | The same: `only=[tag]`, `allow_fallbacks=false`, `require_parameters=true`, temperature 0, `json_object`, 1,024-token cap; `max_price` set to the pinned prices per million so a repriced endpoint is declined, not paid |
| Before a session | Zero prices on the listing | The endpoint is listed at or below the pinned prices (a per-request price must be zero, a reasoning price at most the completion price, a cache-read price at most the prompt price); the cap is above $0; one request's ceiling fits under today's cap |
| Before a request | Session and daily request limits | The same limits, then the request's ceiling held against the cap |
| After a reply | Pinned model, listed identity, cost exactly zero | Pinned model, listed identity, a numeric `usage.cost`, which is charged |
| Refused reply | Route stopped for the day (`COST_NOT_VERIFIED`) | Refused as a label; the reported cost — or the ceiling, when none was reported — is charged; the route is off for the session |

The ceiling is every prompt token up to 4,096 and every completion token up to 1,024 at
the pinned prices: **$0.0024576** per request for the pinned model, against AV-006's
measured ~295 prompt tokens per request. It is an upper bound, not an estimate — and with
a time-varying endpoint it is a generous one, because the pin is the peak rate and an
off-peak request holds the same amount before being charged what the reply reports. The
learner sees the paid route's failure when it dispatched and the free route's when it did
not, and a paid label is bound to the transcript revision and mapped exactly like a free
one. One key serves both routes, so a rejected key turns both off; a 402 or 429 stops only
the route it happened on. Worst case, a turn dispatches four requests and takes about 80
seconds, and a session in which every turn fails on both routes exhausts the 30-request
session cap in seven turns; the harness now opens a fresh session every seven requests for
the same reason.

### The budget

`QuotaLedger` records paid reservations with their hold and `charge` entries with the
reply's reported cost. Today's spend is every charge recorded today plus the hold of every
paid reservation made today that has no charge yet, so a timeout, a crash mid-flight or an
unreadable reply counts at its ceiling because the money may have been spent. When a
request's ceiling would take the UTC day past the cap the ledger writes a
`BUDGET_EXHAUSTED` stop for the paid route and refuses before dispatch; the free route is
untouched, and a new UTC day clears the spend and the stop like the request counter. Stops
carry the route they apply to; a ledger written before this card reads as free-route
entries, and the request-limit stops still apply to everything — both asserted in
`QuotaLedgerTest`. `$0` turns the paid route off without recording a stop: off is not
exhausted.

The cap lives in the private settings as `daily_cap_usd`, dollars and cents from `$0` to
`$10.00`, default `$1.00`. The allowance card shows the spend beside the request counters,
the paid route's own stop, a field for the cap, **Check the routes** (both pre-session
price checks, reserving nothing) and one test request per route. The disclosure gained
**What may cost money**, built from the pinned constants so it cannot describe a route the
build does not ship, and states that a stop never rates a card.

## The spike: candidates chosen, measurement pending

The card bounds the spike to two or three OpenRouter models on the **tuning 20 only**. On
September 16, 2026 the public endpoints listings (`/api/v1/models/<id>/endpoints`, no key
needed) gave these single-tag endpoints that support `response_format`, `temperature`
and `max_tokens`:

| Candidate | Endpoint tag | Prompt / completion, USD per million tokens | Why |
| --- | --- | --- | --- |
| `openai/gpt-4.1-nano` | `openai` | 0.10 / 0.40 | One first-party endpoint, 99.99% uptime over 30 minutes, strict JSON mode; **pinned from September 16 until September 17, 2026** |
| `google/gemini-2.5-flash-lite` | `google-ai-studio` | 0.10 / 0.40 | The same price from a second first-party provider; the `/flex` tier is half price but queued |
| `mistralai/ministral-8b-2512` | `mistral` | 0.15 / 0.15 | The cheapest completion price on the shortlist from a first-party endpoint |

`google/gemma-4-26b-a4b-it` — AV-006's grader A, which its free route 429'd before it
could be measured — is listed paid through `deepinfra/fp8` at 0.07 / 0.34 and is the
natural substitute if one of the three is refused by the price check. `openai/gpt-5-nano`
was excluded because it does not accept `temperature`, and multi-provider open models
because a pin is one endpoint tag.

At the September 16 prices the whole spike — 20 tuning answers, at most two dispatches
each, on three candidates — was bounded by 3 × 20 × 2 × $0.0008192 ≈ **$0.10** of holds. At
the pin's current peak rate the same shape holds 3 × 20 × 2 × $0.0024576 ≈ **$0.30**, and is
still expected to cost well under $0.05 in reported charges. The spike tooling:

- `-Pav043.spike=<model>@<tag>` makes `GradingEvaluationTest` run the paid candidate on
  its own, through the shipped provider and grader, and **refuses any split but tuning**
  (`tests/test_av043_paid_route.py` and `tools/av043-qa/validate.py` guard the same rule
  over the evidence);
- [`tools/av043-qa/spike.py`](../../../tools/av043-qa/spike.py) compares the recorded
  runs — labels returned, label agreement, AV-017's three rates, cost per request from
  the ledger's own charges, p50/p95 latency — and writes `comparison.json` and
  `comparison.md` for this page.

**Exit criterion, still open.** When the spike runs, one model is pinned in `PaidRoute.kt`
with its listed prices and this page records the comparison table and the reason; if none
returns a usable two-key label, the paid route ships with the cap default at `$0` and the
finding is raised on #27 and #29. Until then the pin is the owner's choice below, and
`tools/av043-qa/validate.py` fails if the code and this page disagree about it.

## The pin moved on September 17, 2026, at the owner's request

The owner asked for `deepseek/deepseek-v4.1-flash` — *DeepSeek: DeepSeek V4.1 Flash* — in
place of `openai/gpt-4.1-nano`. It is pinned through DeepSeek's own endpoint tag
`deepseek`, which the public listing gave as the only occurrence of that tag, at 99.999%
uptime over 30 minutes, supporting `response_format`, `temperature` and `max_tokens`.

**This is a choice, not a measurement.** No grading quality was compared: the AV-043 spike
is still unrecorded, and nothing here says the new model grades as well as the old one, or
well enough. The exit criterion above is unchanged and still owns that question.

Two things the swap forced, both recorded rather than papered over:

- **The endpoint charges by the hour.** $0.15 / $0.60 per million off peak, doubling to
  $0.30 / $1.20 on weekdays 01:00–04:00 and 06:00–10:00 UTC, as a `pricing.overrides` array
  on the endpoint. The pin is the **peak** rate, so the pinned prices bound a request
  whenever it lands. That raises the per-request ceiling from $0.0008192 to **$0.0024576**,
  three times what the previous pin held. At the $1.00 default cap that is ~406 requests a
  day rather than ~1,221 — well above the 50-request default daily limit, so the limit and
  not the cap is still what stops a runaway session. Off-peak requests hold the peak
  ceiling and are then charged what the reply reports, which is the conservative direction.
- **`PaidRoute.priceCheck` could not read an override at all.** It walked every key in the
  endpoint's `pricing` and refused on anything that was not a number, so a `pricing.overrides`
  array refused the route outright — the paid route would have been dead on arrival. The
  guard now holds **every** window to the same pin rather than working out which is in
  force: the listing is read once per session while a request may be sent minutes later, and
  a price guard that reasoned about the clock would be a second, disagreeing source of truth
  about the time. A window it cannot read still refuses the route rather than being skipped,
  and the hour and weekday keys inside a window are not read as money. `PaidRouteTest` covers
  all of that, including the endpoint's own listing as it stood on the day, and fails without
  the guard change.

The candidates below were the September 16 shortlist and are kept as the record of what was
compared then. They were not re-measured for this swap.

<!-- av043:spike:begin — replaced by tools/av043-qa/spike.py output when the spike is recorded -->
_Spike not yet recorded._
<!-- av043:spike:end -->

## Validation

Offline, with no network, on September 16, 2026 (Gradle 9.3.1 on Android Studio's JBR
25.0.2, macOS ARM64):

| Check | Result |
| --- | --- |
| `:provider:testDebugUnitTest` | 113 tests, 0 failures: `FreeRouteGuardTest` (10), `FreeRouteRegressionTest` (4), `PaidRouteTest` (8), `QuotaLedgerTest` (17), `GradingProviderTest` (24), `SemanticGraderTest` (32), `ProviderBoundaryTest` (1), `GradingEvaluationTest` rule-only (2), and the unchanged replay, JSON, diagnostics and credential tests |
| `:app:testDebugUnitTest` | `ProviderControllerTest` (14) with the cap, spend and stop cases; the rest of the app suite unchanged |
| `checkModuleBoundaries :core:test assembleDebug`, then `:ankidroid:testDebugUnitTest :provider:testDebugUnitTest :speech:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug` | Both `BUILD SUCCESSFUL`: module boundaries follow AV-022; 312 `:core`, 61 `:ankidroid`, 52 `:speech`, 113 `:provider` and 79 `:app` tests, 0 failures; debug, androidTest and release APKs assembled; lint ran clean |
| `python -m unittest discover -s tests` | includes `test_av043_paid_route` (17 guards) and the routes-aware `test_av017_evaluation` session-cap check |
| `tools/av017-qa/validate.py`, `tools/av043-qa/validate.py` | pass; the AV-043 guard notes that no spike evidence exists yet |

What the offline suite establishes, case by case, as the card asks:

- a free reply is accepted by name and by tag, and a foreign provider, a nonzero cost and
  an absent cost are refused — over fixtures and over the recorded evidence;
- the paid route is not attempted while the free route succeeds;
- the paid route is attempted after each free-route refusal or failure class: the guard
  refusing a reply, the route unavailable at session start, two timeouts, two invalid
  replies, a 429; and it has its own single retry;
- the cap: the ceiling is held before dispatch, the reported cost replaces it, a reply
  with no cost is charged the ceiling, a timeout keeps its hold, the day stops at
  `BUDGET_EXHAUSTED` before the cap is passed and never touches the free route, a spent
  budget blocks the paid route at session start without a price check;
- restart durability of spend and stops, the UTC-day reset, and ledgers written before
  this card;
- `$0` disables the paid route without a price check or a stop, and the learner sees the
  free failure;
- no grading path reaches a writer: `ProviderBoundaryTest` scans every compiled
  `:provider` class for a review writer, transport, intent or outcome in any public
  signature or supertype, and the Python guard scans the sources.

## What this does not establish

- **Anything about the paid model's grading quality or real cost.** The pin is a listing
  choice; the spike measures it. Its listed prices can change, which the pre-session price
  check refuses rather than pays.
- **The AI path's held-out rates.** AV-017's measurement remains the 100% abstention of
  the build as it was; the re-recorded pass under a new freeze is pending and is appended
  to [AV-017's results](../av017/results.md#re-recording-under-av-043--september-16-2026)
  when it exists.
- **A graded study turn on the device.** The study session still uses on-device rules
  only (`RuleOnlyGrader`); wiring `SemanticGrader` into the session is #68 (AV-045). On
  the device both routes are exercised end to end from the settings screen's test
  requests, which is what the pinned-AVD check in the runbook records.
- **The cap refusal on a real day of use.** The smallest nonzero cap, `$0.01`, is about
  twelve ceilings of the pinned model, so the refusal is reached on the AVD by spending
  past it with test requests, not by a single request.
- Physical devices, other languages, real decks, and OpenRouter's or OpenAI's retention,
  which the disclosure says were not established here.

## Next

1. Run the three live layers of the [runbook](runbook.md) with the owner's key, in
   order: the spike, then the pin decision and this page's table, then the re-recorded
   AV-017 pass under a new freeze, then the pinned-AVD check with its screenshots and
   ledger copies. Record the total spend for each.
2. Note the re-recorded pass's outcome on #19, and, if no candidate returns a usable
   two-key label, raise the finding on #27 and #29 and ship the paid route with the cap
   default at `$0`.
