# AV-026: the study screen, implemented and verified offline; live check prepared

Issue [#27 — Build the real study surface and verify the integrated mobile flow](https://github.com/BrockBadeaux14/AnkiVoice/issues/27).
Branch `codex/av-026-study-surface`. Review remains pending.

The study screen replaces AV-014's debug command surface: one Compose screen, reached from
the setup screen after deck selection, provisioning and AV-018's gate, showing the card's
Prompt, the session's state in the learner's words, the settled transcript with its version
and an editor, the grading status bound to that version, AV-019's Announced position with
its source, the writer's outcome, every AV-014 command's touch control, and every paused or
halted state explained with only the controls that apply. **The offline layer passed on
September 17, 2026**: 67 JVM tests across three `:app` suites, with no emulator and no
network, beside the 69 that already covered the shell, the provider settings and the journal.

**The live layer is prepared and not yet run.** It needs the owner at the emulator speaking
every answer, and two of its ten turns need the owner's OpenRouter key entered in the app.
Nothing in the harness synthesizes a voice or attests on the operator's behalf, so the
ten-turn check waits for a person; the [runbook](runbook.md) is the routine, and this page
records the result when it is in.

## Implementation

Everything is in `:app`; no `:core` rule, contract or session changed, and the AV-013
session, the AV-014 router and the AV-019 exchange are used as shipped.

| Part | What it owns |
| --- | --- |
| `StudyController` | One session on its own thread. Publishes `StudyState`, an immutable snapshot **derived** from the session, the exchange and the router on every action; there is no second copy of the turn. Automatic grading once an answer settles and after every edit. Done, Cancel and an interruption reach a capture in flight from the main thread. Reload through the startup gate. The per-session evidence export. |
| `StudyState` and `StudyControl` | What the screen shows and which controls apply. `controls` is computed on the session thread from the session's state, AV-012's recovery options and the router's `available()`, so the screen never offers a control the session would refuse. |
| `StudyWords` | Every halt the session can reach, in the learner's words: what happened, what was **not** written, and what to do next. |
| `StudiableCardProvider` | AV-010's read-only skipping, placed where `ReviewSession.offerCard` reads its cards, so an unstudiable card is announced and skipped rather than halting the session, and five in a row stop it with the recorded summary. |
| `StudyScreen` | The Compose screen. Every button is enabled only while `controls` lists it. |
| `MainActivity` | The setup screen and the study screen it leads to. A screen lock is told apart from an app switch through `PowerManager.isInteractive`, so the record says which. |
| `StudyInstrumentation` | The live harness: launches the real app, watches the shipped controller, exports its evidence. |

### Removed

`CommandCard` and the debug-surface composables beside it (`AnnouncedPosition`,
`SelfGradeControls`, `OutcomeControls`) are gone from `MainActivity`. `CommandController`
became `StudyController` — the card left that to the implementer's discretion, and a class
that is now the study surface should not carry the debug surface's name. AV-023's readiness
preview stays on the setup screen as a **Check deck** button; its Start, Stop and Resume
controls are gone, because the study screen is where a session is started and stopped.

Every instrumentation harness still builds and passes `assembleDebugAndroidTest`:
`ReviewInstrumentation`, `SpeechInstrumentation`, `SessionInstrumentation`,
`JournalInstrumentation`, `CommandInstrumentation`, `EvaluationInstrumentation`,
`ConfidenceInstrumentation`, `ExchangeInstrumentation`, with `CaptureDiagnostics` and
`LiveVerificationUi`. None of them depended on the removed composables.

### What the screen derives, and from where

| Shown | Derived from |
| --- | --- |
| Prompt, card, permitted ratings | `session.card` |
| Status line | `session.state`, `answerTurn.phase`, whether a grade is in flight, or the halt's explanation |
| Transcript, version, kind, needs-review | `session.answer`, `session.transcriptRevision` |
| Grading status | The last grading reply, shown **only** while its revision is the session's current one and its card is the open card; a retry, an edit or Next card hides it |
| Announced position | `PrecommitExchange.position`, which AV-019 already derives from the session |
| Outcome | `PrecommitExchange.settled` |
| Halt | `session.halt` and `session.lastFailure`, through `StudyWords.explain`; after the session closes, the halt it closed in |
| Controls | The session state, `recoveryOptions`, `router.available()` and `router.spokenAvailable()` |

### Interruptions

Leaving the foreground and locking the screen each interrupt the session, release the
microphone and require Reload. A capture in flight is cancelled through the transport from
the main thread the moment the interruption arrives, so the microphone is released at once
rather than when the answer window would have expired; the blocked session thread applies
the interruption when the transport returns, and the late transcript is never delivered to
the session. A grading reply or a spoken command that lands after teardown finds the session
gone and runs nothing. Reload is the same `start()` as the first one: it consults AV-045's
gate before any card is offered, and opens a **fresh** session — nothing resumes the
interrupted one. `StudyControllerTest` covers all three, and the blocked reload.

### Grading is automatic

The debug surface had a Grade button. The study screen grades as soon as an answer settles
as gradable, and again after every transcript edit: rules on device first, the AI route only
on a rule miss and only when the learner has configured it. An edit made while a request is
in flight withdraws that request; when its stale reply arrives the session drops it and the
controller grades the replaced revision, so an edited answer is never left ungraded. The
suggestion is still advisory and the confirmation is still separate.

### Evidence for #29

`StudyController.evidence` exports, for the open session or the last one that closed: the
session `events` and `outcomes`, AV-018's journal entries for the session, the touch actions
taken outside any turn, and per turn the grading path (`rule`, `ai-free`, `ai-paid`,
`abstain`, `unavailable`), every settled attempt with its status, confidence and raw score,
the retry and edit counts, the self-grade, the rating corrections, the confirmation source,
the outcome and the touch actions and spoken commands. It carries study content and is handed
to the caller that asked; `StudyCompositionTest` asserts that no card content or transcript
reaches AV-020's diagnostics in a full confirmed turn.

### A gap the surface exposed

An unknown outcome the writer itself settled in an earlier run is outstanding at the next
start, but AV-045's gate built its notice text only from the reconciliations of *unsettled*
entries, so that start would block with an acknowledge button and no words beside it.
`JournalReport.notices` now builds the same notice from the entry, and
`StudyControllerTest` blocks a reload on exactly that case. Nothing about the gate's
decisions changed.

## Offline results — September 17, 2026

```
./gradlew checkModuleBoundaries :core:test :app:testDebugUnitTest
```

| Suite | Tests | Result |
| --- | --- | --- |
| `StudyControllerTest` (`:app`) | 42 | pass |
| `StudyScenariosTest` (`:app`) | 12 | pass |
| `StudyCompositionTest` (`:app`) | 13 | pass |
| `:app:testDebugUnitTest` in full | 136 | pass |
| Python suite (`tests/`) | 294, two new | pass |

What `StudyControllerTest` covers, in the card's own order: the prompt and controls after
a start; an unavailable deck explained with only a reload; the runnable commands and their
touch controls; Start answer opening the microphone; the partial and the settled transcript
with its version; a capture with no transcript pausing with the reason and the manual
controls; the answer window offering only Done and Cancel and no spoken command; Done and
Cancel reaching the transport mid-capture; Try again and the revision it opens; an edit
retiring the suggestion and being regraded, including across a reply in flight; a
low-confidence final shown, not graded, and accepted by the learner; pause, skip and resume;
a refused command reported rather than crashing; finishing; a spoken finish leaving a reload
and a finish; an app switch and a screen lock each interrupting, releasing and requiring a
reload; the late capture, the late grade and the late spoken command each dropped; reload
consulting the gate and opening a fresh session, and blocked by an unacknowledged unknown
outcome; no control except Confirm writing; the announcement with its source, version and
grading status; an AI suggestion naming its route; the single write and the duplicate not
offered; Next card clearing the previous card's verdict and grade; the Undo handoff closing
the session; a failed write and an unconfirmable write offering only what applies; the
abstain path through the self-grade; a grading fault; a correction recorded; AV-010's
skipped card and five-card stop; an exhausted queue; and the evidence export with a spoken
confirmation's source.

`StudyScenariosTest` drives decision 4's eleven named scenarios through the controller and
checks, at the end of each, how many reviews the collection holds and that nothing but
Confirm wrote one. `StudyCompositionTest` keeps AV-045's composition under the new surface
and adds the full confirmed turn with content-free diagnostics.

## Live layer — prepared, not yet run

The ten turns, their routines and their expectations are in the [runbook](runbook.md);
`tools/av026-qa/run.py` drives them one per cold boot and `tools/av026-qa/validate.py`
re-derives every claim from the retained snapshots. `tests/test_av026_evidence.py` runs the
validator with the Python suite and CI runs it on every push; until the run is recorded it
reports that there is nothing to validate.

| Turn | Needs | Status |
| --- | --- | --- |
| `rule-match` | the owner's voice | not yet run |
| `ai-labelled` | the owner's voice and OpenRouter key | not yet run |
| `abstain-self-grade` | the owner's voice | not yet run |
| `corrected-confirmed` | the owner's voice | not yet run |
| `transcript-edit` | the owner's voice | not yet run |
| `skip` | the owner at the screen | not yet run |
| `pause-resume` | the owner at the screen | not yet run |
| `interruption-reload` | the owner's voice | not yet run |
| `undo-handoff` | the owner's voice, and their report of what AnkiDroid offered | not yet run |
| `route-refused-self-grade` | the owner's voice and key, with the daily limit at 0 | not yet run |

Record each turn's result here as it lands, keep every attempt that proved nothing in
`evidence/inconclusive/`, and record which confirmations were spoken and which were touched;
the summary the driver writes carries the source of each.

## What this does not establish

The offline suites prove the surface's derivation, its controls and its write discipline
against the fakes. They say nothing about recognition quality, about AnkiDroid's real write
behaviour under this screen, or about how the screen reads on a device; those are the live
layer's, and it has not run. Compose composables are not unit-tested here: the screen is
driven entirely by `StudyState.controls`, which is, and the live check is what exercises the
rendering.

Ten turns on one emulator against one disposable collection will not be a study either. They
show each property once, which is what the card asks for; the 30-turn human run and the
manual-intervention count stay in [#29](https://github.com/BrockBadeaux14/AnkiVoice/issues/29),
and the sync and stale-session run in [#28](https://github.com/BrockBadeaux14/AnkiVoice/issues/28).
Capture on this emulator remains unreliable and the cause is still not established; it is
[#74](https://github.com/BrockBadeaux14/AnkiVoice/issues/74)'s question, and the screen's
Try again is the answer this card gives to it.
