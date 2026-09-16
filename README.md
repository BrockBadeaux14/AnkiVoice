# AnkiVoice

An Android-first college project for voice-based Anki study using a dedicated
VoiceQA note type, with cloud speech and AI grading allowed and the app kept in
the foreground.

The repository contains planning documentation and repeatable synthetic Anki
fixtures. Implementation and runtime validation are tracked on the
[project board](https://github.com/users/BrockBadeaux14/projects/2).

See [AV-001: Platform and pilot constraints](docs/decisions/0001-platform-and-pilot.md)
for the selected platform, test environment, deadline, pilot constraints, and
Android-first dependency plan.

See [AV-002: VoiceQA test collection](docs/testing/voiceqa-fixtures.md) to generate
disposable collections, run the fixture checks, and reset or back up a test run.

See [AV-004: AnkiDroid review access](docs/testing/av004-ankidroid-review-access.md)
for the pinned emulator investigation, captured evidence, reproducible probe, and
constraints on submission verification.

See [AV-005: Foreground speech](docs/testing/av005-foreground-speech.md) for the
constrained-go result on emulator speech, the full sixteen-scenario matrix and its
measurements, and the limits of that evidence. The
[runbook](docs/testing/av005/runbook.md) explains how to run the twelve-turn loop
with your own voice, which is the one thing the recorded matrix could not supply.

See [AV-006: Speech and grading providers](docs/decisions/0006-speech-and-grading-providers.md)
for the measured native speech/OpenRouter comparison, free-only provider decision,
fallback requirements, and reproducible evidence.

See [AV-040: Live microphone and interruptions](docs/testing/av040-live-microphone.md)
for the bounded human investigation, its no-go result, raw evidence and measured
policy limits. Its original #13/#26 gate is superseded for the narrowed MVP by
the AV-042 decision below.

See [AV-042: Live human voice input](docs/testing/av042/results.md) for the owner's
subsequent scope change to a voice-input-only MVP, the emulator microphone failure
diagnosis, and the confirmed “green blue red” and “five” transcripts. The broader
interruption requirements are deferred for this scope; the prior failures remain
recorded. The [unblock decision](docs/testing/av042/results.md#downstream-unblock-decision)
satisfies the #26 (AV-025) and #13 (AV-012) capability prerequisite on acceptance
and supplies their initial implementation policy. The
[runbook](docs/testing/av042/runbook.md) reproduces the working probe.

See [AV-007: Integration contracts and review lifecycle](docs/contracts/av007-session-contracts.md)
for the five session contracts, the card-identity and capability rules bound to AV-004's
measurements, and the five-state review lifecycle. The
[scripted transcripts](docs/contracts/av007/transcripts.md) show the in-memory fakes
driving every state and failure mode with no emulator and no network, including
explicit spoken/touch confirmation, transcript edits, stale callbacks and manual
self-grading fallback.

See [AV-022: Android implementation](docs/decisions/0022-android-implementation.md)
for the scored Kotlin-versus-Flutter decision, the pinned build/support baseline,
component ownership across the five AV-007 contracts, and the implementation
breakdown for the Android cards.

See [AV-041: Android build](android/README.md) for the `android/` Gradle build, its
pins and five modules, and the Kotlin port of the AV-007 contract types and fakes. That
page also covers the drift guard that keeps the port in step with the Python binding.

See [AV-023: Mobile shell and permission onboarding](docs/testing/av023/results.md)
for the Compose shell, deck selection, debug sample-session controls, private settings,
and the pinned-emulator evidence. The [runbook](docs/testing/av023/runbook.md) reproduces
the onboarding and lifecycle checks without submitting any reviews.

See [AV-039: VoiceQA note type provisioning](docs/testing/av039/results.md)
for the explicit setup action, the reuse/refuse and demo-content policy, the confirmed
AnkiDroid 2.24.1 provider route, and the pinned-emulator evidence that no review was
added or altered. The [runbook](docs/testing/av039/runbook.md) reproduces every
provisioning scenario on a disposable collection.

See [AV-020: Provider credentials, usage controls and diagnostics](android/README.md#provider-credentials-usage-controls-and-diagnostics)
for the Keystore-wrapped runtime credential, the free-only route guard, the durable quota
ledger, the retention disclosure and the content-free diagnostics. Its
[runbook](docs/testing/av020/runbook.md) records the one live smoke request.

See [AV-015: Rule-based grading](android/README.md#rule-based-grading) for the on-device
policy. It suggests Good only on an exact normalized match or a one-letter slip in a long
word that is not a number, negation or unit. Otherwise it defers to the AI grader or an
explicit self-grade.

See [AV-012: Answer boundaries and transcript policy](android/README.md#answer-boundaries-and-transcript-policy)
for the bounded answer window, the separate finalization deadline, the six transcript
states and the revision rules that keep a stale transcript or grade off the screen.

See [AV-016: Semantic grading and optional rubrics](android/README.md#semantic-grading-and-optional-rubrics)
for the policy layer between those rules and the free route: the pinned AV-006 instruction,
the strict two-key reply schema, the 20-second deadline with one quota-consuming retry, and
the binding of every suggestion to the transcript revision that produced it.

See [AV-024: AnkiDroid adapter and review safeguards](docs/testing/av024/results.md)
for the real scheduled-card provider, single-shot guarded writer, and per-rating
emulator evidence. The shell checks real deck readiness without submitting reviews.

See [AV-010: Card eligibility and bounded skipping](docs/testing/av010/results.md)
for VoiceQA and language validation, visible rejection reasons, and read-only
queue traversal that stops after five consecutive unstudiable cards.

See [AV-025: Mobile speech and audio routing](android/README.md#speech-transport) for the
speech transport: the pinned TTS and recognizer route, the app-owned microphone pipe, the
playback-to-capture ordering, and the cancellation and failure rules behind both AV-007
speech contracts. Its [runbook](docs/testing/av025/runbook.md) separates the offline rule
checks from the live check on the pinned AVD, and
[results](docs/testing/av025/results.md) records what has and has not been verified.

See [AV-013: Session state machine](android/README.md#session-state-machine) for the
deterministic turn loop: the explicit states, the token rules that reject late callbacks,
the invalidate-then-clean-up teardown, main-thread confinement, and the rule that only an
explicit learner confirmation reaches the writer. Its 53-scenario conformance suite is
ported from the Python binding and held to it by a drift guard. The
[runbook](docs/testing/av013/runbook.md) separates the offline suite from the live check
on the pinned AVD, and [results](docs/testing/av013/results.md) records what has and has
not been verified. AV-025's absorbed live speech check passed; the session's guarded
write after a spoken answer and explicit confirmation remains outstanding.
