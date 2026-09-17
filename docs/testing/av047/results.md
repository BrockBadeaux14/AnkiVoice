# AV-047: automatic grading, implemented and verified offline; live check prepared

Issue [#76 — Add an automatic grading option to the main menu](https://github.com/BrockBadeaux14/AnkiVoice/issues/76).
Branch `codex/av-047-automatic-grading`. Review remains pending.

The setup screen has an **Automatic grading** switch. While it is on, a rating the grader
proposed is saved after a five-second cancel window without the learner confirming it.
**The offline layer passed on September 17, 2026**: 363 `:core` tests and 158 `:app` tests,
with no emulator and no network, of which 44 are new. **The live layer is prepared and not
yet run** — it needs the owner at the emulator speaking every answer and setting the switch
themselves, so the [runbook](runbook.md) is the routine and this page records the result
when it is in.

## The open decision, settled

The card left one decision open: how an automatic commit satisfies the writer's
confirmation guard.

**Option 1 was taken — a named confirmation source.** `ConfirmationSource` gained `AUTO`.
Option 2, auto-pressing Confirm in the session layer, is less code but makes an automatic
write indistinguishable from a touched one in the record, which breaks the action-source
requirement AV-007 keeps for #29 — and which the card's own sixth acceptance criterion
("the session record and the AV-018 journal tell an automatic commit apart from a confirmed
one") rules out. Option 1 was also the card's recommendation.

## What this reverses, in writing

| Document | Where it said the opposite | What it says now |
| --- | --- | --- |
| [AV-007 session contracts](../../contracts/av007-session-contracts.md) | "Every rating requires an explicit learner confirmation" (September 14, 2026) | amended in the header, in the product decision itself, in the grader and writer sections, and in a section of its own recording the reversal, its date and the new confirmation model |
| [ADR 0006](../../decisions/0006-speech-and-grading-providers.md) | "**No-go for unattended rating** … require an explicit user rating/confirmation" | annotated in place: overridden in part, by the owner, on September 17, 2026, with what the override does and does not cover, and with the measurements it does **not** withdraw |
| [ADR 0022](../../decisions/0022-android-implementation.md) | "Rating automation: disabled" | amended in the baseline table and the deviations list |

AV-006's measured error rates are not retracted. That decision's reading of them was that a
wrong label should not be able to write; the owner's is that they will accept that risk on
their own collection. No automation *threshold* is approved: the option is a learner's
choice, not a measured confidence bar, and #19 still owns held-out evaluation.

## Implementation

| Part | What it owns |
| --- | --- |
| `ConfirmationSource.AUTO` (`:core`) | The named source. `ReviewIntent.hasConfirmation` treats it as it treats `TOUCH` — not a recognition event, so no confidence requirement — and checks every other binding exactly as before. |
| `AutomaticGrading`, `AutomaticCommit` (`:core`) | The option as one session was opened with it, and the armed window. `PrecommitExchange.armed` is **derived** from the session on every read, as `position` is, so a correction, an edit, a retry, a pause, an interruption or the commit retires it without anyone remembering to. |
| `PrecommitExchange.commitAutomatically` / `.cancelAutomatic` (`:core`) | The only place an `AUTO` confirmation is minted, and the only way to call one off. Both re-derive `armed` first; neither writes when it is gone. |
| `JournalRequest`, `JournalEntry`, `JournaledReviewWriter` (`:core`) | The confirmation source in the durable `dispatch` record, written before the write is handed over. |
| `ShellSettings.automaticGrading`, `ShellController.setAutomaticGrading` (`:app`) | The stored option, beside `language` in the `shell` preferences. Off when the key is absent. Unlike the language it does not stop the deck preview. |
| `DelayScheduler`, `StudyController.armAutomatic` / `.cancelAutomaticCommit` (`:app`) | The clock, behind a seam. The exchange never times anything; the controller does, and refuses a released task that no longer applies rather than trusting cancellation alone. |
| `StudyState`, `StudyControl.CANCEL_AUTOMATIC`, `StudyScreen` (`:app`) | The option's state for the whole session, the countdown where the rating is shown, and **Keep it manual** as the one control that stops it — offered only while a window is open. |
| `MainActivity` (`:app`) | The setup screen's switch, with what it costs stated before it: the review is saved without a confirmation, and only AnkiDroid's Undo can then take it back. |

### The orderings that matter

Stopping a window is best-effort by design — a scheduler cannot recall a task it has
already released — so the controller does not rely on it:

| Ordering | What happens |
| --- | --- |
| The window expires | the task asks the exchange again; anything that retired the rating means nothing is written |
| **Keep it manual** before the timer fires | the task is cancelled and the exchange disarms |
| **Keep it manual** after the timer fired, before its task runs | the window is marked stopped; the released task reads that and writes nothing |
| A second window arms | the first is no longer the current one, so its task writes nothing |
| Interrupted, finished or reloaded | the window is stopped, and a task that ran anyway finds a different session |

Each of those is a test, not a claim.

## Offline results — September 17, 2026

```sh
cd android && ./gradlew --console=plain checkModuleBoundaries :core:test :app:testDebugUnitTest
```

| Suite | Tests | What it proves |
| --- | --- | --- |
| `AutomaticGradingTest` (`:core`) | 22 | The exchange's rules over the **shipped** `GuardedReviewWriter` inside AV-018's journal: off arms nothing and says nothing; on commits a rule match and an AI suggestion through a bound `AUTO` event; the guard still rejects that event on any mismatch and after a correction clears it; an abstention, a grading failure, a self-grade and a correction arm nothing; an edit and an interruption inside the window leave the card unwritten; cancelling writes nothing and leaves the rating correctable; a window that expires after a cancellation writes nothing; the journal says `auto` and `touch` respectively, and an older line reads back with no source. |
| `AutomaticStudyTest` (`:app`) | 18 | The surface: the window's length reaches the screen, the control is offered only while one is open, **a cancel that arrives after the timer fired still writes nothing**, the next card opens its own window, and an edit, an interruption or Finish inside the window each write nothing. The record carries the option's state and `auto`, and a cancel does not follow the learner to the next card. |
| `ShellControllerTest` (`:app`) | +4 | Off on first run; written to the store; survives a controller rebuild; does not stop the deck preview; publishes nothing when set to what it already is. |

**`Off` is unchanged, and the evidence is that nothing had to change.**
`PrecommitExchangeTest`, `GuardedReviewWriterTest`, `ReviewLifecycleTest`,
`ReviewJournalTest`, `StudyControllerTest` and `StudyScenariosTest` were **not modified**
and pass. `checkModuleBoundaries`, `ManifestDriftTest`, `ScenarioDriftTest`, the 294 Python
tests, `:app:lintDebug`, `:app:assembleRelease` and `:app:assembleDebugAndroidTest` are all
green.

### One recorded measurement moved

The `confirmation` field costs **20 bytes per journal entry**. `JournalSizeTest`'s worst
case at the 50-entry bound is now 136,982 / 136,982 / 236,982 / 636,982 bytes across the
four transcript shapes, against the unchanged 700,000-byte ceiling.
[AV-018's results](../av018/results.md) are re-recorded with the new figures and the old
ones beside them, so the documented numbers do not quietly go stale.

## A smoke check on the pinned AVD — September 17, 2026

**This is not the live check, and it does not satisfy the card's ninth criterion.** It is
recorded because it happened and because it is useful, not because it is evidence: the
answers were **typed**, not spoken. The emulator was booted with `-no-audio`, both captures
came back `noMatch`, and the transcript was then typed through the screen's own editor —
AV-012's documented recovery path, and the only path that needs no voice.

What it showed, on the composed app against a real AnkiDroid collection:

- The switch is on the setup screen, off on first run, and writing `automatic_grading` to
  the `shell` preferences. It survived `am force-stop` and a relaunch.
- Turning it on changed the Study card's own wording from "Every rating needs your
  confirmation before it is saved" to "a rating the grader proposes is saved on its own".
- The study screen showed the **Automatic grading is on** banner for the whole session.
- **Two reviews were saved with no Confirm tapped**, one from a rule match and one from the
  AI grader's suggestion, each announced, counted down and written on its own. The journal
  recorded `"confirmation":"auto"` on both `dispatch` records and `confirmed` on both
  `settle` records.
- Turning the switch back off restored the original wording and the stored `false`.

What it did **not** show: a spoken answer, a cancelled window — five seconds proved too
short to catch with scripted `uiautomator` taps, which is itself worth knowing before the
real run — or anything a driver retained as evidence.

**Side effects, stated plainly.** Two reviews were written to the emulator's **VoiceQA
Demo** deck (cards `1789664633030` and `1789664633092`); neither was undone. The second
card's grading took the paid route and spent **$0.000042867** of the owner's OpenRouter
credit, within AV-043's cap — unintended, and reported rather than swept up.

## Live verification — not yet run

The [runbook](runbook.md)'s five turns need the owner at the emulator: every turn needs a
spoken answer, and the switch is theirs to set on the app's own setup screen. Nothing in
the harness synthesizes a voice, taps a control or writes the `automatic_grading`
preference — a check that set the toggle itself would not have checked the toggle.

| Turn | Switch | Must show |
| --- | --- | --- |
| `automatic-rule` | ON | a countdown; one review saved with **no** confirmation; `auto` in the record and the journal; `confirm` not among the touches |
| `automatic-cancelled` | ON | a countdown that was stopped; nothing written; the rating still waiting |
| `automatic-abstain` | ON | path `abstain`; no countdown; one review, `touch` |
| `automatic-unavailable` | ON, daily limit 0 | path `unavailable`; no countdown; one review, `touch` |
| `automatic-off` | OFF | no countdown and no control to stop one; one review, `touch` |

The card's ninth criterion asks for at least one automatic commit and one cancelled one:
`automatic-rule` and `automatic-cancelled` are those two, and the other three are what keep
them honest. `tools/av047-qa/run.py` drives them, reusing AV-026's driver rather than
forking it, and judges each turn from the snapshots the session actually published — so a
turn driven with the switch the wrong way fails rather than passing quietly. Evidence lands
in `evidence/` beside this page.

## What this does not establish

Nothing here is a measurement of grading quality. A run in which automatic grading saved
the right rating five times would not be evidence that it usually will. The emulator bounds
are the usual ones: no physical device, no Bluetooth, no phone calls. And the cost the
option carries is not mitigated away — with it on, a wrong AI rating that the learner does
not stop within five seconds is correctable in AnkiDroid alone, because AV-007's
correction-before-commit rule is unchanged and this card did not add a durable undo.
