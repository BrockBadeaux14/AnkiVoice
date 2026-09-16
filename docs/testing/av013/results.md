# AV-013: Session implemented; live transport check passed

Issue [#14 — Build the deterministic session state machine](https://github.com/BrockBadeaux14/AnkiVoice/issues/14).
Branch `codex/av-013-session-state-machine`.

`ReviewSession` is ported to `:core`, its 53-scenario conformance suite passes, and the
drift guard is in place. **Live criterion 9 passed on September 16, 2026 UTC:**
operator-confirmed “green blue red” (625 ms), “five” (672 ms), explicit Cancel and
permission-denied all have production-route evidence. **Criterion 8 remains in progress.**
A session turn recognized “five blocks” (681 ms) but paused on absent confidence.
No review has yet been written.

## Live verification — September 16, 2026 UTC

[Environment and APK hashes](evidence/live-20260916/environment.json),
[all-attempt ledger](evidence/live-20260916/ledger.json), and
[disposable collection baseline](evidence/live-20260916/session-baseline.json).
The AVD is AV-042's `AnkiVoice_AV005`, cold-booted with audio on port 5588.
Android fingerprint, speech-service version, built-in host input/output and guest media
9/15 match the accepted configuration. Host output was 60% for attempts 1–5, then
restored to 53% before the interactive checks; the operator confirmed their prompts
audible at that level. Input gain was 84%.

| # | Mode | Phrase requested | `capture` returned verbatim | `doneToFinalMs` | Outcome / reviews written |
| --- | --- | --- | --- | --- | --- |
| 1 | permission | — | No capture: missing instrumentation registration | — | no capture; 0 |
| 2 | cancel | — | No capture: missing instrumentation registration | — | no capture; 0 |
| 3 | permission | — | `{"kind":"failed","failure":"SpeechInput.permissionDenied: RECORD_AUDIO not granted","token":"OperationToken(sessionId=av013-permission-03, turn=1, sequence=1)"}` | — | expected failure; 0 |
| 4 | cancel | — | `{"kind":"failed","failure":"SpeechInput.recognizerError: cancelled","token":"OperationToken(sessionId=av013-cancel-04, turn=1, sequence=1)"}` | — | expected failure; 0 |
| 5 | colors | green blue red | `{"kind":"failed","failure":"SpeechInput.noMatch: code 7","token":"OperationToken(sessionId=av013-colors-05, turn=1, sequence=1)"}` | 837 | failed; 0 |
| 6 | colors-interactive | green blue red | `{"kind":"transcript","text":"green blue red","confidence":"absent","token":"OperationToken(sessionId=av013-colors-06, turn=1, sequence=1)"}` | 625 | transcript; 0 |
| 7 | five-interactive | five | `{"kind":"failed","failure":"SpeechInput.noMatch: code 7","token":"OperationToken(sessionId=av013-five-07, turn=1, sequence=1)"}` | 685 | failed; 0 |
| 8 | session-interactive | five blocks | `{"kind":"failed","failure":"SpeechInput.noMatch: code 7","token":"OperationToken(sessionId=av013-session-08/answer, turn=1, sequence=1)"}` | 681 | paused; 0 |
| 9 | five-diagnostic | five | `{"kind":"transcript","text":"five","confidence":"absent","token":"OperationToken(sessionId=av013-five-09, turn=1, sequence=1)"}` | 672 | transcript; 0 |
| 10 | session-interactive | five blocks | `{"kind":"transcript","text":"five blocks","confidence":"absent","token":"OperationToken(sessionId=av013-session-10/answer, turn=1, sequence=1)"}` | 681 | paused; 0 |
| 11 | session-interactive | five blocks | No capture: no operator start before timeout | — | no capture; 0 |

The recognizer's result does not establish what was spoken. For attempt 5 the operator reported: “I heard the prompt but did not see anything. Try
again.” That confirms audibility, not whether the phrase was spoken. The interactive
checks record independent, initially unchecked audibility and spoken-phrase attestations.
Attempts 6 and 9 were operator-confirmed correct transcripts; attempt 7 was an
operator-confirmed spoken phrase that nevertheless returned no-match.
Audio logs reported repeated late reads with inserted silence; that is a diagnostic
observation, not a proven cause or evidence of the historical 220 Hz fallback.
Full diagnostic logs stay under ignored `build/av013/live-20260916/`.

Live execution exposed several harness gaps, corrected only in the test APK:

- AGP replaced the first instrumentation entry with the configured default runner,
  removing `SpeechInstrumentation`. Declaring `ReviewInstrumentation` first preserves
  all three; the packaged manifest and installed components were checked.
- `confirmRating` was supplied before the answer existed. The session harness now
  shows the actual transcript and proposed rating, requires a fresh operator confirmation,
  and rejects pre-capture `confirmRating`. Interactive mode takes a direct Confirm touch;
  the terminal alternative waits for a challenge-bound confirmation file. The
  [device rejection check](evidence/live-20260916/confirmation-before-capture-rejected.txt)
  passed. A successful post-capture confirmation/write is still untested.
- The original timed harness had no visible controls. `LiveVerificationUi` supplies
  Play prompt, Start answer, Done/Cancel and two independent unchecked attestations.
- The pinned recognizer returned absent confidence even for correct text. Attempt 10
  correctly paused, but the harness had not exposed the existing `correctTranscript`
  acceptance path. It now offers **Use this transcript** before grading, then a separate
  rating confirmation. No-match cannot enter that path; raw confidence stays absent.
- The old session `passed` flag meant only “no harness exception”; attempt 8 therefore
  retained `passed:true` even though it paused on no-match. The updated harness records
  `completed` separately and requires a confirmed commit for a passing normal session turn.

The optional diagnostic observer copies actual MIC frames without altering them.
Attempt 9 contains 4.4 seconds of microphone input and 3,682 clipped samples out of
70,400; it still returned the correct phrase. This observation does not explain earlier
no-matches or establish reliability. [PCM analysis](evidence/live-20260916/09-pcm-analysis.json).

The fresh AnkiDroid 2.24.1 installation contained no cards or reviews before import.
The imported AV-002 fixture has eight synthetic cards, seven suspended, and 11 seeded
history rows. The eligible card `1789534470381` asks about three red and two blue blocks,
expects “five blocks,” and initially has zero reviews. Its baseline was copied before
any session turn. A [read-only provider preflight](evidence/live-20260916/session-card-preflight.txt)
returned that card and all four permitted ratings. The no-match session wrote nothing;
[an independent database comparison](evidence/live-20260916/08-unchanged-collection.json)
confirmed unchanged card state and review history. Attempt 10 also stopped before grading
or a review proposal. Attempt 11 used the revised harness but timed out waiting for Play prompt; no capture
opened. Criterion 8 awaits the operator's live completion.

Microphone forwarding and host output were restored at the first pause, recorded in
[the initial cleanup](evidence/live-20260916/initial-pause-cleanup.json); forwarding was enabled again
for interactive checks. At the final pause it was disabled again and the app force-stopped.
A final native database comparison found all cards and review history exactly equal to
the pristine baseline, so no fixture reset was necessary. See [final cleanup](evidence/live-20260916/cleanup.json)
and [evidence validation](evidence/live-20260916/validation.json). Raw PCM and complete
diagnostic logs stay in ignored `build/`.

Current validation: **235 core tests, 52 speech tests, zero failures; nine Python drift
tests pass; module boundaries and both debug APK builds pass.** The earlier implementation
counts below are historical. Evidence: [Gradle](evidence/live-20260916/gradle.txt),
[final harness rebuild](evidence/live-20260916/transcript-review-build.txt),
[Python](evidence/live-20260916/python-tests.txt).

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

## Remaining verification and limits

- **Criterion 8 remains open.** No spoken session turn has reached explicit confirmation
  and a guarded write on the device. The disposable fixture and updated harness are ready.
- **Criterion 9 passed.** Both required phrases now have operator-confirmed correct
  production transcripts. Cancel and permission-denied passed live. All no-matches remain
  in the ledger and do not count as recognition successes.
- **The session harness reached no-match and low-confidence recovery on the device.**
  The live proposal, explicit rating confirmation and guarded commit remain to be verified.
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

Resume criterion 8 with the interactive session harness (the last waiting screen timed
out). Enable `adb -s emulator-5588 emu avd hostmicon`, use disposable deck
`1789534470372` while this same-day fixture remains valid, then follow [the runbook](runbook.md): speak the real answer,
explicitly accept its transcript if confidence is absent, then separately confirm the
actual proposed rating. Verify exactly one added native review and reset the disposable
fixture. Criterion 9 is complete; every failed and successful attempt remains recorded.

No production speech, answer-policy, session or writer behavior was changed for these
checks. The broader study-flow UI, reliability run and interruption matrix remain outside
this verification.
