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
policy limits. Live capture and interruption safety remain gates for #13/#26.

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
