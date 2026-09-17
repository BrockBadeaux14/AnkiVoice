# AV-047 runbook: verify automatic grading

Issue [#76 — Add an automatic grading option to the main menu](https://github.com/BrockBadeaux14/AnkiVoice/issues/76).

Two layers, in the manner of the [AV-026 runbook](../av026/runbook.md). The offline layer
proves the option's rules against the AV-041 fakes and runs anywhere: what arms a cancel
window and what does not, that the window is a real chance to stop a write, that the
writer's guard is unchanged, and that the record says which commits were automatic. The
live layer proves the one thing no JVM test can — that the **composed app**, with the
learner's own switch on its setup screen, saves a real review to a real AnkiDroid
collection without anybody confirming it, and stops when told to.

**This card reverses two recorded decisions on purpose.** AV-007's "every rating requires
an explicit learner confirmation" and AV-006's no-go for unattended rating are both
overridden, for grader proposals alone and only while the option is on. Both documents now
say so. The offline layer's first job is to show that with the option **off** nothing
changed at all.

The offline layer writes no review. **The live layer writes three**, one of them with no
confirmation at all, deliberately. Both the driver and the harness refuse any deck whose
name does not start with `AV002`. **Back the collection up first**; AnkiVoice cannot take
a review back, and neither can this runbook.

## 1. Offline: the exchange, the surface and the option

```sh
cd android && ./gradlew --console=plain checkModuleBoundaries :core:test :app:testDebugUnitTest
```

```sh
.venv/bin/python -m unittest discover -s tests
```

| Suite | What it proves |
| --- | --- |
| `AutomaticGradingTest` (`:core`) | The exchange's own rules, over the **shipped** `GuardedReviewWriter` inside AV-018's journal. With the option off, a proposal arms nothing, `commitAutomatically` writes nothing and the announcement says nothing about saving on its own. With it on, a rule match and an AI suggestion each commit through a `ConfirmationSource.AUTO` event bound to this intent, identity, rating and revision — and the guard still rejects that event when any of those does not match, or when a correction cleared it. An abstention, a grading failure, a self-grade and a correction each arm nothing. A transcript edit and an interruption inside the window leave the card unwritten. Cancelling writes nothing, leaves the rating correctable, and a window that expires afterwards still writes nothing. The journal says `auto` for an automatic commit and `touch` for a confirmed one, and a line written before the field existed reads back with no source. |
| `AutomaticStudyTest` (`:app`) | The surface's part: the window is a timer the controller owns, `Keep it manual` is the one control that stops it and is offered only while one is open, and **a cancel that arrives after the timer has already fired still writes nothing**. The option's state and the countdown reach the screen; the next card opens a window of its own; an abstention, a grader failure, a self-grade and a correction open none; an edit, an interruption and Finish inside the window all leave the card unwritten. The per-turn record carries the option's state and `auto`. |
| `ShellControllerTest` (`:app`) | The toggle: off on first run, written to the store, survives a controller rebuild, and — unlike the language — does not stop the deck preview. |

**The evidence that "off" is unchanged** is that `PrecommitExchangeTest`,
`GuardedReviewWriterTest`, `ReviewLifecycleTest`, `ReviewJournalTest`, `StudyControllerTest`
and `StudyScenariosTest` all pass **without modification**. Check that before trusting the
new suites:

```sh
cd android && git diff --stat main -- '*PrecommitExchangeTest.kt' '*GuardedReviewWriterTest.kt' '*ReviewLifecycleTest.kt' '*ReviewJournalTest.kt' '*StudyControllerTest.kt' '*StudyScenariosTest.kt'
```

That must print nothing.

## 2. Live: five turns on the pinned AVD

### What each turn has to show

Every turn is one card on the **app's own study screen**, with the option set on the app's
own setup screen. The harness launches the real app, watches the shipped controller's
snapshots, and exports its evidence when you finish; it taps nothing, speaks nothing, and
**does not touch the switch**.

| Turn | Switch | You do, on the study screen | What must happen |
| --- | --- | --- | --- |
| `automatic-rule` | **ON** | Play prompt · Start answer · say the reference answer · Done · **touch nothing** · Finish | a countdown appears; one review is saved with no confirmation; the record and the journal both say `auto` |
| `automatic-cancelled` | **ON** | answer · tap **Keep it manual** while it counts down · Finish without confirming | a countdown appeared and stopped; nothing written; the rating still waiting |
| `automatic-abstain` | **ON** | answer with something only partly right · pick a rating yourself · Confirm · Finish | path `abstain`; **no** countdown; one review, `touch` |
| `automatic-unavailable` | **ON**, daily limit `0` | answer in your own words · pick a rating · Confirm · Finish | path `unavailable`; **no** countdown; one review, `touch` |
| `automatic-off` | **OFF** | answer with the reference answer · Confirm · Finish | **no** countdown, and no control to stop one; one review, `touch` |

The switch is on the setup screen under **Automatic grading**, below the Study card. It
applies to the **next** session you start, so set it before tapping Start studying. The
driver prints what each turn needs and fails the turn if the session reports the other
state — it will not quietly pass a turn you drove with the switch the wrong way.

`automatic-rule` is the only turn in this repository that may pass with a review nobody
confirmed. It is judged on exactly that: the harness requires `auto` in the turn record and
in the journal, and requires that `confirm` is **not** among the turn's touch actions.

**A capture that comes back empty costs a Try again, not the turn.** The screen says so and
offers Try again; the answer version the rating lands on may be higher than 1. On
`automatic-rule` the countdown starts when the grade lands, so speak, tap Done, and then
keep your hands off the screen.

### Prerequisites

Identical to [AV-026's](../av026/runbook.md#prerequisites): the pinned AVD
`AnkiVoice_AV005` cold-booted on port 5588 with `-allow-host-audio -no-snapshot
-no-boot-anim`, AnkiDroid 2.24.1 with its API enabled and both permissions granted, a
**disposable** AV-002 collection, and a person at the machine with a working microphone.
`automatic-unavailable` needs the owner's OpenRouter key entered in the app so the route
exists to be refused; set **Daily limit** to `0` before it and back afterwards.

One turn per boot. The emulator's coreaudio backend leaks a listener per microphone open
and exits on the second or third of a boot.

### Build and install

```sh
cd android && ./gradlew --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
```

```sh
adb install -r -d android/app/build/outputs/apk/debug/app-debug.apk
```

```sh
adb install -r -d android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

```sh
adb shell pm list instrumentation | grep StudyInstrumentation
```

### Record the deck the run may touch, then run it

```sh
mkdir -p build/av047 && echo '[<deckId>, "AV002 Baseline"]' > build/av047/deck.json
```

```sh
.venv/bin/python tools/av047-qa/run.py build/av047/deck.json
```

For each turn the driver cold-boots the AVD, clears the journal, selects the disposable
deck, snapshots the collection, prints the switch position and the routine, launches the
harness — which launches the app — and then waits: **you drive the study screen from
here**, starting with the setup screen's switch. It prints the screen's status line as it
changes and snapshots the collection again when the session closes.

Expected output:

```
---- automatic-rule: Automatic grading must be ON ----
== automatic-rule ==
  reviews added 1 (expected 1) · journal entries 1 · passed True
---- automatic-cancelled: Automatic grading must be ON ----
== automatic-cancelled ==
  reviews added 0 (expected 0) · journal entries 0 · passed True
---- automatic-abstain: Automatic grading must be ON ----
== automatic-abstain ==
  reviews added 1 (expected 1) · journal entries 1 · passed True
---- automatic-unavailable: Automatic grading must be ON ----
== automatic-unavailable ==
  reviews added 1 (expected 1) · journal entries 1 · passed True
---- automatic-off: Automatic grading must be OFF ----
== automatic-off ==
  reviews added 1 (expected 1) · journal entries 1 · passed True

AV-047 live check complete
```

A subset re-runs on its own, which is how a turn interrupted by an emulator fault is
repeated:

```sh
.venv/bin/python tools/av047-qa/run.py build/av047/deck.json --turns automatic-cancelled
```

**Keep an attempt that came back wrong.** Re-running until one comes back right and
retaining only that one is not evidence. Move it to `evidence/inconclusive/` with a line
saying what happened and why it proved nothing, as
[AV-019's](../av019/evidence/inconclusive/README.md) are kept.

Stopping the host-side driver does **not** stop the instrumentation on the device.
Force-stop `org.ankivoice.test` as well.

### The cancelled turn

The countdown is five seconds. Tap **Keep it manual** while it is still on screen. If you
miss it, the review is saved — that is the option working as asked, not a defect — and the
turn fails because it wrote. Re-run the turn; keep the attempt that wrote, in
`evidence/inconclusive/`, with a line saying you were too slow.

## 3. What this does not establish

The same bounds as every emulator run in this repository: nothing here establishes
physical-device, Bluetooth or phone-call behaviour, and nothing here is a measurement of
grading quality. A run in which automatic grading saved the **right** rating five times is
not evidence that it usually will; #19 owns held-out evaluation, and AV-006's measured
error rates stand unretracted. What this check establishes is narrower and is the whole of
what the card claimed: the option is off until the learner turns it on, and while it is on
a grader proposal is saved without a confirmation, stoppable within the window, told apart
in the record, and confined to what the grader proposed.
