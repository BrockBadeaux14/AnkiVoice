# AV-018 runbook: verify the session journal and its reconciliation

Issue [#20 — Implement review commit tracking and recovery](https://github.com/BrockBadeaux14/AnkiVoice/issues/20).

Two layers, deliberately separate, in the manner of the
[AV-013 runbook](../av013/runbook.md). The offline layer proves the journal's durability,
its settle rules and every row of the reconciliation table, and runs anywhere. The live
layer proves the one thing no JVM test can: that a real process kill mid-submission leaves
a readable entry, and that reconciling it against the real collection reaches the specified
resolution **without adding a second review**.

The offline layer reads no collection and writes no review. **The live layer writes one
review** in its second case — deliberately, because that is the case being measured — which
is why it refuses any deck whose name does not start with `AV002`.

## 1. Offline: durability, settle rules, the table and the size bound

```sh
cd android
./gradlew --console=plain checkModuleBoundaries :core:test :app:testDebugUnitTest
```

```sh
python -m unittest discover -s tests
```

`:core:test` runs `ReviewJournalTest`, `JournalReconciliationTest` and `JournalSizeTest`:
the crash windows either side of dispatch, duplicate and stale settles, a truncated and a
corrupt line, retention pruning, the transcript round trip, and every row of the
reconciliation table including a competing native write. `:app:testDebugUnitTest` runs
`SessionJournalTest` and `ShellJournalTest`: the durable file, the backup exclusion, the
assertion that transcript text never reaches AV-020's diagnostics, and the rule that no
card is offered before reconciliation has run.

To re-read the measured worst case on disk that justifies the retention bound:

```sh
cd android && ./gradlew --console=plain :core:test --tests '*JournalSizeTest' -i | grep 'bytes total'
```

None of these touches a device, and none of them is evidence that a real kill leaves a
readable file. That is the next section.

## 2. Live: two process kills on the pinned AVD

Prerequisites:

- The pinned AVD from AV-042's accepted configuration, booted, with AnkiDroid 2.24.1
  installed, its API enabled, and AnkiVoice holding the database permission.
- A **disposable** AV-002 collection, generated as
  [the fixtures runbook](../voiceqa-fixtures.md) describes. **Back it up first** — the
  second case writes a real review and does not undo it.
- Exactly one attached device. The runner refuses to guess between several.

Build and install both APKs:

```sh
cd android && ./gradlew --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
```

```sh
adb install -r -d android/app/build/outputs/apk/debug/app-debug.apk
```

```sh
adb install -r -d android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Confirm all four instrumentation components survived packaging — AGP rewrites the *first*
entry to the configured default runner, which is why `ReviewInstrumentation` is declared
first:

```sh
adb shell pm list instrumentation | grep ankivoice
```

Record the deck the run may touch, then run both cases:

```sh
echo '[<deckId>, "AV002 Baseline"]' > build/av018/deck.json
```

```sh
python3 tools/av018-qa/run.py build/av018/deck.json
```

Expected output:

```
before-dispatch: failed, reviews added by the kill 0, by reconciliation 0
after-dispatch: outcome-unknown, reviews added by the kill 1, by reconciliation 0
AV-018 live check complete
```

### What the runner does, and where the kill lands

For each case it clears the journal, asks the real provider which card it would offer,
snapshots the collection, then starts the `strand` instrumentation. That mode journals one
genuinely confirmed intent through `JournaledReviewWriter` and then **blocks inside the
transport**:

| Case | Blocks | The kill therefore lands |
| --- | --- | --- |
| `before` | before `AnkiDroidReviewTransport.answerCard` is called | between the durable journal write and the single dispatch |
| `after` | after `answerCard` returned, before the writer settles | between the dispatch and the settle |

The host waits for the instrumentation's marker file, then runs `am force-stop` on the app
and test packages. That is a real process kill, not a simulated one: no `finally` block
runs, nothing is flushed on the way out, and what remains is whatever reached the
filesystem. The runner then snapshots the collection again, runs the `reconcile`
instrumentation in a **new** process, and snapshots once more.

The instrumentation refuses to run unless the build fingerprint is an emulator, the
`confirm` argument is `AV018_SYNTHETIC_ONLY`, and the named deck exists and begins with
`AV002`. Nothing in the app's UI reaches it.

## 3. Check the retained evidence offline

```sh
python3 tools/av018-qa/validate.py
```

It re-derives every claim [the results page](results.md) makes from the retained snapshots
rather than trusting the summary, and `tests/test_av018_evidence.py` runs it with the rest
of the Python suite. Full collection copies stay under ignored `build/av018/`; only the
synthetic JSON evidence is retained.

## 4. Reset

The second case leaves one extra review in the disposable collection. Restore the backup,
or regenerate the fixture as the [fixtures runbook](../voiceqa-fixtures.md) describes. The
journal itself is app-private; clearing the app's data removes it.
