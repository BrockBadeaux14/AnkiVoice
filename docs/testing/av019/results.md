# AV-019: pre-commit exchange implemented and verified on the pinned AVD

Issue [#21 — Make rating correction predictable](https://github.com/BrockBadeaux14/AnkiVoice/issues/21).
Branch `codex/av-019-rating-correction`. Review remains pending.

The exchange, the announcement, the re-prompt rule, the commit step and the outcome
handling are in `:core`; the writer wiring, the surface controls and the live harness are
in `:app`. **The offline layer passed on September 16, 2026**: 45 new JVM tests across
`:core` and `:app`, with no emulator and no network.

**The live layer is complete: all five cases passed** on the pinned AVD on September 17,
2026, in the owner's own voice, against a disposable AV-002 collection. Three reviews were
written, each from an explicit confirmation and none any other way; two survive, because
the third was taken back by AnkiDroid's own Undo in the handoff case. `confirmed` carries
the finding that matters most for the card's decision 3: **a spoken confirmation executed
and wrote one review.** Every attempt that proved nothing along the way is kept in
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


## Live layer — September 17, 2026, five of five

Five cases, driven by `tools/av019-qa/run.py` through `ExchangeInstrumentation` on the
pinned `AnkiVoice_AV005` AVD against a disposable AV-002 collection.

| Case | What it must show | Result |
| --- | --- | --- |
| `confirmed` | one review, journal settled `confirmed`, the duplicate confirm not offered | **passed** — spoken confirm, rating 3 |
| `corrected` | one review at the **corrected** rating, journal settled `confirmed` | **passed** — Again replaced by Hard; Hard is what the revlog records |
| `correction-only` | no review, no journal entry | **passed** |
| `abandoned` | no review, no journal entry | **passed** |
| `undo-handoff` | one write, the session stopped, and AnkiDroid's own Undo | **passed** — Undo was offered, used, and took the review back |

`tools/av019-qa/validate.py` re-derives **170 checks** over the five cases.

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

### `corrected`: the corrected rating is the one that gets written

The rules answered `uncertain`, so the exchange opened as an abstention; `selfGrade` named
**Again**, announced as `learner` at revision 1; a correction replaced it with **Hard**,
re-announced at the same revision; and a touched confirmation committed once.

| | |
| --- | --- |
| Announced | abstention (`none`) → Again (`learner`) → Hard (`learner`), all revision 1 |
| Confirmation | **touch** — "Rating 2 is confirmed for this card." |
| Outcome | `confirmed`, "Consistent one-review transition", acknowledgement 1 |
| Revlog | `ease` **2** — Hard, the corrected rating, and **not** the Again it replaced |
| Journal | entry 1, settled `confirmed`, rating 2, revision 1 |
| Card | `reps` 4 → 5 |

A rating that was announced and then replaced never reached the collection. That is what
the card's "correction and confirmation are two separate steps" decision exists for, now
shown against a real one.

### `undo-handoff`: AnkiVoice writes, stops, and AnkiDroid takes it back

The rules matched here, so **Good** was announced from an exact rule match and confirmed by
touch. The write landed — the transport was called once and the journal entry settled
`confirmed` at rating 3 — and the session then **stopped**, not resumable, with the notice
sending the learner to AnkiDroid's own Undo. The operator reported: *"AnkiDroid offered
Undo and I used it."*

The collection's net revlog delta is therefore **zero** and the card returned to its prior
state. That is the handoff working end to end rather than a missing write, and it is the
one thing no JVM test could show: AnkiVoice has no programmatic undo, and correction after
commit is genuinely AnkiDroid's.

### `correction-only` and `abandoned`: a correction, and an exchange, that wrote nothing

Both took the abstain path — the rules answered `uncertain`, the learner's own rating was
named through `selfGrade` and announced as `learner`, and only then was there anything to
correct or abandon. Both left the collection byte-identical, with no journal entry and no
call to the transport.

| | `correction-only` | `abandoned` |
| --- | --- | --- |
| Answer | settled first attempt | settled on the **third**; two came back empty |
| Announced | abstention → Again (`learner`) → **Hard** (`learner`), revision 1 | abstention → Again (`learner`), revision 5 |
| Then | "Session finished. No review was written." | "Paused. The microphone is released and the card is kept." then finished |
| Result | 0 reviews · 0 journal entries · scheduling unchanged | 0 reviews · 0 journal entries · scheduling unchanged |

`abandoned` is the more useful record, because its two empty captures and the retries that
followed are in the evidence rather than hidden: AV-012's Try again opens a new revision
each time, which is why the rating it abandoned sits at revision 5.

## What the run exposed, and what was fixed

Nothing in this section is an exchange fault. The exchange behaved correctly in every
attempt, including the ones that proved nothing; the gaps were in this card's own harness,
its validator and its expectations.

**The harness could not take AV-019's own abstain path.** Three early cases assumed a
grader proposal, so when the rules answered `uncertain` and the exchange correctly opened
as an abstention with no pending rating, the case scripts had nothing to act on and
skipped. `ExchangeInstrumentation.nameRating` now names a rating through `selfGrade`, so a
case reaches a pending rating whatever the rules made of the answer — and the abstain path
gets live evidence of its own.

**The validator let a skipped case pass.** It now requires each case to have reached the
steps it is named for, to have had a pending rating at all, and rejects a Pause that was
refused because the turn had already halted.

**A dropped capture cost a whole case.** It now costs a **Try again** through AV-012's own
`retry`, up to three attempts, each recorded.

**`undo-handoff` was expected to add a review.** That is only true when the Undo is *not*
used, and the assumption marked a case that had done exactly what was asked of it as a
failure. Two questions are now kept apart:

- **Did a write happen?** The transport call and the settled journal entry answer that, and
  never depend on what the operator did afterwards. The harness judges only this, because
  the process cannot see AnkiDroid.
- **Should a review have survived?** The driver answers that from the collection snapshots
  either side and the operator's own report of what AnkiDroid offered.

The retained records keep both verdicts: `harnessPassed` is what the device concluded, and
`passed` is the driver's, derived from the collection and the journal rather than from the
harness's summary.

**The spoken confirm was lost twice** before `corrected` passed — once to `noMatch`, once
heard as `"confirm good confirm hard"`, a single merged utterance that whole-utterance
matching correctly rejected. Each time the router refused it and paused with the card kept,
writing nothing. That is the right answer to an unheard command, and it is why the runbook
now says to tap the confirmation unless the spoken path is the point of the run.

Every attempt that proved nothing is kept in
[`evidence/inconclusive/`](evidence/inconclusive/README.md). Re-running until one comes back
right and keeping only that one is not evidence.

### Reproducing it

Every case needs one spoken answer, and each confirmation is the operator's choice of
spoken or touched, recorded as it happened. The owner asked on September 16, 2026 that
spoken evidence be spoken; nothing in the harness synthesizes a voice or attests on the
operator's behalf. `tools/av019-qa/validate.py` re-derives every claim above from the
retained snapshots — **170 checks over the five cases** — rather than trusting either
summary. Reproduce with the [runbook](runbook.md).

## What this does not establish

Five cases on one emulator against one disposable four-card collection is not a study. It
shows each property once, which is what the card asked for; it does not show how often.

**Undo was offered once, on this AnkiDroid and this card, moments after the review.** That
is exactly the situation the app's own notice says not to rely on — AnkiDroid may not offer
it after other activity, or after either app's process is closed — and nothing here tests
that boundary.

One spoken confirmation is not a recognition-accuracy estimate either. It shows the guarded
spoken path works on this route at all, which it had never been observed doing; it does not
say how often it will. Capture on this emulator remains unreliable and the cause is still
not established — it cost several attempts, and it is an AV-025
([#26](https://github.com/BrockBadeaux14/AnkiVoice/issues/26)) question rather than one this
card answers.

Nothing here covers the `ai` source end to end: the live harness grades on device, so that
announcement is exercised only against the fakes. Spending the owner's OpenRouter credit to
re-check a label AV-045 already covers would buy nothing this card needs.

The 30-turn acceptance run, and the manual-intervention count that goes with it, stay in
[#29](https://github.com/BrockBadeaux14/AnkiVoice/issues/29).
