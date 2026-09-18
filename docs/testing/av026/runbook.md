# AV-026 runbook: verify the study screen and the integrated mobile flow

Issue [#27 — Build the real study surface and verify the integrated mobile flow](https://github.com/BrockBadeaux14/AnkiVoice/issues/27).

Two layers, deliberately separate, in the manner of the [AV-019 runbook](../av019/runbook.md).
The offline layer proves the study screen's controller — what it shows, which controls it
offers in every state, that only Confirm writes, that an interruption releases the
microphone and requires a reload — against the AV-041 fakes, and runs anywhere. The live
layer proves the one thing no JVM test can: that the **composed app** — the real activity,
controller, session, grader, transport and journaled writer — carries a learner's spoken
answer through the real study screen to a real AnkiDroid collection, and that everything
the screen says it did not write, it did not.

The offline layer writes no review. **The live layer writes seven**, one per turn that ends
in a confirmation, deliberately: the study screen is the learner's one path to the writer,
and a check that wrote nothing would prove nothing about it. Both the driver and the harness
refuse any deck whose name does not start with `AV002`. **Back the collection up first.**

## 1. Offline: the surface, the scenarios and the composition

```sh
cd android && ./gradlew --console=plain checkModuleBoundaries :core:test :app:testDebugUnitTest
```

```sh
.venv/bin/python -m unittest discover -s tests
```

`:app:testDebugUnitTest` runs three suites over the fakes, with no emulator and no network:

| Suite | What it proves |
| --- | --- |
| `StudyControllerTest` | The snapshot is derived from the session: prompt, transcript and version, grading status, Announced position, outcome. Every state offers exactly the controls that apply, and no halted state offers a control that writes. Done and Cancel reach a capture in flight. An app switch and a screen lock each interrupt, release the microphone and require Reload; a capture, a grading reply and a spoken command that land after teardown are dropped; Reload consults the startup gate. AV-010's skipping and the five-card stop. The evidence export. |
| `StudyScenariosTest` | Decision 4's eleven AV-007 scenarios — `confirmed-commit`, `precommit-correction`, `corrected-transcript-and-stale-grade`, `absent-confirmation`, `spoken-confirmation`, `early-closure-and-touch-fallback`, `grader-unavailable`, `skip-request`, `native-undo-handoff`, `single-active-reviewer`, `unconfirmed-write-variants` — driven through the controller, asserting the screen at each step. |
| `StudyCompositionTest` | AV-045's composition under the new surface: rules first, the route only on a rule miss, the reconciliation gate, the journaled writer, automatic grading after an answer settles and after an edit, and that no card content or transcript reaches AV-020's diagnostics. |

None of this touches a device. That is the next section.

## 2. Live: ten turns on the pinned AVD

### What each turn has to show

Every turn is one card on the **app's own study screen**. The harness launches the real
app, watches the shipped controller's snapshots, and exports its evidence when you finish;
it taps nothing and speaks nothing.

> **Amended September 17, 2026 by [AV-050](https://github.com/BrockBadeaux14/AnkiVoice/issues/81).**
> The study screen no longer has two taps in front of the microphone. Wherever a routine
> below says **Play prompt** and **Start answer**, the card now reads itself when it is
> offered and the microphone opens itself when that playback settles — so you wait rather
> than tap. Both controls are still there for a reading or an open that did not happen.
> **Done** is likewise optional: the capture ends itself a moment after you stop speaking,
> and tapping Done only hurries it. Everything else in these routines is unchanged.


| Turn | You do, on the study screen | What must happen |
| --- | --- | --- |
| `rule-match` | Play prompt · Start answer · say the reference answer · Done · Confirm · Finish | grading path `rule`; one review; journal settled `confirmed` |
| `ai-labelled` | answer in your own words (not the reference) · Confirm the AI suggestion · Finish | path `ai-free` or `ai-paid`; one review |
| `abstain-self-grade` | answer with something partly right · pick a rating yourself · Confirm · Finish | path `abstain`; a self-grade; one review |
| `corrected-confirmed` | answer · tap a **different** rating than the one waiting · Confirm · Finish | a correction; the corrected rating is what the revlog records |
| `transcript-edit` | answer · Edit transcript, change it, Use this transcript · Confirm · Finish | an edit; a grade at the new version; one review |
| `skip` | Play prompt · Skip · Finish | `skip_requested`; nothing written; the card unchanged |
| `pause-resume` | Play prompt · Pause · Resume · Finish | a pause and a resume; nothing written |
| `interruption-reload` | Play prompt · Start answer · **press Home while listening** · return · Reload · Finish | `app_switch`; the microphone released; a fresh session; nothing written |
| `undo-handoff` | answer · Confirm · **Wrong rating? Undo in AnkiDroid** · use Undo in AnkiDroid · come back | one write, the session closed for the handoff; the driver asks what AnkiDroid offered |
| `route-refused-self-grade` | with the **daily limit at 0**: answer in your own words · pick a rating · Confirm · Finish | path `unavailable`; a self-grade; one review |

Each confirmation is your choice of tapping **Confirm** or tapping **Speak a command** and
saying "confirm"; the source is recorded as it happened. AV-019 already evidences a spoken
confirm executing at raw score 0.972, and a spoken one costs a second microphone open in a
boot that may not have one left, so **tap the confirmation unless the spoken path is the
point of the turn**. Speak it on `rule-match` if you want the spoken path recorded here too.

**A capture that comes back empty costs a Try again, not the turn.** The screen says so
when it happens and offers Try again; each attempt is recorded, and the answer version the
rating finally lands on may be higher than 1. That is the retry path working, not drift.

### Prerequisites

- The pinned AVD `AnkiVoice_AV005` from AV-042's accepted configuration. The driver
  cold-boots it on port 5588 with `-allow-host-audio -no-snapshot -no-boot-anim` and turns
  the host microphone on, because without those the guest microphone is zeroed. Check which
  AVD a serial actually is before assuming — `adb -s <serial> emu avd name` — and `sync`
  then `emu kill` a stray instance rather than starting a second copy.
- AnkiDroid 2.24.1 installed, its API enabled, and AnkiVoice holding the database
  permission and `RECORD_AUDIO`. The install, the permission grant and the pushed
  collection all survive a cold boot without `-wipe-data`.
- A **disposable** AV-002 collection, generated as
  [the fixtures runbook](../voiceqa-fixtures.md) describes. **Back it up first**: seven
  turns write a real review and none of them undoes it.
- For `ai-labelled` and `route-refused-self-grade`: the owner's OpenRouter key entered in
  the app's setup screen with the disclosure acknowledged. No key lives on the host, and
  paid requests spend the owner's credit within AV-043's cap. Before
  `route-refused-self-grade`, set **Daily limit** to `0` on the setup screen so every
  request is refused by the ledger; set it back afterwards.
- A person at the machine with a working microphone. Every turn but `skip` and
  `pause-resume` needs a spoken answer; nothing here synthesizes a voice.

One turn per boot. The emulator's coreaudio backend leaks a listener per microphone open
and exits on the second or third of a boot, which would take the turn down with it.
`interruption-reload` and `transcript-edit` may need two opens; plan for them to be the
only captures in their boot.

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

Confirm the instrumentation survived packaging — AGP rewrites the *first* entry to the
configured default runner, which is why `ReviewInstrumentation` is declared first:

```sh
adb shell pm list instrumentation | grep StudyInstrumentation
```

### Record the deck the run may touch, then run it

```sh
mkdir -p build/av026 && echo '[<deckId>, "AV002 Baseline"]' > build/av026/deck.json
```

```sh
.venv/bin/python tools/av026-qa/run.py build/av026/deck.json
```

For each turn the driver cold-boots the AVD, clears the journal, selects the disposable
deck in the app, snapshots the collection, launches the harness — which launches the app —
and then waits: **you drive the study screen from here.** Tap **Start studying** on the
setup screen, follow the turn's routine from the table above, and end with **Finish** (or,
for `undo-handoff`, with the handoff). The driver prints the screen's status line as it
changes, snapshots the collection again when the session closes, and for `undo-handoff`
asks on the terminal what AnkiDroid offered.

Expected output:

```
== rule-match ==
  reviews added 1 (expected 1) · journal entries 1 · passed True
== ai-labelled ==
  reviews added 1 (expected 1) · journal entries 1 · passed True
== abstain-self-grade ==
  reviews added 1 (expected 1) · journal entries 1 · passed True
== corrected-confirmed ==
  reviews added 1 (expected 1) · journal entries 1 · passed True
== transcript-edit ==
  reviews added 1 (expected 1) · journal entries 1 · passed True
== skip ==
  reviews added 0 (expected 0) · journal entries 0 · passed True
== pause-resume ==
  reviews added 0 (expected 0) · journal entries 0 · passed True
== interruption-reload ==
  reviews added 0 (expected 0) · journal entries 0 · passed True
== undo-handoff ==
  reviews added 0 (expected 0) · journal entries 1 · passed True
== route-refused-self-grade ==
  reviews added 1 (expected 1) · journal entries 1 · passed True

AV-026 live check complete
```

A subset re-runs on its own, which is how a turn interrupted by an emulator fault is
repeated. The summary is rebuilt from every retained turn file, so re-running one does not
drop the others:

```sh
.venv/bin/python tools/av026-qa/run.py build/av026/deck.json --turns transcript-edit
```

**Keep an attempt that came back wrong.** Re-running until one comes back right and
retaining only that one is not evidence. Move it to `evidence/inconclusive/` with a line
saying what happened and why it proved nothing, as
[AV-019's](../av019/evidence/inconclusive/README.md) are kept.

Stopping the host-side driver does **not** stop the instrumentation on the device. Force-stop
`org.ankivoice.test` as well, or the next run meets a screen from the last one.

### The interruption turn

Press Home **while the screen says it is listening**. The microphone is released at once —
the answer window does not run out first — and when you return the screen says study was
interrupted, offers **Reload**, and offers nothing that writes. Reload goes through AV-018's
startup gate before any card is offered, then opens a fresh session on the same card.

### The Undo handoff

After the review is written, tap **Wrong rating? Undo in AnkiDroid**. AnkiVoice **closes** the
session and tells you to use AnkiDroid's own Undo; the driver then asks on the terminal what
AnkiDroid actually offered. Answer it honestly — "AnkiDroid did not offer Undo" is a result,
not a failure of the run. Returning to AnkiVoice must require a fresh session: nothing resumes
the closed one, and the card and its scheduling are read from AnkiDroid again.

## 3. Check the retained evidence offline

```sh
.venv/bin/python tools/av026-qa/validate.py
```

It re-derives every claim [the results page](results.md) makes from the retained snapshots
rather than trusting the harness or the driver: the reviews are counted from the collection's
own revlog, each one is matched to a journal entry settled `confirmed` at the same rating and
card, the three no-write turns are checked for byte-identical card scheduling, each turn's
evidence is checked for the grading path, the recognition outcomes, the retry and edit counts,
the confirmation source, the corrections and the touch actions #29 needs, and each turn is
checked to have done what it is named for. `tests/test_av026_evidence.py` runs it with the
rest of the Python suite, and CI runs it on every push. A partial run is reported as partial —
the outstanding turns are named and the exit status stays zero — and before the live run it
reports that there is nothing to validate rather than passing silently.

Full collection copies stay under ignored `build/av026/`; only the synthetic JSON evidence in
`docs/testing/av026/evidence/` is retained.

## 4. Reset

The run leaves seven extra reviews in the disposable collection, one of which the
`undo-handoff` turn may have undone in AnkiDroid. Restore the backup, or regenerate the fixture
as the [fixtures runbook](../voiceqa-fixtures.md) describes. The journal is app-private;
clearing the app's data removes it. Set the daily limit back to its previous value after
`route-refused-self-grade`.
