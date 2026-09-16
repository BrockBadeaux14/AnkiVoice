# AV-010: Card eligibility and bounded skipping

Issue: [#11](https://github.com/BrockBadeaux14/AnkiVoice/issues/11).

The shell now checks eligibility before reporting **Card ready**, shows each
rejected card's ID and repair reason, and stops after five consecutive rejected
cards with counts by reason. No capture, grading, review submission, bury,
suspend or reschedule is part of this path. A new Start creates a new provider
and clears the previous rejection report; deck selection clears it too.

## Classification and locale

`core/eligibility/Eligibility.kt` contains the pure classifier over an already-read
card. It checks the VoiceQA model, the six ordered field names and value count,
card template ordinal, nonblank Prompt and ReferenceAnswer, and Language syntax.
The adapter still owns field parsing; it supplies the original layout and value
count so duplicate, missing, extra and reordered fields cannot disappear during
map conversion. The adapter's ordinary `nextCard` and `readCard` validation uses
the same classifier.

Every rejection retains the full `CardIdentity` and an `ANNOUNCEMENT` utterance
in the session language. Reasons distinguish unsupported note type, blank
required field, layout mismatch, malformed Language and unsupported extra card
template. The shell displays the utterance text; speech playback remains #26.

BCP 47 syntax is checked with `Locale.Builder.setLanguageTag`. Empty Language
uses the existing `cardLanguage` fallback. Nonempty values are checked as stored,
including rejecting surrounding whitespace, so eligibility and existing utterance
builders agree on the locale. Syntactically valid private, grandfathered,
script/region and extension tags pass without inspecting installed voices.
Unavailable voices remain #26's responsibility. Utterance/grading builders are
reused unchanged: Prompt alone is the question, ReferenceAnswer is the reveal,
Extra is optional elaboration and cannot enter `GradingContext`.

## Advancing a non-consuming queue

Repeated `schedule` reads with `limit=1` return the same head until a review
changes it. The initial branch's scripted failures concealed that problem.
The pinned AnkiDroid 2.24.1 [provider implementation](https://github.com/ankidroid/Anki-Android/blob/9f579c10bb151146728220729c510acbbd8faba7/AnkiDroid/src/main/java/com/ichi2/anki/provider/CardContentProvider.kt#L323-L384)
also accepts `limit` and passes it to `getQueuedCards(fetchLimit=limit)`.

`EligibilityCardProvider` adds a read-only candidate path without changing the
five AV-007 interfaces. After classification, the adapter excludes the observed
note/ordinal in memory. Each subsequent read requests a prefix of at most the
number of exclusions plus one and takes its first unexcluded entry in the
scheduler's order. The five-card stop bounds consecutive candidate reads; this
is not a deck-wide preflight. Only the last observed candidate can be excluded.
Exclusions belong to the provider instance and never survive a new shell session.

Ordinary `nextCard`, `readCard` and capability reads use the same exclusions, so
later review freshness checks still refer to the offered card. They do not cache
card content or scheduling state. `supportsSkip` remains false: the existing
user-requested skip policy is unchanged. AV-010's automatic eligibility filtering
is an in-memory read policy, not a scheduler operation.

The offer counter resets on a studiable card. Exhaustion of the currently
scheduled candidates after exclusions is separate from reaching five rejections.
Null cursors, malformed schedule rows, invalid answer buttons, missing identities
and other provider failures pause; no text matching converts them to a guessed
blank-field rejection. A repeated rejected identity also pauses rather than
counting the same card five times. Discarded asynchronous replies cannot restore
readiness or publish rejection reports after Stop.

## Validation

Local validation passed on the macOS host with Android Studio's JBR 25:
115 `:core`, 61 `:ankidroid` and 46 `:app` JVM tests (222 total), plus all
200 Python tests. Debug and release assembly, module boundaries and Android
lint passed. Lint reported 16 warnings concerning dependency/SDK pins, the app
icon and existing KTX suggestions; it reported no errors. `git diff --check`
passed. No live collection or network grading call was used for these checks.

Run from the repository root:

```sh
cd android
./gradlew --console=plain checkModuleBoundaries :core:test :ankidroid:testDebugUnitTest :app:testDebugUnitTest assembleDebug :app:assembleRelease :app:lintDebug
```

Run the repository fixture/contract checks from the repository root:

```sh
.venv/bin/python -m unittest discover -s tests -v
```

The JVM checks cover:

- AV-002's actual `valid-control`, Basic, Cloze and missing-reference fixtures;
  canonical field-name drift; malformed and well-formed language tags; field
  count/order/duplicate failures; and the existing utterance/context separation.
- All rejection reasons, unchanged fake collection order and full card states,
  zero calls to the fake review writer's transport, mixed-reason summaries,
  the five-card cap, counter reset, exhaustion and provider failures.
- The real adapter over a stable, non-consuming fake schedule: bounded prefix
  requests, advancement to the second card, five distinct rejected identities,
  retained freshness behavior, per-card ratings and fresh-session exclusions.
- Shell status, rejection reports, report reset and stale asynchronous delivery.

## Limits

No emulator run is required by #11 and none is claimed here. The prefix route is
backed by the pinned source and JVM integration tests; #29 still owns integrated
runtime acceptance. This shell remains a readiness preview. Speech output and
full ReviewSession orchestration remain #26/#14, and actual voice availability is
not established by syntax validation. The prior single-reviewer and collection
replacement limitations from AV-024 still apply.
