# AV-013: The session state machine is implemented; its live check is not yet run

Issue [#14 — Build the deterministic session state machine](https://github.com/BrockBadeaux14/AnkiVoice/issues/14).
Branch `codex/av-013-session-state-machine`.

**Result: `ReviewSession` is ported to `:core`, the 53-scenario conformance suite passes
as JVM tests, and the drift guard is in place. The live verification on the pinned AVD —
this card's last acceptance criterion, including AV-025's absorbed check — has not been
performed.** That run needs a person speaking into a running emulator, so it could not be
produced from the implementation session. Until it exists, no real speech has passed
through the shipped `:speech` transport and no turn has run end to end on a device.

The offline evidence below says the state machine orders, guards and classifies a turn
correctly. It never says the turn works on a device.

## What was implemented

`ReviewSession` and its state model now live in `:core`, in
[`org/ankivoice/core/session/`](../../../android/core/src/main/kotlin/org/ankivoice/core/session).
It is a port of `ReviewSession` in `tools/av007_contracts.py`, with two deliberate changes.

**The six states AV-013 requires are explicit.** The binding folds pause, interruption,
unsupported card and outcome-unknown into `paused`/`stopped`, has no retry state, and
does not model reveal at all. `SessionState` separates them:

| Kotlin state | Binding state | What it means |
| --- | --- | --- |
| `PAUSED` | `paused` | Resumable; the learner may fix the cause |
| `OUTCOME_UNKNOWN` | `paused` | A write may have landed; reconcile before anything else |
| `INTERRUPTED` | `stopped` | The single-active-reviewer precondition broke |
| `UNSUPPORTED` | `stopped` | The offered card is not a usable VoiceQA card |
| `RETRYING` | `listening` | An explicit Try again opened a new revision |
| `REVEALING` | — | Reveal playback in flight; transient |

`SessionState.binding` carries that mapping, so the ported suite can assert against the
source it came from without freezing the Kotlin model to the coarser one. Every binding
state is reachable, which `ScenarioDriftTest` checks.

**The answer phase is AV-012's, not the binding's.** The binding handles capture inline.
The port drives `AnswerTurn` instead, so the bounded window, the finalization deadline,
the six answer states and the revision identity stay in one place and the session reaches
the recognizer only through AV-007's `SpeechInput`. `:core` remains pure Kotlin/JVM;
`checkModuleBoundaries` and a Python guard both check that no platform type appears.

Beyond the port:

- **Token rules.** Session, card, turn and attempt are all bound into tokens. Capture
  tokens are namespaced apart from playback, grading and proposal tokens, so a token from
  one operation can never match another that happens to share a turn and sequence.
- **Invalidate then clean up.** Teardown nulls every token *before* it cancels anything,
  because cancellation itself produces late callbacks. Three tests drive a fake that
  answers its own `cancel()` and assert the answer is dropped.
- **Main-thread confinement.** Every transition checks the owning thread and fails loudly
  rather than corrupting the turn.
- **Confirmation.** A rating reaches the writer only from `commit`, only for an intent
  carrying a current, final, sufficiently confident confirmation for this attempt and
  revision. A transcript edit discards the pending suggestion, the pending rating and any
  confirmation bound to the old revision.
- **Failures.** Every fault from `:speech` or AV-012 routes into a halt that preserves the
  card, with `recoveryOptions` naming the explicit manual controls. Self-grade appears
  only when a gradable transcript exists, so a fault never supplies one.

## What was verified, and how

```sh
cd android
./gradlew --console=plain checkModuleBoundaries :core:test :ankidroid:testDebugUnitTest \
  :provider:testDebugUnitTest :speech:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
python -m unittest discover -s tests
```

- **The 53-scenario conformance suite passes**, with no emulator and no network: 19 named
  scenarios in `NamedScenariosTest` and the 34-mode failure sweep in `FailureSweepTest`.
  Each named test asserts what its Python counterpart records in `run.note(...)`, so a
  behavioral difference fails rather than being described.
- **18 further guard tests** in `SessionGuardsTest` cover the token rules, the teardown
  ordering, confinement, the explicit states, and the no-overlap and one-rating-per-answer
  invariants.
- **`:core` is 235 unit tests, 0 failures** (124 before this card). Across all five
  modules: 462 tests, 0 failures — `:core` 235, `:provider` 63, `:ankidroid` 61,
  `:speech` 52, `:app` 51.
- **The drift guard is in place**, in the manner of AV-041's. `tools/av013_scenarios.py`
  derives the manifest from `tools/av007_scenarios.py`; `tests/test_av013_session.py`
  fails when the checked-in copy is stale or a named scenario is unported; `:core`'s
  `ScenarioDriftTest` fails when the Kotlin suite, the failure taxonomy, the binding state
  coverage or the interruption kinds stop matching it. Both run in CI already.
- **222 Python tests, 0 failures.** `checkModuleBoundaries`, `:app:assembleRelease`,
  `:app:assembleDebugAndroidTest` and `:app:lintDebug` all pass.

Two test failures during development were defects in the new tests, not the session: one
asserted that a competing *native* write counted against this caller, and one expected the
advisory suggestion to survive a proposal, which the binding also clears. Both assertions
were corrected to the behavior the binding actually specifies.

## What was **not** verified

- **The live run on the pinned AVD has not happened**, so this card's last two acceptance
  criteria are open. No turn has gone from a spoken answer through explicit confirmation to
  a guarded write on a device.
- **AV-025's absorbed live check is still outstanding.** No real speech has passed through
  the shipped `:speech` transport; `doneToFinalMs` remains unmeasured for it (AV-042's
  690 ms and 687 ms belong to the disposable probe); and the Cancel and permission-denied
  paths are covered offline only. Nothing in this card changed that, and nothing here
  claims otherwise.
- **The harness for the run exists but has never executed.** `SessionInstrumentation`
  compiles, is registered and is exercised by nothing but the compiler. The first live run
  may find defects in it.
- **Recognition quality is not simulated and cannot be.** Every offline test uses scripted
  transcripts. A green suite says the session handles a transcript correctly, never that a
  transcript is correct.
- **Grading in the live harness is AV-015's rules only.** An unmatched answer is reported
  uncertain, which proposes no rating and routes to an explicit self-grade. AI grading
  (#18) is not wired into the live path here, and #27 owns the integrated flow.
- **Interruption safety is unchanged.** AV-040 recorded that no interruption signal reaches
  the app while the recognizer holds the microphone. `interrupt` is the session's response
  to a signal it is given; it does not create one. Backgrounding, screen lock, route
  changes, Bluetooth and real calls remain with
  [#32](https://github.com/BrockBadeaux14/AnkiVoice/issues/32).
- **Emulator results establish nothing about physical devices.**

## Reproduce

[The runbook](runbook.md) separates the two layers: the offline suite and the drift guard
run anywhere, and the live section gives the exact `am instrument` commands, the AVD and
host-audio prerequisites, and the rule that every attempted turn is recorded — including
the ones that come back wrong.

## Next

The live section of the runbook is the remaining work on this card. Run it on the pinned
AVD, record every turn and its `doneToFinalMs` in the table the runbook provides, and
update [AV-025's results](../av025/results.md) once the absorbed check has actually run.

Per the issue's recorded consequence: **a failure in the live run is a finding against
`:speech`, not against the state machine.** Reopen
[#26](https://github.com/BrockBadeaux14/AnkiVoice/issues/26) or open a follow-up rather
than repairing the transport here.
