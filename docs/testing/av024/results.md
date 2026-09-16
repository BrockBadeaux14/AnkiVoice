# AV-024: AnkiDroid adapter and review safeguards

Issue [#25](https://github.com/BrockBadeaux14/AnkiVoice/issues/25).
Branch: `codex/av024-ankidroid-adapter`. Review remains pending.

## Implementation

`AnkiDroidCardProvider` implements the AV-007 reads over the existing
`AndroidAccessPlatform`. It validates deck existence before interpreting an
empty schedule, resolves card/note/model identity, splits note fields using
0x1f, validates VoiceQA content, reads each offered card's button count and
resolves the selected deck's `maxTaken`. Unknown/malformed caps fail closed.
It does not use microphone permission to authorize card reads. Token-tagged
read overloads use the existing serial worker and main delivery executor.

`GuardedReviewWriter` ports the eight-step guard into pure Kotlin. It requires
an explicit current confirmation, compares both freshly offered and by-ID
snapshots, rechecks ratings and cancellation, dispatches once, and verifies
structural one-review evidence. No due-date arithmetic, scheduling prediction,
retry, journal, or automatic rating conversion is introduced. Ambiguous
acknowledgements and post-states remain outcome-unknown. `ReviewOutcome`
retains the evidence needed by #20. `FakeReviewWriter` lives in test fixtures.

The shell composition root now supplies the real provider in debug and release.
Start checks the selected deck off the main thread and discards stale results
after stop/background. The shell has no reference to a review writer. The locally signed release APK
was installed on the same isolated AVD: Start showed both **Card ready** and
**Queue exhausted**. Independent before/after snapshots show neither Start
changed card state or review history. Screenshots and UI trees are retained as
`release-card-ready` and `release-queue-exhausted`. One separately recorded
instrumentation review prepared the exhausted state.

Two specification/reference differences are resolved in favor of AV-007's
written algorithm, with the Python binding unchanged:

- The scheduled snapshot's content is compared as well as the by-ID snapshot's
  content. The Python writer only checked content in the latter read.
- When verification is supported, null/error acknowledgements also get a
  post-write read for evidence. They still cannot confirm, even if the card
  advanced. The Python writer returned unknown before that read.

## Environment and method

The dedicated `AnkiVoice_AV024` AVD matches AV-022: macOS 26.6.2 ARM64,
API 36 Google Play ARM64 image revision 7, 1080×2400/420 dpi, emulator
37.1.11.0, AnkiDroid 2.24.1/version 322401300. No Google or AnkiWeb login was
performed. Exact environment and APK hashes are in
[`environment.json`](evidence/environment.json).

Fresh AV-002 packages were generated for the test day/timezone. Each of the
four isolated state packages suspends other cards in a fresh host copy, leaving
the target's identity, content, stored state and seeded history unchanged.
Each rating case resets by native collection import before testing. Read-only
SQLite copies of the collection and WAL provide independent review-log evidence.
The application uses only the public provider. See the [runbook](runbook.md).

## Validation

The 16-case matrix covers ratings 1/2/3/4 on new, learning, relearning and mature
review cards. All 16 settled **confirmed** and added exactly one review with the
intended card and rating. Existing history and unrelated cards are checked by
the evidence validator.

| Additional case | Verified result |
| --- | --- |
| Raw rating 0 and 5 | ratingRejected; zero dispatches and reviews |
| 98,765 ms elapsed | Submitted unchanged; 60,000 ms stored cap |
| Deleted card | cardNotFound; no write |
| Deleted deck | deckMissing; no write |
| Disabled API component | apiDisabled; no write |
| Revoked database permission | accessDenied; no write |
| Queue rebuild withdraws held card | staleIdentity; no write after rebuild |
| Async capability/next/by-ID reads | Three matching-token callbacks on main thread |

The local Android checks cover module boundaries, core/adapter/shell JVM tests,
debug assembly, release assembly and lint. JVM coverage includes every identity
and stored-state field, all VoiceQA content, confirmation/cancellation, rating
withdrawal, all fake write anomalies, and every CardProvider failure before and
after dispatch. The 189 Python tests (including the new retained-evidence check) and AV-006 evidence validation
pass. JVM totals are 94 core, 55 adapter and 40 shell tests, with no failures.
The retained-evidence validator passes 281 assertions. Local executions do not claim a remote GitHub Actions run.

### Stored-time observation

AnkiDroid reconstructs a running timer from `time_taken`; time can advance before
its scheduler stores the review. One matrix row and one initial discovery row
stored 12,346 ms for a submitted 12,345 ms. The adapter submits the measured value
unchanged. Offline validation bounds stored time by the measured dispatch duration
and the deck cap. The above-cap test must store exactly 60,000 ms.

`expectedStoredTimeMs` remains AV-007's nominal `min(submitted, cap)`. Its exact
comparison helper can therefore be false for a real uncapped timer increment.
The app cannot query revlog and never uses that helper to confirm a review.
This is evidence of provider timer overhead, not a unit conversion or lost review.

### Retained setup failures

The initial smoke began before import completed and returned deckMissing with
no write. The first matrix stopped on an exact-time assertion after observing a
1 ms timer increment. Both remain under `initial-smoke*` / `timing-discovery*`;
the completed matrix uses fresh resets and separately measured dispatch bounds.
The first file-picker navigation also stopped before any review; its collection
snapshot is retained as `picker-setup-before-reset.json`. Android also rejected a
shell component toggle; the completed API-disabled case used AnkiDroid's own
setting. The stale-case picker initially showed a grid with the limits package
offscreen; switching to list view completed the import without an extra review.

## Limits

Evidence is confined to this pinned emulator/build. Physical devices and other
AnkiDroid builds remain unverified. The freshness check cannot close a race
between the final read and update: the provider has no transaction, compare-and-
write, idempotency key or stable collection-generation token. Identical IDs and
fields after restore do not prove collection continuity. Unknown outcomes need
explicit reconciliation; process-death journaling is #20 and sync behavior is #28.

Full session orchestration, speech/grading integration and user-facing review
confirmation remain #14/#27. The shell only checks readiness and cannot submit.
