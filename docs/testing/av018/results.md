# AV-018: Session journal implemented; live process-loss verification complete

Issue [#20 — Implement review commit tracking and recovery](https://github.com/BrockBadeaux14/AnkiVoice/issues/20).
Branch `codex/av-018-review-commit-tracking`. Review remains pending.

The journal port, entry model and reconciliation policy are in `:core`; the durable
file-backed store is in `:app`, on an I/O executor of its own. **Both live cases passed on
September 16, 2026 UTC**: a real `am force-stop` between the durable journal write and the
single dispatch resolved `failed` with nothing written, and a real kill between dispatch
and settle resolved `outcome-unknown` — and reconciliation added no second review in
either case.

## Implementation

`org.ankivoice.core.journal` holds the whole policy and no Android type:

| Part | What it owns |
| --- | --- |
| `JournalEntry`, `JournalPhase`, `JournalResolution` | The entry model and its four phases: `dispatching`, `settled`, `reconciled`, `unreadable` |
| `JournalStore` | The storage port. `append` is durable on return; `readLines` never drops a line it cannot parse |
| `ReviewJournal` | The append-only fold, the settle rules and the retention bound |
| `JournalReconciler` | The startup reconciliation table, and only it may resolve an unsettled entry |
| `JournaledReviewWriter` | The seam: journal, flush, call the guarded writer once, settle from what it returned |
| `JournalRetention` | 50 settled entries or 7 days, whichever is smaller, and the 2,000-character transcript cap |

`:app` supplies `FileJournalStore`, which appends and `fsync`s before returning and
replaces the file through a temporary during pruning, and `JournalAccess`, which runs every
journal call on a dedicated I/O executor and returns only immutable results to the main
thread. `ShellApplication` wires them; `ShellController` runs reconciliation **before the
first card is offered** and stops the start on an unknown outcome.

`JournaledReviewWriter` wraps AV-024's `GuardedReviewWriter` rather than changing it, and
AV-013's `ReviewSession` is untouched — a test asserts that its `reconcile(...)` is still a
one-argument, learner-reported method, which is a different thing from this card's startup
pass and is not implemented in terms of it.

### What it records, and what it still does not

Recorded: card and note identity, ordinal, deck, the proposed rating, elapsed ms, the
session/turn/attempt token, the AV-012 transcript revision, **the settled transcript text
for that revision**, the journalled `CardState` pre-state, monotonic and wall-clock
timestamps, the phase, and on settle the `ReviewOutcome` state, reason, acknowledgement and
post-state.

Never recorded: card content, reference answers, accepted answers, audio, prompts or
grading reasons. A `:core` test asserts that none of the demo card's six fields reaches the
file, and an `:app` test asserts that no journal surface — `summary()`, `toString()` or a
reconciliation notice — puts transcript text into an AV-020 diagnostics bundle. The
transcript lives in the journal file and nowhere else.

The disclosure moved with the footprint. `DISCLOSURE_STORED_ON_DEVICE` now states that the
answer text is stored, for how long, that it is excluded from backup and device transfer,
and that clearing app data deletes it. Because AV-020's grading disclosure is gated on a
saved key and the journal records transcripts whether or not grading is configured, the
same text is also shown unconditionally in an **Kept on this device** card. The app must
not disclose a narrower footprint than it has.

### The reconciliation table, as implemented

| Observation after re-reading the journalled card by ID | Resolution |
| --- | --- |
| Identity and stored state byte-for-byte as journalled | `failed` — provably no write landed |
| A consistent one-review transition from the pre-state | `outcome-unknown` |
| Any other change, or a changed identity | `outcome-unknown` |
| Card missing, deck missing, access denied, API disabled, read failure or a throwing provider | `outcome-unknown` |
| The journal line itself is truncated or corrupt | `outcome-unknown`, and the line is kept |

No branch retries, replays or compensates for a write. The only automatic resolution is
`failed`, and `confirmed` is unreachable from this path by construction — AV-004 measured
that reps and time cannot attribute a competing native or sync write to this caller, so
`reps + 1` after a restart proves that *a* review happened, never that AnkiVoice made it.

**One honest limit.** The table's "content changed" cannot be checked, because checking it
would mean storing card content, which this card is forbidden to do. Identity and stored
state are compared instead. That does not weaken the `failed` branch: it is the unchanged
stored state that proves no review was recorded, and editing a note does not write one.

### Retention, measured rather than assumed

Kept regardless of age or count: the current session, every unsettled entry, every
unreadable line, and every unknown outcome the learner has not acknowledged. Pruning
applies only to resolved history from other sessions, oldest first.

`JournalSizeTest` measures the worst case with transcript text included, at the 50-entry
bound and the 2,000-character cap, and fails if a format change moves it past the recorded
ceiling of 700,000 bytes:

| Transcript shape | Per entry | 50 settled entries |
| --- | --- | --- |
| Plain ASCII | 2,718 bytes | 135,982 bytes |
| Words and punctuation | 2,718 bytes | 135,982 bytes |
| Four-byte UTF-8 throughout | 4,718 bytes | 235,982 bytes |
| Every character escaped (the absolute ceiling) | 12,718 bytes | 635,982 bytes |

The cap is what makes that a bound: a recognizer transcript is short, but a typed
correction has no length of its own, so the journal cuts the stored text and records that
it did. An eight-times-over-long transcript produces the same file size. The live run's own
two-record file was **775 bytes**.

## Live verification — September 16, 2026 UTC

[Environment and APK hashes](evidence/environment.json), and per-case
[before](evidence/before-dispatch-summary.json) /
[after](evidence/after-dispatch-summary.json) summaries. The AVD is AV-042's
`AnkiVoice_AV005` (`sdk_gphone64_arm64`, API 36, fingerprint
`google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094`), AnkiDroid 2.24.1, on the
disposable `AV002 Baseline` collection (8 VoiceQA notes, 12 pre-existing revlog rows). The
collection was backed up before the run.

Both cases journalled the same card, 1789534470381, rating 3, revision 2, transcript
`five blocks`, pre-state `reps 1 / type 1 / queue 1 / due 1789563936 / ivl 0`.

| Case | Kill landed | Entry after the kill | Resolution | Reviews added by the kill | Reviews added by reconciliation |
| --- | --- | --- | --- | --- | --- |
| `before-dispatch` | between the flush and the dispatch | readable, `dispatching`, transcript intact | **`failed`** | 0 | **0** |
| `after-dispatch` | between the dispatch and the settle | readable, `dispatching`, transcript intact | **`outcome-unknown`** | 1 | **0** |

Independent read-only SQLite snapshots of the collection and WAL, taken before the strand,
after the kill and after reconciliation:

| Case | revlog before | after the kill | after reconciliation | integrity |
| --- | --- | --- | --- | --- |
| `before-dispatch` | 12 | 12 | 12 | ok / ok / ok |
| `after-dispatch` | 12 | 13 | 13 | ok / ok / ok |

The single row the `after` case added is `cid 1789534470381, ease 3, time 4200` — the
review the kill interrupted, written by AnkiDroid itself. Reconciliation read the card back
as `reps 2 / type 2 / queue 2 / due 92 / ivl 1`, recognised a consistent one-review
transition, and refused to claim it:

> Card 1789534470381: this app cannot prove it saved rating 3. The card shows one more
> review than it did, which this app cannot attribute to itself. Open AnkiDroid and check
> the card before studying it again. AnkiVoice will not resubmit it.

That entry stayed blocking and unacknowledged, so no card would have been offered.
`tools/av018-qa/validate.py` re-derives all 44 of these checks from the retained snapshots
offline, and runs with the Python suite.

## Offline coverage

JVM only, no emulator and no network. This card adds 53 tests: 39 in `:core`
(`ReviewJournalTest` 23, `JournalReconciliationTest` 14, `JournalSizeTest` 2) and 14 in
`:app` (`SessionJournalTest` 9, `ShellJournalTest` 5). Module totals after this card are
274 `:core`, 65 `:app`, 61 `:ankidroid`, 52 `:speech` and 63 `:provider` tests — 515 in all,
with no failures. The Python suite is 223 tests, including the retained-evidence check.

Covered: the entry is readable from storage *before* the writer is entered; a crash between
persist and dispatch; a crash between dispatch and settle; duplicate settles; a settle for
an unknown entry; a stale settle arriving after reconciliation; a refused replay leaving no
spurious entry; a pre-dispatch rejection settling as `failed`; a truncated line; a corrupt
line; an orphaned settle record; each reconciliation row, including every `CardProvider`
failure and a throwing provider; a competing native write between persist and restart; six
single-field stored-state mutations; every `WriteAnomaly` settling once with one dispatch
and no replay; a cancelled intent journalling nothing; retention pruning by count and by age; the four
categories pruning may never drop; the transcript round trip with quotes, newlines and
tabs; the transcript cap; and the assertion that no content-free surface exposes the
transcript.

## What this does not establish

- Two kills are not a reliability estimate. They are one instance of each specified window.
- The kill windows are chosen by the instrumentation, not by chance. A real crash can land
  anywhere; what these cases show is that the two windows the design reasons about behave
  as specified when a process genuinely dies in them.
- Emulator evidence establishes nothing about a physical device, and nothing here was run
  under sync. AnkiDroid sync handoff and stale sessions belong to #28.
- The `outcome-unknown` notice was verified through the instrumentation and through
  `ShellJournalTest`, not by a person reading it on the shipped study surface. #15 owns the
  command surface it will finally live on and #27 the study surface.
- Nothing here promises that AnkiDroid's native Undo survives process loss, and the app
  does not claim it does.
- `:speech:lintDebug` fails on `main` for an unrelated `AudioRecord` permission warning in
  AV-025's code. It is untouched by this card and still fails; every other module's lint,
  the module-boundary check and all JVM suites pass.
