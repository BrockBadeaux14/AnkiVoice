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
| `AutomaticStudyTest` (`:app`) | The surface's part: the window is a timer the controller owns, and **a cancel that arrives after the timer has already fired still writes nothing**. The next card opens a window of its own; an abstention, a grader failure, a self-grade and a correction open none; an edit, an interruption and Finish inside the window all leave the card unwritten. The per-turn record carries the option's state and `auto`. **Amended by AV-050:** the option's state and the countdown no longer reach the screen, and this suite now also asserts that no snapshot of a whole automatic session names the mode. |
| `ShellControllerTest` (`:app`) | The toggle: off on first run, written to the store, survives a controller rebuild, and — unlike the language — does not stop the deck preview. |

**The evidence that "off" is unchanged** is that `PrecommitExchangeTest`,
`GuardedReviewWriterTest`, `ReviewLifecycleTest`, `ReviewJournalTest`, `StudyControllerTest`
and `StudyScenariosTest` all pass **without modification**. Check that before trusting the
new suites:

```sh
cd android && git diff --stat main -- '*PrecommitExchangeTest.kt' '*GuardedReviewWriterTest.kt' '*ReviewLifecycleTest.kt' '*ReviewJournalTest.kt' '*StudyControllerTest.kt' '*StudyScenariosTest.kt'
```

That used to print nothing. **It no longer does, and that is expected.** AV-050 changed
what a study session *does* — the card reads itself, the microphone opens and closes itself
— so `StudyControllerTest` and `StudyScenariosTest` changed with it, in both modes alike.
What AV-047's claim is now checked against is the narrower set that never touched the
surface:

```sh
cd android && git diff --stat main -- '*PrecommitExchangeTest.kt' '*GuardedReviewWriterTest.kt' '*ReviewLifecycleTest.kt' '*ReviewJournalTest.kt'
```

That must still print nothing.

## 2. Live: five turns on the pinned AVD

### What each turn has to show

Every turn is one card on the **app's own study screen**, with the option set on the app's
own setup screen. The harness launches the real app, watches the shipped controller's
snapshots, and exports its evidence when you finish; it taps nothing, speaks nothing, and
**does not touch the switch**.

| Turn | Switch | You do, on the study screen | What must happen |
| --- | --- | --- | --- |
**Amended September 17, 2026 by [AV-050](https://github.com/BrockBadeaux14/AnkiVoice/issues/81).**
Two things about these turns changed. The study screen no longer shows a banner, a
countdown or **Keep it manual**, so nothing in the "what must happen" column can be judged
by the operator's eye any more — the driver reads it from the snapshots the controller
published. And the card reads itself and opens its own microphone, so no turn begins with
Play prompt and Start answer. `automatic-cancelled` is retired with the control it used to
tap, and `automatic-corrected` proves the same thing — a window that is retired writes
nothing — by naming a different rating instead.

| Turn | Switch | You do, on the study screen | What must happen |
| --- | --- | --- | --- |
| `automatic-rule` | **ON** | Start studying · say the reference answer · stop speaking · **touch nothing** · Finish | a window is armed (in the snapshots, not on screen); one review is saved with no confirmation; the record and the journal both say `auto` |
| `automatic-corrected` | **ON** | answer · tap a **different rating** within about three seconds · Finish without confirming | a window was armed and retired; nothing written; the new rating still waiting |
| `automatic-abstain` | **ON** | answer with something only partly right · pick a rating yourself · Confirm · Finish | path `abstain`; **no** window; one review, `touch` |
| `automatic-unavailable` | **ON**, daily limit `0` | answer in your own words · pick a rating · Confirm · Finish | path `unavailable`; **no** window; one review, `touch` |
| `automatic-off` | **OFF** | answer with the reference answer · Confirm · Finish | **no** window; one review, `touch` |

In every one of these turns the study screen must **never** say "Automatic grading". That
is AV-050's acceptance criterion and it applies to all five, including `automatic-off`.

The switch is on the setup screen under **Automatic grading**, below the Study card. It
applies to the **next** session you start, so set it before tapping Start studying. The
driver prints what each turn needs and fails the turn if the session reports the other
state — it will not quietly pass a turn you drove with the switch the wrong way.

`automatic-rule` is the only turn in this repository that may pass with a review nobody
confirmed. It is judged on exactly that: the harness requires `auto` in the turn record and
in the journal, and requires that `confirm` is **not** among the turn's touch actions.

**A capture that comes back empty costs a Try again, not the turn.** The screen says so and
offers Try again; the answer version the rating lands on may be higher than 1. On
`automatic-rule` the window starts when the grade lands, so speak, stop speaking, and then
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
---- automatic-corrected: Automatic grading must be ON ----
== automatic-corrected ==
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
.venv/bin/python tools/av047-qa/run.py build/av047/deck.json --turns automatic-corrected
```

**Keep an attempt that came back wrong.** Re-running until one comes back right and
retaining only that one is not evidence. Move it to `evidence/inconclusive/` with a line
saying what happened and why it proved nothing, as
[AV-019's](../av019/evidence/inconclusive/README.md) are kept.

Stopping the host-side driver does **not** stop the instrumentation on the device.
Force-stop `org.ankivoice.test` as well.

### The cancelled turn

The window is five seconds and, since AV-050, invisible. Tap the different rating within
about three seconds of the rating appearing. If you
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
