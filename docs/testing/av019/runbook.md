# AV-019 runbook: verify the pre-commit exchange and the single write

Issue [#21 — Make rating correction predictable](https://github.com/BrockBadeaux14/AnkiVoice/issues/21).

Two layers, deliberately separate, in the manner of the
[AV-018 runbook](../av018/runbook.md). The offline layer proves the exchange — the
announcement and its source, the re-prompt rule, one commit per attempt, the three outcome
classes and the Undo handoff — against the AV-041 fakes, and runs anywhere. The live layer
proves the one thing no JVM test can: that an explicit confirmation on a real device
reaches a real AnkiDroid collection **once**, leaves a journal entry settled from what the
writer returned, and that a correction or an abandoned exchange writes nothing at all.

The offline layer writes no review. **The live layer writes three** — one per confirmed
case, deliberately, because the exchange is the only path to the writer and a run that
wrote nothing would prove nothing about it. Both the driver and the instrumentation refuse
any deck whose name does not start with `AV002`. **Back the collection up first.**

## 1. Offline: the exchange, the write path and the outcomes

```sh
cd android && ./gradlew --console=plain checkModuleBoundaries :core:test :app:testDebugUnitTest
```

```sh
python -m unittest discover -s tests
```

`:core:test` runs `PrecommitExchangeTest`: the announcement for each of the four sources
and for an abstention, correct-then-confirm re-announcing, the re-prompt-once rule, the
single commit, a correction that never writes, silence and a speech failure and a grading
failure each writing nothing with no timeout path, a transcript edit invalidating both the
pending rating and the confirmation collected for it, the three outcome classes, the
learner-reported reconcile and the Undo handoff.

`:app:testDebugUnitTest` runs `CommandControllerTest` and `StudyCompositionTest`: the
surface fields the announcement produces, the outcome controls, the abstain path through
the self-grade control, and — the wiring this card had to add — a confirmed rating
journalled before the write and settled from the outcome, with the settled transcript
supplied only for the revision the rating came from. Those tests call `studyWriter`, the
same function `ShellApplication` composes the session with, so the wiring under test is the
wiring that ships.

None of this touches a device. That is the next section.

## 2. Live: five cases on the pinned AVD

### What each case has to show

| Case | The operator does | What must happen |
| --- | --- | --- |
| `confirmed` | answers, then confirms the rating as proposed, then a second Confirm is attempted | one review; the journal entry settles `confirmed`; the duplicate is not even offered |
| `corrected` | answers, corrects the rating, confirms the corrected one | one review **at the corrected rating**; the journal entry settles `confirmed` |
| `correction-only` | answers, corrects, finishes the session | no review, no journal entry: a correction is not a commit |
| `abandoned` | answers, pauses, finishes the session | no review, no journal entry: nothing expires into a write |
| `undo-handoff` | answers, confirms, then uses AnkiDroid's own Undo | one review, the session **stopped**, and the card read again afterwards |

Each confirmation is the operator's choice of **Speak it** or **Tap it**, and the source is
recorded as it happened. AV-044 ([#67](https://github.com/BrockBadeaux14/AnkiVoice/issues/67))
established that the pinned engine does supply confidence, and on September 17, 2026 a
spoken confirm **executed** at raw score 0.972 and wrote its review; if one is refused
instead, the re-prompt rule is what should be recorded, not worked around.

The rules abstain on plenty of real answers. When they do, the exchange opens Announced
with **no** pending rating and the harness takes AV-019's abstain path — `selfGrade` names
the card's first permitted rating and announces it as learner-named — so every case reaches
a pending rating whatever the grade was. That is a feature of the run, not a fallback: it
is the only live evidence for the abstain path. The screen says so when it happens.

### Prerequisites

- The pinned AVD `AnkiVoice_AV005` from AV-042's accepted configuration. The driver
  cold-boots it on port 5588 with `-allow-host-audio -no-snapshot -no-boot-anim` and turns
  the host microphone on, because without those the guest microphone is zeroed.
- AnkiDroid 2.24.1 installed, its API enabled, and AnkiVoice holding the database
  permission and `RECORD_AUDIO`.
- A **disposable** AV-002 collection, generated as
  [the fixtures runbook](../voiceqa-fixtures.md) describes. **Back it up first**: three
  cases write a real review and none of them undoes it.
- A person at the machine with a working microphone. Every case needs one spoken answer;
  nothing here synthesizes a voice, and the owner asked on September 16, 2026 that spoken
  evidence be spoken.

One case per boot. The emulator's coreaudio backend leaks a listener per microphone open
and exits on the second or third of a boot, which would take the run down with it.

Check which AVD a serial actually is before assuming — `adb -s <serial> emu avd name`. On
September 17, 2026 `AnkiVoice_AV005` was already running on the **default** port 5554, and
starting a second copy failed with "Running multiple emulators with the same AVD". `sync`,
`emu kill` the stray instance, and let the driver cold-boot on 5588. The AnkiDroid install,
its database permission and the AV002 collection all survive between sessions; only the
APKs need reinstalling.

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

Confirm the instrumentation components survived packaging — AGP rewrites the *first* entry
to the configured default runner, which is why `ReviewInstrumentation` is declared first:

```sh
adb shell pm list instrumentation | grep ankivoice
```

`org.ankivoice.app.ExchangeInstrumentation` must be in that list.

### Record the deck the run may touch, then run it

```sh
mkdir -p build/av019 && echo '[<deckId>, "AV002 Baseline"]' > build/av019/deck.json
```

```sh
python3 tools/av019-qa/run.py build/av019/deck.json
```

The driver cold-boots the AVD, clears the journal, snapshots the collection, starts the
harness and then waits: **the operator drives the emulator screen from here.** Each case
puts a short routine on the device, in this order:

1. **Answer the card** — listen for the question, tap *Start answer*, say the answer out
   loud. Anything close to the card's reference answer is fine; the rating that comes back
   is what is being measured, not recall.
2. **Confirm what you said** — two unchecked statements. Check only what actually happened.
3. The case's own step — *Confirm*, *Speak it / Tap it*, a correction, a pause, or the
   handoff to AnkiDroid.

Expected output:

```
== confirmed ==
  reviews added 1 (expected 1) · journal entries added 1 · passed True
== corrected ==
  reviews added 1 (expected 1) · journal entries added 1 · passed True
== correction-only ==
  reviews added 0 (expected 0) · journal entries added 0 · passed True
== abandoned ==
  reviews added 0 (expected 0) · journal entries added 0 · passed True
== undo-handoff ==
  reviews added 1 (expected 1) · journal entries added 1 · passed True

AV-019 live check complete
```

A subset re-runs on its own, which is how a case interrupted by an emulator fault is
repeated. The summary is rebuilt from every retained case file, so re-running one does not
drop the others:

```sh
python3 tools/av019-qa/run.py build/av019/deck.json --cases corrected
```

**Keep an attempt that came back wrong.** Re-running until one comes back right and
retaining only that one is not evidence. Move it to `evidence/inconclusive/` with a line
saying what happened and why it proved nothing, as
[the three from September 17, 2026](evidence/inconclusive/README.md) are kept.

### The Undo handoff

The last case is the only one that leaves the emulator. After the review is written,
AnkiVoice **stops** and tells the operator to use AnkiDroid's own Undo; the harness then
asks what AnkiDroid actually offered. Answer it honestly — "AnkiDroid did not offer Undo"
is a result, not a failure of the run. Returning to AnkiVoice must require a fresh session:
nothing resumes the stopped one, and the card and its scheduling are read from AnkiDroid
again.

## 3. Check the retained evidence offline

```sh
python3 tools/av019-qa/validate.py
```

It re-derives every claim [the results page](results.md) makes from the retained snapshots
rather than trusting the harness summary: the reviews are counted from the collection's own
revlog, each one is matched to a journal entry settled `confirmed` at the same rating and
card, the two no-write cases are checked for byte-identical card scheduling, every
announced rating is checked for a source and a transcript revision, and each confirmation's
recorded source is checked to be one the operator actually gave.
It also requires each case to have reached the steps it is named for, so a case the harness
skipped cannot pass the no-write checks while demonstrating nothing.
`tests/test_av019_evidence.py` runs it with the rest of the Python suite, and CI runs it on
every push. A partial run is reported as partial — the outstanding cases are named and the
exit status stays zero — and before the live run it reports that there is nothing to
validate rather than passing silently.

Full collection copies stay under ignored `build/av019/`; only the synthetic JSON evidence
in `docs/testing/av019/evidence/` is retained.

## 4. Reset

The run leaves three extra reviews in the disposable collection, one of which the
`undo-handoff` case may have undone in AnkiDroid. Restore the backup, or regenerate the
fixture as the [fixtures runbook](../voiceqa-fixtures.md) describes. The journal is
app-private; clearing the app's data removes it.
