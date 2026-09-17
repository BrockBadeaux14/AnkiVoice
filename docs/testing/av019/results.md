# AV-019: pre-commit exchange implemented; one live case of five

Issue [#21 — Make rating correction predictable](https://github.com/BrockBadeaux14/AnkiVoice/issues/21).
Branch `codex/av-019-rating-correction`. Review remains pending.

The exchange, the announcement, the re-prompt rule, the commit step and the outcome
handling are in `:core`; the writer wiring, the surface controls and the live harness are
in `:app`. **The offline layer passed on September 16, 2026**: 45 new JVM tests across
`:core` and `:app`, with no emulator and no network.

**The live layer is one case of five.** `confirmed` passed on the pinned AVD on
September 17, 2026, in the owner's own voice, and it carries the finding that matters most
for the card's decision 3: **a spoken confirmation executed and wrote one review.** The
other four are outstanding. Three of them were attempted first and proved nothing — the
harness could not yet take AV-019's own abstain path — and those attempts are kept in
[`evidence/inconclusive/`](evidence/inconclusive/README.md) rather than deleted.

## Implementation

`org.ankivoice.core.exchange` holds the whole policy and no Android type:

| Part | What it owns |
| --- | --- |
| `RatingSource` | The four sources a pending rating is announced with: `rule`, `ai`, `learner`, `none` |
| `RatingAnnouncement` | One Announced position as it was announced, including whether it was actually spoken |
| `ExchangeStep` | What one step did: `Announced`, `Reprompted`, `Committed`, `Untouched` |
| `PrecommitExchange` | The two positions, the announcement, the re-prompt rule, the single commit and the outcome |

`:app` supplies the surface and the wiring. `studyWriter` in `ShellApplication.kt` is the
one writer a study session may use — AV-018's `JournaledReviewWriter` around AV-024's
`GuardedReviewWriter`, with a `SettledTranscripts` that hands over the settled answer only
for the revision the intent was computed from — and the JVM tests call that same function,
so the wiring under test is the wiring that ships. `CommandController` routes every command
through the exchange and adds the self-grade, Next card, Undo-handoff and
learner-reported-reconcile controls; `MainActivity` renders them on AV-014's debug surface.

AV-013's `ReviewSession` is **unchanged**: `git diff` against `main` shows nothing under
`core/src/main/kotlin/org/ankivoice/core/session/`. The card's constraint — add the
announcement "without changing the binding-guarded `propose` and `correct` signatures" — is
met by not touching that file at all, so the 53-scenario conformance suite and the Python
binding it was ported from still describe the shipped session exactly.

AV-014's `CommandRouter` changed in three places, none of them behaviour: the class comment
that left the submitting step to #27, which the owner superseded on September 16, 2026; the
matching sentence on `confirm`; and the notice that confirmation returns, which used to end
"Nothing is written until the review is submitted" and would now be false at the moment the
write runs. The router still has no code path to `ReviewSession.commit`; the exchange above
it is what commits. AV-014's retained evidence quotes the old notice, and is left as the
record of what the app said that day.

### What the exchange owns, and what it deliberately does not

Owned: what Announced says, what a correction re-announces, what an unusable confirmation
is told, the commit an accepted confirmation runs, the announcement made from the returned
`ReviewOutcome`, and the post-commit handoff.

Not owned, and not rebuilt: the command vocabulary and its parsing (#15), the session
states, tokens and teardown (#14), the answer window and transcript states (#13), rule and
AI grading (#16, #18), the journal and its startup reconciliation (#20), and the guarded
write with its verification (#25). The exchange consumes an already-parsed `CommandOutcome`
and never reads an utterance.

### The Announced position is derived, not remembered

`PrecommitExchange.position` recomputes from the session on every read: it is null unless
the announcement's transcript revision is still the session's and a matching pending rating
is still open. A transcript edit, a retry, a pause, a skip, an interruption and a commit
therefore each retire the announcement without a line of bookkeeping — and an old
suggestion can never be re-announced against a new revision. `settled` does the same for
the outcome, so advancing to the next card cannot leave the previous card's verdict on
screen.

### Where the source comes from

Only the grader that chose the route can say which policy answered, so `StudyGrader.sourceOf`
reports it for the request it last answered and for no other, read on the grading worker in
the same step that took the reply. A rating whose provenance cannot be stated is not shown
at all: the exchange opens as an abstention instead, because AV-019 never presents a rating
without its source.

## Offline results — September 16, 2026

```
./gradlew checkModuleBoundaries :core:test :app:testDebugUnitTest
```

| Suite | Tests | Result |
| --- | --- | --- |
| `PrecommitExchangeTest` (`:core`) | 28 | pass |
| `CommandControllerTest` (`:app`) | 17 | pass |
| `StudyCompositionTest` (`:app`) | 12 | pass |
| Whole repository (`:core`, `:app`, `:ankidroid`, `:speech`, `:provider`, Python) | 678 JVM + 292 Python | pass |

What the 28 `:core` tests cover, in the card's own order: the announcement for each of the
four sources and for an abstention; correct-then-confirm re-announcing; the re-prompt-once
rule, including the second refusal that points at the touch control; confirm-then-commit
exactly once; a correction never writing; silence, a speech failure and a grading failure
each producing no write and no timeout path; a transcript edit invalidating both the pending
rating and the confirmation collected for it, with the new revision re-announced and the old
suggestion never re-announced; each of the three outcome classes announced correctly; the
learner-reported reconcile; and the Undo handoff stopping the session with no write. Two
more guard the announcement itself: it is refused while the microphone is open, and a
synthesizer fault leaves the pending rating intact rather than discarding it.

The `:app` tests add the surface fields, the abstain path through the self-grade control,
the outcome controls, and the two write-path properties this card had to wire: a confirmed
rating journalled before the write and settled from the outcome at the right rating, card
and revision; and a superseded revision's text never journalled against a newer rating.

## Live layer — September 17, 2026, one case of five

Five cases, one per cold boot, driven by `tools/av019-qa/run.py` through
`ExchangeInstrumentation` on the pinned `AnkiVoice_AV005` AVD against a disposable AV-002
collection. Three of them write a real review, deliberately.

| Case | What it must show | Status |
| --- | --- | --- |
| `confirmed` | one review, journal settled `confirmed`, the duplicate confirm not offered | **passed** |
| `corrected` | one review at the corrected rating, journal settled `confirmed` | outstanding |
| `correction-only` | no review, no journal entry | outstanding |
| `abandoned` | no review, no journal entry | outstanding |
| `undo-handoff` | one review, the session stopped, the card re-read afterwards | outstanding |

### `confirmed`, in the owner's voice

| | |
| --- | --- |
| Card | `1789411952536`, deck `AV002 Baseline` |
| Heard | "green blue red" — `final`, classified `sufficient`, raw score **0.972** |
| Announced | **Good**, source `rule`, answer version 1, spoken through `SpeechOutput` |
| Announcement | "Good is waiting, from an exact rule match on what I heard (answer version 1). Say or tap Confirm to save it, or choose a different rating. Nothing is saved yet." |
| Confirmation | **Speak it** — executed, source `spoken` |
| Outcome | `confirmed`, "Consistent one-review transition", acknowledgement 1 |
| Write | 1 · revlog `ease` 3 · card `reps` 1 → 2 |
| Journal | entry 1, phase `settled`, `outcomeState` `confirmed`, 14 transcript characters |
| Duplicate confirm | not offered — "confirm is not available while the session is committed" |

**Decision 3 is retired.** The card said spoken confirmation was touch-only on the pinned
route until AV-044 landed. AV-044 (#67) measured that the engine does supply confidence;
this run is the first time a guarded command has been **observed executing by voice against
a real collection**, and it wrote the review it was asked to. The elapsed time submitted
was 108,900 ms and the revlog stored 60,000: the deck's own `maxTaken` cap, which
`ReviewOutcome.timeWasCapped` already models and which is not a unit error.

### What went wrong first, and what was fixed

`corrected`, `correction-only` and `abandoned` were attempted before the harness knew how
to take AV-019's abstain path. Two of the three cards graded `uncertain`, so the exchange
opened as an abstention with **no pending rating** — correct behaviour, and exactly what
the card requires — and the case scripts, which assumed a grader proposal, had nothing to
correct and skipped. The third never settled an answer at all. Nothing was written in any
of them, and the exchange itself was right every time; the gap was in the harness, and in a
validator lenient enough to let a skipped case pass the no-write checks.

Both were fixed the same day, before any of the three was re-run:

- `ExchangeInstrumentation.nameRating` takes the abstain path — `selfGrade` opens Announced
  with the rating announced as learner-named — so a case reaches a pending rating whatever
  the rules make of the answer, and the abstain path gets live evidence of its own.
- `validate.py` requires each case to have reached the steps it is named for, so a skipped
  case cannot pass while demonstrating nothing.

The three attempts are kept in [`evidence/inconclusive/`](evidence/inconclusive/README.md).
Re-running until one comes back right and keeping only that one is not evidence.

### Reproducing the rest

Every case needs one spoken answer, and each confirmation is the operator's choice of
spoken or touched, recorded as it happened. The owner asked on September 16, 2026 that
spoken evidence be spoken; nothing in the harness synthesizes a voice or attests on the
operator's behalf. `tools/av019-qa/validate.py` re-derives every claim above from the
retained snapshots — 94 checks over the three completed cases — and names the two still
outstanding rather than passing as though the run were done. Reproduce with the
[runbook](runbook.md).

## What this does not establish

Three cases are not five. Nothing here yet shows that a **corrected** rating is the one
that gets written, or that AnkiDroid offers its own Undo after the handoff — those are the
two cases still to run, and #21 should stay open until they have.

One spoken confirmation is also not a recognition-accuracy estimate. It shows the guarded
spoken path works on this route at all, which it had never been observed doing; it does not
say how often it will.

The offline layer says nothing about recognition quality or about AnkiDroid's real write
behaviour. It also says nothing about the AI source end to end: the live harness grades on
device, so the `ai` announcement is exercised only against the fakes — spending the owner's
OpenRouter credit to re-check a label AV-045 already covers would buy nothing this card
needs.

The 30-turn acceptance run, and the manual-intervention count that goes with it, stay in
[#29](https://github.com/BrockBadeaux14/AnkiVoice/issues/29).
