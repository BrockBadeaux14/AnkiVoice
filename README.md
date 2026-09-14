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

See [AV-006: Speech and grading providers](docs/decisions/0006-speech-and-grading-providers.md)
for the measured native speech/OpenRouter comparison, free-only provider decision,
fallback requirements, and reproducible evidence.
