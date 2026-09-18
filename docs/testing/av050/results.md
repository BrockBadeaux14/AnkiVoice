# AV-050: a silent automatic mode and a microphone that opens and closes itself

Issue [#81 — Polishing hotfixes](https://github.com/BrockBadeaux14/AnkiVoice/issues/81).
Branch `codex/av-050-polish-hotfixes`. Review remains pending.

**The offline layer passed on September 17, 2026**: 802 JVM tests across the modules, with
no emulator and no network. **The live layer is prepared and not yet
run** — it needs the owner at the emulator speaking every answer, so the
[runbook](runbook.md) is the routine and this page records the result when it is in.

This card was a running list of fixes found while actually studying with the app. Three
were specified when it opened and one more was added while it was being built.

## A. The study screen says nothing about automatic grading

Deleted from `StudyScreen.kt`: `AutomaticGradingBanner`, `AutomaticGradingPanel` — the
countdown, the progress bar, **Keep it manual**, the "you stopped automatic grading" line
and the "this rating is yours to confirm" line — and the automatic variant of the Confirm
sentence. Reworded in `PrecommitExchange`: the announcement, which is **spoken** as well as
shown, and every notice that named the mode.

| What used to be said | What is said now |
| --- | --- |
| "Good is waiting … Automatic grading is on, so it will be saved in 5 seconds unless you stop it … or tap Keep it manual" | "Good is waiting … Say or tap Confirm to save it, or choose a different rating. Nothing is saved yet." — the same sentence the manual mode makes, word for word |
| "… Automatic grading saved it; you did not confirm this one." | the saved rating and what undo costs, and nothing about the mode |
| "Automatic grading had nothing left to save …" | "There was nothing left to save for this card, so nothing was written." |
| "Automatic grading did not run here: …" | "That rating was not saved: … Nothing was written, and the rating is still waiting." |

**What stays.** The switch and both of its warnings on the setup screen, including that
only AnkiDroid's own Undo can take a saved review back. `StudyState.automaticGrading`, the
armed window, the `auto` confirmation source and the journal's record of it — the behaviour
and the evidence are exactly as AV-047 wrote them. And, deliberately, a rating that was
left **unwritten**: a silent version of that would leave the learner believing a review
exists when none does.

**The guard is in two halves,** because neither alone is enough.
`assertNoAutomaticGradingText` checks every field of a published `StudyState` that the
screen renders as text, over a whole automatic session's worth of snapshots.
`StudySurfaceGuardTest` checks the screen's **own** string literals, because a composable's
literals never reach a `StudyState` and a reinstated banner would otherwise pass every
snapshot assertion in the suite while sitting on screen for the whole session. Comments and
KDoc are stripped first, so the file may still document the rule it enforces.

## B. The microphone opens itself once the front has been read

The chain from a card being offered to the learner speaking has no tap in it. A card that
is offered is published to the screen, its Prompt is spoken, and when that playback settles
the microphone opens on its own.

**The amended rule.** "Capture opens only on an explicit Start answer" is replaced by **the
microphone opens itself exactly once per attempt, after that attempt's prompt playback
settles.** It is amended in all five places that stated it: `SpeechTransport.listen`'s
contract, `AnswerPhase.THINKING`, [AV-007's contracts](../../contracts/av007-session-contracts.md),
[`android/README.md`](../../../android/README.md) and [ADR 0022](../../decisions/0022-android-implementation.md).

**One open per attempt is structural, not a flag.** `handsFreeToMicrophone` is reached from
exactly four places — a card being offered, a tapped Play prompt, a Try again, and a resume
— and each reaches it once. `AnswerLimits.AUTOMATIC_REARMS` is untouched at 0, and a test
proves nothing reopens a capture after a result inside an attempt. A **Try again** is a new
attempt: it hears the Prompt again and gets its own single automatic open.

**Start answer stays a touch control**, offered whenever capture is not running — for an
automatic open that failed, for a learner who wants to start early, and for AV-048's rule
that no control is voice-only. **Play prompt** stays for the same reason.

**The answer window, and what the tap used to buy.** The explicit Start answer bought
*unbounded* thinking time. The owner's decision was a **pre-roll** in front of the window
rather than a longer window, so the 15 seconds stays an honest speaking budget:

| Clock | Value | What it measures |
| --- | --- | --- |
| Pre-roll | 15,000 ms | recall, from the microphone opening; it ends the moment the learner is heard |
| Answer window | 5,000 ms | speaking, from speech onset — or from the pre-roll running out, when nobody was heard. Cut from 15,000 at the owner's direction once endpointing was in: the window is the backstop, not the budget |
| Finalization | 5,000 ms | unchanged |

Both are **pinned engineering bounds, not measurements.** Nothing established that 15
seconds is long enough to recall an answer, and nothing establishes that it is long enough
to say one. A learner who never speaks gets the pre-roll and then the window, and the expiry
preserves the card as it always did.

## C. The microphone closes itself when the learner has finished

There is still exactly **one** way to stop a microphone — the `beginFinalization` path Done
uses, and the same 5,000 ms finalization deadline behind it — and two ways to decide it is
time.

| Route | Signal | Hold |
| --- | --- | --- |
| The engine's endpoint (primary) | `onEndOfSpeech`, or a segment the engine closed | 800 ms; any further speech, partial or segment takes it back |
| Trailing silence in the frames (fallback) | mean frame amplitude below 500 on the 16-bit scale, measured in the pump that already reads every frame | 1,500 ms, and only when the engine offered no endpoint at all |

**The wake-up is the load-bearing part.** `awaitCapture` parks on a deadline, and an
endpoint that arrives mid-wait has to *signal* that wait rather than be discovered when it
next looks. A first cut recorded the endpoint under the lock and left the wait parked on
the backstop, so the primary route never ended a capture early — only the backstop behind
it did, and the stop was then mislabelled `endpoint`. Four tests tell the routes apart from
the backstop by asserting **when** the microphone stopped, against a backstop deliberately
seconds away; all four fail if the signal is removed.

The backstop mirrors `AnswerLimits` **as #13 measures it**: once the learner has been heard
it runs from that onset, and only a capture nobody spoke into gets the whole pre-roll and
then the whole window. Anchoring it to the microphone open instead let the capture outlive
#13's own window by the length of the pre-roll — and a final arriving after that is past
#13's finalization deadline before it is delivered, so a good answer settled as `TIMED_OUT`
and was discarded.

`onEndOfSpeech` and `onBeginningOfSpeech` used to go to the diagnostics observer alone;
they are now reported to the transport. The fallback exists because the pinned engine has
not been shown to endpoint reliably on the segmented external-audio route, and it needs no
new audio path: it measures the frames on their way past and writes every one of them to
the recognizer unchanged, loud or quiet.

**An automatic stop is not a Done.** `CaptureStop` gains `endpoint` beside `done`,
`window-expiry` and `cancelled`, the transport reports which one stopped a capture, and the
controller turns it into `ReviewSession.endpointAnswer()`. A turn's evidence carries it, so
a journal or a runbook can never read an endpoint as a gesture the learner made. AV-012's
settled statuses are unchanged: what the recognizer finally returns is still the answer.

**Guards.** Nothing stops before speech has begun — a learner still remembering gets the
pre-roll and the window, not an instant timeout. A 1,200 ms minimum capture duration
protects a false start, measured **from the onset** rather than from the microphone open:
what it protects is a duration of speech, and measured from the open it would be spent
during the pre-roll and protect nothing. Done and Cancel take effect immediately and take precedence over
both routes. An automatic stop re-arms nothing.

## D. The other small fixes

### D.1 The card is on screen before its prompt is spoken

Found while studying, September 17, 2026. The card is read from AnkiDroid on the session
thread and the prompt is played on the same thread, so without an explicit publish between
them the first thing a learner got after tapping **Start studying** was audio over an
"Opening your deck…" screen. `cardIsOnScreen` publishes the loaded card, its prompt and its
identity before anything is spoken, and it doubles as the guard that nothing is spoken for a
card that is not there: an exhausted queue, a halt on the way in, or a card the provider
refused all leave `ReviewSession.card` null and stop the chain before the synthesizer is
touched.

## What this does not establish

- **No live evidence yet.** Every claim above is from JVM tests against the `:core` fakes
  and `FakeSpeechPlatform`. Nothing here says the pinned engine delivers `onEndOfSpeech` on
  the segmented route, that 800 ms is the right hold for a real pause, that 500 is the right
  amplitude for a real microphone, or that 15 seconds of pre-roll is enough to recall an
  answer. The [runbook](runbook.md) is what would establish the first two.
- **The endpointing constants are selections.** They are pinned and documented as such,
  in the same terms AV-042 pinned its own. A live run may well move them.
- **`CANCEL_AUTOMATIC` is now unreachable.** AV-050 deletes **Keep it manual** from the
  screen, which was its only control; the armed window itself is
  [#80 (AV-049)](https://github.com/BrockBadeaux14/AnkiVoice/issues/80)'s deletion and has
  not landed. Until it does, an armed window runs to its end with nothing able to stop it,
  which is AV-049's intended behaviour arriving one card early rather than a new one.
- **`automatic-cancelled` is retired.** AV-047's live turn tapped the control this card
  deletes. `automatic-corrected` replaces it and proves the same thing — a retired window
  writes nothing — by naming a different rating instead.
- Emulator evidence, when it arrives, will not establish physical-device, Bluetooth or
  phone-call behaviour.
