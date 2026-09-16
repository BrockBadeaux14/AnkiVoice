# AV-007: Integration contracts and review lifecycle

- Issue: [#7 — Specify the integration contracts and review lifecycle](https://github.com/BrockBadeaux14/AnkiVoice/issues/7).
- Date: September 14, 2026. Status: ready for review; acceptance pending.
- Dependencies: #1, #4, #5 and #6 are closed. The [AV-004 capability matrix](../testing/av004-ankidroid-review-access.md)
  is the normative input; these contracts are bound to what it measured, not to an idealized API.
- Scope: specification and in-memory fakes only. No AnkiDroid transport, no provider
  SDKs, no UI. The real adapter is #25, the persistent journal is #20.

Anki keeps ownership of the collection and the scheduler. Nothing here computes an
interval, a due date or a queue; the session proposes one rating per card, commits it
once, and verifies what it can read back.

These contracts are language-neutral. The executable binding in this repository is
[`tools/av007_contracts.py`](../../tools/av007_contracts.py), whose Python names are
the snake_case spelling of the names used below.

## Scope and bounds

Covers the VoiceQA note type only: Prompt, ReferenceAnswer, RequiredConcepts,
AcceptedAnswers, Language, Extra. Cloze, media and arbitrary templates are out of
scope and belong to #30, #31 and #35.

AV-004 returned a **constrained go**. The released ContentProvider selects scheduled
cards and saves reviews, but the reviewed contract exposes **no revlog endpoint, no
transaction or idempotency key, and no atomic compare-and-write**, and an
`update_count` of `1` was observed **without** a saved review. Every rule below is
written to be safe under those limits rather than to work around them.

## Confirmed product decisions

User decisions, September 14, 2026. Downstream cards implement these; they do not
re-litigate them.

- **Voice-first with touch fallback.** Routine voice controls, touch controls and
  transcript edits are supported. Every rating requires an explicit learner
  confirmation; a suggestion, silence or elapsed time never submits. Transcript
  edits invalidate prior suggestions and confirmations. Action source and correction
  events are recorded so #29 can count manual interventions.
- **No skip.** AV-004 found no non-mutating skip operation. Bury and suspend both
  change scheduling. A skip request pauses or exits the session **without any write**.
  Skip is never emulated by rating, burying, suspending or reordering another queue.
  AV-010's later automatic eligibility policy is separate: it reads a bounded
  scheduled prefix and excludes rejected candidates in session memory without
  changing Anki's queue. See [AV-010](../testing/av010/results.md); the
  `supportsSkip` flag and this user-requested skip behavior remain unchanged.
- **Correction before commit only.** The learner may change a rating until it is
  submitted. After a confirmed commit the app offers no in-app correction: it directs
  the learner to AnkiDroid's native Undo, then stops the session and reloads. No
  programmatic or durable undo is promised.

## Identity and snapshots

Session and card identity is the tuple AV-004 proved readable:

| Element | Source | Notes |
| --- | --- | --- |
| card ID | `cards` | Primary key for re-reads and verification. |
| note ID | `schedule`, `cards` | Equal to the card ID in the AV-002 fixtures; never assume that in general. |
| deck ID | `cards` | Validated against the selected deck. |
| ordinal | `schedule`, `cards` | `0` for VoiceQA, which has one card per note. |
| model | `models` | Must be `VoiceQA`; any other model is `unsupportedNoteType`. |

**A scheduled card is a snapshot, not a reservation.** AV-004 force-stopped AnkiDroid
after one answer and the rebuilt queue replaced the offered card; raw submission of the
withdrawn card returned `1` and changed nothing. Therefore:

1. `ReviewWriter` re-queries the scheduled card and the card by ID **immediately
   before** the write, comparing the full identity tuple and stored state. A card
   withdrawn from the queue is stale even if its stored fields are unchanged.
2. Any difference in any element rejects the write as `staleIdentity`, before the
   write is attempted.
3. **A race still remains after this check.** With no transaction, idempotency key or
   compare-and-write available, the freshness check narrows the window but cannot
   close it. The post-write verification and the pause rule below are load-bearing.

Stored state is `reps`, `cardType`, `queue`, `due`, `intervalDays` and
`lastReviewTimeSecs`. `due` is scheduler-relative and its unit depends on the queue:
Unix seconds for intraday learning/relearning, collection-relative days for review or
day learning, a queue position while new. **These units are never interchanged**, so no rule here does
arithmetic on `due`; it is only compared for equality.

Each session also has an opaque, unique `sessionId`, with monotonically increasing
turn and operation numbers. These identify callbacks, not collection generations or
provider transactions. A restart gets a new session ID. The provider exposes no
stable collection-generation token; matching card fields cannot prove continuity
after a restore, sync or app switch.

## Capability flags

The session adapts by reading flags, never by branching on a device, an Android version
or an AnkiDroid build. Defaults are the AV-004 measurements for AnkiDroid 2.24.1.

| Flag | AV-004 value | Required behavior |
| --- | --- | --- |
| `permittedRatings` | `1, 2, 3, 4` for every tested state | Read **per card** from the offered card, not from this default. Offer only these. |
| `maxReviewTimeMs` | `60000` (deck `maxTaken` = 60 s) | Submit the true measured value; expect the stored value to be truncated to this. |
| `supportsPostWriteVerification` | `true` | When false, **every** commit resolves to `outcome-unknown`: there is no evidence a review landed. |
| `supportsSkip` | `false` | Pause or exit without a write. Never rate, bury, suspend or reorder to emulate a skip. |
| `supportsProgrammaticUndo` | `false` | Offer correction before commit only; hand off to native Undo afterwards, then stop and reload. |
| `supportsRevlogQuery` | `false` | Verification uses card fields only. The submitted review time is never read back in-app. |
| `supportsTransactions` | `false` | One write per intent. Never group or defer writes. |
| `supportsIdempotencyKey` | `false` | **Never automatically replay** an unconfirmed write; a replay cannot be deduplicated. |
| `supportsAtomicCompareAndWrite` | `false` | The freshness check is advisory; keep post-write verification and the pause rule. |

An empty permitted-rating range permits no submission. The time cap is a nonnegative
integer value in milliseconds, not a boolean: zero means storage caps the time to
zero. The adapter must resolve the selected deck's cap; an unavailable or malformed
cap is an access/card failure rather than permission to assume 60 seconds. These
defaults describe the fixture deck, not every collection.

## The five contracts

Each operation returns either its value or one enumerated failure. A failure is a
transport or provider fault and is **never** convertible into a rating.

### CardProvider

Reads the scheduled VoiceQA queue. Performs no writes.

| Operation | Input | Output |
| --- | --- | --- |
| `capabilities` | provider bound to a validated selected deck | capability flags, or a failure |
| `nextCard` | same deck binding | a scheduled card snapshot, **or** `queueExhausted`, **or** a failure |
| `readCard` | card ID | a scheduled card snapshot, or a failure |

| Failure | Meaning |
| --- | --- |
| `accessDenied` | Permission never granted or revoked; AV-004 saw `SecurityException`. |
| `apiDisabled` | Null cursor: “Enable AnkiDroid API” switched off. |
| `packageUnavailable` | Null cursor: AnkiDroid disabled or absent. |
| `nullCursor` | Null result whose cause is unknown. Do not infer disabled API, missing package or queue exhaustion. |
| `deckMissing` | The selected deck is gone. **Not** exhaustion. |
| `cardNotFound` | The offered card no longer exists. |
| `collectionChanged` | Replaced, restored or synced underneath the session. |
| `unsupportedNoteType` | The card is not VoiceQA. |
| `malformedCard` | Prompt or ReferenceAnswer is missing. |

`queueExhausted` is a **valid, empty result**, not a failure. A null cursor is a
different thing entirely, and so is a missing deck.
The adapter validates deck existence before interpreting a valid empty schedule,
since AV-004 found that an unknown deck may also produce an empty schedule. The
fake's collection represents this bound deck. Identity/content validation precedes
exposing the card; required VoiceQA fields and model are checked on reads.

### SpeechOutput

Speaks one utterance. Every utterance carries a purpose; the question purpose is
constructed from Prompt alone.

| Operation | Input | Output |
| --- | --- | --- |
| `speak` | operation token, utterance (purpose, text, language) | `PlaybackResult(token, failure?)`; success means playback completed |
| `cancel` | operation token | idempotent cleanup; invalidates playback, later completions are ignored |

Failures: `engineUnavailable`, `languageUnsupported`, `audioFocusLost`,
`playbackInterrupted`.

### SpeechInput

Captures one spoken answer.

| Operation | Input | Output |
| --- | --- | --- |
| `listen` | capture token, language tag | `CaptureEvent(token, kind, text, confidence, failure?)` |
| `cancel` | capture token | idempotent cleanup; later events are ignored |

Failures: `permissionDenied`, `recognizerUnavailable`, `recognizerError`,
`noSpeechDetected`, `listenTimeout`, `networkUnavailable`, `quotaExhausted`,
`noMatch`, `earlyClosure`, `lowConfidence`.

`noSpeechDetected` requires evidence of silence. Android's overloaded
`ERROR_NO_MATCH` is `noMatch`, whose cause remains unknown: it does **not** prove
silence, network loss or microphone failure. `earlyClosure` means capture ended
without a usable final. None is a wrong answer. The transcript is shown for learner
correction; the current accepted or corrected version is what `Grader` receives.

### Grader

Advisory semantic grading. Never returns or implies an Anki rating.

| Operation | Input | Output |
| --- | --- | --- |
| `grade` | `GradingRequest(operationToken, transcriptRevision, context)` | `GradingReply(request, result)`, where result is a label (`correct`, `partial`, `incorrect`, `uncertain`) and reason, or failure |
| `cancel` | grading request | invalidate the request; ignore later replies even if provider cancellation cannot stop computation |

Failures: `quotaExhausted`, `providerError`, `graderTimeout`, `unparsableResponse`,
`outputTruncated`.

A label maps to a **proposal**, not a commit: `correct` proposes Good, `incorrect`
proposes Again, and `partial` and `uncertain` propose nothing and ask for a spoken
or touch self-grade. AV-006 recorded a no-go for unattended rating; #19 evaluates
suggestion quality and does not waive explicit confirmation. When grading fails the
session pauses with no rating. An explicit `selfGrade(rating)` action resumes this
answer with a learner-selected proposal, which still needs confirmation. A transcript
edit is another explicit recovery route. No failure itself supplies a rating.

### Selected provider constraints

The [AV-006 decision](../decisions/0006-speech-and-grading-providers.md) selects the
installed Google local English TTS voice, native online-permitted English recognition,
and OpenRouter `liquid/lfm-2.5-2.6b:free` pinned to `liquid/fp8`, pass-2 instruction,
temperature 0, JSON output and 1,024-token cap. Grading remains advisory. Preserve
zero-price guards, no paid/provider/model fallback and no automatic retries. #17 owns
credentials and quota enforcement; #18 validates responses. A malformed, empty,
truncated or timed-out response produces a failure, never a recovered grade from a
partial payload. Manual/self-grade fallback remains available by explicit action.
The fixture schema's historical `initial_auto_ratings` names describe suggestions
only; they grant no permission for unattended writes.

### ReviewWriter

Commits one review and classifies the outcome. Its required algorithm is:

1. Reject a rating outside the ratings offered for this card. **A rejected rating is
   never converted to Again.**
2. Reject a negative elapsed time.
3. Require a current explicit confirmation bound to this intent, identity, rating and
   transcript revision. A missing or invalid event is `confirmationRequired`.
4. Re-query the scheduled card and card by ID. A read failure is `precommitReadFailed`;
   no write is attempted. A withdrawn scheduled card is stale.
5. Reject on any identity, stored-state or VoiceQA-content difference as `staleIdentity`.
6. Re-check the rating against the freshly offered ratings and re-check that the
   intent/confirmation was not cancelled while the reads ran.
7. Hand over **exactly one** write.
8. Verify by re-reading the same card; any interruption during write/verification
   makes the outcome unknown and keeps the session stopped.

| Operation | Input | Output |
| --- | --- | --- |
| `commit` | `ReviewIntent(cardSnapshot, rating, elapsedMs, token, transcriptRevision, confirmation)` | `ReviewOutcome(state, reason, failure?, acknowledgement?, preState?, postState?, submittedTimeMs?, expectedStoredTimeMs?, writeAttempted)` |

| Failure | Meaning |
| --- | --- |
| `ratingRejected` | Outside the ratings offered for this card, before or at commit. |
| `invalidReviewTime` | Elapsed time is not a nonnegative integer in milliseconds. |
| `staleIdentity` | Identity or stored state moved between the offer and the commit. |
| `precommitReadFailed` | The freshness read failed; the underlying provider failure is retained as its cause. |
| `writeRejected` | The provider returned an explicit zero and the card is unchanged. |
| `confirmationRequired` | Missing, stale, uncertain or mismatched explicit confirmation; no write attempted. |

The single-shot write itself is a separate `ReviewTransport` seam so the algorithm can
be specified, tested and reused independently of AnkiDroid. It is called **at most
once** per intent.

Invalid method ordering (such as replaying a terminal intent) is a caller contract
violation, rejected without a transport call; it is not a provider failure. The
Python binding raises `ValueError`. Cancellation before dispatch makes the pending
intent terminal `failed` with no write; cancellation after dispatch is never proof
that the write was cancelled.

## Transcript revisions, cancellation and explicit confirmation

- `partial` transcripts may be displayed but cannot grade, propose, confirm or submit.
- `final` transcripts require nonempty text, no accompanying failure and sufficient
  confidence according to the downstream policy. Low **or absent** confidence pauses
  for explicit retry/correction. Confidence is a policy classification, not a numeric
  threshold established by these fakes.
- `learner-corrected` transcripts are created only by an explicit edit action, never
  by a recognizer callback. They increment the transcript revision, cancel pending
  grading, clear the old suggestion and discard the old intent/confirmation. Even an
  edit back to the original words has a new revision.
- Tokens contain session ID, turn number and operation sequence. Each new operation
  receives a new token; replies must match the active token, phase and revision.
  Failures take precedence over any accompanying text. Duplicate final/reply callbacks
  and callbacks from previous turns or sessions are discarded.
- Pause, cancel/skip, background, lock and external interruption invalidate tokens
  **before** requesting speech/grader cleanup. Resume is explicit and obtains a fresh
  card. A stopped session requires reload/new session. No callback resumes it.
- `RatingConfirmation(token, identity, rating, transcriptRevision, source, final,
  confidence)` is a distinct learner event. Source is spoken or touch. A spoken
  command must be final and sufficiently confident; no partial, no-match, error,
  timeout, low/absent confidence, silence or answer transcript is a confirmation.
  Touch is an explicit gesture and has no recognition confidence requirement.
- Proposing a rating never creates a confirmation. Rating corrections issue a new
  intent token and clear confirmation, including when changed back. `confirm(event)`
  validates the event; `commit` is permitted only afterwards. A successful event
  authorizes exactly the current pending review, never a future turn.

The fakes serialize these callbacks deterministically and expose acceptance methods
so tests can deliver stale events and interruptions. Production #14/#26 must serialize
state changes and dispatch on an owned execution context; these types alone are not
a thread-safety mechanism. The writer never retries an ambiguous dispatch.

### Capture-policy ownership

[AV-005](../testing/av005-foreground-speech.md) had no human transcripts and proposed
a 15-second response window; AV-006's synthetic audio experiment proposed a
30-second capture ceiling and 5-second finalization deadline. Those are conflicting
proposals, not validated human-speech defaults. [#45](https://github.com/BrockBadeaux14/AnkiVoice/issues/45)
supplies the measured capture/finish/interruption policy to #13/#26. Waiting to think
and an active recognizer attempt must remain distinguishable. This specification
chooses no silence duration, retry count, confidence threshold or focus-loss duration.
Ordinary internal playback-to-capture handoff is not an external interruption; #45
must measure how to distinguish them. #23 chooses framework/ownership independently.

## Question, reveal and grading criteria

The rendered question and the reference answer are separate throughout.

| Surface | Fields | Rule |
| --- | --- | --- |
| Question audio | Prompt | The only field spoken before the answer. Never carries ReferenceAnswer or Extra. |
| Reveal audio | ReferenceAnswer | Explicit `reveal` after an accepted/corrected answer, including self-grade fallback. |
| Elaboration audio | Extra | Optional explicit reveal after an accepted/corrected answer. |
| Grading criteria | Prompt, ReferenceAnswer, RequiredConcepts, AcceptedAnswers | Plus the learner transcript. |

**Extra is never part of question audio and never part of the grading criteria.** The
grading context is a closed structure with exactly those fields, so Extra cannot reach
the grader by accident. Field content is study data and is never treated as
instructions to the grader.

## Ratings

- Permitted ratings are **what the provider offered for the current card**. AV-004
  observed four buttons for every tested state; that is an observation, not a constant.
- Ratings are read per card and re-checked immediately before the write.
- Out-of-range ratings are rejected. AV-004 submitted raw `0` and `5`; each returned
  `0` and changed nothing. **A rejection is never downgraded to Again**, and it never
  becomes a write of any other value.
- In the session, an unoffered rating leaves the correction window open so the learner
  can choose a permitted one. In `ReviewWriter` it is a `failed` review with no write
  attempted.

## Review time

- Measure elapsed milliseconds from a **monotonic clock**, from the end of the question
  audio until submission of the explicitly confirmed rating, including correction and
  confirmation time. Never a wall clock, never used for scheduling.
- Submit a nonnegative value. A clock that fails to advance yields `0`, which is valid.
- The deck's `maxTaken` **silently truncates** the stored value: AV-004 submitted
  98,765 ms and the collection stored 60,000 ms against `maxTaken=60` seconds.
- A capped read-back is an **expected transformation**. It is not evidence of a
  different unit and it is not a verification failure. Verification never inspects the
  review time: with no revlog endpoint the app cannot read it back at all, so the cap
  is only ever observed in offline evidence.

## The review lifecycle

| State | Meaning |
| --- | --- |
| `pending` | A rating is proposed and correctable. Nothing has been written. |
| `submitting` | Exactly one write has been handed to the provider. |
| `confirmed` | A post-write read showed a consistent one-review transition. |
| `failed` | No write landed, and that is provable. |
| `outcome-unknown` | A write may or may not have landed. |

### Transitions

| From | To | Condition |
| --- | --- | --- |
| — | `pending` | A rating is proposed after an accepted/corrected answer, from advisory grading or explicit self-grade. |
| `pending` | `pending` | Correction: the rating changes any number of times. |
| `pending` | `failed` | Precommit validation/confirmation failure or explicit cancellation. No write attempted. |
| `pending` | `submitting` | Explicit confirmation current, rating permitted, time valid, scheduled identity and stored state unchanged, single reviewer still active. |
| `submitting` | `confirmed` | `update_count` is 1 **and** the post-write read is a consistent one-review transition. |
| `submitting` | `failed` | `update_count` is 0 **and** the post-write read shows the card unchanged. |
| `submitting` | `outcome-unknown` | Everything else. |

A **consistent one-review transition** requires all of:

- the same identity tuple on the post-write read;
- `reps` incremented by exactly one;
- a positive, populated `lastReviewTimeSecs` that has not moved backwards;
- `cardType`, `queue`, `due` or `intervalDays` changed — scheduling moved;
- a valid post-review type/queue pair: learning `(1,1)` or `(1,3)`, review `(2,2)`,
  relearning `(3,1)` or `(3,3)`; never new, suspended or buried;
- a nonnegative interval and no known violation of the single-reviewer precondition.

Anki owns scheduling: the fake checks structural consistency and movement, never
computes a target interval/due date. AV-004 measured Easy transitions; the fake's
other ratings are stand-ins, not newly validated Anki scheduling. #25 must validate
the expected transition for the real adapter's supported cases; a transition it
cannot explain remains unknown. Any unexpected delta (including an update count
other than 0 or 1), any null or error response, and any unavailable
post-state is `outcome-unknown` — including an acknowledged write with an unchanged
card, which is exactly the case AV-004 observed.

### outcome-unknown

`outcome-unknown` **pauses the session and requires explicit reconciliation.** While it
stands, the following are prohibited:

- **No automatic replay** of the unconfirmed write. There is no idempotency key, so a
  replay could double-count a review.
- **No automatic advance** to the next card.
- **No success announcement.** Only a `confirmed` review may be announced as saved.
- **No automatic resume.** The learner reports what AnkiDroid actually shows; the app
  never infers it.

Recovery across process loss is out of scope here: **#20 owns the persistent journal**
and **#28 owns sync-handoff and stale-session validation**. The session specified here
deliberately keeps nothing across a restart.
Reconciliation records the learner's observation and requires a collection reload;
it never retroactively labels an unknown intent confirmed or replays it. Another
halt, skip request or late callback cannot clear the reconciliation obligation.

## Transport and provider errors are not answers

A microphone denial, a null cursor, a disabled AnkiDroid API, a recognizer failure and
an exhausted quota are faults in the app's own plumbing. None of them is evidence about
what the learner knows, and **none of them may produce a rating**.

| Distinction | Why it matters |
| --- | --- |
| Null cursor vs. empty queue | A null cursor is unavailable data with an optionally known cause; a valid empty queue in an existing deck means studying finished. The first pauses, the second ends normally. |
| Missing deck vs. exhaustion | A missing deck means the collection moved underneath the session. It stops rather than reporting a finished session. |
| No-match/silence vs. a wrong answer | `noMatch` preserves an unknown cause; proven `noSpeechDetected` also pauses. Neither is graded or rated. |
| Grader unavailable vs. incorrect | An exhausted quota falls back to a spoken self-grade. It never becomes Again. |

Failures that the learner can resolve — permission, API switch, engine, recognizer,
quota — pause and are resumable. Failures that invalidate the collection view —
`deckMissing`, `collectionChanged`, `unsupportedNoteType` — stop the session. A stale
identity/state/content rejection also stops and requires a reload before further study.

## Single active reviewer

This is a **precondition**, not an aspiration. `reps` and review time cannot attribute a
competing native or sync write to this caller, and no revlog endpoint is available to
disambiguate. Therefore app switching, process loss or resume, possible concurrent
modification, and synchronization each **stop the session** rather than reconcile
silently. When an unconfirmed write is outstanding at that moment, the stop also
requires reconciliation before studying resumes.

## The fakes

[`tools/av007_fakes.py`](../../tools/av007_fakes.py) provides one in-memory fake per
contract, plus a deterministic clock and a small VoiceQA collection built from the
AV-002 fixture examples. They run with no emulator, no network, no credentials and no
real clock, so #23, #25, #16, #20 and #21 can be developed and tested against the
contracts before any device work.

The fakes are **not a scheduler**. The state a review produces in them is a legible
stand-in; only the shape AV-004 verified is contractually meaningful. The fake
collection also records a review log that no contract may read — it is the offline
analogue of the revlog AV-004 inspected outside the app, and exists so tests can prove
that exactly one review, or none, was written.

[`tools/av007_scenarios.py`](../../tools/av007_scenarios.py) scripts 53 sessions: 19
named scenarios and one per enumerated failure mode, covering all 34. Regenerate the
committed transcripts with:

```sh
python tools/av007_scenarios.py --output docs/contracts/av007/transcripts.md
python -m unittest discover -s tests -v
```

The [transcripts](av007/transcripts.md) include the demonstrations this card owes:
a confirmed commit, a pre-commit correction, a stale-identity rejection, a capped
review time, an ambiguous acknowledgement resolving to `outcome-unknown`, a rejected
out-of-range rating, a skip that writes nothing, and the native-undo handoff.
They also exercise partial/early closure, learner correction, a stale grade,
spoken/touch confirmation, absent confirmation, manual fallback and interruptions
during playback/capture. `FakeReviewWriter` uses `GuardedReviewWriter` over
`FakeCardProvider` and `FakeReviewTransport` to exercise the same verification guards;
the other four contracts have named fake classes.

### Acceptance coverage

| Requirement | Executable evidence |
| --- | --- |
| Five contracts and every failure | Failure sweep; `ScenarioCatalogTests`, provider/speech/grader fakes and guarded fake writer |
| Full identity, snapshot freshness, permitted ratings | `IdentityAndStalenessTests`, `RatingTests`, `WriteGuardRegressionTests` |
| Question/reveal split; no Extra in grading | `QuestionAnswerSeparationTests`, explicit reveal test |
| Monotonic time, cap, false capabilities | `ReviewTimeTests`, `CapabilityTests`, confirmation-wait test |
| Five review states, ambiguity, no replay/advance/success | `ReviewStateMachineTests`, ambiguity/interruption regression tests |
| No skip, precommit correction, native Undo, single reviewer | `CapabilityTests`, `SingleActiveReviewerTests`, stopped-session regressions |
| Revisions, tokens, explicit confirmation, no-match, fallback | `ConfirmationTests`, `TranscriptAndCancellationTests`, named transcript scenarios |
| Selected provider restrictions and timing owners | Provider/capture-policy sections above; AV-006 payload/response tests remain in the full suite |

## What this does not establish

This is a specification and a set of fakes. It is not evidence about any device.

- No AnkiDroid transport, provider SDK or UI exists yet. #25 implements the adapter and
  must re-verify these rules against the real provider.
- The fakes cannot prove timing, threading, audio focus or Android lifecycle behavior.
- AV-004's evidence is one emulator, one AnkiDroid build, one Android version, a single
  client and no synchronization. Physical hardware, Bluetooth, phone calls, multiple
  clients and sync remain unverified; #28 and #31 own those boundaries.
- The remaining commit race is narrowed, not eliminated. No arrangement of these
  contracts can close it while the provider offers no atomic compare-and-write.
